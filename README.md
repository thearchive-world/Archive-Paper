# Archive-Paper
PaperMC Fork for thearchive.world

Archive-Paper runs archived worlds as a read-only server: saving is disabled by default, so chunk mutations, entity ticks, and player edits never persist to disk. The upgrade pipeline below exists to bring pre-26.1 source worlds up to the current data version before they're served; day-to-day operation is just running the server with the default `--archiveDisableSaving` behaviour.

## Usage

`--archiveDisableSaving` controls the read-only behaviour. Three states: flag absent uses the archive default (saving off); `--archiveDisableSaving` alone enables it; `--archiveDisableSaving=true|false` sets explicitly. Override: when the operator does NOT explicitly pass this flag, saving is forced on during `--forceUpgrade`, `--recreateRegionFiles`, and `--upgradeChunks` so the upgrade can persist. Explicitly setting `--archiveDisableSaving=true` alongside any of those logs a wedge warning at startup (the upgrade would silently no-op every write).

## Upgrading worlds

Archive-Paper does not auto-upgrade pre-26.1 source worlds on first startup. To upgrade in place, pass `--upgradeChunks` (recommended) or `--forceUpgrade`. Alternatively, run vanilla Paper once on the world, then switch to Archive-Paper.

`--upgradeChunks` is the recommended path for upgrading archive worlds. Parallel post-spin conversion pass with saving enabled; resumable via `.archive-upgrade-progress.txt`; per-chunk failure tolerant. After the per-dimension chunk loop, a single-threaded tail pass DFUs `data/minecraft/maps/*.dat` and `last_id.dat` (overworld only); progress and failures share the same end-of-run summary. Logs `Starting chunk upgrade pass` when it begins. The server halts after the pass.

`--forceUpgrade` runs Mojang's `WorldUpgrader` pre-spin. The scaffolding (`WorldUpgrader`, `RegionStorageUpgrader`) is Mojang's; per-chunk conversion goes through Paper's data converter rewrite (`MCDataConverter`) at the leaf, same engine as `--upgradeChunks`. Paper gutted the flag in 26.1; restored here. Single-threaded; not resumable.

`--upgradeWorkerCount=N` sets the `--upgradeChunks` worker pool size. Any positive `N` is honoured verbatim. The default (when the flag is absent) is `min(availableProcessors, 16)`; the 16 cap applies only to that default, so pass `--upgradeWorkerCount=32` to actually use 32 workers on a 32-core host.

`--splitEntities` extracts legacy embedded entities from pre-1.17 chunks into `entities/*.mca` during `--upgradeChunks`. Pre-1.17 worlds store entities inside chunk NBT; Paper's runtime would split them out lazily on chunk load, but Archive-Paper disables saving by default, so the split never persists on disk. Off by default; bare flag enables, or pass an explicit boolean.

`--cleanDirtyChunks` is the post-upgrade NBT cleaner. Strips the dirt classes `--auditDirtyChunks` measures (invalid attributes, ghost block entities, block-entity coord mismatches, block-entity type mismatches, duplicate UUIDs) directly from `region/*.mca` and `entities/*.mca` in parallel, with no chunk-system load. Run AFTER `--upgradeChunks`, never on raw 1.12.2: vanilla legacy attribute names fail the same `Identifier.tryParse` parser the Forge-taint detector uses, so Level-wrapped chunks are skipped and counted under `legacy-chunks`. Halts after the pass.

`--bakeLight` is the post-clean light/heightmap/UpgradeData baker. Bakes `isLightOn`, finalized heightmaps, and resolved `UpgradeData` (Indices, Sides, neighbour-tick lists) into each chunk on disk so the runtime save-gated load no longer pays `UpgradeData.upgrade()`, Starlight, or `Heightmap.primeHeightmaps` per-visit on legacy chunks. Run AFTER `--cleanDirtyChunks`, BEFORE `--auditDirtyChunks`. Halts after the pass.

