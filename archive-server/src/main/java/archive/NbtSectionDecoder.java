package archive;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * Decodes a 1.16+ paletted-container section. Bits-per-entry is
 * {@code max(4, ceil(log2(palette.size())))}, indices are packed into the
 * {@code data} long array with no straddling: each long holds
 * {@code 64 / bitsPerEntry} indices. A palette of size 1 omits the data
 * field entirely; the implicit index is 0. Block index for local
 * (x, y, z) in [0, 16) follows {@code Strategy.BLOCK_STATES.getIndex}:
 * {@code ((y * 16) + z) * 16 + x} (YZX order).
 *
 * <p>Shared by {@link DirtNbtCleaner} (ghost-bes / be-coord-mismatch /
 * be-type-mismatch strip rules) and {@link AuditDirtyChunks} (the matching
 * dirt-class flag rules). Co-locating the decoder is the structural
 * protection against the cleaner-strip and audit-flag predicates drifting
 * out of lockstep on a future schema change.
 */
record NbtSectionDecoder(List<String> palette, long[] data, int bitsPerEntry) {
    private static final String AIR = "minecraft:air";

    static NbtSectionDecoder from(CompoundTag section) {
        CompoundTag bs = section.getCompoundOrEmpty("block_states");
        ListTag pal = bs.getListOrEmpty("palette");
        if (pal.isEmpty()) return null;
        List<String> names = new ArrayList<>(pal.size());
        for (int i = 0; i < pal.size(); i++) {
            names.add(pal.getCompoundOrEmpty(i).getStringOr("Name", AIR));
        }
        long[] data = bs.getLongArray("data").orElse(new long[0]);
        int bits = names.size() <= 1 ? 0
            : Math.max(4, 32 - Integer.numberOfLeadingZeros(names.size() - 1));
        return new NbtSectionDecoder(names, data, bits);
    }

    String blockNameAt(int x, int y, int z) {
        if (bitsPerEntry == 0) return palette.get(0);
        if (data.length == 0) return null;
        int flatIndex = (y * 16 + z) * 16 + x;
        int indicesPerLong = 64 / bitsPerEntry;
        int longIndex = flatIndex / indicesPerLong;
        if (longIndex >= data.length) return null;
        int bitOffset = (flatIndex % indicesPerLong) * bitsPerEntry;
        long mask = (1L << bitsPerEntry) - 1;
        int paletteIndex = (int) ((data[longIndex] >>> bitOffset) & mask);
        if (paletteIndex < 0 || paletteIndex >= palette.size()) return null;
        return palette.get(paletteIndex);
    }
}
