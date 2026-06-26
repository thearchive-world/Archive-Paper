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
    private static volatile boolean upgradeChunksComplete;
    private static volatile boolean preserveChunkTimestamps;

    public static void captureOptions(OptionSet options) {
        capturedOptions = options;
        preserveChunkTimestamps = options.has("preserveChunkTimestamps")
            && (options.valueOf("preserveChunkTimestamps") instanceof Boolean b ? b : true);
        warnIfWedgeCombo(options);
        warnIfUpgradeChunksWedge(options);
        warnIfForceUpgradeAndUpgradeChunks(options);
        warnIfPreserveTimestampsWithoutWritePass(options);
        warnIfMultiplePostSpinPassesSelected(options);
    }

    private static void warnIfWedgeCombo(OptionSet options) {
        if (!options.has("archiveDisableSaving")) return;
        if (!archiveDisableSavingValue(options)) return;
        boolean force = options.has("forceUpgrade");
        boolean recreate = options.has("recreateRegionFiles");
        if (!force && !recreate) return;
        String flag = force && recreate ? "--forceUpgrade and --recreateRegionFiles"
                : force ? "--forceUpgrade" : "--recreateRegionFiles";
        LOGGER.error("[The Archive] --archiveDisableSaving=true combined with {} will silently no-op every upgrade write. On a pre-26.1 world this wedges WorldFolderMigration in a retry loop. Drop --archiveDisableSaving (or set it to false) to actually persist the upgrade.", flag);
    }

    private static void warnIfUpgradeChunksWedge(OptionSet options) {
        if (!options.has("archiveDisableSaving")) return;
        if (!archiveDisableSavingValue(options)) return;
        if (!options.has("upgradeChunks")) return;
        if (!upgradeChunksValue(options)) return;
        LOGGER.error("[The Archive] --archiveDisableSaving=true combined with --upgradeChunks will silently no-op every write the upgrade pass issues, leaving entities/*.mca and poi/*.mca empty. Drop --archiveDisableSaving (or set it to false) to actually persist the chunk upgrade.");
    }

    private static void warnIfForceUpgradeAndUpgradeChunks(OptionSet options) {
        if (!options.has("upgradeChunks")) return;
        if (!upgradeChunksValue(options)) return;
        if (!options.has("forceUpgrade") && !options.has("recreateRegionFiles")) return;
        LOGGER.info("[The Archive] --forceUpgrade/--recreateRegionFiles and --upgradeChunks are both set. Both DFU the same files; --forceUpgrade runs pre-spin and --upgradeChunks then no-ops on already-current chunks via its skip-if-current pre-flight. Either flag is sufficient on its own.");
    }

    private static void warnIfPreserveTimestampsWithoutWritePass(OptionSet options) {
        if (!preserveChunkTimestamps) return;
        if (options.has("upgradeChunks") || options.has("cleanDirtyChunks") || options.has("bakeLight")) return;
        LOGGER.warn("[The Archive] --preserveChunkTimestamps without --upgradeChunks/--cleanDirtyChunks/--bakeLight will freeze region-file slot timestamps on every chunk write for the JVM lifetime. Only set this flag on archive-pipeline invocations.");
    }

    private static void warnIfMultiplePostSpinPassesSelected(OptionSet options) {
        java.util.ArrayList<String> selected = new java.util.ArrayList<>(4);
        if (upgradeChunksRequested()) selected.add("--upgradeChunks");
        if (cleanDirtyChunks()) selected.add("--cleanDirtyChunks");
        if (bakeLight()) selected.add("--bakeLight");
        if (auditDirtyChunks()) selected.add("--auditDirtyChunks");
        if (selected.size() < 2) return;
        LOGGER.warn("[The Archive] Multiple post-spin pipeline flags requested: {}. The dispatcher runs at most one pass per invocation in order upgrade -> clean -> bake -> audit. This run will execute {}; re-invoke with the remaining flag(s) after it completes.",
                String.join(", ", selected), selected.get(0));
    }

    private static boolean archiveDisableSavingValue(OptionSet o) {
        return o.valueOf("archiveDisableSaving") instanceof Boolean b ? b : true;
    }

    private static boolean upgradeChunksValue(OptionSet o) {
        return o.valueOf("upgradeChunks") instanceof Boolean b ? b : true;
    }

    /**
     * Re-log the disable-saving wedge at ERROR when a post-spin pass starts so the
     * symptom (no writes, "successful" exit, wedged retry next boot) surfaces
     * alongside the pass progress instead of buried in earlier boot INFO.
     */
    public static void warnIfDisableSavingAtPassEntry(String passLabel) {
        if (!disableSaving()) return;
        LOGGER.error("[The Archive] {} starting with --archiveDisableSaving=true: every write this pass issues will be silently dropped. Drop --archiveDisableSaving (or set it to false) to actually persist the pass output.", passLabel);
    }

    public static void markUpgradeComplete() {
        upgradeComplete = true;
    }

    public static boolean upgradeComplete() {
        return upgradeComplete;
    }

    public static boolean upgradeChunksRequested() {
        OptionSet o = options();
        if (o == null) return false;
        if (!o.has("upgradeChunks")) return false;
        return upgradeChunksValue(o);
    }

    public static boolean upgradeChunksComplete() {
        return upgradeChunksComplete;
    }

    public static void markUpgradeChunksComplete() {
        upgradeChunksComplete = true;
    }

    private static OptionSet options() {
        OptionSet o = capturedOptions;
        if (o != null) return o;
        MinecraftServer s = MinecraftServer.getServer();
        return s instanceof DedicatedServer ds ? ds.options : null;
    }

    public static int upgradeWorkerCount() {
        OptionSet o = options();
        if (o == null || !o.has("upgradeWorkerCount")) {
            return Math.min(Runtime.getRuntime().availableProcessors(), 16);
        }
        Object v = o.valueOf("upgradeWorkerCount");
        if (v instanceof Integer i && i > 0) {
            int cap = Runtime.getRuntime().availableProcessors() * 4;
            if (i > cap) {
                LOGGER.warn("[The Archive] --upgradeWorkerCount={} exceeds the cap (availableProcessors * 4 = {}); clamping to {}.", i, cap, cap);
                return cap;
            }
            return i;
        }
        return Math.min(Runtime.getRuntime().availableProcessors(), 16);
    }

    public static boolean splitEntities() {
        OptionSet o = options();
        if (o == null || !o.has("splitEntities")) return false;
        Object v = o.valueOf("splitEntities");
        return v instanceof Boolean b ? b : true;
    }

    public static boolean auditDirtyChunks() {
        OptionSet o = options();
        if (o == null || !o.has("auditDirtyChunks")) return false;
        Object v = o.valueOf("auditDirtyChunks");
        return v instanceof Boolean b ? b : true;
    }

    public static boolean cleanDirtyChunks() {
        OptionSet o = options();
        if (o == null || !o.has("cleanDirtyChunks")) return false;
        Object v = o.valueOf("cleanDirtyChunks");
        return v instanceof Boolean b ? b : true;
    }

    public static boolean bakeLight() {
        OptionSet o = options();
        if (o == null || !o.has("bakeLight")) return false;
        Object v = o.valueOf("bakeLight");
        return v instanceof Boolean b ? b : true;
    }

    /**
     * True when this invocation requested any post-spin batch pass
     * (--upgradeChunks / --cleanDirtyChunks / --bakeLight / --auditDirtyChunks).
     * Read at initServer time (options are captured pre-spin) to run those passes
     * headless: the network listener is skipped so a batch run never depends on a
     * free server-port. Without that, a port collision aborts initServer after the
     * pre-spin world-folder migration but before the post-spin pass, leaving a
     * migrated-but-not-upgraded world.
     */
    public static boolean anyPostSpinPass() {
        return upgradeChunksRequested() || cleanDirtyChunks() || bakeLight() || auditDirtyChunks();
    }

    public static boolean preserveChunkTimestamps() {
        return preserveChunkTimestamps;
    }

    public static boolean disableSaving() {
        OptionSet o = options();
        if (o == null) return true; // pre-bootstrap safe default

        boolean explicitlySet = o.has("archiveDisableSaving");
        boolean configuredValue = archiveDisableSavingValue(o);

        // forceUpgrade and recreateRegionFiles both trigger the vanilla pre-spin
        // upgrade (vanilla guard tests options.has("forceUpgrade") || recreateRegionFilesValue).
        // Mirror that here so the upgrade actually persists. The override
        // applies only when the user didn't explicitly set archiveDisableSaving,
        // and only during the upgrade phase (cleared by markUpgradeComplete).
        // The chunk upgrade is a separate post-spin phase that also needs saving
        // enabled while it runs; cleared by markUpgradeChunksComplete.
        if (!explicitlySet && (
                (o.has("forceUpgrade") || o.has("recreateRegionFiles")) && !upgradeComplete
             || upgradeChunksRequested() && !upgradeChunksComplete
           )) {
            return false;
        }
        return configuredValue;
    }
}
