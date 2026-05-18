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
import java.util.EnumSet;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction8;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.ProtoChunkTicks;
import ca.spottedleaf.moonrise.patches.starlight.light.StarLightEngine;
import ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface;
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
 * <p>Stage 3 + stage 4 (this state): stage 3 runs the own-chunk UpgradeData
 * walk and side-strip handler against a {@link BakeLevelAccessor} stub;
 * stage 4 builds a {@link ProtoChunk} per owned cache entry, primes the
 * {@link ChunkStatus#FULL} heightmaps, and runs Starlight's
 * {@link StarLightInterface#lightChunk} against a {@link BakeLightChunkGetter}
 * over the same 3x3 cache, then merges the baked heightmaps and section light
 * nibbles back into the parsed record via {@link SerializableChunkData#copyOf}.
 * Write-back is unchanged: {@link SerializableChunkData#write} via {@link
 * NbtIo} through the still-open center {@link RegionFile}. Skip-if-baked
 * fires when {@link UpgradeData} is empty, {@code lightCorrect} is true,
 * and the heightmap map is populated.
 *
 * <p>Forfeits still owed (later stages):
 * <ul>
 *   <li>{@code UpgradeData.neighbor_block_ticks} /
 *       {@code neighbor_fluid_ticks} replay (stage 5).</li>
 *   <li>Cross-region LEAVES BFS / CHEST writes landing outside the 3x3
 *       working set (stage 6, journal piggy-backs on stage 5).</li>
 *   <li>{@code bake-status} audit counters (stage 8).</li>
 *   <li>Bake-specific smoke harnesses (stage 9).</li>
 * </ul>
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
            LOGGER.info("[The Archive] Bake-light complete: {} chunks parsed, {} baked, {} cross-region writes dropped in {}s",
                        totalChunks, failures.chunksBaked.get(), failures.crossRegionWritesDropped.get(), elapsedSec);
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
     * Stage 3 worker flow for one region. Sequence:
     * <ol>
     *   <li>Open the center {@link RegionFile} (non-DSYNC, same as {@link
     *       DirectNbtUpgrader}; durability at close via {@code file.force(true)}).</li>
     *   <li>Parse all 1024 populated slots into {@link CachedChunk} entries
     *       marked {@code owned=true}.</li>
     *   <li>Open each of up to 8 neighbour region files. Parse the 32 edge
     *       chunks of each cardinal neighbour and the 1 corner chunk of each
     *       diagonal neighbour into the cache marked {@code owned=false}.
     *       Missing region files and missing slots tolerated silently.</li>
     *   <li>For every owned cache entry whose {@link UpgradeData} is
     *       non-empty, run the own-chunk walk (mirrors {@code upgradeInside}),
     *       drain LEAVES chunky-fixers, then the side-strip handler
     *       (mirrors {@code upgradeSides}). The walk mutates {@link
     *       PalettedContainer} state in the cached {@link LevelChunkSection}
     *       and flips the entry's {@code dirty} flag.</li>
     *   <li>For every dirty owned cache entry, rebuild a {@link
     *       SerializableChunkData} record with {@link UpgradeData#EMPTY}
     *       (other fields reused; the mutated section list is shared), call
     *       {@code .write()}, and write the resulting {@link CompoundTag}
     *       back through the still-open center {@code RegionFile}.</li>
     * </ol>
     *
     * <p>Cross-region writes (LEAVES BFS, CHEST pairing landing outside the
     * 3x3) drop with a {@link Failures#crossRegionWritesDropped} counter
     * increment; the journal lands in stage 5/6, not this stage.
     *
     * <p>Per-chunk try-catch: any throw inside the walk or write-back logs a
     * partial-bake counter and continues with the rest of the region. The
     * old NBT for that chunk stays on disk untouched; runtime first-load
     * lazy-path code fixes the residual on its own.
     */
    private static long processRegion(
        RegionStorageInfo info, ServerLevel level, Path regionFolder, int rx, int rz, Failures failures
    ) throws IOException {
        Path regionPath = regionFolder.resolve("r." + rx + "." + rz + ".mca");
        if (!Files.exists(regionPath)) return 0;
        String regionLabel = "r." + rx + "." + rz;
        Map<Long, CachedChunk> cache = new HashMap<>(1156);
        long parsed = 0;
        try (RegionFile region = new RegionFile(info, regionPath, regionFolder, false)) {
            parsed = loadCenter(region, level, cache, regionLabel, failures, rx, rz);
            loadBorder(info, level, regionFolder, rx, rz, cache);

            BakeLevelAccessor accessor = new BakeLevelAccessor(level, cache);
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
                boolean upgradeNeeded = !entry.data.upgradeData().isEmpty();
                boolean lightNeeded = !entry.data.lightCorrect() || entry.data.heightmaps().isEmpty();
                if (!upgradeNeeded && !lightNeeded) continue;
                ChunkPos pos = entry.data.chunkPos();
                try {
                    if (upgradeNeeded) bakeChunkUpgrade(accessor, level, entry, pos);
                    bakeChunkLight(level, lightInterface, entry);
                    entry.dirty = true;
                    baked++;
                } catch (Throwable t) {
                    String chunkKey = regionLabel + " bake(" + pos.x() + "," + pos.z() + ")";
                    LOGGER.error("[The Archive] bake-light {} failed: {}", chunkKey, t.toString(), t);
                    failures.recordChunk(chunkKey);
                }
            }
            failures.chunksBaked.addAndGet(baked);

            // Region finalize: write back every owned dirty cache entry. Only owned
            // entries belong to this region's slot map; neighbour-owned writes that
            // dirtied a non-owned border entry are dropped (stage 5 journal handles
            // those). Region's force(true) on close gives durability granularity.
            for (CachedChunk entry : cache.values()) {
                if (!entry.dirty || !entry.owned) continue;
                ChunkPos pos = entry.data.chunkPos();
                try {
                    writeChunk(region, pos, entry.data);
                } catch (Throwable t) {
                    String chunkKey = regionLabel + " writeback(" + pos.x() + "," + pos.z() + ")";
                    LOGGER.error("[The Archive] bake-light {} failed: {}", chunkKey, t.toString(), t);
                    failures.recordChunk(chunkKey);
                }
            }

            failures.crossRegionWritesDropped.addAndGet(accessor.crossRegionWritesDropped.get());
        }
        return parsed;
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
     * is silently tolerated.
     */
    private static void loadBorder(
        RegionStorageInfo info, ServerLevel level, Path regionFolder, int rx, int rz, Map<Long, CachedChunk> cache
    ) {
        // 4 cardinal neighbours contribute one 32-chunk edge each.
        loadEdge(info, level, regionFolder, rx - 1, rz, 31, -1, -1, 0, 32, cache);   // west neighbour east edge
        loadEdge(info, level, regionFolder, rx + 1, rz, 0,  -1, -1, 0, 32, cache);   // east neighbour west edge
        loadEdge(info, level, regionFolder, rx, rz - 1, -1, 31, 0,  -1, 32, cache);  // north neighbour south edge
        loadEdge(info, level, regionFolder, rx, rz + 1, -1, 0,  0,  -1, 32, cache);  // south neighbour north edge

        // 4 diagonal neighbours contribute one corner chunk each.
        loadEdge(info, level, regionFolder, rx - 1, rz - 1, 31, 31, -1, -1, 1, cache);
        loadEdge(info, level, regionFolder, rx + 1, rz - 1, 0,  31, -1, -1, 1, cache);
        loadEdge(info, level, regionFolder, rx - 1, rz + 1, 31, 0,  -1, -1, 1, cache);
        loadEdge(info, level, regionFolder, rx + 1, rz + 1, 0,  0,  -1, -1, 1, cache);
    }

    /**
     * Read 1..32 slots from a neighbour region. Edge specifiers:
     * {@code fixedX}/{@code fixedZ} = pinned coord (use -1 to iterate),
     * {@code baseX}/{@code baseZ} = iteration base when -1 was passed for
     * the corresponding fixed coord (use 0 for full edge, ignored for
     * pinned). {@code count} is the slot count to read. Cardinal edges pass
     * one pinned coord and count=32; corners pin both and count=1.
     */
    private static void loadEdge(
        RegionStorageInfo info, ServerLevel level, Path regionFolder,
        int neighbourRx, int neighbourRz,
        int fixedX, int fixedZ, int baseX, int baseZ, int count,
        Map<Long, CachedChunk> cache
    ) {
        Path neighbourPath = regionFolder.resolve("r." + neighbourRx + "." + neighbourRz + ".mca");
        if (!Files.exists(neighbourPath)) return;
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
        } catch (Exception ex) {
            LOGGER.warn("[The Archive] bake-light border load r.{}.{} failed: {}",
                        neighbourRx, neighbourRz, ex.toString());
        }
    }

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

        CachedChunk(SerializableChunkData data, boolean owned) {
            this.data = data;
            this.owned = owned;
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
        final AtomicLong crossRegionWritesDropped = new AtomicLong();
        final AtomicLong chunksBaked = new AtomicLong();

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
