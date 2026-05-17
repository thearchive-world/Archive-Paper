package archive;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.jspecify.annotations.Nullable;

/**
 * Hand-rolled {@link LevelAccessor} stub used by the bake-light pass during
 * the own-chunk UpgradeData walk and the side-strip handler. Backed by a
 * worker-local cache of {@link BakeLightPass.CachedChunk}; reads and writes
 * go to {@link LevelChunkSection#getBlockState}/{@code setBlockState} on the
 * cached section's {@link net.minecraft.world.level.chunk.PalettedContainer}.
 *
 * <p>Surface deliberately minimal. {@link UpgradeData#upgradeInside} plus
 * {@link UpgradeData#upgradeSides} plus {@link Block#updateOrDestroy} call:
 * {@code getBlockState}, {@code setBlock}, {@code getBlockEntity},
 * {@code getRandom}, {@code getMinY}/{@code getMaxY},
 * {@code getSectionYFromSectionIndex}, {@code getFluidState},
 * {@code isClientSide}, {@code getBlockTicks}, {@code getFluidTicks},
 * {@code destroyBlock}. Block-specific {@code updateShape} implementations
 * occasionally call {@code scheduleTick} via the no-op tick-access stubs we
 * return; those ticks are dropped (UpgradeData neighbour ticks are handled
 * by the dedicated tick-replay pass, not by this stub). Anything else
 * the runtime tries to call throws {@link UnsupportedOperationException};
 * the per-chunk try-catch in {@link BakeLightPass#processRegion} converts
 * the throw to a partial-bake counter increment and moves on.
 *
 * <p>Cross-region write routing: {@code setBlock}/{@code destroyBlock}
 * targeting a chunk outside the worker's 3x3 working set increments
 * {@link #crossRegionWritesDropped} and returns without mutating. Stage 5
 * lands the journal; stage 3 just drops with a counter.
 */
final class BakeLevelAccessor implements LevelAccessor {
    private final ServerLevel level;
    private final Map<Long, BakeLightPass.CachedChunk> cache;
    private final RandomSource random;
    private final NoOpBlockTicks blockTicks = new NoOpBlockTicks();
    private final NoOpFluidTicks fluidTicks = new NoOpFluidTicks();

    final AtomicLong crossRegionWritesDropped = new AtomicLong();

    BakeLevelAccessor(final ServerLevel level, final Map<Long, BakeLightPass.CachedChunk> cache) {
        this.level = level;
        this.cache = cache;
        this.random = new SingleThreadedRandomSource(0L);
    }

    private BakeLightPass.CachedChunk entryFor(final int chunkX, final int chunkZ) {
        return this.cache.get(ChunkPos.pack(chunkX, chunkZ));
    }

    private @Nullable LevelChunkSection sectionAt(final BlockPos pos) {
        BakeLightPass.CachedChunk entry = entryFor(pos.getX() >> 4, pos.getZ() >> 4);
        if (entry == null) return null;
        return entry.sectionAt(this.level, pos.getY());
    }

    // ---- BlockGetter ----------------------------------------------------

