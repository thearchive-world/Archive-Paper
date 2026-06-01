package archive;

import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
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
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.resources.Identifier;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

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
 * targeting a non-owned cache entry (border chunk read from a neighbour
 * region) appends a {@code DISCRIMINANT_BLOCK_STATE} record to the
 * worker's {@link BakeLightJournal} and bumps {@link #crossRegionWritesJournaled}.
 * A write targeting a chunk outside the 3x3 working set entirely (no cache
 * entry) is journaled the same way, since the operator-supplied LEAVES BFS
 * can spill beyond the immediate border in pathological corner cases; the
 * tail pass drops it with {@link BakeLightPass.Failures#crossRegionWritesMissingTarget}
 * if the destination region file does not exist on disk.
 *
 * <p>Registry / datapack invariant: {@link #registryAccess} and
 * {@link #environmentAttributes} return the backing {@link ServerLevel}'s
 * accessors directly. The bake pipeline runs against whatever registry and
 * datapack state was loaded at JVM start. The CHEST {@code swapContents}
 * path calls {@link BlockEntity#loadStatic} with this registry to resolve
 * item ids; modded items, custom enchantments, or datapack additions that
 * existed in the source world but are not loaded here silently drop during
 * the swap. The operator is responsible for ensuring the offline pipeline
 * runs with the same datapack and mod set as the source world; running
 * without parity loses data with no counter increment because the
 * vanilla {@code loadStatic} path treats unknown ids as legitimate empties.
 * No equivalent check fires from this stub.
 */
final class BakeLevelAccessor implements LevelAccessor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final NoOpBlockTicks BLOCK_TICKS = new NoOpBlockTicks();
    private static final NoOpFluidTicks FLUID_TICKS = new NoOpFluidTicks();

    private final ServerLevel level;
    private final Map<Long, BakeLightPass.CachedChunk> cache;
    private final RandomSource random;
    private final BakeLightJournal journal;
    private final Identifier dim;

    final AtomicLong crossRegionWritesJournaled = new AtomicLong();
    final AtomicLong crossRegionWritesIoFailed = new AtomicLong();
    // Counter for getChunk / getChunkIfLoadedImmediately stub-null returns.
    // Zero on every observed run today; non-zero surfaces a new vanilla code
    // path that hit the stub and got a silent null instead of a real chunk,
    // which can quietly produce wrong-shape state. Observability only; does
    // not gate the FAILED branch or progress-file retention.
    final AtomicLong getChunkCalls = new AtomicLong();
    // First-occurrence flag for setBlock journal IOException logging.
    // The counter still bumps on every failure and the progress-file gate
    // still fires; this flag throttles the ERROR-level log to once per
    // accessor instance so a sustained disk failure does not spam the log.
    private boolean loggedJournalIoFailure = false;

    BakeLevelAccessor(
        final ServerLevel level,
        final Map<Long, BakeLightPass.CachedChunk> cache,
        final BakeLightJournal journal,
        final Identifier dim
    ) {
        this.level = level;
        this.cache = cache;
        this.journal = journal;
        this.dim = dim;
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
        // BE index lifted off the cache entry so CHEST.swapContents can read
        // both halves of a chest pair. Owned and non-owned entries contribute
        // equally; non-owned entries are read so the swap can fire even when
        // one chest sits across the region boundary. Returning null for
        // positions outside the 3x3 working set preserves the contract for any
        // other getBlockEntity call path that may surface.
        BakeLightPass.CachedChunk entry = entryFor(pos.getX() >> 4, pos.getZ() >> 4);
        if (entry == null) return null;
        return entry.blockEntityAt(this, pos);
    }

    // ---- LevelWriter ----------------------------------------------------

    @Override
    public boolean setBlock(final BlockPos pos, final BlockState state, final int flags, final int recursionLimit) {
        BakeLightPass.CachedChunk entry = entryFor(pos.getX() >> 4, pos.getZ() >> 4);
        if (entry != null && entry.owned) {
            LevelChunkSection section = entry.sectionAt(this.level, pos.getY());
            if (section == null) return false;
            section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state, false);
            entry.dirty = true;
            // BE index is intentionally NOT invalidated here. Vanilla's
            // {@code UpgradeData.CHEST.updateShape} flips ChestType then calls
            // {@code Block.updateOrDestroy} which routes back through us; the
            // resulting setBlock on the source-chest position must not drop the
            // in-index ChestBlockEntity that holds the post-swap contents. The
            // bake walk does not change block class for any position that
            // carries a BE, so the in-index BE remains a valid representation
            // of the (now mutated) entity state.
            return true;
        }
        // Non-owned cache entry, or outside the 3x3 working set entirely.
        // Journal the write; the tail pass replays it after every worker has
        // released its region buffers.
        try {
            this.journal.appendBlockState(this.dim, pos, state);
            this.crossRegionWritesJournaled.incrementAndGet();
        } catch (java.io.IOException ex) {
            if (!this.loggedJournalIoFailure) {
                LOGGER.error("[The Archive] BakeLevelAccessor.setBlock journal IO failed at {} dim={}; progress retained for retry (further occurrences on this accessor will log at DEBUG)", pos, this.dim, ex);
                this.loggedJournalIoFailure = true;
            } else {
                LOGGER.debug("[The Archive] BakeLevelAccessor.setBlock journal IO failed at {} dim={}", pos, this.dim, ex);
            }
            this.crossRegionWritesIoFailed.incrementAndGet();
        }
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
        return BLOCK_TICKS;
    }

    @Override
    public LevelTickAccess<net.minecraft.world.level.material.Fluid> getFluidTicks() {
        return FLUID_TICKS;
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
        // The counter surfaces any such surprise call path in the pass-end
        // summary so silent wrong-shape state cannot accumulate undetected.
        this.getChunkCalls.incrementAndGet();
        return null;
    }

    @Override
    public @Nullable ChunkAccess getChunkIfLoadedImmediately(final int chunkX, final int chunkZ) {
        this.getChunkCalls.incrementAndGet();
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
