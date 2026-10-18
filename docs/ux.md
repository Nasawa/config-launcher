# Config Launcher — UX spec (P1, MVP)

Owner: Pragma (UX) · Builds from it: Sprite · Sources: [`ARCHITECTURE.md`](../ARCHITECTURE.md) (wins for behaviour),
[`schema/launcher-config.v1.schema.json`](../schema/launcher-config.v1.schema.json) and `docs/research-spike.md`.
This doc wins for wording. Aligned with ARCHITECTURE §16 (all 13 listed changes applied).

**Product in one line:** an Android home screen whose whole layout lives in one JSON file. Edits on the phone write
back to the file, and edits to the file show up on the phone.

**MVP scope (ARCHITECTURE §12):** home pages with an app grid and a dock, the app drawer (A–Z, hidden list, rule
groups), folders (one level), Unsorted modes, app and pinned-shortcut items (personal + work profiles), the file in a
user-picked SAF folder, live reload, write-back on move/add/remove/rename/folder create and merge, and validation with
error banners.
**Not in the MVP:** widgets, icon packs, AI/agent proposals (sketched in [Later](#11-later-reviewing-an-agents-proposed-change-not-in-the-mvp)),
notification badges, gestures beyond swipe-up.

Design principles:
1. **The file is the truth.** Every on-screen layout state can be written to the file, and every valid file can be shown on screen.
2. **Never lose a layout.** A bad file never blanks the home screen. The last good layout stays, and history snapshots exist (10, app-private).
3. **Never block the phone.** Errors appear as banners, never as modal dialogs over the home screen. Apps always launch.
4. **One gesture = one write.** No Save button.

The file: `launcher.json` at the root of the chosen folder, validated against
[`schema/launcher-config.v1.schema.json`](../schema/launcher-config.v1.schema.json) (full example:
[`schema/examples/minimal.json`](../schema/examples/minimal.json)).

```json
{
  "schemaVersion": 1,
  "revision": 42,
  "grid": { "columns": 4, "rows": 5 },
  "pages": [
    { "id": "home", "title": "Home", "items": [
      { "id": "a-chrome", "type": "app", "package": "com.android.chrome", "x": 0, "y": 0 },
      { "id": "f-travel", "type": "folder", "label": "Travel", "x": 1, "y": 0,
        "items": [ { "id": "a-maps", "type": "app", "package": "com.google.android.apps.maps" } ] }
    ] }
  ],
  "dock": [ { "id": "a-dialer", "type": "app", "package": "com.google.android.dialer" } ],
  "drawer": { "sort": "label", "hidden": ["com.example.bloat"], "rules": [] },
  "unsorted": { "mode": "drawer" }
}
```

Shape notes (ARCHITECTURE §3, §16):
- Every page, item and drawer rule has a stable opaque `id` (`^[a-z0-9][a-z0-9_-]{0,63}$`, unique in the file). Drags, conflict replay and agents refer to items by `id`. The launcher generates IDs like `a-7k2m9q`, and agents may use readable ones like `a-settings`.
- Apps carry `package` and an optional `activity` (full class name, no leading-dot shorthand; omit it to use the default launcher activity). An optional `profile` is `personal` (default) or `work`. Work apps are written only when **Include work apps in config** is on (default off). **Private-space apps are never written.**
- `shortcut` items: `package` + `shortcutId` (a pinned shortcut).
- Folders use `label` and hold apps and shortcuts only, with no nested folders. Folder and dock children have no `x`/`y`; their order is the array order.
- Pages need an `id`, and `title` is optional. Page items need `x`/`y`. Every item is 1 × 1.
- `drawer.hidden[]` holds **package names** (hiding is per app). `drawer.sort` is `label` (default) or `installTime`. `drawer.rules[]` group apps in the drawer by `packages`, `packagePrefix`, `tag` or `category` (first match wins).
- `unsorted.mode`: `drawer` (default), `page` (also show unplaced apps on a synthetic last page, which is never written) or `hidden`. The starter layout uses `drawer`.
- Unknown keys are allowed only with an `x-` prefix (preserved verbatim, e.g. `x-note`). Any other unknown key makes the file invalid. JSON has no comments.

---

## 1. Home screen

### 1.1 Layout and wireframe

```
┌──────────────────────────────────────┐  status bar (system, transparent)
│ ┌──────────────────────────────────┐ │
│ │ ▲ Can't load launcher.json. ...  │ │  banner slot (only when needed, §6)
│ └──────────────────────────────────┘ │
│                                      │
│   [◉]      [◉]      [▣]      [◉]     │  row 0     ◉ app icon  ▣ folder
│  Chrome   Maps    Travel   Camera    │
│                                      │
│   [◉]      [◉]      [◌]              │  row 1     ◌ app not installed (dimmed)
│  Photos  Signal   Banking            │
│                                      │
│                                      │  rows 2–4 (empty cells are just space)
│                                      │
│                                      │
│                ● ○ ○                 │  page indicator (hidden if 1 page)
│ ──────────────────────────────────── │
│   [◉]      [◉]      [◉]      [◉]     │  dock (no labels)
└──────────────────────────────────────┘  gesture/nav bar (system, transparent)
```

| Element | Spec |
|---|---|
| Grid | `grid.columns` × `grid.rows`, default **4 × 5**. The file allows **2–12 × 2–12** (validation rule). The Settings picker offers a narrower 3–6 × 4–7 (UX choice only); a file outside the picker range still renders, and the picker then shows the file's value. Cells fill the space between the banner slot and the page indicator evenly |
| Cell | Icon **56 dp** centred, label below. **Minimum cell 64 × 80 dp**. If the screen can't fit the configured rows at that size, labels are hidden first, then icons shrink to 48 dp; the layout is never clipped |
| Label | 12 sp, 1 line, ellipsized at the end, centred, white with a soft shadow for wallpaper contrast (§12) |
| Pages | Horizontal pager. One page minimum. The page indicator shows only with ≥ 2 pages. Dots are 8 dp, current = filled |
| Dock | `dock[]` (a plain array, left to right), **0–7 items** (schema max), same icon size, **no labels**, fixed on all pages. The starter layout fills 4. Above 5 items, icons shrink to fit the width (min 48 dp). A 1 dp divider (white 24 %) above it |
| Wallpaper | System wallpaper, shown through (`windowShowWallpaper`) |
| Density | Uses dp/sp only. Phones only in MVP. Landscape: same grid, rotated pager, dock moves to the right edge (Q-S2) |

Gestures and taps (home):

| Action | Result |
|---|---|
| Tap app | Launch via `LauncherApps.startMainActivity` |
| Tap folder | Open folder (§3) |
| Tap dimmed item | Nothing launches. A hint sheet says why: `Not installed`, `Shortcut no longer available`, `Work apps are paused`, `Turn on "Include work apps in config" to use this.` (tap → system unpause prompt) or `Set as Home to use shortcuts`, with **`Remove from layout`** (one write). `Open store page` comes later |
| Long-press app, shortcut or folder | Item sheet showing the **real app name and package** (so a label override can't disguise an app), plus `Edit layout`, `App info`, `Remove from home` |
| Long-press empty space | Home menu: `Edit layout`, `Reload layout`, `Settings` |
| Swipe up anywhere / tap ⌃ handle | Open the drawer (§2) |
| Swipe left/right | Change page |
| Home button while on home | Go to page 1, close drawer/folder |

### 1.2 Reconciliation with installed apps (spike §4)
- Entries for **uninstalled or unknown** apps, unpublished shortcuts and paused or removed work apps show as dimmed
  tiles (40 % opacity, `◌`) with their label override or package name. They are **never deleted automatically**.
- An `app` with an `activity` that no longer exists falls back to the package's only launcher activity, if there is
  exactly one (the file is left unchanged), and is dimmed if there are several (ARCHITECTURE OQ-27).
- Installed apps that are placed nowhere are **Unsorted**: per `unsorted.mode` they appear in the drawer (default), also
  on a synthetic last page titled `unsorted.pageTitle` (default `Unsorted`). `hidden` only removes that page; the drawer still lists them (ARCHITECTURE §17 Q11). Unsorted apps are never written
  to the file. Only an explicit drag places them.
- Work-profile apps show the system work badge. With **Include work apps in config** off (default) they appear only in
  the drawer and **can't be placed** on pages, the dock or folders (drag is refused, see §4.2). Work items **already in
  the file** while the setting is off render greyed with the work badge. Tapping one shows the hint
  `Turn on "Include work apps in config" to use this.` They aren't invalid and are never deleted. Turning the setting on
  makes them live without a write (§17 Q12).
- Private-space apps appear only in the system-style private-space section at the bottom of the drawer while it's
  unlocked. They're never placed, never written, never shown as Unsorted, and not affected by `drawer.hidden`. They
  **can't be dragged** out of that section, and long-press offers only the system actions (§17 Q13).
- Labels: bidi and zero-width control characters are stripped at render time (the file is left as written).

---

## 2. App drawer

```
┌──────────────────────────────────────┐
│ ┌──────────────────────────────┐ [⋮] │  search field + menu
│ │ 🔍 Search apps               │     │
│ └──────────────────────────────┘     │
│  [Personal]  [Work]                  │  tabs, only if a work profile exists
│   [◉]      [◉]      [◉]      [◉]     │
│  Authy    Bank     Calc    Camera    │  A–Z, 4 columns (= grid.columns)
│   ...                                │
└──────────────────────────────────────┘
```

| Item | Spec |
|---|---|
| Open / close | Swipe up from home, or tap the handle. Close: swipe down at the top of the list, Back, or Home |
| Search | Field at top, hint `Search apps`. **The drawer opens with the search field focused and the keyboard up** (Q-C2, ARCHITECTURE §18.2). Back or swipe-down with the keyboard up hides the keyboard first; a second Back closes the drawer. Setting `Open keyboard with drawer` (default on) turns this off, e.g. for wall displays. Filters live on each keystroke: case- and accent-insensitive match on the label start or word start, then anywhere. Enter launches the first result. No results: `No apps match "xyz".` |
| Sorting | `drawer.sort`: `label` (default, A–Z with locale-aware `Collator`, numbers first) or `installTime` (newest first). Not editable in the UI in the MVP |
| Groups | `drawer.rules[]` create titled groups (the rule's `group`) at the top of the list, in rule order, each sorted as above. An app matching no rule is listed under `All apps`. Rules never affect home pages |
| What's listed | **Every launchable app** in the personal profile (and the work profile, badged), **including apps already placed** on pages, the dock or in folders, minus `drawer.hidden` (§17 b). Private space has its own section (§1.2) |
| Hidden apps | Package names in `drawer.hidden[]`. Not shown in the list, search or Unsorted. Long-press a drawer app → `Hide` (one write). Hidden apps are listed in **Settings → Hidden apps**, where `Unhide` is one write. A hidden app placed explicitly on a page still shows and launches there |
| Long-press app | `Add to home` (first free cell, page 1 onward, else a new page), `Hide`, `App info`. Dragging out of the drawer enters edit mode on home (§4). Work apps offer `Add to home` only when **Include work apps in config** is on |
| Menu `⋮` | `Settings` |

---

## 3. Folders

- Defined as `type: "folder"` with `label` and `items[]` (apps and shortcuts only, max 48; no nested folders). Children have no `x`/`y`.
- Tile: 56 dp rounded square showing the first 4 app icons in a 2 × 2 grid, label = `label`.
- **Open:** a centred card over a dim scrim (50 % black). Title = folder `label`, then a grid of 4 columns (3 if
  `grid.columns` is 3). It scrolls if it has more than 4 rows. Tap outside, Back or Home closes it.
- **Create:** in edit mode, drop an app onto another app → a folder named `Folder` (localised), opened with the name
  field focused. One write.
- **Rename:** in edit mode, tap the folder title. A text field, max 60 characters (schema limit). Empty → reverts to the previous name. Written on Done or close.
- **Add/remove:** drag into or out of the open folder (edit mode). A folder with **one** app left stays a folder. A folder with **zero** apps is deleted.
- Dock folders are allowed (same behaviour).

---

## 4. Edit mode and drag

### 4.1 Entering and leaving
- Enter: `Edit layout` from any long-press menu, or long-press and **drag** an item (drag starts after 300 ms + 8 dp of movement).
- On screen: a top bar replaces the banner slot: `Editing layout` on the left, **`Done`** button on the right. Every
  cell shows a faint outline (white 16 %), so empty cells are visible. No jiggle animation (§12 reduced motion).
- Leave: `Done`, Back, Home, or 30 s with no interaction.
- Edit mode **stays available while the file is invalid** (ARCHITECTURE OQ-15). The first edit copies the broken file
  to `.configlauncher/broken/launcher.<timestamp>.json` in the folder, then writes the edited last working layout to
  `launcher.json`. Snackbar: `Your broken launcher.json was saved to .configlauncher/broken/.` B2 shows until that write succeeds.

### 4.2 Drag targets

| Drop on | Result |
|---|---|
| Empty cell | Move there |
| Occupied cell (app) | Create a folder (§3) |
| Folder | Add to that folder |
| Screen edge (hold 600 ms) | Turn to the previous/next page. Past the last page → creates a new empty page |
| Dock slot | Move into the dock (if 7 are full, the drop is refused with a short shake, or a red outline with reduced motion) |
| Any target, dragging a work app with **Include work apps in config** off | Refused, with snackbar `Turn on "Include work apps in config" in Settings to place work apps.` |
| `Remove` target (top bar, appears while dragging) | Remove from home. The app stays installed and in the drawer |
| `App info` target | Opens system app info, and the item doesn't move |

There's no automatic reflow: other items never move to make room (keeps the file diff minimal and predictable). An
empty page is deleted when you leave edit mode.

### 4.3 How each edit writes back

Each finished gesture (drop, rename, hide, remove, add, folder create or merge) applies immediately on screen and is
queued as an ID-based operation (`Move`, `Add`, `Remove`, `Rename`, `CreateFolder`). Writes follow ARCHITECTURE §5:

1. **Debounce:** the write happens **750 ms** after the last edit, and never during an active drag (drop starts the
   timer). A burst of rearranging becomes one write.
2. **Check for an external change:** re-read `launcher.json` and compare its hash with the base hash (the version the
   screen was built from). Different → conflict (§4.4). Don't write.
3. **Serialize canonically:** 2-space indent, keys in schema order, `x-` keys after known keys (preserved verbatim), LF,
   trailing newline, UTF-8 without BOM. Hand formatting and key order are **not** preserved. Bump `revision` (max seen + 1).
4. **Write safely (no SAF rename is relied on):**
   1. Write `.configlauncher/launcher.json.tmp` in the folder, and read it back to verify the hash.
   2. Truncate-write `launcher.json` (`"wt"`), and read it back to verify.
   3. On failure, restore `launcher.json` from the temp copy. If that also fails → banner B5 with `Retry`. The edit stays
      on screen and in the app's last working copy.
5. Remember the hash, so our own write never triggers a reload. Update the app-private last working copy. The previous
   version goes to app-private history (last **10**), which is recoverable in Settings → `Restore previous layout`.

Success is silent (no "saved" toast). A reload never causes a write.

### 4.4 External changes and conflicts

| Situation | Behaviour | Copy |
|---|---|---|
| File changed, valid, **not** in edit mode | Reload and re-render in place (no page jump; the open drawer or folder stays open if it still exists) | Snackbar (3 s): `Layout updated from launcher.json.` |
| File changed while **dragging** | Hold the reload until the drag ends, then run the conflict check | — |
| File changed **and** we have a pending edit (hash mismatch at step 2) | Nothing is overwritten. Banner B6 | `launcher.json changed while you were editing.` Actions: **`Use file`** (discard my pending edits and load the file; my version is saved to history) · **`Keep mine`** (replay my edits by ID onto the file's version; the file's version is saved to history first) |
| `Keep mine` and a target item was deleted in the file | That edit is skipped | Snackbar: `1 change skipped because the item was removed in the file.` [`Undo`] |
| `Keep mine` and the target cell is taken in the file | Placed in the nearest free cell on the same page, else the first free cell, else a new page | — |
| File changed and is invalid | §6 error banner (B1/B2). Editing still works (§4.1) | see §6 |

Both versions always survive in app-private history (Settings → `Restore previous layout`), so neither button loses data. `Keep mine` replays edits instead of overwriting, so an agent's changes to other items are kept. The content hash, not `revision`, decides what is new: a file with a lower `revision` but different content is still applied.

---

## 5. Reload

- Automatic: polling every **2 s while the home screen is visible**, and immediately on every return to home (`onResume`). A ContentObserver is only a hint that triggers an immediate poll. No polling in the background. Before parsing, the app waits 300 ms and checks the file is stable (handles a sync client mid-write).
- Targets: a change appears within 1 s of pressing Home after the file lands, or within 3 s while the home screen is showing.
- Sibling conflict copies (`launcher*.sync-conflict-*`, `launcher (n).json`) are never read. A dismissible notice names them: `Sync conflict copies found: launcher.sync-conflict-…json. Only launcher.json is used.`
- Manual: long-press empty space → `Reload layout`.
- A reload with an unchanged hash does nothing. A reload never moves the user to another page or closes the drawer unless the open item no longer exists.

---

## 6. Problems: invalid config, schema errors, missing access

Rule: **keep showing the last good layout**, show a **non-blocking banner** at the top of the home screen (below the
status bar, above row 0), and offer a detail view. Apps keep launching. Editing still works (§4.1). At cold start the
app's last working copy is drawn first and replaced when the folder read finishes. With no last working copy, the
built-in default is shown: one empty page, the dock filled via role queries (phone, messages, browser, camera) and all
apps in the drawer.

### 6.1 Banner
- Full-width card, 12 dp margin, amber ▲ icon (error red ■ for access loss), 1–2 lines of text, one or two text buttons, and `×`.
- `×` collapses it to a small ▲ chip in the top-right corner that reopens it. It stays until the problem is fixed (no auto-dismiss).
- When fixed (a valid version appears): the banner clears automatically and the snackbar `Layout loaded.` shows.
- TalkBack: announced once when it appears (polite live region).

| ID | Cause | Banner text (exact) | Buttons |
|---|---|---|---|
| **B1** | JSON parse error (syntax, duplicate key, bad encoding, over 2 MiB or too deeply nested, a NUL character) | `Can't read launcher.json. Showing the last working layout.` | `Details` |
| **B2** | Validation errors (schema: wrong type, unknown non-`x-` key, unsupported `type` such as `widget`, missing `schemaVersion`; plus duplicate IDs, overlapping or out-of-range cells, grid outside 2–12) | `launcher.json has 3 problems. Showing the last working layout.` (`1 problem` singular) | `Details` |
| **B3** | Folder access lost or folder gone: the persisted grant is missing, or querying the folder itself fails | `Can't reach your config folder. Showing the last working layout.` | `Reconnect` (re-pick the folder) only, since nothing can be created there (§17 AQ-8) |
| **B4** | Folder readable, no `launcher.json` in it. Never auto-created | `No launcher.json yet.` | `Create from current layout` · `Reconnect` (wrong folder picked, or the sync app hasn't delivered the file yet). Not cleared by our own create alone; it clears when a valid file appears (§17 AQ-8, a) |
| **B5** | Save failed (§4.3) | `Couldn't save your change.` | `Retry` |
| **B5** (size) | The serialized layout would be over 2 MiB, so the writer refuses (§17 Q16). The edit stays on screen, in memory | `Couldn't save your change: launcher.json would be over 2 MB.` | `Retry` (after removing items or `x-` data) |
| **B6** | Conflict (§4.4) | `launcher.json changed while you were editing.` With more than one pending edit: `launcher.json changed while you were editing (2 changes).` | `Use file` · `Keep mine` (only `Use file` if the replayed result fails validation). Edits made while B6 is up join the queue and stay on screen (§17 Q8) |
| **B7** | `schemaVersion` newer than supported | `This file needs a newer Config Launcher.` | `Details` |
| **B8** (notice, lowest priority: shown only when no B1–B6 banner is up, and below them) | `schemaVersion` older: migrated in memory, not yet written | `launcher.json uses an older format (v{N}). It will be upgraded on your next change.` | `Upgrade now` (saves the original to `.configlauncher/backup/launcher.v{N}.{timestamp}.json`, then writes through the normal path with the hash check and B6) · `✕` (dismissed for that file hash; it returns only for a different older-format file. The next edit still upgrades, with a backup) |
| — (notice) | Sync conflict copies next to the file (§5) | `Sync conflict copies found: <names>. Only launcher.json is used.` | `×` |

`Create from current layout` writes the layout currently on screen (last working copy, or the built-in default) to
`launcher.json`. Unknown packages and unpublished shortcuts are **not** errors (§1.2). Duplicates of the same app twice
on one page or in one folder are logged as warnings only, with no banner. The last load result (ok or errors, hash,
time) is also written to `.configlauncher/status.json`, so an agent can see why its edit was rejected.

### 6.2 Error detail view
Full-screen page, opened from `Details`.

```
┌──────────────────────────────────────┐
│ ←  Layout file problems              │
│ launcher.json · checked 10:42        │
│──────────────────────────────────────│
│ ■ Line 12, column 7                  │
│   Expected "," or "}" after a value. │
│    11 │   "x": 1,                    │
│  ▶ 12 │   "y": 0                     │
│    13 │   "type": "app"              │
│──────────────────────────────────────│
│ ■ Line 30, column 15                 │
│   pages[0].items[3].x must be 0–3    │
│   (the grid has 4 columns).          │
│──────────────────────────────────────│
│ [ Copy errors ]  [ Reload ]          │
│ [ Restore last working version ]     │
└──────────────────────────────────────┘
```

- Each problem: `Line L, column C` (1-based, for parse errors and where the position is known), a plain-language message, the JSON path for validation errors, and a 3-line monospace excerpt with the line marked `▶`. Syntax errors stop at the first error. Schema errors list **all** of them, max 50, then `…and 12 more.`
- `Copy errors`: plain text with all problems and the file name, for pasting to an agent or chat.
- `Reload`: re-read the file now.
- `Restore last working version`: confirm dialog `Replace launcher.json with your last working layout? The current file is saved to .configlauncher/backup/ first.` [`Cancel`] [`Replace`].
- Back (`←`) dismisses the sheet. The banner stays until the file is valid.

### 6.3 Error message wording (examples)

| Problem | Message |
|---|---|
| Syntax | `Expected "," or "}" after a value.` · `Unexpected end of file.` · `Text must be in double quotes.` |
| Range | `pages[0].items[3].x must be 0–3 (the grid has 4 columns).` |
| Overlap | `pages[0].items[3]: cell (2,0) already used by "a-settings".` |
| Duplicate ID | `pages[1].items[0].id "a-chrome" is already used at dock[2].` |
| Unknown key | `pages[0].items[1]: unknown key "actoin". Custom keys must start with x-.` |
| Dock | `dock has 8 items; the maximum is 7.` |
| Dock position | `dock[1] must not have x or y.` |
| Type | `pages[0].items[0].type "widget" isn't supported yet. Use app, shortcut or folder.` |
| Missing | `pages[0].items[4] needs an "id".` |
| Schema version (B7) | `This file uses schemaVersion 2, but this Config Launcher understands up to 1. Update the app.` |
| Missing version | `"schemaVersion" is required.` |

---

## 7. First run: choosing the config folder (SAF)

Shown on first launch, before the home screen. Each step is one full screen with one primary button (ARCHITECTURE §4, §8).

1. **Welcome**
   - Title: `Your home screen, as a file`
   - Body: `Config Launcher keeps your whole layout in one file, launcher.json. Change it here or in any editor, and both stay in sync.`
   - Button: `Get started`
2. **Pick a folder**
   - Title: `Choose your config folder`
   - Body: `Pick a folder for launcher.json. A folder that a sync app (like Syncthing) or a git app keeps in sync lets you edit it from your computer, with history. Config Launcher also keeps a small .configlauncher folder there for status and backups.`
   - Button: `Choose folder` → system picker (`ACTION_OPEN_DOCUMENT_TREE`). Take a persistable read+write grant.
   - Picker cancelled → stay on this screen, no message.
   - Secondary button `Skip for now` (Q-C1, ARCHITECTURE §18.2): uses app-only storage on the phone and lands on the home screen. Note under the buttons: `Skipping keeps your layout on this phone only. You can choose a folder later from the ⋮ menu.`
   - Choosing a folder later (⋮ → `Pick config folder…`): no `launcher.json` there → the current layout is written there. A file exists → dialog `This folder already has a launcher.json.` with `Use the folder's layout` (current layout kept in history) · `Replace it with mine` (folder file copied to `.configlauncher/backup/` first). ⋮ → `Use phone storage` copies the current layout to the phone file and releases the folder grant.
   - **Cloud folder** (the picked folder's provider isn't local storage, `com.android.externalstorage.documents`): a soft warning before continuing: `Cloud folders can take a while to sync. A Syncthing or git folder works best.` Buttons: `Use anyway` · `Choose another folder`. Not blocked (§17 Q15).
3. **Folder result**
   - **Contains a valid `launcher.json`:** `Found launcher.json with 3 pages and 24 items.` Buttons: `Use this layout` · `Start fresh` (the existing file is first saved to `.configlauncher/backup/`).
   - **Contains an invalid `launcher.json`:** `launcher.json in this folder has problems.` Buttons: `See problems` (§6.2) · `Start fresh` (backup first, as above) · `Choose another folder`.
   - **No file:** `There's no launcher.json here yet. Create one from a starter layout?` Button: `Create from current layout`. The starter is the built-in default: one empty page, a dock with phone, messages, browser and camera when they can be found, every other app in the drawer (`unsorted.mode: "drawer"`).
   - Shown under all three: `When you edit on your phone, launcher.json is rewritten in a standard format. Comments aren't supported; use "x-note" keys for notes.`
4. **Make it your home app**
   - Title: `Set as home app`
   - Body: `To use Config Launcher when you press Home, set it as your default home app. Shortcuts only work once it's your home app.`
   - Button: `Set as home app` → `RoleManager.createRequestRoleIntent(ROLE_HOME)` on Android 10+, or the system Home settings on Android 8–9. Secondary: `Not now`.
   - If declined: the app still works as a normal app, minus shortcuts, which show dimmed with `Set as Home to use shortcuts`. Settings shows `Not your home app` with a `Set as home app` button.
5. Land on the home screen. One-time hint chip: `Long-press empty space to edit your layout.` (dismiss ×, never shown again).

**Lost access or missing file (B3/B4):**
- `Reconnect` reopens the picker. Picking a folder with a valid file loads it. Picking one without a file offers `Create from current layout`, which uses what's on screen now (not the starter).
- Lost access is detected on every start (persisted grants are checked), on resume, and on any failed read or write.

---

## 8. Settings (MVP, minimal)

| Setting | Spec |
|---|---|
| `Config folder` | Folder name and last load time, `Change folder`, `Reload layout` |
| `Restore previous layout` | Lists the app-private history (last 10, newest first, with time). Picking one writes it to `launcher.json` like any edit (hash check, safe write) |
| `Include work apps in config` | Switch, default **off**. Off: work apps show in the drawer with the badge but can't be placed, and nothing new about them is written. Work items already in the file stay, greyed, with the hint in §1.2. Turning it on makes them live without a write (§17 Q12) |
| `Hidden apps` | The packages in `drawer.hidden`, each with `Unhide` (one write) |
| `Home app` | Status + `Set as home app` |
| `Grid` | Columns/rows pickers (3–6 × 4–7). They write `grid` like any edit. Shrinking that would push items off-grid is refused with `Move apps out of the last column first.` |
| `About` | Version and licenses. No Kvaesitso code is copied (ARCHITECTURE §13) |

---

## 9. Feedback summary (snackbars and toasts)

| When | Text |
|---|---|
| External reload (also when a drag is cancelled while a new file is waiting, §17 Q6) | `Layout updated from launcher.json.` |
| Backup copy couldn't be updated (app storage full; the folder write succeeded). One time, retried on the next load or write (§17 Q4) | `Couldn't update the backup copy (phone storage full).` |
| Work item tap while the opt-in is off | `Turn on "Include work apps in config" to use this.` |
| Problem fixed | `Layout loaded.` |
| Dimmed item tap | Hint sheet (§1.1): `Not installed` / `Shortcut no longer available` / `Work apps are paused` / `Set as Home to use shortcuts`, with [`Remove from layout`] |
| First edit while the file is invalid | `Your broken launcher.json was saved to .configlauncher/broken/.` |
| `Keep mine` skipped an edit | `1 change skipped because the item was removed in the file.` [`Undo`] (plural: `3 changes skipped…`). A folder whose members are mostly gone: `Folder "<Label>" was skipped because its apps were removed in the file.` (§17 Q9: a partial folder is built from the remaining members; with fewer than 2, the remaining one is placed as a plain item) |
| Work app drag refused | `Turn on "Include work apps in config" in Settings to place work apps.` |
| Removed from home | `Removed <Label> from home.` [`Undo`] (Undo = a second write restoring it) |
| Hidden | `<Label> hidden from the app list.` [`Undo`] |

---

## 10. Copy style guide

- Plain, calm, short. Sentence case. Second person only when needed ("your layout").
- Always name the file exactly: `launcher.json`, in code style where possible.
- Say **layout**, **config folder** (the architecture's and Settings' name for the picked folder), **app list** or
  **drawer**, and **home screen**. Avoid "URI", "SAF", "schema" (except the `schemaVersion` messages), "JSON path" in
  body text (paths appear only inside error messages). File and folder names are exact: `launcher.json`, `.configlauncher/`.
- Errors say what happened + what still works + what to do: `Can't read launcher.json. Showing the last working layout.`
- No exclamation marks, no "Oops", no blame.
- Line and column are 1-based and always written `Line 12, column 7`.
- Buttons are verbs: `Choose folder`, `Reconnect`, `Retry`, `Use file`, `Keep mine`, `Done`.
- All strings in `strings.xml`, with plurals via `<plurals>` (`1 problem` / `3 problems`).

---

## 11. Later: reviewing an agent's proposed change (NOT in the MVP)

For a future exported intent or a companion CLI (ARCHITECTURE §12 Later: "propose-and-approve diffs for on-device agents"; spike §3 option 3, Mouture-style).
The MVP doesn't need it: an agent edits the synced file directly, and the normal reload and errors apply.

- The agent submits a full proposed `launcher.json` (or a patch), plus a short `summary`.
- A notification: `<Agent> suggests a layout change.` Tap → **Review** screen:
  - Header: the summary text, the agent name, the time.
  - **Visual diff:** home preview with added (green +), moved (blue →) and removed (red −) items, page by page.
  - Toggle `Show JSON diff`: a unified diff, monospace, with +/− lines.
  - Validation runs first. An invalid proposal shows its problems (§6.2 format) and only `Reject`.
  - Buttons: **`Apply`** (a normal write: hash check, safe write, history snapshot, `revision` + 1) · **`Reject`** (nothing written; the agent is told `rejected`).
- If the file's hash changed since the proposal was made: `This suggestion is based on an older layout.` → only `Reject` or `Review again` (rebase is the agent's job).
- Never auto-apply. No allow-list of "trusted" agents in v1 of this feature.

---

## 12. Accessibility

| Area | Spec |
|---|---|
| TalkBack labels | App: `<Label>`. Work app: `<Label>, work`. Dimmed: `<Label>, not installed` (or `, shortcut unavailable` / `, work apps paused`). Folder: `<Label> folder, 5 apps`. Dock item: `<Label>, dock`. Empty cell in edit mode: `Empty, column 2, row 3`. Page indicator: `Page 2 of 3` |
| Actions | Every drag action also has custom accessibility actions on the item (`Move to…` with a page/row/column picker, `Add to folder…`, `Move to dock`, `Remove from home`, `Hide`), so the layout is fully editable without drag. Each is one write |
| Edit mode | Announce `Editing layout. Double-tap Done when finished.` Focus order: top bar, then grid in reading order, then dock |
| Banners | Polite live region, announced once. Buttons are focusable. The error detail view reads as a list of problems |
| Touch targets | ≥ **48 × 48 dp** for everything (cells 64 × 80 dp, dock slots 64 dp, chip ≥ 48 dp hit area, banner buttons 48 dp tall) |
| Font scaling | Labels and text use sp and follow the system font size up to 200 %. Grid labels stay 1 line and ellipsize; at ≥ 150 % labels may be hidden on the home grid (still spoken by TalkBack) if cells can't fit them. The drawer always shows labels (wrapping to 2 lines). Banners and settings wrap, never truncate |
| Contrast | Text in app UI (drawer, settings, banners, detail view) ≥ 4.5:1 and icons/controls ≥ 3:1, in light and dark (follow the system theme; Material 3 dynamic colour off for status colours). Home labels over wallpaper: white text with a 1 dp 50 % black shadow, plus possibly a `Label backdrop` scrim setting (Q-C4, not in the MVP settings) |
| Reduced motion | Respect `Settings.Global.ANIMATOR_DURATION_SCALE == 0` and "Remove animations": no page-slide spring, folder open as a fade, no shake (use a red outline), no jiggle ever |
| Colour | Status is never colour-only: ▲ warning / ■ error glyphs, dimmed tiles also say "not installed" to TalkBack |

---

## 13. Build checklist (Sprite)

- [ ] Home: pager + grid (`grid.columns/rows`, default 4 × 5, file allows 2–12) + dock (0–7), sizes from §1.1, wallpaper through.
- [ ] Dimmed tiles and reconciliation (§1.2): not installed, unpublished shortcut, paused work apps, activity fallback, Unsorted modes (incl. the synthetic page), private space never placed. Never auto-delete.
- [ ] Item sheet shows the real app name and package. Strip bidi and zero-width characters from labels at render.
- [ ] Drawer: search (§2 match rules, keyboard up on open unless `Open keyboard with drawer` is off), `drawer.sort` (label or installTime), `drawer.rules` groups, hidden apps section (package names), work tab.
- [ ] Folders: `label`, create/rename/add/remove/delete rules (§3), open card, max 48.
- [ ] Edit mode: entry/exit, outlines, drag targets (§4.2), no reflow, 30 s idle exit, **allowed while invalid** with the broken-copy snackbar, work-app refusal when the opt-in is off.
- [ ] Write path (§4.3): ID-based ops → 750 ms debounce (never mid-drag) → hash check → canonical serialize (`x-` keys kept) → temp write + verify → `"wt"` write + verify → restore on failure → remember hash, history (10). B5 on failure.
- [ ] Conflict (§4.4): hold during drag, B6 `Use file` / `Keep mine` (replay by ID, skipped-op snackbar with Undo, nearest free cell).
- [ ] Reload (§5): 2 s poll while visible + immediate on resume, ContentObserver as a hint, 300 ms stability check, hash decides, no page jump, sync-conflict notice.
- [ ] Banners B1–B8 (§6.1), collapse chip, live region, auto-clear. Detail view (§6.2) with 1-based line/column, paths and excerpts; Copy errors; Restore with confirmation (backup to `.configlauncher/backup/`).
- [ ] First run (§7) with exact copy, persistable grant, three folder outcomes, format note, home role request (RoleManager on 10+, Home settings on 8–9), one-time hint.
- [ ] Settings (§8): Config folder, Restore previous layout, Include work apps in config, Home app, Grid, About.
- [ ] Snackbars (§9) with Undo where listed.
- [ ] Accessibility (§12): labels, custom actions for every drag action, 48 dp targets, font scaling, reduced motion.
- [ ] All copy in `strings.xml` with plurals.
- [ ] Manual tests: edit the file on a PC via Syncthing while the phone is on home, in edit mode, and mid-drag. Corrupt the file, then edit on the phone (broken copy). Revoke access in system settings. Delete the file. Newer `schemaVersion`. Font 200 %. TalkBack-only layout edit.

---

## Open questions for Christopher

The architecture proposes answers to some of these (noted). They stay here until you confirm.

- **Q-C1** *(Answered §18.1: yes, built.)* Onboarding requires picking a folder (architecture). Do you also want a "skip for now" path using app-only storage (no external editing)? Not in the architecture today.
- **Q-C2** *(Answered §18.1: keyboard up, with a setting to turn it off.)* Should the drawer open with the keyboard up (search-first, like KISS) or without it (the spec's default)?
- **Q-C3** Unplaced apps: the architecture defaults to the drawer (`unsorted.mode: "drawer"`), with an optional synthetic `Unsorted` page (`"page"`). Is the drawer the right default for you? (Same as ARCHITECTURE §14 Q3.)
- **Q-C4** Home labels: plain white with a shadow (spec), or an optional dark backdrop behind labels for busy wallpapers?
- **Q-C5** The architecture chose JSON only (no comments; use `x-note` keys). OK for you? (Same as ARCHITECTURE §14 Q2.)
- **Q-C6** History: the architecture keeps the last 10 versions in app-private storage (not in your folder) and relies on git/Syncthing for the PC side. Enough?
- ~~Q-C7~~ Resolved by ARCHITECTURE §17 Q12: work items stay in the file, greyed with a hint, never deleted. They go live again when the setting is turned on.
- Also open in ARCHITECTURE §14: sync tool, `.configlauncher/status.json` in your folder, work profile/private space use, distribution, your phone model, and widgets after the MVP.

## UI questions for Sprite

- **Q-S1** Compose drag-and-drop across pager pages and into the dock: is `Modifier.dragAndDropSource/Target` enough, or a custom pointer-input layer?
- **Q-S2** Landscape: support it in the MVP (dock on the right) or lock portrait?
- **Q-S3** The architecture picks `kotlinx.serialization` (plus a `JsonElement` tree). Does it give line/column for parse errors, and positions for validation paths, or do we need a small tokenizer for the excerpts?
- **Q-S5** Fitting cells at 200 % font scale on small phones: confirm the label-hiding fallback is acceptable visually.
- **Q-S6** Dock with 6–7 items: is shrinking icons to a 48 dp minimum readable, or should the UI cap drags at 5 (the file still allows 7)?
- ~~Q-S4 poll cost~~ → resolved by ARCHITECTURE §4: one small cursor query every 2 s, foreground only.

## Architecture questions for Archimedes

All seven are answered in ARCHITECTURE §16 and applied above:

| AQ | Resolution |
|---|---|
| AQ-1 | ✅ `launcher.json`, schema at `schema/launcher-config.v1.schema.json` (+ `schema/examples/`) |
| AQ-2 | ✅ Canonical reformat on every launcher write. `x-` keys kept, other unknown keys invalid, key order not preserved. Reloads never write |
| AQ-3 | ✅ Errors reject the whole file (last working layout kept, B2). Grid 2–12 × 2–12 |
| AQ-4 | ✅ Temp write + verify, then `"wt"` write + verify, restore on failure. No SAF rename relied on |
| AQ-5 | ✅ Separate `id`, `package`, `activity`, `profile` (`personal`/`work`). Private space never written |
| AQ-6 | ✅ Hash check before writing, B6 `Use file` / `Keep mine` (replay by ID). Hash, not `revision`, is the conflict key |
| AQ-7 | ✅ App-private `last-good.json`, survives process death and reboots, not uninstall |

| AQ-8 | ✅ §17: B3 (no access) = `Reconnect` only; B4 (readable, file missing) = `Create from current layout` + `Reconnect`. Never auto-created |
| AQ-9 | ✅ §17: B8 is the lowest-priority notice, copy `…older format (v{N}). It will be upgraded on your next change.`, actions `Upgrade now` (backup first) and `✕` (per file hash) |

No architecture questions are open.
