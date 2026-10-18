# Config Launcher — UX spec (P1, MVP)

Owner: Pragma (UX) · Builds from it: Sprite · Source: `docs/research-spike.md` (no ARCHITECTURE.md yet).
When an architecture doc lands, it wins for behaviour and this doc wins for wording. Conflicts go to
[Architecture questions](#architecture-questions-for-archimedes).

**Product in one line:** an Android home screen whose whole layout lives in one JSON file. Edits on the phone write
back to the file, and edits to the file show up on the phone.

**MVP scope (from spike §4):** home pages with an app grid and a dock, the app drawer, folders, the file in a
user-picked SAF folder, live reload, write-back on drag/add/remove, and schema validation with an error banner.
**Not in the MVP:** widgets, icon packs, AI/agent proposals (sketched in [Later](#11-later-reviewing-an-agents-proposed-change-not-in-the-mvp)),
notification badges, gestures beyond swipe-up.

Design principles:
1. **The file is the truth.** Every on-screen layout state can be written to the file, and every valid file can be shown on screen.
2. **Never lose a layout.** A bad file never blanks the home screen. The last good layout stays, and history snapshots exist.
3. **Never block the phone.** Errors appear as banners, never as modal dialogs over the home screen. Apps always launch.
4. **One gesture = one write.** No Save button.

Assumed file (for this doc; the schema is Archimedes' call, see AQ-1): `launcher.json` in the chosen folder.

```json
{
  "schema": 1,
  "revision": 42,
  "grid": { "columns": 4, "rows": 5 },
  "pages": [
    { "items": [
      { "type": "app", "id": "com.android.chrome/com.google.android.apps.chrome.Main", "x": 0, "y": 0 },
      { "type": "folder", "name": "Travel", "x": 1, "y": 0,
        "items": [ { "type": "app", "id": "com.google.android.apps.maps/com.google.android.maps.MapsActivity" } ] }
    ] }
  ],
  "dock": [ { "type": "app", "id": "com.google.android.dialer/.extensions.GoogleDialtactsActivity" } ],
  "drawer": { "hidden": ["com.example.bloat/.Main"] }
}
```

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
| Grid | `grid.columns` × `grid.rows`, default **4 × 5**. Allowed 3–6 columns, 4–7 rows (AQ-3). Cells fill the space between the banner slot and the page indicator evenly |
| Cell | Icon **56 dp** centred, label below. **Minimum cell 64 × 80 dp**. If the screen can't fit the configured rows at that size, labels are hidden first, then icons shrink to 48 dp; the layout is never clipped |
| Label | 12 sp, 1 line, ellipsized at the end, centred, white with a soft shadow for wallpaper contrast (§12) |
| Pages | Horizontal pager. One page minimum. The page indicator shows only with ≥ 2 pages. Dots are 8 dp, current = filled |
| Dock | `dock[]`, **0–5 items** (default 4), same icon size, **no labels**, fixed on all pages. A 1 dp divider (white 24 %) above it |
| Wallpaper | System wallpaper, shown through (`windowShowWallpaper`) |
| Density | Uses dp/sp only. Phones only in MVP. Landscape: same grid, rotated pager, dock moves to the right edge (Q-S2) |

Gestures and taps (home):

| Action | Result |
|---|---|
| Tap app | Launch via `LauncherApps.startMainActivity` |
| Tap folder | Open folder (§3) |
| Tap dimmed (not installed) | Snackbar `Banking isn't installed.` with action `Remove` (one write) |
| Long-press app or folder | Item menu: `Edit layout`, `App info`, `Remove from home` (and pinned shortcuts later) |
| Long-press empty space | Home menu: `Edit layout`, `Reload layout`, `Settings` |
| Swipe up anywhere / tap ⌃ handle | Open the drawer (§2) |
| Swipe left/right | Change page |
| Home button while on home | Go to page 1, close drawer/folder |

### 1.2 Reconciliation with installed apps (spike §4)
- Config entries for **uninstalled or unknown** apps show as dimmed tiles (40 % opacity, `◌`) with their last known label,
  or the package name if unknown. They are **never deleted automatically**.
- Installed apps not in the config appear **only in the drawer**. No "Unsorted" home page in the MVP (Q-C3).
- Work profile apps: shown with the system work badge. Their config id includes the profile (AQ-5).

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
| Search | Field at top, hint `Search apps`. **The keyboard does not open automatically** (Q-C2). Filters live on each keystroke: case- and accent-insensitive match on the label start or word start, then anywhere. Enter launches the first result. No results: `No apps match "xyz".` |
| Sorting | **A–Z only** in MVP, by label with locale-aware collation (`Collator`). Numbers before letters |
| Hidden apps | Listed in `drawer.hidden[]`. Not shown in the list or search. Menu `⋮` → `Show hidden apps` toggles a section at the bottom titled `Hidden apps (3)`. Long-press a drawer app → `Hide`; long-press a hidden app → `Unhide`. Each is one write. Home-screen items stay visible even if hidden from the drawer |
| Long-press app | `Add to home` (first free cell, page 1 onward, else a new page), `Hide`, `App info`. Dragging out of the drawer enters edit mode on home (§4) |
| Menu `⋮` | `Show hidden apps` (checkbox), `Settings` |

---

## 3. Folders

- Defined as `type: "folder"` with `name` and `items[]` (apps only; no nested folders).
- Tile: 56 dp rounded square showing the first 4 app icons in a 2 × 2 grid, label = `name`.
- **Open:** a centred card over a dim scrim (50 % black). Title = folder name, then a grid of 4 columns (3 if
  `grid.columns` is 3). It scrolls if it has more than 4 rows. Tap outside, Back or Home closes it.
- **Create:** in edit mode, drop an app onto another app → a folder named `Folder` (localised), opened with the name
  field focused. One write.
- **Rename:** in edit mode, tap the folder title. A text field, max 30 characters. Empty → reverts to the previous name. Written on Done or close.
- **Add/remove:** drag into or out of the open folder (edit mode). A folder with **one** app left stays a folder. A folder with **zero** apps is deleted.
- Dock folders are allowed (same behaviour).

---

## 4. Edit mode and drag

### 4.1 Entering and leaving
- Enter: `Edit layout` from any long-press menu, or long-press and **drag** an item (drag starts after 300 ms + 8 dp of movement).
- On screen: a top bar replaces the banner slot: `Editing layout` on the left, **`Done`** button on the right. Every
  cell shows a faint outline (white 16 %), so empty cells are visible. No jiggle animation (§12 reduced motion).
- Leave: `Done`, Back, Home, or 30 s with no interaction.
- Edit mode is **unavailable while the file is invalid or unreachable** (§6). Trying shows the snackbar
  `Fix launcher.json first, or restore the last working version.`

### 4.2 Drag targets

| Drop on | Result |
|---|---|
| Empty cell | Move there |
| Occupied cell (app) | Create a folder (§3) |
| Folder | Add to that folder |
| Screen edge (hold 600 ms) | Turn to the previous/next page. Past the last page → creates a new empty page |
| Dock slot | Move into the dock (if 5 are full, the drop is refused with a short shake, or a fade with reduced motion) |
| `Remove` target (top bar, appears while dragging) | Remove from home. The app stays installed and in the drawer |
| `App info` target | Opens system app info, and the item doesn't move |

There's no automatic reflow: other items never move to make room (keeps the file diff minimal and predictable). An
empty page is deleted when you leave edit mode.

### 4.3 How each edit writes back

Each finished gesture (drop, rename, hide, remove, add) makes **one** model mutation and **one** write:

1. Apply the mutation to the in-memory model (the UI updates immediately).
2. Increment `revision`.
3. Serialize to canonical JSON: 2-space indent, the documented key order, **unknown keys preserved** as-is (AQ-2).
4. **Check for an external change:** read the file's current content hash. If it differs from the hash of the
   version we last loaded or wrote → conflict (§4.4). Don't write.
5. Snapshot the current file to `.history/launcher-<revision>-<timestamp>.json` (keep the last 20).
6. Write to a temp file, then replace `launcher.json` atomically (AQ-4). Remember the new hash, so our own write doesn't trigger a reload.
7. Failure (I/O, revoked grant) → the edit stays on screen, and the banner `Couldn't save your change to launcher.json.` with `Retry` appears. Edits pile up in memory and are written together on Retry.

Rapid gestures within 500 ms are coalesced into one write (one snapshot). There's no "saved" toast; success is silent.

### 4.4 External changes and conflicts

| Situation | Behaviour | Copy |
|---|---|---|
| File changed, valid, **not** in edit mode | Reload and re-render in place (no page jump; the open drawer or folder stays open if it still exists) | Snackbar (3 s): `Layout updated from launcher.json.` |
| File changed while **dragging** | Hold the reload until the drag ends, then run the conflict check | — |
| File changed **and** we have a local edit to write (hash mismatch at step 4) | Nothing is overwritten. The conflict banner shows | `launcher.json changed while you were editing.` Actions: **`Use file`** (discard my last edit, load the file) · **`Keep mine`** (write my version; the file's version goes to `.history/`) |
| File changed and is invalid | §6 error banner. Local edits are blocked | see §6 |

Both versions always survive in `.history/`, so neither button loses data. A sync-tool merge (git) is outside the app.

---

## 5. Reload

- Automatic: a `ContentObserver` on the SAF document, plus polling every **2 s while the home screen is visible**, and on every return to home (`onResume`). FileObserver doesn't work on SAF (spike §3).
- Manual: long-press empty space → `Reload layout`.
- A reload with an unchanged hash does nothing. A reload never moves the user to another page or closes the drawer unless the open item no longer exists.

---

## 6. Problems: invalid config, schema errors, missing access

Rule: **keep showing the last good layout**, show a **non-blocking banner** at the top of the home screen (below the
status bar, above row 0), and offer a detail view. Apps keep launching. Edit mode is disabled until it's resolved.

### 6.1 Banner
- Full-width card, 12 dp margin, amber ▲ icon (error red ■ for access loss), 1–2 lines of text, one or two text buttons, and `×`.
- `×` collapses it to a small ▲ chip in the top-right corner that reopens it. It stays until the problem is fixed (no auto-dismiss).
- When fixed: the banner disappears and the snackbar `Layout loaded.` shows.
- TalkBack: announced once when it appears (polite live region).

| ID | Cause | Banner text (exact) | Buttons |
|---|---|---|---|
| **B1** | JSON syntax error | `Can't read launcher.json. Showing your last working layout.` | `Details` |
| **B2** | Schema or validation error (wrong type, out-of-range x/y, overlap, too many dock items, unknown `type`, newer `schema`) | `launcher.json has 3 problems. Showing your last working layout.` (`1 problem` singular) | `Details` |
| **B3** | Folder access revoked or folder gone (persisted URI permission missing) | `Can't reach your layout folder. Showing your last working layout.` | `Choose folder` |
| **B4** | Folder fine, `launcher.json` missing | `launcher.json is missing from "<folder name>".` | `Recreate` · `Choose folder` |
| **B5** | Write failed (§4.3) | `Couldn't save your change to launcher.json.` | `Retry` |
| **B6** | Conflict (§4.4) | `launcher.json changed while you were editing.` | `Use file` · `Keep mine` |
| — | First launch ever with a bad file and no last good layout | Starter layout from installed apps (in memory only, never written over the bad file) + B1/B2 | as above |

`Recreate` writes the last good layout to `launcher.json` (one write). Unknown-package entries are **not** errors (§1.2).

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
│ [ Copy details ]  [ Reload ]         │
│ [ Restore last working version ]     │
└──────────────────────────────────────┘
```

- Each problem: `Line L, column C` (1-based), a plain-language message, the JSON path for schema errors, and a 3-line monospace excerpt with the line marked `▶`. Syntax errors stop at the first error. Schema errors list **all** of them, max 50, then `…and 12 more.`
- `Copy details`: plain text with all problems and the file name, for pasting to an agent or chat.
- `Reload`: re-read the file now.
- `Restore last working version`: confirm dialog `Replace launcher.json with your last working layout? The current file is saved in .history first.` [`Cancel`] [`Replace`].

### 6.3 Error message wording (examples)

| Problem | Message |
|---|---|
| Syntax | `Expected "," or "}" after a value.` · `Unexpected end of file.` · `Text must be in double quotes.` |
| Range | `pages[0].items[3].x must be 0–3 (the grid has 4 columns).` |
| Overlap | `pages[1].items[2] and pages[1].items[5] are both at column 2, row 1.` |
| Dock | `dock has 6 items; the maximum is 5.` |
| Type | `pages[0].items[0].type "widget" isn't supported yet. Use app or folder.` |
| Missing | `pages[0].items[4] needs an "id".` |
| Schema version | `This file uses schema 2, but this version of the launcher understands up to schema 1. Update the app.` |

---

## 7. First run: choosing the layout folder (SAF)

Shown on first launch, before the home screen. Each step is one full screen with one primary button.

1. **Welcome**
   - Title: `Your home screen, as a file`
   - Body: `Config Launcher keeps your whole layout in one file, launcher.json. Change it here or in any editor, and both stay in sync.`
   - Button: `Get started`
2. **Pick a folder**
   - Title: `Choose where your layout lives`
   - Body: `Pick a folder for launcher.json. A folder that a sync app (like Syncthing) or a git app keeps in sync lets you edit it from your computer, with history.`
   - Primary: `Choose folder` → system picker (`ACTION_OPEN_DOCUMENT_TREE`). Take a persistable read+write grant.
   - Secondary: `Use app storage for now` → internal file, no external editing. It can be changed later in Settings (Q-C1).
   - Picker cancelled → stay here, no message.
   - A system-restricted folder (e.g. Download root on Android 11+) is refused by the picker itself.
3. **Folder result**
   - **Contains a valid `launcher.json`:** `Found launcher.json with 3 pages and 24 apps.` Buttons: `Use this layout` · `Start fresh` (backs up the existing file to `.history/` first).
   - **Contains an invalid `launcher.json`:** `launcher.json in this folder has problems.` Buttons: `See problems` (§6.2) · `Start fresh` (backup first) · `Choose another folder`.
   - **No file:** `We'll create launcher.json here with a starter layout.` Button: `Create layout`. The starter layout has a dock with the default phone, messages, browser and camera apps (from role and default-app queries, when found), and page 1 empty. All apps are in the drawer.
4. **Make it your home app**
   - Title: `Set as home app`
   - Body: `To use Config Launcher when you press Home, set it as your default home app.`
   - Button: `Set as home app` → `RoleManager.createRequestRoleIntent(ROLE_HOME)`. Secondary: `Not now`.
   - If declined: home still works inside the app. Settings shows `Not your home app` with a `Set as home app` button.
5. Land on the home screen. One-time hint chip: `Long-press empty space to edit your layout.` (dismiss ×, never shown again).

**Later, in Settings → Layout file:** shows the folder name and the last load time, `Change folder`, `Open history folder`
(via the SAF document UI if possible), and `Reload layout`.

**Revoked or missing (B3/B4):**
- `Choose folder` reopens the picker. Picking a folder with a valid file loads it. Picking one without a file offers `Create layout` from the last good layout (not the starter).
- Revocation is detected on start, on resume, and on any failed read or write.

---

## 8. Settings (MVP, minimal)

`Layout file` (folder, change, reload, history) · `Home app` (status + button) · `Grid` (columns/rows pickers. These write
`grid` like any edit; shrinking that would push items off-grid is refused with `Move apps out of the last column first.`) ·
`About` (version, licenses incl. GPL notices if any Kvaesitso code is used).

---

## 9. Feedback summary (snackbars and toasts)

| When | Text |
|---|---|
| External reload | `Layout updated from launcher.json.` |
| Problem fixed | `Layout loaded.` |
| Not installed tap | `<Label> isn't installed.` [`Remove`] |
| Edit blocked | `Fix launcher.json first, or restore the last working version.` |
| Removed from home | `Removed <Label> from home.` [`Undo`] (Undo = a second write restoring it) |
| Hidden | `<Label> hidden from the app list.` [`Undo`] |

---

## 10. Copy style guide

- Plain, calm, short. Sentence case. Second person only when needed ("your layout").
- Always name the file exactly: `launcher.json`, in code style where possible.
- Say **layout**, **folder**, **app list** or **drawer**, and **home screen**. Avoid "config", "URI", "SAF", "schema"
  (except the schema-version message), "JSON path" in body text (paths appear only inside error messages).
- Errors say what happened + what still works + what to do: `Can't read launcher.json. Showing your last working layout.`
- No exclamation marks, no "Oops", no blame.
- Line and column are 1-based and always written `Line 12, column 7`.
- Buttons are verbs: `Choose folder`, `Retry`, `Use file`, `Keep mine`, `Done`.
- All strings in `strings.xml`, with plurals via `<plurals>` (`1 problem` / `3 problems`).

---

## 11. Later: reviewing an agent's proposed change (NOT in the MVP)

For a future exported intent or a companion CLI (spike §3 option 3, Mouture-style "the model proposes, you decide").
The MVP doesn't need it: an agent edits the synced file directly, and the normal reload and errors apply.

- The agent submits a full proposed `launcher.json` (or a patch), plus a short `summary`.
- A notification: `<Agent> suggests a layout change.` Tap → **Review** screen:
  - Header: the summary text, the agent name, the time.
  - **Visual diff:** home preview with added (green +), moved (blue →) and removed (red −) items, page by page.
  - Toggle `Show JSON diff`: a unified diff, monospace, with +/− lines.
  - Validation runs first. An invalid proposal shows its problems (§6.2 format) and only `Reject`.
  - Buttons: **`Apply`** (one write, snapshot first, revision +1) · **`Reject`** (nothing written; the agent is told `rejected`).
- If the file changed since the proposal's base revision: `This suggestion is based on an older layout.` → only `Reject` or `Review again` (rebase is the agent's job).
- Never auto-apply. No allow-list of "trusted" agents in v1 of this feature.

---

## 12. Accessibility

| Area | Spec |
|---|---|
| TalkBack labels | App: `<Label>`. Not installed: `<Label>, not installed`. Folder: `<Name> folder, 5 apps`. Dock item: `<Label>, dock`. Empty cell in edit mode: `Empty, column 2, row 3`. Page indicator: `Page 2 of 3` |
| Actions | Every drag action also has custom accessibility actions on the item (`Move to…` with a page/row/column picker, `Add to folder…`, `Move to dock`, `Remove from home`, `Hide`), so the layout is fully editable without drag. Each is one write |
| Edit mode | Announce `Editing layout. Double-tap Done when finished.` Focus order: top bar, then grid in reading order, then dock |
| Banners | Polite live region, announced once. Buttons are focusable. The error detail view reads as a list of problems |
| Touch targets | ≥ **48 × 48 dp** for everything (cells 64 × 80 dp, dock slots 64 dp, chip ≥ 48 dp hit area, banner buttons 48 dp tall) |
| Font scaling | Labels and text use sp and follow the system font size up to 200 %. Grid labels stay 1 line and ellipsize; at ≥ 150 % labels may be hidden on the home grid (still spoken by TalkBack) if cells can't fit them. The drawer always shows labels (wrapping to 2 lines). Banners and settings wrap, never truncate |
| Contrast | Text in app UI (drawer, settings, banners, detail view) ≥ 4.5:1 and icons/controls ≥ 3:1, in light and dark (follow the system theme; Material 3 dynamic colour off for status colours). Home labels over wallpaper: white text with a 1 dp 50 % black shadow, plus an optional `Label backdrop` scrim setting (Q-C4) |
| Reduced motion | Respect `Settings.Global.ANIMATOR_DURATION_SCALE == 0` and "Remove animations": no page-slide spring, folder open as a fade, no shake (use a red outline), no jiggle ever |
| Colour | Status is never colour-only: ▲ warning / ■ error glyphs, dimmed tiles also say "not installed" to TalkBack |

---

## 13. Build checklist (Sprite)

- [ ] Home: pager + grid (`grid.columns/rows`, default 4 × 5) + dock (0–5), sizes from §1.1, wallpaper through.
- [ ] Not-installed tiles and reconciliation (§1.2). Never auto-delete.
- [ ] Drawer: search (§2 match rules, no auto keyboard), A–Z with `Collator`, hidden apps section, work tab.
- [ ] Folders: create/rename/add/remove/delete rules (§3), open card.
- [ ] Edit mode: entry/exit, outlines, drag targets (§4.2), no reflow, 30 s idle exit, disabled when the file is invalid.
- [ ] Write path (§4.3): mutate → revision++ → canonical serialize (preserve unknown keys) → hash check → snapshot → atomic write → remember hash. 500 ms coalescing. B5 on failure.
- [ ] Conflict (§4.4): hold during drag, B6 with `Use file`/`Keep mine`, both versions in `.history/`.
- [ ] Reload (§5): ContentObserver + 2 s poll while visible + onResume. Hash-skip. No page jump.
- [ ] Banners B1–B6 (§6.1), collapse chip, live region. Detail view (§6.2) with 1-based line/column and excerpts. Restore with confirmation.
- [ ] First run (§7) with exact copy, persistable grant, three folder outcomes, home role request, one-time hint.
- [ ] Settings (§8).
- [ ] Snackbars (§9) with Undo where listed.
- [ ] Accessibility (§12): labels, custom actions for every drag action, 48 dp targets, font scaling, reduced motion.
- [ ] All copy in `strings.xml` with plurals.
- [ ] Manual tests: edit the file on a PC via Syncthing while the phone is on home, in edit mode, and mid-drag. Corrupt the file. Revoke access in system settings. Delete the file. Font 200 %. TalkBack-only layout edit.

---

## Open questions for Christopher

- **Q-C1** Should `Use app storage for now` exist (easier start, no external editing), or should picking a folder be required?
- **Q-C2** Should the drawer open with the keyboard up (search-first, like KISS) or without it (the spec's default)?
- **Q-C3** Installed apps not in the layout: drawer only (spec), or also an automatic `Unsorted` home page (spike §4 mentions both)?
- **Q-C4** Home labels: plain white with a shadow (spec), or an optional dark backdrop behind labels for busy wallpapers?
- **Q-C5** JSON (spec assumption, which you asked for) vs YAML/JSON5 with comments. Comments are nice for hand edits but harder to preserve on write-back.
- **Q-C6** Is 20 history snapshots right, or rely only on git/Syncthing versioning?

## UI questions for Sprite

- **Q-S1** Compose drag-and-drop across pager pages and into the dock: is `Modifier.dragAndDropSource/Target` enough, or a custom pointer-input layer?
- **Q-S2** Landscape: support it in the MVP (dock on the right) or lock portrait?
- **Q-S3** Excerpts in the error view need line/column from the parser. Does the chosen JSON library (kotlinx.serialization, Moshi) report positions, or do we need a small tokenizer?
- **Q-S4** Can the 2 s poll be cheap enough (hash of a small file over SAF), or should it back off when the screen is idle?
- **Q-S5** Fitting cells at 200 % font scale on small phones: confirm the label-hiding fallback is acceptable visually.

## Architecture questions for Archimedes

- **AQ-1** File name and schema: `launcher.json` with `schema`, `revision`, `grid`, `pages[].items[]`, `dock[]`, `drawer.hidden[]` as sketched? Publish a JSON Schema file in the repo?
- **AQ-2** Write-back fidelity: canonical formatting with preserved unknown keys and key order, or byte-preserving edits? (This affects diffs in git.)
- **AQ-3** Grid limits (3–6 × 4–7) and the overlap/out-of-range rules: errors (whole file rejected, spec) or warnings with the item skipped?
- **AQ-4** Atomic replace over SAF: `DocumentsContract.renameDocument` isn't atomic on every provider. What's the safe write sequence, and how are partial writes detected?
- **AQ-5** Item ids: `package/activity` plus a profile marker for work/private-space apps. What's the format?
- **AQ-6** Conflict model: hash-before-write + `Use file`/`Keep mine` (spec) vs pure last-writer-wins with the version counter (spike). Is `revision` enough when a sync tool rewrites the file?
- **AQ-7** Where does the last good layout persist across process death (app-private copy of the last valid file)?
