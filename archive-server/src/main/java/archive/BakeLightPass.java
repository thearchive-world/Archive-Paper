package archive;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction8;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import ca.spottedleaf.moonrise.patches.starlight.light.StarLightEngine;
import ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface;
import org.slf4j.Logger;

/**
 * Bake-light pass. Strips runtime load-time cost off legacy chunks by
 * baking isLightOn, finalized heightmaps, and resolved UpgradeData
 * (Indices, Sides, neighbour-tick lists) into the on-disk chunk. Reuses
 * {@link DirectNbtUpgrader}'s pass-chain envelope (worker pool,
 * tick-thread pump, per-future deadline, try-catch, resumable progress
 * file). New parts are the 3x3 region working set, per-worker chunk
 * cache backed by a hand-rolled LevelAccessor stub, Starlight bake on
 * raw ProtoChunks, and a journal-replay tail pass for cross-region
 * writes.
 *
 * <p>For each owned chunk with outstanding work: run the own-chunk
 * UpgradeData walk and side-strip handler against a {@link
 * BakeLevelAccessor} stub; build a {@link ProtoChunk} backed by the
 * cached {@link LevelChunkSection} array, prime the {@link
 * ChunkStatus#FULL} heightmaps, and run Starlight's {@link
 * StarLightInterface#lightChunk} against a {@link BakeLightChunkGetter}
 * over the same 3x3 cache; merge the baked heightmaps and section light
 * nibbles back into the parsed record via {@link
 * SerializableChunkData#copyOf}. Replay {@code
 * UpgradeData.neighbor_block_ticks} and {@code neighbor_fluid_ticks}
 * into target chunks' on-disk tick lists, routing intra-region appends
 * through the worker cache and cross-region appends through a
 * per-worker {@link BakeLightJournal} drained by a final tail pass.
 * Cross-region writes from the walk (LEAVES BFS, side-strip, CHEST
 * swapContents) land in the same journal: {@link
 * BakeLightJournal#DISCRIMINANT_BLOCK_STATE} for packed BlockState ids,
 * {@link BakeLightJournal#DISCRIMINANT_BLOCK_ENTITY_NBT} for raw BE
 * NBT. The tail pass dispatches on discriminant: tick records append to
 * {@link ChunkAccess.PackedTicks}, block-state records write into the
 * target section's {@link PalettedContainer}, BE-NBT records replace
 * (or append) the matching {@link SerializableChunkData#blockEntities}
 * entry. Skip-if-baked fires only when {@link UpgradeData} is empty,
 * carries no neighbour ticks, {@code lightCorrect} is true, and the
 * heightmap map is populated.
 */
public final class BakeLightPass {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern REGION_FILE_REGEX =
        Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca$");
    // Same envelope tuning as DirectNbtUpgrader; rationale documented there.
    private static final long PER_REGION_TIMEOUT_MILLIS = 30L * 60 * 1000;
    private static final long FINAL_DRAIN_TIMEOUT_MILLIS = 5L * 60 * 1000;
    private static final int CHUNK_FAILURE_SAMPLE_CAP = 100;
    private static final long PROGRESS_LOG_INTERVAL_MILLIS = 30L * 1000;

    private BakeLightPass() {}

    public static void run(MinecraftServer server) {
        long startMillis = System.currentTimeMillis();
        int threadCount = ArchiveSettings.upgradeWorkerCount();
        LOGGER.info("[The Archive] Starting bake-light pass ({} workers)...", threadCount);

        Path progressFile = progressFilePath(server);
        Set<String> completed = readProgress(progressFile);
        if (!completed.isEmpty()) {
            LOGGER.info("[The Archive] Resuming with {} regions already complete", completed.size());
        }

        Path worldRoot = server.storageSource.getLevelDirectory().path();
        BakeLightJournal.JournalRegistry journals = new BakeLightJournal.JournalRegistry(worldRoot);

        Failures failures = new Failures();
        long totalChunks = 0;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount,
            new ThreadFactoryBuilder().setNameFormat("archive-bakelight-%d").setDaemon(true).build());

        try {
            for (ServerLevel level : server.getAllLevels()) {
                totalChunks += processLevel(server, level, pool, completed, progressFile, journals, failures);
            }
        } finally {
            pool.shutdown();
            awaitWithWatchdogTicks(pool, failures);
            journals.closeAll();
        }

        // Tail pass: drain every worker's journal plus any orphans on disk left
        // from prior interrupted runs. Single-threaded; per-record fail-soft.
        // Picks up journal files by directory scan rather than registry handle
        // so a SIGKILL between region-force and progress-append (in either
        // this run or a prior one) doesn't lose cross-region writes.
        runJournalReplayTailPass(server, worldRoot, failures);

