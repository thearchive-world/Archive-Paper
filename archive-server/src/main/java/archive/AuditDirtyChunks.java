package archive;

import com.mojang.logging.LogUtils;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.slf4j.Logger;

/**
 * Read-only NBT audit. Three dirt classes are measured:
 * <ul>
 *   <li>{@code ghost-bes}: entries in {@code block_entities} sitting at a
 *       coordinate whose stored block does not carry a block entity, i.e. its
 *       {@link Block} is not an {@link EntityBlock}. Matches the runtime guard
 *       at {@code LevelChunk.setBlockEntity} (silently dropped on load)
 *       and the chunk-system load codec, which throws
 *       {@code IllegalStateException("Invalid block entity ... state at
 *       BlockPos{...}, got Block{...}")} for these.</li>
 *   <li>{@code be-coord-mismatch}: entries in {@code block_entities} whose
 *       stored {@code (x, y, z)} chunk-coords disagree with the holding
 *       chunk's coords. Paper logs WARN "found in a wrong chunk, expected
 *       position from chunk [...]" and drops the BE on load.</li>
 *   <li>{@code invalid-attrs}: entities whose {@code attributes[*].id} or
 *       {@code attributes[*].modifiers[*].id} fails {@link Identifier#tryParse}.
 *       Catches legacy mod-attribute taint like {@code forge.swimSpeed} that
 *       survives DFU but is dropped on a chunk-system load (the codec rejects
 *       the entry). Walks embedded {@code Entities} in {@code region/*.mca}
 *       (pre-1.17 layout, still present on DFU'd 1.21 chunks that haven't been
 *       chunk-system-loaded) AND split entities in {@code entities/*.mca}
 *       (post-1.17 layout, created on chunk-system normalize).</li>
 * </ul>
 *
 * Used for idempotency and regression verification via
 * the NBT-diff approach (count dirt classes directly from NBT, not from
 * runtime log signatures).
 *
 * Limitations the operator should know:
 *   - Only audits chunks at the post-1.16 paletted-container schema. Pre-1.16
 *     chunks (legacy 8-bit blocks + NibbleArray data) decode to a {@code null}
 *     palette name and are conservatively counted as ghost; for pre-1.16
 *     sources, run direct-NBT upgrade first to bring them to current data
 *     version, then audit.
 *   - The {@code ghost-bes} check is the {@code hasBlockEntity()} predicate,
 *     i.e. "this block carries a BE at all". It does NOT catch a type-mismatch
 *     case like a chest BE on a hopper block (both carry BEs, but of different
 *     types). That class only emits the muted WARN at chunk-system load, not
 *     the fatal codec {@code IllegalStateException}, so for our purposes the
 *     stronger predicate is unnecessary; the cleaner mirrors the same
 *     predicate.
 *   - "invalid-attrs" only inspects the post-DFU 1.21 attribute schema
 *     ({@code attributes:[{id,base,modifiers:[{id,amount,operation}]}]}). The
 *     legacy 1.12.2 shape ({@code Attributes:[{Name,Base,Modifiers:[...]}]})
 *     is not directly scanned; vanilla legacy names like {@code generic.maxHealth}
 *     fail {@link Identifier#tryParse} the same way Forge taint does, so the
 *     validator would over-report. {@code Level}-wrapped (pre-1.18) chunks are
 *     instead counted under {@code legacy-chunks}. On this archive that count
 *     is the upper bound on "chunks that may still carry Forge taint": only
 *     1.12.2-era player downloads ran a Forge client, and 1.12.2 chunks are
 *     exactly the Level-wrapped ones; post-1.12.2 downloads used Fabric and
 *     ship in 1.13+ flat NBT (so a post-DFU chunk with 0 invalid-attrs is
 *     genuinely clean). When {@code legacy-chunks} is non-zero, run
 *     {@code --upgradeChunks} to DFU them, then re-audit for the actual count.
 */
