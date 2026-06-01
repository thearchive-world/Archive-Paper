package archive;

import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.Tag;

/**
 * Three-state decode of a {@code UUID} tag on an entity-like compound:
 * absent, present-and-parseable, or present-and-malformed. Returned via
 * {@link UuidResult} so callers can distinguish "no UUID at all" (vanilla
 * assigns at load time, do nothing) from "UUID is present but unreadable"
 * (a dirt class worth counting but not stripping).
 *
 * <p>Shared by {@link DirtNbtCleaner} (inter-chunk dedup + passenger
 * recursion, 5 call sites) and {@link AuditDirtyChunks} (per-UUID
 * occurrence counter for the {@code uuid-dups} total). Co-locating the
 * decode keeps the cleaner's dedup definition in lockstep with the
 * audit's counter definition.
 */
final class UuidDecoder {
    private UuidDecoder() {}

    enum UuidStatus { ABSENT, PRESENT_PARSED, PRESENT_MALFORMED }

    record UuidResult(UuidStatus status, UUID uuid) {
        static final UuidResult ABSENT = new UuidResult(UuidStatus.ABSENT, null);
        static final UuidResult MALFORMED = new UuidResult(UuidStatus.PRESENT_MALFORMED, null);
        static UuidResult parsed(UUID uuid) {
            return new UuidResult(UuidStatus.PRESENT_PARSED, uuid);
        }
    }

    static UuidResult decodeUuid(CompoundTag entity) {
        Tag tag = entity.get("UUID");
        if (tag == null) return UuidResult.ABSENT;
        if (!(tag instanceof IntArrayTag intArrayTag)) return UuidResult.MALFORMED;
        int[] array = intArrayTag.getAsIntArray();
        if (array.length != 4) return UuidResult.MALFORMED;
        return UuidResult.parsed(UUIDUtil.uuidFromIntArray(array));
    }
}
