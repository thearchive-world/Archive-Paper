package archive;

import com.mojang.logging.LogUtils;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import archive.UuidDecoder.UuidResult;
import archive.UuidDecoder.UuidStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.levelgen.Heightmap;
import ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil;
import org.slf4j.Logger;

/**
 * Read-only NBT audit. Four dirt classes are measured:
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
 *   <li>{@code be-type-mismatch}: entries in {@code block_entities} whose
 *       stored {@code id} maps to a registered {@link BlockEntityType} whose
 *       {@code isValid(BlockState)} set does NOT include the block at the
 *       BE's coordinate. The block is itself a BE-carrier (so the
 *       {@code ghost-bes} predicate passes), it is just the wrong type. MC
 *       rejects on load with the same {@code IllegalStateException} format
 *       as {@code ghost-bes}; surfaced as 2x {@code minecraft:hopper} at
 *       {@code minecraft:beacon} + 1x {@code minecraft:dispenser} at
 *       {@code minecraft:dropper}.</li>
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
 *     i.e. "this block carries a BE at all". The {@code be-type-mismatch}
 *     check covers the complementary case (block carries a BE but of a
 *     different type than the BE's stored {@code id}); both classes throw
 *     the same {@code IllegalStateException("Invalid block entity ...")}
 *     under Paper's chunk-system load codec, so a healthy world reports
 *     zero on both.
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
    // Drive Paper's 60s watchdog at a steady cadence without spamming.
    private static final long WATCHDOG_TICK_INTERVAL_MILLIS = 5_000;
    // Same cadence as DirtNbtCleaner so audit progress logs look familiar.
    private static final long PROGRESS_LOG_INTERVAL_MILLIS = 30_000;
    // Populated at the top of run(). The worker thread reads it via a local
    // copy passed through auditLevel -> auditChunk. Static so we don't have to
    // thread it through every signature.
    private static volatile Set<String> blocksWithEntity = Set.of();
    // Maps each registered BE type id to the set of block names whose default
    // state passes BlockEntityType.isValid. Populated at the top of run().
    // Read by auditChunk to flag be-type-mismatch: a BE entry whose stored
    // id points at a registered type that does not accept the block actually
    // at the BE's coordinate.
    private static volatile Map<String, Set<String>> beIdToBlocks = Map.of();
    // Per-UUID occurrence counter. Populated by auditEntityList while walking
    // both region/ (lowercase "entities") and entities/ (capital "Entities").
    // End-of-walk reduces to sum(count - 1 where count > 1) = uuid-dups total.
    private static volatile ConcurrentHashMap<UUID, AtomicInteger> uuidCounts = new ConcurrentHashMap<>();

    private AuditDirtyChunks() {}

    public static void run(MinecraftServer server) {
        LOGGER.info("[The Archive] Starting dirty-chunk audit pass...");
        long start = System.currentTimeMillis();
        blocksWithEntity = BlockEntityRegistry.computeBlocksWithEntity();
        beIdToBlocks = BlockEntityRegistry.computeBeIdToBlocks();
        uuidCounts = new ConcurrentHashMap<>();
        Totals total = new Totals();
        Failures failures = new Failures();

        // Resume infrastructure: per-region progress markers at
        // .archive-audit-progress.txt, an aggregate Totals snapshot at
        // .archive-audit-totals.txt. Per-region key shape is
        // "<dim-identifier> <folder-name> <rx> <rz>" with folder-name in
        // {region, entities, poi} to track each of the audit's three
        // file-kind walks independently. A SIGKILL inside one kind's walk
        // re-walks only that file on resume; the prior kinds are skipped.
        //
        // Snapshot is written BEFORE the progress marker (atomic rename for
        // the totals; append for progress). SIGKILL between the two leaves
        // the region's contribution in the loaded totals on the next run
        // AND the region absent from progress, so it re-walks AND
        // re-accrues. This is the cleaner-shape bounded-drift behaviour
        // documented in feedback_resume_bounded_drift_invariant. Audit is
        // single-threaded (one worker), so the only drift source is the
        // SIGKILL window itself, not in-flight worker contributions.
        //
        // uuidCounts is intentionally NOT snapshotted. The map can reach
        // ~150M entries on the 1.1 TB main archive (~7.5 GB serialised);
        // a resumed run rebuilds uuidCounts from scratch using only the
        // not-yet-completed regions, so the summary's uuid-dups field
        // under-counts cross-region duplicates (a UUID present once in a
        // pre-kill-completed region and once in a resumed region is not
        // detected). On a post-cleaner fixture uuid-dups is 0 and the
        // under-count is moot; on a pre-cleaner fixture the operator
        // should treat resumed-run uuid-dups as a lower bound.
        Path progressFile = progressFilePath(server);
        Path totalsFile = totalsFilePath(server);
        Set<String> completed = readProgress(progressFile, failures);
        if (!completed.isEmpty()) {
            LOGGER.info("[The Archive] Resuming audit with {} regions previously marked complete", completed.size());
            Totals prior = readTotalsSnapshot(totalsFile, failures);
            if (prior != null) {
                LOGGER.info("[The Archive] Loaded prior audit totals: {} chunks, {} block entities, {} entities, {} bake-complete",
                            prior.chunks, prior.blockEntities, prior.entities, prior.bakeComplete);
                total.add(prior);
            }
        }

        // Starlight light-version observability for the operator. The audit's
        // bake-classification reads SaveUtil.STARLIGHT_LIGHT_VERSION directly
        // each run; if moonrise bumps the constant the first post-bump audit
        // reports every previously-baked chunk as bake-pending and that shows
        // up in the summary counters.
        LOGGER.info("[The Archive] Starlight version {} at audit start", SaveUtil.STARLIGHT_LIGHT_VERSION);

        // The audit walk is pure RegionFile NBT decode (IO-bound, no chunk-system
        // state). On a 12k-region world the synchronous walk blew past Paper's
        // 60s watchdog. Run
        // the walk on a worker; pump cache.pollTask() + WatchdogThread.tick() on
        // the tick thread while waiting.
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                for (ServerLevel level : server.getAllLevels()) {
                    auditLevel(level, total, completed, progressFile, totalsFile, failures);
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
                    LOGGER.error("[The Archive] pollTask failed during audit", t);
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
        long progressReadFailed = failures.progressReadFailed.get();
        long totalsWriteFailed = failures.totalsWriteFailed.get();
        LOGGER.info("[The Archive] Audit complete: {} chunks, {} block entities, {} ghost-bes, {} be-coord-mismatch, {} be-type-mismatch, {} entities, {} invalid-attrs, {} uuid-dups, {} legacy-chunks, {} bake-complete, {} bake-partial, {} bake-pending, {} bake-ineligible, {} poi-valid, {} poi-invalid, progress-read-failures={}, totals-write failures={} in {}s",
                    total.chunks, total.blockEntities, total.ghostBes, total.beCoordMismatch,
                    total.beTypeMismatch,
                    total.entities, total.invalidAttrs, uuidDups, total.legacyChunks,
                    total.bakeComplete, total.bakePartial, total.bakePending, total.bakeIneligible,
                    total.poiValidSections, total.poiInvalidSections,
                    progressReadFailed, totalsWriteFailed, elapsedSec);

        // Clean exit: remove the per-region progress markers and the
        // aggregate totals snapshot. The next audit run starts from zero.
        // A non-zero progressReadFailed at this point already logged in
        // readProgress; the delete failures here are tolerated (non-fatal
        // observability) since they leave stale files that the next run
        // either reads cleanly or fails-to-read with the same counter.
        try {
            Files.deleteIfExists(progressFile);
        } catch (IOException ex) {
            LOGGER.warn("[The Archive] Failed to delete audit progress file: {}", ex.getMessage());
        }
        try {
            Files.deleteIfExists(totalsFile);
        } catch (IOException ex) {
            LOGGER.warn("[The Archive] Failed to delete audit totals file: {}", ex.getMessage());
        }

        // Drop the populated map for GC. On the 1.1 TB main archive the audit
        // walks ~150M entities; the static reference would otherwise pin the
        // whole map for the JVM's lifetime after the pass returns. Reassigning
        // to an empty map (not null) keeps the field non-null so concurrent
        // accidental reads cannot NPE.
        uuidCounts = new ConcurrentHashMap<>();
    }

    private static void auditLevel(
        ServerLevel level, Totals total, Set<String> completed,
        Path progressFile, Path totalsFile, Failures failures
    ) {
        Identifier dim = level.dimension().identifier();
        Path dimPath = level.getServer().storageSource.getDimensionPath(level.dimension());
        String levelId = level.getServer().storageSource.getLevelId();
        Totals dimTotals = new Totals();

        Path regionFolder = dimPath.resolve("region");
        File[] regionFiles = regionFolder.toFile().listFiles((d, n) -> n.endsWith(".mca"));
        if (regionFiles == null) {
            LOGGER.info("[The Archive]   {}: no region/ folder", dim);
            return;
        }
        RegionStorageInfo chunkInfo = new RegionStorageInfo(levelId, level.dimension(), "chunk");
        ProgressTracker regionProgress = new ProgressTracker(regionFiles.length);
        for (File f : regionFiles) {
            Matcher m = REGION_FILE_REGEX.matcher(f.getName());
            if (!m.matches()) continue;
            int rx = Integer.parseInt(m.group(1));
            int rz = Integer.parseInt(m.group(2));
            String key = dim + " region " + rx + " " + rz;
            if (completed.contains(key)) {
                regionProgress.regionDone(dim, "region");
                continue;
            }
            int xOffset = rx << 5;
            int zOffset = rz << 5;
            Totals regionTotals = new Totals();
            try (RegionFile rf = new RegionFile(chunkInfo, f.toPath(), regionFolder, true)) {
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        ChunkPos pos = new ChunkPos(x + xOffset, z + zOffset);
                        if (!rf.doesChunkExist(pos)) continue;
                        ChunkAudit a = auditChunk(rf, pos);
                        if (a == null) continue;
                        regionTotals.chunks++;
                        regionTotals.blockEntities += a.blockEntityCount;
                        regionTotals.ghostBes += a.ghostBeCount;
                        regionTotals.beCoordMismatch += a.beCoordMismatchCount;
                        regionTotals.beTypeMismatch += a.beTypeMismatchCount;
                        regionTotals.entities += a.entityCount;
                        regionTotals.invalidAttrs += a.invalidAttrCount;
                        if (a.legacy) regionTotals.legacyChunks++;
                        switch (a.bakeStatus) {
                            case COMPLETE -> regionTotals.bakeComplete++;
                            case PARTIAL -> regionTotals.bakePartial++;
                            case PENDING -> regionTotals.bakePending++;
                            case INELIGIBLE -> regionTotals.bakeIneligible++;
                        }
                        regionProgress.chunkDone();
                    }
                }
            } catch (IOException ex) {
                LOGGER.error("[The Archive] Failed to audit {}: {}", f.toPath(), ex.toString());
            }
            dimTotals.add(regionTotals);
            total.add(regionTotals);
            writeTotalsSnapshot(totalsFile, total, failures);
            appendProgress(progressFile, key);
            completed.add(key);
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
                int rx = Integer.parseInt(m.group(1));
                int rz = Integer.parseInt(m.group(2));
                String key = dim + " entities " + rx + " " + rz;
                if (completed.contains(key)) {
                    entitiesProgress.regionDone(dim, "entities");
                    continue;
                }
                int xOffset = rx << 5;
                int zOffset = rz << 5;
                Totals regionTotals = new Totals();
                try (RegionFile rf = new RegionFile(entitiesInfo, f.toPath(), entitiesFolder, true)) {
                    for (int x = 0; x < 32; x++) {
                        for (int z = 0; z < 32; z++) {
                            ChunkPos pos = new ChunkPos(x + xOffset, z + zOffset);
                            if (!rf.doesChunkExist(pos)) continue;
                            EntityAudit ea = auditEntitiesChunk(rf, pos);
                            if (ea == null) continue;
                            regionTotals.entities += ea.entityCount;
                            regionTotals.invalidAttrs += ea.invalidAttrCount;
                            entitiesProgress.chunkDone();
                        }
                    }
                } catch (IOException ex) {
                    LOGGER.error("[The Archive] Failed to audit entities {}: {}", f.toPath(), ex.toString());
                }
                dimTotals.add(regionTotals);
                total.add(regionTotals);
                writeTotalsSnapshot(totalsFile, total, failures);
                appendProgress(progressFile, key);
                completed.add(key);
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
                int rx = Integer.parseInt(m.group(1));
                int rz = Integer.parseInt(m.group(2));
                String key = dim + " poi " + rx + " " + rz;
                if (completed.contains(key)) {
                    poiProgress.regionDone(dim, "poi");
                    continue;
                }
                int xOffset = rx << 5;
                int zOffset = rz << 5;
                Totals regionTotals = new Totals();
                try (RegionFile rf = new RegionFile(poiInfo, f.toPath(), poiFolder, true)) {
                    for (int x = 0; x < 32; x++) {
                        for (int z = 0; z < 32; z++) {
                            ChunkPos pos = new ChunkPos(x + xOffset, z + zOffset);
                            if (!rf.doesChunkExist(pos)) continue;
                            auditPoiChunk(rf, pos, regionTotals);
                            poiProgress.chunkDone();
                        }
                    }
                } catch (IOException ex) {
                    LOGGER.error("[The Archive] Failed to audit poi {}: {}", f.toPath(), ex.toString());
                }
                dimTotals.add(regionTotals);
                total.add(regionTotals);
                writeTotalsSnapshot(totalsFile, total, failures);
                appendProgress(progressFile, key);
                completed.add(key);
                poiProgress.regionDone(dim, "poi");
            }
        }

        LOGGER.info("[The Archive]   {}: {} chunks, {} block entities, {} ghost-bes, {} be-coord-mismatch, {} be-type-mismatch, {} entities, {} invalid-attrs, {} legacy-chunks, {} bake-complete, {} bake-partial, {} bake-pending, {} bake-ineligible, {} poi-valid, {} poi-invalid",
                    dim, dimTotals.chunks, dimTotals.blockEntities, dimTotals.ghostBes, dimTotals.beCoordMismatch,
                    dimTotals.beTypeMismatch,
                    dimTotals.entities, dimTotals.invalidAttrs, dimTotals.legacyChunks,
                    dimTotals.bakeComplete, dimTotals.bakePartial, dimTotals.bakePending, dimTotals.bakeIneligible,
                    dimTotals.poiValidSections, dimTotals.poiInvalidSections);
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
                              int beTypeMismatchCount,
                              int entityCount, int invalidAttrCount, boolean legacy,
                              BakeStatus bakeStatus) {}

    private record EntityAudit(int entityCount, int invalidAttrCount) {}

    /**
     * Four-state classification of {@code --bakeLight} progress on a single
     * post-1.18 chunk:
     * <ul>
     *   <li>{@code COMPLETE}: {@code isLightOn} present AND
     *       {@code starlight.light_version == STARLIGHT_LIGHT_VERSION}
     *       (Paper marks "fully lit" iff both, see {@code SerializableChunkData}
     *       and {@link SaveUtil#STARLIGHT_VERSION_TAG}); AND every key in
     *       {@code ChunkStatus.FULL.heightmapsAfter()} is present on the
     *       {@code Heightmaps} compound; AND {@code UpgradeData} is absent or
     *       carries no Indices, Sides, neighbor_block_ticks, neighbor_fluid_ticks
     *       (mirrors {@code BakeLightPass.hasUpgradeWork}).</li>
     *   <li>{@code PARTIAL}: at least one but not all of the above hold. Surfaces
     *       a chunk that the bake started on but didn't finish, or one whose
     *       markers got partially stripped by a later write.</li>
     *   <li>{@code PENDING}: none of the bake markers are set. Pre-1.18
     *       {@code Level}-wrapped chunks classify here unconditionally (they
     *       must run through {@code --upgradeChunks} before the bake can touch
     *       them).</li>
     *   <li>{@code INELIGIBLE}: the chunk has no {@code Status} string at root,
     *       so {@code SerializableChunkData.parse} returns null and the bake
     *       silently skips. The canonical case is the Bobby Fabric mod (a
     *       client-side render-distance extender) writing a minimal-NBT
     *       render-cache snapshot with sections + heightmaps but no Status,
     *       isLightOn, InhabitedTime, or LastUpdate. Classifying these
     *       separately keeps the {@code bake-partial} bucket reflective of
     *       chunks the bake actually tried to process.</li>
     * </ul>
     */
    private enum BakeStatus { COMPLETE, PARTIAL, PENDING, INELIGIBLE }

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
        if (legacy) return new ChunkAudit(0, 0, 0, 0, 0, 0, true, BakeStatus.PENDING);

        BakeStatus bakeStatus = classifyBakeStatus(root);

        // Embedded entities: post-DFU 1.21 stores them at root under lowercase
        // "entities" (mirroring "block_entities"). Split into entities/*.mca
        // on the first chunk-system load.
        EntityAudit ea = auditEntityList(root.getListOrEmpty("entities"));

        ListTag bes = root.getListOrEmpty("block_entities");
        if (bes.isEmpty()) return new ChunkAudit(0, 0, 0, 0, ea.entityCount, ea.invalidAttrCount, false, bakeStatus);

        Map<Integer, NbtSectionDecoder> sections = new HashMap<>();
        for (int i = 0; i < root.getListOrEmpty("sections").size(); i++) {
            CompoundTag sec = root.getListOrEmpty("sections").getCompoundOrEmpty(i);
            int sectionY = sec.getByteOr("Y", (byte) 0);
            NbtSectionDecoder dec = NbtSectionDecoder.from(sec);
            if (dec != null) sections.put(sectionY, dec);
        }

        Set<String> entityBlocks = blocksWithEntity;
        Map<String, Set<String>> beTypes = beIdToBlocks;
        int ghosts = 0;
        int coordMismatch = 0;
        int typeMismatch = 0;
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
            NbtSectionDecoder dec = sections.get(Math.floorDiv(y, 16));
            String name = dec == null ? null : dec.blockNameAt(x & 15, Math.floorMod(y, 16), z & 15);
            if (name == null || !entityBlocks.contains(name)) {
                ghosts++;
            } else {
                // be-type-mismatch: the block is itself a BE-carrier (so the
                // ghost-bes predicate above passed), but the BE's stored id
                // resolves to a registered BlockEntityType whose isValid set
                // does not include this specific block. MC's chunk-system
                // load codec rejects these with
                // IllegalStateException("Invalid block entity ...").
                // Unknown ids (no map entry) are skipped: those fall under a
                // separate concern (custom BE types from mods).
                String beId = be.getStringOr("id", "");
                if (!beId.isEmpty()) {
                    Set<String> allowed = beTypes.get(beId);
                    if (allowed != null && !allowed.contains(name)) {
                        typeMismatch++;
                    }
                }
            }
        }
        return new ChunkAudit(bes.size(), ghosts, coordMismatch, typeMismatch, ea.entityCount, ea.invalidAttrCount, false, bakeStatus);
    }

    /**
     * Classify the {@code --bakeLight} state of a single chunk from raw NBT.
     * Four markers are inspected; their combined state collapses to one of
     * {@link BakeStatus#COMPLETE}, {@link BakeStatus#PARTIAL},
     * {@link BakeStatus#PENDING}.
     *
     * The marker set tracks what {@code BakeLightPass} writes: the lit pair
     * ({@code isLightOn} and Starlight's version tag), all FULL-status
     * heightmaps, and a drained {@code UpgradeData}. Both halves of the lit
     * pair are required because Paper's Starlight gates "fully lit" on the
     * version match in addition to the legacy bool, so a chunk carrying only
     * one is genuinely half-baked.
     *
     * Emptiness for {@code UpgradeData} mirrors {@code BakeLightPass#hasUpgradeWork}
     * rather than {@code UpgradeData.isEmpty()}: the latter only inspects
     * Indices and Sides, missing chunks that still carry neighbour ticks.
     */
    private static BakeStatus classifyBakeStatus(CompoundTag root) {
        // Status absent (or wrong type) makes SerializableChunkData.parse
        // return null at line 145, so BakeLightPass.loadCenter silently skips
        // and the chunk never moves toward COMPLETE. Bobby mod cache shape:
        // sections + Heightmaps present but Status / isLightOn / InhabitedTime
        // / LastUpdate absent. Classify
        // first; the marker counts below are irrelevant on a chunk the bake
        // cannot even parse.
        if (root.getString("Status").isEmpty()) return BakeStatus.INELIGIBLE;
        boolean hasLightOn = root.get("isLightOn") != null;
        boolean hasLightVersion = root.getIntOr(SaveUtil.STARLIGHT_VERSION_TAG, -1) == SaveUtil.STARLIGHT_LIGHT_VERSION;
        CompoundTag heightmaps = root.getCompoundOrEmpty("Heightmaps");
        boolean hasAllHeightmaps = true;
        for (Heightmap.Types type : ChunkStatus.FULL.heightmapsAfter()) {
            if (heightmaps.get(type.getSerializationKey()) == null) {
                hasAllHeightmaps = false;
                break;
            }
        }
        boolean upgradeDataDrained = upgradeDataIsDrained(root);

        int markersSet = (hasLightOn ? 1 : 0)
                       + (hasLightVersion ? 1 : 0)
                       + (hasAllHeightmaps ? 1 : 0)
                       + (upgradeDataDrained ? 1 : 0);
        if (markersSet == 4) return BakeStatus.COMPLETE;
        // upgradeDataDrained is true for an absent UpgradeData (the bake's
        // expected end state), so a freshly-DFU'd chunk that has not yet been
        // baked typically has 1/4 markers set: drained-or-absent UpgradeData
        // but no light pair and no heightmaps. Distinguishing "0 markers" from
        // "1 marker (drained UpgradeData only)" would over-report PARTIAL on
        // every pre-bake chunk, so PENDING covers the pre-bake state.
        //
        // Deliberate tolerance: the audit aggregates absent-UpgradeData and
        // drained-UpgradeData chunks into the same bucket at every status
        // level. COMPLETE requires upgradeDataDrained=true and the lit pair +
        // heightmaps; both equivalent shapes pass through identically. PARTIAL
        // and PENDING likewise do not distinguish. A downstream consumer that
        // needs the absent-vs-drained distinction (e.g. to attribute a
        // post-bake PARTIAL chunk back to "bake replayed UpgradeData ticks"
        // vs "UpgradeData was absent on disk before the bake started") must
        // add a dedicated counter; the four BakeStatus buckets are
        // consumer-blind on this distinction by design.
        boolean anyBakeArtifact = hasLightOn || hasLightVersion || hasAllHeightmaps;
        return anyBakeArtifact ? BakeStatus.PARTIAL : BakeStatus.PENDING;
    }

    /**
     * Returns true if a chunk's {@code UpgradeData} carries no remaining
     * upgrade work for the bake to drain. Absent {@code UpgradeData} and a
     * drained-but-present {@code UpgradeData} both return true; callers that
     * need to distinguish those two shapes must inspect the root tag
     * themselves (see the consumer-blind note above {@link #classifyBakeStatus}).
     */
    private static boolean upgradeDataIsDrained(CompoundTag root) {
        CompoundTag ud = root.getCompoundOrEmpty("UpgradeData");
        if (ud.isEmpty()) return true;
        // Vanilla UpgradeData.write serializes Indices as a CompoundTag keyed
        // by stringified section index, not a ListTag. Reading it as a list
        // would always match empty on type mismatch and silently classify
        // every Indices-bearing chunk as bake-complete.
        if (!ud.getCompoundOrEmpty("Indices").isEmpty()) return false;
        // Sides is a single byte bitmask; non-zero means at least one side
        // still owes a wall update. Absence reads as 0 via getByteOr default.
        if (ud.getByteOr("Sides", (byte) 0) != 0) return false;
        if (!ud.getListOrEmpty("neighbor_block_ticks").isEmpty()) return false;
        if (!ud.getListOrEmpty("neighbor_fluid_ticks").isEmpty()) return false;
        return true;
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
            if (AttributeValidator.entityHasInvalidAttribute(entity)) invalid++;
            // Walk Passengers for UUID counting only; count/invalid intentionally
            // stay top-level. Mirrors {@code DirtNbtCleaner.stripDuplicatePassengers}
            // so a future regression in nested dedup shows up here.
            auditEntityUuids(entity);
        }
        return new EntityAudit(count, invalid);
    }

    private static void auditEntityUuids(CompoundTag entity) {
        UuidResult r = UuidDecoder.decodeUuid(entity);
        if (r.status() == UuidStatus.PRESENT_PARSED) {
            uuidCounts.computeIfAbsent(r.uuid(), k -> new AtomicInteger()).incrementAndGet();
        }
        ListTag passengers = entity.getListOrEmpty("Passengers");
        for (int i = 0; i < passengers.size(); i++) {
            auditEntityUuids(passengers.getCompoundOrEmpty(i));
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
        long beTypeMismatch;
        long entities;
        long invalidAttrs;
        long legacyChunks;
        // Bake-status counters: see {@link BakeStatus}. bake-pending dominates a
        // pre-bake post-DFU snapshot, bake-complete should fully account for
        // non-legacy chunks after --bakeLight, and a non-zero bake-partial on
        // a post-bake fixture is a real signal worth investigating.
        long bakeComplete;
        long bakePartial;
        long bakePending;
        long bakeIneligible;
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
            beTypeMismatch += other.beTypeMismatch;
            entities += other.entities;
            invalidAttrs += other.invalidAttrs;
            legacyChunks += other.legacyChunks;
            bakeComplete += other.bakeComplete;
            bakePartial += other.bakePartial;
            bakePending += other.bakePending;
            bakeIneligible += other.bakeIneligible;
            poiValidSections += other.poiValidSections;
            poiInvalidSections += other.poiInvalidSections;
        }
    }

    private static final class Failures {
        // Bumped from readProgress when the progress file is present but
        // Files.readAllLines throws. A non-zero count means resume started
        // from zero rather than the prior run's checkpoint, so the entire
        // pass was re-walked.
        final AtomicLong progressReadFailed = new AtomicLong();
        // Bumped from writeTotalsSnapshot when the temp-write or atomic-move
        // throws. A non-zero count means a SIGKILL after that region's
        // snapshot-attempt and before the next successful snapshot will
        // leave that region's contribution absent from the resumed totals
        // even though the region itself was completed; resume re-walks and
        // re-accrues the region's contribution.
        final AtomicLong totalsWriteFailed = new AtomicLong();
    }

    private static Path progressFilePath(MinecraftServer server) {
        return server.storageSource.getLevelDirectory().path()
            .resolve(".archive-audit-progress.txt");
    }

    private static Path totalsFilePath(MinecraftServer server) {
        return server.storageSource.getLevelDirectory().path()
            .resolve(".archive-audit-totals.txt");
    }

    private static Set<String> readProgress(Path path, Failures failures) {
        if (!Files.exists(path)) return ConcurrentHashMap.newKeySet();
        try {
            Set<String> set = ConcurrentHashMap.newKeySet();
            for (String line : Files.readAllLines(path)) {
                if (!line.isBlank() && !line.startsWith("#")) set.add(line.trim());
            }
            return set;
        } catch (IOException ex) {
            LOGGER.error("[The Archive] Failed to read audit progress file {}: {}", path, ex.toString());
            failures.progressReadFailed.incrementAndGet();
            return ConcurrentHashMap.newKeySet();
        }
    }

    private static synchronized void appendProgress(Path path, String entry) {
        try {
            Files.writeString(path, entry + "\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ex) {
            LOGGER.warn("[The Archive] Failed to append audit progress marker {}: {}", entry, ex.toString());
        }
    }

    private static synchronized void writeTotalsSnapshot(Path path, Totals totals, Failures failures) {
        try {
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            List<String> lines = new ArrayList<>(16);
            lines.add("chunks=" + totals.chunks);
            lines.add("blockEntities=" + totals.blockEntities);
            lines.add("ghostBes=" + totals.ghostBes);
            lines.add("beCoordMismatch=" + totals.beCoordMismatch);
            lines.add("beTypeMismatch=" + totals.beTypeMismatch);
            lines.add("entities=" + totals.entities);
            lines.add("invalidAttrs=" + totals.invalidAttrs);
            lines.add("legacyChunks=" + totals.legacyChunks);
            lines.add("bakeComplete=" + totals.bakeComplete);
            lines.add("bakePartial=" + totals.bakePartial);
            lines.add("bakePending=" + totals.bakePending);
            lines.add("bakeIneligible=" + totals.bakeIneligible);
            lines.add("poiValidSections=" + totals.poiValidSections);
            lines.add("poiInvalidSections=" + totals.poiInvalidSections);
            Files.write(tmp, lines);
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            LOGGER.warn("[The Archive] Failed to write audit totals snapshot {}: {}", path, ex.toString());
            failures.totalsWriteFailed.incrementAndGet();
        }
    }

    private static Totals readTotalsSnapshot(Path path, Failures failures) {
        if (!Files.exists(path)) return null;
        try {
            Map<String, Long> values = new HashMap<>();
            for (String line : Files.readAllLines(path)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                String k = trimmed.substring(0, eq);
                long v;
                try { v = Long.parseLong(trimmed.substring(eq + 1)); }
                catch (NumberFormatException ex) { continue; }
                values.put(k, v);
            }
            Totals t = new Totals();
            t.chunks            = values.getOrDefault("chunks", 0L);
            t.blockEntities     = values.getOrDefault("blockEntities", 0L);
            t.ghostBes          = values.getOrDefault("ghostBes", 0L);
            t.beCoordMismatch   = values.getOrDefault("beCoordMismatch", 0L);
            t.beTypeMismatch    = values.getOrDefault("beTypeMismatch", 0L);
            t.entities          = values.getOrDefault("entities", 0L);
            t.invalidAttrs      = values.getOrDefault("invalidAttrs", 0L);
            t.legacyChunks      = values.getOrDefault("legacyChunks", 0L);
            t.bakeComplete      = values.getOrDefault("bakeComplete", 0L);
            t.bakePartial       = values.getOrDefault("bakePartial", 0L);
            t.bakePending       = values.getOrDefault("bakePending", 0L);
            t.bakeIneligible    = values.getOrDefault("bakeIneligible", 0L);
            t.poiValidSections  = values.getOrDefault("poiValidSections", 0L);
            t.poiInvalidSections = values.getOrDefault("poiInvalidSections", 0L);
            return t;
        } catch (IOException ex) {
            LOGGER.error("[The Archive] Failed to read audit totals snapshot {}: {}", path, ex.toString());
            failures.progressReadFailed.incrementAndGet();
            return null;
        }
    }
}
