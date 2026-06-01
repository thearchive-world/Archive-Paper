package archive;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Walks {@code BuiltInRegistries.BLOCK} / {@code BLOCK_ENTITY_TYPE} into
 * relationship maps used by the {@code ghost-bes} and {@code be-type-mismatch}
 * dirt classes.
 *
 * <p>Exposed as static methods rather than eagerly-initialised static
 * fields so callers control the initialisation moment. Both registries
 * must be fully populated (post-Bootstrap, post-freeze) when these run;
 * the cleaner and audit invoke them at the top of {@code run(server)},
 * which is dispatched post-spin, so the snapshot is taken against a
 * stable registry. Class-init at JVM start would risk running before
 * Paper freezes the registries, producing an empty set/map that would
 * silently over-flag every BE entry as ghost.
 *
 * <p>Shared by {@link DirtNbtCleaner} (the strip rules) and
 * {@link AuditDirtyChunks} (the flag rules). Co-locating the registry
 * walks ensures both passes see the same block-entity surface.
 */
final class BlockEntityRegistry {
    private BlockEntityRegistry() {}

    /**
     * Snapshot of registered block ids whose {@link Block} implements
     * {@link EntityBlock}. {@code BlockState.hasBlockEntity()} reduces to
     * {@code block instanceof EntityBlock} (no state-specific variation
     * in vanilla), so a name-only set is the tightest predicate the
     * NBT-only walk can run.
     */
    static Set<String> computeBlocksWithEntity() {
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
     * Snapshot of the runtime map from BE type id to its valid-block set
     * (defined by {@link BlockEntityType#isValid}). Used by the
     * {@code be-type-mismatch} check; the type's accept set is iterated
     * against {@link Block#defaultBlockState()} for each registered
     * block, capturing the type's intended block targets even if a
     * specific block-state property would refine acceptance (the audit
     * walks NBT, not live BlockStates, so the default-state predicate is
     * the tightest test without faulting on chunk load).
     */
    static Map<String, Set<String>> computeBeIdToBlocks() {
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
}
