package archive;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.logging.LogUtils;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
        blocksWithEntity = BlockEntityRegistry.computeBlocksWithEntity();
        beIdToBlocks = BlockEntityRegistry.computeBeIdToBlocks();
        uuidMap = new ConcurrentHashMap<>();
        // Downstream smoke tooling greps "Starting dirty-chunk clean" / "Dirty-chunk clean complete";
        // keep both in sync when changing.
        LOGGER.info("[The Archive] Starting dirty-chunk clean pass ({} workers, {} block-entity-bearing blocks in registry)...",
                    threadCount, blocksWithEntity.size());
        ArchiveSettings.warnIfDisableSavingAtPassEntry("--cleanDirtyChunks");

        Failures failures = new Failures();
        Path progressFile = progressFilePath(server);
        Set<String> completed = readProgress(progressFile, failures);
        if (!completed.isEmpty()) {
            LOGGER.info("[The Archive] Resuming clean with {} regions already complete", completed.size());
        }

        Totals totals = new Totals();
        Path totalsPath = totalsFilePath(server);
        Map<String, Long> priorTotals = readTotals(totalsPath);
        if (!priorTotals.isEmpty()) {
            totals.loadAdd(priorTotals);
            LOGGER.info("[The Archive] Loaded prior clean totals: rewrote={}, invalid-attrs={}, ghost-bes={}, be-coord-mismatch={}, be-type-mismatch={}, uuid-dups={}, unparseable-uuid={}, legacy-chunks-skipped={}",
                        totals.chunksRewritten.get(), totals.invalidAttrsStripped.get(),
                        totals.ghostBesStripped.get(), totals.beCoordMismatchStripped.get(),
                        totals.beTypeMismatchStripped.get(),
                        totals.uuidDupsStripped.get(), totals.unparseableUuid.get(),
                        totals.legacyChunksSkipped.get());
        }
        ExecutorService pool = Executors.newFixedThreadPool(threadCount,
            new ThreadFactoryBuilder().setNameFormat("archive-clean-%d").setDaemon(true).build());

        try {
            rescanCompletedRegions(server, pool, completed, failures);
            for (ServerLevel level : server.getAllLevels()) {
                processLevel(server, level, pool, completed, progressFile, totalsPath, failures, totals);
            }
        } finally {
            pool.shutdown();
            awaitWithWatchdogTicks(pool, failures);
        }

        long elapsedSec = (System.currentTimeMillis() - startMillis) / 1000;
        long progressFailed = failures.progressAppendFailed.get();
        long totalsFailed = failures.totalsWriteFailed.get();
        long drainInterrupted = failures.drainInterrupted.get();
        long progressReadFailed = failures.progressReadFailed.get();
        if (failures.regionCount.get() > 0 || failures.chunkCount.get() > 0 || progressFailed > 0) {
            LOGGER.error("[The Archive] Dirty-chunk clean FAILED: {} region failures, {} chunk failures, {} progress-marker IO failures, {} totals-write failures, drain-interrupted={}, progress-read-failures={}; cleaned {} chunks (stripped {} invalid-attrs, {} ghost-bes, {} be-coord-mismatch, {} be-type-mismatch, {} uuid-dups, {} unparseable-uuid) in {}s",
                         failures.regionCount.get(), failures.chunkCount.get(), progressFailed, totalsFailed,
                         drainInterrupted, progressReadFailed,
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
            try {
                Files.deleteIfExists(totalsPath);
            } catch (IOException ex) {
                LOGGER.warn("[The Archive] Failed to delete clean totals file: {}", ex.getMessage());
            }
            LOGGER.info("[The Archive] Dirty-chunk clean complete: rewrote {} chunks, stripped {} invalid-attrs, {} ghost-bes, {} be-coord-mismatch, {} be-type-mismatch, {} uuid-dups, {} unparseable-uuid, skipped {} legacy chunks, progress-marker IO failures={}, totals-write failures={}, drain-interrupted={}, progress-read-failures={} in {}s. UUID map at exit: {} unique UUIDs.",
                        totals.chunksRewritten.get(), totals.invalidAttrsStripped.get(),
                        totals.ghostBesStripped.get(), totals.beCoordMismatchStripped.get(),
                        totals.beTypeMismatchStripped.get(),
                        totals.uuidDupsStripped.get(), totals.unparseableUuid.get(),
                        totals.legacyChunksSkipped.get(), progressFailed, totalsFailed,
                        drainInterrupted, progressReadFailed, elapsedSec, uuidMap.size());
        }
    }

    private static void processLevel(
        MinecraftServer server, ServerLevel level, ExecutorService pool,
        Set<String> completed, Path progressFile, Path totalsPath, Failures failures, Totals totals
    ) {
        Identifier dim = level.dimension().identifier();
        Path dimRoot = server.storageSource.getDimensionPath(level.dimension());
        String levelId = server.storageSource.getLevelId();

        // region/ pass: strips invalid-attrs from embedded "entities" (lowercase)
        // AND strips ghost-bes from block_entities.
        processFolder(server, level, pool, completed, progressFile, totalsPath, failures, totals,
            dim, dimRoot, levelId, "region", "chunk", FolderKind.REGION);
        // entities/ pass: strips invalid-attrs from split "Entities" (capital).
        // Absent on chunks that have not been split yet; absence is not an error.
        processFolder(server, level, pool, completed, progressFile, totalsPath, failures, totals,
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

    private static void processFolder(
        MinecraftServer server, ServerLevel level, ExecutorService pool,
        Set<String> completed, Path progressFile, Path totalsPath, Failures failures, Totals totals,
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
        // Deterministic dispatch order. FS iteration order from listFiles is not
        // stable across runs (or across filesystems), and the cleaner's UUID
        // dedup is first-occurrence-wins via uuidMap.putIfAbsent. Without a
        // stable order, which-ChunkPos-wins-which-UUID is FS-iteration-dependent
        // and the chosen survivor varies run-to-run on identical input. Sort by
        // filename so dispatch order is byte-stable; the worker pool still
        // races on completion, but the submit-order bias is enough on every
        // observed fixture for the count- and identity-level invariants to hold
        // across reruns. Mirrors commit 64a7bc0 (bake journalFiles
        // sort) for the same determinism reason.
        Arrays.sort(files, Comparator.comparing(File::getName));
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
                    RegionResult result = processRegion(info, regionFile.toPath(), folderPath,
                                                rx, rz, kind, totals, failures);
                    chunkCounter.addAndGet(result.parsedChunks());
                    regionCounter.incrementAndGet();
                    // Per-chunk failures keep this region OUT of the progress file
                    // so resume re-walks it. Mirrors the BakeLightPass appendProgress gate (commit
                    // 8dbae7f) and the upgrader sibling. Per-chunk strip is
                    // idempotent under retry: a chunk already cleaned matches
                    // nothing on re-walk and rewrites zero times, so a region
                    // re-attempted to catch the corrupt slots costs only the
                    // clean-only walk on the rest. The retry surfaces persistent
                    // corruption as a stable signal instead of a hidden no-op.
                    if (result.perChunkFailures() == 0) {
                        // Totals snapshot BEFORE progress append: a SIGKILL between
                        // these two writes leaves totals capturing this region's
                        // contribution but the region absent from progress, so
                        // resume re-walks it and strips zero (chunks already
                        // cleaned on disk). The reverse order would mark the
                        // region done while losing its totals contribution, so
                        // resume would skip it and the resumed-run summary would
                        // undercount by this region's strips.
                        writeTotalsSnapshot(totalsPath, totals, failures);
                        appendProgress(progressFile, key, failures);
                    }
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
                } catch (InterruptedException ie) {
                    // The drain thread itself was interrupted (the surrounding
                    // run is being torn down). Preserve the interrupt for the
                    // caller, log + bump a typed counter, and stop draining.
                    Thread.currentThread().interrupt();
                    LOGGER.error("[The Archive] {} ({}) drain interrupted while waiting on {}",
                                 dim, folderName, task.key());
                    failures.drainInterrupted.incrementAndGet();
                    break;
                } catch (ExecutionException ee) {
                    // Worker threw out of the lambda body; its own catch (Throwable t)
                    // at the submit site already recorded the region failure. Log
                    // the unwrapped cause at debug for offline diagnosis and move on.
                    LOGGER.debug("[The Archive] {} ({}) worker threw for {} (already recorded)",
                                 dim, folderName, task.key(), ee.getCause());
                    break;
                } catch (CancellationException ce) {
                    // The future was cancelled from outside the deadline branch
                    // above (no current caller does this, but a future shutdown
                    // hook could). Record the failure so it surfaces in the
                    // summary; today's deadline path already records before
                    // cancelling, so this branch is reachable only via external
                    // cancellation.
                    LOGGER.error("[The Archive] {} ({}) drain saw external cancel of {}",
                                 dim, folderName, task.key());
                    failures.recordRegion(task.key());
                    break;
                }
            }
            org.spigotmc.WatchdogThread.tick();
        }

        LOGGER.info("[The Archive]   {} ({}): complete, {} chunks walked",
                    dim, folderName, chunkCounter.get());
    }

    /**
     * Per-region result. {@code parsedChunks} is the count of chunk slots walked
     * (rewritten or not); {@code perChunkFailures} counts throws caught by the
     * per-chunk try-catch below. The submit lambda gates appendProgress on a
     * zero failure count so a region with corrupt slots stays out of the progress
     * file and a re-run gets another shot. Mirrors the same-named record in
     * {@link DirectNbtUpgrader} (per-chunk-only) and the larger one in
     * {@link BakeLightPass} (per-chunk + border-load + cross-region IO).
     */
    private record RegionResult(long parsedChunks, int perChunkFailures) {}

    private static RegionResult processRegion(
        RegionStorageInfo info, Path regionPath, Path regionFolder,
        int rx, int rz, FolderKind kind, Totals totals, Failures failures
    ) throws IOException {
        long processed = 0;
        int perChunkFailures = 0;
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
                        // The submit lambda gates appendProgress on
                        // perChunkFailures so the region is re-attempted on resume.
                        String chunkKey = "r." + rx + "." + rz + " chunk(" + pos.x() + "," + pos.z() + ")";
                        LOGGER.error("[The Archive] {} failed: {}", chunkKey, ex.toString());
                        failures.recordChunk(chunkKey);
                        perChunkFailures++;
                    }
                }
            }
        }
        return new RegionResult(processed, perChunkFailures);
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
                UuidResult r = UuidDecoder.decodeUuid(entity);
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

                if (AttributeValidator.entityHasInvalidAttribute(entity)) {
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
                Map<Integer, NbtSectionDecoder> sections = new HashMap<>();
                ListTag secList = root.getListOrEmpty("sections");
                for (int i = 0; i < secList.size(); i++) {
                    CompoundTag sec = secList.getCompoundOrEmpty(i);
                    int sectionY = sec.getByteOr("Y", (byte) 0);
                    NbtSectionDecoder dec = NbtSectionDecoder.from(sec);
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
                    NbtSectionDecoder dec = sections.get(Math.floorDiv(y, 16));
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
            UuidResult r = UuidDecoder.decodeUuid(passenger);
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
            UuidResult r = UuidDecoder.decodeUuid(passenger);
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
        List<SubmittedRegion> futures = new ArrayList<>(completed.size());

        // Sort the completed set so resume re-populates uuidMap in the same
        // order processFolder dispatches the main pass. completed is a Set,
        // whose iteration order is undefined; without sorting, the resumed
        // run's UUID-survivor identity could differ from the pre-kill run's.
        List<String> orderedCompleted = new ArrayList<>(completed);
        orderedCompleted.sort(String::compareTo);
        for (String key : orderedCompleted) {
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

            Future<?> future = pool.submit(() -> {
                try {
                    rescanRegion(info, regionPath, folderPath, rx, rz, kind, failures);
                    rescanned.incrementAndGet();
                } catch (Throwable t) {
                    LOGGER.error("[The Archive] Rescan of {} failed: {}", regionPath, t.toString());
                    markRescanFailed(key, completed, failures);
                }
            });
            futures.add(new SubmittedRegion(key, future));
        }

        // Drain. Same per-future deadline shape as processFolder; watchdog
        // ticks every ~4 s while waiting. A cancel-on-deadline here bypasses
        // the in-lambda catch above (the task may be still queued, or its
        // thread may not yet observe the interrupt), so the cancel-site has
        // to call the same failure path or the region's key stays in {@code
        // completed} with its UUIDs never registered, leaving pending regions
        // unable to dedup against the missing entries.
        for (SubmittedRegion task : futures) {
            long deadline = System.currentTimeMillis() + PER_REGION_TIMEOUT_MILLIS;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    task.future().cancel(true);
                    LOGGER.error("[The Archive] Rescan future exceeded {}m, cancelled: {}",
                                 PER_REGION_TIMEOUT_MILLIS / 60_000, task.key());
                    markRescanFailed(task.key(), completed, failures);
                    break;
                }
                try {
                    task.future().get(Math.min(remaining, 4_000), TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException te) {
                    org.spigotmc.WatchdogThread.tick();
                } catch (InterruptedException ie) {
                    // Drain thread interrupted mid-rescan: preserve the interrupt
                    // and stop. The rescan's job is to repopulate uuidMap; a
                    // truncated rescan leaves some completed regions unrescanned
                    // and their UUIDs absent from the dedup map, but the surrounding
                    // shutdown signal means subsequent pending regions will not
                    // run anyway.
                    Thread.currentThread().interrupt();
                    LOGGER.error("[The Archive] Rescan drain interrupted while waiting on {}", task.key());
                    failures.drainInterrupted.incrementAndGet();
                    break;
                } catch (ExecutionException ee) {
                    // Worker threw out of the rescan lambda; its own catch
                    // (Throwable t) at the submit site already called
                    // markRescanFailed. Log the unwrapped cause at debug.
                    LOGGER.debug("[The Archive] Rescan worker threw for {} (already recorded)",
                                 task.key(), ee.getCause());
                    break;
                } catch (CancellationException ce) {
                    // External cancellation (not the deadline branch above);
                    // mirror the deadline path so the region's UUIDs aren't
                    // silently lost from the dedup map.
                    LOGGER.error("[The Archive] Rescan drain saw external cancel of {}", task.key());
                    markRescanFailed(task.key(), completed, failures);
                    break;
                }
            }
            org.spigotmc.WatchdogThread.tick();
        }

        long elapsedSec = (System.currentTimeMillis() - start) / 1000;
        LOGGER.info("[The Archive] Resumed dedup map: {} UUIDs from {} completed regions in {}s",
                    uuidMap.size(), rescanned.get(), elapsedSec);
    }

    /**
     * Mark a rescan as failed: record it in {@code failures} and remove the
     * key from {@code completed} so the main pass re-cleans the region and
     * its UUIDs reach {@code uuidMap}. Used by both the in-lambda catch and
     * the drain-loop cancel-site; the latter cannot rely on the in-lambda
     * catch because a cancel may bypass the task body (still queued) or
     * fire before the worker observes the interrupt.
     */
    private static void markRescanFailed(String key, Set<String> completed, Failures failures) {
        failures.recordRegion("rescan:" + key);
        completed.remove(key);
    }

    private static void rescanRegion(
        RegionStorageInfo info, Path regionPath, Path regionFolder, int rx, int rz, FolderKind kind,
        Failures failures
    ) throws IOException {
        // sync=false: rescan is read-only (no writes back to the region), so
        // DSYNC is a strict no-op cost. Matches the main-pass open at processRegion
        // and silences the would-be confusion of a reader observing an
        // inconsistency between the two RegionFile opens.
        try (RegionFile region = new RegionFile(info, regionPath, regionFolder, false)) {
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
                        // Mirror the main-pass processChunk catch: log + record so
                        // a per-chunk corruption surfaces in the FAILED summary and
                        // the chunk-sample buffer instead of vanishing silently.
                        // The "rescan-chunk" prefix disambiguates from main-pass
                        // chunk failures in the same key namespace.
                        String chunkKey = "r." + rx + "." + rz + " rescan-chunk(" + pos.x() + "," + pos.z() + ")";
                        LOGGER.error("[The Archive] {} failed: {}", chunkKey, ex.toString());
                        failures.recordChunk(chunkKey);
                        continue;
                    }
                    if (kind == FolderKind.REGION && root.getCompound("Level").isPresent()) continue;
                    ListTag entities = root.getListOrEmpty(entitiesKey);
                    for (int i = 0; i < entities.size(); i++) {
                        CompoundTag entity = entities.getCompoundOrEmpty(i);
                        UuidResult r = UuidDecoder.decodeUuid(entity);
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

    private static Set<String> readProgress(Path path, Failures failures) {
        if (!Files.exists(path)) return ConcurrentHashMap.newKeySet();
        try {
            Set<String> set = ConcurrentHashMap.newKeySet();
            for (String line : Files.readAllLines(path)) {
                if (!line.isBlank() && !line.startsWith("#")) set.add(line.trim());
            }
            return set;
        } catch (IOException ex) {
            // Escalate to ERROR (was WARN) so a corrupt or unreadable progress
            // file surfaces above the pass's INFO lines; the operator otherwise
            // loses every region already marked complete and re-walks the whole
            // pipeline from zero (a multi-hour cost on the 1.1 TB main archive).
            // Counter bump makes the loss visible at-a-glance in the summary.
            LOGGER.error("[The Archive] Failed to read clean progress file, starting fresh: {}", ex.getMessage());
            failures.progressReadFailed.incrementAndGet();
            return ConcurrentHashMap.newKeySet();
        }
    }

    private static synchronized void appendProgress(Path path, String entry, Failures failures) {
        try {
            Files.writeString(path, entry + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException ex) {
            // Escalate to ERROR (was WARN) so a filling disk surfaces above the
            // per-region INFO lines, and bump a counter so the run-end summary
            // can flag a pass that completed in-memory but did not record its
            // progress on disk (a SIGKILL would then redo the whole pass).
            LOGGER.error("[The Archive] Failed to append clean progress for {}: {}", entry, ex.getMessage());
            failures.progressAppendFailed.incrementAndGet();
        }
    }

    private static Path totalsFilePath(MinecraftServer server) {
        return server.storageSource.getLevelDirectory().path()
            .resolve(".archive-clean-totals.txt");
    }

    /**
     * Read the persisted Totals snapshot from a prior run. Returns an empty
     * map if the file is absent or unreadable; resume then starts from zero
     * and the resumed run's summary line under-reports the prior run's
     * already-finished work by exactly the lost snapshot. Tolerated rather
     * than failing the pass because a missing/malformed totals file does not
     * compromise correctness of the dirt strip itself; the strip results are
     * on disk in the .mca files, only the cross-run summary is degraded.
     */
    private static Map<String, Long> readTotals(Path path) {
        if (!Files.exists(path)) return Map.of();
        try {
            Map<String, Long> map = new HashMap<>();
            for (String line : Files.readAllLines(path)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                try {
                    map.put(key, Long.parseLong(value));
                } catch (NumberFormatException ex) {
                    LOGGER.warn("[The Archive] Skipping malformed clean-totals entry: {}", line);
                }
            }
            return map;
        } catch (IOException ex) {
            LOGGER.warn("[The Archive] Failed to read clean totals file, starting fresh: {}", ex.getMessage());
            return Map.of();
        }
    }

    /**
     * Rewrite the full Totals snapshot atomically. Writes to a sibling temp
     * file with SYNC, then atomic-moves into place; a SIGKILL between the
     * write and the move leaves the previous snapshot intact, a SIGKILL after
     * the move loses zero state. Synchronized because multiple workers may
     * finish their region concurrently and each calls this. Caller must
     * invoke BEFORE appendProgress in the submit lambda: if progress is
     * appended before totals are snapshotted and a SIGKILL strikes between,
     * the region is marked done but its strip contributions never accrue,
     * leaving the resumed-run summary undercount the missing region's strips.
     */
    private static synchronized void writeTotalsSnapshot(Path path, Totals totals, Failures failures) {
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try {
            Files.writeString(tmp, String.join("\n", totals.toLines()) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            LOGGER.error("[The Archive] Failed to write clean totals snapshot: {}", ex.getMessage());
            failures.totalsWriteFailed.incrementAndGet();
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
        // Progress-marker write failure counter. Bumped from appendProgress's
        // catch when Files.writeString throws on the SIGKILL-safe progress file.
        // The region's work is durable at this point but the resume marker is
        // not; a non-zero count after the pass means a SIGKILL+restart will
        // redo every region whose marker never landed.
        final AtomicLong progressAppendFailed = new AtomicLong();
        // Totals-snapshot write failure counter. Bumped from
        // writeTotalsSnapshot when the temp-write or atomic-move throws. A
        // non-zero count means a SIGKILL after that region's strip and before
        // the next successful snapshot will leave that region's contribution
        // unrecoverable in the resume totals (the region will be re-walked
        // because progress wasn't appended, but its strips will accrue zero
        // since the disk is already clean).
        final AtomicLong totalsWriteFailed = new AtomicLong();
        // Drain-loop interrupt counter. Bumped from the per-future drain (both
        // processFolder and rescanCompletedRegions) when future.get throws
        // InterruptedException, i.e. the drain thread itself is being torn
        // down. The interrupt is re-asserted on the thread so the caller can
        // observe it; this counter surfaces that the run did not drain cleanly.
        final AtomicLong drainInterrupted = new AtomicLong();
        // Progress-file read failure counter. Bumped from readProgress when
        // Files.readAllLines throws (corrupt or unreadable .archive-clean-progress.txt).
        // A non-zero value means resume started from zero rather than the prior
        // run's checkpoint, so the entire pass was re-walked.
        final AtomicLong progressReadFailed = new AtomicLong();

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

        List<String> toLines() {
            List<String> out = new ArrayList<>(8);
            out.add("chunksRewritten=" + chunksRewritten.get());
            out.add("invalidAttrsStripped=" + invalidAttrsStripped.get());
            out.add("ghostBesStripped=" + ghostBesStripped.get());
            out.add("beCoordMismatchStripped=" + beCoordMismatchStripped.get());
            out.add("beTypeMismatchStripped=" + beTypeMismatchStripped.get());
            out.add("uuidDupsStripped=" + uuidDupsStripped.get());
            out.add("unparseableUuid=" + unparseableUuid.get());
            out.add("legacyChunksSkipped=" + legacyChunksSkipped.get());
            return out;
        }

        void loadAdd(Map<String, Long> values) {
            chunksRewritten.addAndGet(values.getOrDefault("chunksRewritten", 0L));
            invalidAttrsStripped.addAndGet(values.getOrDefault("invalidAttrsStripped", 0L));
            ghostBesStripped.addAndGet(values.getOrDefault("ghostBesStripped", 0L));
            beCoordMismatchStripped.addAndGet(values.getOrDefault("beCoordMismatchStripped", 0L));
            beTypeMismatchStripped.addAndGet(values.getOrDefault("beTypeMismatchStripped", 0L));
            uuidDupsStripped.addAndGet(values.getOrDefault("uuidDupsStripped", 0L));
            unparseableUuid.addAndGet(values.getOrDefault("unparseableUuid", 0L));
            legacyChunksSkipped.addAndGet(values.getOrDefault("legacyChunksSkipped", 0L));
        }
    }

}
