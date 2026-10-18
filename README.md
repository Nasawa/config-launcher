# Config Launcher

An Android home screen whose whole layout lives in one file, `launcher.json`, in a folder you choose. Rearrange apps
on your phone and the file updates. Edit the file on your computer (or let an agent edit it) and the phone updates.

- Kotlin + Jetpack Compose, Android 8.0+ (minSdk 26).
- No risky permissions: no storage, `QUERY_ALL_PACKAGES`, Accessibility or network access. You grant access to one
  folder through Android's system folder picker, and you can revoke it.
- Status: architecture and UX specs are done. See [ARCHITECTURE.md](ARCHITECTURE.md) and [docs/ux.md](docs/ux.md).

## Editing launcher.json (for agents and humans)

### Where the file lives
- `launcher.json` sits at the **root of the folder picked on first run** (Settings → *Config folder* changes it). A
  folder kept in sync with a computer (Syncthing, a git client) is the intended setup. Cloud providers such as Google
  Drive work on a best-effort basis, and first run warns when you pick one.
- Only the file named exactly `launcher.json` is read.
- The launcher also creates `.configlauncher/` in that folder (see [Files the launcher writes](#files-the-launcher-writes-in-your-folder)).

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
  "drawer": { "sort": "label", "hidden": [], "rules": [] },
  "unsorted": { "mode": "drawer" }
}
```

Shape in brief:
- Required: `schemaVersion` (`1`) and `pages` (1–20). Optional: `revision`, `grid` (`columns`/`rows` 2–12, default
  4 × 5), `dock` (up to 7 items), `drawer`, `unsorted`.
- Item types: `app`, `shortcut` (`package` + `shortcutId` of a pinned shortcut), and `folder` (`label` + `items` of apps
  and shortcuts, no nested folders).
- Page items need `x`/`y` (0-based, inside the grid; every item is 1 × 1). Dock and folder items must **not** have
  `x`/`y`: their order is the array order.
- `drawer.sort`: `label` or `installTime`. `drawer.hidden`: package names. `drawer.rules`: `{id, group, match}` where
  `match` uses `packages`, `packagePrefix`, `tag` or `category`. The first matching rule wins, and rules only group the
  drawer.
- `unsorted.mode`: `drawer` (default), `page` (also shown on a synthetic last page, never written) or `hidden`
  (removes only that page; the drawer still lists them). The drawer lists every app, including placed ones, minus
  `drawer.hidden`. Installed apps that you don't place are never written to the file by the launcher.

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
  - If the phone has unsaved edits when your change arrives, the user chooses `Use file` (your version wins) or `Keep
    mine`. `Keep mine` replays the phone's edits by ID onto **your** version, so your changes to other items survive.
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
- `launcher.json.tmp`: the write-verify copy (above).
- `broken/`: copies of invalid files saved before a phone edit replaced them.
- `backup/`: originals saved before an *Upgrade now* (as `launcher.v{N}.{timestamp}.json`), *Restore last working version* or *Start fresh*.

If the folder is a git repo, ignore the files that change on every phone write:

```gitignore
.configlauncher/launcher.json.tmp
.configlauncher/status.json
```

`backup/` and `broken/` are yours to commit or ignore.

`launcher.json` is never created automatically. If it's missing, the phone shows `No launcher.json yet.` with
*Create from current layout* and *Reconnect*.

Sync conflict copies such as `launcher.sync-conflict-*.json` or `launcher (1).json` are never read, merged or deleted.
The phone shows a notice naming them, and they're listed in `status.json`.

### `.history/` is not used
There is **no `.history/` folder**. The launcher keeps the last 10 versions privately on the phone (*Settings → Restore
previous layout*), and that history isn't synced. For history on the computer side, use git or your sync tool's
versioning.

### Dos and don'ts
**Do**
- Validate against the schema before saving.
- Keep IDs stable and unique, and give new items new IDs.
- Use `package` (and `activity` only when you need a specific one).
- Put notes in `x-` keys.
- Write the whole file in one go.
- Retry a read that fails to parse.
- Check `.configlauncher/status.json` after a change.

**Don't**
- Add comments, trailing commas, or keys without an `x-` prefix.
- Add `widget`/`text` items (not in v1), nested folders, or `x`/`y` on dock or folder items.
- Write private-space apps, user IDs or serial numbers.
- Write anything into `.configlauncher/`, or rely on it for history.
- Expect your formatting to survive a phone edit.
- Copy unplaced apps into the file just to "list" them. The drawer already shows everything.
