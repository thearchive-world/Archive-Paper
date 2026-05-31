package archive;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.logging.LogUtils;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.FastBufferedInputStream;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

public final class DirectNbtUpgrader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern REGION_FILE_REGEX =
        Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca$");
    private static final int CURRENT_DATA_VERSION =
        SharedConstants.getCurrentVersion().dataVersion().version();
    // Hard upper bound on per-region task wall-clock. Measured ~6099
    // chunks/sec total with 8 workers (~762/worker) on 1.12.2 fixtures, i.e. ~1.3s
    // for a full 1024-chunk region per worker. 30 minutes is wildly clear of
    // any "slow but working" path (>1000x normal) and exists only to bound a
    // real hang so the run can complete and surface the failure rather than
    // spin the watchdog forever.
    private static final long PER_REGION_TIMEOUT_MILLIS = 30L * 60 * 1000;
    // After all submits + cancels, give workers a generous window to wind down
    // before shutdownNow. Past this, accept that something is genuinely stuck.
    private static final long FINAL_DRAIN_TIMEOUT_MILLIS = 5L * 60 * 1000;
    // Cap retained chunk-failure coords for the end-of-run summary. A pathologically
    // broken world could produce millions; 100 samples is enough to start digging.
    private static final int CHUNK_FAILURE_SAMPLE_CAP = 100;
    // Time-based progress heartbeat. Big worlds need a "still alive + current
    // rate" signal independent of region count.
    private static final long PROGRESS_LOG_INTERVAL_MILLIS = 30L * 1000;

    private DirectNbtUpgrader() {}

    public static void run(MinecraftServer server) {
        long startMillis = System.currentTimeMillis();
        int threadCount = ArchiveSettings.upgradeWorkerCount();
        // Downstream smoke tooling greps the log prefix below. Keep "Starting
        // chunk upgrade" and "Chunk upgrade complete" in sync when changing.
        LOGGER.info("[The Archive] Starting chunk upgrade pass (direct-NBT, {} workers)...", threadCount);

        Path progressFile = progressFilePath(server);
        Set<String> completed = readProgress(progressFile);
        if (!completed.isEmpty()) {
            LOGGER.info("[The Archive] Resuming with {} regions already complete", completed.size());
        }

        Failures failures = new Failures();
        long totalChunks = 0;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount,
            new ThreadFactoryBuilder().setNameFormat("archive-upgrade-%d").setDaemon(true).build());

        try {
            for (ServerLevel level : server.getAllLevels()) {
                totalChunks += processLevel(server, level, pool, completed, progressFile, failures);
            }
        } finally {
            pool.shutdown();
            awaitWithWatchdogTicks(pool, failures);
        }

        // Map data lives ONLY at <world>/data/minecraft/maps/ on the overworld root
        // (never per-dimension): MinecraftServer constructs its SavedDataStorage at
        // storageSource.getLevelPath(LevelResource.DATA) (MinecraftServer.java:329),
        // and ServerLevel.getMapData() routes to that server-wide storage
        // (ServerLevel.java:1481), which resolves SavedDataType ids of the form
        // "minecraft:maps/<n>" to <dataDir>/minecraft/maps/<n>.dat. FileFixerUpper
        // (Main.java:148, gated on level.dat DataVersion < v4772) has already moved
        // any legacy data/map_*.dat -> data/minecraft/maps/<n>.dat and
        // data/idcounts.dat -> data/minecraft/maps/last_id.dat before we run, so we
        // walk one directory. Single-threaded: typical worlds have a few hundred map
        // files, three orders of magnitude smaller than chunk work.
        processMapData(server, completed, progressFile, failures);

        long elapsedSec = (System.currentTimeMillis() - startMillis) / 1000;
        long progressFailed = failures.progressAppendFailed.get();
        if (failures.regionCount.get() > 0 || failures.chunkCount.get() > 0 || progressFailed > 0) {
            LOGGER.error("[The Archive] Chunk upgrade FAILED: {} region failures, {} chunk failures, {} progress-marker IO failures, {} chunks succeeded in {}s",
                         failures.regionCount.get(), failures.chunkCount.get(), progressFailed, totalChunks, elapsedSec);
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
                LOGGER.error("[The Archive] Progress file retained at {} for retry of failed regions.", progressFile);
            } else {
                // Chunk-only failures: nothing for a retry to fix (corrupt NBT will
                // fail the same way next run). Keep the file so the operator notices
                // the run was incomplete; they can delete it after investigating.
                LOGGER.error("[The Archive] Progress file retained at {} (no region retries needed; investigate chunk failures offline).", progressFile);
            }
        } else {
            try {
                Files.deleteIfExists(progressFile);
            } catch (IOException ex) {
                LOGGER.warn("[The Archive] Failed to delete progress file: {}", ex.getMessage());
            }
            LOGGER.info("[The Archive] Chunk upgrade complete: direct-NBT, {} chunks, progress-marker IO failures={} in {}s",
                        totalChunks, progressFailed, elapsedSec);
        }
    }

    private static long processLevel(
        MinecraftServer server, ServerLevel level, ExecutorService pool,
        Set<String> completed, Path progressFile, Failures failures
    ) {
        Identifier dim = level.dimension().identifier();
        long total = 0;
        // region/ pass: DFU chunk data. May also extract embedded legacy "Entities"
        // ListTags into entities/*.mca when --splitEntities is set (pre-1.17 sources).
        total += processFolder(server, level, pool, completed, progressFile, failures,
            dim, "region", "chunk", DataFixTypes.CHUNK, true);
        // entities/ pass: DFU existing entity chunks (post-1.17 sources whose entities/
        // folder is below current data version). For pre-1.17 sources the folder is
        // either freshly populated by the region/ pass above at CURRENT_DATA_VERSION
        // (DFU is then a no-op) or absent (and we skip).
        total += processFolder(server, level, pool, completed, progressFile, failures,
            dim, "entities", "entities", DataFixTypes.ENTITY_CHUNK, false);
        // poi/ pass: DFU existing POI chunks. For pre-1.17 sources this folder is
        // usually absent; runtime POI compute is deferred to chunk load time.
        total += processFolder(server, level, pool, completed, progressFile, failures,
            dim, "poi", "poi", DataFixTypes.POI_CHUNK, false);

        // Flush Paper's chunk-system IO worker pool for the level once at the end.
        // Even though our writes are via raw RegionFile (not through the chunk system),
        // SimpleRegionStorage's internal IOWorker shares the pool, and flush is a
        // belt-and-suspenders barrier before the patch's halt(false) returns.
        try {
            ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.flush(level);
        } catch (Exception ex) {
            LOGGER.warn("[The Archive] Flush warning for {}: {}", dim, ex.getMessage());
        }
        return total;
    }

    /**
     * Process one storage folder (region/, entities/, or poi/) for a level.
     * Spawns one worker per region file in that folder, each running
     * {@link #processRegion} with a folder-typed DFU helper.
     *
     * @param folderName disk folder name ("region", "entities", "poi")
     * @param storageTypeToken token passed to {@link RegionStorageInfo} for this folder
     *                         ("chunk", "entities", "poi"); used for logging/identification
     *                         downstream, not behavior
     * @param fixType {@link DataFixTypes} variant matching the folder's chunk shape
     * @param canSplit whether this folder pass is allowed to extract embedded "Entities"
     *                 (true only for region/; --splitEntities is gated on this too)
     */
    private static long processFolder(
        MinecraftServer server, ServerLevel level, ExecutorService pool,
        Set<String> completed, Path progressFile, Failures failures,
        Identifier dim, String folderName, String storageTypeToken,
        DataFixTypes fixType, boolean canSplit
    ) {
        Path dimRoot = server.storageSource.getDimensionPath(level.dimension());
        Path folderPath = dimRoot.resolve(folderName);
        Path entitiesFolder = dimRoot.resolve("entities");
        boolean splitEntities = canSplit && ArchiveSettings.splitEntities();

        File[] files = folderPath.toFile().listFiles((d, n) -> n.endsWith(".mca"));
        if (files == null || files.length == 0) {
            LOGGER.info("[The Archive] Upgrading dimension {} ({}): no region files, skipping",
                        dim, folderName);
            return 0;
        }
        if (splitEntities) {
            try {
                Files.createDirectories(entitiesFolder);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        LOGGER.info("[The Archive] Upgrading dimension {} ({}): {} region files queued",
                    dim, folderName, files.length);

        RegionStorageInfo info = new RegionStorageInfo(
            server.storageSource.getLevelId(), level.dimension(), storageTypeToken);
        RegionStorageInfo entityInfo = canSplit
            ? new RegionStorageInfo(server.storageSource.getLevelId(), level.dimension(), "entities")
            : null;
        // SimpleRegionStorage allocates an internal IOWorker on a fresh RegionFileStorage
        // rooted at folderPath. We only call upgradeChunkTag (pure NBT, no IO) so the
        // internal storage stays lazily-uninitialized; close at end of pass is cheap.
        SimpleRegionStorage dfuHelper = new SimpleRegionStorage(
            info, folderPath, DataFixers.getDataFixer(), true, fixType);

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
            Path entityRegionPath = canSplit ? entitiesFolder.resolve(regionFile.getName()) : null;

            Future<?> future = pool.submit(() -> {
                try {
                    long chunks = processRegion(
                        info, regionFile.toPath(), folderPath,
                        entityInfo, entityRegionPath, entitiesFolder,
                        rx, rz, dfuHelper, splitEntities, failures);
                    chunkCounter.addAndGet(chunks);
                    regionCounter.incrementAndGet();
                    appendProgress(progressFile, key, failures);
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
                    // Do NOT add to progress file; retried on next run.
                    LOGGER.error("[The Archive] {} ({}) region r.{}.{} failed",
                                 dim, folderName, rx, rz, t);
                    failures.recordRegion(key);
                }
            });
            submitted.add(new SubmittedRegion(key, future));
        }

        // Main thread blocks here while workers process. Tick the watchdog at most
        // every 4s during long waits (below Paper's early-warning-every default of
        // 5s) AND unconditionally after every future resolution.
        // Per-future deadline of PER_REGION_TIMEOUT_MILLIS caps any single hung
        // worker; on exceed, cancel and record as a region failure (deduped via
        // Failures.recordRegion). The worker's own catch may also record on
        // InterruptedException; the dedup makes that safe.
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
                    // errors already logged + recorded per-task
                    break;
                }
            }
            org.spigotmc.WatchdogThread.tick();
        }

        try {
            dfuHelper.close();
        } catch (IOException ignored) {
        }
        LOGGER.info("[The Archive]   {} ({}): complete, {} chunks processed",
                    dim, folderName, chunkCounter.get());
        return chunkCounter.get();
    }

    private static long processRegion(
        RegionStorageInfo chunkInfo, Path chunkRegionPath, Path regionFolder,
        RegionStorageInfo entityInfo, Path entityRegionPath, Path entitiesFolder,
        int rx, int rz, SimpleRegionStorage dfuHelper, boolean splitEntities,
        Failures failures
    ) throws IOException {
        long processed = 0;
        // sync=false: open WITHOUT StandardOpenOption.DSYNC. With DSYNC every chunk
        // write fsyncs to disk; jstack confirmed all 8 workers stuck in pwrite0 via
        // writeHeader (~5s of CPU each across 51s elapsed), which serialized them
        // at disk-queue level. RegionFile.close() calls file.force(true) at
        // RegionFile.java:877, so the file is still durable at region granularity.
        // Our progress marker only advances after processRegion returns (i.e., after
        // close()), so resume semantics remain crash-safe at region granularity.
        // try-with-resources skips close() on null; entityRegion is null when not splitting.
        try (RegionFile chunkRegion = new RegionFile(chunkInfo, chunkRegionPath, regionFolder, false);
             RegionFile entityRegion = splitEntities
                 ? new RegionFile(entityInfo, entityRegionPath, entitiesFolder, false)
                 : null) {
            int xOffset = rx << 5;
            int zOffset = rz << 5;
            for (int dx = 0; dx < 32; dx++) {
                for (int dz = 0; dz < 32; dz++) {
                    ChunkPos pos = new ChunkPos(dx + xOffset, dz + zOffset);
                    if (!chunkRegion.doesChunkExist(pos)) continue;
                    try {
                        if (processChunk(chunkRegion, entityRegion, pos, dfuHelper, splitEntities)) {
                            processed++;
                        }
                    } catch (Exception ex) {
                        // Per-chunk corruption: log + record but keep going so the
                        // other ~1023 chunks in the region still complete. The region
                        // gets marked done; failures.chunkSample surfaces the
                        // operator-visible list at end of run.
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
     * Processes a single chunk slot. Returns true if the chunk was walked; false
     * when the read-stream is null despite doesChunkExist (rare race).
     */
    private static boolean processChunk(
        RegionFile chunkRegion, RegionFile entityRegion, ChunkPos pos,
        SimpleRegionStorage dfuHelper, boolean splitEntities
    ) throws IOException {
        CompoundTag chunkTag;
        try (DataInputStream in = chunkRegion.getChunkDataInputStream(pos)) {
            if (in == null) return false;
            chunkTag = NbtIo.read(in);
        }

        // Skip-if-current: chunk is already at the latest DataVersion AND has no
        // embedded "entities" field to extract. Saves the conversion call and the
        // chunk rewrite. SimpleRegionStorage.upgradeChunkTag short-circuits on
        // version match anyway (and chunk conversion routes through Paper's
        // MCDataConverter, not Mojang's DataFixer), but the rewrite costs
        // sector-replacement IO that's non-trivial at archive scale on re-runs.
        // Conservative shape: only skip when nothing the upgrade would change.
        // Pre-1.17 chunks with legacy Entities still embedded force the full path
        // even on already-current DataVersion.
        int dataVersion = chunkTag.getIntOr("DataVersion", 0);
        boolean canSkipWrite = dataVersion == CURRENT_DATA_VERSION
            && (!splitEntities || chunkTag.getListOrEmpty("entities").isEmpty());
        if (canSkipWrite) {
            return true;
        }

        chunkTag = dfuHelper.upgradeChunkTag(chunkTag, -1);

        if (splitEntities) {
            // Post-DFU the legacy entities live at the lowercase "entities"
            // key on the chunk root; SerializableChunkData reads it from
            // there at SerializableChunkData.java:190. Entity-chunk file
            // format on the other hand still uses the capital-E "Entities"
            // key per SerializableChunkData.java:621 when writing.
            // Defensive copy: getListOrEmpty returns the underlying ListTag
            // and we're about to remove the key from the parent.
            ListTag entitiesList = chunkTag.getListOrEmpty("entities").copy();

            // Order: entities first, chunk second. On a crash between writes the
            // legacy entities stay on the chunk tag; rerun extracts and writes again.
            if (!entitiesList.isEmpty()) {
                CompoundTag entityTag = new CompoundTag();
                entityTag.put("Entities", entitiesList);
                entityTag.putIntArray("Position", new int[]{pos.x(), pos.z()});
                entityTag.putInt("DataVersion", CURRENT_DATA_VERSION);
                try (DataOutputStream out = entityRegion.getChunkDataOutputStream(pos)) {
                    NbtIo.write(entityTag, out);
                }
            }
            chunkTag.remove("entities");
        }

        try (DataOutputStream out = chunkRegion.getChunkDataOutputStream(pos)) {
            NbtIo.write(chunkTag, out);
        }
        return true;
    }

    private static void processMapData(
        MinecraftServer server, Set<String> completed, Path progressFile, Failures failures
    ) {
        Path mapsDir = server.storageSource.getLevelPath(LevelResource.DATA)
            .resolve("minecraft").resolve("maps");
        File[] files = mapsDir.toFile().listFiles((d, n) -> n.endsWith(".dat"));
        if (files == null || files.length == 0) {
            LOGGER.info("[The Archive] Upgrading map data: no files, skipping");
            return;
        }

        LOGGER.info("[The Archive] Upgrading map data: {} files", files.length);
        long startMillis = System.currentTimeMillis();
        long upgraded = 0;
        long skippedCurrent = 0;
        long resumed = 0;

        // Single-threaded loop runs on the main thread post-spin; pump the Paper
        // watchdog every 200 files (~hundred ms of DFU work) to avoid the 60s
        // exit-70 kill on large mapsets (47k+ archive files). Progress log every
        // 5000 processed (not resumed) files for visibility on large mapsets.
        int sincePump = 0;
        long processed = 0;
        for (File file : files) {
            String name = file.getName();
            String key = "mapdata " + name;
            if (completed.contains(key)) {
                resumed++;
                continue;
            }
            // last_id.dat is the map index (formerly idcounts.dat); everything else
            // in maps/ is per-map data. Names checked against MapIndex.TYPE (id
            // "maps/last_id") and MapId.key() ("maps/" + id).
            DataFixTypes fixType = name.equals("last_id.dat")
                ? DataFixTypes.SAVED_DATA_MAP_INDEX
                : DataFixTypes.SAVED_DATA_MAP_DATA;
            try {
                if (upgradeMapFile(file.toPath(), fixType)) {
                    upgraded++;
                } else {
                    skippedCurrent++;
                }
                appendProgress(progressFile, key, failures);
            } catch (Throwable t) {
                LOGGER.error("[The Archive] Map file {} failed: {}", name, t.toString());
                failures.recordRegion(key);
            }
            if (++sincePump >= 200) {
                org.spigotmc.WatchdogThread.tick();
                sincePump = 0;
            }
            if (++processed % 5000 == 0) {
                long elapsedMs = System.currentTimeMillis() - startMillis;
                long rate = elapsedMs > 0 ? processed * 1000L / elapsedMs : 0;
                LOGGER.info("[The Archive]   map data: {} / {} processed ({} maps/s, {} upgraded, {} already current)",
                            processed, files.length - resumed, rate, upgraded, skippedCurrent);
            }
        }

        long elapsedSec = (System.currentTimeMillis() - startMillis) / 1000;
        LOGGER.info("[The Archive] Map data upgrade complete: {} upgraded, {} already current, {} resumed in {}s",
                    upgraded, skippedCurrent, resumed, elapsedSec);
    }

    /**
     * Read one map .dat, DFU if below current version, rewrite gzipped. Returns
     * true when the file was rewritten, false when skipped because it's already
     * at current. Mirrors {@link net.minecraft.world.level.storage.SavedDataStorage#readTagFromDisk}'s
     * gzip detection: legacy uncompressed files predate the rename DFU and may
     * survive on ancient sources; post-rename files are gzip via NbtIo.writeCompressed.
     */
    private static boolean upgradeMapFile(Path file, DataFixTypes fixType) throws IOException {
        CompoundTag tag;
        try (InputStream in = Files.newInputStream(file);
             PushbackInputStream pin = new PushbackInputStream(new FastBufferedInputStream(in), 2)) {
            byte[] header = new byte[2];
            int read = pin.read(header, 0, 2);
            boolean gzip = read == 2 && (((header[1] & 0xFF) << 8) | (header[0] & 0xFF)) == 0x8B1F;
            if (read > 0) pin.unread(header, 0, read);
            if (gzip) {
                tag = NbtIo.readCompressed(pin, NbtAccounter.unlimitedHeap());
            } else {
                try (DataInputStream dis = new DataInputStream(pin)) {
                    tag = NbtIo.read(dis);
                }
            }
        }
        // 1343 (1.13.2) is the same pre-DataVersion fallback vanilla uses in
        // SavedDataStorage; older tags will DFU forward from there.
        int version = NbtUtils.getDataVersion(tag, 1343);
        if (version == CURRENT_DATA_VERSION) return false;
        CompoundTag fixed = fixType.update(DataFixers.getDataFixer(), tag, version, CURRENT_DATA_VERSION);
        // DataFixTypes.update doesn't stamp DataVersion (the codec wrapper does on
        // encode); we're writing raw NBT, so stamp ourselves or the next run will
        // see the pre-DFU version and re-fix.
        NbtUtils.addCurrentDataVersion(fixed);
        // Atomic write: NbtIo.writeCompressed truncates the target before streaming
        // gzip; a JVM kill mid-write leaves a stub. Stage to .tmp + ATOMIC_MOVE
        // so the on-disk file is either the pre-DFU original or the fully written
        // post-DFU version, never partial.
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(fixed, tmp);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    private static Path progressFilePath(MinecraftServer server) {
        return server.storageSource.getLevelDirectory().path()
            .resolve(".archive-upgrade-progress.txt");
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
            LOGGER.warn("[The Archive] Failed to read progress file, starting fresh: {}", ex.getMessage());
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
            LOGGER.error("[The Archive] Failed to append progress for {}: {}", entry, ex.getMessage());
            failures.progressAppendFailed.incrementAndGet();
        }
    }

    // Bounded version of the original spin-on-awaitTermination loop. Past
    // FINAL_DRAIN_TIMEOUT_MILLIS we shutdownNow and record any dropped tasks as
    // a synthetic region failure so they surface in the end-of-run summary.
    private static void awaitWithWatchdogTicks(ExecutorService pool, Failures failures) {
        long deadline = System.currentTimeMillis() + FINAL_DRAIN_TIMEOUT_MILLIS;
        try {
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    List<Runnable> dropped = pool.shutdownNow();
                    LOGGER.error("[The Archive] Pool drain exceeded {}m, forcing shutdown ({} tasks dropped)",
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

    // recordRegion is deduped (by key) so the cancel-path and the worker's own
    // catch can both call it without double-counting. recordChunk caps the
    // retained sample but always increments the count.
    private static final class Failures {
        final Set<String> regions = ConcurrentHashMap.newKeySet();
        final AtomicLong regionCount = new AtomicLong();
        final ConcurrentLinkedQueue<String> chunkSample = new ConcurrentLinkedQueue<>();
        final AtomicLong chunkCount = new AtomicLong();
        // Progress-marker write failure counter. Bumped from appendProgress's
        // catch when Files.writeString throws on the SIGKILL-safe progress file.
        // The region's work is durable at this point (RegionFile.close called
        // force(true)), but the resume marker is not. A non-zero count after
        // the pass means a SIGKILL+restart will redo every region whose marker
        // never landed.
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