public final class AuditDirtyChunks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern REGION_FILE_REGEX =
        Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca$");
    private static final String AIR = "minecraft:air";
    // Drive Paper's 60s watchdog at a steady cadence without spamming.
    private static final long WATCHDOG_TICK_INTERVAL_MILLIS = 5_000;
    // Same cadence as DirtNbtCleaner so audit progress logs look familiar.
    private static final long PROGRESS_LOG_INTERVAL_MILLIS = 30_000;
    // Populated at the top of run(). The worker thread reads it via a local
    // copy passed through auditLevel -> auditChunk. Static so we don't have to
    // thread it through every signature.
    private static volatile Set<String> blocksWithEntity = Set.of();
    // Per-UUID occurrence counter. Populated by auditEntityList while walking
    // both region/ (lowercase "entities") and entities/ (capital "Entities").
    // End-of-walk reduces to sum(count - 1 where count > 1) = uuid-dups total.
    private static volatile ConcurrentHashMap<UUID, AtomicInteger> uuidCounts = new ConcurrentHashMap<>();

    private AuditDirtyChunks() {}

    public static void run(MinecraftServer server) {
        LOGGER.info("[The Archive] Starting dirty-chunk audit pass...");
        long start = System.currentTimeMillis();
        blocksWithEntity = computeBlocksWithEntity();
        uuidCounts = new ConcurrentHashMap<>();
        Totals total = new Totals();

        // The audit walk is pure RegionFile NBT decode (IO-bound, no chunk-system
        // state). On a 12k-region world the synchronous walk blew past Paper's
        // 60s watchdog. Run
        // the walk on a worker; pump cache.pollTask() + WatchdogThread.tick() on
        // the tick thread while waiting.
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                for (ServerLevel level : server.getAllLevels()) {
                    Totals dim = auditLevel(level);
                    total.add(dim);
                }
            } catch (Throwable t) {
                LOGGER.error("[The Archive] Audit worker failed: {}", t.toString(), t);
            } finally {
                done.countDown();
            }
        }, "archive-audit-worker");
        worker.setDaemon(true);
        worker.start();

        long lastWatchdogTick = System.currentTimeMillis();
        while (done.getCount() > 0) {
            boolean polled = false;
            for (ServerLevel level : server.getAllLevels()) {
                try {
                    polled |= level.getChunkSource().pollTask();
                } catch (Throwable t) {
                    LOGGER.error("[The Archive] pollTask failed during audit: {}", t.toString());
                }
            }
            long now = System.currentTimeMillis();
            if (now - lastWatchdogTick >= WATCHDOG_TICK_INTERVAL_MILLIS) {
                org.spigotmc.WatchdogThread.tick();
                lastWatchdogTick = now;
            }
            if (!polled) {
                LockSupport.parkNanos(1_000_000L);
            }
        }
        try {
            worker.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        long elapsedSec = (System.currentTimeMillis() - start) / 1000;
        long uuidDups = uuidCounts.values().stream()
            .mapToLong(c -> c.get() - 1)
            .filter(n -> n > 0)
            .sum();
        LOGGER.info("[The Archive] Audit complete: {} chunks, {} block entities, {} ghost-bes, {} be-coord-mismatch, {} entities, {} invalid-attrs, {} uuid-dups, {} legacy-chunks, {} poi-valid, {} poi-invalid in {}s",
                    total.chunks, total.blockEntities, total.ghostBes, total.beCoordMismatch,
                    total.entities, total.invalidAttrs, uuidDups, total.legacyChunks,
                    total.poiValidSections, total.poiInvalidSections, elapsedSec);
    }

    /**
     * Snapshots the registered blocks that produce a {@link Block} implementing
     * {@link EntityBlock}. {@code BlockState.hasBlockEntity()} reduces to
     * {@code block instanceof EntityBlock}, so a name-only set is sufficient
     * for the audit walk.
     */
    private static Set<String> computeBlocksWithEntity() {
        Set<String> set = new HashSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof EntityBlock) {
                Identifier id = BuiltInRegistries.BLOCK.getKey(block);
                if (id != null) set.add(id.toString());
            }
        }
        return Set.copyOf(set);
    }

    private static Totals auditLevel(ServerLevel level) {
        Identifier dim = level.dimension().identifier();
        Path dimPath = level.getServer().storageSource.getDimensionPath(level.dimension());
        String levelId = level.getServer().storageSource.getLevelId();
        Totals dimTotals = new Totals();

        Path regionFolder = dimPath.resolve("region");
        File[] regionFiles = regionFolder.toFile().listFiles((d, n) -> n.endsWith(".mca"));
        if (regionFiles == null) {
            LOGGER.info("[The Archive]   {}: no region/ folder", dim);
            return dimTotals;
        }
        RegionStorageInfo chunkInfo = new RegionStorageInfo(levelId, level.dimension(), "chunk");
        ProgressTracker regionProgress = new ProgressTracker(regionFiles.length);
        for (File f : regionFiles) {
            Matcher m = REGION_FILE_REGEX.matcher(f.getName());
            if (!m.matches()) continue;
            int xOffset = Integer.parseInt(m.group(1)) << 5;
            int zOffset = Integer.parseInt(m.group(2)) << 5;
            try (RegionFile rf = new RegionFile(chunkInfo, f.toPath(), regionFolder, true)) {
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        ChunkPos pos = new ChunkPos(x + xOffset, z + zOffset);
                        if (!rf.doesChunkExist(pos)) continue;
                        ChunkAudit a = auditChunk(rf, pos);
                        if (a == null) continue;
                        dimTotals.chunks++;
                        dimTotals.blockEntities += a.blockEntityCount;
                        dimTotals.ghostBes += a.ghostBeCount;
                        dimTotals.beCoordMismatch += a.beCoordMismatchCount;
                        dimTotals.entities += a.entityCount;
                        dimTotals.invalidAttrs += a.invalidAttrCount;
                        if (a.legacy) dimTotals.legacyChunks++;
                        regionProgress.chunkDone();
                    }
                }
            } catch (IOException ex) {
                LOGGER.error("[The Archive] Failed to audit {}: {}", f.toPath(), ex.toString());
            }
            regionProgress.regionDone(dim, "region");
        }

        // Post-1.17 layout: entities split into entities/*.mca. Folder is
        // absent on sources whose chunks have not been split yet; absence is not an error.
        Path entitiesFolder = dimPath.resolve("entities");
        File[] entityFiles = entitiesFolder.toFile().listFiles((d, n) -> n.endsWith(".mca"));
        if (entityFiles != null) {
            RegionStorageInfo entitiesInfo = new RegionStorageInfo(levelId, level.dimension(), "entities");
            ProgressTracker entitiesProgress = new ProgressTracker(entityFiles.length);
            for (File f : entityFiles) {
                Matcher m = REGION_FILE_REGEX.matcher(f.getName());
                if (!m.matches()) continue;
                int xOffset = Integer.parseInt(m.group(1)) << 5;
                int zOffset = Integer.parseInt(m.group(2)) << 5;
                try (RegionFile rf = new RegionFile(entitiesInfo, f.toPath(), entitiesFolder, true)) {
                    for (int x = 0; x < 32; x++) {
                        for (int z = 0; z < 32; z++) {
                            ChunkPos pos = new ChunkPos(x + xOffset, z + zOffset);
                            if (!rf.doesChunkExist(pos)) continue;
                            EntityAudit ea = auditEntitiesChunk(rf, pos);
                            if (ea == null) continue;
                            dimTotals.entities += ea.entityCount;
                            dimTotals.invalidAttrs += ea.invalidAttrCount;
                            entitiesProgress.chunkDone();
                        }
                    }
                } catch (IOException ex) {
                    LOGGER.error("[The Archive] Failed to audit entities {}: {}", f.toPath(), ex.toString());
                }
                entitiesProgress.regionDone(dim, "entities");
            }
        }

        // poi/*.mca layout: holds Valid:true sections written by the runtime
        // on first chunk load (a chunk-system FULL transition). Counters
        // document disk state; on a post-pipeline world both are typically 0
        // since the runtime materialises poi/*.mca lazily on visit. A non-zero
        // poi-invalid flags DataVersion drift on whatever poi/ content exists.
        Path poiFolder = dimPath.resolve("poi");
        File[] poiFiles = poiFolder.toFile().listFiles((d, n) -> n.endsWith(".mca"));
        if (poiFiles != null) {
            RegionStorageInfo poiInfo = new RegionStorageInfo(levelId, level.dimension(), "poi");
            ProgressTracker poiProgress = new ProgressTracker(poiFiles.length);
            for (File f : poiFiles) {
                Matcher m = REGION_FILE_REGEX.matcher(f.getName());
                if (!m.matches()) continue;
                int xOffset = Integer.parseInt(m.group(1)) << 5;
                int zOffset = Integer.parseInt(m.group(2)) << 5;
                try (RegionFile rf = new RegionFile(poiInfo, f.toPath(), poiFolder, true)) {
                    for (int x = 0; x < 32; x++) {
                        for (int z = 0; z < 32; z++) {
                            ChunkPos pos = new ChunkPos(x + xOffset, z + zOffset);
                            if (!rf.doesChunkExist(pos)) continue;
                            auditPoiChunk(rf, pos, dimTotals);
                            poiProgress.chunkDone();
                        }
                    }
                } catch (IOException ex) {
                    LOGGER.error("[The Archive] Failed to audit poi {}: {}", f.toPath(), ex.toString());
                }
                poiProgress.regionDone(dim, "poi");
            }
        }

        LOGGER.info("[The Archive]   {}: {} chunks, {} block entities, {} ghost-bes, {} be-coord-mismatch, {} entities, {} invalid-attrs, {} legacy-chunks, {} poi-valid, {} poi-invalid",
                    dim, dimTotals.chunks, dimTotals.blockEntities, dimTotals.ghostBes, dimTotals.beCoordMismatch,
                    dimTotals.entities, dimTotals.invalidAttrs, dimTotals.legacyChunks,
                    dimTotals.poiValidSections, dimTotals.poiInvalidSections);
        return dimTotals;
    }

    private static void auditPoiChunk(RegionFile rf, ChunkPos pos, Totals totals) {
        try (DataInputStream in = rf.getChunkDataInputStream(pos)) {
            if (in == null) return;
            CompoundTag root = NbtIo.read(in);
            CompoundTag secMap = root.getCompoundOrEmpty("Sections");
            for (String key : secMap.keySet()) {
                CompoundTag sec = secMap.getCompoundOrEmpty(key);
                if (sec.getBooleanOr("Valid", false)) totals.poiValidSections++;
                else totals.poiInvalidSections++;
            }
        } catch (IOException ex) {
            LOGGER.error("[The Archive] Failed to audit poi chunk {}: {}", pos, ex.toString());
        }
    }

    private record ChunkAudit(int blockEntityCount, int ghostBeCount, int beCoordMismatchCount,
                              int entityCount, int invalidAttrCount, boolean legacy) {}

    private record EntityAudit(int entityCount, int invalidAttrCount) {}

    private static ChunkAudit auditChunk(RegionFile rf, ChunkPos pos) {
        CompoundTag root;
        try (DataInputStream in = rf.getChunkDataInputStream(pos)) {
            if (in == null) return null;
            root = NbtIo.read(in);
        } catch (IOException ex) {
            LOGGER.error("[The Archive] Failed to read chunk {}: {}", pos, ex.toString());
            return null;
        }

        // Pre-1.18 chunks wrap everything in a "Level" compound. Their entity
        // and attribute fields use legacy capital names whose vanilla values
        // (e.g. "generic.maxHealth") fail Identifier.tryParse the same way
        // Forge taint does, so we can't validate them; just flag the chunk as
        // legacy so the operator knows the invalid-attrs total is a lower
        // bound. DFU'ing first (--upgradeChunks) lifts them to the schema we
        // can validate.
        boolean legacy = root.getCompound("Level").isPresent();
        if (legacy) return new ChunkAudit(0, 0, 0, 0, 0, true);

        // Embedded entities: post-DFU 1.21 stores them at root under lowercase
        // "entities" (mirroring "block_entities"). Split into entities/*.mca
        // on the first chunk-system load.
        EntityAudit ea = auditEntityList(root.getListOrEmpty("entities"));

        ListTag bes = root.getListOrEmpty("block_entities");
        if (bes.isEmpty()) return new ChunkAudit(0, 0, 0, ea.entityCount, ea.invalidAttrCount, false);

        Map<Integer, SectionDecoder> sections = new HashMap<>();
        for (int i = 0; i < root.getListOrEmpty("sections").size(); i++) {
            CompoundTag sec = root.getListOrEmpty("sections").getCompoundOrEmpty(i);
            int sectionY = sec.getByteOr("Y", (byte) 0);
            SectionDecoder dec = SectionDecoder.from(sec);
            if (dec != null) sections.put(sectionY, dec);
        }

        Set<String> entityBlocks = blocksWithEntity;
        int ghosts = 0;
        int coordMismatch = 0;
        for (int i = 0; i < bes.size(); i++) {
            CompoundTag be = bes.getCompoundOrEmpty(i);
            int x = be.getIntOr("x", 0);
            int y = be.getIntOr("y", 0);
            int z = be.getIntOr("z", 0);
            // be-coord-mismatch: the BE's stored coords belong in a different
            // chunk than the one holding it. Paper logs WARN "found in a
            // wrong chunk, expected position from chunk [...]" and drops the
            // BE on load. Check this
            // before the palette lookup; the (x & 15, z & 15) below would
            // hit the wrong chunk's block grid and mask the bug.
            if ((x >> 4) != pos.x() || (z >> 4) != pos.z()) {
                coordMismatch++;
                continue;
            }
            SectionDecoder dec = sections.get(Math.floorDiv(y, 16));
            String name = dec == null ? null : dec.blockNameAt(x & 15, Math.floorMod(y, 16), z & 15);
            if (name == null || !entityBlocks.contains(name)) {
                ghosts++;
            }
        }
        return new ChunkAudit(bes.size(), ghosts, coordMismatch, ea.entityCount, ea.invalidAttrCount, false);
    }

    private static EntityAudit auditEntitiesChunk(RegionFile rf, ChunkPos pos) {
        CompoundTag root;
        try (DataInputStream in = rf.getChunkDataInputStream(pos)) {
            if (in == null) return null;
            root = NbtIo.read(in);
        } catch (IOException ex) {
            LOGGER.error("[The Archive] Failed to read entities chunk {}: {}", pos, ex.toString());
            return null;
        }
        // Split entities/*.mca uses capital "Entities" (see EntityStorage.ENTITIES_TAG).
        return auditEntityList(root.getListOrEmpty("Entities"));
    }

    /**
     * Counts entities and flags any whose {@code attributes[*].id} or
     * {@code attributes[*].modifiers[*].id} fails {@link Identifier#tryParse}.
     * An entity with no {@code attributes} tag, or with an empty list, is not
     * dirt; legitimate entities frequently carry no attributes. Dirt requires
     * a present-and-non-validating id (the codec rejects on parse failure, so
     * Paper drops the attribute on the next chunk-system load).
     */
    private static EntityAudit auditEntityList(ListTag entities) {
        if (entities.isEmpty()) return new EntityAudit(0, 0);
        int count = 0;
        int invalid = 0;
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entity = entities.getCompoundOrEmpty(i);
            count++;
            if (entityHasInvalidAttribute(entity)) invalid++;
            // Walk Passengers for UUID counting only; count/invalid intentionally
            // stay top-level. Mirrors {@code DirtNbtCleaner.stripDuplicatePassengers}
            // so a future regression in nested dedup shows up here.
            auditEntityUuids(entity);
        }
        return new EntityAudit(count, invalid);
    }

    private static void auditEntityUuids(CompoundTag entity) {
        UuidResult r = decodeUuid(entity);
        if (r.status() == UuidStatus.PRESENT_PARSED) {
            uuidCounts.computeIfAbsent(r.uuid(), k -> new AtomicInteger()).incrementAndGet();
        }
        ListTag passengers = entity.getListOrEmpty("Passengers");
        for (int i = 0; i < passengers.size(); i++) {
            auditEntityUuids(passengers.getCompoundOrEmpty(i));
        }
    }

    private enum UuidStatus { ABSENT, PRESENT_PARSED, PRESENT_MALFORMED }

    private record UuidResult(UuidStatus status, UUID uuid) {
        static final UuidResult ABSENT = new UuidResult(UuidStatus.ABSENT, null);
        static final UuidResult MALFORMED = new UuidResult(UuidStatus.PRESENT_MALFORMED, null);
        static UuidResult parsed(UUID uuid) {
            return new UuidResult(UuidStatus.PRESENT_PARSED, uuid);
        }
    }

    private static UuidResult decodeUuid(CompoundTag entity) {
        Tag tag = entity.get("UUID");
        if (tag == null) return UuidResult.ABSENT;
        if (!(tag instanceof IntArrayTag intArrayTag)) return UuidResult.MALFORMED;
        int[] array = intArrayTag.getAsIntArray();
        if (array.length != 4) return UuidResult.MALFORMED;
        return UuidResult.parsed(UUIDUtil.uuidFromIntArray(array));
    }

    private static boolean entityHasInvalidAttribute(CompoundTag entity) {
        ListTag attrs = entity.getListOrEmpty("attributes");
        for (int i = 0; i < attrs.size(); i++) {
            CompoundTag attr = attrs.getCompoundOrEmpty(i);
            if (isInvalidResourceLocation(attr.getStringOr("id", ""))) return true;
            ListTag mods = attr.getListOrEmpty("modifiers");
            for (int j = 0; j < mods.size(); j++) {
                if (isInvalidResourceLocation(mods.getCompoundOrEmpty(j).getStringOr("id", ""))) return true;
            }
        }
        return false;
    }

    private static boolean isInvalidResourceLocation(String id) {
        // Empty / missing id is not the dirt we're measuring (it would fail the
        // codec for a different reason). Only flag a present id that fails the
        // ResourceLocation parser, matching what Paper's codec does on load.
        return !id.isEmpty() && Identifier.tryParse(id) == null;
    }

    /**
     * Decodes a 1.16+ paletted-container section. Bits-per-entry is
     * {@code max(4, ceil(log2(palette.size())))}, indices are packed into the
     * {@code data} long array with no straddling: each long holds
     * {@code 64 / bitsPerEntry} indices. A palette of size 1 omits the data
     * field entirely; the implicit index is 0. Block index for local
     * (x, y, z) in [0, 16) follows {@code Strategy.BLOCK_STATES.getIndex}:
     * {@code ((y * 16) + z) * 16 + x} (YZX order).
     */
    private record SectionDecoder(List<String> palette, long[] data, int bitsPerEntry) {
        static SectionDecoder from(CompoundTag section) {
            CompoundTag bs = section.getCompoundOrEmpty("block_states");
            ListTag pal = bs.getListOrEmpty("palette");
            if (pal.isEmpty()) return null;
            List<String> names = new ArrayList<>(pal.size());
            for (int i = 0; i < pal.size(); i++) {
                names.add(pal.getCompoundOrEmpty(i).getStringOr("Name", AIR));
            }
            long[] data = bs.getLongArray("data").orElse(new long[0]);
            int bits = names.size() <= 1 ? 0
                : Math.max(4, 32 - Integer.numberOfLeadingZeros(names.size() - 1));
            return new SectionDecoder(names, data, bits);
        }

        String blockNameAt(int x, int y, int z) {
            if (bitsPerEntry == 0) return palette.get(0);
            if (data.length == 0) return null;
            int flatIndex = (y * 16 + z) * 16 + x;
            int indicesPerLong = 64 / bitsPerEntry;
            int longIndex = flatIndex / indicesPerLong;
            if (longIndex >= data.length) return null;
            int bitOffset = (flatIndex % indicesPerLong) * bitsPerEntry;
            long mask = (1L << bitsPerEntry) - 1;
            int paletteIndex = (int) ((data[longIndex] >>> bitOffset) & mask);
            if (paletteIndex < 0 || paletteIndex >= palette.size()) return null;
            return palette.get(paletteIndex);
        }
    }

    /**
     * Periodic progress logger for a single audit folder pass. Tracks regions
     * done vs total and chunks walked, emits a log line at most every
     * {@link #PROGRESS_LOG_INTERVAL_MILLIS}. Single-thread only; the audit
     * worker is one thread, so no atomics. Mirrors the per-folder log shape
     * {@code DirtNbtCleaner.processFolder} emits.
     */
    private static final class ProgressTracker {
        private final int totalRegions;
        private final long startMillis;
        private long lastLogMillis;
        private int regionsDone;
        private long chunksDone;

        ProgressTracker(int totalRegions) {
            this.totalRegions = totalRegions;
            this.startMillis = System.currentTimeMillis();
            this.lastLogMillis = this.startMillis;
        }

        void chunkDone() { chunksDone++; }

        void regionDone(Identifier dim, String folderName) {
            regionsDone++;
            long now = System.currentTimeMillis();
            if (now - lastLogMillis < PROGRESS_LOG_INTERVAL_MILLIS) return;
            lastLogMillis = now;
            long rate = chunksDone * 1000L / Math.max(1, now - startMillis);
            LOGGER.info("[The Archive]   {} ({}): {} / {} regions ({} chunks, {} ch/s)",
                        dim, folderName, regionsDone, totalRegions, chunksDone, rate);
        }
    }

    private static final class Totals {
        long chunks;
        long blockEntities;
        long ghostBes;
        long beCoordMismatch;
        long entities;
        long invalidAttrs;
        long legacyChunks;
        // POI section counters, populated by the poi/ pass. Document disk
        // state of poi/*.mca (written by a runtime chunk-system FULL
        // transition); both are typically 0 on a post-pipeline world since
        // the runtime materialises poi/ lazily on chunk load. poi-invalid
        // flags DataVersion drift on whatever poi/ content exists.
        long poiValidSections;
        long poiInvalidSections;
        void add(Totals other) {
            chunks += other.chunks;
            blockEntities += other.blockEntities;
            ghostBes += other.ghostBes;
            beCoordMismatch += other.beCoordMismatch;
            entities += other.entities;
            invalidAttrs += other.invalidAttrs;
            legacyChunks += other.legacyChunks;
            poiValidSections += other.poiValidSections;
            poiInvalidSections += other.poiInvalidSections;
        }
    }
}
