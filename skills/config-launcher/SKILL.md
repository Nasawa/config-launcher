---
name: config-launcher
description: Edit a Config Launcher home screen (launcher.json) safely - place, move and group Android apps, manage pages, the dock and drawer rules, validate against the schema, and read the phone's status. Use when a folder contains launcher.json and .configlauncher/, or the user asks to organize their Android home screen.
---

# Config Launcher layout editing

## When to use
The working folder contains `launcher.json` (usually with `.configlauncher/` beside it), or the user asks to organize the home screen of a phone running Config Launcher.

## Inputs to read first
- `launcher.json`: the layout. Schema: https://github.com/Nasawa/config-launcher/blob/main/schema/launcher-config.v1.schema.json
- `.configlauncher/apps.json`: installed apps. The only valid source of package and activity names.
- `.configlauncher/status.json`: whether the phone accepted the current file, with error paths.
- `AGENTS.md` in the folder, if present: the same rules, kept with the file.

## Procedure
1. Git folder: `git pull --rebase`.
2. Plan the edit by IDs. Keep existing IDs; new IDs match `^[a-z0-9][a-z0-9_-]{0,63}$`, unique file-wide (`a-` app, `s-` shortcut, `f-` folder, `r-` rule; readable page IDs).
3. Edit:
   - Page items: `x`/`y` inside `grid`, one per cell, array in row-major order (y, then x).
   - Dock (≤ 7) and folder items (≤ 48): no `x`/`y`; array order is display order. No nested folders.
   - Only `app`, `shortcut`, `folder` types. Notes go in `x-` keys. Bump `revision`.
   - Omit blocks you don't use; don't add defaults.
   - Apps not in `apps.json`: don't add; report them to the user.
4. Validate: `python3 validate.py launcher.json`, then `python3 validate.py --format launcher.json` for canonical output (download `scripts/validate.py` from https://raw.githubusercontent.com/Nasawa/config-launcher/main/scripts/validate.py if it isn't present).
5. Write the whole file at once; commit and push (never force-push; on rejection pull --rebase, re-validate, push).
6. After sync, read `.configlauncher/status.json`. Errors mean the phone kept its last working layout; fix the listed paths and repeat. A parse error on read means a write in progress: retry after a second.
7. Optional, with adb: `adb shell am broadcast -a dev.nasawa.configlauncher.RELOAD -n dev.nasawa.configlauncher/.ReloadReceiver` reloads immediately and rewrites `status.json`. Result codes: `0` unchanged, `1` reloaded, `2` rejected (read `status.json` and fix), `3` unavailable (retry later), `4` busy: phone edit, drag, or conflict pending (back off and retry), `5` timeout after 8 s (retry later).

## Never
Private-space apps, user IDs or serials, work-profile items unless `apps.json` lists them, writes inside `.configlauncher/`, comments, unknown non-`x-` keys, `widget`/`text` items, files over 2 MiB.

## Limits
20 pages, 144 items per page, 2000 items, grid 2–12, labels 60 chars, IDs 64, other strings 256, 16 tags.
