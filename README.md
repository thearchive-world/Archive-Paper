# Archive-Paper
PaperMC Fork for thearchive.world

Archive-Paper does not auto-upgrade pre-26.1 source worlds on first startup. To upgrade in place, pass `--upgradeChunks` (recommended) or `--forceUpgrade`. Alternatively, run vanilla Paper once on the world, then switch to Archive-Paper.

## CLI flags

`--upgradeChunks` is the recommended path for upgrading archive worlds. Parallel post-spin conversion pass with saving enabled; resumable via `.archive-upgrade-progress.txt`; per-chunk failure tolerant. Logs `Starting chunk upgrade pass` when it begins. The server halts after the pass.

`--forceUpgrade` runs Mojang's `WorldUpgrader` pre-spin. The scaffolding (`WorldUpgrader`, `RegionStorageUpgrader`) is Mojang's; per-chunk conversion goes through Paper's data converter rewrite (`MCDataConverter`) at the leaf, same engine as `--upgradeChunks`. Paper gutted the flag in 26.1; restored here. Single-threaded; not resumable.

`--upgradeWorkerCount=N` sets the `--upgradeChunks` worker pool size (default `min(cores, 16)`).

`--splitEntities` extracts legacy embedded entities from pre-1.17 chunks into `entities/*.mca` during `--upgradeChunks`. Pre-1.17 worlds store entities inside chunk NBT; Paper's runtime would split them out lazily on chunk load, but Archive-Paper disables saving by default, so the split never persists on disk. Off by default; bare flag enables, or pass an explicit boolean.

Combining `--forceUpgrade` (or `--recreateRegionFiles`) with `--upgradeChunks` is redundant; both run conversion on the same files. A warning is logged at INFO.
