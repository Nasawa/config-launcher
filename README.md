# Config Launcher

An Android home screen whose whole layout lives in one file, `launcher.json`, in a folder you choose. Rearrange apps
on your phone and the file updates. Edit the file on your computer (or let an agent edit it) and the phone updates.

- Kotlin + Jetpack Compose, Android 10+ (minSdk 29).
- No risky permissions: no storage, `QUERY_ALL_PACKAGES`, Accessibility or network access. You grant access to one
  folder through Android's system folder picker, and you can revoke it.
- Status: architecture and UX specs are done. See [ARCHITECTURE.md](ARCHITECTURE.md) and [docs/ux.md](docs/ux.md).

## Editing launcher.json (for agents and humans)

### Where the file lives
- `launcher.json` sits at the **root of the folder picked on first run** (Settings → *Config folder* changes it). A
  git clone kept in sync by a git app on the phone is the intended setup (below). Syncthing works too, and cloud folders
  are best-effort.
- Only the file named exactly `launcher.json` is read.
- The launcher also creates `.configlauncher/` in that folder (see [Files the launcher writes](#files-the-launcher-writes-in-your-folder)).

### Recommended: keep the folder in git
Git is the main way to sync. A git app on the phone (for example one that auto-pulls and auto-commits a folder) keeps a
clone of your repo, and that clone is the folder you pick in Config Launcher. The launcher never runs git itself: it
only reads and writes `launcher.json` in that folder.

1. **Clone or pull.**
   ```sh
   git clone <your-repo> launcher-config && cd launcher-config   # first time
   git pull --rebase                                              # every time after
   ```
   Pull first, every time. The phone commits its own edits (through the git app), so the remote often has changes.
2. **Edit `launcher.json`.** Keep IDs stable, give new items new IDs, put notes in `x-` keys (see [Schema](#schema)).
3. **Validate before committing.**
   ```sh
   pip install jsonschema            # optional; the phone's own rules run without it
   python3 scripts/validate.py launcher.json            # same errors, same wording as the phone
   python3 scripts/validate.py --format launcher.json   # rewrite in the phone's canonical form
   ```
   `scripts/validate.py` (Python 3.9+, stdlib only) runs the phone's checks: the schema, unique IDs across the whole
   file, no two items in one cell, cells inside the grid, no nested folders, no x/y in the dock or folders, and the
   size limits. `--format` writes exactly what the phone writes, so your commit won't show up as a diff after the phone's
   next save. It never rewrites an invalid file. `--check` exits 1 if the file isn't already canonical, which is useful
   in a pre-commit hook. Exit codes: 0 valid, 1 invalid (or not canonical with `--check`), 2 usage error. Syntax
   errors are worded by Python's JSON parser, so they read differently from the phone's.
4. **Commit and push.**
   ```sh
   git add launcher.json && git commit -m "Move Maps to the dock" && git push
   ```
5. **How the phone picks it up.** Once the phone's git app pulls, the file changes in the folder. The launcher checks
   every 2 s while the home screen is showing and immediately when you press Home, so the change appears within a few
   seconds of the pull. How often the pull happens depends on the git app's sync interval. If the new file is invalid,
   the phone keeps the last working layout and shows a banner, and `.configlauncher/status.json` (ignored by git, see
   below) says why on the phone.
6. **Push rejected** (`! [rejected] … (fetch first)`): the phone pushed meanwhile. Run `git pull --rebase`, then
   validate and push again. Never force-push: that would drop the phone's edits.
7. **Merge conflict in `launcher.json`.** Never commit a file with `<<<<<<<` markers. The phone would treat it as a
   parse error (B1) and keep the last working layout, so nothing breaks, but nothing updates either. Instead:
   - Take one whole side, usually the remote (phone) version: `git checkout --ours launcher.json` (during a rebase,
     "ours" is the remote and "theirs" is your commit), or edit the hunks by hand.
   - Re-apply your change on top, validate (step 3), then `git add launcher.json && git rebase --continue`.
   - Because the phone rewrites the whole file in a canonical format, writing the same format yourself (2-space
     indent, keys in schema order) keeps conflicts small.
8. **Ignore the launcher's scratch files.** Add this `.gitignore`:
   ```gitignore
   .configlauncher/*.tmp
   .configlauncher/status.json
   .configlauncher/apps.json
   ```
   Never commit `.tmp`, `status.json` or `apps.json`. They change on every phone write and cause pointless conflicts. `backup/`
   and `broken/` are yours to commit or ignore. Configure the phone's git app to respect `.gitignore` (most do).

**Other sync tools (secondary).** Syncthing works the same without commits: edit, save, and it syncs. Its conflict
copies (`launcher.sync-conflict-*.json`) are never read; merge them by hand. Cloud folders (Google Drive and similar)
work on a best-effort basis and can be slow, and first run warns when you pick one.

**No folder at all.** On first run you can choose *Skip for now*: the layout then lives only on the phone, and nothing
can edit it from outside until you choose a folder in *Settings → Config folder → Choose folder*.

### Schema
- JSON Schema 2020-12: [`schema/launcher-config.v1.schema.json`](schema/launcher-config.v1.schema.json). Full example:
  [`schema/examples/minimal.json`](schema/examples/minimal.json). Validate with the command in [`schema/README.md`](schema/README.md).
- JSON only (RFC 8259): no comments, no JSON5 or YAML. UTF-8. A BOM is accepted on read, but the launcher never writes one.

Minimal valid file:

```json
{
  "schemaVersion": 1,
  "revision": 1,
  "grid": { "columns": 4, "rows": 5 },
  "pages": [
    { "id": "home", "items": [
      { "id": "a-settings", "type": "app", "package": "com.android.settings", "x": 0, "y": 0 }
    ] }
  ],
  "dock": [
    { "id": "a-dialer", "type": "app", "package": "com.google.android.dialer" }
  ],
  "drawer": { "sort": "label", "hidden": [], "rules": [] }
}
```

Shape in brief:
- Required: `schemaVersion` (`1`) and `pages` (1–20). Optional: `revision`, `grid` (`columns`/`rows` 2–12, default
  4 × 5), `dock` (up to 7 items), `drawer`.
- Item types: `app`, `shortcut` (`package` + `shortcutId` of a pinned shortcut), and `folder` (`label` + `items` of apps
  and shortcuts, no nested folders).
- Page items need `x`/`y` (0-based, inside the grid; every item is 1 × 1). Dock and folder items must **not** have
  `x`/`y`: their order is the array order.
- `drawer.sort`: `label` or `installTime`. `drawer.hidden`: package names. `drawer.rules`: `{id, group, match}` where
  `match` uses `packages`, `packagePrefix`, `tag` or `category`. The first matching rule wins, and rules only group the
  drawer.
- Apps you don't place live **only in the drawer**. There's no Unsorted home page, and `unsorted.mode` is never
  acted on: `page` stays valid in the schema but behaves exactly like `drawer`. The drawer lists every app, including placed
  ones, minus `drawer.hidden`. Installed apps that you don't place are never written to the file by the launcher.

### IDs and profiles
- Every page, item and drawer rule has an `id` matching `^[a-z0-9][a-z0-9_-]{0,63}$`, **unique across the whole
  file**. IDs are how drags, conflict merges and agents refer to items, so keep them stable. Don't renumber them.
  - The launcher generates IDs like `a-7k2m9q`. Readable ones like `a-settings` are fine.
- Apps are identified by **separate fields**, not by the ID:
  - `package` (required), for example `com.android.chrome`.
  - `activity` (optional, full class name, no leading-dot shorthand). Omit it to use the app's default launcher activity.
    If a named activity disappears, the launcher falls back to the package's only launcher activity if there's exactly
    one, otherwise the item is dimmed.
  - `profile` (optional): `personal` (default) or `work`. Work-profile items are only written by the launcher when the
    user turns on *Include work apps in config* (default off).
- **Private space** apps are never written to the file and have no `profile` value. Numeric user IDs or serials never
  appear in the file.
- The same app may appear in several places (for example the dock and a page). Twice on one page or in one folder is
  only a warning.

### Rules the launcher follows (and expects)
- **Canonical formatting.** When the launcher writes (only after an edit on the phone, or *Upgrade now*), it
  reformats the whole file: 2-space indent, keys in schema order, `x-` keys after known keys, LF line endings, a trailing
  newline, UTF-8 without BOM. Your formatting and key order are not preserved. Emitting the same format yourself keeps
  git diffs small (encouraged, not required). A reload never triggers a write.
- **Unknown keys.** Keys starting with `x-` are allowed at the root, on pages and on items, and are kept verbatim (use
  `x-note` for notes). Any other unknown key makes the file invalid, so typos fail loudly instead of being silently dropped.
- **`revision`.** The launcher sets it to max seen + 1 on every write. Agents should bump it too, but nothing depends on
  it, and a missing `revision` is valid.
- **Conflicts.** The **content hash** decides what's new, not `revision`. Any file with different content from what the
  phone last applied is loaded, even with a lower `revision`.
  - If the phone has unsaved edits when your change arrives, it **merges automatically**: your file wins, except the
    items the user just touched, whose edits are replayed by ID onto your version. The phone shows
    `Layout updated from launcher.json. Kept your 2 changes.` with *Undo*. With *Ask before merging* on, the user
    picks `Use file` or `Keep mine` instead.
  - An edit whose item you deleted is skipped.
  - Changes that arrive during a drag wait until the drop.
- **How the launcher writes.** Android's folder access has no atomic replace, so it writes `.configlauncher/launcher.json.tmp`
  first and verifies it, then truncate-writes `launcher.json` and verifies that, restoring from the temp copy on failure.
  For a few milliseconds, a reader can see a partial file. **If you get a parse error reading `launcher.json`, wait a
  second and retry.**
- **What the launcher expects from your writes.** Write the whole file. The launcher waits until the size and modified
  time are stable for 300 ms before parsing, and a torn or half-synced file simply fails validation and is ignored
  (the phone keeps the last working layout).
- **What makes the file invalid:**
  - parse errors (bad syntax, duplicate keys in one object, non-UTF-8, NUL or lone surrogates)
  - a file over 2 MiB, or nested more than 8 levels
  - a missing or unsupported `schemaVersion` (a newer version is refused, never downgraded)
  - schema violations, including unsupported types such as `widget` or `text`
  - unknown non-`x-` keys
  - duplicate IDs
  - `x`/`y` outside the grid, or two page items in the same cell
  - a grid outside 2–12
  - exceeding the limits: 20 pages, 144 items per page, 2000 items, 48 per folder, 7 in the dock, labels 60
    characters, IDs 64, other strings 256, 16 `tags` per item. `x-` values count toward the
    2 MiB budget. The phone also refuses to save a layout that would be over 2 MiB (the edit stays on screen)
- **Not errors:** apps that aren't installed or shortcuts that aren't published. They show dimmed and are never deleted
  automatically. An older `schemaVersion` is migrated in memory and only written back on the next edit or *Upgrade
  now* (with a backup first).
- **How the app reacts to an invalid file:** the home screen keeps showing the last working layout. A banner says what's
  wrong, for example `launcher.json has 2 problems. Showing the last working layout.`, and the details list each
  problem with its JSON path (plus line and column for parse errors). If the user edits on the phone while the file is
  invalid, your broken file is first copied to `.configlauncher/broken/launcher.<timestamp>.json`, so your work is never
  lost, and then the edited layout is written to `launcher.json`.
- **Nothing in the file is ever executed.** There are no intents, URIs, actions or scripts. The launcher only launches
  installed apps (by package and activity) and pinned shortcuts.

### Files the launcher writes in your folder
`.configlauncher/` holds:
- `status.json`: the last load result (ok or the list of errors, the hash and the time) and any sync-conflict copies it
  noticed. **Read this to find out why your edit was rejected** without picking up the phone.
- `apps.json`: every launchable app the drawer shows (`package`, `activity`, `label`, `profile`, `installTime`), rewritten
  when apps are installed, removed or changed, and when *Include work apps in config*, *Include hidden apps in
  apps.json* or the hidden list changes. Use it to place apps by their real package and activity. Work apps are listed
  only when *Include work apps in config* is on. Apps hidden from the drawer are left out unless *Include hidden apps in
  apps.json* is on (off by default).
- `launcher.json.tmp` and `AGENTS.md.tmp`: the write-verify copies (above). `AGENTS.md` goes through the same temp,
  verify and restore path as `launcher.json`, so its `.tmp` stays too. Both are reused on the next write.
- `launcher-config.v1.schema.json`: an offline copy of the schema, so agents can validate without network. Replaced on
  app updates unless you edited it.
- `broken/`: copies of invalid files saved before a phone edit replaced them.
- `backup/`: originals saved before an *Upgrade now* (as `launcher.v{N}.{timestamp}.json`), *Restore last working version* or *Start fresh*,
  and your edited `AGENTS.md` before *Update agent instructions* replaces it (as `AGENTS.{timestamp}.md`).

Ignore the `.tmp` files, `status.json` and `apps.json` in git (see [the git workflow](#recommended-keep-the-folder-in-git)).

`launcher.json` is never created automatically. If it's missing, the phone shows `No launcher.json yet.` with
*Create from current layout* and *Reconnect*.

Sync conflict copies such as `launcher.sync-conflict-*.json` or `launcher (1).json` are never read, merged or deleted.
The phone shows a notice naming them, and they're listed in `status.json`.

### `.history/` is not used
There is **no `.history/` folder**. The launcher keeps the last 10 versions privately on the phone (*Settings → Restore
previous layout*), and that history isn't synced. For history on the computer side, use git or your sync tool's
versioning.

### Canonical details agents ask about (#76)
- **Order of `pages[].items[]`:** order doesn't change what's shown (`x`/`y` decide). The canonical order is row-major, by `y` then `x`, which is what the phone writes after any edit on that page. Write it that way to avoid diffs.
- **Key order in drawer rules:** canonical is `id`, `group`, `match` (the order of `properties` in the schema, not of `required`). Inside `match`: `packages`, `packagePrefix`, `tag`, `category`.
- **Untouched blocks and defaults:** omit blocks you don't use (`drawer`, `grid`, `revision` are all optional) and don't write default values you didn't set. The phone keeps whatever you wrote and adds a block only when an edit needs it (for example *Hide* creates `drawer.hidden`).
- **ID prefixes:** convention `a-` apps, `s-` shortcuts, `f-` folders, `r-` drawer rules, and readable page IDs like `home`. Any ID matching the pattern is valid; the convention just keeps files readable.

### Agent kit
- [`templates/config-folder/AGENTS.md`](templates/config-folder/AGENTS.md): copy this into your config folder. Agent tools (Claude Code, Codex, Cursor and others) read `AGENTS.md` automatically.
- [`skills/config-launcher/SKILL.md`](skills/config-launcher/SKILL.md): a reusable skill with the same procedure, for harnesses that support skills.
- [`AGENTS.md`](AGENTS.md) at this repo's root is for contributors to the app, not for layout editing.

### Dos and don'ts
**Do**
- Validate with `scripts/validate.py` before saving.
- Keep IDs stable and unique, and give new items new IDs.
- Use `package` (and `activity` only when you need a specific one).
- Put notes in `x-` keys.
- Pull before editing, validate, then commit and push (git).
- Write the whole file in one go.
- Retry a read that fails to parse.
- Check `.configlauncher/status.json` after a change.
  `revision` is the applied file's `revision` (null when the file was rejected); `hash` stays the authority.
- To reload immediately from a script (instead of waiting for the 2 s poll or pressing Home):
  `adb shell am broadcast -a dev.nasawa.configlauncher.RELOAD -n dev.nasawa.configlauncher/.ReloadReceiver`.
  It runs the same load as ⋮ → Reload and rewrites `status.json` before returning Result codes: `0` unchanged,
  `1` reloaded, `2` rejected (details in `status.json`), `3` unavailable, `4` busy (phone edit, drag, or conflict
  pending), `5` timeout (8 s). On `2`, read `status.json` and fix the listed paths; on `4`, back off and retry; on
  `3` or `5`, retry later. Only adb shell can send it (it requires `android.permission.DUMP`).

**Don't**
- Add comments, trailing commas, or keys without an `x-` prefix.
- Add `widget`/`text` items (not in v1), nested folders, or `x`/`y` on dock or folder items.
- Write private-space apps, user IDs or serial numbers.
- Write anything into `.configlauncher/`, or rely on it for history.
- Expect your formatting to survive a phone edit.
- Commit `.configlauncher/*.tmp` or `status.json`, commit conflict markers, or force-push.
- Copy unplaced apps into the file just to "list" them. The drawer already shows everything.

## Debug builds: signing key

Debug builds are signed with the committed, debug-only key `app/debug.keystore` (alias `androiddebugkey`, passwords
`android`), so a debug APK built on any machine or CI runner installs over the previous one and keeps the folder grant,
settings and home-app role. Certificate SHA-256:
`3a0cb6b59a1140b4b76d6a6fd70fbf3744f3174fd2479d89a3a148810cfd3dca` (CI checks it). Check an APK before installing with
`apksigner verify --print-certs app-debug.apk`.

This key is public and proves nothing about who built an APK. Never use it for release builds, and never commit a real
release key. Builds signed with the old per-machine keys (up to v0.0.1-dev2) need one uninstall before the first build
with this key.

## Debug builds: pushing a config in app-only mode

In app-only mode ("Skip for now"), launcher.json lives in app-private storage at
`files/config/source/launcher.json` (ARCHITECTURE.md §18.5). Only **debug** builds can be reached
from a computer, through `run-as`:

```sh
# push
adb shell run-as dev.nasawa.configlauncher mkdir -p files/config/source
adb shell run-as dev.nasawa.configlauncher sh -c "'cat > files/config/source/launcher.json.tmp && mv files/config/source/launcher.json.tmp files/config/source/launcher.json'" < launcher.json
# pull
adb exec-out run-as dev.nasawa.configlauncher cat files/config/source/launcher.json > launcher.json
```

Debug builds watch that directory with a FileObserver, so the home screen reloads straight away.
Release builds don't poll or watch it; connect a folder to edit from a computer.
