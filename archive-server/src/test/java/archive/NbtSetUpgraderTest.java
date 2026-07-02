package archive;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AllFeatures
public class NbtSetUpgraderTest {

    private static OptionSet parse(String... args) {
        OptionParser parser = new OptionParser();
        parser.accepts("upgradeNbt");
        parser.accepts("cleanDirtyChunks");
        parser.accepts("archiveNbtType").withRequiredArg();
        parser.accepts("archiveNbtInputDir").withRequiredArg();
        parser.accepts("archiveNbtOutputDir").withRequiredArg();
        parser.accepts("archiveNbtFromVersion").withRequiredArg().ofType(Integer.class);
        parser.accepts("archiveNbtToVersion").withRequiredArg().ofType(Integer.class);
        return parser.parse(args);
    }

    /** Install options and clear the sticky pass-failed flag between cases. */
    private static void install(OptionSet options) throws Exception {
        ArchiveSettings.captureOptions(options);
        setStatic("passFailed", false);
    }

    private static void setStatic(String fieldName, Object value) throws Exception {
        Field field = ArchiveSettings.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    @AfterAll
    static void restoreArchiveSettings() throws Exception {
        setStatic("capturedOptions", null);
        setStatic("passFailed", false);
    }

    private static void runWith(String... args) throws Exception {
        install(parse(args));
        NbtSetUpgrader.run(null);
    }

    @Test
    void rejectsCombinationWithWorldContentPass(@TempDir Path in, @TempDir Path out) throws Exception {
        runWith("--upgradeNbt", "--cleanDirtyChunks",
            "--archiveNbtType=TILE_ENTITY",
            "--archiveNbtInputDir=" + in, "--archiveNbtOutputDir=" + out,
            "--archiveNbtFromVersion=3700");
        assertTrue(ArchiveSettings.passFailed());
    }

    @Test
    void rejectsUnknownType(@TempDir Path in, @TempDir Path out) throws Exception {
        runWith("--upgradeNbt", "--archiveNbtType=ENTITY",
            "--archiveNbtInputDir=" + in, "--archiveNbtOutputDir=" + out,
            "--archiveNbtFromVersion=3700");
        assertTrue(ArchiveSettings.passFailed());
    }

    @Test
    void rejectsMissingDirs() throws Exception {
        runWith("--upgradeNbt", "--archiveNbtType=TILE_ENTITY", "--archiveNbtFromVersion=3700");
        assertTrue(ArchiveSettings.passFailed());
    }

    @Test
    void rejectsSameInputAndOutputDir(@TempDir Path dir) throws Exception {
        runWith("--upgradeNbt", "--archiveNbtType=TILE_ENTITY",
            "--archiveNbtInputDir=" + dir, "--archiveNbtOutputDir=" + dir,
            "--archiveNbtFromVersion=3700");
        assertTrue(ArchiveSettings.passFailed());
    }

    @Test
    void rejectsMissingFromVersion(@TempDir Path in, @TempDir Path out) throws Exception {
        runWith("--upgradeNbt", "--archiveNbtType=TILE_ENTITY",
            "--archiveNbtInputDir=" + in, "--archiveNbtOutputDir=" + out);
        assertTrue(ArchiveSettings.passFailed());
    }

    @Test
    void rejectsDowngradeVersionOrder(@TempDir Path in, @TempDir Path out) throws Exception {
        runWith("--upgradeNbt", "--archiveNbtType=TILE_ENTITY",
            "--archiveNbtInputDir=" + in, "--archiveNbtOutputDir=" + out,
            "--archiveNbtFromVersion=4189", "--archiveNbtToVersion=3700");
        assertTrue(ArchiveSettings.passFailed());
    }

    @Test
    void emptyIdCompoundFailsTheFile(@TempDir Path in, @TempDir Path out) throws Exception {
        CompoundTag noId = new CompoundTag();
        noId.putInt("x", 1);
        NbtIo.write(noId, in.resolve("no-id.nbt"));
        int current = SharedConstants.WORLD_VERSION;
        runWith("--upgradeNbt", "--archiveNbtType=TILE_ENTITY",
            "--archiveNbtInputDir=" + in, "--archiveNbtOutputDir=" + out,
            "--archiveNbtFromVersion=" + current, "--archiveNbtToVersion=" + current);
        assertTrue(ArchiveSettings.passFailed());
        assertFalse(Files.exists(out.resolve("no-id.nbt")));
    }

    @Test
    void identityVersionRunWritesOutputAndStaysClean(@TempDir Path in, @TempDir Path out) throws Exception {
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putInt("x", 8);
        chest.putInt("y", 64);
        chest.putInt("z", -24);
        NbtIo.write(chest, in.resolve("chest.nbt"));
        int current = SharedConstants.WORLD_VERSION;
        runWith("--upgradeNbt", "--archiveNbtType=TILE_ENTITY",
            "--archiveNbtInputDir=" + in, "--archiveNbtOutputDir=" + out,
            "--archiveNbtFromVersion=" + current, "--archiveNbtToVersion=" + current);
        assertFalse(ArchiveSettings.passFailed());
        assertEquals(chest, NbtIo.read(out.resolve("chest.nbt")));
    }
}
