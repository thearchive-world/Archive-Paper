package archive;

import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import org.jspecify.annotations.Nullable;

/**
 * Thin {@link LightChunkGetter} backed by the worker-local 3x3 cache. Wraps the
 * real {@link ServerLevel} so {@link
 * ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface} sees a
 * {@code ServerLevel} from {@link #getLevel} (installing
 * {@code ServerLightQueue}, deriving min/max section bounds from the dimension).
 * {@link #getChunkForLighting} resolves to a lazily-built
 * {@link net.minecraft.world.level.chunk.ProtoChunk} on the cache entry, so
 * Starlight's neighbour reads land on the same in-memory state the bake walk
 * already mutated.
 *
 * <p>Cache entries outside the 3x3 working set return null;
 * Starlight tolerates a null neighbour during its BFS by treating that
 * chunk's contribution as empty/opaque-at-the-boundary, the same way the
 * runtime engine handles unloaded neighbours.
 */
public final class BakeLightChunkGetter implements LightChunkGetter {
    private final ServerLevel level;
    private final Map<Long, BakeLightPass.CachedChunk> cache;

    public BakeLightChunkGetter(ServerLevel level, Map<Long, BakeLightPass.CachedChunk> cache) {
        this.level = level;
        this.cache = cache;
    }

    @Override
    public @Nullable LightChunk getChunkForLighting(final int x, final int z) {
        BakeLightPass.CachedChunk entry = cache.get(ChunkPos.pack(x, z));
        if (entry == null) return null;
        return entry.protoChunk(level);
    }

    @Override
    public BlockGetter getLevel() {
        return level;
    }
}
