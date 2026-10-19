# Config Launcher — UX spec (P1, MVP)

Owner: Pragma (UX) · Builds from it: Sprite · Sources: [`ARCHITECTURE.md`](../ARCHITECTURE.md) (wins for behaviour),
[`schema/launcher-config.v1.schema.json`](../schema/launcher-config.v1.schema.json) and `docs/research-spike.md`.
This doc wins for wording. Aligned with ARCHITECTURE §16 (all 13 listed changes applied).

**Product in one line:** an Android home screen whose whole layout lives in one JSON file. Edits on the phone write
back to the file, and edits to the file show up on the phone.

**MVP scope (ARCHITECTURE §12):** home pages with an app grid and a dock, the app drawer (A–Z, hidden list, rule
groups), folders (one level), unplaced apps in the drawer, app and pinned-shortcut items (personal + work profiles), the file in a
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
  "drawer": { "sort": "label", "hidden": ["com.example.bloat"], "rules": [] }
}
```

Shape notes (ARCHITECTURE §3, §16):
- Every page, item and drawer rule has a stable opaque `id` (`^[a-z0-9][a-z0-9_-]{0,63}$`, unique in the file). Drags, conflict replay and agents refer to items by `id`. The launcher generates IDs like `a-7k2m9q`, and agents may use readable ones like `a-settings`.
- Apps carry `package` and an optional `activity` (full class name, no leading-dot shorthand; omit it to use the default launcher activity). An optional `profile` is `personal` (default) or `work`. Work apps are written only when **Include work apps in config** is on (default off). **Private-space apps are never written.**
- `shortcut` items: `package` + `shortcutId` (a pinned shortcut).
- Folders use `label` and hold apps and shortcuts only, with no nested folders. Folder and dock children have no `x`/`y`; their order is the array order.
- Pages need an `id`, and `title` is optional. Page items need `x`/`y`. Every item is 1 × 1.
- `drawer.hidden[]` holds **package names** (hiding is per app). `drawer.sort` is `label` (default) or `installTime`. `drawer.rules[]` group apps in the drawer by `packages`, `packagePrefix`, `tag` or `category` (first match wins).
- Unplaced apps live **only in the drawer** (Q-C3, ARCHITECTURE §18.1). `unsorted.mode` defaults to `drawer`. `unsorted.mode` is never acted on: `page` (still valid in the schema, not offered in Settings) behaves exactly like `drawer`, per Christopher (AF-2). The starter layout has no `unsorted` key.
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
| Label backdrop (v1 setting, default off, Q-C4, §18.1) | When on: a rounded **60 % black** scrim behind each home-grid label (a background on the label, 6 dp side and 2 dp top/bottom padding, hugging the text width up to the cell width). The text shadow stays on. Applies to page items and folder tiles on home, not the dock (no labels) or inside the folder card. Stored in app settings (DataStore), not in `launcher.json` |
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
| Long-press app, shortcut or folder | Item sheet showing the **real app name and package** (so a label override can't disguise an app), plus `Edit layout`, `App info`, `Remove from home`. Folder: header `Folder, 5 apps` (plural `Folder, 1 app`), no `App info`. Dimmed (not installed): header `Not installed · <package>`, no `App info` (§14) |
| Long-press empty space (empty cells only; items open their sheet) | Home menu: `Edit layout`, `Pages` (only with ≥ 2 pages; opens the §1.3 sheet), `Widgets` (later, §16), `Wallpaper` (system wallpaper picker), `Reload layout`, `Settings`. In app-only mode also `Choose folder` |
| Swipe up anywhere / tap `Apps` | Open the drawer (§1.4) |
| Swipe left/right | Change page |
| Tap the page label (≥ 2 pages) | Page list, jump to a page (§1.3) |
| Home button while on home | Go to page 1, close drawer/folder |

### 1.3 Page label and page list (#78, MVP)
**Page label.** A single line at the top of the page area, under the banner slot, centred, 14 sp, white with the label
shadow (and the 60 % scrim when `Label backdrop` is on). Text: the page `title`, or `Page 2` when the page has no title.
- Shown when there are **≥ 2 pages**, or when the only page has a `title`. Hidden for a single untitled page.
- It's a tap target only with ≥ 2 pages (min 48 dp tall, padded to at least 96 dp wide). A trailing `▾` (12 dp) shows
  it's tappable. With one page it's plain text, no `▾`, no action.
- Long titles: 1 line, ellipsized at the end, max width = screen width − 32 dp.

**Tap → page list.** A bottom sheet (Material `ModalBottomSheet`), title `Pages`, one row per page in file order:
- Row: `<title>` or `Page N` (N = 1-based position), 56 dp tall. The current page shows a leading `✓` and bold text;
  others have a blank 24 dp slot so the titles line up.
- Tap a row → the sheet closes and the pager scrolls to that page (an instant jump with reduced motion). Tapping the
  current page just closes the sheet. Back, swipe-down or a tap on the scrim closes it without moving.
- 20 pages (the max): the list scrolls, opening scrolled so the current page is visible. No search, no sections.
- Nothing is written. Jumping never changes the file.

**Edit mode:** the label and the list still work (jump to a page to drop things there). Jump-only in the MVP.
Rename and reorder pages from the list are **later** (edit the file meanwhile). While an item is being dragged the
label is inert (page turns use the screen edge, §4.2).

**Page indicator:** unchanged (dots, ≥ 2 pages, not tappable). The label and the dots always agree, and both update
when the pager settles.

**TalkBack:**
- Label: role button, announced `Home, page 2 of 3, button`, hint (`onClick` label) `list pages`, so TalkBack says
  `Double-tap to list pages`. With one page: plain text, `Home`.
- Sheet: a list with collection info (`20 items`). Rows: `Home, page 1 of 3` with **selected** state on the current
  page (`Selected, Home, page 1 of 3`). Focus starts on the current row.
- After a jump, the pager announces `Page 3 of 3` (polite).
- The label also exposes a custom action `List pages` on the home grid, for users who navigate by actions.

Copy: sheet title `Pages`; untitled row and label `Page %1$d`; TalkBack `%1$s, page %2$d of %3$d`, action `List pages`.

### 1.4 Opening the drawer: swipe up and its alternatives (#77, MVP)
- **Gesture:** a vertical swipe up that starts anywhere on home (empty cells, items, page label, dock, the gap
  between). It opens when the finger moves **≥ 48 dp up** with a vertical-dominant angle (|dy| > 1.5 × |dx|), or on a
  fling upwards faster than 1000 dp/s after ≥ 16 dp. The drawer follows the finger (interactive), and releasing
  under the threshold springs it back.
- **Conflicts:**
  - Horizontal pager: whichever axis passes touch slop first wins for the rest of the gesture. Horizontal → pager,
    vertical-up → drawer. Vertical-down on home does nothing (notification shade is out of scope, #77).
  - Long-press and drag: once a long-press fires (item sheet, home menu or drag), the swipe is cancelled. Swipe-up
    never starts edit mode.
  - Edit mode: swipe-up is **off** (vertical moves belong to drag). Use `Done` first. The `Apps` button is hidden in edit mode.
  - Open folder or open sheet: the gesture goes to them, not the drawer.
  - System gesture navigation: the bottom system gesture inset is excluded (the system's own home gesture wins there).
- **Dock:** swiping up from the dock opens the drawer like anywhere else. Tapping a dock item still launches.
- **Keyboard:** the keyboard request (§2) fires when the open animation starts, so the first keystroke isn't lost.
  With `Open keyboard with drawer` off, no keyboard.
- **Tap / accessibility alternative:** the `Apps` button in the top bar (current build) is the tap path, label
  `Apps`, TalkBack `Apps, button`, hint `open app list`. It stays even with swipe-up working. (It replaces the
  `⌃ handle` in the wireframe; one tap target is enough.) A TalkBack custom action `Open app list` is also on the home grid.
- **Reduced motion:** the drawer appears with a fade (150 ms) instead of sliding with the finger; the threshold is the same.
- **Close:** swipe down at the top of the list, Back (keyboard first, §2), Home, or launching an app.

### 1.2 Reconciliation with installed apps (spike §4)
- Entries for **uninstalled or unknown** apps, unpublished shortcuts and paused or removed work apps show as dimmed
  tiles (40 % opacity, `◌`) with their label override or package name. They are **never deleted automatically**.
- An `app` with an `activity` that no longer exists falls back to the package's only launcher activity, if there is
  exactly one (the file is left unchanged), and is dimmed if there are several (ARCHITECTURE OQ-27).
- Installed apps that are placed nowhere are **unplaced**. They appear in the drawer only, like every app. There's no
  Unsorted home page (Q-C3). Unplaced apps are never written
  to the file. Only an explicit drag places them.
- Work-profile apps show the system work badge. With **Include work apps in config** off (default) they appear only in
  the drawer and **can't be placed** on pages, the dock or folders (drag is refused, see §4.2). Work items **already in
  the file** while the setting is off render greyed with the work badge. Tapping one shows the hint
  `Turn on "Include work apps in config" to use this.` They aren't invalid and are never deleted. Turning the setting on
  makes them live without a write (§17 Q12).
- Private-space apps appear only in the system-style private-space section at the bottom of the drawer while it's
  unlocked. They're never placed, never written, and not affected by `drawer.hidden`. They
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
| Open / close | Swipe up from home, or tap `Apps` (§1.4). Close: swipe down at the top of the list, Back, or Home |
| Search | Field at top, hint `Search apps`. **Opening the drawer focuses the field and shows the keyboard** (Q-C2, search-first). Scrolling the list hides the keyboard, and tapping the field brings it back. Swipe-down or Back with the keyboard up hides the keyboard first; a second Back closes the drawer (§18.2). Setting `Open keyboard with drawer` (default on) turns auto-open off, e.g. for wall displays. A swipe up from home that's still in motion must not drop the first keystroke: focus is requested when the open animation starts. |
| Search: accessibility | With TalkBack on, the field gets accessibility focus and is announced `Search apps, edit box`, but the **keyboard is not shown automatically**, because it would cover half the list during exploration. Double-tap the field to type. Results update a polite live region: `5 apps` / `No apps match "xyz".` With reduced motion, the keyboard appears without waiting for the drawer animation |
| Search: hardware keyboard | With a physical keyboard attached, the field is focused, the on-screen keyboard stays hidden (system default), and typing filters immediately. `Enter` launches the first result. `↓`/`Tab` moves focus into the results, `↑` at the first result returns to the field. `Esc` clears a non-empty query, then closes the drawer. Arrow focus shows a 2 dp focus ring |
| Search: filtering | Filters live on each keystroke: case- and accent-insensitive match on the label start or word start, then anywhere. Enter launches the first result. No results: `No apps match "xyz".` |
| Sorting | `drawer.sort`: `label` (default, A–Z with locale-aware `Collator`, numbers first) or `installTime` (newest first). Not editable in the UI in the MVP |
| Groups | `drawer.rules[]` create titled groups (the rule's `group`) at the top of the list, in rule order, each sorted as above. An app matching no rule is listed under `All apps`. Rules never affect home pages |
| What's listed | **Every launchable app** in the personal profile (and the work profile, badged), **including apps already placed** on pages, the dock or in folders, minus `drawer.hidden` (§17 b). Private space has its own section (§1.2) |
| Hidden apps | Package names in `drawer.hidden[]`. Not shown in the list or search. Long-press a drawer app → `Hide` (one write). Hidden apps are listed in **Settings → Hidden apps**, where `Unhide` is one write (interim: drawer ⋮ → `Hidden apps`, §14). The `Hide` snackbar has `Undo`. A hidden app placed explicitly on a page still shows and launches there |
| Long-press app | `Add to home` (first free cell, page 1 onward, else a new page), `Hide`, `App info`. Dragging out of the drawer to home is **later** (§14); `Add to home` covers the MVP. Work apps offer `Add to home` only when **Include work apps in config** is on |
| Menu `⋮` | Folder mode: `Choose folder` / `Use phone storage`, `Reload layout`, `Set as home app`, `Hidden apps`, `Agent instructions (AGENTS.md)`, `Add`/`Update agent instructions`, `Include hidden apps in apps.json` (checkbox, default off, §7.2), `Ask before merging`, `Open keyboard with drawer`, `Label backdrop`, `Include work apps in config` (only when a work profile exists). App-only: hides folder-only items (AGENTS.md, apps.json). Home menu `Settings` opens the same menu (§14 R10) |

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
| File changed **and** we have a pending edit (hash mismatch at write time, or an incoming file while ops are pending) | **Default: automatic merge** (§18.2). The pending ops are replayed by ID onto the file and the result is written, with no prompt. "Just touched" means exactly the items targeted by pending (unwritten) ops; everything else comes from the file. Both pre-merge versions go to history | Snackbar per §9.1 (all kept, all skipped or mixed). Undo writes the file's version back, i.e. "Use file" |
| Same, with **`Ask before merging`** on (Settings, default off), or if the merged result fails validation (regardless of the setting) | Nothing is overwritten. Banner B6 | `launcher.json changed while you were editing.` Actions: **`Use file`** (discard my pending edits and load the file; my version is saved to history) · **`Keep mine`** (the same replay as the automatic merge) |
| Merge (automatic or `Keep mine`) and a target item was deleted in the file | That edit is skipped | Counted in the §9.1 snackbar |
| Merge and the target cell is taken in the file | Placed in the nearest free cell on the same page, else the first free cell, else a new page | — |
| File changed and is invalid | §6 error banner (B1/B2). Editing still works (§4.1) | see §6 |

Both versions always survive in app-private history (Settings → `Restore previous layout`), so no merge or button loses data. The merge replays edits instead of overwriting, so an agent's changes to other items are kept. The content hash, not `revision`, decides what is new: a file with a lower `revision` but different content is still applied.

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
| **B6** | Conflict (§4.4), only with `Ask before merging` on or when the merged result fails validation | `launcher.json changed while you were editing.` With more than one pending edit: `launcher.json changed while you were editing (2 changes).` | `Use file` · `Keep mine` (only `Use file` if the replayed result fails validation). Edits made while B6 is up join the queue and stay on screen (§17 Q8) |
| **B7** | `schemaVersion` newer than supported | `This file needs a newer Config Launcher.` | `Details` |
| **B8** (notice, lowest priority: shown only when no B1–B6 banner is up, and below them) | `schemaVersion` older: migrated in memory, not yet written | `launcher.json uses an older format (v{N}). It will be upgraded on your next change.` | `Upgrade now` (saves the original to `.configlauncher/backup/launcher.v{N}.{timestamp}.json`, then writes through the normal path with the hash check and B6) · `✕` (dismissed for that file hash; it returns only for a different older-format file. The next edit still upgrades, with a backup) |
| — (notice) | Sync conflict copies next to the file (§5) | `Sync conflict copies found: <names>. Only launcher.json is used.` | `×` |
| **B9** (later, §17.4; notice, lowest priority like B8) | A reload raised the number of dashboard widgets waiting for approval | `2 widgets need your OK.` (`1 widget needs your OK.`) | `Review` · `×` (collapses to the count chip) |

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
   - Secondary text button under it: `Skip for now` (Q-C1). See **Skip for now** below.
   - Line under the body (folder mode only): `It also adds AGENTS.md, a short guide for AI agents that edit your layout. You can turn this off in Settings.` (§7.1)
   - Picker cancelled → stay on this screen and show a line above the buttons: `No folder chosen. You can skip this and connect one later in Settings.` Both buttons stay.
   - **Folder can't be read** (#82, approved as built): a dialog, because first run has no banner area. Title `Can't read that folder`, body `Nothing was changed. Choose the folder again, or pick another one.`, the reason underneath in secondary text (e.g. the system error), buttons `Choose folder` (primary, reopens the picker) · `Cancel` (back to step 2, where `Skip for now` is still available). Nothing in the folder is written or overwritten.
   - **Cloud folder** (the picked folder's provider isn't local storage, `com.android.externalstorage.documents`): a soft warning before continuing: `Cloud folders can take a while to sync. A Syncthing or git folder works best.` Buttons: `Use anyway` · `Choose another folder`. Not blocked (§17 Q15). `Choose another folder` (reopens the picker) is approved as written (§18.4).
