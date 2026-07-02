package archive;

import ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AllFeatures
public class AuditBakeStatusTest {

    /** Minimal parseable chunk: bake classification requires a Status string. */
    private static CompoundTag baseChunk() {
        CompoundTag root = new CompoundTag();
        root.putString("Status", "minecraft:full");
        return root;
    }

    private static CompoundTag completeChunk() {
        CompoundTag root = baseChunk();
        root.putByte("isLightOn", (byte) 1);
        root.putInt(SaveUtil.STARLIGHT_VERSION_TAG, SaveUtil.STARLIGHT_LIGHT_VERSION);
        CompoundTag heightmaps = new CompoundTag();
        for (Heightmap.Types type : ChunkStatus.FULL.heightmapsAfter()) {
            heightmaps.putLongArray(type.getSerializationKey(), new long[]{0L});
        }
        root.put("Heightmaps", heightmaps);
        return root;
    }

    @Test
    void missingStatusIsIneligibleEvenWithAllBakeMarkers() {
        CompoundTag root = completeChunk();
        root.remove("Status");
        assertEquals(AuditDirtyChunks.BakeStatus.INELIGIBLE, AuditDirtyChunks.classifyBakeStatus(root));
    }

    @Test
    void allFourMarkersClassifyComplete() {
        assertEquals(AuditDirtyChunks.BakeStatus.COMPLETE, AuditDirtyChunks.classifyBakeStatus(completeChunk()));
    }

    @Test
    void freshChunkWithoutBakeArtifactsIsPendingNotPartial() {
        // Absent UpgradeData counts as drained (1 of 4 markers), but with no
        // light pair and no heightmaps there is no bake artifact: PENDING.
        assertEquals(AuditDirtyChunks.BakeStatus.PENDING, AuditDirtyChunks.classifyBakeStatus(baseChunk()));
    }

    @Test
    void anyBakeArtifactShortOfCompleteIsPartial() {
        CompoundTag lightOnly = baseChunk();
        lightOnly.putByte("isLightOn", (byte) 1);
        assertEquals(AuditDirtyChunks.BakeStatus.PARTIAL, AuditDirtyChunks.classifyBakeStatus(lightOnly));
    }

    @Test
    void staleStarlightVersionDemotesCompleteToPartial() {
        CompoundTag root = completeChunk();
        root.putInt(SaveUtil.STARLIGHT_VERSION_TAG, SaveUtil.STARLIGHT_LIGHT_VERSION - 1);
        assertEquals(AuditDirtyChunks.BakeStatus.PARTIAL, AuditDirtyChunks.classifyBakeStatus(root));
    }

    @Test
    void missingIsLightOnDemotesCompleteToPartial() {
        CompoundTag root = completeChunk();
        root.remove("isLightOn");
        assertEquals(AuditDirtyChunks.BakeStatus.PARTIAL, AuditDirtyChunks.classifyBakeStatus(root));
    }

    @Test
    void missingOneHeightmapDemotesCompleteToPartial() {
        CompoundTag root = completeChunk();
        String firstKey = ChunkStatus.FULL.heightmapsAfter().iterator().next().getSerializationKey();
        CompoundTag heightmaps = root.getCompoundOrEmpty("Heightmaps");
        heightmaps.remove(firstKey);
        root.put("Heightmaps", heightmaps);
        assertEquals(AuditDirtyChunks.BakeStatus.PARTIAL, AuditDirtyChunks.classifyBakeStatus(root));
    }

    @Test
    void undrainedUpgradeDataDemotesCompleteToPartial() {
        CompoundTag root = completeChunk();
        CompoundTag upgradeData = new CompoundTag();
        upgradeData.putByte("Sides", (byte) 1);
        root.put("UpgradeData", upgradeData);
        assertEquals(AuditDirtyChunks.BakeStatus.PARTIAL, AuditDirtyChunks.classifyBakeStatus(root));
    }

    @Test
    void absentAndEmptyUpgradeDataAreDrained() {
        assertTrue(AuditDirtyChunks.upgradeDataIsDrained(new CompoundTag()));
        CompoundTag root = new CompoundTag();
        root.put("UpgradeData", new CompoundTag());
        assertTrue(AuditDirtyChunks.upgradeDataIsDrained(root));
    }

    @Test
    void indicesCompoundMarksUndrained() {
        // The documented type-trap: vanilla serializes Indices as a CompoundTag
        // keyed by section index, not a ListTag. This pins the compound read.
        CompoundTag indices = new CompoundTag();
        indices.putIntArray("0", new int[]{5});
        CompoundTag upgradeData = new CompoundTag();
        upgradeData.put("Indices", indices);
        CompoundTag root = new CompoundTag();
        root.put("UpgradeData", upgradeData);
        assertFalse(AuditDirtyChunks.upgradeDataIsDrained(root));
    }

    @Test
    void sidesAndNeighbourTicksMarkUndrained() {
        CompoundTag sides = new CompoundTag();
        sides.putByte("Sides", (byte) 4);
        CompoundTag rootSides = new CompoundTag();
        rootSides.put("UpgradeData", sides);
        assertFalse(AuditDirtyChunks.upgradeDataIsDrained(rootSides));

        for (String key : new String[]{"neighbor_block_ticks", "neighbor_fluid_ticks"}) {
            CompoundTag upgradeData = new CompoundTag();
            ListTag ticks = new ListTag();
            ticks.add(new CompoundTag());
            upgradeData.put(key, ticks);
            CompoundTag root = new CompoundTag();
            root.put("UpgradeData", upgradeData);
            assertFalse(AuditDirtyChunks.upgradeDataIsDrained(root), key);
        }
    }

    @Test
    void unrelatedUpgradeDataKeysStillCountAsDrained() {
        CompoundTag upgradeData = new CompoundTag();
        upgradeData.putInt("unrelated", 1);
        CompoundTag root = new CompoundTag();
        root.put("UpgradeData", upgradeData);
        assertTrue(AuditDirtyChunks.upgradeDataIsDrained(root));
    }
}
