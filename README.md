# Archive-Paper

PaperMC fork that enables a Minecraft read-only server. It disables world persistence while preserving features like portal navigation and entity handling.

Used by thearchive.world server.

## Custom Patches

### Minecraft Patches (13 patches)
**World Saving Disablement:**

- **0001-Disable-Region-File-IO-Saving**: Blocks the server from writing chunk data to region files (.mca files)
- **0002-Skip-chunk-save-serialization**: Prevents converting chunk data from memory into save file format
- **0003-Skip-entity-save-serialization**: Prevents converting entity and POI data from memory into save file format
- **0004-Skip-level.dat-writing**: Prevents writing the main world info file (level.dat)
- **0005-Skip-RegionFileStorage-write**: Blocks the low-level system that writes chunk NBT data to .mca files
- **0007-Disable-all-data-directory-saving**: Prevents saving all world data files (structures, villages, etc.)
- **0012-Disable-region-file-padding-saving**: Prevents writing padding bytes to align region files to sector boundaries (avoids unnecessary disk writes)

**Functional Enhancements:**

- **0006-Allow-loading-tile-entities-from-outside-chunk**: Allows entities to load even when they're outside their original chunk boundaries (fixes sign blanking and missing entities in world downloads with coordinate mismatches; reverts Paper's Folia-specific restriction)
- **0008-Search-for-exit-portals-using-chunk-data**: Custom portal detection using chunk data instead of POI files (required for older world downloads from before portals were stored in POI files, and clean world downloads without POI folders)
- **0010-Patch-player-respawn-position-chunk-load-process**: Fixes server crashes caused by respawn position calculation trying to create new chunks (sets coprime=0 to disable spawn radius randomization)
- **0011-Handle-entities-with-invalid-position-rotation**: Removes entities with completely invalid positions (NaN/infinite) and fixes corrupted rotation values using custom NanHelper utility
- **0009-mute-setBlockEntity-logspam**: Silences warning messages when block entities (signs, chests, etc.) don't match their block states - common in archived worlds with data inconsistencies
- **0013-log-archive-saving-state-on-startup**: Shows whether saving is enabled/disabled when server starts

### Paper Patches (2 patches)
**Configuration & API:**

- **0001-fix-MC-version-bukkit-API**: Changes version resource path from 'paper-api' to 'archive-api' so Bukkit reports correct Archive-Paper version information
- **0002-archiveDisableSaving-CLI-arg**: Adds `--archiveDisableSaving` command-line argument to explicitly control saving (saving disabled by default, `--forceUpgrade` enables saving)

## Usage

**Default behavior:** Saving is disabled by default.

```bash
# Default - saving disabled
java -jar archive-paper.jar

# Enable saving with forceUpgrade
java -jar archive-paper.jar --forceUpgrade

# Explicitly control saving behavior
java -jar archive-paper.jar --archiveDisableSaving=false  # Enable saving
java -jar archive-paper.jar --archiveDisableSaving=true   # Disable saving (default)
```