3. **Folder result**
   - **Contains a valid `launcher.json`:** `Found launcher.json with 3 pages and 24 items.` Buttons: `Use this layout` · `Start fresh` (the existing file is first saved to `.configlauncher/backup/`).
   - **Contains an invalid `launcher.json`:** `launcher.json in this folder has problems.` Buttons: `See problems` (§6.2) · `Start fresh` (backup first, as above) · `Choose another folder`.
   - **No file:** `There's no launcher.json here yet. Create one from a starter layout?` Button: `Create from current layout`. The starter is the built-in default: one empty page, a dock with phone, messages, browser and camera when they can be found, every other app in the drawer (no `unsorted` key).
   - Shown under all three: `When you edit on your phone, launcher.json is rewritten in a standard format. Comments aren't supported; use "x-note" keys for notes.`
4. **Make it your home app**
   - Title: `Set as home app`
   - Body: `To use Config Launcher when you press Home, set it as your default home app. Shortcuts only work once it's your home app.`
   - Button: `Set as home app` → `RoleManager.createRequestRoleIntent(ROLE_HOME)`. Secondary: `Not now`.
   - If declined: the app still works as a normal app, minus shortcuts, which show dimmed with `Set as Home to use shortcuts`. Settings shows `Not your home app` with a `Set as home app` button.
5. Land on the home screen. One-time hint chip: `Long-press empty space to edit your layout.` (dismiss ×, never shown again).

**Skip for now (app-only storage, Q-C1):**
- `Skip for now` → a confirm sheet (UX addition): title `Use without a folder?`, body `Your layout will be kept only on this phone. You can't edit it from your computer until you choose a folder in Settings.` Buttons: `Skip for now` · `Choose folder`.
- Then go straight to step 4 with the starter layout (as in step 3, "No file").
- In app-only mode the layout lives in an app-private `launcher.json` (§18.2). Everything on the phone works the same: edits, one gesture = one write, validation, history (10), Restore previous layout. The `.configlauncher/` files exist but stay app-private. Changes are detected without polling. B3/B4 never show; B5 can (storage full). Sync-conflict notices don't apply. Outside editing is only possible via `adb` on debug builds.
- **Reminder (unobtrusive):**
  - Settings → `Config folder` shows `Layout stored on this phone only` with `Choose folder` (§18.2).
  - The home menu (long-press empty space) gets an extra item: `Choose folder`.
  - One hint chip on home, **shown once**, 7 days after skipping (or on the 10th phone edit, whichever comes first): `Your layout is only on this phone. Choose a folder to edit it from your computer.` [`Choose folder`] [×]. Dismissed forever either way. No notifications, no banners, no repeat nags.
- **Moving to a folder later** (Settings → `Choose folder` → picker → cloud-folder warning as in step 2 → result, §18.2):
  - **Folder has no `launcher.json`:** the current layout is written there (one write; choosing the folder is the explicit create), and the folder becomes the source.
  - **Folder has a valid `launcher.json`:** dialog `This folder already has a launcher.json.` Buttons: `Use the folder's layout` (the app-private file is kept as a history entry) · `Replace it with mine` (the folder file is copied to `.configlauncher/backup/` first).
  - **Folder has an invalid `launcher.json`:** the same dialog and choices, plus `See problems`.
  - After connecting: snackbar `Connected to <folder name>.` and normal folder mode from then on (polling, banners, `status.json`). The app-private copy becomes the usual last-good copy.
- The app-private file isn't deleted. **Settings → `Use phone storage`** switches back from a folder: it copies the current layout into the app-private file and releases the folder grant.

**Lost access or missing file (B3/B4):**
- `Reconnect` reopens the picker. Picking a folder with a valid file loads it. Picking one without a file offers `Create from current layout`, which uses what's on screen now (not the starter).
- Lost access is detected on every start (persisted grants are checked), on resume, and on any failed read or write.

---

### 7.1 AGENTS.md in the config folder (#96)
- When a folder is chosen or connected, with the setting on, the app writes `AGENTS.md` (the bundled guide) next to `launcher.json`. It never touches an `AGENTS.md` the user edited, and it never recreates one the user deleted.
- **Setting:** `Agent instructions (AGENTS.md)`, a switch, default **on** (interim: a checkbox in the ⋮ menu, §14 R10). Off: nothing is written. Existing files are left alone.
- **Action** in the ⋮ menu (folder mode only):
  - No `AGENTS.md` in the folder: `Add agent instructions`. Writes the current version.
  - `AGENTS.md` present: `Update agent instructions` (replaces `Add agent instructions again`).
    - The file is unchanged since the app wrote it (its hash matches a shipped version): replaced silently, then the snackbar `Agent instructions updated.`
    - The user edited it (any other content): confirm `Replace your AGENTS.md?` / `Your edits will be saved to .configlauncher/backup/.` [`Cancel`] [`Replace`]. Replace copies the old file to `.configlauncher/backup/AGENTS.<timestamp>.md`, then writes the new one.
- **App-only mode:** the setting, the action and the first-run line are hidden.
- `AGENTS.md` is written with the same temp, verify and restore path as `launcher.json`.

### 7.2 apps.json (#72)
`.configlauncher/apps.json` lists every launchable app the drawer shows (`package`, `activity`, `label`, `profile`,
`installTime`), rewritten when apps are installed, removed or changed, and when the settings below or the hidden list
change. Agents use it to place apps by their real package and activity. Work apps are listed only when **Include work
apps in config** is on. Apps in `drawer.hidden` are left out unless **Include hidden apps in apps.json** is on.
- **Setting:** `Include hidden apps in apps.json`, a switch, default **off** (interim: a checkbox in the ⋮ menu).
  Description (TalkBack now; helper line in Settings later): `apps.json lists your installed apps for agents. Apps hidden from the drawer are left out unless this is on.`
  String ids: `menu_include_hidden_apps`, `menu_include_hidden_apps_desc`.
- Toggling the setting, or changing `drawer.hidden`, rewrites `apps.json` silently (no snackbar).
- **App-only mode:** not written (no folder sidecars), and the setting is hidden.

## 8. Settings (MVP, minimal)

MVP vs later and the interim ⋮ menu: see §14.

| Setting | Spec |
|---|---|
| `Config folder` | Folder name and last load time, `Change folder`, `Reload layout`, `Use phone storage`. In app-only mode: `Layout stored on this phone only` and `Choose folder` (§7) |
| `Ask before merging` | Switch, default **off**. Off: conflicts merge automatically (§4.4). On: B6 asks `Use file` / `Keep mine` |
| `Open keyboard with drawer` | Switch, default **on**. Off: the drawer opens without focusing search (§2) |
| `Restore previous layout` | Lists the app-private history (last 10, confirmed by Christopher, Q-C6; newest first, with time). Picking one writes it to `launcher.json` (or the app-private layout in app-only mode) like any edit (hash check, safe write) |
| `Include work apps in config` | Switch, default **off**. Off: work apps show in the drawer with the badge but can't be placed, and nothing new about them is written. Work items already in the file stay, greyed, with the hint in §1.2. Turning it on makes them live without a write (§17 Q12) |
| `Hidden apps` | The packages in `drawer.hidden`, each with `Unhide` (one write) |
| `Include hidden apps in apps.json` | Switch, default **off**. Off: apps in `drawer.hidden` are left out of `.configlauncher/apps.json`. On: they are listed. Rewrites `apps.json` silently when toggled or when the hidden list changes (§7.2). Hidden in app-only mode |
| `Label backdrop` | Switch, default **off**. A rounded 60 % black scrim behind home labels for busy wallpapers (§1.1). Stored in DataStore |
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
| Merge results (kept and skipped changes, folder skips) | See §9.1 |
| Work app drag refused | `Turn on "Include work apps in config" in Settings to place work apps.` |
| Removed from home | `Removed <Label> from home.` [`Undo`] (Undo = a second write restoring it) |
| Hidden | `<Label> hidden from the app list.` [`Undo`] |

---