`--auditDirtyChunks` is the read-only audit pass. Walks each dimension's `region/*.mca`, decodes block entities and section block states, counts dirt classes (`invalid-attrs`, `ghost-bes`, `be-coord-mismatch`, `be-type-mismatch`, `uuid-dups`, `bake-partial`, `bake-pending`, `bake-ineligible`). Single-threaded; logs progress every 30s as `X / Y regions (Z chunks, W ch/s)`. Prints per-dim and total counts; does not modify world files. Use to verify the pipeline ran clean (counts should be zero, or within published bake-partial spec).

`--auditFailOnDirty` turns the audit into a pipeline gate. By default `--auditDirtyChunks` reports counts and exits 0 whatever it finds, so a measurement audit always succeeds; with this flag the audit exits 70 when any cleaner-target class (`ghost-bes`, `be-coord-mismatch`, `be-type-mismatch`, `invalid-attrs`, `uuid-dups`) is non-zero. The informational counts (`legacy-chunks`, `bake-*`, `poi-valid`, `poi-invalid`, totals) never gate. Pair it as `--auditDirtyChunks --auditFailOnDirty` for the final pipeline step; it exits 0 only once `--cleanDirtyChunks` has driven those five classes to zero. Opt-in, default off.

`--preserveChunkTimestamps` preserves the per-chunk region-file slot timestamps (the 1024-slot table in each `.mca` header, sector 1). The flag is hooked at the shared `RegionFile.write` entry, so it covers `--upgradeChunks`, `--cleanDirtyChunks`, and `--bakeLight`, plus any other chunk write while the flag is set; set it only on archive-pipeline invocations, not on a live server or a `--forceUpgrade` pre-spin. Without the flag every touched slot's timestamp is restamped to the pipeline's wallclock; with it, the original slot timestamp is preserved as long as it was nonzero. The flag must be set on every pipeline invocation that should preserve those timestamps; it has no retroactive effect on slots already stamped by a flagless prior run. Default off so the vanilla restamp behavior is unchanged for non-archival runs. The NBT `LastUpdate` and `InhabitedTime` fields are already preserved unchanged by the pipeline; this flag only affects the slot-header timestamps.

The operator pipeline is `--upgradeChunks` then `--cleanDirtyChunks` then `--bakeLight` then `--auditDirtyChunks`, each as a separate invocation. None of the flags chain.

Combining `--forceUpgrade` (or `--recreateRegionFiles`) with `--upgradeChunks` is redundant; both run conversion on the same files. A warning is logged at INFO.

The batch passes run headless. When any of `--upgradeChunks`, `--cleanDirtyChunks`, `--bakeLight`, `--auditDirtyChunks`, or `--upgradeNbt` is requested, the server skips the network listener and does not bind `server-port`, so a pass never depends on a free port. Before this, a port collision could abort startup after the world-folder migration but before the upgrade ran, leaving a world migrated to the per-dimension layout but not data-upgraded. A normal (non-pass) server run binds the port as usual.

## Upgrading a standalone NBT set

`--upgradeNbt` is a standalone datafixer, separate from the world pipeline above: it touches no world content. It upgrades a directory of standalone block-entity NBT compounds from one data version to another in isolation, using the same rewrite converter (`MCDataConverter`) the chunk passes use, and writes each upgraded compound to an output directory under its original filename. It is the datafixer half of a downstream content-addressed block-entity store, which dedups a world's block entities to a small unique set and upgrades that set once per version bump.

```sh
java -jar paperclip-*.jar nogui --upgradeNbt \
    --archiveNbtType=TILE_ENTITY \
    --archiveNbtInputDir=<dir of source-version .nbt files> \
    --archiveNbtOutputDir=<dir for target-version .nbt files> \
    --archiveNbtFromVersion=4189 \
    --archiveNbtToVersion=4790
```

