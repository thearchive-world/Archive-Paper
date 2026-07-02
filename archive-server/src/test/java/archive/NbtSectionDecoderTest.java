package archive;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@AllFeatures
public class NbtSectionDecoderTest {

    private static CompoundTag section(List<String> palette, long[] data) {
        ListTag pal = new ListTag();
        for (String name : palette) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", name);
            pal.add(entry);
        }
        CompoundTag blockStates = new CompoundTag();
        blockStates.put("palette", pal);
        if (data != null) blockStates.putLongArray("data", data);
        CompoundTag section = new CompoundTag();
        section.put("block_states", blockStates);
        return section;
    }

    @Test
    void missingBlockStatesOrEmptyPaletteYieldsNull() {
        assertNull(NbtSectionDecoder.from(new CompoundTag()));
        assertNull(NbtSectionDecoder.from(section(List.of(), null)));
    }

    @Test
    void singleEntryPaletteOmitsDataAndAnswersEverywhere() {
        NbtSectionDecoder decoder = NbtSectionDecoder.from(section(List.of("minecraft:stone"), null));
        assertEquals(0, decoder.bitsPerEntry());
        assertEquals("minecraft:stone", decoder.blockNameAt(0, 0, 0));
        assertEquals("minecraft:stone", decoder.blockNameAt(15, 15, 15));
    }

    @Test
    void multiEntryPaletteWithMissingDataYieldsNullLookups() {
        NbtSectionDecoder decoder = NbtSectionDecoder.from(section(List.of("minecraft:air", "minecraft:stone"), null));
        assertEquals(4, decoder.bitsPerEntry());
        assertNull(decoder.blockNameAt(0, 0, 0));
    }

    @Test
    void fourBitUnpackingInYzxOrder() {
        // 2 entries -> 4 bits minimum, 16 indices per long.
        // long[0] nibble 0 = flat index 0 = (0,0,0); nibble 1 = flat 1 = (1,0,0).
        long[] data = new long[17];
        data[0] = 0x10L; // (0,0,0) -> palette 0, (1,0,0) -> palette 1
        // flat index for (0,1,0) is (1*16+0)*16+0 = 256 -> long 16, nibble 0.
        data[16] = 0x1L; // (0,1,0) -> palette 1
        NbtSectionDecoder decoder = NbtSectionDecoder.from(section(List.of("minecraft:air", "minecraft:stone"), data));
        assertEquals("minecraft:air", decoder.blockNameAt(0, 0, 0));
        assertEquals("minecraft:stone", decoder.blockNameAt(1, 0, 0));
        assertEquals("minecraft:air", decoder.blockNameAt(2, 0, 0));
        assertEquals("minecraft:stone", decoder.blockNameAt(0, 1, 0));
    }

    @Test
    void cornerBlockLandsInLastLong() {
        // flat(15,15,15) = 4095 -> long 255, nibble 15 (bit offset 60).
        long[] data = new long[256];
        data[255] = 1L << 60;
        NbtSectionDecoder decoder = NbtSectionDecoder.from(section(List.of("minecraft:air", "minecraft:stone"), data));
        assertEquals("minecraft:stone", decoder.blockNameAt(15, 15, 15));
        assertEquals("minecraft:air", decoder.blockNameAt(14, 15, 15));
    }

    @Test
    void seventeenEntryPaletteUsesFiveBits() {
        List<String> palette = new java.util.ArrayList<>();
        for (int i = 0; i < 17; i++) palette.add("minecraft:block_" + i);
        // 5 bits, 12 indices per long; flat 1 sits at bit offset 5 of long 0.
        long[] data = new long[342];
        data[0] = 16L << 5; // (1,0,0) -> palette index 16
        NbtSectionDecoder decoder = NbtSectionDecoder.from(section(palette, data));
        assertEquals(5, decoder.bitsPerEntry());
        assertEquals("minecraft:block_16", decoder.blockNameAt(1, 0, 0));
        assertEquals("minecraft:block_0", decoder.blockNameAt(0, 0, 0));
    }

    @Test
    void outOfRangePaletteIndexAndShortDataYieldNull() {
        long[] data = new long[1];
        data[0] = 0xFL; // (0,0,0) -> palette index 15, palette has 2 entries
        NbtSectionDecoder decoder = NbtSectionDecoder.from(section(List.of("minecraft:air", "minecraft:stone"), data));
        assertNull(decoder.blockNameAt(0, 0, 0));
        // flat(0,1,0) = 256 -> long 16, beyond data.length 1.
        assertNull(decoder.blockNameAt(0, 1, 0));
    }
}
