package archive;

import net.minecraft.server.dedicated.DedicatedServer;

public class ArchiveSettings {
    private static final boolean disableSavingOption = DedicatedServer.getServer().options.has("archiveDisableSaving");
    private static final boolean forceUpgradeOption = DedicatedServer.getServer().options.has("forceUpgrade");

    public static boolean disableSaving() {
        // optional override to force disabling saving
        if (disableSavingOption) {
            return true;
        }
        // enable saving if forceUpgrade is set
        if (forceUpgradeOption) {
            return false;
        }
        // default to disabling saving
        return true;
    }
}
