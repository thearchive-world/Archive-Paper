package archive;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@AllFeatures
public class BakeLightJournalTest {

    private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");

    /** Raw wire-format writer mirroring appendHeader + append: used to craft malformed files. */
    private static void writeRecord(DataOutputStream out, String dim, int x, int y, int z,
                                    byte discriminant, byte[] payload) throws IOException {
        byte[] dimBytes = dim.getBytes(StandardCharsets.UTF_8);
        out.writeShort(dimBytes.length);
        out.write(dimBytes);
        out.writeInt(x >> 4);
        out.writeInt(z >> 4);
        out.writeInt(x);
        out.writeInt(y);
        out.writeInt(z);
        out.writeByte(discriminant);
        out.writeInt(payload.length);
        out.write(payload);
    }

    private static DataOutputStream open(Path file) throws IOException {
        OutputStream raw = Files.newOutputStream(file);
        return new DataOutputStream(raw);
    }

    @Test
    void blockStateRoundTripsThroughAppendReadDecode(@TempDir Path dir) throws IOException {
        BakeLightJournal.JournalRegistry registry = new BakeLightJournal.JournalRegistry(dir);
        BakeLightJournal journal = registry.forCurrentThread();
        BlockPos pos = new BlockPos(-33, 70, 129);
        BlockState state = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
        journal.appendBlockState(OVERWORLD, pos, state);
        registry.closeAll();

        AtomicLong malformed = new AtomicLong();
        List<BakeLightJournal.Record> records = BakeLightJournal.read(journal.path(), malformed);
        assertEquals(1, records.size());
        assertEquals(0, malformed.get());
        BakeLightJournal.Record record = records.get(0);
        assertEquals(OVERWORLD, record.dim());
        assertEquals(-3, record.targetChunkX());
        assertEquals(8, record.targetChunkZ());
        assertEquals(pos, record.blockPos());
        assertEquals(BakeLightJournal.DISCRIMINANT_BLOCK_STATE, record.discriminant());

        BakeLightJournal.BlockStateDecodeResult decoded = BakeLightJournal.decodeBlockState(record);
        // BlockStates are canonical instances; identity proves an exact round-trip.
        assertSame(state, decoded.state());
        assertEquals(0, decoded.propertiesSkipped());
    }

    @Test
    void malformedDimIdSkipsRecordAndRecoversLaterOnes(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("journal.bin");
        try (DataOutputStream out = open(file)) {
            writeRecord(out, "NOT A VALID DIM!", 0, 0, 0, (byte) 9, new byte[]{1, 2});
            writeRecord(out, "minecraft:overworld", 16, 64, 16, (byte) 9, new byte[]{3});
        }
        AtomicLong malformed = new AtomicLong();
        List<BakeLightJournal.Record> records = BakeLightJournal.read(file, malformed);
        assertEquals(1, records.size());
        assertEquals(1, malformed.get());
        assertEquals(OVERWORLD, records.get(0).dim());
    }

    @Test
    void badPayloadLengthCountsAndStopsAtCleanPrefix(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("journal.bin");
        try (DataOutputStream out = open(file)) {
            writeRecord(out, "minecraft:overworld", 0, 0, 0, (byte) 9, new byte[]{1});
            // Second record header, then a negative payload length.
            byte[] dimBytes = "minecraft:overworld".getBytes(StandardCharsets.UTF_8);
            out.writeShort(dimBytes.length);
            out.write(dimBytes);
            out.writeInt(0); out.writeInt(0);
            out.writeInt(0); out.writeInt(0); out.writeInt(0);
            out.writeByte(9);
            out.writeInt(-5);
            // A third, fully valid record that must NOT be recovered: bad
            // payload length abandons the rest of the file.
            writeRecord(out, "minecraft:overworld", 32, 0, 32, (byte) 9, new byte[]{7});
        }
        AtomicLong malformed = new AtomicLong();
        List<BakeLightJournal.Record> records = BakeLightJournal.read(file, malformed);
        assertEquals(1, records.size());
        assertEquals(1, malformed.get());
    }

