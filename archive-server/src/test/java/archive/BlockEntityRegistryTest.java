package archive;

import java.util.Map;
import java.util.Set;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AllFeatures
public class BlockEntityRegistryTest {

    @Test
    void blocksWithEntitySpotChecks() {
        Set<String> blocks = BlockEntityRegistry.computeBlocksWithEntity();
        assertTrue(blocks.contains("minecraft:chest"));
        assertTrue(blocks.contains("minecraft:furnace"));
        assertFalse(blocks.contains("minecraft:stone"));
        assertFalse(blocks.contains("minecraft:air"));
    }

    @Test
    void beIdToBlocksSpotChecks() {
        Map<String, Set<String>> map = BlockEntityRegistry.computeBeIdToBlocks();
        assertTrue(map.get("minecraft:chest").contains("minecraft:chest"));
        // The be-type-mismatch dirt class the audit docstring names:
        // a hopper BE at a beacon block, a dispenser BE at a dropper block.
        assertFalse(map.get("minecraft:hopper").contains("minecraft:beacon"));
        assertFalse(map.get("minecraft:dispenser").contains("minecraft:dropper"));
    }

    @Test
    void everyValidTargetBlockIsAnEntityBlock() {
        // Cross-map invariant, not a registry golden list: any block a BE type
        // accepts must itself be in the EntityBlock set, or the ghost-bes and
        // be-type-mismatch predicates would disagree about the same block.
        Set<String> blocksWithEntity = BlockEntityRegistry.computeBlocksWithEntity();
        Map<String, Set<String>> map = BlockEntityRegistry.computeBeIdToBlocks();
        for (Map.Entry<String, Set<String>> entry : map.entrySet()) {
            for (String block : entry.getValue()) {
                assertTrue(blocksWithEntity.contains(block),
                    "BE type " + entry.getKey() + " accepts " + block + " which is not an EntityBlock");
            }
        }
    }
}
