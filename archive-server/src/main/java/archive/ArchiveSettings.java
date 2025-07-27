package archive;

import net.minecraft.server.dedicated.DedicatedServer;

public class ArchiveSettings {
    private static final boolean disableSavingOptionSet = DedicatedServer.getServer().options.has("archiveDisableSaving");
    private static final boolean disableSavingOption = (boolean) net.minecraft.server.dedicated.DedicatedServer.getServer().options.valueOf("archiveDisableSaving");
    private static final boolean forceUpgradeOption = DedicatedServer.getServer().options.has("forceUpgrade");

    public static boolean disableSaving() {
        // use set value if option is set
        if (disableSavingOptionSet) {
            return disableSavingOption;
        }
        // enable saving if forceUpgrade is set
        if (forceUpgradeOption) {
            return false;
        }
        // otherwise fallback to default
        return disableSavingOption;
    }
}
