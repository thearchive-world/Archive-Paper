package archive;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.logging.LogUtils;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.slf4j.Logger;

/**
 * Standalone post-DFU pass that strips the dirt classes {@link AuditDirtyChunks}
 * measures (invalid-attrs, ghost-bes, BE-coord-mismatch, be-type-mismatch)
 * directly from the region NBT, in parallel, with no chunk-system load.
 * Silences the codec WARN spam these dirt classes produce at runtime serve
 * time.
 *
 * <p>Run AFTER {@code --upgradeChunks} (DFU), never on raw 1.12.2: vanilla
 * legacy attribute names (e.g. {@code generic.maxHealth}) fail the same
 * {@link Identifier#tryParse} parser the Forge-taint detector uses and would
 * over-report wildly on {@code Level}-wrapped (pre-1.18) chunks. Such chunks
 * are skipped and counted under {@code legacy-chunks}; run {@code --upgradeChunks}
 * first to lift them.
 *
 * <p>Four strips:
 * <ul>
 *   <li>{@code invalid-attrs}: when ANY {@code attributes[*].id} or
 *       {@code attributes[*].modifiers[*].id} fails {@code Identifier.tryParse},
 *       drop the entire {@code attributes} tag from that entity. Mirrors the
 *       vanilla codec semantic, which wraps the whole list in one DataResult
 *       and discards it on parse failure (the mob then loads with default
 *       attribute base values). Walks embedded {@code entities} (lowercase)
 *       in {@code region/*.mca} AND split {@code Entities} (capital) in
 *       {@code entities/*.mca}.</li>
 *   <li>{@code ghost-bes}: drop entries from {@code block_entities} whose
 *       stored palette block at {@code (x, y, z)} does not carry a block
 *       entity, i.e. its {@link Block} is not an {@link EntityBlock}. This is
 *       the same predicate the runtime guard at {@code LevelChunk.setBlockEntity}
 *       uses to drop ghost BEs. The chunk-system load
 *       codec throws {@code IllegalStateException("Invalid block entity ...
 *       state at BlockPos{...}, got Block{...}")} for these and wedges
 *       normalize. Subsumes the narrower {@code air-orphan} subset (air has
 *       no block entity). {@code region/*.mca} only.</li>
 *   <li>{@code be-coord-mismatch}: drop entries from {@code block_entities}
 *       whose stored {@code (x, y, z)} chunk-coords differ from the holding
 *       chunk's coords. Paper's load path logs WARN "found in a wrong chunk,
 *       expected position from chunk [...]" and drops the BE anyway, so
 *       stripping at upgrade time is silent and strictly equivalent.
 *       {@code region/*.mca} only.</li>
 *   <li>{@code be-type-mismatch}: drop entries from {@code block_entities}
 *       whose stored {@code id} resolves to a registered
 *       {@link BlockEntityType} whose {@code isValid} set does not include
 *       the block at the BE's coordinate. Block is itself a BE-carrier (so
 *       the {@code ghost-bes} check above passes), but a different type than
 *       the BE NBT names. Same {@code IllegalStateException("Invalid block
 *       entity ...")} from the chunk-system load codec as {@code ghost-bes}.
 *       Unknown BE ids (custom mod
 *       types) are kept intact. {@code region/*.mca} only.</li>
 * </ul>
 *
 * <p>Skip-if-clean at the chunk level: no rewrite when zero dirt is found.
 * Avoids sector-replacement IO on the ~97% of chunks with nothing to strip.
 */
