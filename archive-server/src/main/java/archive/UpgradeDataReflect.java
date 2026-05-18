package archive;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Direction8;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.SavedTick;

/**
 * Reflection bridge for {@link UpgradeData}'s package-private fixer registry.
 * Vanilla keeps {@code MAP} and {@code BlockFixers} private; the bake-light
 * pass needs to drive the same dispatch from {@code archive} package. A
 * Paper patch is the alternative; reflection keeps the blast radius local
 * and the upstream surface untouched.
 *
 * <p>Same-classloader unnamed-module access; {@code setAccessible(true)}
 * succeeds without {@code --add-opens} on Paper's runtime. If the upstream
 * field layout ever shifts the static initializer fails fast at class load
 * (chained as {@link ExceptionInInitializerError}) rather than later during
 * a bake walk.
 */
final class UpgradeDataReflect {
    private static final UpgradeData.BlockFixer DEFAULT_FIXER;
    private static final Map<Block, UpgradeData.BlockFixer> MAP;
    private static final Set<UpgradeData.BlockFixer> CHUNKY_FIXERS;
    private static final Field UPGRADE_DATA_INDEX_FIELD;
    private static final Field UPGRADE_DATA_SIDES_FIELD;
    private static final Field UPGRADE_DATA_BLOCK_TICKS_FIELD;
    private static final Field UPGRADE_DATA_FLUID_TICKS_FIELD;

    static {
        try {
            // Class.forName(name) with default init=true triggers static init of
            // BlockFixers; populating MAP and CHUNKY_FIXERS as the enum constants
            // construct. The class itself is package-private, so reflective access
            // to its members requires setAccessible.
            Class<?> blockFixersClass = Class.forName(
                "net.minecraft.world.level.chunk.UpgradeData$BlockFixers",
                true, UpgradeData.class.getClassLoader());

            Field mapField = UpgradeData.class.getDeclaredField("MAP");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Block, UpgradeData.BlockFixer> map = (Map<Block, UpgradeData.BlockFixer>) mapField.get(null);
            MAP = map;

            Field chunkyField = UpgradeData.class.getDeclaredField("CHUNKY_FIXERS");
            chunkyField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<UpgradeData.BlockFixer> chunky = (Set<UpgradeData.BlockFixer>) chunkyField.get(null);
            CHUNKY_FIXERS = chunky;

            // Read DEFAULT directly off the enum class field rather than via
            // Enum.valueOf (which routes through the enclosing-class access check).
            Field defaultField = blockFixersClass.getDeclaredField("DEFAULT");
            defaultField.setAccessible(true);
            DEFAULT_FIXER = (UpgradeData.BlockFixer) defaultField.get(null);

            UPGRADE_DATA_INDEX_FIELD = UpgradeData.class.getDeclaredField("index");
            UPGRADE_DATA_INDEX_FIELD.setAccessible(true);
            UPGRADE_DATA_SIDES_FIELD = UpgradeData.class.getDeclaredField("sides");
            UPGRADE_DATA_SIDES_FIELD.setAccessible(true);
            UPGRADE_DATA_BLOCK_TICKS_FIELD = UpgradeData.class.getDeclaredField("neighborBlockTicks");
            UPGRADE_DATA_BLOCK_TICKS_FIELD.setAccessible(true);
            UPGRADE_DATA_FLUID_TICKS_FIELD = UpgradeData.class.getDeclaredField("neighborFluidTicks");
            UPGRADE_DATA_FLUID_TICKS_FIELD.setAccessible(true);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private UpgradeDataReflect() {}

    static UpgradeData.BlockFixer fixerFor(final Block block) {
        return MAP.getOrDefault(block, DEFAULT_FIXER);
    }

    /** Mirrors vanilla's {@code CHUNKY_FIXERS.forEach(fixer -> fixer.processChunk(level))}. */
    static void runChunkyFixers(final LevelAccessor level) {
        for (UpgradeData.BlockFixer fixer : CHUNKY_FIXERS) {
            fixer.processChunk(level);
        }
    }

    static int[][] indices(final UpgradeData ud) {
        try {
            return (int[][]) UPGRADE_DATA_INDEX_FIELD.get(ud);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    static EnumSet<Direction8> sides(final UpgradeData ud) {
        try {
            return (EnumSet<Direction8>) UPGRADE_DATA_SIDES_FIELD.get(ud);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    static List<SavedTick<Block>> neighborBlockTicks(final UpgradeData ud) {
        try {
            return (List<SavedTick<Block>>) UPGRADE_DATA_BLOCK_TICKS_FIELD.get(ud);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    static List<SavedTick<Fluid>> neighborFluidTicks(final UpgradeData ud) {
        try {
            return (List<SavedTick<Fluid>>) UPGRADE_DATA_FLUID_TICKS_FIELD.get(ud);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
