package archive;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@AllFeatures
public class UuidDecoderTest {

    @Test
    void missingUuidTagIsAbsent() {
        UuidDecoder.UuidResult result = UuidDecoder.decodeUuid(new CompoundTag());
        assertEquals(UuidDecoder.UuidStatus.ABSENT, result.status());
        assertNull(result.uuid());
    }

    @Test
    void fourIntArrayParses() {
        CompoundTag entity = new CompoundTag();
        entity.putIntArray("UUID", new int[]{1, 2, 3, 4});
        UuidDecoder.UuidResult result = UuidDecoder.decodeUuid(entity);
        assertEquals(UuidDecoder.UuidStatus.PRESENT_PARSED, result.status());
        // UUIDUtil packs (a,b,c,d) as msb=(a<<32)|b, lsb=(c<<32)|d.
        assertEquals(new UUID(0x0000000100000002L, 0x0000000300000004L), result.uuid());
    }

    @Test
    void wrongLengthIntArrayIsMalformed() {
        CompoundTag entity = new CompoundTag();
        entity.putIntArray("UUID", new int[]{1, 2, 3});
        UuidDecoder.UuidResult result = UuidDecoder.decodeUuid(entity);
        assertEquals(UuidDecoder.UuidStatus.PRESENT_MALFORMED, result.status());
        assertNull(result.uuid());
    }

    @Test
    void wrongTagTypeIsMalformed() {
        CompoundTag entity = new CompoundTag();
        entity.putString("UUID", "069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assertEquals(UuidDecoder.UuidStatus.PRESENT_MALFORMED, UuidDecoder.decodeUuid(entity).status());
    }
}