        long elapsedSec = (System.currentTimeMillis() - startMillis) / 1000;
        long tailMalformed = failures.tailPassMalformedRecords.get();
        long borderLoadFail = failures.borderLoadFailed.get();
        long progressFailed = failures.progressAppendFailed.get();
        long crossRegionIoFail = failures.crossRegionWritesIoFailed.get();
        if (failures.regionCount.get() > 0 || failures.chunkCount.get() > 0 || tailMalformed > 0 || borderLoadFail > 0 || progressFailed > 0 || crossRegionIoFail > 0) {
            LOGGER.error("[The Archive] Bake-light FAILED: {} region failures, {} chunk failures, {} border-load failures, {} cross-region journal IO failures, {} tail-pass malformed records, {} progress-marker IO failures, {} chunks walked in {}s",
                         failures.regionCount.get(), failures.chunkCount.get(), borderLoadFail, crossRegionIoFail, tailMalformed, progressFailed, totalChunks, elapsedSec);
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
            LOGGER.error("[The Archive] Progress file retained at {} for retry.", progressFile);
        } else {
            try {
                Files.deleteIfExists(progressFile);
            } catch (IOException ex) {
                LOGGER.warn("[The Archive] Failed to delete progress file: {}", ex.getMessage());
            }
            LOGGER.info(
                "[The Archive] Bake-light complete: {} chunks parsed, {} baked, ticks replayed={} (dropped: distance={} missing={} dedup={}), cross-region writes journaled={} applied={} (dropped: missing-target={} io-failed={}), tail-pass malformed={}, border-load failures={}, progress-marker IO failures={} in {}s",
                totalChunks, failures.chunksBaked.get(),
                failures.ticksReplayed.get(),
                failures.ticksDroppedDistanceFilter.get(),
                failures.ticksDroppedMissingTarget.get(),
                failures.ticksDroppedDedup.get(),
                failures.crossRegionWritesJournaled.get(),
                failures.crossRegionWritesApplied.get(),
                failures.crossRegionWritesMissingTarget.get(),
                failures.crossRegionWritesIoFailed.get(),
                tailMalformed,
                borderLoadFail,
                progressFailed,
                elapsedSec);
        }
    }

    private static long processLevel(
        MinecraftServer server, ServerLevel level, ExecutorService pool,
        Set<String> completed, Path progressFile,
        BakeLightJournal.JournalRegistry journals, Failures failures
    ) {
        Identifier dim = level.dimension().identifier();
        Path dimRoot = server.storageSource.getDimensionPath(level.dimension());
        Path folderPath = dimRoot.resolve("region");

        File[] files = folderPath.toFile().listFiles((d, n) -> n.endsWith(".mca"));
        if (files == null || files.length == 0) {
            LOGGER.info("[The Archive] Bake-light {}: no region files, skipping", dim);
            return 0;
        }
        LOGGER.info("[The Archive] Bake-light {}: {} region files queued", dim, files.length);

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
            String key = dim + " bakelight " + rx + " " + rz;
            if (completed.contains(key)) {
                regionCounter.incrementAndGet();
                continue;
            }

            RegionStorageInfo info = new RegionStorageInfo(
                server.storageSource.getLevelId(), level.dimension(), "chunk");
            Future<?> future = pool.submit(() -> {
                try {
                    RegionResult result = processRegion(info, level, folderPath, rx, rz, journals, failures);
                    chunkCounter.addAndGet(result.parsedChunks());
                    regionCounter.incrementAndGet();
                    // Per-chunk failures (bake / write-back), border-load failures
                    // (corrupt or truncated neighbour region file), and cross-region
                    // journal IO failures (per-entry BE-index appends or accessor
                    // LEAVES BFS / side-strip / CHEST writes) all keep this region
                    // OUT of the progress file so resume re-walks it. Per-chunk
                    // failures are already in failures.chunkCount; border-load
                    // failures in failures.borderLoadFailed; cross-region IO
                    // failures in failures.crossRegionWritesIoFailed.
                    if (result.perChunkFailures() == 0
                        && result.borderLoadFailures() == 0
                        && result.crossRegionWritesIoFailed() == 0) {
                        appendProgress(progressFile, key, failures);
                    }
                    long now = System.currentTimeMillis();
                    long last = lastLogMillis.get();
                    if (now - last >= PROGRESS_LOG_INTERVAL_MILLIS && lastLogMillis.compareAndSet(last, now)) {
                        int doneNow = regionCounter.get();
                        long chunksNow = chunkCounter.get();
                        long rate = chunksNow * 1000L / Math.max(1, now - folderStartMillis);
                        LOGGER.info("[The Archive]   bake-light {}: {} / {} regions ({} chunks, {} ch/s)",
                                    dim, doneNow, totalRegions, chunksNow, rate);
                    }
                } catch (Throwable t) {
                    LOGGER.error("[The Archive] bake-light {} region r.{}.{} failed",
                                 dim, rx, rz, t);
                    failures.recordRegion(key);
                }
            });
            submitted.add(new SubmittedRegion(key, future));
        }

        for (SubmittedRegion task : submitted) {
            long deadline = System.currentTimeMillis() + PER_REGION_TIMEOUT_MILLIS;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    task.future().cancel(true);
                    LOGGER.error("[The Archive] bake-light {} region task exceeded {}m, cancelled: {}",
                                 dim, PER_REGION_TIMEOUT_MILLIS / 60_000, task.key());
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

        LOGGER.info("[The Archive]   bake-light {}: complete, {} chunks walked", dim, chunkCounter.get());
        return chunkCounter.get();
    }

    /**
     * Worker flow for one region. Sequence:
     * <ol>
     *   <li>Open the center {@link RegionFile} (non-DSYNC, same as {@link
     *       DirectNbtUpgrader}; durability at close via {@code file.force(true)}).</li>
     *   <li>Parse all 1024 populated slots into {@link CachedChunk} entries
     *       marked {@code owned=true}.</li>
     *   <li>Open each of up to 8 neighbour region files. Parse the 32 edge
     *       chunks of each cardinal neighbour and the 1 corner chunk of each
     *       diagonal neighbour into the cache marked {@code owned=false}.
     *       Missing region files and missing slots tolerated silently.</li>
     *   <li>For every owned cache entry whose {@link UpgradeData} has any
     *       work outstanding (Indices, Sides, or neighbour ticks), run the
     *       own-chunk walk, drain LEAVES chunky-fixers, run the side-strip
     *       handler, then replay neighbour ticks (intra-region targets
     *       deposit into the target {@link CachedChunk}'s pending tick lists;
     *       cross-region targets are journaled). Then bake light + heightmaps.</li>
     *   <li>Region finalize, in this order:
     *     <ol type="a">
     *       <li>For every dirty owned entry, flush any pending tick appends
     *           into {@code entry.data} via a fresh {@link SerializableChunkData}
     *           record holding the extended {@link ChunkAccess.PackedTicks}.</li>
     *       <li>Write the entry to the center region file.</li>
     *       <li>{@code fsync} the worker's journal.</li>
     *       <li>Close the region file, which calls {@code file.force(true)}.</li>
     *       <li>Caller appends the region key to the progress file.</li>
     *     </ol>
     *     The order is load-bearing for SIGKILL safety: the fsync-then-force-then-progress sequence ensures every state a SIGKILL can leave on disk is resumable.</li>
     * </ol>
     *
     * <p>Per-chunk try-catch: any throw inside the walk or write-back logs a
     * partial-bake counter and continues with the rest of the region. The
     * old NBT for that chunk stays on disk untouched; runtime first-load
     * lazy-path code fixes the residual on its own.
     */
    /**
     * Per-region result. {@code perChunkFailures} counts bake-time and
     * write-back failures observed in this region only; {@code borderLoadFailures}
     * counts neighbour regions whose file existed but failed to load (the
     * owned-region's bake at the boundary then runs against a partial 3x3
     * cache and produces wrong light along that edge); {@code crossRegionWritesIoFailed}
     * snapshots {@link BakeLevelAccessor#crossRegionWritesIoFailed} at finalize
     * (per-entry BE-index journal appends from the non-owned dirty BE loop
     * plus accessor-driven LEAVES BFS / side-strip / CHEST writes). The submit
     * lambda uses all three counters to decide whether the region key is safe
     * to append to the progress file; any non-zero leaves the key out so a
     * re-run gets another shot. Region-level throws bypass this path and are
     * caught by the lambda's outer try.
     */
    private record RegionResult(
        long parsedChunks,
        int perChunkFailures,
        int borderLoadFailures,
        int crossRegionWritesIoFailed
    ) {}

    private static RegionResult processRegion(
        RegionStorageInfo info, ServerLevel level, Path regionFolder, int rx, int rz,
        BakeLightJournal.JournalRegistry journals, Failures failures
    ) throws IOException {
        Path regionPath = regionFolder.resolve("r." + rx + "." + rz + ".mca");
        if (!Files.exists(regionPath)) return new RegionResult(0, 0, 0, 0);
        String regionLabel = "r." + rx + "." + rz;
        Identifier dim = level.dimension().identifier();
        Map<Long, CachedChunk> cache = new HashMap<>(1156);
        long parsed = 0;
        int perChunkFailures = 0;
        int borderLoadFailures;
        BakeLightJournal journal = journals.forCurrentThread();
        try (RegionFile region = new RegionFile(info, regionPath, regionFolder, false)) {
            parsed = loadCenter(region, level, cache, regionLabel, failures, rx, rz);
            borderLoadFailures = loadBorder(info, level, regionFolder, rx, rz, cache, failures);

            BakeLevelAccessor accessor = new BakeLevelAccessor(level, cache, journal, dim);
            BakeLightChunkGetter lightAccess = new BakeLightChunkGetter(level, cache);
            StarLightInterface lightInterface = new StarLightInterface(
                lightAccess,
                level.dimensionType().hasSkyLight(),
                true,
                level.getChunkSource().getLightEngine()
            );
            long baked = 0;
            for (CachedChunk entry : new ArrayList<>(cache.values())) {
                if (!entry.owned) continue;
                UpgradeData ud = entry.data.upgradeData();
                boolean upgradeNeeded = hasUpgradeWork(ud);
                boolean lightNeeded = !entry.data.lightCorrect() || entry.data.heightmaps().isEmpty();
                if (!upgradeNeeded && !lightNeeded) continue;
                ChunkPos pos = entry.data.chunkPos();
                try {
                    if (upgradeNeeded) {
                        bakeChunkUpgrade(accessor, level, entry, pos);
                        replayNeighborTicks(level, cache, journal, dim, entry, ud, pos, failures);
                    }
                    bakeChunkLight(level, lightInterface, entry);
                    entry.dirty = true;
                    baked++;
                } catch (Throwable t) {
                    String chunkKey = regionLabel + " bake(" + pos.x() + "," + pos.z() + ")";
                    LOGGER.error("[The Archive] bake-light {} failed: {}", chunkKey, t.toString(), t);
                    failures.recordChunk(chunkKey);
                    perChunkFailures++;
                }
            }
            failures.chunksBaked.addAndGet(baked);

            // Region finalize. Order matters for SIGKILL safety:
            //   1. Journal non-owned dirty BE indices. Vanilla's CHEST.swapContents
            //      mutates BEs on both sides of a cross-region chest pair; the
            //      journal captures the non-owned half (the owned half lands in
            //      step 3's blockEntities() rebuild). Counted as journaled writes
            //      so the totals add up against the tail-pass counter family.
            //   2. fsync the worker's journal so cross-region writes are durable
            //      BEFORE any owned chunk's UpgradeData-stripped form is written
            //      to the source region. If fsync throws, the catch re-throws out of
            //      processRegion: the writeback loop never runs, try-with-resources
            //      closes the region, and file.force(true) flushes a region whose
            //      contents are unchanged from when the worker opened it. The
            //      source chunks retain their UpgradeData on disk, so a re-run
            //      walks them again and re-emits the cross-region writes.
            //   3. Flush pending ticks + BE indices into each owned dirty entry,
            //      then write the chunk via the still-open RegionFile.
            //   4. Region close happens at try-with-resources scope exit, which
            //      calls file.force(true). This is the source-chunk durability
            //      barrier; the journal is already fsynced.
            //   5. Caller (the pool.submit lambda) appends the region key to the
            //      progress file after this method returns successfully.
            for (CachedChunk entry : cache.values()) {
                if (entry.owned) continue;
                if (!entry.blockEntityIndexDirty()) continue;
                try {
                    long before = journal.recordsWritten();
                    entry.journalBlockEntityIndex(accessor, journal, dim);
                    long appended = journal.recordsWritten() - before;
                    accessor.crossRegionWritesJournaled.addAndGet(appended);
                } catch (IOException ex) {
                    LOGGER.error("[The Archive] bake-light {}: BE-index journal failed for non-owned c({},{}): {}",
                                 regionLabel, entry.data.chunkPos().x(), entry.data.chunkPos().z(), ex.getMessage());
                    accessor.crossRegionWritesIoFailed.incrementAndGet();
                }
            }

            // Propagate accessor's cross-region counters into Failures BEFORE
            // the fsync so the metrics survive an fsync throw.
            failures.crossRegionWritesJournaled.addAndGet(accessor.crossRegionWritesJournaled.get());
            failures.crossRegionWritesIoFailed.addAndGet(accessor.crossRegionWritesIoFailed.get());

            try {
                journal.fsync();
            } catch (IOException ex) {
                LOGGER.error("[The Archive] bake-light journal fsync failed for region {} ({}); owned chunk writebacks skipped, source on-disk state preserved for retry",
                             regionLabel, ex.getMessage());
                failures.recordRegion(dim + " bakelight " + rx + " " + rz);
                throw ex;
            }

            for (CachedChunk entry : cache.values()) {
                if (!entry.owned) continue;
                if (entry.blockEntityIndexDirty()) {
                    entry.flushBlockEntityIndexInto(accessor);
                    entry.dirty = true;
                }
                if (entry.hasPendingTicks()) {
                    entry.flushPendingTicksInto(level);
                    entry.dirty = true;
                }
                if (!entry.dirty) continue;
                ChunkPos pos = entry.data.chunkPos();
                try {
                    writeChunk(region, pos, entry.data);
                } catch (Throwable t) {
                    String chunkKey = regionLabel + " writeback(" + pos.x() + "," + pos.z() + ")";
                    LOGGER.error("[The Archive] bake-light {} failed: {}", chunkKey, t.toString(), t);
                    failures.recordChunk(chunkKey);
                    perChunkFailures++;
                }
            }
            // Snapshot AFTER writeback. The accessor's counter accumulates from
            // both the non-owned BE-index journal loop above and any
            // accessor-driven LEAVES BFS / side-strip / CHEST writes that hit
            // an IO failure during bakeChunkUpgrade; a non-zero count here means
            // at least one cross-region append was lost and the region must NOT
            // be marked complete.
            int crossRegionIoFailed = (int) Math.min(Integer.MAX_VALUE, accessor.crossRegionWritesIoFailed.get());
            return new RegionResult(parsed, perChunkFailures, borderLoadFailures, crossRegionIoFailed);
        }
    }

    /**
     * True if the chunk still has any UpgradeData work to do: Indices, Sides,
     * or pending neighbour ticks. {@link UpgradeData#isEmpty} only consults
     * Indices and Sides, so a chunk carrying neighbour ticks alone (e.g. a
     * legacy chunk whose only DFU side-effect relocated some ticks) would
     * skip and lose them.
     */
    private static boolean hasUpgradeWork(UpgradeData ud) {
        if (!ud.isEmpty()) return true;
        if (!UpgradeDataReflect.neighborBlockTicks(ud).isEmpty()) return true;
        if (!UpgradeDataReflect.neighborFluidTicks(ud).isEmpty()) return true;
        return false;
    }

    /** Parse all populated slots of the center region into the cache as owned. */
    private static long loadCenter(
        RegionFile region, ServerLevel level, Map<Long, CachedChunk> cache,
        String regionLabel, Failures failures, int rx, int rz
    ) throws IOException {
        long parsed = 0;
        int xOffset = rx << 5;
        int zOffset = rz << 5;
        for (int dx = 0; dx < 32; dx++) {
            for (int dz = 0; dz < 32; dz++) {
                ChunkPos pos = new ChunkPos(dx + xOffset, dz + zOffset);
                if (!region.doesChunkExist(pos)) continue;
                try {
                    CachedChunk entry = loadChunk(region, pos, level, true);
                    if (entry == null) continue;
                    cache.put(pos.pack(), entry);
                    parsed++;
                } catch (Exception ex) {
                    String chunkKey = regionLabel + " parse(" + pos.x() + "," + pos.z() + ")";
                    LOGGER.error("[The Archive] bake-light {} failed: {}", chunkKey, ex.toString());
                    failures.recordChunk(chunkKey);
                }
            }
        }
        return parsed;
    }

    /**
     * Parse the 1-chunk-wide border around the center region. Reads up to 8
     * neighbour region files (4 cardinal, 4 diagonal); each missing-or-empty
     * is silently tolerated. Returns the count of neighbour regions whose
     * file existed on disk but failed to load; the caller treats a non-zero
     * count as a region-level failure so the owned region's progress key
     * stays out of the progress file and a re-run with a potentially-repaired
     * neighbour gets another shot.
     */
    private static int loadBorder(
        RegionStorageInfo info, ServerLevel level, Path regionFolder, int rx, int rz,
        Map<Long, CachedChunk> cache, Failures failures
    ) {
        int failed = 0;
        // 4 cardinal neighbours contribute one 32-chunk edge each.
        failed += loadEdge(info, level, regionFolder, rx - 1, rz, 31, -1, -1, 0, 32, cache, failures);
        failed += loadEdge(info, level, regionFolder, rx + 1, rz, 0,  -1, -1, 0, 32, cache, failures);
        failed += loadEdge(info, level, regionFolder, rx, rz - 1, -1, 31, 0,  -1, 32, cache, failures);
        failed += loadEdge(info, level, regionFolder, rx, rz + 1, -1, 0,  0,  -1, 32, cache, failures);

        // 4 diagonal neighbours contribute one corner chunk each.
        failed += loadEdge(info, level, regionFolder, rx - 1, rz - 1, 31, 31, -1, -1, 1, cache, failures);
        failed += loadEdge(info, level, regionFolder, rx + 1, rz - 1, 0,  31, -1, -1, 1, cache, failures);
        failed += loadEdge(info, level, regionFolder, rx - 1, rz + 1, 31, 0,  -1, -1, 1, cache, failures);
        failed += loadEdge(info, level, regionFolder, rx + 1, rz + 1, 0,  0,  -1, -1, 1, cache, failures);
        return failed;
    }

    /**
     * Read 1..32 slots from a neighbour region. Edge specifiers:
     * {@code fixedX}/{@code fixedZ} = pinned coord (use -1 to iterate),
     * {@code baseX}/{@code baseZ} = iteration base when -1 was passed for
     * the corresponding fixed coord (use 0 for full edge, ignored for
     * pinned). {@code count} is the slot count to read. Cardinal edges pass
     * one pinned coord and count=32; corners pin both and count=1.
     * Returns 1 if the neighbour region file existed but the load threw
     * mid-flight (truncated header, corrupt sector pointers, etc.); returns
     * 0 if the load succeeded OR the file was missing (the latter is
     * tolerated as the documented "no neighbour" state).
     */
    private static int loadEdge(
        RegionStorageInfo info, ServerLevel level, Path regionFolder,
        int neighbourRx, int neighbourRz,
        int fixedX, int fixedZ, int baseX, int baseZ, int count,
        Map<Long, CachedChunk> cache, Failures failures
    ) {
        Path neighbourPath = regionFolder.resolve("r." + neighbourRx + "." + neighbourRz + ".mca");
        if (!Files.exists(neighbourPath)) return 0;
        try (RegionFile region = new RegionFile(info, neighbourPath, regionFolder, false)) {
            int worldX = neighbourRx << 5;
            int worldZ = neighbourRz << 5;
            for (int i = 0; i < count; i++) {
                int localX = fixedX >= 0 ? fixedX : (baseX + i);
                int localZ = fixedZ >= 0 ? fixedZ : (baseZ + i);
                ChunkPos pos = new ChunkPos(worldX + localX, worldZ + localZ);
                if (!region.doesChunkExist(pos)) continue;
                CachedChunk entry = loadChunk(region, pos, level, false);
                if (entry != null) cache.put(pos.pack(), entry);
            }
            return 0;
        } catch (Exception ex) {
            LOGGER.warn("[The Archive] bake-light border load r.{}.{} failed: {}",
                        neighbourRx, neighbourRz, ex.toString());
            failures.borderLoadFailed.incrementAndGet();
            return 1;
        }
    }

    /**
     * Drain every per-worker journal left in {@code worldRoot}, applying each
     * record's cross-region write to its target chunk. Single-threaded and
     * runs after the worker pool has fully terminated.
     *
     * <p>Records are grouped by (dim, target chunk) so each target is loaded,
     * extended, and re-serialized exactly once even if multiple workers wrote
     * to it. {@link RegionFile} handles are cached by (dim, region pos) to
     * amortize the open cost across all target chunks in a region.
     *
     * <p>Failure policy: per-record fail-soft. A malformed payload or a missing target chunk drops only
     * the offending record; other records on the same target apply normally.
     * Chunk-level write-back failure increments {@link Failures#recordChunk}
     * and retains the journal for the next run.
     *
     * <p>Journals are deleted only after the entire tail pass completes with
     * zero chunk-level failures. Partial-failure mode retains every journal
     * file for retry.
     */
    private static void runJournalReplayTailPass(MinecraftServer server, Path worldRoot, Failures failures) {
        List<Path> journalFiles = BakeLightJournal.JournalRegistry.discoverFiles(worldRoot);
        if (journalFiles.isEmpty()) {
            return;
        }

        Map<TailKey, List<BakeLightJournal.Record>> grouped = new LinkedHashMap<>();
        long totalRecords = 0;
        for (Path journal : journalFiles) {
            List<BakeLightJournal.Record> records;
            try {
                records = BakeLightJournal.read(journal);
            } catch (IOException ex) {
                LOGGER.error("[The Archive] bake-light failed to read journal {}: {}", journal, ex.getMessage());
                continue;
            }
            for (BakeLightJournal.Record rec : records) {
                TailKey key = new TailKey(rec.dim(), rec.targetChunkX(), rec.targetChunkZ());
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(rec);
                totalRecords++;
            }
        }

        LOGGER.info("[The Archive]   bake-light tail pass: {} records across {} target chunks in {} journals",
                    totalRecords, grouped.size(), journalFiles.size());

        Map<Identifier, Map<Long, RegionFile>> openRegions = new HashMap<>();
        int chunksApplied = 0;
        int chunksFailed = 0;
        try {
            for (Map.Entry<TailKey, List<BakeLightJournal.Record>> entry : grouped.entrySet()) {
                TailKey tk = entry.getKey();
                ServerLevel level = resolveLevel(server, tk.dim());
                if (level == null) {
                    LOGGER.warn("[The Archive]   bake-light tail pass: unknown dim {} ({} records dropped)",
                                tk.dim(), entry.getValue().size());
                    countMissingTarget(entry.getValue(), failures);
                    continue;
                }
                int rx = tk.chunkX() >> 5;
                int rz = tk.chunkZ() >> 5;
                Map<Long, RegionFile> dimRegions = openRegions.computeIfAbsent(tk.dim(), k -> new HashMap<>());
                long regionKey = ChunkPos.pack(rx, rz);
                RegionFile region = dimRegions.get(regionKey);
                if (region == null) {
                    region = openRegionForTailPass(server, level, rx, rz);
                    if (region == null) {
                        countMissingTarget(entry.getValue(), failures);
                        continue;
                    }
                    dimRegions.put(regionKey, region);
                }
                ChunkPos pos = new ChunkPos(tk.chunkX(), tk.chunkZ());
                try {
                    if (replayRecordsAtTarget(level, region, pos, entry.getValue(), failures)) {
                        chunksApplied++;
                    }
                } catch (Throwable t) {
                    chunksFailed++;
                    String chunkKey = "tail " + tk.dim() + " r." + rx + "." + rz + " c(" + pos.x() + "," + pos.z() + ")";
                    LOGGER.error("[The Archive] bake-light tail pass {} failed: {}", chunkKey, t.toString(), t);
                    failures.recordChunk(chunkKey);
                }
            }
        } finally {
            for (Map<Long, RegionFile> dimMap : openRegions.values()) {
                for (RegionFile rf : dimMap.values()) {
                    try {
                        rf.close();
                    } catch (IOException ex) {
                        LOGGER.warn("[The Archive] bake-light tail pass: region close failed: {}", ex.getMessage());
                    }
                }
            }
        }

        LOGGER.info("[The Archive]   bake-light tail pass complete: {} target chunks updated, {} failed",
                    chunksApplied, chunksFailed);

        if (chunksFailed == 0) {
            for (Path journal : journalFiles) {
                try {
                    Files.deleteIfExists(journal);
                } catch (IOException ex) {
                    LOGGER.warn("[The Archive] bake-light: failed to delete journal {}: {}", journal, ex.getMessage());
                }
            }
        } else {
            LOGGER.warn("[The Archive] bake-light tail pass had {} chunk failures; journals retained for retry", chunksFailed);
        }
    }

    private static @org.jspecify.annotations.Nullable ServerLevel resolveLevel(MinecraftServer server, Identifier dim) {
        return server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
    }

    private static @org.jspecify.annotations.Nullable RegionFile openRegionForTailPass(
        MinecraftServer server, ServerLevel level, int rx, int rz
    ) {
        Path dimRoot = server.storageSource.getDimensionPath(level.dimension());
        Path regionFolder = dimRoot.resolve("region");
        Path regionPath = regionFolder.resolve("r." + rx + "." + rz + ".mca");
        if (!Files.exists(regionPath)) {
            LOGGER.warn("[The Archive] bake-light tail pass: target region r.{}.{}.mca missing in {}", rx, rz, level.dimension().identifier());
            return null;
        }
        RegionStorageInfo info = new RegionStorageInfo(server.storageSource.getLevelId(), level.dimension(), "chunk");
        try {
            return new RegionFile(info, regionPath, regionFolder, false);
        } catch (IOException ex) {
            LOGGER.warn("[The Archive] bake-light tail pass: failed to open r.{}.{}.mca: {}", rx, rz, ex.getMessage());
            return null;
        }
    }

    /**
     * Increment the missing-target counter family for the given record list.
     * Splits the count between the tick-replay counter family
     * ({@link Failures#ticksDroppedMissingTarget}) and the cross-region write
     * counter family ({@link Failures#crossRegionWritesMissingTarget})
     * so the pass-end summary doesn't conflate.
     */
    private static void countMissingTarget(List<BakeLightJournal.Record> records, Failures failures) {
        for (BakeLightJournal.Record rec : records) {
            switch (rec.discriminant()) {
                case BakeLightJournal.DISCRIMINANT_BLOCK_TICK, BakeLightJournal.DISCRIMINANT_FLUID_TICK ->
                    failures.ticksDroppedMissingTarget.incrementAndGet();
                case BakeLightJournal.DISCRIMINANT_BLOCK_STATE, BakeLightJournal.DISCRIMINANT_BLOCK_ENTITY_NBT ->
                    failures.crossRegionWritesMissingTarget.incrementAndGet();
                default -> {}
            }
        }
    }

    /**
     * Apply all journal records for a single target chunk. Loads the chunk via
     * raw {@link NbtIo}, partitions records by discriminant, applies each
     * group to the parsed {@link SerializableChunkData}, and writes back if
     * anything changed. Returns {@code true} iff a write-back happened.
     *
     * <p>Handled discriminants:
     * <ul>
     *   <li>{@link BakeLightJournal#DISCRIMINANT_BLOCK_TICK} /
     *       {@link BakeLightJournal#DISCRIMINANT_FLUID_TICK}: append to
     *       {@link ChunkAccess.PackedTicks} after sentinel resolution and
     *       {@link SavedTick#UNIQUE_TICK_HASH} dedup against the existing list
     *       and the in-batch new list.</li>
     *   <li>{@link BakeLightJournal#DISCRIMINANT_BLOCK_STATE}: decode the
     *       packed-int payload via {@link Block#BLOCK_STATE_REGISTRY} and
     *       write into the target section's {@link PalettedContainer}. If the
     *       section is absent on disk the write is dropped with the missing-
     *       target counter; targets that fall inside an existing section are
     *       guaranteed (the source chunk's bake walk can only emit writes to
     *       its 1-chunk neighbourhood and those chunks must exist for the
     *       walk to have observed them).</li>
     *   <li>{@link BakeLightJournal#DISCRIMINANT_BLOCK_ENTITY_NBT}: decode
     *       the NBT payload via {@link NbtIo#read}; the embedded x/y/z fields
     *       carry the BE position. Replace any existing entry in
     *       {@link SerializableChunkData#blockEntities} that targets the same
     *       position; append if absent. Last writer wins on duplicates.</li>
     * </ul>
     *
     * <p>Light-bake state ({@code lightCorrect=true}, heightmaps,
     * UpgradeData=EMPTY) is preserved verbatim through the rebuild: the
     * target chunk was baked in the worker pass, the tail pass only extends
     * tick lists and section/BE state.
     */
    private static boolean replayRecordsAtTarget(
        ServerLevel level, RegionFile region, ChunkPos pos, List<BakeLightJournal.Record> records, Failures failures
    ) throws IOException {
        CompoundTag chunkTag;
        try (DataInputStream in = region.getChunkDataInputStream(pos)) {
            if (in == null) {
                countMissingTarget(records, failures);
                return false;
            }
            chunkTag = NbtIo.read(in);
        }
        SerializableChunkData data = SerializableChunkData.parse(level, level.palettedContainerFactory(), chunkTag);
        if (data == null) {
            countMissingTarget(records, failures);
            return false;
        }

        ObjectOpenCustomHashSet<SavedTick<?>> blockDedup = new ObjectOpenCustomHashSet<>(SavedTick.UNIQUE_TICK_HASH);
        blockDedup.addAll(data.packedTicks().blocks());
        ObjectOpenCustomHashSet<SavedTick<?>> fluidDedup = new ObjectOpenCustomHashSet<>(SavedTick.UNIQUE_TICK_HASH);
        fluidDedup.addAll(data.packedTicks().fluids());

        List<SavedTick<Block>> newBlocks = null;
        List<SavedTick<Fluid>> newFluids = null;
        boolean sectionMutated = false;
        // Per-position BE replacements applied to the parsed list. The journal
        // can carry multiple BE records for the same position (a chunk on the
        // boundary of two regions might receive two passes' worth of writes);
        // last writer wins.
        Map<Long, CompoundTag> beReplacements = null;

        for (BakeLightJournal.Record rec : records) {
            try {
                switch (rec.discriminant()) {
                    case BakeLightJournal.DISCRIMINANT_BLOCK_TICK -> {
                        BakeLightJournal.DecodedTick decoded = BakeLightJournal.decodeTick(rec);
                        Block type = resolveBlockType(level, data, decoded);
                        if (type == null) {
                            failures.ticksDroppedMissingTarget.incrementAndGet();
                            continue;
                        }
                        SavedTick<Block> tick = new SavedTick<>(type, decoded.pos(), decoded.delay(), decoded.priority());
                        if (blockDedup.add(tick)) {
                            if (newBlocks == null) newBlocks = new ArrayList<>();
                            newBlocks.add(tick);
                            failures.ticksReplayed.incrementAndGet();
                        } else {
                            failures.ticksDroppedDedup.incrementAndGet();
                        }
                    }
                    case BakeLightJournal.DISCRIMINANT_FLUID_TICK -> {
                        BakeLightJournal.DecodedTick decoded = BakeLightJournal.decodeTick(rec);
                        Fluid type = resolveFluidType(level, data, decoded);
                        if (type == null) {
                            failures.ticksDroppedMissingTarget.incrementAndGet();
                            continue;
                        }
                        SavedTick<Fluid> tick = new SavedTick<>(type, decoded.pos(), decoded.delay(), decoded.priority());
                        if (fluidDedup.add(tick)) {
                            if (newFluids == null) newFluids = new ArrayList<>();
                            newFluids.add(tick);
                            failures.ticksReplayed.incrementAndGet();
                        } else {
                            failures.ticksDroppedDedup.incrementAndGet();
                        }
                    }
                    case BakeLightJournal.DISCRIMINANT_BLOCK_STATE -> {
                        BlockState state = BakeLightJournal.decodeBlockState(rec);
                        if (state == null) {
                            failures.crossRegionWritesMissingTarget.incrementAndGet();
                            continue;
                        }
                        if (applyBlockStateWrite(level, data, rec.blockPos(), state)) {
                            sectionMutated = true;
                            failures.crossRegionWritesApplied.incrementAndGet();
                        } else {
                            failures.crossRegionWritesMissingTarget.incrementAndGet();
                        }
                    }
                    case BakeLightJournal.DISCRIMINANT_BLOCK_ENTITY_NBT -> {
                        CompoundTag beTag = BakeLightJournal.decodeBlockEntity(rec);
                        if (beReplacements == null) beReplacements = new HashMap<>();
                        BlockPos bePos = BlockEntity.getPosFromTag(pos, beTag);
                        beReplacements.put(bePos.asLong(), beTag);
                        failures.crossRegionWritesApplied.incrementAndGet();
                    }
                    default -> {
                        // Unknown discriminant; tolerated by design so future
                        // writers can be skipped without breaking the parser.
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[The Archive] bake-light tail pass: malformed record at {} ({})", pos, t.toString());
                failures.tailPassMalformedRecords.incrementAndGet();
            }
        }

        if (newBlocks == null && newFluids == null && !sectionMutated && beReplacements == null) {
            return false;
        }

        List<SavedTick<Block>> mergedBlocks = data.packedTicks().blocks();
        if (newBlocks != null) {
            mergedBlocks = new ArrayList<>(mergedBlocks.size() + newBlocks.size());
            mergedBlocks.addAll(data.packedTicks().blocks());
            mergedBlocks.addAll(newBlocks);
        }
        List<SavedTick<Fluid>> mergedFluids = data.packedTicks().fluids();
        if (newFluids != null) {
            mergedFluids = new ArrayList<>(mergedFluids.size() + newFluids.size());
            mergedFluids.addAll(data.packedTicks().fluids());
            mergedFluids.addAll(newFluids);
        }
        List<CompoundTag> mergedBlockEntities = data.blockEntities();
        if (beReplacements != null) {
            mergedBlockEntities = mergeBlockEntityTags(data.blockEntities(), beReplacements, pos);
        }

        SerializableChunkData updated = new SerializableChunkData(
            data.containerFactory(), data.chunkPos(), data.minSectionY(),
            data.lastUpdateTime(), data.inhabitedTime(), data.chunkStatus(),
            data.blendingData(), data.belowZeroRetrogen(), data.upgradeData(),
            data.carvingMask(), data.heightmaps(),
            new ChunkAccess.PackedTicks(mergedBlocks, mergedFluids),
            data.postProcessingSections(), data.lightCorrect(), data.sectionData(),
            data.entities(), mergedBlockEntities, data.structureData(),
            data.persistentDataContainer()
        );
        writeChunk(region, pos, updated);
        return true;
    }

    /**
     * Write a single packed BlockState into the target chunk's parsed
     * section data. Returns {@code true} if the write landed in an existing
     * section, {@code false} if the section is absent on disk (the bake's
     * 3x3 working set guarantees this should not happen in practice, but a
     * cross-region target whose region file was concurrently rewritten could
     * surface a hole; treat as missing rather than throwing).
     */
    private static boolean applyBlockStateWrite(
        ServerLevel level, SerializableChunkData data, BlockPos pos, BlockState state
    ) {
        int sectionY = pos.getY() >> 4;
        int sectionIndex = level.getSectionIndexFromSectionY(sectionY);
        if (sectionIndex < 0) return false;
        LevelChunkSection section = null;
        for (SerializableChunkData.SectionData sd : data.sectionData()) {
            if (level.getSectionIndexFromSectionY(sd.y()) == sectionIndex) {
                section = sd.chunkSection();
                break;
            }
        }
        if (section == null) return false;
        section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state, false);
        return true;
    }

    /**
     * Merge a per-position map of BE replacements into the parsed block-entity
     * list. Entries that match a position in {@code replacements} are
     * substituted; remaining replacements are appended as new entries.
     */
    private static List<CompoundTag> mergeBlockEntityTags(
        List<CompoundTag> existing, Map<Long, CompoundTag> replacements, ChunkPos chunkPos
    ) {
        List<CompoundTag> merged = new ArrayList<>(existing.size() + replacements.size());
        Map<Long, CompoundTag> remaining = new HashMap<>(replacements);
        for (CompoundTag tag : existing) {
            BlockPos pos = BlockEntity.getPosFromTag(chunkPos, tag);
            CompoundTag replacement = remaining.remove(pos.asLong());
            merged.add(replacement != null ? replacement : tag);
        }
        merged.addAll(remaining.values());
        return merged;
    }

    private static @org.jspecify.annotations.Nullable Block resolveBlockType(
        ServerLevel level, SerializableChunkData data, BakeLightJournal.DecodedTick decoded
    ) {
        Identifier id = Identifier.tryParse(decoded.typeName());
        if (id == null) return null;
        Block type = BuiltInRegistries.BLOCK.getValue(id);
        if (type == null) return null;
        if (type == Blocks.AIR) {
            BlockState state = blockStateAtData(level, data, decoded.pos());
            return state != null ? state.getBlock() : Blocks.AIR;
        }
        return type;
    }

    private static @org.jspecify.annotations.Nullable Fluid resolveFluidType(
        ServerLevel level, SerializableChunkData data, BakeLightJournal.DecodedTick decoded
    ) {
        Identifier id = Identifier.tryParse(decoded.typeName());
        if (id == null) return null;
        Fluid type = BuiltInRegistries.FLUID.getValue(id);
        if (type == null) return null;
        if (type == Fluids.EMPTY) {
            BlockState state = blockStateAtData(level, data, decoded.pos());
            return state != null ? state.getFluidState().getType() : Fluids.EMPTY;
        }
        return type;
    }

    private static @org.jspecify.annotations.Nullable BlockState blockStateAtData(
        ServerLevel level, SerializableChunkData data, BlockPos pos
    ) {
        int sectionY = pos.getY() >> 4;
        int sectionIndex = level.getSectionIndexFromSectionY(sectionY);
        if (sectionIndex < 0) return null;
        LevelChunkSection section = null;
        for (SerializableChunkData.SectionData sd : data.sectionData()) {
            if (level.getSectionIndexFromSectionY(sd.y()) == sectionIndex) {
                section = sd.chunkSection();
                break;
            }
        }
        if (section == null) return null;
        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        int localY = pos.getY() & 15;
        return section.getStates().get(localX, localY, localZ);
    }

    private record TailKey(Identifier dim, int chunkX, int chunkZ) {}

    /**
     * Mirrors {@link UpgradeData}{@code .upgrade()} for a single chunk against
     * our {@link BakeLevelAccessor}: own-chunk walk via {@code upgradeInside},
     * chunky-fixer drain (LEAVES BFS), then the 8-direction side-strip
     * handler ({@code upgradeSides}). Neighbour-tick replay is handled by
     * {@link #replayNeighborTicks}, called separately from the bake loop.
     */
    private static void bakeChunkUpgrade(BakeLevelAccessor accessor, ServerLevel level, CachedChunk entry, ChunkPos chunkPos) {
        UpgradeData ud = entry.data.upgradeData();
        bakeUpgradeInside(accessor, level, entry, ud, chunkPos);
        // Drain LEAVES BFS queue (the only CHUNKY fixer currently registered);
        // writes routed via BakeLevelAccessor.setBlock to cache or counter.
        UpgradeDataReflect.runChunkyFixers(accessor);
        bakeUpgradeSides(accessor, level, entry, ud, chunkPos);
    }

    /**
     * Mirrors {@link UpgradeData}{@code .upgradeInside} (UpgradeData.java:173-216):
     * iterate each section's Indices, look up the dispatch fixer per current
     * state, run updateShape for each direction whose neighbour is still in the
     * same chunk, finalize with {@link Block#updateOrDestroy}.
     */
    private static void bakeUpgradeInside(
        BakeLevelAccessor accessor, ServerLevel level, CachedChunk entry, UpgradeData ud, ChunkPos chunkPos
    ) {
        int[][] indices = UpgradeDataReflect.indices(ud);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbourPos = new BlockPos.MutableBlockPos();
        Direction[] directions = Direction.values();

        for (int sectionIndex = 0; sectionIndex < indices.length; sectionIndex++) {
            int[] upgradeIndex = indices[sectionIndex];
            indices[sectionIndex] = null;
            if (upgradeIndex == null || upgradeIndex.length == 0) continue;

            LevelChunkSection section = entry.sectionByIndex(level, sectionIndex);
            if (section == null) continue;
            PalettedContainer<BlockState> states = section.getStates();
            int sectionY = level.getSectionYFromSectionIndex(sectionIndex);
            int bottomYInSection = SectionPos.sectionToBlockCoord(sectionY);

            for (int coord : upgradeIndex) {
                int x = coord & 15;
                int y = (coord >> 8) & 15;
                int z = (coord >> 4) & 15;
                pos.set(chunkPos.getMinBlockX() + x, bottomYInSection + y, chunkPos.getMinBlockZ() + z);
                BlockState state = states.get(coord);
                BlockState newState = state;

                for (Direction direction : directions) {
                    neighbourPos.setWithOffset(pos, direction);
                    if (SectionPos.blockToSectionCoord(neighbourPos.getX()) == chunkPos.x()
                            && SectionPos.blockToSectionCoord(neighbourPos.getZ()) == chunkPos.z()) {
                        UpgradeData.BlockFixer fixer = UpgradeDataReflect.fixerFor(newState.getBlock());
                        newState = fixer.updateShape(newState, direction, accessor.getBlockState(neighbourPos), accessor, pos, neighbourPos);
                    }
                }

                Block.updateOrDestroy(state, newState, accessor, pos, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }

    /**
     * Mirrors {@link UpgradeData}{@code .upgradeSides} (UpgradeData.java:133-164):
     * for each of the 8 {@link Direction8} sides flagged on the chunk's
     * UpgradeData, walk the marked edge strip, reapply updateShape across all
     * 4 cardinal neighbours (cross-chunk reads through the cache), finalize
     * with {@link Block#updateOrDestroy}. Writes always land intra-chunk.
     */
    private static void bakeUpgradeSides(
        BakeLevelAccessor accessor, ServerLevel level, CachedChunk entry, UpgradeData ud, ChunkPos chunkPos
    ) {
        EnumSet<Direction8> sides = UpgradeDataReflect.sides(ud);
        if (sides.isEmpty()) return;
        Direction[] updateDirections = Direction.values();
        BlockPos.MutableBlockPos neighbourPos = new BlockPos.MutableBlockPos();

        // Snapshot the side set since processing is destructive in vanilla; we
        // iterate a copy and clear after.
        Direction8[] toProcess = sides.toArray(new Direction8[0]);
        sides.clear();

        for (Direction8 direction8 : toProcess) {
            Set<Direction> dirs = direction8.getDirections();
            boolean east = dirs.contains(Direction.EAST);
            boolean west = dirs.contains(Direction.WEST);
            boolean south = dirs.contains(Direction.SOUTH);
            boolean north = dirs.contains(Direction.NORTH);
            boolean singular = dirs.size() == 1;
            int minBlockX = chunkPos.getMinBlockX();
            int minBlockZ = chunkPos.getMinBlockZ();
            int minX = minBlockX + (!singular || !north && !south ? (west ? 0 : 15) : 1);
            int maxX = minBlockX + (!singular || !north && !south ? (west ? 0 : 15) : 14);
            int minZ = minBlockZ + (!singular || !east && !west ? (north ? 0 : 15) : 1);
            int maxZ = minBlockZ + (!singular || !east && !west ? (north ? 0 : 15) : 14);

            for (BlockPos pos : BlockPos.betweenClosed(minX, accessor.getMinY(), minZ, maxX, accessor.getMaxY(), maxZ)) {
                BlockState state = accessor.getBlockState(pos);
                BlockState newState = state;

                for (Direction direction : updateDirections) {
                    neighbourPos.setWithOffset(pos, direction);
                    UpgradeData.BlockFixer fixer = UpgradeDataReflect.fixerFor(newState.getBlock());
                    newState = fixer.updateShape(newState, direction, accessor.getBlockState(neighbourPos), accessor, pos, neighbourPos);
                }

                Block.updateOrDestroy(state, newState, accessor, pos, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }

    /**
     * Replay {@code UpgradeData.neighborBlockTicks} and
     * {@code neighborFluidTicks} into the target chunks owning each tick's
     * position. Mirrors {@link UpgradeData}{@code .upgrade}'s tick block at
     * lines 116-128, but routes each surviving tick to the appropriate write
     * sink (intra-region cache entry or per-worker journal) rather than to
     * {@code level.scheduleTick}.
     *
     * <p>Distance-from-source filter: copy of vanilla's
     * {@code filterTickList} predicate (Chebyshev distance from source chunk
     * must equal 1). We do not call the vanilla method because it
     * {@code LOGGER.warn}s per drop, which floods the log at archive scale;
     * we aggregate into the {@code ticks-dropped-distance-filter} counter
     * instead.
     *
     * <p>Sentinel resolution ({@code tick.type() == Blocks.AIR} or
     * {@code Fluids.EMPTY}) happens at append time against the target's
     * block state for intra-region targets, and at tail-pass time for
     * cross-region targets (the journal stores the raw type, sentinel
     * included; see {@link BakeLightJournal}).
     *
     * <p>Append-with-dedup: the target's pending list dedup-checks each new
     * tick against its already-on-disk tick list plus any earlier pending
     * appends via {@link SavedTick#UNIQUE_TICK_HASH}.
     */
    private static void replayNeighborTicks(
        ServerLevel level, Map<Long, CachedChunk> cache, BakeLightJournal journal, Identifier dim,
        CachedChunk source, UpgradeData ud, ChunkPos sourcePos, Failures failures
    ) throws IOException {
        List<SavedTick<Block>> blockTicks = UpgradeDataReflect.neighborBlockTicks(ud);
        List<SavedTick<Fluid>> fluidTicks = UpgradeDataReflect.neighborFluidTicks(ud);
        if (blockTicks.isEmpty() && fluidTicks.isEmpty()) return;

        for (SavedTick<Block> tick : blockTicks) {
            if (!withinChebyshevOne(sourcePos, tick.pos())) {
                failures.ticksDroppedDistanceFilter.incrementAndGet();
                continue;
            }
            routeBlockTick(level, cache, journal, dim, tick, failures);
        }
        for (SavedTick<Fluid> tick : fluidTicks) {
            if (!withinChebyshevOne(sourcePos, tick.pos())) {
                failures.ticksDroppedDistanceFilter.incrementAndGet();
                continue;
            }
            routeFluidTick(level, cache, journal, dim, tick, failures);
        }
        blockTicks.clear();
        fluidTicks.clear();
    }

    private static boolean withinChebyshevOne(ChunkPos source, BlockPos tickPos) {
        int tickCX = tickPos.getX() >> 4;
        int tickCZ = tickPos.getZ() >> 4;
        int dist = Math.max(Math.abs(source.x() - tickCX), Math.abs(source.z() - tickCZ));
        return dist == 1;
    }

    private static void routeBlockTick(
        ServerLevel level, Map<Long, CachedChunk> cache, BakeLightJournal journal, Identifier dim,
        SavedTick<Block> tick, Failures failures
    ) throws IOException {
        long key = ChunkPos.pack(tick.pos());
        CachedChunk target = cache.get(key);
        if (target == null) {
            failures.ticksDroppedMissingTarget.incrementAndGet();
            return;
        }
        if (!target.owned) {
            // Cross-region: target lives in a region this worker does not own.
            // Journal verbatim (sentinel unresolved); the tail pass resolves and dedups.
            journal.appendBlockTick(dim, tick);
            return;
        }
        // Intra-region: resolve sentinel against target's block state, dedup,
        // and append to the target's pending list.
        SavedTick<Block> resolved = tick;
        if (tick.type() == Blocks.AIR) {
            BlockState targetState = blockStateAt(level, target, tick.pos());
            if (targetState != null) {
                resolved = new SavedTick<>(targetState.getBlock(), tick.pos(), tick.delay(), tick.priority());
            }
        }
        if (target.appendPendingBlockTick(resolved)) {
            failures.ticksReplayed.incrementAndGet();
        } else {
            failures.ticksDroppedDedup.incrementAndGet();
        }
    }

    private static void routeFluidTick(
        ServerLevel level, Map<Long, CachedChunk> cache, BakeLightJournal journal, Identifier dim,
        SavedTick<Fluid> tick, Failures failures
    ) throws IOException {
        long key = ChunkPos.pack(tick.pos());
        CachedChunk target = cache.get(key);
        if (target == null) {
            failures.ticksDroppedMissingTarget.incrementAndGet();
            return;
        }
        if (!target.owned) {
            journal.appendFluidTick(dim, tick);
            return;
        }
        SavedTick<Fluid> resolved = tick;
        if (tick.type() == Fluids.EMPTY) {
            BlockState targetState = blockStateAt(level, target, tick.pos());
            if (targetState != null) {
                resolved = new SavedTick<>(targetState.getFluidState().getType(), tick.pos(), tick.delay(), tick.priority());
            }
        }
        if (target.appendPendingFluidTick(resolved)) {
            failures.ticksReplayed.incrementAndGet();
        } else {
            failures.ticksDroppedDedup.incrementAndGet();
        }
    }

    /**
     * Read the block state at {@code blockPos} from {@code target}'s cached
     * {@link LevelChunkSection}. Returns {@code null} if the section index is
     * out of range or the section is absent (the latter typically means the
     * chunk is air at that Y; in which case sentinel resolution would treat
     * it as such, identical to vanilla's runtime resolution).
     */
    private static @org.jspecify.annotations.Nullable BlockState blockStateAt(
        ServerLevel level, CachedChunk target, BlockPos blockPos
    ) {
        int sectionY = blockPos.getY() >> 4;
        int sectionIndex = level.getSectionIndexFromSectionY(sectionY);
        LevelChunkSection section = target.sectionByIndex(level, sectionIndex);
        if (section == null) return null;
        int localX = blockPos.getX() & 15;
        int localZ = blockPos.getZ() & 15;
        int localY = blockPos.getY() & 15;
        return section.getStates().get(localX, localY, localZ);
    }

    /**
     * Per-chunk light bake: prime heightmaps and run Starlight against the
     * cache entry's lazily-built {@link ProtoChunk}, then merge the freshly
     * baked heightmaps and section light nibbles back into {@code entry.data}
     * along with the UpgradeData strip from the own-chunk walk and
     * {@code lightCorrect=true}.
     *
     * <p>{@link SerializableChunkData#copyOf} produces {@link LevelChunkSection}
     * copies on the returned record (each section is {@code .copy()}'d), which
     * decouples disk-bound state from the in-cache sections the bake walk and
     * cross-region neighbour reads have been mutating. The merge then re-uses
     * entities, block entities, packed ticks, structure data, and PDC verbatim
     * from the original parsed record.
     */
    private static void bakeChunkLight(ServerLevel level, StarLightInterface lightInterface, CachedChunk entry) {
        ProtoChunk pc = entry.protoChunk(level);
        Heightmap.primeHeightmaps(pc, ChunkStatus.FULL.heightmapsAfter());
        Boolean[] empty = StarLightEngine.getEmptySectionsForChunk(pc);
        lightInterface.lightChunk(pc, empty);
        pc.setLightCorrect(true);
        SerializableChunkData baked = SerializableChunkData.copyOf(level, pc);
        entry.data = mergeBaked(entry.data, baked);
    }

    /**
     * Merge a fresh bake output back into the parsed-from-disk record. The bake
     * contributes {@code sectionData} (with Starlight nibbles), {@code heightmaps},
     * and the {@code lightCorrect=true} flag; everything else (entities, block
     * entities, packed ticks, structure data, PDC, etc.) is preserved from the
     * original. {@link UpgradeData#EMPTY} is substituted unconditionally; a chunk
     * that reaches this point is guaranteed to have been through either the
     * own-chunk UpgradeData walk or skip-if-current (in which case the
     * original was already empty).
     *
     * <p>Status promote to {@link ChunkStatus#LIGHT}: chunks the source world
     * saved at status below LIGHT (mid-generation NOISE / SURFACE / CARVERS /
     * FEATURES / INITIALIZE_LIGHT) get bumped. Both
     * {@code SerializableChunkData.write} (the {@code starlight.light_version}
     * tag write) and {@code SaveUtil.loadLightHookReal} (the loader's nibble
     * read into {@code starlight$blockNibbles}/{@code skyNibbles}) gate on
     * {@code status.isOrAfter(LIGHT)}; without the bump the bake's computed
     * nibbles round-trip to disk but the loader rejects them, {@code
     * lightCorrect=false}, and the chunk system advances LIGHT->SPAWN->FULL on
     * first load, re-invoking {@code ChunkStatusTasks.LIGHT} ({@code lightEngine
     * ().lightChunk}) and paying the lazy cost the bake was meant to eliminate.
     * {@code StarLightEngine.light} forces the self chunk into the engine cache
     * without {@code canUseChunk} filtering, so the bake's light computation
     * already works on sub-LIGHT chunks today; only the marker write was being
     * suppressed. SPAWN and FULL still run on first load identically to before;
     * only the LIGHT step moves from gameplay-time to bake-time.
     */
    private static SerializableChunkData mergeBaked(SerializableChunkData original, SerializableChunkData baked) {
        ChunkStatus promotedStatus = original.chunkStatus().isOrAfter(ChunkStatus.LIGHT)
            ? original.chunkStatus()
            : ChunkStatus.LIGHT;
        return new SerializableChunkData(
            original.containerFactory(),
            original.chunkPos(),
            original.minSectionY(),
            original.lastUpdateTime(),
            original.inhabitedTime(),
            promotedStatus,
            original.blendingData(),
            original.belowZeroRetrogen(),
            UpgradeData.EMPTY,
            original.carvingMask(),
            baked.heightmaps(),
            original.packedTicks(),
            original.postProcessingSections(),
            true,
            baked.sectionData(),
            original.entities(),
            original.blockEntities(),
            original.structureData(),
            original.persistentDataContainer()
        );
    }

    private static void writeChunk(RegionFile region, ChunkPos pos, SerializableChunkData data) throws IOException {
        CompoundTag tag = data.write();
        try (DataOutputStream out = region.getChunkDataOutputStream(pos)) {
            NbtIo.write(tag, out);
        }
    }

    /**
     * Read one chunk slot's raw NBT and parse it to {@link
     * SerializableChunkData}. Returns null when the stream is unexpectedly
     * absent (rare race against another writer; same shape as
     * {@link DirectNbtUpgrader#processChunk}).
     *
     * <p>The parse hits {@link SerializableChunkData#parse} which reads
     * UpgradeData, heightmaps, light arrays, ticks, and structure data into
     * a record without instantiating any chunk object. No side effects on
     * the live {@link ServerLevel} (no POI write, no light-engine queueing,
     * no chunk-system ticket): all of that happens in
     * {@link SerializableChunkData#read} which we deliberately avoid.
     */
    private static CachedChunk loadChunk(RegionFile region, ChunkPos pos, ServerLevel level, boolean owned) throws IOException {
        CompoundTag chunkTag;
        try (DataInputStream in = region.getChunkDataInputStream(pos)) {
            if (in == null) return null;
            chunkTag = NbtIo.read(in);
        }
        SerializableChunkData parsed = SerializableChunkData.parse(level, level.palettedContainerFactory(), chunkTag);
        if (parsed == null) return null;
        return new CachedChunk(parsed, owned);
    }

    private static Path progressFilePath(MinecraftServer server) {
        return server.storageSource.getLevelDirectory().path()
            .resolve(".archive-bakelight-progress.txt");
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
            LOGGER.warn("[The Archive] Failed to read bake-light progress file, starting fresh: {}", ex.getMessage());
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
            LOGGER.error("[The Archive] Failed to append bake-light progress for {}: {}", entry, ex.getMessage());
            failures.progressAppendFailed.incrementAndGet();
        }
    }

    private static void awaitWithWatchdogTicks(ExecutorService pool, Failures failures) {
        long deadline = System.currentTimeMillis() + FINAL_DRAIN_TIMEOUT_MILLIS;
        try {
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    List<Runnable> dropped = pool.shutdownNow();
                    LOGGER.error("[The Archive] Bake-light pool drain exceeded {}m, forcing shutdown ({} tasks dropped)",
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

    /**
     * Per-worker cache entry. Holds the parsed chunk record plus an
     * {@code owned} flag (set when the chunk belongs to this worker's center
     * region versus a 1-chunk border read from a neighbour region) and a
     * {@code dirty} flag (set when the bake mutated the parsed state and a
     * write-back is owed at region finalize).
     *
     * <p>{@code sectionsByIndex} is a lazily-built dense array of {@link
     * LevelChunkSection} indexed by {@code sectionIndex} (=
     * {@code sectionY - minSectionY}), the same indexing vanilla uses in
     * {@link UpgradeData}{@code .upgradeInside}. The array shares
     * {@link PalettedContainer} references with the {@link
     * SerializableChunkData.SectionData} list, so the bake's in-place mutations
     * survive when {@link SerializableChunkData#write} re-serializes the
     * section list.
     */
    static final class CachedChunk {
        SerializableChunkData data;
        final boolean owned;
        boolean dirty;
        private LevelChunkSection @org.jspecify.annotations.Nullable [] sectionsByIndex;
        private @org.jspecify.annotations.Nullable ProtoChunk protoChunk;

        // Pending tick appends for this chunk, deposited by bake walks of
        // OTHER chunks in the cache whose neighbour-tick lists landed on this
        // chunk. Flushed into {@link #data} at region finalize via {@link
        // #flushPendingTicksInto}. Dedup sets are populated lazily on first
        // append, seeded from {@code data.packedTicks()}; this matches
        // vanilla's level-scheduler dedup via {@link SavedTick#UNIQUE_TICK_HASH}.
        private @org.jspecify.annotations.Nullable List<SavedTick<Block>> pendingBlockTicks;
        private @org.jspecify.annotations.Nullable List<SavedTick<Fluid>> pendingFluidTicks;
        private @org.jspecify.annotations.Nullable ObjectOpenCustomHashSet<SavedTick<?>> blockTickDedup;
        private @org.jspecify.annotations.Nullable ObjectOpenCustomHashSet<SavedTick<?>> fluidTickDedup;

        // BE index: lazily built on first {@link #blockEntityAt} call so
        // the bake's CHEST fixer can read both halves of a chest pair (including
        // cross-region neighbours). Marked dirty when a {@code getBlockEntity}
        // returns a real BE instance; finalize re-serializes every entry in the
        // index. Owned-entry flush rebuilds {@code data.blockEntities()};
        // non-owned-entry flush appends one {@code DISCRIMINANT_BLOCK_ENTITY_NBT}
        // record per BE to the worker's journal.
        private @org.jspecify.annotations.Nullable Map<Long, BlockEntity> blockEntityIndex;
        private boolean blockEntityIndexDirty;

        CachedChunk(SerializableChunkData data, boolean owned) {
            this.data = data;
            this.owned = owned;
        }

        /**
         * Try to append a block tick to this chunk's pending list. Returns
         * {@code true} if the tick was new (added to dedup + pending list),
         * {@code false} if a tick with the same {@code (type, pos)} already
         * exists on disk or in the pending list (no-op, caller increments
         * the dropped-dedup counter).
         */
        boolean appendPendingBlockTick(SavedTick<Block> tick) {
            if (blockTickDedup == null) {
                blockTickDedup = new ObjectOpenCustomHashSet<>(SavedTick.UNIQUE_TICK_HASH);
                blockTickDedup.addAll(data.packedTicks().blocks());
                pendingBlockTicks = new ArrayList<>();
            }
            if (!blockTickDedup.add(tick)) return false;
            pendingBlockTicks.add(tick);
            return true;
        }

        boolean appendPendingFluidTick(SavedTick<Fluid> tick) {
            if (fluidTickDedup == null) {
                fluidTickDedup = new ObjectOpenCustomHashSet<>(SavedTick.UNIQUE_TICK_HASH);
                fluidTickDedup.addAll(data.packedTicks().fluids());
                pendingFluidTicks = new ArrayList<>();
            }
            if (!fluidTickDedup.add(tick)) return false;
            pendingFluidTicks.add(tick);
            return true;
        }

        boolean hasPendingTicks() {
            return (pendingBlockTicks != null && !pendingBlockTicks.isEmpty())
                || (pendingFluidTicks != null && !pendingFluidTicks.isEmpty());
        }

        /**
         * Merge pending tick appends into {@link #data} by rebuilding the
         * {@link SerializableChunkData} record with extended {@link
         * ChunkAccess.PackedTicks}. Idempotent: a second call after the lists
         * have been cleared is a no-op.
         */
        void flushPendingTicksInto(ServerLevel level) {
            if (!hasPendingTicks()) return;
            ChunkAccess.PackedTicks original = data.packedTicks();
            List<SavedTick<Block>> blocks;
            if (pendingBlockTicks != null && !pendingBlockTicks.isEmpty()) {
                blocks = new ArrayList<>(original.blocks().size() + pendingBlockTicks.size());
                blocks.addAll(original.blocks());
                blocks.addAll(pendingBlockTicks);
                pendingBlockTicks.clear();
            } else {
                blocks = original.blocks();
            }
            List<SavedTick<Fluid>> fluids;
            if (pendingFluidTicks != null && !pendingFluidTicks.isEmpty()) {
                fluids = new ArrayList<>(original.fluids().size() + pendingFluidTicks.size());
                fluids.addAll(original.fluids());
                fluids.addAll(pendingFluidTicks);
                pendingFluidTicks.clear();
            } else {
                fluids = original.fluids();
            }
            ChunkAccess.PackedTicks extended = new ChunkAccess.PackedTicks(blocks, fluids);
            data = new SerializableChunkData(
                data.containerFactory(),
                data.chunkPos(),
                data.minSectionY(),
                data.lastUpdateTime(),
                data.inhabitedTime(),
                data.chunkStatus(),
                data.blendingData(),
                data.belowZeroRetrogen(),
                data.upgradeData(),
                data.carvingMask(),
                data.heightmaps(),
                extended,
                data.postProcessingSections(),
                data.lightCorrect(),
                data.sectionData(),
                data.entities(),
                data.blockEntities(),
                data.structureData(),
                data.persistentDataContainer()
            );
        }

        /**
         * Look up the block entity at {@code pos} from the lazily-built index.
         * Returns {@code null} if no BE exists at the position. Marks the
         * index dirty on every non-null return so finalize re-serializes the
         * BE; the bake's only {@link BakeLevelAccessor#getBlockEntity} caller
         * is the vanilla {@code UpgradeData.CHEST} fixer, which then either
         * runs {@code ChestBlockEntity.swapContents} (mutates both BEs) or
         * skips the swap (in which case the no-op re-serialize is harmless).
         */
        @org.jspecify.annotations.Nullable BlockEntity blockEntityAt(BakeLevelAccessor accessor, BlockPos pos) {
            ensureBlockEntityIndex(accessor);
            BlockEntity be = blockEntityIndex.get(pos.asLong());
            if (be != null) {
                blockEntityIndexDirty = true;
            }
            return be;
        }

        private void ensureBlockEntityIndex(BakeLevelAccessor accessor) {
            if (blockEntityIndex != null) return;
            Map<Long, BlockEntity> index = new HashMap<>(data.blockEntities().size());
            ChunkPos chunkPos = data.chunkPos();
            for (CompoundTag tag : data.blockEntities()) {
                BlockPos pos = BlockEntity.getPosFromTag(chunkPos, tag);
                BlockState state = accessor.getBlockState(pos);
                BlockEntity be = BlockEntity.loadStatic(pos, state, tag, accessor.registryAccess());
                if (be != null) {
                    index.put(pos.asLong(), be);
                }
            }
            blockEntityIndex = index;
        }

        boolean blockEntityIndexDirty() {
            return blockEntityIndexDirty;
        }

        /**
         * Re-serialize every BE in the index and merge into
         * {@code data.blockEntities()}. Owned-entry path: index entries
         * overwrite the matching position in the existing list, any BE in the
         * index not present in the original list is appended.
         */
        void flushBlockEntityIndexInto(BakeLevelAccessor accessor) {
            if (blockEntityIndex == null || !blockEntityIndexDirty) return;
            Map<Long, CompoundTag> serialized = new HashMap<>(blockEntityIndex.size());
            for (Map.Entry<Long, BlockEntity> e : blockEntityIndex.entrySet()) {
                serialized.put(e.getKey(), e.getValue().saveWithFullMetadata(accessor.registryAccess()));
            }
            List<CompoundTag> mergedList = mergeBlockEntityTags(data.blockEntities(), serialized, data.chunkPos());
            data = new SerializableChunkData(
                data.containerFactory(),
                data.chunkPos(),
                data.minSectionY(),
                data.lastUpdateTime(),
                data.inhabitedTime(),
                data.chunkStatus(),
                data.blendingData(),
                data.belowZeroRetrogen(),
                data.upgradeData(),
                data.carvingMask(),
                data.heightmaps(),
                data.packedTicks(),
                data.postProcessingSections(),
                data.lightCorrect(),
                data.sectionData(),
                data.entities(),
                mergedList,
                data.structureData(),
                data.persistentDataContainer()
            );
            blockEntityIndexDirty = false;
        }

        /**
         * Non-owned-entry path. Walk each BE in the index, serialize via
         * {@code saveWithFullMetadata}, append to the worker's journal as a
         * {@code DISCRIMINANT_BLOCK_ENTITY_NBT} record. The tail pass merges
         * each record into the target chunk's {@code blockEntities()} list.
         */
        void journalBlockEntityIndex(BakeLevelAccessor accessor, BakeLightJournal journal, Identifier dim) throws IOException {
            if (blockEntityIndex == null || !blockEntityIndexDirty) return;
            for (Map.Entry<Long, BlockEntity> e : blockEntityIndex.entrySet()) {
                BlockPos pos = BlockPos.of(e.getKey());
                CompoundTag tag = e.getValue().saveWithFullMetadata(accessor.registryAccess());
                journal.appendBlockEntity(dim, pos, tag);
            }
            blockEntityIndexDirty = false;
        }

        LevelChunkSection sectionByIndex(final ServerLevel level, final int sectionIndex) {
            ensureSectionArray(level);
            if (sectionIndex < 0 || sectionIndex >= sectionsByIndex.length) return null;
            return sectionsByIndex[sectionIndex];
        }

        LevelChunkSection sectionAt(final ServerLevel level, final int blockY) {
            int sectionIndex = level.getSectionIndexFromSectionY(blockY >> 4);
            return sectionByIndex(level, sectionIndex);
        }

        /**
         * Lazily build (and cache) a {@link ProtoChunk} backed by the same
         * {@link LevelChunkSection} array the cross-chunk reads and the
         * own-chunk walk have been mutating. {@link UpgradeData#EMPTY} on the
         * proto: by the time the light bake runs, the own-chunk walk has
         * already played the UpgradeData side effects, and the light bake
         * itself does not consult {@code chunk.getUpgradeData()}. Persisted status is restored from the
         * parsed record so {@link SerializableChunkData#copyOf}'s
         * {@code canBeSerialized()} guard passes and the heightmap-after set
         * filtering matches what was on disk.
         */
        ProtoChunk protoChunk(final ServerLevel level) {
            if (protoChunk != null) return protoChunk;
            ensureSectionArray(level);
            ProtoChunk pc = new ProtoChunk(
                data.chunkPos(),
                UpgradeData.EMPTY,
                sectionsByIndex,
                ProtoChunkTicks.load(data.packedTicks().blocks()),
                ProtoChunkTicks.load(data.packedTicks().fluids()),
                level,
                level.palettedContainerFactory(),
                null
            );
            pc.setPersistedStatus(data.chunkStatus());
            protoChunk = pc;
            return pc;
        }

        private void ensureSectionArray(final ServerLevel level) {
            if (sectionsByIndex != null) return;
            LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
            for (SerializableChunkData.SectionData section : data.sectionData()) {
                if (section.chunkSection() == null) continue;
                int idx = level.getSectionIndexFromSectionY(section.y());
                if (idx >= 0 && idx < sections.length) {
                    sections[idx] = section.chunkSection();
                }
            }
            sectionsByIndex = sections;
        }
    }

    private static final class Failures {
        final Set<String> regions = ConcurrentHashMap.newKeySet();
        final AtomicLong regionCount = new AtomicLong();
        final ConcurrentLinkedQueue<String> chunkSample = new ConcurrentLinkedQueue<>();
        final AtomicLong chunkCount = new AtomicLong();
        // Cross-region write counters. {@code crossRegionWritesJournaled}
        // is the bake-time count of LEAVES BFS / side-strip / CHEST property /
        // CHEST swap-content writes that landed outside the worker's owned
        // region and went through the per-worker journal. {@code missingTarget}
        // is incremented by the tail pass when a destination region file does
        // not exist on disk. {@code ioFailed} is bumped at write time when the
        // append call itself raises IOException (BakeLevelAccessor accessor
        // writes or the per-entry BE-index journal loop in processRegion);
        // a non-zero per-region snapshot keeps the region OUT of the progress
        // file via RegionResult.crossRegionWritesIoFailed, and any non-zero
        // total trips the pass-end FAILED summary.
        final AtomicLong crossRegionWritesJournaled = new AtomicLong();
        final AtomicLong crossRegionWritesMissingTarget = new AtomicLong();
        final AtomicLong crossRegionWritesIoFailed = new AtomicLong();
        final AtomicLong crossRegionWritesApplied = new AtomicLong();
        final AtomicLong chunksBaked = new AtomicLong();
        // Tick-replay counters. Aggregated across all workers and the tail
        // pass; surfaced in the pass-end summary.
        final AtomicLong ticksReplayed = new AtomicLong();
        final AtomicLong ticksDroppedDistanceFilter = new AtomicLong();
        final AtomicLong ticksDroppedMissingTarget = new AtomicLong();
        final AtomicLong ticksDroppedDedup = new AtomicLong();
        // Tail-pass malformed-record counter. Bumped from the per-record catch
        // in replayRecordsAtTarget when decodeTick/decodeBlockState/decodeBlockEntity
        // throws on a corrupt journal payload. Without a dedicated counter, the
        // pass-end summary would under-report drops and operators could not
        // distinguish zero-loss from N-loss after a SIGKILL+resume.
        final AtomicLong tailPassMalformedRecords = new AtomicLong();
        // Border-load failure counter. Bumped from loadEdge's catch when a
        // neighbour region's RegionFile open or per-slot read throws. The
        // affected owned-region's processRegion treats a non-zero edge-failure
        // count as a region-level failure (RegionResult.borderLoadFailures)
        // so its progress key stays out of the file and a re-run with a
        // potentially-repaired neighbour gets another shot.
        final AtomicLong borderLoadFailed = new AtomicLong();
        // Progress-marker write failure counter. Bumped from appendProgress's
        // catch when Files.writeString throws on the SIGKILL-safe progress file.
        // The region's bake work is durable at this point (journal fsynced,
        // RegionFile force(true) by close()) but the resume marker is not; a
        // non-zero count after the pass means a SIGKILL+restart will redo
        // every region whose marker never landed.
        final AtomicLong progressAppendFailed = new AtomicLong();

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
}