### 9.1 Merge snackbar (automatic merge and `Keep mine`)
One snackbar per merge, never two. It leads with what happened, then gives counts. `k` = changes kept, `s` = changes skipped
(an edit whose item was removed in the file; a folder that wasn't recreated counts as one skipped change).

| Case | Text (exact) | Undo |
|---|---|---|
| All kept, k = 1 | `Layout updated from launcher.json. Kept your change.` | Yes |
| All kept, k ≥ 2 | `Layout updated from launcher.json. Kept your 2 changes.` | Yes |
| Mixed, 1/1 | `Layout updated from launcher.json. Kept 1 change, skipped 1.` | Yes |
| Mixed, n/1 | `Layout updated from launcher.json. Kept 3 changes, skipped 1.` | Yes |
| Mixed, 1/n | `Layout updated from launcher.json. Kept 1 change, skipped 3.` | Yes |
| Mixed, n/n | `Layout updated from launcher.json. Kept 2 changes, skipped 2.` (61 characters) | Yes |
| All skipped, s = 1, and it's a regular edit | `1 change skipped because the item was removed in the file.` | No |
| All skipped, s ≥ 2 | `2 changes skipped because the items were removed in the file.` | No |
| All skipped, s = 1, and it's a folder with one member left (§17 Q9) | `Folder "<Label>" wasn't recreated because its other apps were removed or moved in the file. "<App>" was placed on its own.` | No |
| All skipped, s = 1, and it's a folder with no members left | `Folder "<Label>" wasn't recreated because its apps were removed or moved in the file.` | No |

Rules:
- **Folder message:** shown only when the folder is the *only* change and it was skipped. In every other case it's folded into the counts (one folder = one skipped change). The full story is in history.
- **Undo** (automatic merge only) restores the file's version as it arrived, i.e. "Use file" (§18.2), with Sprite's safety rule: if the file changed again since the merge, only the items the merge touched are reverted. Skipped edits stay skipped either way. **All-skipped merges have no Undo**, because nothing of yours was written and the screen already shows the file's version. The pre-merge phone layout stays in `Restore previous layout`.
- **`Keep mine`** (B6) uses the same table, minus Undo, because the user has just chosen. All kept shows nothing extra.
- Snackbar duration: 10 s when it has Undo (the §18.2 Undo window), 6 s without.
- **TalkBack** announces the full sentence, not the short form. Mixed: `Layout updated from launcher.json. Kept 2 changes. Skipped 2 changes because their items were removed or moved in the file.` The Undo action is exposed as the snackbar's action and announced by the system (`Undo, button`). All-kept and all-skipped announce the visible text.
- **Android resources** (no hard-coded English in Kotlin):
  ```xml
  <string name="merge_updated">Layout updated from launcher.json.</string>
  <plurals name="merge_kept_all">
      <item quantity="one">Kept your change.</item>
      <item quantity="other">Kept your %d changes.</item>
  </plurals>
  <!-- Mixed: %1$s = merge_updated, %2$s = merge_kept_part, %3$s = merge_skipped_part -->
  <string name="merge_mixed">%1$s %2$s, %3$s.</string>
  <plurals name="merge_kept_part">
      <item quantity="one">Kept %d change</item>
      <item quantity="other">Kept %d changes</item>
  </plurals>
  <plurals name="merge_skipped_part">
      <item quantity="one">skipped %d</item>
      <item quantity="other">skipped %d</item>
  </plurals>
  <plurals name="merge_skipped_all">
      <item quantity="one">%d change skipped because the item was removed in the file.</item>
      <item quantity="other">%d changes skipped because the items were removed in the file.</item>
  </plurals>
  <plurals name="merge_skipped_a11y">
      <item quantity="one">Skipped %d change because its item was removed or moved in the file.</item>
      <item quantity="other">Skipped %d changes because their items were removed or moved in the file.</item>
  </plurals>
  <string name="merge_folder_one_left">Folder "%1$s" wasn't recreated because its other apps were removed or moved in the file. "%2$s" was placed on its own.</string>
  <string name="merge_folder_none_left">Folder "%1$s" wasn't recreated because its apps were removed or moved in the file.</string>
  <!-- TalkBack long form: %1$s = merge_updated, %2$s = merge_kept_part, %3$s = merge_skipped_a11y -->
  <string name="merge_mixed_a11y">%1$s %2$s. %3$s</string>
  <string name="merge_undo">Undo</string>
  ```
  `merge_mixed` is `merge_updated` + `merge_kept_part` + `merge_skipped_part`. Always pass the count as the plurals quantity *and* the format argument (`getQuantityString(id, n, n)`).

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
| Actions | Every drag action also has custom accessibility actions on the item (`Move to…` with a page/row/column picker, `Add to folder…`, `Move to dock`, `Remove from home`, `Hide`), so the layout is fully editable without drag. Each is one write. `Add to folder…` is added in the same PR that builds dropping onto a folder |
| Edit mode | Announce `Editing layout. Double-tap Done when finished.` Focus order: top bar, then grid in reading order, then dock |
| Banners | Polite live region, announced once. Buttons are focusable. The error detail view reads as a list of problems |
| Touch targets | ≥ **48 × 48 dp** for everything (cells 64 × 80 dp, dock slots 64 dp, chip ≥ 48 dp hit area, banner buttons 48 dp tall) |
| Font scaling | Labels and text use sp and follow the system font size up to 200 %. Grid labels stay 1 line and ellipsize; at ≥ 150 % labels may be hidden on the home grid (still spoken by TalkBack) if cells can't fit them. The drawer always shows labels (wrapping to 2 lines). Banners and settings wrap, never truncate |
| Contrast | Text in app UI (drawer, settings, banners, detail view) ≥ 4.5:1 and icons/controls ≥ 3:1, in light and dark (follow the system theme; Material 3 dynamic colour off for status colours). Home labels over wallpaper: white text with a 1 dp 50 % black shadow. With `Label backdrop` on, the 60 % black scrim is added (§1.1). Over a pure white wallpaper, white text on that scrim alone measures 5.7:1 (WCAG, worst case), so it meets 4.5:1 on any wallpaper |
| Reduced motion | Respect `Settings.Global.ANIMATOR_DURATION_SCALE == 0` and "Remove animations": no page-slide spring, folder open as a fade, no shake (use a red outline), no jiggle ever |
| Colour | Status is never colour-only: ▲ warning / ■ error glyphs, dimmed tiles also say "not installed" to TalkBack |

---

## 13. Build checklist (Sprite)

- [ ] Home: pager + grid (`grid.columns/rows`, default 4 × 5, file allows 2–12) + dock (0–7), sizes from §1.1, wallpaper through.
- [ ] Dimmed tiles and reconciliation (§1.2): not installed, unpublished shortcut, paused work apps, activity fallback, unplaced apps in the drawer only (`page` not offered in Settings), private space never placed. Never auto-delete.
- [ ] Item sheet shows the real app name and package. Strip bidi and zero-width characters from labels at render.
- [ ] Drawer: search (§2 match rules, focused with the keyboard up; TalkBack and hardware-keyboard rules, Back hides the keyboard first, `Open keyboard with drawer` setting), `drawer.sort` (label or installTime), `drawer.rules` groups, hidden apps section (package names), work tab.
- [ ] Folders: `label`, create/rename/add/remove/delete rules (§3), open card, max 48.
- [ ] Edit mode: entry/exit, outlines, drag targets (§4.2), no reflow, 30 s idle exit, **allowed while invalid** with the broken-copy snackbar, work-app refusal when the opt-in is off.
- [ ] Write path (§4.3): ID-based ops → 750 ms debounce (never mid-drag) → hash check → canonical serialize (`x-` keys kept) → temp write + verify → `"wt"` write + verify → restore on failure → remember hash, history (10). B5 on failure.
- [ ] Conflict (§4.4): hold during drag, automatic merge with the §9.1 snackbar (kept / skipped / mixed, Undo only when something was kept) by default, B6 `Use file` / `Keep mine` only with `Ask before merging` or a failed merge (replay by ID, skipped-op snackbar with Undo, nearest free cell).
- [ ] Reload (§5): 2 s poll while visible + immediate on resume, ContentObserver as a hint, 300 ms stability check, hash decides, no page jump, sync-conflict notice.
- [ ] Banners B1–B8 (§6.1), collapse chip, live region, auto-clear. Detail view (§6.2) with 1-based line/column, paths and excerpts; Copy errors; Restore with confirmation (backup to `.configlauncher/backup/`).
- [ ] First run (§7) with exact copy, `Skip for now` (app-private source, Choose folder later, Use phone storage, one-time reminder), persistable grant, three folder outcomes, format note, home role request (RoleManager), one-time hint.
- [ ] Settings (§8): Config folder (incl. Choose folder / Use phone storage), Ask before merging, Restore previous layout, Include work apps in config, Hidden apps, Label backdrop, Open keyboard with drawer, Home app, Grid, About.
- [ ] Snackbars (§9) with Undo where listed.
- [ ] Accessibility (§12): labels, custom actions for every drag action, 48 dp targets, font scaling, reduced motion.
- [ ] All copy in `strings.xml` with plurals.
- [ ] Manual device check (#93): with TalkBack on, opening the Pages sheet puts focus on the current row, announced as selected (`Selected, Home, page 1 of 3`). Hint: a `FocusRequester` on the current row, requested after the sheet settles (`sheetState.currentValue == Expanded`), plus `Modifier.semantics { selected = true }` on that row.
- [ ] Manual tests: edit the file on a PC via Syncthing while the phone is on home, in edit mode, and mid-drag. Corrupt the file, then edit on the phone (broken copy). Revoke access in system settings. Delete the file. Newer `schemaVersion`. Font 200 %. TalkBack-only layout edit.

---

## 14. Rulings on Sprite's #53 build (#59, #60, #62, #63)

| # | Topic | Ruling | Sprite | Scope |
|---|---|---|---|---|
| R1 | Folder item sheet | Confirmed: header `Folder, 5 apps` (`Folder, 1 app`), actions `Edit layout`, `Remove from home`. No `App info` | — | MVP |
| R2 | Not-installed item sheet (long-press) | Confirmed: header `Not installed · <package>`, actions `Edit layout`, `Remove from home`, no `App info`. A **tap** still opens the dimmed-item hint sheet (§1.1) | — | MVP |
| R3 | `Remove from home` on a folder | Removes the folder and its items **from home only**. The apps stay installed and in the drawer (the drawer always lists every app). No confirm dialog. Instead: one write plus snackbar `Removed folder "<Label>" from home.` [`Undo`] (Undo = a second write restoring the folder with its items and position). TalkBack: the same text. If Undo can't restore because all pages are full: `Couldn't restore "<Label>": all 20 pages are full.` (TalkBack: the same text) | Add the snackbar with Undo | MVP |
| R4 | Home key in edit mode | **Change:** Home leaves edit mode (as §4.1 says), after flushing pending writes, and goes to page 1 | Handle Home in edit mode | MVP |
| R5 | Empty pages | **Change:** leaving edit mode deletes empty pages (as §4.2 says), never the last page. Folded into the same write as the last edit, or one write if there were no edits | Implement | MVP |
| R6 | Home menu only on empty cells | Confirmed. Menu: `Edit layout`, `Wallpaper`, `Reload layout`, `Settings` (+ `Choose folder` in app-only mode). `Wallpaper` (#63) opens the system wallpaper picker and is confirmed | — | MVP |
| R7 | Drag out of the drawer | **Later.** MVP uses `Add to home`. ux.md §2 updated | — | Later |
| R8 | Unhide | **Change (interim):** drawer ⋮ → `Hidden apps` opens a sheet titled `Hidden apps`, listing each hidden app (icon, label, package) with `Unhide` (one write). Empty: `No hidden apps.` The `Hide` snackbar `<Label> hidden from the app list.` gets [`Undo`]. Moves to Settings → Hidden apps when Settings exists | Add the interim sheet, the empty state and the Hide snackbar with Undo | MVP |
| R9 | New pages from `Add to home` | Confirmed: id `page-N` (lowest unused N), no `title`, max 20 pages. **Change the full copy** from `No room on home. Remove a page first.` to `No room on home: all 20 pages are full.` | Change `home_full` | MVP |
| R10 | No Settings screen; ⋮ menu holds folder and home-app items | **Acceptable interim**, but use the doc's names: `Choose folder` (not `Pick config folder…`), `Reload layout` (not `Reload launcher.json`), `Set as home app`, `Hidden apps`. Until the screen exists, the home menu's `Settings` opens the same ⋮ menu | Rename `menu_pick_folder` → `Choose folder`, `menu_reload` → `Reload layout`; route home-menu `Settings` to the ⋮ menu | MVP interim |
| R11 | Which Settings are MVP | **MVP:** Config folder (Choose folder / Use phone storage / Reload layout), Home app, Hidden apps, Include hidden apps in apps.json (§7.2), Ask before merging, Open keyboard with drawer, Label backdrop, Include work apps in config (only shown if a work profile exists), Agent instructions (AGENTS.md), Restore previous layout. **Later:** Grid (edit the file), About (version only in the ⋮ menu is fine) | — | — |

## 15. Config-driven title bar (#79, later, not v1-gating)
UX side of Archimedes' design in [`docs/design/title-bar.md`](design/title-bar.md) (#81), which decides the architecture and schema.
The bar is the first element in the general `elements[]` model. The three-slot bar below is the **default preset** the
phone offers. The file can go further (templates, styling, per-page overrides), and the phone keeps whatever it can't
edit verbatim.

**Default preset (what the phone writes).** Off by default. When off, or with no `titleBar` element, the §1.3 page label
shows. Turning it on writes `{ "id": "t-main", "type": "titleBar", "anchor": "top", "content": { "slots": { "center": "{page}" } } }`.
The slot picker (start, center, end) offers `Page title` (`{page}`), `Clock` (`{time}`), `Date` (`{date}`) and `None`.
- **Custom slots (Q-T1):** if a slot holds anything else (e.g. `{date:EEE d MMM}` or literal text), the picker shows a
  read-only, selected row `Custom (set in the file)` with the raw template underneath in monospace. Picking another choice
  replaces it (one write; history keeps the old version), with the inline note `Picking another option replaces it.`
- The phone edits only on/off and the preset. Style, overrides (`pages[].overrides[<elementId>]`) and templates are
  file-only and never edited on the phone in this release.

**Rulings from #81 that the UX follows.** Top-level element by default; per-page overrides only in the file. No
schemaVersion 2: an unknown element type, or one whose `requires` is above the launcher's feature level, is **skipped with
a warning** and kept in the file. Unknown keys inside a known type are still errors (B2). `backdrop: "auto"` (default)
follows the device's `Label backdrop` setting; an explicit `none`, `scrim` or custom backdrop in the file wins.

**Layout and size.** One line. `center` keeps at least 50 % of the width; side slots ellipsize first, then `center`. Clock
and date never truncate, and the page title ellipsizes at the end. **Default size is `small`, 14 sp (Q-T3)**: it matches
the §1.3 page label and status-bar text, and at 200 % font (28 sp) it still fits one line and costs one grid row at most
on small phones, where 18 sp (36 sp at 200 %) would push rows below the 64 × 80 dp minimum. The bar takes its height
from size + padding, and grid rows share the rest. Never overlaps icons.

**Contrast.** Light text always gets the label shadow; dark text doesn't. With `auto` + `Label backdrop` on, or
`scrim`, the 60 % scrim gives ≥ 5.7:1 for white (§12). With a custom backdrop, the launcher measures text vs backdrop.
**Below 4.5:1 it renders anyway** and adds a warning (Q-T2): in `status.json` and in the error detail view's
**Warnings** section only. **No snackbar or banner**: the person editing the file chose the colours, and the agent sees it
in `status.json`. With `none` nothing is measured (the wallpaper is unknown).

**Skipped elements (Q-T4).** Nothing on home: no placeholder, banner or snackbar (an element is decoration and must
never make the home screen look broken). If the skipped element was the title bar, the §1.3 page label shows instead.
The warning appears in `status.json` and the Warnings section.

**Where warnings are found.** The error detail view (§6.2) gets a **Warnings** section under any problems, header
`1 warning` / `3 warnings`, each with the path and message (same layout as problems, ▲ instead of ■). With a valid file
that has warnings, it's reachable from Settings (interim: the ⋮ menu) → `Config folder`, whose status line reads
`Layout loaded · 1 warning` (tap → the detail view). `Copy errors` includes warnings.

**TalkBack.** One focusable node per non-empty slot, in reading order. `{page}` keeps the §1.3 button semantics, the
48 dp target and the `List pages` action. Clock and date are read in spoken form (`10:42 PM`, `Sunday, October 18`) and
aren't a live region. Literal text in a template is read as written.

**#78 tap target.** The page list opens only from a `{page}` token's node. Other slots aren't tappable in this release.
If no visible slot holds `{page}` (including an override that hides it), the page list is still reachable through the
`List pages` action and a `Pages` item in the home long-press menu.

### 15.1 Copy: token errors and warnings (Q-T5)
Same style as §6.3: path first, then what's wrong, then the fix. Errors are B2 problems (`launcher.json has 2 problems.
Showing the last working layout.`). Warnings never raise a banner.

| Case | Kind | Message |
|---|---|---|
| Unknown token | Error | `elements[0].content.slots.end: unknown token {wether}. Use page, pageNumber, pageCount, time or date.` |
| Disallowed pattern letter | Error | `elements[0].content.slots.end: "s" isn't allowed in {time:HH:mm:ss}. Use y M d E a H h k K m; seconds and time zones aren't supported.` |
| Unclosed brace | Error | `elements[0].content.slots.start: the "{" at character 5 isn't closed. For a literal brace, write {{.` |
| Stray closing brace | Error | `elements[0].content.slots.start: the "}" at character 9 has no opening "{". For a literal brace, write }}.` |
| Pattern too long | Error | `elements[0].content.slots.start: the pattern in {date:…} is 40 characters; the maximum is 32.` |
| Too many tokens | Error | `elements[0].content.slots.center has 9 tokens; the maximum is 8.` |
| Template too long | Error | `elements[0].content.slots.center is 130 characters; the maximum is 120.` |
| Second title bar | Error | `elements[1]: only one titleBar is allowed (elements[0] is already one).` |
| Unknown element type | Warning | `elements[2]: type "appWidget" isn't supported by this Config Launcher, so it was skipped. It stays in the file.` |
| `requires` too high | Warning | `elements[2] needs feature level 3, but this Config Launcher supports up to 2, so it was skipped. It stays in the file. Update the app to show it.` |
| Override for a missing element | Warning | `pages[1].overrides: "t-clock" doesn't match any element, so it was ignored.` |
| Low contrast | Warning | `elements[0].style.color: contrast 2.1:1 on the backdrop (4.5:1 recommended). Shown anyway.` |

Resources (character positions are 1-based, like line and column):
```xml
<string name="err_token_unknown">%1$s: unknown token {%2$s}. Use page, pageNumber, pageCount, time or date.</string>
<string name="err_token_letter">%1$s: \"%2$s\" isn\'t allowed in %3$s. Use y M d E a H h k K m; seconds and time zones aren\'t supported.</string>
<string name="err_token_unclosed">%1$s: the \"{\" at character %2$d isn\'t closed. For a literal brace, write {{.</string>
<string name="err_token_stray">%1$s: the \"}\" at character %2$d has no opening \"{\". For a literal brace, write }}.</string>
<string name="err_pattern_long">%1$s: the pattern in %2$s is %3$d characters; the maximum is %4$d.</string>
<string name="err_tokens_many">%1$s has %2$d tokens; the maximum is %3$d.</string>
<string name="err_template_long">%1$s is %2$d characters; the maximum is %3$d.</string>
<string name="err_titlebar_twice">%1$s: only one titleBar is allowed (%2$s is already one).</string>
<string name="warn_element_type">%1$s: type \"%2$s\" isn\'t supported by this Config Launcher, so it was skipped. It stays in the file.</string>
<string name="warn_element_level">%1$s needs feature level %2$d, but this Config Launcher supports up to %3$d, so it was skipped. It stays in the file. Update the app to show it.</string>
<string name="warn_override_missing">%1$s: \"%2$s\" doesn\'t match any element, so it was ignored.</string>
<string name="warn_contrast">%1$s: contrast %2$s:1 on the backdrop (4.5:1 recommended). Shown anyway.</string>
<plurals name="warnings_header">
    <item quantity="one">%d warning</item>
    <item quantity="other">%d warnings</item>
</plurals>
<plurals name="status_loaded_warnings">
    <item quantity="one">Layout loaded · %d warning</item>
    <item quantity="other">Layout loaded · %d warnings</item>
</plurals>
<string name="titlebar_custom">Custom (set in the file)</string>
<string name="titlebar_custom_note">Picking another option replaces it.</string>
```
Counts in the messages above (the maxima) are always ≥ 2, so they don't need plurals. `status.json` uses the same English
text so agents and the phone say the same thing. The contrast ratio is formatted with one decimal, locale-independent (`2.1`).

## 16. Widgets (later, first post-MVP) — draft for #110
UX side of Archimedes' recommendation in [`docs/research/widgets.md`](research/widgets.md) (#110): grow the #81
`elements[]` model with a grid-placed `appWidget` (then `text` and `clock`). Bind consent always starts from a user tap,
never from a file load. Skipped grid elements still reserve their cells. Answers Q-W1–Q-W6. Not a v1 gate.

**Architecture (accepted for UX planning).** Portable record = `provider` + `profile` + `page`/`x`/`y`/`w`/`h`. The
phone keeps `filesDir/widgets/bindings.json` (element ID → appWidgetId), never synced. File loads never open bind or
configure dialogs. Out-of-bounds sizes render at the file's size and add a `status.json` warning. Stage order: container
with cell reservation → `appWidget` host → first-party `text`/`clock`.

### 16.1 Adding a widget (Q-W1)
- **Entry:** home long-press menu → `Widgets` (after `Pages`, empty cells only, §1.1). Also available from edit mode's
  top bar as `+ Widget`. Both open the same picker. Not in the drawer (drawer is apps).
- **Picker:** a full-screen sheet titled `Widgets`. Search field at the top (hint `Search widgets`), then groups by
  app (app icon + label as a sticky header). Each provider is a row: `previewImage` or `previewLayout` when available
  (else a grey rectangle with the app icon), provider label, and size chip `2 × 1`. Long-press a row for `App info`.
- **Work profile:** after personal widgets, a section `Work` with the work badge on each row. Shown only when
  **Include work apps in config** is on; otherwise the section is hidden (widgets write a `profile: "work"` entry, same
  gate as placing work apps).
- **First-party (Stage 4):** a leading group `Config Launcher` with `Text` and `Clock` (no preview image; a simple
  mock). They add a `text` / `clock` element, not an AppWidget.
- Tap a provider → place at the first free rectangle that fits the provider's default cells on the current page, else
  the next page, else a new page (max 20). Then enter edit mode with the new widget selected for resize. One write.

### 16.2 Placeholders and copy (Q-W2)
A placeholder fills the element's cells: rounded 12 dp frame, white 12 % fill, the provider's app icon (48 dp) centred,
label below (12 sp, one line), then a one-line reason (11 sp, secondary). Tap runs the next step for that state;
long-press opens the item sheet (`Edit layout`, `Remove from home`, and `App info` when the package is installed).
TalkBack: `<Label>, widget, <reason>`.

| State | Reason (exact) | Tap |
|---|---|---|
| Not bound yet (from the file, or after reinstall) | `Tap to set up` | Start the bind flow (§16.3) |
| Provider not installed | `Not installed` | Hint sheet with `Remove from layout` (same as dimmed apps) |
| Bind denied / cancelled | `Tap to allow` | Retry the bind flow |
| Configure cancelled (required configure) | `Tap to finish setup` | Re-open the configure activity |
| Provider crashed / host view failed | `Can't show this widget` | `Retry` (rebind); long-press sheet adds `Remove from home` |
| Provider uninstalled after bind | `Not installed` | Same as not installed |
| Work profile paused | `Work apps are paused` | System unpause prompt (same as dimmed work apps) |
| Size out of range (§16.5) | *(live widget still shows; cue below)* | — |

### 16.3 Bind explanation (Q-W3)
Shown **once per provider package** (DataStore set of packages), immediately before `ACTION_APPWIDGET_BIND`, and only
when `bindAppWidgetIdIfAllowed` returned false. Skipped when: the launcher already has always-allow for this provider,
or the user has dismissed this explanation for that package.

- Title: `Allow this widget?`
- Body: `Android will ask you to let Config Launcher create a <App> widget. Your layout file only names the widget; the
  phone still needs your OK, once per app.`
- Buttons: `Continue` (primary) · `Not now` (stays on the placeholder, `Tap to allow`).
- After Continue, the system dialog runs. Cancel → placeholder `Tap to allow`, `deleteAppWidgetId`. Allow → if a
  required configure activity exists, open it; cancel there → `Tap to finish setup`. Optional configure is skipped
  until the user picks `Reconfigure` from the item sheet.
- Reconfigure (when `reconfigurable`): item sheet → `Reconfigure` → the provider's configure activity. No explanation
  sheet (already bound). Failure keeps the previous config.

### 16.4 Resize (Q-W4)
- **Edit mode only.** Selecting a widget shows a white 24 % frame and four edge handles (and corners if both axes are
  allowed). Drag a handle; size snaps to whole cells. Soft haptic at each cell step. Release → one write (`w`/`h`).
- **Occupied cells:** the drag **stops** at the last free rectangle (red outline on the blocked edge; with reduced
  motion, the outline only). No reflow, no auto-move of other items (matches §4.2 "no automatic reflow", and
  Christopher's keep-it-simple). Dropping onto an occupied cell is refused.
- **Min/max:** handles won't cross the provider's min/max cells (derived at runtime). The file can still hold an
  out-of-range size from an agent (§16.5).
- **TalkBack:** custom action `Resize…` opens a sheet with Width and Height steppers (current, min, max shown as
  `Width, 2 of 4`), `Done` writes once. Also `Move to…` (existing) and `Remove from home`.

### 16.5 Out-of-range size cue (Q-W5)
Warning only is not enough on the home screen. **Also a visual cue:** a 2 dp amber outline around the live widget (or
placeholder) and a small ▲ badge at the top-end corner. TalkBack appends `, size may be wrong`. The error sheet's
Warnings section and `status.json` carry the path message (same style as §15.1), e.g.
`elements[3]: size 1×1 is below the provider's minimum 2×1. Shown at 1×1 anyway.` No auto-resize, no banner.

### 16.6 Text and clock in the picker (Q-W6)
- Appear under `Config Launcher` in the picker (§16.1). Defaults: `text` → `{page}` at 2×1; `clock` → `{time}` at 2×1.
  Templates use the title-bar token grammar.
- **Phone styling (preset-only, like the title bar):** size `small`/`medium`/`large`, colour `light`/`dark`, backdrop
  `auto`/`none`/`scrim`. Anything else (custom hex, weight, padding, raw templates beyond the three shorthands) shows
  as read-only `Custom (set in the file)` with the same replace note as §15. The phone never invents style the file
  didn't ask for.

### 16.7 Sync: one write, what the agent sees
| Phone action | File write | Agent sees |
|---|---|---|
| Add from picker | One write: new element with `provider`, `profile`, `page`, `x`, `y`, `w`, `h` | Bound on this phone only; other phones show `Tap to set up` until tapped |
| Move / resize | One write of `x`/`y`/`w`/`h` | Same |
| Remove | One write removing the element; binding deleted privately | Gone |
| Bind / configure / reconfigure | **No file write** (device-local) | Unchanged; `status.json` may list warnings |
| Unbound / missing provider | No write | Element stays; `status.json` warning e.g. `elements[3]: widget not set up on this phone` or `… provider not installed` |

`apps.json` / `AGENTS.md` are unrelated. Bindings never appear in the synced folder.


## 17. Dashboard widgets: consent and components (later, after §16)
(References to "§17 AQ-…" or "§17 Q…" elsewhere in this doc point to ARCHITECTURE §17, not to this section.)
Christopher's new framing: **make your home screen into an AI-assisted dashboard.** Widgets defined in the config, like
Claw's "Anigeek LLC" card (blog post, three uptime metrics, an unhealthy-monitor list, "as of 08:38"), are now the core
feature. Hosted AppWidgets (§16) sit next to them. This section covers the UX of (1) the **per-widget capability and
consent model** Archimedes is designing and (2) the **component system** these widgets are drawn with.

**Status.** Archimedes accepted this section and answered Q-A1–Q-A8 in the [#116 comment](https://github.com/Nasawa/config-launcher/pull/116#issuecomment-6270653131). His key names are normative and are
used below. The §17.11 JSON stays illustrative, and **Archimedes owns the schema.** Words reused from existing docs:
element, `elements[]`, `requires`/feature level, `style`, skip-and-keep, Stage 1 cell reservation, `{data:…}`,
`status.json`, the Warnings section.

**v1 capability kinds (closed list, Q-A1, #117):** `data.file` and `ui.reload` (Show), `ui.openApp` and `ui.openUrl` (Open), `net.fetch`
(Internet, feature level 5). Phone data and Act tiers have **no kinds in v1**. Their UX below is kept ready for later.
Never-offered kinds aren't in the schema at all. On the phone, an unknown `kind` **skips only that element** (skip-and-keep,
cells kept, consent tile `Update Config Launcher to show this`, §17.3). It doesn't B2-reject the whole file. `validate.py`/CI
at the current feature level may still flag it as an error so agents catch typos ([#116 follow-up](https://github.com/Nasawa/config-launcher/pull/116) and [#117](https://github.com/Nasawa/config-launcher/pull/117)).

Assumptions A1–A5, all **✅ resolved** in the [#116 comment](https://github.com/Nasawa/config-launcher/pull/116#issuecomment-6270653131):
- ✅ **A1** Approval is **device-local** (like `bindings.json`, §16) and never stored in the synced folder, so nothing that
  writes `launcher.json`, whether an agent, a sync or a git merge, can approve its own widget.
- ✅ **A2** Approval is keyed by **element `id` + a capability fingerprint**: a canonical serialization of the
  capabilities only: each `kind` plus its kind-specific field (`packages`, `hosts`, `origins`; `data.file` and
  `ui.reload` have none), sorted. Layout, text, style, span and `author` are left
  out, so editing them never prompts (Q-A2). It's compared against the widget's **approved set**, which never shrinks
  (§17.2, Claw #3).
- ✅ **A3** Capabilities are **enforced, not just shown**: a widget can't use anything it didn't declare, because the
  runtime grants only the APIs in the element's **approved** manifest (Q-A7). The prompt lists everything a widget can do,
  not a best guess.
- ✅ **A4** Dashboard data comes from **one file per widget** at `.configlauncher/widgets-data/<elementId>.json`
  (`data.file`, card feature level 4, Q-A5). The agent fetches on its own machine and writes the file, so the launcher
  can stay offline. `net.fetch` exists in the schema at feature level 5, but whether it ships, and with it the INTERNET
  permission, is Christopher's call (Q-D3). Phone data and Act have no v1 kinds.
- ✅ **A5** Hosted AppWidgets (§16) are **outside this model**. Android's bind consent (§16.3) covers them, and the
  provider app holds its own permissions. They are listed in the review screen as read-only `Allowed by Android` (Q-A8).

### 17.1 What a widget can ask for (UX tiers)
The prompt groups capabilities by what they mean to a person, not by API. Each tier has one fixed sentence shape.

| Tier | v1 kind (field) | Examples | Prompt line (exact shape) | Needs OK? |
|---|---|---|---|---|
| **Show** | `data.file` (no fields: the file is always `.configlauncher/widgets-data/<elementId>.json`, from the element `id`, so a card can't point at another card's file) | Static text, `{time}`/`{date}`/`{page}`, the widget's own data file | `Show data from widgets-data/w-anigeek.json in your config folder` | **No.** It's listed in the review screen, but on its own it never prompts (Q-D1). The folder is already yours, and displaying data can't reach anything |
| **Show** | `ui.reload` (no fields) | A tap or button that re-reads this widget's own data file now | `Reload its data when you tap it` | **No** (#117: Show tier, silent). It only re-reads a file the card can already show (§17.9) |
| **Open** | `ui.openApp` (`packages`) | Tap to open an app, or a launcher target (drawer, page list) | `Open Signal when you tap it` (the app label; the package if it isn't installed) | **No**, same rule as title-bar tap targets |
| **Open** | `ui.openUrl` (`hosts`: a bare hostname such as `anigeek.com` = https only; scheme-qualified `https://nas.lan` or `http://nas.lan` = that scheme for that host. No ports) | Tap to open a link in the browser | `Open links to anigeek.com when you tap it`. An http entry is written literally, `Open links to http://nas.lan when you tap it`, followed by the note `Links to http://nas.lan aren't private. Others on your network could see or change them.` | **Yes, always**: on first enable and whenever a host is added. It's in the Open tier, but a file-written link is the phishing path. Documented as the Open-tier split in #117 (`ui.openApp` silent, `ui.openUrl` prompts). Christopher confirms in Q-D5 |
| **Internet** (feature level 5, only if Q-D3 says yes) | `net.fetch` (`origins`: full `https://host[:port]` or `http://host[:port]`, approved by name; literal URLs only) | Fetch from named origins | `Connect to status.anigeek.com and blog.anigeek.com` (https hosts shown without `https://`, with `:port` only when it isn't 443; more than 3 → `and 2 more`, expandable). An http origin is always written in full, `Connect to http://nas.lan:3001`, followed by the note `The connection to http://nas.lan:3001 isn't private. Others on your network could see or change it.` | **Yes** |
| **Phone data** (later, no v1 kinds) | — (future `phone.*`) | Calendar, next alarm, battery, notification counts | `Read your calendar` · `See notification counts` | **Yes.** After `Allow`, Android may ask for its own permission too |
| **Act** (later, no v1 kinds) | — | Write back to the data file, toggles, webhooks. In v1 buttons only Open | `Change values in widgets-data/w-anigeek.json` | **Yes** |
| **Never offered** | — (not in the schema) | Send SMS, place calls, read contacts or messages, mic, camera, location, VPN or tunnel control, run code | — | The schema has no kind for these (the iappyx lesson: one bridge for every widget). One written anyway is an unknown kind: the phone skips that element (§17.3), and `validate.py` reports it |

**Hosts and schemes (Christopher: guardrails and information, not blocks).** https is the default. Plain http is
allowed only for a host or origin the user approves **by name**, shown literally as `http://…` with the not-private
note, so the choice is visible. There's **no silent downgrade**: approving `anigeek.com` (https) doesn't cover
`http://anigeek.com`, and a widget that switches a host to http is broadened (`Needs your OK again`). Hosts match
**exactly**, after normalising (IDNA → lowercase ASCII / punycode, #117). There are no implied subdomains:
`anigeek.com` doesn't cover `blog.anigeek.com` (list both), `blog.anigeek.com` doesn't cover `anigeek.com`, and neither
covers `anigeek.com.evil.net`. `hosts` entries carry no port (ports only via a full `net.fetch` origin). The prompt and
the detail show the normalised punycode form (`xn--…`) the phone enforces, so lookalikes are visible. A user name or password in a URL
(`https://anigeek.com@evil.net`) is never allowed.

**Combination line (later).** When a widget asks for **Phone data** *and* **Internet**, the prompt adds a final line in
bold: `It could send what it reads from your phone to these sites.` It can't happen in v1, because there are no `phone.*`
kinds. (There's no line like this for Show + Internet: the widget's data file is already the agent's.) When phone
data kinds arrive, phone data must never be put into a link or an Act target (Claw #1). That's a rule for Archimedes'
schema, and until it exists that pair gets this line too.

**Write-back is input to the agent (later, threat note; Claw #5).** An Act kind that writes values (e.g. a toggle into
the widget's data file) creates a channel back into what the agent reads. What the phone writes is **untrusted input to
the agent**, because someone holding the phone, or a bug, can put anything there. It's bounded to the keys and value
types the widget declares (e.g. `lights.on: boolean`), and nothing else is written. Agents should treat it as **data,
never instructions**: a written string is a value to act on by the agent's own rules, not a prompt. AGENTS.md (§7.1)
will say so when write-back ships.

### 17.2 When the prompt appears
Rule inherited from §16 (research §5.3): **a file load never opens a dialog.** A file change only *marks* a widget, and
the prompt opens from a tap.

**One approval model (Claw #3; adopted by Archimedes as the approval *ceiling*, Q-A9).** Each widget `id` has one
stored **approved set** on this phone, a **cumulative union** that approvals at different times add to: every capability line
(each kind with each of its `packages` / `hosts` / `origins` entries; `data.file` and `ui.reload` are a line each) the user has allowed for it. `Allow` adds
the lines the prompt showed. The set **never shrinks** when the file narrows; only `Remove from home` or the element
leaving the file (GC) clears it. On every load:
- **Everything the widget declares is within the approved set → live, no prompt, no badge, no dot.** This covers
  narrowing, returning to an earlier version, and any mix of approved lines.
- **Anything beyond it → `Needs your OK again`** (or `Needs your OK` when the set is empty). Only the lines outside the
  set are marked `New`.
- The one exception (later): the **first time** a widget has both Phone data and Internet, the combination prompt
  (§17.1 combination line) shows once, even if each was approved on its own earlier. After `Allow` the pair joins the
  set like any other line.

`Turn off` (§17.6) is separate from the approved set. It's a sticky off switch that wins until the user picks `Allow`
again, and it leaves the set unchanged.

| Trigger | Where it's detected | What happens on the grid | Prompt |
|---|---|---|---|
| **Added on this phone** (picker, §16.1, a template or a first-party type) | The add action | Placed and live once allowed | **Inline, before placing**, only if it needs OK (a Show/Open-only widget is placed straight away). `Keep off` cancels the add, so nothing is written |
| **Added in the file** (by an agent, a sync, a PC edit) | Reload | Disabled tile `Needs your OK` (§17.3) | From a tap on the tile, or the review screen (§17.4) |
| **Broadened** (a new kind, or a new entry inside one: a new origin, hostname, package or file, or https → http for a host) | Reload: a line outside the approved set → `needsApprovalAgain` | **Disabled** with `Needs your OK again`. The old version isn't kept running (its definition is gone) | `"<Title>" wants more access` (§17.5), new lines first |
| **Narrowed, or back to an earlier version** (everything still within the approved set) | Reload | Stays (or becomes) live. No dot, no badge. The approved set is unchanged | None. A quiet history entry in the widget detail (`10:42 · No longer asks to connect to status.anigeek.com`) |
| **Data bindings changed** (different `{data:…}` paths, a new layout, same capabilities) | Reload | Stays live (not consent-relevant, since layout isn't in the fingerprint) | None, and no history entry |
| **Element `id` changed** | Reload | Treated as a new widget: `Needs your OK`. Its data file changes too, because the path comes from the `id` (`widgets-data/<newId>.json`), so the agent must rename it | As "Added in the file". The old approval is dropped at the next GC (like widget bindings) |
| **Same file, another device** (tablet) | First load there | `Needs your OK` (approvals don't sync, A1) | As "Added in the file" (Q-D6) |

**Who changed it.** The launcher can't know who edited the file, so it never claims to. It only knows **"on this
phone"** (its own write, history-tracked) vs **"in launcher.json"** (anything else). If the element has the optional
`author` field (string, max 40, bidi and zero-width characters stripped; Q-A4), it's shown as a quote: `Changed in launcher.json · says "Claw"`. It is never a trust signal: an agent and a
person editing on a PC get the same prompt, and no author is ever auto-approved (Q-D7).

**Phone edits never broaden.** In this release the phone edits only placement, size and the §16.6 presets. It never
edits capabilities, so a phone-side edit can't trigger a prompt.

### 17.3 A disabled widget on the grid
A disabled widget **never renders its content**: no data is read, nothing is fetched, and its own text isn't drawn (it
could imitate system UI, e.g. a fake "Tap to verify your account"). It shows a **consent tile** in its cells, built like
the §16.2 placeholder so every non-live widget looks like the same family:
- 12 dp-radius frame (scaled, §17.8), white 12 % fill, **1 dp dashed white 40 % outline** (the dash is the "not live"
  cue; amber stays reserved for size problems, §16.5).
- Centre: a 24 dp `shield` outline icon, then the widget's **title** from the file (14 sp, 1 line, bidi and
  zero-width characters stripped, §13), then the reason (12 sp, secondary).
- 1×1: icon only, with the title and reason in TalkBack and on long-press. 2×1: icon + reason, no title.

| State | Reason (exact) | Tap |
|---|---|---|
| New, never allowed | `Needs your OK` | Prompt (§17.5) |
| Broadened | `Needs your OK again` | Broadened prompt |
| User chose `Keep off` on a prompt | Unchanged (`Needs your OK` / `Needs your OK again`) | Prompt again |
| User revoked it | `Turned off` | Same |
| Above this launcher's feature level (`requires`, e.g. `net.fetch` at 5 on a level-4 phone) | `Update Config Launcher to show this` | Hint sheet. Same skip-and-keep rule as §15, but it **keeps its cells and shows the tile**, because a dashboard card going missing looks broken |
| Declares a capability `kind` this launcher doesn't know (a newer kind that's missing its `requires` bump, a typo, or a never-offered kind) | `Update Config Launcher to show this` | Same hint sheet and skip-and-keep: cells kept, the element stays in the file, and nothing is rendered or granted. The `status.json` warning names the kind (below), so a typo is visible to the agent even though the phone's copy suggests an update. No prompt, no B2 |
| Data file missing or unreadable on first load | *(live; values show `—`, `as of` gets ▲, §17.9)* | — |

Warnings for the two skip rows (Warnings section + `status.json`, §15.1 style; the `consent` map shows no `state` for a
skipped element):
- `requires` too high: the existing §15.1 `warn_element_level` text.
- Unknown kind: `elements[4]: capability kind "phone.calendar" isn't supported by this Config Launcher, so the widget was skipped. It stays in the file. Update the app, or check the kind for a typo.`
```xml
<string name="warn_capability_kind">%1$s: capability kind \"%2$s\" isn\'t supported by this Config Launcher, so the widget was skipped. It stays in the file. Update the app, or check the kind for a typo.</string>
```

Long-press: the item sheet with the title, `Widget · from launcher.json`, then `Review`, `Edit layout`,
`Remove from home`. TalkBack: `<Title>, widget, needs your OK. Double-tap to review.`

### 17.4 The badge, the notice and the review list
**Count:** the number of widgets in `Needs your OK` or `Needs your OK again` (not `Turned off`; that was the user's choice).

- **Banner notice B9** (lowest priority, like B8: shown only when no B1–B7 banner is up). It appears when a reload
  *raises* the count: `2 widgets need your OK.` (`1 widget needs your OK.`) · buttons `Review` · `×`. `×` collapses it
  into the count chip below, and it isn't shown again until the count goes up. No snackbar is added. The §9 reload
  snackbar is unchanged.
- **Count chip:** a 20 dp pill at the top end of the banner slot, `shield` icon + number, tap → review list. Shown while
  the count is > 0. If the B-banner ▲ chip is also showing, this chip sits to its start (left in LTR).
- **⋮ / menu badges:** the same number on the home long-press menu's `Widgets` item (`Widgets · 2 to review`), on the
  interim ⋮ menu (R10) and on Settings → `Widget permissions` once Settings exists. A ⋮ icon by itself only gets a dot, not
  a number, because a number on ⋮ doesn't say what it counts.

**Review list** (`Widget permissions`, full screen, from all of the above). Sections, each hidden when empty:
1. `Needs your OK (2)`: one row per widget with the title, `Page 2 · 4 × 3`, a one-line summary (`New: Internet,
   links`) and the source (`in launcher.json · 10:42`, plus the author quote if present). Tap → prompt.
2. `Allowed (5)`: title and capability chips (`Show`, `Open`, `Internet`). Tap → detail.
3. `Turned off (1)`.
4. `Android widgets (3)`: hosted AppWidgets, read-only, `Allowed by Android` (A5). Tap → App info.

There's no `Allow all` button. Each widget gets its own decision (Q-D2). The empty state is `No dashboard widgets yet.`

### 17.5 Prompt copy
A bottom sheet (not a dialog, so it can scroll and show a preview), opened only from a tap.

**New widget**
```
┌──────────────────────────────────────┐
│ Allow "Anigeek LLC"?                 │
│ Added in launcher.json · 10:42       │
│ says "Claw"                          │
│ ┌──────────────────────────────────┐ │
│ │ [preview: layout with — for data]│ │
│ └──────────────────────────────────┘ │
│ This widget can:                     │
│  · Show data from widgets-data/      │
│    w-anigeek.json in your config     │
│    folder                            │
│  · Connect to status.anigeek.com     │
│  · Open links to anigeek.com when    │
│    you tap it                        │
│  · Open links to http://nas.lan when │
│    you tap it                        │
│    Links to http://nas.lan aren't    │
│    private. Others on your network   │
│    could see or change them.         │
│                                      │
│ You can change this later in Widget  │
│ permissions.                         │
│            [Keep off]   [Allow]      │
└──────────────────────────────────────┘
```
- Title: `Allow "<Title>"?`. Source line: `Added in launcher.json · <time>` or `Added on this phone`.
- The **preview** draws the widget's layout with every data binding shown as `—` and no actions wired. It shows the
  shape, not data the widget hasn't been allowed to read.
- Lines are in tier order (§17.1), Show first. An http host or origin is always written in full with its not-private
  note directly under it (secondary text, ▲ glyph, not colour-only). The combination line goes last when it applies.
- Buttons: `Allow` (primary) · `Keep off`. Back or a swipe-down = no decision (the tile stays `Needs your OK`). There's no
  "always allow from this source" option.
- After `Allow`, Android runtime permissions follow if needed, one system dialog per permission, but only those that
  are missing. If one is denied, the widget is live, the affected bindings show `—`, and the detail shows `Android
  permission off: Calendar` with `Open settings`.

**Broadened**: title `"<Title>" wants more access`. The list is split into `New` (each line prefixed `New:`) and `Already
allowed`. Same buttons. `Keep off` leaves the approved set unchanged and the tile stays `Needs your OK again`
(it isn't the sticky `Turn off`). If the file comes back within the approved set, the widget is live again with no
prompt (§17.2).

**Strings**
```xml
<string name="consent_title">Allow "%1$s"?</string>
<string name="consent_title_more">"%1$s" wants more access</string>
<string name="consent_source_file">Added in launcher.json · %1$s</string>
<string name="consent_source_phone">Added on this phone</string>
<string name="consent_author">says "%1$s"</string>
<string name="consent_can">This widget can:</string>
<string name="consent_new_prefix">New: %1$s</string>
<string name="consent_already">Already allowed</string>
<string name="consent_combo">It could send what it reads from your phone to these sites.</string>
<string name="consent_footer">You can change this later in Widget permissions.</string>
<string name="consent_allow">Allow</string>
<string name="consent_keep_off">Keep off</string>
<string name="cap_show_file">Show data from %1$s in your config folder</string>
<string name="cap_open_app">Open %1$s when you tap it</string>
<string name="cap_net">Connect to %1$s</string>
<string name="cap_reload">Reload its data when you tap it</string>
<string name="reload_in_progress">Reloading…</string>
<string name="reload_a11y_updated">Updated</string>
<string name="reload_a11y_same">No new data</string>
<string name="tap_link_blocked">This link isn\'t allowed for this widget.</string>
<string name="warn_tap_host">%1$s: the tap link\'s host \"%2$s\" isn\'t in this widget\'s approved hosts, so the tap was ignored.</string>
<string name="warn_tap_scheme">%1$s: the tap link uses http, but \"%2$s\" is approved for https only, so the tap was ignored.</string>
<string name="warn_tap_userinfo">%1$s: the tap link has a user name or password in it, so the tap was ignored.</string>
<string name="cap_http_note">%1$s isn\'t private. Others on your network could see or change it.</string>
<string name="warn_card_title">%1$s: a card needs a title, so it was skipped. It stays in the file.</string>
<string name="warn_surface_none">%1$s: \"none\" needs room for the header row, so the default card surface was used.</string>
<string name="warn_tap_empty">%1$s: the tap link %2$s has no value, so the tap was ignored.</string>
<string name="cap_open_link">Open links to %1$s when you tap it</string>
<!-- later, no v1 kind: -->
<string name="cap_write_data">Change values in %1$s</string>
<string name="tile_needs_ok">Needs your OK</string>
<string name="tile_needs_ok_again">Needs your OK again</string>
<string name="tile_off">Turned off</string>
<string name="tile_update">Update Config Launcher to show this</string>
<plurals name="b9_widgets_review">
    <item quantity="one">%d widget needs your OK.</item>
    <item quantity="other">%d widgets need your OK.</item>
</plurals>
<string name="b9_review">Review</string>
```
`status.json` (Q-A3) has a `consent` map keyed by element id, each with `state` (`needsApproval` | `needsApprovalAgain` |
`off` | `allowed`) and an optional `warning` in the §15.1 style. It never holds the approval itself:
```json
"consent": { "w-anigeek": { "state": "needsApproval", "warning": "elements[4]: widget \"Anigeek LLC\" is waiting for approval on this phone. It isn't shown until then." } }
```
An agent can see it's blocked and tell the person to look at the phone, and it can't do anything about it itself.

### 17.6 Manage and revoke (per widget)
Widget detail (from the review list, or item sheet → `Review` / `Permissions`):
- Header: title, `Page 2 · 4 × 3`, source and last change (`Changed in launcher.json · 10:42 · says "Claw"`).
- `This widget can:` with the same lines as the prompt, each with a status (`Allowed`, `Android permission off`).
- **History** (last 5 consent-relevant changes on this phone, quiet: never a badge or dot): `10:42 · No longer asks to
  connect to status.anigeek.com`, `Yesterday · Allowed`.
- `Approved on this phone:` the full approved set, including lines the current version no longer uses (secondary
  text, `not used now`).
- Actions: `Turn off` (live widget) or `Allow` (off). `Turn off` takes effect immediately: the tile turns to `Turned off`,
  the data stops and the snackbar says `"<Title>" turned off.` [`Undo`]. **No file write**, because consent is device-local
  and the widget stays in the file. To delete the widget, use `Remove from home` (one write, §16.7).
- `Forget` isn't offered. `Turn off` + `Allow` covers it, and the approval is GC'd once the element leaves the file.
- Turning off never revokes Android runtime permissions the launcher holds (other widgets may use them). The detail
  links `Open settings` for that.

### 17.7 Accessibility (adds to §12)
- **Consent tile:** one node: `<Title>, widget, needs your OK. Double-tap to review.` Custom actions `Review`, `Move to…`,
  `Remove from home`.
- **Live widget:** the root node reads `<Title>, widget` followed by its children in reading order. Each primitive has a
  spoken form (§17.9). Custom actions: `Permissions`, `Resize…` (§16.4),
  `Move to…`, `Remove from home`.
- **Prompt:** focus starts on the title. The capability lines are a list (`List, 3 items`), and `New:` is read as `new,`.
  The combination line comes last before the buttons. `Allow` is not the initial focus.
- **B9 and chip:** polite live region, announced once, like B1–B8. The chip reads `Widget permissions, 2 need your OK`.
- **Font scale:** primitives follow the system scale up to 200 %, inside the tier caps (§17.8). Overflow drops content by
  `priority` and never clips text mid-glyph. Dropped content stays available to TalkBack (`, 2 more items`).
- **Reduced motion:** no shimmer on loading `—`. Progress changes are instant.
- **Colour:** status is never colour-only. A dot is always paired with a word or glyph (`up`, `down`, ▲, ■).

### 17.8 Component system: cell-relative layout
**Anti-example (KWGT, `kwgt-handmade-widget.jpg`):** hand-placed absolute positions and pixel font sizes (`ctx`/`5h`/`7d`
bars, `up 8h 44m`, `status: idle`, a mascot image under the text). It looks right on one phone and breaks on another
screen: elements overlap or float, text doesn't scale, and the card's height has no relation to the icon grid around it.
**Target (Claw card):** one surface, nested tiles, a clear title / source / value / label hierarchy, a small `as of`, and
status dots paired with words.

**Rules.**
1. **No coordinates inside a widget.** The file gives the widget's `w`×`h` in grid cells (§16). Inside it, layout is
   flow only: `column`, `row` (children with `weight`) and `tile` (an inset sub-surface). No `x`/`y`, dp, px or `%`
   offsets inside a widget.
2. **Everything resolves from the cell.** Let `cell` = the smaller of the rendered cell width and height in dp (≥ 64 dp
   by §1.1). Scale `k = clamp(cell / 80, 0.85, 1.5)`. Each token is a base value × `k`, rounded to the dp:

   | Token | Base | Use |
   |---|---|---|
   | `pad.card` | 12 dp | Card inner padding |
   | `pad.tile` | 8 dp | Tile inner padding |
   | `gap` | 8 dp | Between siblings (`gap.s` 4, `gap.l` 12) |
   | `radius.card` | 16 dp | Card corners (clamped 12–24) |
   | `radius.tile` | `radius.card − pad.card / 2`, min 6 | Nested corners stay concentric (Claw look) |
   | `icon` | 16 dp (`icon.l` 24) | Glyphs, app icons inside a widget |
   | `dot` | 6 dp | Status dot |
3. **Text tiers by size class, not device.** The size class comes from the widget's span: width **compact** (w ≤ 2),
   **regular** (3–4), **wide** (≥ 5); height **short** (h = 1), **tall** (h ≥ 2). Base sp (before the system font scale),
   then × `k` capped at 1.25 so a tablet doesn't get billboard text:

   | Role | Compact | Regular | Wide | Floor |
   |---|---|---|---|---|
   | `caption` (source, `as of`) | 11 | 12 | 12 | 11 |
   | `body` (default, matches the 14 sp of §15) | 13 | 14 | 14 | 12 |
   | `title` | 14 | 16 | 16 | 13 |
   | `metric` value | 18 | 22 | 28 (tall: 32) | 16 |

   With the system scale on top, the combined size never goes above 2× base and never below the floor.
4. **Responsive layouts, chosen not stretched.** A widget may declare up to 3 `layouts` keyed by minimum span (e.g.
   `2×1`, `4×2`, `4×3`). The launcher uses the **largest that fits** the actual span. If even the smallest doesn't fit,
   it uses the smallest and drops children by `priority` (low first), then ellipsizes. With **no** declared layouts, the
   single layout reflows by the same rules.
5. **Surface and contrast over wallpaper** (extends §12). The default `surface: "auto"` is a dark card, `#121218` at
   **≥ 85 %** opacity, which is the floor (Claw's feel). Worst case over pure white wallpaper (computed like
   `tools/contrast.py`): white text **12.1:1**, secondary white 70 % **6.8:1**, inside a 6 % white tile **10.1:1** /
   **5.9:1**; status dots green `#4CD964` **5.5:1**, amber `#FFC400` **6.3:1**, red `#FF6363` **3.4:1** (all ≥ 3:1 as
   non-text graphics). Red is never used for text: `down` is white with a red dot and ■. `surface: "light"` mirrors
   this. Any custom colour is measured against the surface: below 4.5:1 (text) or 3:1 (dots, bars) it **renders anyway
   with a §15-style warning** (`status.json` + Warnings section, no banner). `surface: "none"` (text right on the
   wallpaper, always with the header row, §17.9) gets the label shadow, plus the 60 % scrim behind each text run when `Label backdrop` is on (§1.1). It
   isn't measured.
6. **No images with baked-in text, no background images in v1.** Icons are named Material symbols or an installed app's
   icon. (A mascot under the text, as in the KWGT example, is what makes contrast impossible to guarantee.)

### 17.9 Primitives
Six content primitives plus three containers (`column`, `row`, `tile`). Every primitive takes `priority` (0–9, default
5), optional `showIn` (size classes) and an optional `status` (`ok`/`warn`/`error`/`none`), which draws the dot plus a
glyph for warn ▲ and error ■.

| Primitive | Shows | Overflow | Empty / stale / error | TalkBack |
|---|---|---|---|---|
| `text` | Template text (title-bar grammar + `{data:…}`), `role` caption/body/title, `maxLines` 1–4 | Ellipsize at the end | Binding missing → `—` | As written |
| `metric` | Label (caption), value (metric tier), optional unit and status, like `PicSpin site · up · 96 ms` | Label ellipsizes first, value never truncates. Too narrow → the unit is dropped, then the label | `—` | `PicSpin site, up, 96 milliseconds` |
| `list` | Rows from a data array: leading dot/icon, primary text, trailing caption (`down`). `maxItems` or `auto` (as many as fit) | `auto` shows what fits plus `+2 more` | Empty → `emptyText` (default `Nothing to show`) | `Unhealthy monitors, list, 1 item. Label Printer, down` |
| `icon` | Material symbol or app icon, `icon`/`icon.l` | Dropped by priority | — | Its `label`, or skipped if decorative |
| `progress` | Bar (or ring at 1×1), value 0–1, label + percentage (the ctx/5h/7d case, done right) | Label drops before the bar | `—` and an empty track | `ctx, 18 percent` |
| `button` | Label (+ icon), runs a declared action (`ui.openApp` / `ui.openUrl` / `ui.reload`; Act kinds later) | Icon-only at compact, label kept in TalkBack | Action not allowed → hidden, never greyed-and-tappable | `<Label>, button` |

**Card chrome (automatic, always drawn; Claw #4).** Every config widget gets a header row with the title (`title` role)
and its source: `as of 08:38` (caption) when the data has `updatedAt`, otherwise a 12 dp `dashboard` glyph that marks it as
a widget. It turns `as of 08:38 · stale` with ▲ after the widget's `staleAfter` (default 2× the agent's declared
interval, else 30 min). **The header can't be turned off.** At `short` heights (h = 1) it collapses to a leading title
caption on the same row, ellipsized before the content, so even a 1-row card says what it is. This way a live card
(even a Show-only one, which needs no prompt) can't pass for a system dialog, a notification or the status bar.
- `title` is required on every `card`. Missing or empty → that element is **skipped** (skip-and-keep, tile `Can't show
  this widget`) with the warning `elements[4]: a card needs a title, so it was skipped. It stays in the file.`
- `surface: "none"` is allowed only with the full header. A `none` card that's 1 row tall, or whose chosen layout
  leaves no room for the header, is **drawn on the default `auto` surface** instead, with the warning
  `elements[4].surface: "none" needs room for the header row, so the default card surface was used.` (No B2, no banner.)
  `validate.py` reports both cases the same way.

**Data states.** Loading on cold start: the last-good render is cached, so it never flashes `—` at every launch. The
first ever load shows `—`. A data file that's missing or unreadable keeps the last values, adds ▲ to `as of`, and writes a
`status.json` warning.

**Taps with data-built links (`ui.openUrl`).** A tap `url` may contain `{data:…}` (e.g. the latest blog post). At tap
time the launcher resolves it, then runs the host check **on the resolved URL**: it opens only if the host matches an
approved `hosts` entry **exactly** after the same normalisation (§17.1), the scheme matches that entry (https for a bare host, http only for an
approved `http://host` entry; `https://host` means the same as the bare host; never a silent downgrade), and there's no user name or password in it. Otherwise:
- **Nothing opens**, and the widget doesn't change. A short snackbar (4 s, no action) says
  `This link isn't allowed for this widget.` TalkBack announces the same text. It shows once per tap, never a dialog,
  and there's no "open anyway".
- A `status.json` warning (Warnings section too) says which rule failed, so the agent can fix the data, e.g.
  `elements[4]: the tap link's host "evil.example" isn't in this widget's approved hosts, so the tap was ignored.` or
  `elements[4]: the tap link uses http, but "anigeek.com" is approved for https only, so the tap was ignored.`
- If the binding has no value, the tap is ignored the same way, with the warning
  `elements[4]: the tap link {data:blog.url} has no value, so the tap was ignored.`
- Allowing a new host is a capability change (`hosts` broadened → `Needs your OK again`). The snackbar never offers it.

**Reload taps (`ui.reload`).** A tap or `button` with `{ "kind": "ui.reload" }` re-reads this widget's data file
right away instead of waiting for the next poll. It doesn't ask the agent for new data; it only re-reads the file.
- **Feedback:** the header's `as of` swaps to `Reloading…` (caption) for as long as the read takes, at least 400 ms
  (instant swap with reduced motion). No snackbar. Then the header shows the file's `updatedAt` as usual.
  TalkBack (polite) says `Updated` when the content changed, or `No new data` when it didn't.
- **No lying about freshness:** if the file is still older than `staleAfter`, the header keeps `· stale` ▲ after the
  reload. Reload never resets the stale clock, because only a new `updatedAt` does that.
- **Rate:** one re-read per widget every 2 s at most (the §5 poll interval). Extra taps in that window do nothing. A
  missing or unreadable file follows the data states above (last values kept, ▲, `status.json` warning).
- TalkBack: the button reads `<Label>, button`. A tile with a reload tap adds the custom action `Reload`.

### 17.10 Phone → tablet (and any span change)
The same `launcher.json` syncs to a tablet. Cells are bigger there (and Q-D4 may give it its own grid).
- **Same span, bigger cells:** `k` rises (tokens grow up to 1.5×, text up to 1.25×). Spare room goes to `list auto`
  rows, then to `showIn: ["wide"]` children, then to padding. The text doesn't become a poster.
- **Bigger span** (the user resizes on the tablet, or the tablet layout gives more): the next declared `layouts` entry
  takes over at its threshold, as a cross-fade (instant with reduced motion).
- **Smaller span** (narrower grid, or resized down): the next smaller layout, then priority drops, then ellipsis. It
  never scales text below the floor, never clips, and never overlaps neighbours. If the span is under the widget's
  declared `minSpan`, it renders at that span with the §16.5 amber outline + ▲ and the warning `elements[4]: span 2×1 is
  below this widget's minimum 3×2. Shown at 2×1 anyway.`
- **Grid too small for the position** (an `x`+`w` past the columns): a validation error (B2, §6). There's no per-device
  grid schema in v1 (Q-A6): one `grid` in the file, and cell size and `k` absorb phone vs tablet.
- **Approval doesn't travel** (A1): on the tablet, the first load shows `Needs your OK`, the tile keeps the widget's
  real cells, and the dashboard comes alive one tap per widget.

### 17.11 Sample declaration (UX terms only; **Archimedes owns the schema**)
```json
{
  "id": "w-anigeek",
  "type": "card",
  "requires": 4,
  "page": "home", "x": 0, "y": 4, "w": 4, "h": 3,
  "minSpan": { "w": 3, "h": 2 },
  "title": "Anigeek LLC",
  "author": "Claw",
  "surface": "auto",
  "capabilities": [
    { "kind": "data.file" },
    { "kind": "ui.reload" },
    { "kind": "ui.openUrl", "hosts": ["anigeek.com", "blog.anigeek.com", "http://nas.lan"] }
  ],
  "data": { "staleAfter": "PT30M" },
  "layouts": {
    "2x1": [ { "type": "metric", "label": "Monitors", "value": "{data:monitors.summary}", "status": "{data:monitors.status}" } ],
    "4x3": [
      { "type": "tile", "children": [
        { "type": "text", "role": "caption", "text": "Latest on blog.anigeek.com" },
        { "type": "text", "role": "body", "text": "{data:blog.title}", "maxLines": 2 }
      ], "tap": { "kind": "ui.openUrl", "url": "{data:blog.url}" } },
      { "type": "row", "children": [
        { "type": "metric", "label": "PicSpin site", "value": "{data:picspin.value}", "status": "{data:picspin.status}" },
        { "type": "metric", "label": "letmeaithatforyou", "value": "{data:lmaitfy.value}", "status": "{data:lmaitfy.status}", "priority": 4 },
        { "type": "metric", "label": "Homelab monitors", "value": "{data:monitors.summary}", "status": "{data:monitors.status}" }
      ] },
      { "type": "list", "label": "Unhealthy monitors", "items": "{data:unhealthy}", "maxItems": "auto", "emptyText": "All monitors up",
        "tap": { "kind": "ui.openUrl", "url": "http://nas.lan/status" } },
      { "type": "button", "label": "Reload", "icon": "refresh", "priority": 2, "tap": { "kind": "ui.reload" } }
    ]
  }
}
```
Normative names from the [#116 comment](https://github.com/Nasawa/config-launcher/pull/116#issuecomment-6270653131): the kinds `data.file` / `ui.openApp` / `ui.openUrl` / `net.fetch`, `author`,
`minSpan` `{w,h}`, `layouts` keyed `"WxH"` with a **lowercase `x`** (confirmed, e.g. `"4x3"`, pattern `^[1-9][0-9]*x[1-9][0-9]*$`; max 3), `priority` 0–9, `showIn`, `staleAfter` (ISO-8601 duration), and
`updatedAt` (ISO-8601) at the root of the data file. Capability entries use kind-specific fields, matching #117:
`data.file` and `ui.reload` → no fields, `ui.openApp` → `packages`, `ui.openUrl` → `hosts`, `net.fetch` → `origins` (exact
`https://host[:port]`, or `http://` when approved by name). Taps are `{ "kind": "ui.openUrl", "url": "…" }`, `{ "kind": "ui.openApp", "package": "…" }` or `{ "kind": "ui.reload" }`.
`net.fetch` URLs are literals and never use `{data:…}`. A `ui.openUrl` tap `url` may use `{data:…}`: the host check runs on the
**resolved** URL at tap time, so the data file can only choose among approved hosts (§17.9). `data.file` has no `path`: the file comes from the element `id`, so this card reads
`.configlauncher/widgets-data/w-anigeek.json`, which the agent writes, e.g.
`{ "updatedAt": "2026-10-19T08:38:00-06:00", "picspin": { "value": "up · 96 ms", "status": "ok" }, "unhealthy": [ … ] }`.
Prompt: `ui.openUrl` only (`Open links to anigeek.com, blog.anigeek.com and http://nas.lan when you tap it`, with the
not-private note under `http://nas.lan`). `data.file` and `ui.reload` are Show-tier lines and don't prompt. Without the
`ui.openUrl` taps, the card would be Show-only and go live with no prompt (Q-D1). `requires: 4` is the card + local-data level. Adding
`net.fetch` would make it 5.

### 17.12 Build checklist additions (Sprite, when scheduled)
- [ ] Consent store (device-local), approved-set check on every load (it never shrinks), the states in §17.2, no dialog from a load.
- [ ] Consent tile (§17.3), B9 + count chip + menu badges (§17.4), review list, prompt sheet with preview, detail with
  Turn off + Undo, `status.json` consent fields.
- [ ] Tokens and tiers (§17.8), six primitives + three containers (§17.9), layout selection and priority drops, card
  chrome with `as of` / stale.
- [ ] Contrast measurement for custom colours (warnings only), 200 % font and TalkBack passes on a phone and a tablet.


## Open questions for Christopher


All answered by Christopher:
- ~~Q-C1~~ Yes: `Skip for now` with app-only storage, connect a folder later in Settings (§7, §8). Architecture: resolved by §18.2.
- ~~Q-C2~~ Search-first: the drawer opens with the field focused and the keyboard up, with TalkBack and hardware-keyboard rules (§2).
- ~~Q-C3~~ Drawer only. No Unsorted home page ("let's not invent iOS"). Schema: resolved by §18.1 (`unsorted.mode` is never acted on; `page` behaves as `drawer`).
- ~~Q-C4~~ `Label backdrop` is a v1 setting, default off (§18.1; §1.1, §8, §12).
- ~~Q-C5~~ JSON confirmed (no comments; `x-note` keys).
- ~~Q-C6~~ 10 app-private history copies confirmed.
- ~~Q-C7~~ Resolved by ARCHITECTURE §17 Q12: work items stay in the file, greyed with a hint, never deleted. They go live again when the setting is turned on.
- Also open in ARCHITECTURE §14: sync tool, `.configlauncher/status.json` in your folder, work profile/private space use, distribution, your phone model, and widgets after the MVP.

Open (dashboard widgets, §17). Recommendations are in brackets:
- **Q-D1** Should a widget that only **shows** data from a file in your config folder (no internet, no phone data, no tap
  actions besides opening apps) go live with **no prompt**? [Yes: it can't reach anything the folder doesn't already
  hold, and prompting for every agent card trains people to tap Allow.]
- **Q-D2** Approval per widget as a whole, or per capability (e.g. allow the calendar but not the internet)? [Whole
  widget in the first release. Partial grants leave half-working cards that are hard to explain.]
- **Q-D3** The schema now has `net.fetch` (exact origins, https by default, feature level 5). Should the launcher ship it? It
  means adding the **INTERNET permission to the whole app**, for every user and every widget, which breaks the README's
  "no network access" promise even for people who never allow a fetch. [Ship level 4 first: your agent fetches on its
  own machine and writes `.configlauncher/widgets-data/<id>.json`, as the Claw card's numbers already could. Turn on
  level 5 only if you want cards that work with no agent running, and update the README promise then. Claw, the agent
  whose card this is, notes that no-fetch-first costs the dashboard nothing: it already fetches on its own machine.]
  **Note:** this differs from Grok Bot's earlier direction ("the launcher fetches from approved sites only"). Pick
  either. If you choose fetch, http to hosts you approve by name is allowed (§17.1).
- **Q-D4** Phone and tablet from one file: v1 has one `grid` for both (cells just get bigger, Q-A6). Do you want a
  per-device grid later? [Not unless the same grid feels wrong on your tablet. §17.10 handles bigger cells.]
- **Q-D5** v1 tap actions are `ui.openApp` and `ui.openUrl` only (no Act kinds). Is it right that opening an app goes
  live without a prompt while opening a link needs one? [Yes: a link an agent writes is the phishing path. Write-back
  and toggles (Act) stay later. Never intents with extras or scripts.]
- **Q-D6** Should an approval on your phone carry over to your tablet? [No: each device asks once per widget. Syncing
  approvals through the folder would let anything that can write the folder approve itself.]
- **Q-D7** Should any author (e.g. Claw) ever be trusted to add widgets without asking? [No, not in this release. The
  author is quoted, never verified.]

## UI questions for Sprite

- **Q-S1** Compose drag-and-drop across pager pages and into the dock: is `Modifier.dragAndDropSource/Target` enough, or a custom pointer-input layer?
- **Q-S2** Landscape: support it in the MVP (dock on the right) or lock portrait?
- **Q-S3** The architecture picks `kotlinx.serialization` (plus a `JsonElement` tree). Does it give line/column for parse errors, and positions for validation paths, or do we need a small tokenizer for the excerpts?
- **Q-S5** Fitting cells at 200 % font scale on small phones: confirm the label-hiding fallback is acceptable visually.
- **Q-S6** Dock with 6–7 items: is shrinking icons to a 48 dp minimum readable, or should the UI cap drags at 5 (the file still allows 7)?
- ~~Q-S4 poll cost~~ → resolved by ARCHITECTURE §4: one small cursor query every 2 s, foreground only.

## Architecture questions for Archimedes

Dashboard capability model (§17): all eight are answered in the [#116 comment](https://github.com/Nasawa/config-launcher/pull/116#issuecomment-6270653131) and applied in §17:

| Q | Resolution |
|---|---|
| ~~Q-A1~~ | ✅ Closed kinds: `data.file` and `ui.reload` (Show, no fields; the data file comes from the element `id`), `ui.openApp` / `ui.openUrl` (Open), `net.fetch` (Internet). No `phone.*` or Act kinds in v1. Never-offered kinds aren't in the schema |
| ~~Q-A2~~ | ✅ Fingerprint = each `kind` + its field (`packages` / `hosts` / `origins`; none for `data.file` / `ui.reload`) only, sorted. Refined by Claw #3 and Q-A9 (§17.2): compared against a per-widget approval ceiling (a cumulative union that never shrinks); anything within it is silent, anything beyond → `needsApprovalAgain` |
| ~~Q-A3~~ | ✅ `status.json` `consent` map keyed by element id: `state` (`needsApproval` / `needsApprovalAgain` / `off` / `allowed`) + optional `warning`. No approval secret in it |
| ~~Q-A4~~ | ✅ Real optional field `author` (string, max 40). Quoted, never trusted |
| ~~Q-A5~~ | ✅ `{data:…}` with dots + `[index]` only, `list` binds to an array, `updatedAt` at the data file root, `staleAfter` on the element. One file per widget: `.configlauncher/widgets-data/<elementId>.json` |
| ~~Q-A6~~ | ✅ `minSpan` `{w,h}`, `layouts` keyed `"WxH"`, lowercase `x` confirmed (`"4x3"`; max 3), `priority` 0–9 (default 5), `showIn`. Proposed levels: container 1, titleBar 2, appWidget 3, card local data 4, `net.fetch` 5. No per-device grid schema in v1 |
| ~~Q-A7~~ | ✅ Only APIs in the approved manifest are granted. **Revised:** an unknown `kind` skips only that element (warning, cells kept). New kinds ship with a feature-level bump, and elements using them set `requires`. `validate.py`/CI may stay strict |
| ~~Q-A8~~ | ✅ Hosted AppWidgets stay outside; listed as read-only `Allowed by Android` |

- ~~Q-A9~~ ✅ Adopted in full in the [#116 comment](https://github.com/Nasawa/config-launcher/pull/116#issuecomment-6271078404) and #117 at db580c4: `hosts` takes bare hostnames (https only) or
  scheme-qualified `http://host` / `https://host`; `origins` take full `http://` / `https://` origins. The prompt shows
  `http://` when it applies, and there's no silent https → http downgrade. Approval is a cumulative per-widget ceiling
  (a union that never shrinks), and the Phone data + Internet combination prompts once (§17.1, §17.2).

~~Follow-up (Pragma): an unknown `kind` rejecting the whole file~~ ✅ Resolved in the [#116 follow-up](https://github.com/Nasawa/config-launcher/pull/116) and [#117](https://github.com/Nasawa/config-launcher/pull/117): on the phone it skips
only that element (§17.3 tile `Update Config Launcher to show this`). New kinds bump the feature level and set
`requires`, and `validate.py`/CI may stay strict for typos.

All seven are answered in ARCHITECTURE §16 and applied above:

| AQ | Resolution |
|---|---|
| AQ-1 | ✅ `launcher.json`, schema at `schema/launcher-config.v1.schema.json` (+ `schema/examples/`) |
| AQ-2 | ✅ Canonical reformat on every launcher write. `x-` keys kept, other unknown keys invalid, key order not preserved. Reloads never write |
| AQ-3 | ✅ Errors reject the whole file (last working layout kept, B2). Grid 2–12 × 2–12 |
| AQ-4 | ✅ Temp write + verify, then `"wt"` write + verify, restore on failure. No SAF rename relied on |
| AQ-5 | ✅ Separate `id`, `package`, `activity`, `profile` (`personal`/`work`). Private space never written |
| AQ-6 | ✅ Hash check before writing, replay by ID. Hash, not `revision`, is the conflict key. Since §18.2 the merge is automatic, and B6 only appears with `Ask before merging` |
| AQ-7 | ✅ App-private `last-good.json`, survives process death and reboots, not uninstall |

| AQ-8 | ✅ §17: B3 (no access) = `Reconnect` only; B4 (readable, file missing) = `Create from current layout` + `Reconnect`. Never auto-created |
| AQ-9 | ✅ §17: B8 is the lowest-priority notice, copy `…older format (v{N}). It will be upgraded on your next change.`, actions `Upgrade now` (backup first) and `✕` (per file hash) |

Flags from Christopher's answers, both resolved in ARCHITECTURE §18:
- ~~AF-1~~ App-only mode: §18.2 (app-private `filesDir/config/source/launcher.json`, Choose folder later, Use phone storage).
- ~~AF-2~~ `unsorted`: `page` stays valid in the schema but `unsorted.mode` is never acted on, so `page` behaves as the drawer. No schema change.

- ~~AQ-10~~ Label backdrop: 60 % black scrim. White on it over a pure white wallpaper is 5.7:1, which meets 4.5:1.
- ~~AQ-11~~ Settled by AF-2: `unsorted.mode` is never acted on, so `page` behaves as the drawer, per Christopher.