    @Test
    void oversizedPayloadLengthCountsAndStops(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("journal.bin");
        try (DataOutputStream out = open(file)) {
            byte[] dimBytes = "minecraft:overworld".getBytes(StandardCharsets.UTF_8);
            out.writeShort(dimBytes.length);
            out.write(dimBytes);
            out.writeInt(0); out.writeInt(0);
            out.writeInt(0); out.writeInt(0); out.writeInt(0);
            out.writeByte(9);
            out.writeInt(16 * 1024 * 1024 + 1);
        }
        AtomicLong malformed = new AtomicLong();
        assertEquals(0, BakeLightJournal.read(file, malformed).size());
        assertEquals(1, malformed.get());
    }

    @Test
    void truncationStopsWithoutCountingMalformed(@TempDir Path dir) throws IOException {
        // Truncated dim bytes: length says 10, only 4 present.
        Path truncatedDim = dir.resolve("truncated-dim.bin");
        try (DataOutputStream out = open(truncatedDim)) {
            writeRecord(out, "minecraft:overworld", 0, 0, 0, (byte) 9, new byte[]{1});
            out.writeShort(10);
            out.write(new byte[]{1, 2, 3, 4});
        }
        AtomicLong malformed = new AtomicLong();
        assertEquals(1, BakeLightJournal.read(truncatedDim, malformed).size());
        assertEquals(0, malformed.get());

        // Truncated payload: length says 100, only 10 present.
        Path truncatedPayload = dir.resolve("truncated-payload.bin");
        try (DataOutputStream out = open(truncatedPayload)) {
            byte[] dimBytes = "minecraft:overworld".getBytes(StandardCharsets.UTF_8);
            out.writeShort(dimBytes.length);
            out.write(dimBytes);
            out.writeInt(0); out.writeInt(0);
            out.writeInt(0); out.writeInt(0); out.writeInt(0);
            out.writeByte(9);
            out.writeInt(100);
            out.write(new byte[10]);
        }
        AtomicLong malformed2 = new AtomicLong();
        assertEquals(0, BakeLightJournal.read(truncatedPayload, malformed2).size());
        assertEquals(0, malformed2.get());
    }

    private static byte[] blockStatePayload(String blockRl, String[][] properties) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(buf)) {
            byte[] rl = blockRl.getBytes(StandardCharsets.UTF_8);
            dos.writeShort(rl.length);
            dos.write(rl);
            dos.writeShort(properties.length);
            for (String[] property : properties) {
                byte[] name = property[0].getBytes(StandardCharsets.UTF_8);
                dos.writeShort(name.length);
                dos.write(name);
                byte[] value = property[1].getBytes(StandardCharsets.UTF_8);
                dos.writeShort(value.length);
                dos.write(value);
            }
        }
        return buf.toByteArray();
    }

    private static BakeLightJournal.Record blockStateRecord(byte[] payload) {
        return new BakeLightJournal.Record(OVERWORLD, 0, 0, new BlockPos(0, 0, 0),
            BakeLightJournal.DISCRIMINANT_BLOCK_STATE, payload);
    }

    @Test
    void decodeSkipsUnknownPropertyAndUnroundtrippableValue(@TempDir Path dir) throws IOException {
        BakeLightJournal.BlockStateDecodeResult unknownName = BakeLightJournal.decodeBlockState(
            blockStateRecord(blockStatePayload("minecraft:oak_stairs", new String[][]{{"no_such_property", "x"}})));
        assertSame(Blocks.OAK_STAIRS.defaultBlockState(), unknownName.state());
        assertEquals(1, unknownName.propertiesSkipped());

        BakeLightJournal.BlockStateDecodeResult badValue = BakeLightJournal.decodeBlockState(
            blockStateRecord(blockStatePayload("minecraft:oak_stairs", new String[][]{{"facing", "sideways"}})));
        assertSame(Blocks.OAK_STAIRS.defaultBlockState(), badValue.state());
        assertEquals(1, badValue.propertiesSkipped());
    }

    @Test
    void decodeUnknownBlockYieldsNullStateWithoutSkips(@TempDir Path dir) throws IOException {
        BakeLightJournal.BlockStateDecodeResult result = BakeLightJournal.decodeBlockState(
            blockStateRecord(blockStatePayload("minecraft:no_such_block", new String[0][])));
        assertNull(result.state());
        assertEquals(0, result.propertiesSkipped());
    }
}
