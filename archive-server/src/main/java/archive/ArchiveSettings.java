package archive;

import com.mojang.logging.LogUtils;
import joptsimple.OptionSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.slf4j.Logger;

public final class ArchiveSettings {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile OptionSet capturedOptions;
    private static volatile boolean upgradeComplete;

    public static void captureOptions(OptionSet options) {
        capturedOptions = options;
        warnIfWedgeCombo(options);
    }

    private static void warnIfWedgeCombo(OptionSet options) {
        if (!options.has("archiveDisableSaving")) return;
        if (!archiveDisableSavingValue(options)) return;
        boolean force = options.has("forceUpgrade");
        boolean recreate = options.has("recreateRegionFiles");
        if (!force && !recreate) return;
        String flag = force && recreate ? "--forceUpgrade and --recreateRegionFiles"
                : force ? "--forceUpgrade" : "--recreateRegionFiles";
        LOGGER.warn("[The Archive] --archiveDisableSaving=true combined with {} will silently no-op every upgrade write. On a pre-26.1 world this wedges WorldFolderMigration in a retry loop. Drop --archiveDisableSaving (or set it to false) to actually persist the upgrade.", flag);
    }

    private static boolean archiveDisableSavingValue(OptionSet o) {
        return o.valueOf("archiveDisableSaving") instanceof Boolean b ? b : true;
    }

    public static void markUpgradeComplete() {
        upgradeComplete = true;
    }

    public static boolean upgradeComplete() {
        return upgradeComplete;
    }

    private static OptionSet options() {
        OptionSet o = capturedOptions;
        if (o != null) return o;
        MinecraftServer s = MinecraftServer.getServer();
        return s instanceof DedicatedServer ds ? ds.options : null;
    }

    public static boolean disableSaving() {
        OptionSet o = options();
        if (o == null) return true; // pre-bootstrap safe default

        boolean explicitlySet = o.has("archiveDisableSaving");
        boolean configuredValue = archiveDisableSavingValue(o);

        // forceUpgrade and recreateRegionFiles both trigger the upgrade (Hunk 3
        // if-guard: options.has("forceUpgrade") || recreateRegionFilesValue).
        // Mirror that here so the upgrade actually persists. The override
        // applies only when the user didn't explicitly set archiveDisableSaving,
        // and only during the upgrade phase (cleared by markUpgradeComplete).
        if (!explicitlySet && (o.has("forceUpgrade") || o.has("recreateRegionFiles")) && !upgradeComplete) {
            return false;
        }
        return configuredValue;
    }
}
