# Release notes: Reflow Launcher rename (pre-1.0)

This build ships **Reflow Launcher** as a **new app** (`com.anigeek.reflowlauncher`), not an in-place upgrade of Config Launcher (`dev.nasawa.configlauncher`). `allowBackup` is false, so nothing carries over automatically between the two packages.

## What to do

1. **Install** Reflow Launcher (this build).
2. **Set HOME** to Reflow (the short launcher label).
3. **Re-pick your config folder** (the same folder you used before). Your `launcher.json` and `.configlauncher/` stay put; the folder name does not change.
4. **Re-add widgets** you care about (widget hosts are per-app).
5. **Uninstall** the old Config Launcher when you are happy with the new one.

Because Reflow has a new app id, it is a fresh install, not an upgrade: you re-pick the folder, set it as Home again, and redo any phone-only settings. Settings that live on the phone rather than in `launcher.json` (for example **Label backdrop**) start at their defaults in Reflow, so set them again in Settings.

Installing Reflow clears the old app's 'default home' choice, so press Home and pick Reflow (or set it in Settings → Apps → Default apps → Home app). Both apps can stay installed and point at the same folder while you switch. Each picks up the other's saved edits when it comes back to the front, but uninstall Config Launcher once Reflow is set up.

### App-only users (no folder)

There is **no** in-app export, share, or import. An app-only layout lives only in that app's private storage and does not move with the rename.

To keep it:

1. In the **old** Config Launcher, open ⋮ → **Choose folder** (or the first-run folder pick) and pick a folder.
2. If the folder is empty, the app writes your current layout there as `launcher.json`. If the folder already has a file, choose **Replace it with mine** so your layout is written (the previous file is copied to `.configlauncher/backup/` first when that path works).
3. Install Reflow Launcher, set HOME, and **pick that same folder**. The new app reads the folder's `launcher.json`.

Debug builds can also pull the private file with `adb exec-out run-as <old-package> cat files/config/source/launcher.json` and push it into a folder, but that is a developer path, not an end-user feature.

## Agent / adb

- Reload (preferred): `adb shell am broadcast -a com.anigeek.reflowlauncher.RELOAD -p com.anigeek.reflowlauncher`
- Reload (explicit component): `adb shell am broadcast -a com.anigeek.reflowlauncher.RELOAD -n com.anigeek.reflowlauncher/dev.nasawa.configlauncher.ReloadReceiver`
- Start: `adb shell am start -W -n com.anigeek.reflowlauncher/dev.nasawa.configlauncher.MainActivity`
- Package for `run-as` / smoke: `com.anigeek.reflowlauncher` (Kotlin namespace stays `dev.nasawa.configlauncher`; always pass the fully qualified `$PKG/$NS.Class` form)
- Schema `$id` for new files: `https://reflowlauncher.com/schema/launcher-config.v1.schema.json` (the old GitHub `$schema` URL is still accepted; existing files are not rewritten on round-trip).

## Verified on

Verified on: Android 10 (API 29) emulator, software rendering: fresh install, side by side with Config Launcher on one folder, and the app-only case (Grace). CI: smoke on API 29/35/36 emulators and the dev8 screen matrix (phones/tablets API 35, fold API 36). Not yet checked on a physical device.

## Unchanged on purpose

- Kotlin packages / Gradle `namespace`: `dev.nasawa.configlauncher`
- Sidecar folder: `.configlauncher/`
- GitHub repository name: `Nasawa/config-launcher` (renamed separately after this lands)
