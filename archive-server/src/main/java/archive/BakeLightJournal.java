package archive;

import com.mojang.logging.LogUtils;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.TickPriority;
import org.slf4j.Logger;

/**
 * Per-worker append-only binary journal for bake-light cross-region writes.
 * One file per worker thread, lazily created at <world-root>/
 * {@value #FILE_PREFIX}{@code <workerId>.bin}. Records are appended verbatim
 * during the bake walk; the journal-replay tail pass drains every worker's
 * journal after all regions are done, then deletes the files.
 *
 * <p>Emits four discriminants: 1 (packed BlockState id, written
 * by LEAVES BFS, side-strip, and CHEST property changes that land outside
 * the worker's owned region), 2 (BlockEntity NBT, written by CHEST
 * {@code swapContents} on cross-region pairs), 3 (block tick), and 4
 * (fluid tick). The record format includes an explicit payload length so
 * unknown discriminants can be skipped without breaking the parse loop.
 *
 * <h2>Record layout</h2>
 * <pre>
 *   short  dim id length (UTF-8 byte count)
 *   bytes  dim id UTF-8
 *   int    target chunk x
 *   int    target chunk z
 *   int    target block x
 *   int    target block y
 *   int    target block z
 *   byte   discriminant
 *   int    payload byte length
 *   bytes  payload
 * </pre>
 *
 * <h3>Payload by discriminant</h3>
 * <ul>
 *   <li>{@link #DISCRIMINANT_BLOCK_STATE}: int packed block-state id, encoded
 *       via {@link Block#BLOCK_STATE_REGISTRY} {@code .getId}. Per-run only;
 *       valid as long as the BlockState registry layout does not shuffle
 *       between writer and reader, which holds inside a single bake run.</li>
 *   <li>{@link #DISCRIMINANT_BLOCK_ENTITY_NBT}: raw NBT bytes from
 *       {@link NbtIo#write}; the embedded x/y/z fields carry the target
 *       block position so the tail pass can index by it without consulting
 *       the record header.</li>
 *   <li>{@link #DISCRIMINANT_BLOCK_TICK}: short type-name length, UTF-8 type name,
 *       int delay, byte priority ordinal.</li>
 *   <li>{@link #DISCRIMINANT_FLUID_TICK}: same shape as block tick.</li>
 * </ul>
 *
 * <p>Sentinel resolution (vanilla maps {@code Blocks.AIR}/{@code Fluids.EMPTY}
 * to the target's runtime block/fluid at {@code tick.pos()}) is performed by
 * the tail pass against the loaded target chunk, never inside the journal.
 * The journal stores the type name verbatim, sentinel included.
 *
 * <p>Buffered through a {@link BufferedOutputStream}; {@link #fsync()} flushes
 * the stream and calls {@link FileOutputStream#getFD()}{@code .sync()} so
 * the region-finalize fsync barrier holds across SIGKILL. No locking;
 * each worker writes to its own file.
 */
final class BakeLightJournal implements AutoCloseable {
    static final byte DISCRIMINANT_BLOCK_STATE = 1;
    static final byte DISCRIMINANT_BLOCK_ENTITY_NBT = 2;
    static final byte DISCRIMINANT_BLOCK_TICK = 3;
    static final byte DISCRIMINANT_FLUID_TICK = 4;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_PREFIX = ".archive-bakelight-journal-";
    private static final String FILE_SUFFIX = ".bin";

    private final Path path;
    private FileOutputStream rawOut;
    private DataOutputStream out;
    private long recordsWritten;

    private BakeLightJournal(final Path path) {
        this.path = path;
    }

    /**
     * Lazily open the underlying file. The first {@link #append} call
     * triggers creation; workers that never write a cross-region tick leave
     * no journal file on disk and the tail pass treats the absence as
     * "no records for this worker".
     */
    private void ensureOpen() throws IOException {
        if (out != null) return;
        Files.createDirectories(path.getParent());
        rawOut = new FileOutputStream(path.toFile(), true);
        out = new DataOutputStream(new BufferedOutputStream(rawOut, 64 * 1024));
    }

    /**
     * Append a single block-state write targeted at a cross-region block
     * position. The encoded payload is one int: the packed BlockState id from
     * {@link Block#BLOCK_STATE_REGISTRY}. The tail pass decodes via
     * {@code BLOCK_STATE_REGISTRY.byId} on the same JVM run.
     */
    void appendBlockState(final Identifier dim, final BlockPos pos, final BlockState state) throws IOException {
        appendHeader(dim, pos);
        int packed = Block.BLOCK_STATE_REGISTRY.getId(state);
        out.writeByte(DISCRIMINANT_BLOCK_STATE);
        out.writeInt(4);
        out.writeInt(packed);
        recordsWritten++;
    }