- `--archiveNbtType` selects the converter data type. `TILE_ENTITY` (block entities) is supported; an unknown type is rejected with a non-zero exit.
- `--archiveNbtInputDir` and `--archiveNbtOutputDir` are directories of `*.nbt` files and must be different directories. Each input is one compound at `--archiveNbtFromVersion`; the output keeps the input filename. The output directory is created if absent, and re-runs overwrite.
- `--archiveNbtFromVersion` is the source data version (required). `--archiveNbtToVersion` is the target; it defaults to the server's current world version when omitted, and must not be older than the from-version (the converter has no downgrade path).
- The `.nbt` files are uncompressed, empty-named-root NBT (the form `net.minecraft.nbt.NbtIo.read`/`write` produces), not gzip.

The pass still boots a world, since it runs in the same post-spin lifecycle as the other passes, so point it at a scratch server directory; an empty one is fine, because the server prepares a fresh throwaway world at boot (with saving off by default it is not written back to disk), and the input and output directories are unrelated to it. It runs headless and halts after the pass. It is standalone: combining it with `--upgradeChunks`, `--cleanDirtyChunks`, `--bakeLight`, or `--auditDirtyChunks` is rejected with an error and a non-zero exit rather than silently running only one of them. Exit codes follow the same convention as the other passes: `0` when every file converts, `70` when any file fails to convert (including a compound with no `id`, which the converter cannot route in isolation) or the configuration is invalid (unknown type, a missing input/output-directory flag, an input directory that does not exist, the input and output pointing at the same directory, or a non-positive or out-of-order version).

## Exit codes

The passes set the process exit code so a pipeline script can gate on `$?` between stages:

- `0`: success. A pass completed cleanly, or a normal server run shut down normally.
- `70`: a batch pass failed. The pass logged `<pass> FAILED` (non-zero tracked region, chunk, or IO failures) or threw, or `--auditFailOnDirty` found cleaner-target dirt. This reuses Paper's existing abnormal-exit code.
- `1`: a bad invocation. An unrecognized or malformed CLI option, or a fatal startup condition such as a `!` or `+` in the working-directory path, an unsupported pre-release JDK, or a pre-spin boot failure.

A pass that completes with some per-chunk failures still logs `<pass> FAILED` and exits 70, so "logged success" and "exited 0" now mean the same thing for the pipeline.

## Examples

Serve an already-upgraded world (the day-to-day case; saving stays off by default):

```sh
java -Xmx32G -jar paperclip-*.jar nogui
```

Full upgrade pipeline on a pre-26.1 world, one invocation per pass:

```sh
java -Xmx32G -jar paperclip-*.jar nogui --upgradeChunks --upgradeWorkerCount=16 --splitEntities
java -Xmx32G -jar paperclip-*.jar nogui --cleanDirtyChunks --upgradeWorkerCount=16
java -Xmx32G -jar paperclip-*.jar nogui --bakeLight --upgradeWorkerCount=16
java -Xmx32G -jar paperclip-*.jar nogui --auditDirtyChunks --auditFailOnDirty
```

Each line halts after its pass; check `$?` between them to stop the pipeline on the first failure (a failed pass or, on the last line, residual dirt exits non-zero).

32-core host, override the default worker cap:

```sh
java -Xmx64G -jar paperclip-*.jar nogui --upgradeChunks --upgradeWorkerCount=32
```

Audit only, to inspect dirt classes on a world without modifying it:

```sh
java -Xmx16G -jar paperclip-*.jar nogui --auditDirtyChunks
```

Resume an interrupted `--upgradeChunks` pass; just re-run the same command. Already-current chunks are skipped via the in-progress file (`.archive-upgrade-progress.txt`) and a per-chunk skip-if-current pre-flight:

```sh
java -Xmx32G -jar paperclip-*.jar nogui --upgradeChunks
```

Legacy single-threaded path (use `--upgradeChunks` instead unless you need the pre-spin behaviour):

```sh
java -Xmx32G -jar paperclip-*.jar nogui --forceUpgrade
```
