package archive;

import net.minecraft.server.MinecraftServer;

/**
 * Facade entry point for the chunk upgrade phase. Delegates to
 * {@link DirectNbtUpgrader} (parallel direct-NBT DFU).
 */
public final class ChunkUpgrader {
    private ChunkUpgrader() {}

    public static void run(MinecraftServer server) {
        DirectNbtUpgrader.run(server);
    }
}