    /**
     * Append a single BlockEntity NBT write targeted at a cross-region block
     * position. The payload is the byte image of the BE's
     * {@link net.minecraft.world.level.block.entity.BlockEntity#saveWithFullMetadata}
     * tag via {@link NbtIo#write}; the embedded x/y/z fields fully
     * disambiguate the destination, but we still write the header so the
     * tail pass can group records by chunk without parsing the payload.
     */
    void appendBlockEntity(final Identifier dim, final BlockPos pos, final CompoundTag nbt) throws IOException {
        appendHeader(dim, pos);
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        try (DataOutputStream dos = new DataOutputStream(buf)) {
            NbtIo.write(nbt, dos);
        }
        byte[] bytes = buf.toByteArray();
        out.writeByte(DISCRIMINANT_BLOCK_ENTITY_NBT);
        out.writeInt(bytes.length);
        out.write(bytes);
        recordsWritten++;
    }

    /** Append a single block-tick record. */
    void appendBlockTick(final Identifier dim, final SavedTick<?> tick) throws IOException {
        BlockPos pos = tick.pos();
        appendHeader(dim, pos);
        writePayload(DISCRIMINANT_BLOCK_TICK, tick);
    }

    /** Append a single fluid-tick record. */
    void appendFluidTick(final Identifier dim, final SavedTick<?> tick) throws IOException {
        BlockPos pos = tick.pos();
        appendHeader(dim, pos);
        writePayload(DISCRIMINANT_FLUID_TICK, tick);
    }

    private void appendHeader(final Identifier dim, final BlockPos pos) throws IOException {
        ensureOpen();
        byte[] dimBytes = dim.toString().getBytes(StandardCharsets.UTF_8);
        out.writeShort(dimBytes.length);
        out.write(dimBytes);
        out.writeInt(pos.getX() >> 4);
        out.writeInt(pos.getZ() >> 4);
        out.writeInt(pos.getX());
        out.writeInt(pos.getY());
        out.writeInt(pos.getZ());
    }

    private void writePayload(final byte discriminant, final SavedTick<?> tick) throws IOException {
        // Type registry name is the only payload field we cannot derive from the
        // record header. Vanilla Block / Fluid implementations identity-hash on
        // their registry instance, so the tail pass round-trips via the same
        // registries.
        String typeName = typeRegistryName(tick.type());
        byte[] typeBytes = typeName.getBytes(StandardCharsets.UTF_8);
        // payload length = 2 (typeName length) + typeBytes + 4 (delay) + 1 (priority)
        int payloadLength = 2 + typeBytes.length + 4 + 1;
        out.writeByte(discriminant);
        out.writeInt(payloadLength);
        out.writeShort(typeBytes.length);
        out.write(typeBytes);
        out.writeInt(tick.delay());
        out.writeByte(tick.priority().ordinal());
        recordsWritten++;
    }

