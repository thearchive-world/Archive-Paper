# Archive-Paper
PaperMC Fork for thearchive.world

Archive-Paper runs archived worlds as a read-only server: saving is disabled by default, so chunk mutations, entity ticks, and player edits never persist to disk. The upgrade pipeline below exists to bring pre-26.1 source worlds up to the current data version before they're served; day-to-day operation is just running the server with the default `--archiveDisableSaving` behaviour.

## Usage

`--archiveDisableSaving` controls the read-only behaviour. Three states: flag absent uses the archive default (saving off); `--archiveDisableSaving` alone enables it; `--archiveDisableSaving=true|false` sets explicitly. Override: when the operator does NOT explicitly pass this flag, saving is forced on during `--forceUpgrade`, `--recreateRegionFiles`, and `--upgradeChunks` so the upgrade can persist. Explicitly setting `--archiveDisableSaving=true` alongside any of those logs a wedge warning at startup (the upgrade would silently no-op every write).

## Upgrading worlds

Archive-Paper does not auto-upgrade pre-26.1 source worlds on first startup. To upgrade in place, pass `--upgradeChunks` (recommended) or `--forceUpgrade`. Alternatively, run vanilla Paper once on the world, then switch to Archive-Paper.

`--upgradeChunks` is the recommended path for upgrading archive worlds. Parallel post-spin conversion pass with saving enabled; resumable via `.archive-upgrade-progress.txt`; per-chunk failure tolerant. Logs `Starting chunk upgrade pass` when it begins. The server halts after the pass.

`--forceUpgrade` runs Mojang's `WorldUpgrader` pre-spin. The scaffolding (`WorldUpgrader`, `RegionStorageUpgrader`) is Mojang's; per-chunk conversion goes through Paper's data converter rewrite (`MCDataConverter`) at the leaf, same engine as `--upgradeChunks`. Paper gutted the flag in 26.1; restored here. Single-threaded; not resumable.

`--upgradeWorkerCount=N` sets the `--upgradeChunks` worker pool size. Any positive `N` is honoured verbatim. The default (when the flag is absent) is `min(availableProcessors, 16)`; the 16 cap applies only to that default, so pass `--upgradeWorkerCount=32` to actually use 32 workers on a 32-core host.

`--splitEntities` extracts legacy embedded entities from pre-1.17 chunks into `entities/*.mca` during `--upgradeChunks`. Pre-1.17 worlds store entities inside chunk NBT; Paper's runtime would split them out lazily on chunk load, but Archive-Paper disables saving by default, so the split never persists on disk. Off by default; bare flag enables, or pass an explicit boolean.

`--cleanDirtyChunks` is the post-upgrade NBT cleaner. Strips the dirt classes `--auditDirtyChunks` measures (invalid attributes, ghost block entities, block-entity coord mismatches, block-entity type mismatches, duplicate UUIDs) directly from `region/*.mca` and `entities/*.mca` in parallel, with no chunk-system load. Run AFTER `--upgradeChunks`, never on raw 1.12.2: vanilla legacy attribute names fail the same `Identifier.tryParse` parser the Forge-taint detector uses, so Level-wrapped chunks are skipped and counted under `legacy-chunks`. Halts after the pass.

`--bakeLight` is the post-clean light/heightmap/UpgradeData baker. Bakes `isLightOn`, finalized heightmaps, and resolved `UpgradeData` (Indices, Sides, neighbour-tick lists) into each chunk on disk so the runtime save-gated load no longer pays `UpgradeData.upgrade()`, Starlight, or `Heightmap.primeHeightmaps` per-visit on legacy chunks. Run AFTER `--cleanDirtyChunks`, BEFORE `--auditDirtyChunks`. Halts after the pass.

`--auditDirtyChunks` is the read-only audit pass. Walks each dimension's `region/*.mca`, decodes block entities and section block states, counts dirt classes (`invalid-attrs`, `ghost-bes`, `be-coord-mismatch`, `be-type-mismatch`, `uuid-dups`, `bake-partial`, `bake-pending`, `bake-ineligible`). Prints per-dim and total counts; does not modify world files. Use to verify the pipeline ran clean (counts should be zero, or within published bake-partial spec).

The operator pipeline is `--upgradeChunks` then `--cleanDirtyChunks` then `--bakeLight` then `--auditDirtyChunks`, each as a separate invocation. None of the flags chain.

Combining `--forceUpgrade` (or `--recreateRegionFiles`) with `--upgradeChunks` is redundant; both run conversion on the same files. A warning is logged at INFO.

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
java -Xmx32G -jar paperclip-*.jar nogui --auditDirtyChunks
```

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
