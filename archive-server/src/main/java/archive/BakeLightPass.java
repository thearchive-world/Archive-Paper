package archive;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.logging.LogUtils;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.slf4j.Logger;

/**
 * Bake-light pass. Strips runtime load-time cost off legacy chunks by
 * baking isLightOn, finalized heightmaps, and resolved UpgradeData
 * (Indices, Sides, neighbour-tick lists) into the on-disk chunk. Design:
 * docs/superpowers/specs/2026-05-17-bake-light-design.md. Reuses
 * {@link DirectNbtUpgrader}'s pass-chain envelope (worker pool,
 * tick-thread pump, per-future deadline, try-catch, resumable progress
 * file). New parts are the 3x3 region working set, per-worker chunk
 * cache backed by a hand-rolled LevelAccessor stub, Starlight bake on
 * raw ProtoChunks, and a journal-replay tail pass for cross-region
 * writes.
 *
 * <p>Stage 1 (this commit): dispatcher skeleton only. Per-region work
 * is a no-op pass that walks the region file but does not yet mutate
 * chunks. Validates engine + patches integration before we add the
 * cache, LevelAccessor stub, UpgradeData walk, and Starlight bake.
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

        Failures failures = new Failures();
        long totalChunks = 0;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount,
            new ThreadFactoryBuilder().setNameFormat("archive-bakelight-%d").setDaemon(true).build());

        try {
            for (ServerLevel level : server.getAllLevels()) {
                totalChunks += processLevel(server, level, pool, completed, progressFile, failures);
            }
        } finally {
            pool.shutdown();
            awaitWithWatchdogTicks(pool, failures);
        }

        // TODO stage 7: journal replay tail pass goes here once cross-region
        // writes are implemented. For now we have no journal, so this is a no-op.

        long elapsedSec = (System.currentTimeMillis() - startMillis) / 1000;
        if (failures.regionCount.get() > 0 || failures.chunkCount.get() > 0) {
            LOGGER.error("[The Archive] Bake-light FAILED: {} region failures, {} chunk failures, {} chunks walked in {}s",
                         failures.regionCount.get(), failures.chunkCount.get(), totalChunks, elapsedSec);
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
            LOGGER.info("[The Archive] Bake-light complete: {} chunks walked in {}s",
                        totalChunks, elapsedSec);
        }
    }

    private static long processLevel(
        MinecraftServer server, ServerLevel level, ExecutorService pool,
        Set<String> completed, Path progressFile, Failures failures
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
                    long chunks = processRegion(info, level, folderPath, rx, rz, failures);
                    chunkCounter.addAndGet(chunks);
                    regionCounter.incrementAndGet();
                    appendProgress(progressFile, key);
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
     * Stage 2 (current): load and parse every populated slot in the center
     * region to {@link SerializableChunkData}. Cache the parsed records by
     * chunk position so stage 3 can hand them to the own-chunk UpgradeData
     * walker without re-parsing. Does NOT yet reconstruct a {@link
     * net.minecraft.world.level.chunk.ProtoChunk}, build the 3x3 border, run
     * the UpgradeData walk, prime heightmaps, bake Starlight, or write back.
     *
     * <p>Non-DSYNC open mirrors {@link DirectNbtUpgrader}; durability at
     * region-close granularity via {@code file.force(true)} in
     * {@code RegionFile.close}. Stage 2 has no writes, so the durability
     * argument is moot for this stage and gets restated when stage 4 lands
     * the heightmap/light write-back.
     */
    private static long processRegion(
        RegionStorageInfo info, ServerLevel level, Path regionFolder, int rx, int rz, Failures failures
    ) throws IOException {
        Path regionPath = regionFolder.resolve("r." + rx + "." + rz + ".mca");
        if (!Files.exists(regionPath)) return 0;
        long parsed = 0;
        Map<Long, CachedChunk> cache = new HashMap<>(1156);  // 1024 owned + 132 border slots
        String regionLabel = "r." + rx + "." + rz;
        try (RegionFile region = new RegionFile(info, regionPath, regionFolder, false)) {
            int xOffset = rx << 5;
            int zOffset = rz << 5;
            for (int dx = 0; dx < 32; dx++) {
                for (int dz = 0; dz < 32; dz++) {
                    ChunkPos pos = new ChunkPos(dx + xOffset, dz + zOffset);
                    if (!region.doesChunkExist(pos)) continue;
                    try {
                        CachedChunk entry = loadOwned(region, pos, level);
                        if (entry == null) continue;
                        cache.put(pos.pack(), entry);
                        parsed++;
                    } catch (Exception ex) {
                        String chunkKey = regionLabel + " chunk(" + pos.x() + "," + pos.z() + ")";
                        LOGGER.error("[The Archive] bake-light {} failed: {}", chunkKey, ex.toString());
                        failures.recordChunk(chunkKey);
                    }
                }
            }
        }
        // TODO stage 3+: 3x3 border load, UpgradeData walk, heightmap prime,
        // Starlight bake, region finalize (write back via copyOf + NbtIo).
        // For now we just verify the parse path works and the cache populates.
        return parsed;
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
    private static CachedChunk loadOwned(RegionFile region, ChunkPos pos, ServerLevel level) throws IOException {
        CompoundTag chunkTag;
        try (DataInputStream in = region.getChunkDataInputStream(pos)) {
            if (in == null) return null;
            chunkTag = NbtIo.read(in);
        }
        SerializableChunkData parsed = SerializableChunkData.parse(level, level.palettedContainerFactory(), chunkTag);
        if (parsed == null) return null;
        return new CachedChunk(parsed, true);
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

    private static synchronized void appendProgress(Path path, String entry) {
        try {
            Files.writeString(path, entry + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException ex) {
            LOGGER.warn("[The Archive] Failed to append bake-light progress: {}", ex.getMessage());
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
     * write-back is owed at region finalize). Stage 2 populates {@code owned}
     * only; {@code dirty} stays {@code false} until stage 3 starts mutating.
     */
    static final class CachedChunk {
        SerializableChunkData data;
        final boolean owned;
        boolean dirty;

        CachedChunk(SerializableChunkData data, boolean owned) {
            this.data = data;
            this.owned = owned;
        }
    }

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
}