    private static String typeRegistryName(final Object type) {
        if (type instanceof net.minecraft.world.level.block.Block block) {
            return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
        }
        if (type instanceof net.minecraft.world.level.material.Fluid fluid) {
            return net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid).toString();
        }
        throw new IllegalArgumentException("Unsupported SavedTick type: " + type);
    }

    /** Flush buffered bytes through the OS to stable storage. */
    void fsync() throws IOException {
        if (out == null) return;
        out.flush();
        rawOut.getFD().sync();
    }

    @Override
    public void close() throws IOException {
        if (out == null) return;
        out.close();
        out = null;
        rawOut = null;
    }

    long recordsWritten() {
        return recordsWritten;
    }

    Path path() {
        return path;
    }

    /**
     * Lazy per-thread journal registry. Holds one {@link BakeLightJournal} per
     * worker thread name; the bake walk looks up its journal via
     * {@link #forCurrentThread} on the worker thread. The registry is owned by
     * the pass runner and is iterated by the tail pass to drain every worker.
     */
    static final class JournalRegistry {
        private final Path worldRoot;
        private final ConcurrentHashMap<String, BakeLightJournal> journals = new ConcurrentHashMap<>();

        JournalRegistry(final Path worldRoot) {
            this.worldRoot = worldRoot;
        }

        BakeLightJournal forCurrentThread() {
            String workerId = workerIdFromThreadName(Thread.currentThread().getName());
            return journals.computeIfAbsent(workerId, id -> new BakeLightJournal(
                worldRoot.resolve(FILE_PREFIX + id + FILE_SUFFIX)));
        }

        List<BakeLightJournal> all() {
            return new ArrayList<>(journals.values());
        }

        long totalRecords() {
            long total = 0;
            for (BakeLightJournal j : journals.values()) total += j.recordsWritten;
            return total;
        }

        /** Close every open journal. Errors logged and swallowed; the file
         * stays on disk and the tail pass picks it up by directory scan. */
        void closeAll() {
            for (BakeLightJournal j : journals.values()) {
                try {
                    j.close();
                } catch (IOException ex) {
                    LOGGER.warn("[The Archive] bake-light journal close failed: {}", ex.getMessage());
                }
            }
        }

        /**
         * Discover any journal files left on disk from a prior interrupted run.
         * A SIGKILL between region-force and progress-append leaves a journal
         * with valid records but no in-memory registry entry; the tail pass
         * picks them up from the directory.
         */
        static List<Path> discoverFiles(final Path worldRoot) {
            List<Path> out = new ArrayList<>();
            if (!Files.isDirectory(worldRoot)) return out;
            try (var stream = Files.newDirectoryStream(worldRoot, FILE_PREFIX + "*" + FILE_SUFFIX)) {
                for (Path p : stream) out.add(p);
            } catch (IOException ex) {
                LOGGER.warn("[The Archive] bake-light journal discovery failed: {}", ex.getMessage());
            }
            return out;
        }

        private static String workerIdFromThreadName(final String name) {
            // Worker names follow ThreadFactoryBuilder "archive-bakelight-%d";
            // strip the prefix so the on-disk file is .archive-bakelight-journal-0.bin
            // rather than .archive-bakelight-journal-archive-bakelight-0.bin.
            String prefix = "archive-bakelight-";
            if (name.startsWith(prefix)) return name.substring(prefix.length());
            // Tail-pass writers run on the main thread; should not normally
            // append, but if they do, the name is preserved verbatim with
            // unsafe-path characters stripped.
            return name.replaceAll("[^A-Za-z0-9_.-]", "_");
        }
    }

    /**
     * Reader for the tail pass. Decodes records into {@link Record} instances
     * in memory; the volume is bounded by perimeter geometry (single-digit MB
     * per worker), so a full in-memory read is fine.
     * Unknown discriminants are skipped via the explicit payload-length field.
     */
    static List<Record> read(final Path file) throws IOException {
        List<Record> records = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new FileInputStream(file.toFile()))) {
            while (true) {
                int dimLen;
                try {
                    dimLen = in.readUnsignedShort();
                } catch (EOFException ignored) {
                    return records;
                }
                byte[] dimBytes = in.readNBytes(dimLen);
                if (dimBytes.length != dimLen) {
                    LOGGER.warn("[The Archive] bake-light journal {} truncated at dim id", file);
                    return records;
                }
                Identifier dim = Identifier.parse(new String(dimBytes, StandardCharsets.UTF_8));
                int targetChunkX = in.readInt();
                int targetChunkZ = in.readInt();
                int bx = in.readInt();
                int by = in.readInt();
                int bz = in.readInt();
                byte discriminant = in.readByte();
                int payloadLength = in.readInt();
                byte[] payload = in.readNBytes(payloadLength);
                if (payload.length != payloadLength) {
                    LOGGER.warn("[The Archive] bake-light journal {} truncated payload at offset", file);
                    return records;
                }
                records.add(new Record(dim, targetChunkX, targetChunkZ, new BlockPos(bx, by, bz), discriminant, payload));
            }
        }
    }

    /** Decode a packed-block-state payload back into a {@link BlockState}. */
    static @org.jspecify.annotations.Nullable BlockState decodeBlockState(final Record record) {
        if (record.discriminant != DISCRIMINANT_BLOCK_STATE) {
            throw new IllegalArgumentException("Not a block-state discriminant: " + record.discriminant);
        }
        if (record.payload.length != 4) {
            throw new IllegalStateException("Block-state payload must be 4 bytes, got " + record.payload.length);
        }
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(record.payload))) {
            int packed = in.readInt();
            return Block.BLOCK_STATE_REGISTRY.byId(packed);
        } catch (IOException ex) {
            throw new IllegalStateException("malformed block-state payload", ex);
        }
    }

    /** Decode a BlockEntity NBT payload via {@link NbtIo#read}. */
    static CompoundTag decodeBlockEntity(final Record record) {
        if (record.discriminant != DISCRIMINANT_BLOCK_ENTITY_NBT) {
            throw new IllegalArgumentException("Not a block-entity discriminant: " + record.discriminant);
        }
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(record.payload))) {
            return NbtIo.read(in);
        } catch (IOException ex) {
            throw new IllegalStateException("malformed block-entity payload", ex);
        }
    }

    /** Decode a tick payload into {@link DecodedTick}. */
    static DecodedTick decodeTick(final Record record) {
        if (record.discriminant != DISCRIMINANT_BLOCK_TICK && record.discriminant != DISCRIMINANT_FLUID_TICK) {
            throw new IllegalArgumentException("Not a tick discriminant: " + record.discriminant);
        }
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(record.payload))) {
            int typeNameLen = in.readUnsignedShort();
            byte[] typeBytes = in.readNBytes(typeNameLen);
            String typeName = new String(typeBytes, StandardCharsets.UTF_8);
            int delay = in.readInt();
            TickPriority priority = TickPriority.values()[in.readByte()];
            return new DecodedTick(typeName, record.blockPos, delay, priority);
        } catch (IOException ex) {
            throw new IllegalStateException("malformed tick payload", ex);
        }
    }

    /** Raw journal record; payload bytes are decoded by discriminant-aware callers. */
    record Record(Identifier dim, int targetChunkX, int targetChunkZ, BlockPos blockPos, byte discriminant, byte[] payload) {}

    /** Decoded tick payload; type name retains the sentinel verbatim for the tail pass to resolve. */
    record DecodedTick(String typeName, BlockPos pos, int delay, TickPriority priority) {}
}
