package archive;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AllFeatures
public class AuditStaleDataVersionTest {

    private static int current() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    @Test
    void chunkAtCurrentDataVersionIsNotStale() {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", current());
        assertFalse(AuditDirtyChunks.isStaleDataVersion(root));
    }

    @Test
    void chunkOneVersionBehindIsStale() {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", current() - 1);
        assertTrue(AuditDirtyChunks.isStaleDataVersion(root));
    }

    @Test
    void chunkAtAnOlderDataVersionIsStale() {
        // 4189 is the data version of the oldest --upgradeNbt fixture corpus,
        // comfortably below every currently supported world version. A world
        // sitting below the server's own version is the population a re-pipeline
        // has to find, and it is exactly what legacy-chunks misses: that
        // predicate fires only on the pre-1.13 "Level" wrapper, so a fully
        // upgraded world one version behind counts as zero everywhere else.
        //
        // Deliberately pinned to a value that is stale on every branch rather
        // than to any particular release's WORLD_VERSION. This file is shared
        // across the version branches, so pinning a branch's own current version
        // would assert that version is stale and contradict
        // chunkAtCurrentDataVersionIsNotStale.
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 4189);
        assertTrue(AuditDirtyChunks.isStaleDataVersion(root));
    }

    @Test
    void chunkWithNoDataVersionTagIsStale() {
        // Pre-DataVersion chunks read as 0, which is below current and so
        // stale. This is the same absent-tag default the upgrader uses.
        assertTrue(AuditDirtyChunks.isStaleDataVersion(new CompoundTag()));
    }
}