public final class DirtNbtCleaner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern REGION_FILE_REGEX =
        Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca$");
    private static final String AIR = "minecraft:air";
    // Mirrors DirectNbtUpgrader's bounds. Cleaner workload is read+mutate+write
    // like upgrader, not the read-only audit walk; same shape applies.
    private static final long PER_REGION_TIMEOUT_MILLIS = 30L * 60 * 1000;
    private static final long FINAL_DRAIN_TIMEOUT_MILLIS = 5L * 60 * 1000;
    private static final int CHUNK_FAILURE_SAMPLE_CAP = 100;
    private static final long PROGRESS_LOG_INTERVAL_MILLIS = 30L * 1000;
    // Populated at the top of run() by walking BuiltInRegistries.BLOCK. Static
    // so processChunk (called from worker threads) can read without threading
    // the set through five method signatures; the single writer is run() before
    // any worker is submitted.
    private static volatile Set<String> blocksWithEntity = Set.of();
    // Mirrors AuditDirtyChunks.beIdToBlocks. BE type id -> set of block names
    // whose default state passes BlockEntityType.isValid. Static so workers
    // read without threading the map through every signature; same publication
    // shape as blocksWithEntity (single writer in run() before any worker
    // submit, volatile read by workers).
    private static volatile Map<String, Set<String>> beIdToBlocks = Map.of();
    // Inter-chunk dedup map. Populated lazily as workers process chunks; on
    // resume, repopulated read-only from already-completed regions before any
    // pending region is dispatched (see rescanCompletedRegions). First putIfAbsent
    // wins; subsequent puts of the same UUID return non-null and the entity is
    // stripped. Same publication shape as blocksWithEntity: single writer (run())
    // before any worker is submitted; workers only call putIfAbsent.
    private static volatile ConcurrentHashMap<UUID, ChunkPos> uuidMap = new ConcurrentHashMap<>();

    private DirtNbtCleaner() {}

    public static void run(MinecraftServer server) {
        long startMillis = System.currentTimeMillis();
        int threadCount = ArchiveSettings.upgradeWorkerCount();
        blocksWithEntity = computeBlocksWithEntity();
        beIdToBlocks = computeBeIdToBlocks();
        uuidMap = new ConcurrentHashMap<>();
        // Downstream smoke tooling greps "Starting dirty-chunk clean" / "Dirty-chunk clean complete";
        // keep both in sync when changing.
        LOGGER.info("[The Archive] Starting dirty-chunk clean pass ({} workers, {} block-entity-bearing blocks in registry)...",
                    threadCount, blocksWithEntity.size());

        Path progressFile = progressFilePath(server);
        Set<String> completed = readProgress(progressFile);
        if (!completed.isEmpty()) {
            LOGGER.info("[The Archive] Resuming clean with {} regions already complete", completed.size());
        }

        Failures failures = new Failures();
        Totals totals = new Totals();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount,
            new ThreadFactoryBuilder().setNameFormat("archive-clean-%d").setDaemon(true).build());

        try {
            rescanCompletedRegions(server, pool, completed, failures);
            for (ServerLevel level : server.getAllLevels()) {
                processLevel(server, level, pool, completed, progressFile, failures, totals);
            }
        } finally {
            pool.shutdown();
            awaitWithWatchdogTicks(pool, failures);
        }

        long elapsedSec = (System.currentTimeMillis() - startMillis) / 1000;
        if (failures.regionCount.get() > 0 || failures.chunkCount.get() > 0) {
            LOGGER.error("[The Archive] Dirty-chunk clean FAILED: {} region failures, {} chunk failures; cleaned {} chunks (stripped {} invalid-attrs, {} ghost-bes, {} be-coord-mismatch, {} be-type-mismatch, {} uuid-dups, {} unparseable-uuid) in {}s",
                         failures.regionCount.get(), failures.chunkCount.get(),
                         totals.chunksRewritten.get(), totals.invalidAttrsStripped.get(),
                         totals.ghostBesStripped.get(), totals.beCoordMismatchStripped.get(),
                         totals.beTypeMismatchStripped.get(),
                         totals.uuidDupsStripped.get(), totals.unparseableUuid.get(), elapsedSec);
            for (String key : failures.regions) {
                LOGGER.error("[The Archive]   failed region: {}", key);
            }
            long chunkTotal = failures.chunkCount.get();
            long chunkShown = 0;
            for (String key : failures.chunkSample) {
                LOGGER.error("[The Archive]   failed chunk: {}", key);
                chunkShown++;
            }
            if (chunkTotal > chunkShown) {
                LOGGER.error("[The Archive]   ... and {} more chunk failures (sample capped at {})",
                             chunkTotal - chunkShown, CHUNK_FAILURE_SAMPLE_CAP);
            }
            if (failures.regionCount.get() > 0) {
                LOGGER.error("[The Archive] Clean progress file retained at {} for retry of failed regions.", progressFile);
            } else {
                LOGGER.error("[The Archive] Clean progress file retained at {} (no region retries needed; investigate chunk failures offline).", progressFile);
            }
        } else {
            try {
                Files.deleteIfExists(progressFile);
            } catch (IOException ex) {
                LOGGER.warn("[The Archive] Failed to delete clean progress file: {}", ex.getMessage());
            }
            LOGGER.info("[The Archive] Dirty-chunk clean complete: rewrote {} chunks, stripped {} invalid-attrs, {} ghost-bes, {} be-coord-mismatch, {} be-type-mismatch, {} uuid-dups, {} unparseable-uuid, skipped {} legacy chunks in {}s. UUID map at exit: {} unique UUIDs.",
                        totals.chunksRewritten.get(), totals.invalidAttrsStripped.get(),
                        totals.ghostBesStripped.get(), totals.beCoordMismatchStripped.get(),
                        totals.beTypeMismatchStripped.get(),
                        totals.uuidDupsStripped.get(), totals.unparseableUuid.get(),
                        totals.legacyChunksSkipped.get(), elapsedSec, uuidMap.size());
        }
    }

    private static void processLevel(
        MinecraftServer server, ServerLevel level, ExecutorService pool,
        Set<String> completed, Path progressFile, Failures failures, Totals totals
    ) {
        Identifier dim = level.dimension().identifier();
        Path dimRoot = server.storageSource.getDimensionPath(level.dimension());
        String levelId = server.storageSource.getLevelId();

        // region/ pass: strips invalid-attrs from embedded "entities" (lowercase)
        // AND strips ghost-bes from block_entities.
        processFolder(server, level, pool, completed, progressFile, failures, totals,
            dim, dimRoot, levelId, "region", "chunk", FolderKind.REGION);
        // entities/ pass: strips invalid-attrs from split "Entities" (capital).
        // Absent on chunks that have not been split yet; absence is not an error.
        processFolder(server, level, pool, completed, progressFile, failures, totals,
            dim, dimRoot, levelId, "entities", "entities", FolderKind.ENTITIES);

        // Belt-and-suspenders flush of Paper's chunk-system IO worker pool for the
        // level. Mirrors DirectNbtUpgrader.processLevel even though our writes are
        // via raw RegionFile, since SimpleRegionStorage and IOWorker may share state.
        try {
            ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.flush(level);
        } catch (Exception ex) {
            LOGGER.warn("[The Archive] Flush warning for {}: {}", dim, ex.getMessage());
        }
    }

    private enum FolderKind { REGION, ENTITIES }

    private enum UuidStatus { ABSENT, PRESENT_PARSED, PRESENT_MALFORMED }

    private record UuidResult(UuidStatus status, UUID uuid) {
        static final UuidResult ABSENT = new UuidResult(UuidStatus.ABSENT, null);
        static final UuidResult MALFORMED = new UuidResult(UuidStatus.PRESENT_MALFORMED, null);
        static UuidResult parsed(UUID uuid) {
            return new UuidResult(UuidStatus.PRESENT_PARSED, uuid);
        }
    }

    private static void processFolder(
        MinecraftServer server, ServerLevel level, ExecutorService pool,
        Set<String> completed, Path progressFile, Failures failures, Totals totals,
        Identifier dim, Path dimRoot, String levelId,
        String folderName, String storageTypeToken, FolderKind kind
    ) {
        Path folderPath = dimRoot.resolve(folderName);
        File[] files = folderPath.toFile().listFiles((d, n) -> n.endsWith(".mca"));
        if (files == null || files.length == 0) {
            LOGGER.info("[The Archive] Cleaning dimension {} ({}): no region files, skipping",
                        dim, folderName);
            return;
        }
        LOGGER.info("[The Archive] Cleaning dimension {} ({}): {} region files queued",
                    dim, folderName, files.length);

        RegionStorageInfo info = new RegionStorageInfo(levelId, level.dimension(), storageTypeToken);

        AtomicLong chunkCounter = new AtomicLong();
        AtomicInteger regionCounter = new AtomicInteger();
        int totalRegions = files.length;
        List<SubmittedRegion> submitted = new ArrayList<>();
        long folderStartMillis = System.currentTimeMillis();
        AtomicLong lastLogMillis = new AtomicLong(folderStartMillis);

        for (File regionFile : files) {
            Matcher matcher = REGION_FILE_REGEX.matcher(regionFile.getName());
            if (!matcher.matches()) continue;
            int rx = Integer.parseInt(matcher.group(1));
            int rz = Integer.parseInt(matcher.group(2));
            // Progress key namespaces by folder so a partial region/ pass doesn't get
            // mistaken for a complete entities/ pass on resume.
            String key = dim + " " + folderName + " " + rx + " " + rz;
            if (completed.contains(key)) {
                regionCounter.incrementAndGet();
                continue;
            }

            Future<?> future = pool.submit(() -> {
                try {
                    long chunks = processRegion(info, regionFile.toPath(), folderPath,
                                                rx, rz, kind, totals, failures);
                    chunkCounter.addAndGet(chunks);
                    regionCounter.incrementAndGet();
                    appendProgress(progressFile, key);
                    long now = System.currentTimeMillis();
                    long last = lastLogMillis.get();
                    if (now - last >= PROGRESS_LOG_INTERVAL_MILLIS && lastLogMillis.compareAndSet(last, now)) {
                        int doneNow = regionCounter.get();
                        long chunksNow = chunkCounter.get();
                        long rate = chunksNow * 1000L / Math.max(1, now - folderStartMillis);
                        LOGGER.info("[The Archive]   {} ({}): {} / {} regions ({} chunks, {} ch/s)",
                                    dim, folderName, doneNow, totalRegions, chunksNow, rate);
                    }
                } catch (Throwable t) {
                    LOGGER.error("[The Archive] {} ({}) region r.{}.{} failed",
                                 dim, folderName, rx, rz, t);
                    failures.recordRegion(key);
                }
            });
            submitted.add(new SubmittedRegion(key, future));
        }

        // Same per-future deadline + watchdog-tick pattern as DirectNbtUpgrader:
        // bound any single hung worker; cancel + record on exceed.
        for (SubmittedRegion task : submitted) {
            long deadline = System.currentTimeMillis() + PER_REGION_TIMEOUT_MILLIS;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    task.future().cancel(true);
                    LOGGER.error("[The Archive] {} ({}) region task exceeded {}m, cancelled: {}",
                                 dim, folderName, PER_REGION_TIMEOUT_MILLIS / 60_000, task.key());
                    failures.recordRegion(task.key());
                    break;
                }
                try {
                    task.future().get(Math.min(remaining, 4_000), TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException te) {
                    org.spigotmc.WatchdogThread.tick();
                } catch (Exception ex) {
                    break;
                }
            }
            org.spigotmc.WatchdogThread.tick();
        }

        LOGGER.info("[The Archive]   {} ({}): complete, {} chunks walked",
                    dim, folderName, chunkCounter.get());
    }

    private static long processRegion(
        RegionStorageInfo info, Path regionPath, Path regionFolder,
        int rx, int rz, FolderKind kind, Totals totals, Failures failures
    ) throws IOException {
        long processed = 0;
        // sync=false: open WITHOUT DSYNC, mirroring DirectNbtUpgrader (DSYNC was a
        // ~10x perf regression before the fix). RegionFile.close() calls
        // file.force(true), so the file is durable at region granularity. Our
        // progress marker only advances after processRegion returns (i.e. after
        // close()), so resume semantics remain crash-safe at region granularity.
        try (RegionFile region = new RegionFile(info, regionPath, regionFolder, false)) {
            int xOffset = rx << 5;
            int zOffset = rz << 5;
            for (int dx = 0; dx < 32; dx++) {
                for (int dz = 0; dz < 32; dz++) {
                    ChunkPos pos = new ChunkPos(dx + xOffset, dz + zOffset);
                    if (!region.doesChunkExist(pos)) continue;
                    try {
                        if (processChunk(region, pos, kind, totals)) {
                            processed++;
                        }
                    } catch (Exception ex) {
                        // Per-chunk corruption: log + record but keep going so the
                        // other ~1023 chunks in the region still complete. The
                        // production fixture has two chunks with malformed JSON
                        // value "Dinner" that crash JsonParser; this catches them.
                        String chunkKey = "r." + rx + "." + rz + " chunk(" + pos.x() + "," + pos.z() + ")";
                        LOGGER.error("[The Archive] {} failed: {}", chunkKey, ex.toString());
                        failures.recordChunk(chunkKey);
                    }
                }
            }
        }
        return processed;
    }

    /**
     * Reads one chunk, strips dirt, writes back if anything changed.
     * Returns true if the chunk slot was walked (regardless of whether it was rewritten).
     */
    private static boolean processChunk(RegionFile region, ChunkPos pos, FolderKind kind, Totals totals)
            throws IOException {
        CompoundTag root;
        try (DataInputStream in = region.getChunkDataInputStream(pos)) {
            if (in == null) return false;
            root = NbtIo.read(in);
        }

        if (kind == FolderKind.REGION) {
            // Pre-1.18 Level-wrapped chunks: vanilla legacy attribute names fail
            // Identifier.tryParse the same way Forge taint does, so we can't
            // validate them here. Skip; the operator should --upgradeChunks first.
            if (root.getCompound("Level").isPresent()) {
                totals.legacyChunksSkipped.incrementAndGet();
                return true;
            }
        }

        int invalidStripped = 0;
        int ghostStripped = 0;
        int coordMismatchStripped = 0;
        int typeMismatchStripped = 0;
        int uuidDupsStripped = 0;
        int unparseableUuid = 0;

        // 1) Strip invalid-attrs from the embedded/split entities list, with
        //    a UUID-dedup pre-check. First putIfAbsent wins per UUID; later
        //    encounters of the same UUID drop the entity entirely (so its
        //    invalid-attrs question is moot).
        String entitiesKey = kind == FolderKind.REGION ? "entities" : "Entities";
        ListTag entities = root.getListOrEmpty(entitiesKey);
        if (!entities.isEmpty()) {
            ListTag rebuiltEntities = new ListTag();
            boolean anyEntityChanged = false;
            for (int i = 0; i < entities.size(); i++) {
                CompoundTag entity = entities.getCompoundOrEmpty(i);

                // UUID dedup. Status-MALFORMED entries are counted but kept
                // (vanilla will reject on load; we don't have a better answer
                // than "leave them alone"). Status-ABSENT silently keeps
                // (vanilla assigns at load time).
                UuidResult r = decodeUuid(entity);
                if (r.status() == UuidStatus.PRESENT_PARSED) {
                    ChunkPos existing = uuidMap.putIfAbsent(r.uuid(), pos);
                    if (existing != null) {
                        uuidDupsStripped++;
                        anyEntityChanged = true;
                        continue;
                    }
                } else if (r.status() == UuidStatus.PRESENT_MALFORMED) {
                    unparseableUuid++;
                    // Fall through: keep the entry.
                }

                if (entityHasInvalidAttribute(entity)) {
                    entity.remove("attributes");
                    invalidStripped++;
                    anyEntityChanged = true;
                }
                // Recurse into Passengers for nested UUID dedup. Wandering-trader
                // caravans (llama in trader-Passengers, optionally inside a boat)
                // and skeleton-trap horse riders surfaced as the residual class
                // after the top-level-only first pass. Passenger-only edits
                // mutate `entity` in place; we only need to fold the strip count
                // into the same uuidDupsStripped totals.
                PassengerWalkResult sub = stripDuplicatePassengers(entity, pos);
                if (sub.stripped() > 0) anyEntityChanged = true;
                uuidDupsStripped += sub.stripped();
                unparseableUuid += sub.unparseable();
                rebuiltEntities.add(entity);
            }
            if (anyEntityChanged) {
                root.put(entitiesKey, rebuiltEntities);
            }
        }

        // 2) Strip ghost-bes from block_entities (region/*.mca only). A BE entry
        //    is ghost when its block at (x,y,z) does not carry a block entity,
        //    i.e. the block is not an EntityBlock. Mirrors the runtime guard
        //    at LevelChunk.setBlockEntity and the chunk-system load codec,
        //    which throws IllegalStateException for these.
        if (kind == FolderKind.REGION) {
            ListTag bes = root.getListOrEmpty("block_entities");
            if (!bes.isEmpty()) {
                Map<Integer, SectionDecoder> sections = new HashMap<>();
                ListTag secList = root.getListOrEmpty("sections");
                for (int i = 0; i < secList.size(); i++) {
                    CompoundTag sec = secList.getCompoundOrEmpty(i);
                    int sectionY = sec.getByteOr("Y", (byte) 0);
                    SectionDecoder dec = SectionDecoder.from(sec);
                    if (dec != null) sections.put(sectionY, dec);
                }

                Set<String> entityBlocks = blocksWithEntity;
                Map<String, Set<String>> beTypes = beIdToBlocks;
                ListTag rebuiltBEs = new ListTag();
                boolean anyBEChanged = false;
                for (int i = 0; i < bes.size(); i++) {
                    CompoundTag be = bes.getCompoundOrEmpty(i);
                    int x = be.getIntOr("x", 0);
                    int y = be.getIntOr("y", 0);
                    int z = be.getIntOr("z", 0);
                    // be-coord-mismatch: stored chunk-coords disagree with the
                    // holding chunk. Paper's load path logs WARN "found in a
                    // wrong chunk" and drops the BE. Strip here to silence at
                    // upgrade time. Check BEFORE the palette lookup; the
                    // (x & 15, z & 15) below would otherwise hit the wrong
                    // chunk's block grid and miss the bug.
                    if ((x >> 4) != pos.x() || (z >> 4) != pos.z()) {
                        coordMismatchStripped++;
                        anyBEChanged = true;
                        continue;
                    }
                    SectionDecoder dec = sections.get(Math.floorDiv(y, 16));
                    String name = dec == null ? null : dec.blockNameAt(x & 15, Math.floorMod(y, 16), z & 15);
                    if (name == null || !entityBlocks.contains(name)) {
                        ghostStripped++;
                        anyBEChanged = true;
                        continue;
                    }
                    // be-type-mismatch: block IS a BE-carrier (passed the
                    // ghost check) but the BE's stored id resolves to a
                    // registered type whose isValid set excludes this block.
                    // Unknown ids (custom mod BEs) are kept; they fail at
                    // chunk-system load as a different class.
                    String beId = be.getStringOr("id", "");
                    if (!beId.isEmpty()) {
                        Set<String> allowed = beTypes.get(beId);
                        if (allowed != null && !allowed.contains(name)) {
                            typeMismatchStripped++;
                            anyBEChanged = true;
                            continue;
                        }
                    }
                    rebuiltBEs.add(be);
                }
                if (anyBEChanged) {
                    root.put("block_entities", rebuiltBEs);
                }
            }
        }

        if (invalidStripped == 0 && ghostStripped == 0 && coordMismatchStripped == 0
                && typeMismatchStripped == 0 && uuidDupsStripped == 0) {
            // Skip-if-clean: avoid sector-replacement IO on chunks with no dirt.
            // unparseableUuid is diagnostic only; the entry is kept, no rewrite
            // needed for it alone, but the count still goes into Totals.
            if (unparseableUuid > 0) {
                totals.unparseableUuid.addAndGet(unparseableUuid);
            }
            return true;
        }

        try (DataOutputStream out = region.getChunkDataOutputStream(pos)) {
            NbtIo.write(root, out);
        }
        totals.chunksRewritten.incrementAndGet();
        totals.invalidAttrsStripped.addAndGet(invalidStripped);
        totals.ghostBesStripped.addAndGet(ghostStripped);
        totals.beCoordMismatchStripped.addAndGet(coordMismatchStripped);
        totals.beTypeMismatchStripped.addAndGet(typeMismatchStripped);
        totals.uuidDupsStripped.addAndGet(uuidDupsStripped);
        totals.unparseableUuid.addAndGet(unparseableUuid);
        return true;
    }

    /**
     * Snapshots the set of registered block IDs that produce a {@link Block}
     * implementing {@link EntityBlock}. {@code BlockState.hasBlockEntity()}
     * reduces to {@code block instanceof EntityBlock} (no state-specific
     * variation in vanilla), so a name-only set is sufficient for the cleaner
     * walk. Computed once at the start of {@link #run}; safe to publish to
     * worker threads via the volatile {@link #blocksWithEntity}.
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

    /**
     * Mirrors {@link AuditDirtyChunks}'s {@code computeBeIdToBlocks}: BE type
     * id (e.g. {@code minecraft:hopper}) to the set of block names whose
     * {@link Block#defaultBlockState} passes {@link BlockEntityType#isValid}.
     * Used to decide be-type-mismatch on BE entries whose block survives the
     * ghost-bes test but is the wrong target type for the BE NBT id.
     */
    private static Map<String, Set<String>> computeBeIdToBlocks() {
        Map<String, Set<String>> result = new HashMap<>();
        for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            Identifier typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
            if (typeId == null) continue;
            Set<String> blocks = new HashSet<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (type.isValid(block.defaultBlockState())) {
                    Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
                    if (blockId != null) blocks.add(blockId.toString());
                }
            }
            result.put(typeId.toString(), Set.copyOf(blocks));
        }
        return Map.copyOf(result);
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
        // Empty / missing id is not the dirt we strip (it would fail the codec
        // for a different reason). Only flag a present id that fails the parser.
        return !id.isEmpty() && Identifier.tryParse(id) == null;
    }

    private static UuidResult decodeUuid(CompoundTag entity) {
        Tag tag = entity.get("UUID");
        if (tag == null) return UuidResult.ABSENT;
        if (!(tag instanceof IntArrayTag intArrayTag)) return UuidResult.MALFORMED;
        int[] array = intArrayTag.getAsIntArray();
        if (array.length != 4) return UuidResult.MALFORMED;
        return UuidResult.parsed(UUIDUtil.uuidFromIntArray(array));
    }

    /**
     * Recursively dedup the {@code Passengers} list rooted at {@code entity},
     * mutating in place. First-occurrence-wins via {@link #uuidMap#putIfAbsent};
     * a colliding passenger is dropped (and its own passengers vanish with it),
     * a kept passenger is descended-into for its own Passengers chain.
     *
     * <p>Only the passenger UUIDs are stripped here; passenger invalid-attrs are
     * intentionally out of scope for this pass (the runtime warns "uuid already
     * exists" but not on bad attribute ids in passenger NBT directly, and the
     * top-level invalid-attrs strip already covers the high-volume case).
     *
     * @return strip / unparseable counts for this subtree; caller folds them
     *         into chunk-wide totals
     */
    private static PassengerWalkResult stripDuplicatePassengers(CompoundTag entity, ChunkPos pos) {
        ListTag passengers = entity.getListOrEmpty("Passengers");
        if (passengers.isEmpty()) return PassengerWalkResult.NONE;

        ListTag rebuilt = new ListTag();
        int stripped = 0;
        int unparseable = 0;
        boolean strippedAtThisLevel = false;
        for (int i = 0; i < passengers.size(); i++) {
            CompoundTag passenger = passengers.getCompoundOrEmpty(i);
            UuidResult r = decodeUuid(passenger);
            if (r.status() == UuidStatus.PRESENT_PARSED) {
                ChunkPos existing = uuidMap.putIfAbsent(r.uuid(), pos);
                if (existing != null) {
                    stripped++;
                    strippedAtThisLevel = true;
                    continue;
                }
            } else if (r.status() == UuidStatus.PRESENT_MALFORMED) {
                unparseable++;
            }
            PassengerWalkResult sub = stripDuplicatePassengers(passenger, pos);
            stripped += sub.stripped();
            unparseable += sub.unparseable();
            rebuilt.add(passenger);
        }
        if (strippedAtThisLevel) entity.put("Passengers", rebuilt);
        return new PassengerWalkResult(stripped, unparseable);
    }

    private record PassengerWalkResult(int stripped, int unparseable) {
        static final PassengerWalkResult NONE = new PassengerWalkResult(0, 0);
    }

    /**
     * Read-only mirror of {@link #stripDuplicatePassengers}: registers every
     * nested passenger's UUID into {@link #uuidMap}. Used by the resume rescan
     * so that pending regions strip against the same set the first pass would
     * have produced, including passenger UUIDs the first pass had already
     * registered.
     */
    private static void registerPassengerUuids(CompoundTag entity, ChunkPos pos) {
        ListTag passengers = entity.getListOrEmpty("Passengers");
        for (int i = 0; i < passengers.size(); i++) {
            CompoundTag passenger = passengers.getCompoundOrEmpty(i);
            UuidResult r = decodeUuid(passenger);
            if (r.status() == UuidStatus.PRESENT_PARSED) {
                uuidMap.putIfAbsent(r.uuid(), pos);
            }
            registerPassengerUuids(passenger, pos);
        }
    }

    /**
     * Resume-rescan: read-only re-populate {@link #uuidMap} from every region
     * in {@code completed} before any pending region is dispatched. Without
     * this, a mid-pass kill creates a real correctness bug: pending regions
     * would dedup against an empty UUID map and let through entities the
     * first pass had already registered.
     *
     * <p>Walks both {@code region/} (lowercase "entities" key) and
     * {@code entities/} (capital "Entities") folder entries present in the
     * progress file; non-existent regions for a given completed key are
     * tolerated (the cleaner namespaces by folder so the key encodes which
     * folder the marker belongs to).
     */
    private static void rescanCompletedRegions(
        MinecraftServer server, ExecutorService pool, Set<String> completed, Failures failures
    ) {
        if (completed.isEmpty()) return;
        long start = System.currentTimeMillis();
        AtomicLong rescanned = new AtomicLong();
        List<Future<?>> futures = new ArrayList<>(completed.size());

        for (String key : completed) {
            // Key shape: "<dim> <folder> <rx> <rz>"; see appendProgress in
            // processFolder.
            String[] parts = key.split(" ");
            if (parts.length != 4) {
                LOGGER.warn("[The Archive] Malformed clean-progress key, skipping rescan: {}", key);
                continue;
            }
            String dimStr = parts[0];
            String folderName = parts[1];
            int rx, rz;
            try {
                rx = Integer.parseInt(parts[2]);
                rz = Integer.parseInt(parts[3]);
            } catch (NumberFormatException ex) {
                LOGGER.warn("[The Archive] Bad rx/rz in clean-progress key, skipping: {}", key);
                continue;
            }

            ServerLevel level = findLevel(server, dimStr);
            if (level == null) {
                LOGGER.warn("[The Archive] Unknown dimension in clean-progress key, skipping: {}", dimStr);
                continue;
            }
            Path dimRoot = server.storageSource.getDimensionPath(level.dimension());
            Path folderPath = dimRoot.resolve(folderName);
            Path regionPath = folderPath.resolve("r." + rx + "." + rz + ".mca");
            if (!Files.exists(regionPath)) {
                // Tolerated: the marker exists but the region was removed
                // between runs (e.g. operator manually pruned). Nothing to
                // rescan; the UUIDs from that region simply aren't in the map.
                continue;
            }
            FolderKind kind = folderName.equals("entities") ? FolderKind.ENTITIES : FolderKind.REGION;
            String storageTypeToken = folderName.equals("entities") ? "entities" : "chunk";
            RegionStorageInfo info = new RegionStorageInfo(
                server.storageSource.getLevelId(), level.dimension(), storageTypeToken);

            futures.add(pool.submit(() -> {
                try {
                    rescanRegion(info, regionPath, folderPath, rx, rz, kind);
                    rescanned.incrementAndGet();
                } catch (Throwable t) {
                    LOGGER.error("[The Archive] Rescan of {} failed: {}", regionPath, t.toString());
                    failures.recordRegion("rescan:" + key);
                }
            }));
        }

        // Drain. Same per-future deadline shape as processFolder; watchdog
        // ticks every ~4 s while waiting.
        for (Future<?> f : futures) {
            long deadline = System.currentTimeMillis() + PER_REGION_TIMEOUT_MILLIS;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    f.cancel(true);
                    LOGGER.error("[The Archive] Rescan future exceeded {}m, cancelled",
                                 PER_REGION_TIMEOUT_MILLIS / 60_000);
                    break;
                }
                try {
                    f.get(Math.min(remaining, 4_000), TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException te) {
                    org.spigotmc.WatchdogThread.tick();
                } catch (Exception ex) {
                    break;
                }
            }
            org.spigotmc.WatchdogThread.tick();
        }

        long elapsedSec = (System.currentTimeMillis() - start) / 1000;
        LOGGER.info("[The Archive] Resumed dedup map: {} UUIDs from {} completed regions in {}s",
                    uuidMap.size(), rescanned.get(), elapsedSec);
    }

    private static void rescanRegion(
        RegionStorageInfo info, Path regionPath, Path regionFolder, int rx, int rz, FolderKind kind
    ) throws IOException {
        try (RegionFile region = new RegionFile(info, regionPath, regionFolder, true)) {
            int xOffset = rx << 5;
            int zOffset = rz << 5;
            String entitiesKey = kind == FolderKind.REGION ? "entities" : "Entities";
            for (int dx = 0; dx < 32; dx++) {
                for (int dz = 0; dz < 32; dz++) {
                    ChunkPos pos = new ChunkPos(dx + xOffset, dz + zOffset);
                    if (!region.doesChunkExist(pos)) continue;
                    CompoundTag root;
                    try (DataInputStream in = region.getChunkDataInputStream(pos)) {
                        if (in == null) continue;
                        root = NbtIo.read(in);
                    } catch (Exception ex) {
                        // Per-chunk corruption: skip silently during rescan.
                        continue;
                    }
                    if (kind == FolderKind.REGION && root.getCompound("Level").isPresent()) continue;
                    ListTag entities = root.getListOrEmpty(entitiesKey);
                    for (int i = 0; i < entities.size(); i++) {
                        CompoundTag entity = entities.getCompoundOrEmpty(i);
                        UuidResult r = decodeUuid(entity);
                        if (r.status() == UuidStatus.PRESENT_PARSED) {
                            uuidMap.putIfAbsent(r.uuid(), pos);
                        }
                        // Mirror the write-path's recursion so resume's baseline
                        // includes nested-passenger UUIDs the first pass had
                        // already registered.
                        registerPassengerUuids(entity, pos);
                    }
                }
            }
        }
    }

    private static ServerLevel findLevel(MinecraftServer server, String identifierStr) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(identifierStr)) return level;
        }
        return null;
    }

    private static Path progressFilePath(MinecraftServer server) {
        return server.storageSource.getLevelDirectory().path()
            .resolve(".archive-clean-progress.txt");
    }

    private static Set<String> readProgress(Path path) {
        if (!Files.exists(path)) return ConcurrentHashMap.newKeySet();
        try {
            Set<String> set = ConcurrentHashMap.newKeySet();
            for (String line : Files.readAllLines(path)) {
                if (!line.isBlank() && !line.startsWith("#")) set.add(line.trim());
            }
            return set;
        } catch (IOException ex) {
            LOGGER.warn("[The Archive] Failed to read clean progress file, starting fresh: {}", ex.getMessage());
            return ConcurrentHashMap.newKeySet();
        }
    }

    private static synchronized void appendProgress(Path path, String entry) {
        try {
            Files.writeString(path, entry + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException ex) {
            LOGGER.warn("[The Archive] Failed to append clean progress: {}", ex.getMessage());
        }
    }

    private static void awaitWithWatchdogTicks(ExecutorService pool, Failures failures) {
        long deadline = System.currentTimeMillis() + FINAL_DRAIN_TIMEOUT_MILLIS;
        try {
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    List<Runnable> dropped = pool.shutdownNow();
                    LOGGER.error("[The Archive] Clean pool drain exceeded {}m, forcing shutdown ({} tasks dropped)",
                                 FINAL_DRAIN_TIMEOUT_MILLIS / 60_000, dropped.size());
                    if (!dropped.isEmpty()) {
                        failures.recordRegion("<pool drain timeout, " + dropped.size() + " tasks dropped>");
                    }
                    return;
                }
                if (pool.awaitTermination(Math.min(30_000, remaining), TimeUnit.MILLISECONDS)) {
                    return;
                }
                org.spigotmc.WatchdogThread.tick();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private record SubmittedRegion(String key, Future<?> future) {}

    private static final class Failures {
        final Set<String> regions = ConcurrentHashMap.newKeySet();
        final AtomicLong regionCount = new AtomicLong();
        final ConcurrentLinkedQueue<String> chunkSample = new ConcurrentLinkedQueue<>();
        final AtomicLong chunkCount = new AtomicLong();

        void recordRegion(String key) {
            if (regions.add(key)) {
                regionCount.incrementAndGet();
            }
        }

        void recordChunk(String key) {
            long n = chunkCount.incrementAndGet();
            if (n <= CHUNK_FAILURE_SAMPLE_CAP) {
                chunkSample.add(key);
            }
        }
    }

    private static final class Totals {
        final AtomicLong chunksRewritten = new AtomicLong();
        final AtomicLong invalidAttrsStripped = new AtomicLong();
        final AtomicLong ghostBesStripped = new AtomicLong();
        final AtomicLong beCoordMismatchStripped = new AtomicLong();
        final AtomicLong beTypeMismatchStripped = new AtomicLong();
        final AtomicLong uuidDupsStripped = new AtomicLong();
        final AtomicLong unparseableUuid = new AtomicLong();
        final AtomicLong legacyChunksSkipped = new AtomicLong();
    }

    // Mirror of AuditDirtyChunks.SectionDecoder. Duplicated rather than shared
    // to avoid touching the audit class's surface; two callers don't yet
    // justify extracting a top-level utility. Decodes a 1.16+ paletted-container
    // section: bits-per-entry is max(4, ceil(log2(palette.size()))), indices
    // are packed into the data long array with no straddling, block index for
    // local (x,y,z) follows YZX order.
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
}