    @Override
    public BlockState getBlockState(final BlockPos pos) {
        LevelChunkSection section = sectionAt(pos);
        if (section == null) return Blocks.AIR.defaultBlockState();
        return section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Override
    public FluidState getFluidState(final BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public @Nullable FluidState getFluidIfLoaded(final BlockPos pos) {
        return getFluidState(pos);
    }

    @Override
    public @Nullable BlockState getBlockStateIfLoaded(final BlockPos pos) {
        return getBlockState(pos);
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(final BlockPos pos) {
        // Bake stage 3 does not materialize BlockEntity objects. CHEST.updateShape
        // therefore skips the swapContents step (the instanceof checks fail) but
        // still applies the ChestType property change via setBlock. Item-content
        // normalization is forfeited; the chunk on first load no longer needs
        // UpgradeData to drive that swap because the source UpgradeData blob is
        // already stripped, so the runtime would not re-attempt it either.
        return null;
    }

    // ---- LevelWriter ----------------------------------------------------

    @Override
    public boolean setBlock(final BlockPos pos, final BlockState state, final int flags, final int recursionLimit) {
        LevelChunkSection section = sectionAt(pos);
        if (section == null) {
            this.crossRegionWritesDropped.incrementAndGet();
            return false;
        }
        section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state, false);
        markDirtyIfOwned(pos);
        return true;
    }

    @Override
    public boolean removeBlock(final BlockPos pos, final boolean movedByPiston) {
        return setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    public boolean destroyBlock(final BlockPos pos, final boolean dropBlock, final @Nullable Entity breaker, final int updateLimit) {
        // Block.updateOrDestroy routes air-result through this. We just clear to
        // air; no item drops (we're not a runtime world).
        return setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS, updateLimit);
    }

    private void markDirtyIfOwned(final BlockPos pos) {
        BakeLightPass.CachedChunk entry = entryFor(pos.getX() >> 4, pos.getZ() >> 4);
        if (entry != null && entry.owned) {
            entry.dirty = true;
        }
    }

    // ---- LevelHeightAccessor (via LevelReader defaults) -----------------

    @Override
    public int getMinY() {
        return this.level.getMinY();
    }

    @Override
    public int getHeight() {
        return this.level.getHeight();
    }

    // ---- ScheduledTickAccess --------------------------------------------

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return this.blockTicks;
    }

    @Override
    public LevelTickAccess<net.minecraft.world.level.material.Fluid> getFluidTicks() {
        return this.fluidTicks;
    }

    // ---- LevelAccessor --------------------------------------------------

    @Override
    public long nextSubTickCount() {
        return 0L;
    }

    @Override
    public LevelData getLevelData() {
        return this.level.getLevelData();
    }

    @Override
    public @Nullable MinecraftServer getServer() {
        return this.level.getServer();
    }

    @Override
    public ChunkSource getChunkSource() {
        // Some BlockBehaviour.updateShape paths poke ChunkSource for hasChunk
        // checks. We delegate to the real level; reads back the runtime chunk
        // state which is fine because we hold no in-memory mutations there.
        return this.level.getChunkSource();
    }

    @Override
    public RandomSource getRandom() {
        return this.random;
    }

    @Override
    public ServerLevel getMinecraftWorld() {
        return this.level;
    }

    // ---- LevelReader: bits BlockBehaviour.updateShape may poke ----------

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public int getSeaLevel() {
        return this.level.getSeaLevel();
    }

    @Override
    public DimensionType dimensionType() {
        return this.level.dimensionType();
    }

    @Override
    public RegistryAccess registryAccess() {
        return this.level.registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return this.level.enabledFeatures();
    }

    @Override
    public EnvironmentAttributeReader environmentAttributes() {
        return this.level.environmentAttributes();
    }

    @Override
    public int getSkyDarken() {
        return 0;
    }

    @Override
    public BiomeManager getBiomeManager() {
        throw unsupported("getBiomeManager");
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(final int quartX, final int quartY, final int quartZ) {
        throw unsupported("getUncachedNoiseBiome");
    }

    @Override
    public int getHeight(final Heightmap.Types type, final int x, final int z) {
        throw unsupported("getHeight(type,x,z)");
    }

    @Override
    public @Nullable ChunkAccess getChunk(final int chunkX, final int chunkZ, final ChunkStatus status, final boolean load) {
        // BlockBehaviour.updateShape never calls this in practice for the
        // UpgradeData walk; defensive null return rather than throw so any
        // surprise call path on a non-existent neighbour quietly degrades.
        return null;
    }

    @Override
    public @Nullable ChunkAccess getChunkIfLoadedImmediately(final int chunkX, final int chunkZ) {
        return null;
    }

    @Override
    public boolean hasChunk(final int chunkX, final int chunkZ) {
        return this.cache.containsKey(ChunkPos.pack(chunkX, chunkZ));
    }

    // ---- LevelSimulatedReader -------------------------------------------

    @Override
    public boolean isStateAtPosition(final BlockPos pos, final Predicate<BlockState> predicate) {
        return predicate.test(getBlockState(pos));
    }

    @Override
    public boolean isFluidAtPosition(final BlockPos pos, final Predicate<FluidState> predicate) {
        return predicate.test(getFluidState(pos));
    }

    @Override
    public <T extends BlockEntity> Optional<T> getBlockEntity(final BlockPos pos, final net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        return Optional.empty();
    }

    // ---- CollisionGetter / SignalGetter / BlockAndLightGetter -----------

    @Override
    public net.minecraft.world.level.border.WorldBorder getWorldBorder() {
        throw unsupported("getWorldBorder");
    }

    @Override
    public List<VoxelShape> getEntityCollisions(final @Nullable Entity source, final AABB testArea) {
        return List.of();
    }

    @Override
    public @Nullable BlockGetter getChunkForCollisions(final int chunkX, final int chunkZ) {
        return null;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        // VegetationBlock.updateShape -> CropBlock.canSurvive -> hasSufficientLight
        // hits this path. Returning a real engine would either NPE (no chunk-system
        // tickets for our bake chunks) or report 0 brightness, both of which would
        // make canSurvive return false and silently destroy the crop. We override
        // {@link #getRawBrightness}/{@link #getBrightness} below to return max, so
        // this fallback should never fire; throw to surface any new caller.
        throw unsupported("getLightEngine");
    }

    @Override
    public int getRawBrightness(final BlockPos pos, final int darkening) {
        // Max brightness keeps light-checking canSurvive paths (crops, vegetation)
        // benign during the bake walk. The walk is not allowed to delete blocks
        // based on transient bake-time light values.
        return 15;
    }

    @Override
    public int getBrightness(final net.minecraft.world.level.LightLayer layer, final BlockPos pos) {
        return 15;
    }

    @Override
    public boolean canSeeSky(final BlockPos pos) {
        return true;
    }

    // ---- EntityGetter ---------------------------------------------------

    @Override
    public List<Entity> getEntities(final @Nullable Entity except, final AABB bb, final Predicate<? super Entity> selector) {
        return List.of();
    }

    @Override
    public <T extends Entity> List<T> getEntities(final EntityTypeTest<Entity, T> type, final AABB bb, final Predicate<? super T> selector) {
        return List.of();
    }

    @Override
    public List<? extends Player> players() {
        return List.of();
    }

    // ---- Side-effect-free no-ops for sounds/particles/events ------------

    @Override
    public void playSound(final @Nullable Entity except, final BlockPos pos, final SoundEvent sound, final SoundSource source, final float volume, final float pitch) {
    }

    @Override
    public void addParticle(final ParticleOptions particle, final double x, final double y, final double z, final double xd, final double yd, final double zd) {
    }

    @Override
    public void levelEvent(final @Nullable Entity source, final int type, final BlockPos pos, final int data) {
    }

    @Override
    public void gameEvent(final Holder<GameEvent> event, final Vec3 position, final GameEvent.Context ctx) {
    }

    @Override
    public void neighborShapeChanged(final Direction direction, final BlockPos pos, final BlockPos neighborPos, final BlockState neighborState, final int updateFlags, final int updateLimit) {
        // Vanilla recursively re-runs updateShape via NeighborUpdater.executeShapeUpdate.
        // We're inside an upgrade walk that already iterates every block; no need to
        // recursively schedule more updates from updateShape side effects.
    }

    private static UnsupportedOperationException unsupported(final String name) {
        return new UnsupportedOperationException("BakeLevelAccessor." + name + " unsupported");
    }

    // ---- No-op tick-access classes --------------------------------------

    private static final class NoOpBlockTicks implements LevelTickAccess<Block> {
        @Override public boolean willTickThisTick(final BlockPos pos, final Block type) { return false; }
        @Override public void schedule(final ScheduledTick<Block> tick) { /* drop */ }
        @Override public boolean hasScheduledTick(final BlockPos pos, final Block type) { return false; }
        @Override public int count() { return 0; }
    }

    private static final class NoOpFluidTicks implements LevelTickAccess<net.minecraft.world.level.material.Fluid> {
        @Override public boolean willTickThisTick(final BlockPos pos, final net.minecraft.world.level.material.Fluid type) { return false; }
        @Override public void schedule(final ScheduledTick<net.minecraft.world.level.material.Fluid> tick) { /* drop */ }
        @Override public boolean hasScheduledTick(final BlockPos pos, final net.minecraft.world.level.material.Fluid type) { return false; }
        @Override public int count() { return 0; }
    }
}
