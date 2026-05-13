package archive;

import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.slf4j.Logger;

/**
 * Facade entry point for the chunk upgrade phase. Today this is a
 * passthrough to {@link DirectNbtUpgrader}; step 2 (docs/upgrade-via-chunk-system-plan.md)
 * adds a dispatch on {@link ArchiveSettings}.normalizeWorld() to
 * ChunkSystemUpgrader for the thorough path.
 *
 * The {@link #enumerateOccupiedChunks} helper below is unused in step 1 and stays
 * here pending step 2's lift to ChunkSystemUpgrader. Its line-referenced comments
 * mirror upstream RegionStorageUpgrader.getAllChunkPositions and are load-bearing
 * for future maintenance; do not delete or rewrite.
 */
public final class ChunkUpgrader {
    @SuppressWarnings("unused")
    private static final Logger LOGGER = LogUtils.getLogger();
    @SuppressWarnings("unused")
    private static final Pattern REGION_FILE_REGEX = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca$");

    private ChunkUpgrader() {}

    public static void run(MinecraftServer server) {
        DirectNbtUpgrader.run(server);
    }

    /**
     * Mirror of net/minecraft/util/worldupdate/RegionStorageUpgrader#getAllChunkPositions
     * (RegionStorageUpgrader.java:162-197): list dim/region/*.mca, parse r.X.Z.mca
     * filenames, open each RegionFile read-only, iterate the 32x32 grid, collect
     * positions where regionFile.doesChunkExist(ChunkPos) returns true.
     *
     * Translation notes (this is a mirror, not a copy):
     *   - The upgrader returns List of FileToUpgrade (per-region-file grouping). We
     *     flatten to List of ChunkPos because the upgrade pass loads chunks via
     *     ServerChunkCache.getChunk, which manages its own region-file caching;
     *     we have no use for the per-file grouping.
     *   - RegionFile ctor is 4-arg: (RegionStorageInfo info, Path file,
     *     Path externalFileDir, boolean sync) throws IOException. Pass regionFolder
     *     as externalFileDir and sync=true, matching the upgrader at line 176.
     *   - RegionStorageInfo for the region/ folder is built as
     *     new RegionStorageInfo(levelStorage.getLevelId(), level.dimension(), "chunk").
     *     The "chunk" type-token is canonical per ChunkMap.java:191
     *     (cf. "poi" at ChunkMap.java:225, "entities" at ServerLevel.java:731).
     *   - regionFolder.toFile().listFiles(filter) returns null when the folder is
     *     absent (e.g. a dimension that has never been visited). Return List.of()
     *     in that case, mirroring RegionStorageUpgrader.java:164-165.
     *   - Existence predicate is RegionFile.doesChunkExist (RegionFile.java:724),
     *     not RegionFile.hasChunk (RegionFile.java:863) which exists but is not
     *     what the upgrader uses; mirror the upgrader's choice.
     *
     * Enumerate only from region/ (block-data side); entities/ and poi/ are exactly
     * what we want to populate, so iterating them would short-circuit the upgrade.
     */
    @SuppressWarnings("unused")
    private static List<ChunkPos> enumerateOccupiedChunks(ServerLevel level) {
        // server.storageSource.getDimensionPath(dimension) is the canonical
        // dimension-root accessor (see ServerLevel.java:634, ServerChunkCache.java).
        // Resolve "region" for the block-data folder.
        MinecraftServer server = level.getServer();
        Path regionFolder = server.storageSource.getDimensionPath(level.dimension()).resolve("region");
        File[] files = regionFolder.toFile().listFiles((dir, name) -> name.endsWith(".mca"));
        if (files == null) {
            return List.of();
        }
        RegionStorageInfo info = new RegionStorageInfo(
            server.storageSource.getLevelId(),
            level.dimension(),
            "chunk"
        );
        List<ChunkPos> positions = new ArrayList<>();
        for (File regionFile : files) {
            Matcher regex = REGION_FILE_REGEX.matcher(regionFile.getName());
            if (!regex.matches()) continue;
            int xOffset = Integer.parseInt(regex.group(1)) << 5;
            int zOffset = Integer.parseInt(regex.group(2)) << 5;
            try (RegionFile regionSource = new RegionFile(info, regionFile.toPath(), regionFolder, true)) {
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        ChunkPos pos = new ChunkPos(x + xOffset, z + zOffset);
                        if (regionSource.doesChunkExist(pos)) {
                            positions.add(pos);
                        }
                    }
                }
            } catch (IOException ex) {
                LOGGER.error("[The Archive] Failed to read chunks from region file {}", regionFile.toPath(), ex);
            }
        }
        return positions;
    }
}
