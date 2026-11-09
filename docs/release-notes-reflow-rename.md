# Release notes: Reflow Launcher rename (pre-1.0)

This build ships **Reflow Launcher** as a **new app** (`com.anigeek.reflowlauncher`), not an in-place upgrade of Config Launcher (`dev.nasawa.configlauncher`). `allowBackup` is false, so nothing carries over automatically.

## What to do

1. **Install** Reflow Launcher (this build).
2. **Set HOME** to Reflow (the short launcher label).
3. **Re-pick your config folder** (the same folder you used before). Your `launcher.json` and `.configlauncher/` stay put; the folder name does not change.
4. **Re-add widgets** you care about (widget hosts are per-app).
5. **Uninstall** the old Config Launcher when you are happy with the new one.

### App-only users (no folder)

Your layout lives only in the old app's private storage. It does **not** carry over. Export it first (⋮ → share / copy `launcher.json` via the documented `run-as` commands on the old build), install Reflow Launcher, then import or re-pick a folder and paste the file back.

## Agent / adb

- Reload: `adb shell am broadcast -a com.anigeek.reflowlauncher.RELOAD -n com.anigeek.reflowlauncher/.ReloadReceiver`
- Package for `run-as` / smoke: `com.anigeek.reflowlauncher`
- Schema `$id` for new files: `https://reflowlauncher.com/schema/launcher-config.v1.schema.json` (the old GitHub `$schema` URL is still accepted; existing files are not rewritten on round-trip).

## Unchanged on purpose

- Kotlin packages / Gradle `namespace`: `dev.nasawa.configlauncher`
- Sidecar folder: `.configlauncher/`
- GitHub repository name: `Nasawa/config-launcher` (renamed separately after this lands)
