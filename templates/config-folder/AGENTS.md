# Agent instructions: Config Launcher layout

This folder holds `launcher.json`, the home screen of an Android phone running **Config Launcher**. The phone reads this file and writes it back when the user rearranges icons. You can edit it; the phone picks up valid changes within seconds of syncing.

Schema: https://github.com/Nasawa/config-launcher/blob/main/schema/launcher-config.v1.schema.json (a local copy may exist at `.configlauncher/launcher-config.v1.schema.json`). Full guide: https://github.com/Nasawa/config-launcher#editing-launcherjson-for-agents-and-humans

## Before you edit
1. If this folder is a git repo: `git pull --rebase` first. The phone commits its own edits.
2. Read `.configlauncher/apps.json` for installed apps (`package`, `activity`, `label`, `profile`). **Never invent a package name.** If an app isn't listed, don't add it; tell the user.
3. Read `.configlauncher/status.json` to see whether the current file loaded cleanly.

## Editing rules
- JSON only: no comments, no trailing commas. UTF-8, 2-space indent, keys in schema order (see the guide).
- Every page, item and drawer rule has an `id` matching `^[a-z0-9][a-z0-9_-]{0,63}$`, **unique across the whole file**. Keep existing IDs; give new things new IDs. Convention: `a-` apps, `s-` shortcuts, `f-` folders, `r-` drawer rules; page IDs are readable words (`home`, `work`). Any valid ID works.
- Page items need `x`/`y` (0-based, inside `grid`, one item per cell). Write them in row-major order (by `y`, then `x`). Dock and folder items have **no** `x`/`y`; their order is the array order. No folders inside folders.
- Types: `app`, `shortcut`, `folder` only (no `widget` or `text`).
- Put notes in keys starting with `x-` (for example `"x-note"`). Any other unknown key makes the file invalid.
- Bump `revision` by 1 when you write.
- Leave out blocks you don't use (for example `drawer`); don't add default values you didn't change.
- **Never write:** private-space apps, user IDs or serial numbers, `profile: "work"` items unless `apps.json` lists work apps, anything inside `.configlauncher/`, or apps just to "list" them (unplaced apps already show in the drawer).

## Limits
2 MiB file, 20 pages, 144 items per page, 2000 items total, 48 per folder, 7 in the dock, grid 2–12 × 2–12, labels 60 characters, IDs 64, other strings 256, 16 tags per item.

## After you edit
1. Validate: `python3 validate.py launcher.json` (get it from https://raw.githubusercontent.com/Nasawa/config-launcher/main/scripts/validate.py; stdlib only, optional `pip install jsonschema`). `--format` rewrites the file exactly as the phone would.
2. Write the whole file in one go. Commit and push (git). Never force-push; on a rejected push, `git pull --rebase`, re-validate, push again. Never commit conflict markers.
3. After the phone syncs, read `.configlauncher/status.json`. If it lists errors, the phone kept its last working layout: fix those errors (paths and messages are given) and write again. `warnings` (for example an element this version skipped) don't block loading; the element stays in the file.
4. If reading `launcher.json` fails to parse, the phone may be mid-write: wait a second and retry.

The phone never runs anything from this file. If the user edits on the phone at the same time, the phone keeps your version except for the items the user just touched.
