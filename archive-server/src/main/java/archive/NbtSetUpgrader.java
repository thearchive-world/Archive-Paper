package archive;

import ca.spottedleaf.dataconverter.minecraft.MCDataConverter;
import ca.spottedleaf.dataconverter.minecraft.datatypes.MCDataType;
import ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * Standalone NBT-set upgrade pass (--upgradeNbt). Datafixes a directory of
 * lone NBT compounds from one data version to another in isolation, with no
 * chunk/world context, and writes the upgraded compounds to an output dir
 * under the same filenames.
 *
 * <p>This is the datafixer half of a downstream content-addressed store that
 * dedups a world's block-entities to a small unique set and needs that set
 * upgraded once per version bump. It runs as a post-spin batch pass for the
 * same reason {@code --upgradeChunks} / {@code --bakeLight} do: Paper's rewrite
 * converter ({@link MCDataConverter} over {@link MCTypeRegistry}) only
 * initializes correctly inside a booted server with the registries
 * bootstrapped. The dispatch site therefore boots a (throwaway) scratch world
 * first; that world is unrelated to {@code --archiveNbtInputDir/OutputDir}.
 *
 * <p>Engine: each compound is upgraded via {@link
 * MCDataConverter#convertTag(MCDataType, CompoundTag, int, int)} with the
 * selected {@link MCDataType}. This is byte-for-byte the same transform the
 * whole-chunk upgrade applies to that compound: {@code --upgradeChunks} routes
 * a chunk through {@code convertTag(CHUNK, ...)}, whose walker descends into the
 * {@code block_entities} sub-list via the {@code TILE_ENTITY} walkers. Calling
 * {@code convertTag(TILE_ENTITY, be, from, to)} directly on a lone block-entity
 * is the isolated form of that descent, which is why the two agree.
 *
 * <p>Wire format: UNCOMPRESSED, empty-named-root, big-endian NBT (bytes begin
 * {@code 0A 00 00}). {@link NbtIo#read(Path)} decodes each input directly and
 * {@link NbtIo#write(CompoundTag, Path)} emits the same form, so a re-run and
 * the downstream reader round-trip cleanly. No gzip.
 *
 * <p>Failure policy: per-file try/catch. A {@code null} read (empty or
 * non-compound file), a convert throw, or a write throw counts the file as
 * failed and continues with the rest of the set. Any failure (or a fatal
 * config error) calls {@link ArchiveSettings#markPassFailed()} so the process
 * exits non-zero (70) via the dispatch site and a bad run is never mistaken
 * for a clean one.
 *
 * <p>Unaffected by {@code --archiveDisableSaving}: that flag gates the world
 * save-paths (region/entity/poi/level.dat writes); this pass writes standalone
 * files straight to {@code --archiveNbtOutputDir}, so it persists regardless.
 */
public final class NbtSetUpgrader {
    private static final Logger LOGGER = LogUtils.getLogger();

    private NbtSetUpgrader() {}

    public static void run(MinecraftServer server) {
        // --upgradeNbt is standalone external I/O with no world content. Combining
        // it with any world-content pass would either silently shadow that pass
        // (this block dispatches first) or upgrade the wrong thing, so reject the
        // combination outright rather than guess. markPassFailed() -> exit 70.
        List<String> conflicts = ArchiveSettings.requestedWorldContentPassFlags();
        if (!conflicts.isEmpty()) {
            failConfig("[The Archive] --upgradeNbt is a standalone pass (external NBT files, no world content) and cannot be combined with {}. Re-invoke --upgradeNbt on its own.",
                       String.join(", ", conflicts));
            return;
        }

        String typeName = ArchiveSettings.archiveNbtType();
        MCDataType type = resolveType(typeName);
        if (type == null) {
            failConfig("[The Archive] --upgradeNbt: unknown or missing --archiveNbtType={} (supported: {}).",
                       typeName, supportedTypes());
            return;
        }

        String inputDirRaw = ArchiveSettings.archiveNbtInputDir();
        String outputDirRaw = ArchiveSettings.archiveNbtOutputDir();
        if (inputDirRaw == null || outputDirRaw == null) {
            failConfig("[The Archive] --upgradeNbt requires --archiveNbtInputDir and --archiveNbtOutputDir (inputDir={}, outputDir={}).",
                       inputDirRaw, outputDirRaw);
            return;
        }
        Path inputDir = Path.of(inputDirRaw);
        Path outputDir = Path.of(outputDirRaw);
        if (!Files.isDirectory(inputDir)) {
            failConfig("[The Archive] --upgradeNbt: --archiveNbtInputDir does not exist or is not a directory: {}", inputDir);
            return;
        }
        // The input dir is the source-version corpus. Writing upgraded compounds back
        // over it would leave a re-run unable to tell already-upgraded files from
        // source ones (standalone compounds carry no data version, and from/to are
        // fixed CLI values), so the second run would re-apply the fixers. Require
        // distinct directories rather than silently double-convert on re-run.
        Path inCanon = inputDir.toAbsolutePath().normalize();
        Path outCanon = outputDir.toAbsolutePath().normalize();
        if (inCanon.equals(outCanon)) {
            failConfig("[The Archive] --upgradeNbt: --archiveNbtInputDir and --archiveNbtOutputDir must be different directories (both resolve to {}); writing upgraded compounds back over the source would double-convert on a re-run.",
                       inCanon);
            return;
        }

        int fromVersion = ArchiveSettings.archiveNbtFromVersion();
        int toVersion = ArchiveSettings.archiveNbtToVersion();
        if (fromVersion <= 0) {
            failConfig("[The Archive] --upgradeNbt requires a positive --archiveNbtFromVersion (got {}). Pass the source data version, e.g. 4189 for 1.21.4.", fromVersion);
            return;
        }
        if (toVersion <= 0) {
            failConfig("[The Archive] --upgradeNbt: --archiveNbtToVersion must be positive (got {}).", toVersion);
            return;
        }
        if (toVersion < fromVersion) {
            failConfig("[The Archive] --upgradeNbt: --archiveNbtToVersion ({}) is older than --archiveNbtFromVersion ({}); the converter has no downgrade path and would write the compounds back unchanged. Check the version order (source -> target).",
                       toVersion, fromVersion);
            return;
        }

        try {
            Files.createDirectories(outputDir);
        } catch (IOException ex) {
            failConfig("[The Archive] --upgradeNbt: failed to create --archiveNbtOutputDir {}: {}", outputDir, ex.getMessage());
            return;
        }

        List<Path> inputs = listNbtFiles(inputDir);
        if (inputs == null) {
            // Directory listing itself failed; treat as fatal (we can't enumerate
            // the work, so "0 files, clean" would be a lie).
            ArchiveSettings.markPassFailed();
            return;
        }

        LOGGER.info("[The Archive] Starting NBT-set upgrade pass (--upgradeNbt): type={}, from dv{} -> dv{}, {} files, in={}, out={}",
                    typeName, fromVersion, toVersion, inputs.size(), inputDir, outputDir);
        if (inputs.isEmpty()) {
            LOGGER.warn("[The Archive] --upgradeNbt: no *.nbt files found in {}; nothing to do.", inputDir);
            return;
        }

        long startMillis = System.currentTimeMillis();
        int written = 0;
        int failed = 0;
        for (Path input : inputs) {
            String name = input.getFileName().toString();
            try {
                CompoundTag data = NbtIo.read(input);
                if (data == null) {
                    // NbtIo.read(Path) returns null only when the file no longer
                    // exists, i.e. it vanished between the listing and this read.
                    LOGGER.error("[The Archive] --upgradeNbt: {} could not be read (vanished after listing); skipping.", name);
                    failed++;
                    continue;
                }
                if (data.getStringOr("id", "").isEmpty()) {
                    // TILE_ENTITY (and later ENTITY) route every fixer off the id
                    // field; with no id the id-gated converters and the embedded
                    // item/payload sub-walkers silently no-op, producing a compound
                    // that looks upgraded but is not. Fail the file instead.
                    LOGGER.error("[The Archive] --upgradeNbt: {} has no \"id\" field; the {} converter routes off id and cannot upgrade it in isolation; skipping.", name, typeName);
                    failed++;
                    continue;
                }
                CompoundTag upgraded = MCDataConverter.convertTag(type, data, fromVersion, toVersion);
                NbtIo.write(upgraded, outputDir.resolve(name));
                written++;
            } catch (Throwable t) {
                LOGGER.error("[The Archive] --upgradeNbt: {} failed: {}", name, t.toString(), t);
                failed++;
            }
        }

        long elapsedMs = System.currentTimeMillis() - startMillis;
        if (failed > 0) {
            ArchiveSettings.markPassFailed();
            LOGGER.error("[The Archive] NBT-set upgrade FAILED: {} in, {} upgraded, {} failed (type={}, dv{} -> dv{}) in {}ms. Output dir retains the {} files that did upgrade.",
                         inputs.size(), written, failed, typeName, fromVersion, toVersion, elapsedMs, written);
        } else {
            LOGGER.info("[The Archive] NBT-set upgrade complete: {} in, {} upgraded, {} failed (type={}, dv{} -> dv{}) in {}ms.",
                        inputs.size(), written, failed, typeName, fromVersion, toVersion, elapsedMs);
        }
    }

    /**
     * Log a fatal configuration error and mark the pass failed so the dispatch
     * site exits non-zero (70). Keeps the ERROR log and markPassFailed() paired
     * in one place, so a guard cannot report a failure yet still exit clean.
     */
    private static void failConfig(String message, Object... args) {
        LOGGER.error(message, args);
        ArchiveSettings.markPassFailed();
    }

    /**
     * Map the --archiveNbtType flag value to a converter data type. Both
     * {@link MCTypeRegistry#TILE_ENTITY} and (later) {@code ENTITY} are
     * {@code IDDataType}, a subtype of {@link MCDataType}, so widening the
     * registry to a new type is a one-line addition here. Unknown/absent
     * returns {@code null}; the caller rejects it with a clear error.
     */
    private static MCDataType resolveType(String typeName) {
        if (typeName == null) return null;
        return switch (typeName) {
            case "TILE_ENTITY" -> MCTypeRegistry.TILE_ENTITY;
            default -> null;
        };
    }

    private static String supportedTypes() {
        return "TILE_ENTITY";
    }

    /**
     * List every {@code *.nbt} file directly in {@code dir}, sorted by filename
     * for run-to-run determinism (the conversion itself is order-independent).
     * Returns {@code null} if the directory listing throws, which the caller
     * treats as a fatal enumeration failure rather than an empty set.
     */
    private static List<Path> listNbtFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.nbt")) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) files.add(p);
            }
        } catch (IOException ex) {
            LOGGER.error("[The Archive] --upgradeNbt: failed to list {}: {}", dir, ex.getMessage());
            return null;
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }
}
