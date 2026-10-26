# Design: layout overrides for one device's changing window (`windowOverrides`, Q-A11)

Status: **proposal, re-scoped** on 2026-10-26 to **one device whose window changes**: rotation, fold and unfold, and
multi-window resizing. This follows Christopher's steering (§13). The earlier versions of this doc, at 9a07171 and
before, designed for one config shared by several devices. They went through Pragma's UX review and two re-reviews, and
the UX review was cleared at 5348e14. This version cuts that cross-device machinery (§2). Pragma re-reviewed it at
c469d27, and its two blockers and one should-fix are applied here, so **the UX review is cleared** (§16). Author:
Archimedes.

Docs only: nothing changes in `schema/` or app code until this is accepted and dev8 has shipped the ux.md §1.5
defaults. It builds on [#185](https://github.com/Nasawa/config-launcher/pull/185) (ux.md §1.5), which merged into `main`
on 2026-10-26.

**UI naming:** the base layout is called **main** in every string people see. An override layout shows its `label`,
or its `id` when it has no label.

Christopher decided Q-L1–Q-L6 on 2026-10-26: the ux.md §1.5 defaults stand, and the config should be able to change
them. This doc turns Pragma's candidate (ux.md Q-A11) into a schema and its rules, cut down to what one device needs as
its window changes.

## 0. Principle: one config, one device, several window shapes

In Christopher's words: **"The goal is ultimately to let an AI or system manage a launcher. If we're adding complexity
without much foreseeable value, cut that thread. Functional, not one-size-fits-all."**
- **A config describes one device's home.** `windowOverrides` adapts it to the shapes that device's window takes: turned
  on its side, folded or unfolded, or resized in split screen or a freeform window. "Pushing a different config every
  rotation is infeasible", so these shapes live in one file.
- **Several devices sharing one config is allowed only in the sense that nothing stops it.** There's no machinery for
  it and no guarantees about it. "Configs are cheap." For the same home on a tablet and a phone, the user's AI writes
  two configs, or the user writes a translation layer to keep them in sync. The schema is strict and well-defined, so
  that isn't hard.
- **Switching configs happens outside the launcher**, for example a script that swaps the file. It's an ordinary
  reload (ARCHITECTURE §4). `windowOverrides` never chooses which config is active.
- **`when` has only window matchers** (§4.3): no device class, weather, time of day or location.
- **What's on home is per config. Where it sits is per layout.** Adding, removing, dock membership, folder membership
  and pages apply to every layout. Only position and size differ between layouts (§6).

## 1. Decisions in brief

1. **Shape.** An optional root array **`windowOverrides[]`**. Each entry has an `id`, an optional `label`, a `when`
   matcher (`widthClass`, `orientation`, `posture`) and per-window fields (`grid` with `positions`, `cellCap`,
   `dock.edge`, `fold.halfOpened`). Entries apply in file order, and a later matching entry wins field by field.
2. **Two global fields, with no matcher:** root `orientationLock` and root `scale` (§5.4, §7).
3. **Versioning.** This takes the **Q-A10 route**: optional root keys on **`schemaVersion` 1**. They're understood from
   **FEATURE_LEVEL 5**, and an older app rejects the file (B2, last good layout kept, §8). `net.fetch` moves to level 6.
4. **Positions.** An entry with `grid` is its own *layout* with a `positions` map. An item with no entry there inherits
   its **main spot** when that fits and is free, and is otherwise **not shown on that layout**. It's then counted in a
   load-time warning, announced by a snackbar when you rotate into the layout, and listed in `Hidden here` (§6.4).
   - **Main stays strictly validated, as today.**
   - An item added on an override layout that has no room on main **has no main spot** (§6.1). Christopher's Q1 still
     holds: adding is never refused, and saved positions are never rewritten.
   - An edit writes only to the active layout. A main edit also changes override layouts, but only for the items they
     inherit (§6.2).
5. **A window change never writes.** Matching reads only the window and the file.
6. **Folds are a best guess, unverified on real hardware.** The conservative ux.md §1.5 defaults stand, and the half
   that holds home can be overridden (§9).

## 2. Cut and why

| Cut | Why |
|---|---|
| `deviceClass` matcher | It existed to tell a phone's config from a tablet's in a shared file. On one device it never changes, except between a fold's cover and inner screens, and `widthClass` + `orientation` already tell those apart |
| `orientationLock` keyed on `deviceClass` | The lock becomes one global root field. On one device there's nothing to key it on, and the "lock changes its own match" loop goes away with the matcher |
| `heightClass` matcher | On one device it mostly mirrors `widthClass` after a rotate. No foreseeable use that `widthClass` + `orientation` + `posture` miss |
| `minWidthDp`/`maxWidthDp`/`minHeightDp`/`maxHeightDp` | They were for picking one device among several ("only my 7-inch tablet") |
| Cover-screen detection (`posture: "folded"`, slice O7) | There's no public API and only a heuristic. The cover screen is a compact window, and `widthClass` already matches it |
| `posture: flat` and the generic `halfOpened` value | A flat inner screen is just a wide window (`widthClass`). `book` and `tabletop` cover the half-open cases |
| `fold.flat` (`asTablet`/`asPhone`) | It's redundant: an entry matching the inner screen can set `grid`, `cellCap.enabled: false` and `dock.edge` directly |
| `fold.halfOpened` values `top`/`bottom` and `warn_override_fold_side` | `start`/`end` now mean the first and second half in either hinge direction, so no value can clash with the hinge |
| The 16-entry cap and its message | One device's distinct windows are few (3 width classes × 2 orientations × 3 postures), and matching is a linear scan. The cap only guarded against shared mega-files. The AGENTS.md line about it goes too |
| Per-entry `requires`, `warn_override_level` | It let devices on older app versions share a file with newer ones. One device has one app version, and the agent can read the level from `status.json` |
| The unknown-enum-value degrade (`warn_override_when_value`, `warn_override_value`) | Same reason: version skew between devices. An unknown value is a plain error now |
| `scale` per entry | Icon size and label visibility are a person's preference, not a window's. The icon ladder already adapts the size to each window. It's now one root field |
| Pins (copy-on-write into override layouts on a main edit) and the `{"skipped": true}` marker | They stopped one device's edit from moving items on another device. On one device, "landscape follows portrait except where you've placed things in landscape" is the simpler rule, and it's visible as soon as you rotate. This reverses Pragma's N1 (§16) |
| `{"hidden": true}`, `Hide on this layout`, `hidden_here_reason_hidden` | Hiding an item on just one window shape has little foreseeable value. Move it, or remove it |
| `warn_override_unreachable` as a phone warning | It's kept as a `validate.py` info line only. The agent writing the file is who needs it |
| `edit_layout_a11y_named`'s "screens that use it" wording | It was cross-device. It now reads `Moving or resizing here changes only this layout.`, because Add, Remove, the dock and folders apply to every layout |

**Kept, and why:**
- **Strict main validation.** It keeps placement independent of file order (§6.1).
- **A resize never writes.**
- **O4 read-only before O5.**
- **The Android 16 lock caveat** (§7.3).
- **Best-guess fold defaults** (§9).
- **The optional main spot** (the "no main spot" relaxation). It's still needed on one device. Say landscape is
  6 × 4 and portrait (main) is 4 × 5. An app added at column 5 in landscape has no matching cell in portrait. The only
  other ways to handle that are to refuse the add (Christopher's Q1 says never) or to invent a second spot on main
  (Pragma's S1, which Q1 rejected). Leaving `x`/`y` out costs one schema rule, one warning and one `Hidden here`
  reason. On rotating to portrait the snackbar says `1 new item isn't shown on this screen.`, and `Show here` places
  it with one tap.

## 3. Example

```json
"orientationLock": "system",
"scale": { "icon": 1.15, "labels": "always" },
"windowOverrides": [
  { "id": "landscape", "label": "Landscape", "when": { "orientation": "landscape" },
    "grid": { "columns": 6, "rows": 4 },
    "positions": { "a-maps": { "x": 5, "y": 0 } },
    "dock": { "edge": "start" } },
  { "id": "unfolded", "label": "Unfolded", "when": { "widthClass": "expanded" },
    "grid": { "columns": 6, "rows": 6 }, "cellCap": { "maxWidthDp": 160 } },
  { "id": "tabletop", "when": { "posture": "tabletop" }, "fold": { "halfOpened": "span" } }
]
```

## 4. Schema shape

### 4.1 Root keys (all optional, FEATURE_LEVEL 5)
| Key | Type | Notes |
|---|---|---|
| `windowOverrides` | array of entries | If it's absent or empty, every window gets the ux.md §1.5 defaults |
| `orientationLock` | `portrait` \| `landscape` \| `system` | Default `system` (§7) |
| `scale` | `{icon?, labels?}` | `icon` is 0.5–2.0 × the 56 dp size (default 1). `labels` is `auto` \| `always` \| `never` (§5.4) |

### 4.2 An entry (strict keys; `x-` keys are allowed)
| Key | Type | Notes |
|---|---|---|
| `id` | id, **required** | Same pattern as other IDs, unique file-wide. Warnings, queued writes and the snackbar's "already announced" set refer to the entry by `id`, never by index |
| `label` | string, 1–40 characters, optional | The name people see (edit bar, TalkBack, `Hidden here`, Settings). Falls back to `id` |
| `when` | object, **required** | The matcher (§4.3). `{}` matches every window |
| `grid` | `{columns, rows}`, both required, 2–12 | Atomic: both values come from the same entry. Defines a layout (§6) |
| `positions` | object, id → `{page?, x, y[, w, h]}`, at most 2000 keys | Only allowed in an entry that also sets `grid` (§6.1) |
| `cellCap` | `{enabled?, maxWidthDp?, maxAspect?}` | `enabled` (bool, default true on Medium/Expanded and false on Compact, as in ux.md §1.5) turns both the cap and centring on or off. `maxWidthDp` is 64–480 (default 144). `maxAspect` is 1.0–3.0 (default 1.25) |
| `dock` | `{edge}` | `auto` (the ux.md §1.5 rule) \| `bottom` \| `top` \| `start` \| `end` (§5.3) |
| `fold` | `{halfOpened}` | `auto` \| `start` \| `end` \| `span` (§9) |

### 4.3 `when`: the matcher
**Every key that's present must match.** Matching uses the current window (`currentWindowMetrics`, as in ux.md §1.5)
and never any override's output.

| Key | Values | Computed from |
|---|---|---|
| `widthClass` | `compact` \| `medium` \| `expanded` | Window width: < 600, 600–839, ≥ 840 dp |
| `orientation` | `portrait` \| `landscape` | `landscape` when the window's width > height, otherwise `portrait` (a square counts as portrait). It's the window's shape, so split screen follows the window |
| `posture` | `book` \| `tabletop` | A half-open `FoldingFeature` on this window, with a vertical (`book`) or horizontal (`tabletop`) hinge (§9). Any other window matches neither |

**Nothing else.** An unknown `when` key or value is an error (strict keys).

### 4.4 Precedence
1. Take every entry whose `when` matches.
2. Walk them in **file order**. For each **leaf** field (`cellCap.maxWidthDp`, `dock.edge`, …), the last entry that sets
   it wins. `grid` is a single leaf: columns and rows always come from the same entry, with that entry's `positions`.
3. Anything no matching entry sets keeps its ux.md §1.5 default.
4. The **active layout** is the last matching entry that sets `grid`, or **main** (the root `grid` and the items' own
   `x`/`y`) if none does.

File order, not "most specific wins", because CSS-literate agents expect it and it takes one sentence to state.

## 5. What each field does

### 5.1 `grid`
It replaces columns × rows on the windows it matches. The ux.md §1.5 cell maths runs on the active grid. Positions are
covered in §6.

### 5.2 `cellCap`
- `enabled: false`: no cap and no centring. Cells fill the page area, as on Compact today.
- `enabled: true` on Compact: the cap and centring apply there too.
- `maxWidthDp` and `maxAspect` replace 144 and 1.25 in the ux.md §1.5 formulas. The `pitchH ≤ 1.5 × pitchW` bound stays
  fixed.

### 5.3 `dock.edge`
- `auto` is the ux.md §1.5 rule. `bottom` is a bar at the content width, as today.
- `top` is a bar at the content width. Order from top to bottom: top row (`Apps`, `⋮`) → banner → notice row →
  **dock** → page label or title bar → grid → page dots. **While dragging with a top dock, the `Remove` and `App info`
  targets move to the bottom of the page area.** TalkBack and D-pad order follow the visual order.
- `start` and `end` are the ux.md §1.5 rail, with the top row at its head, mirrored in RTL.
- In every case: inside `safeDrawing`, items in `dock[]` order, and the ux.md §1.5 slot shrink and scroll rules. A drag
  drop index follows the bar's or rail's axis, and the written `dock[]` index means the same thing on every edge.
- When `fold.halfOpened` isn't `span`, the dock goes in the half that holds home.

### 5.4 `scale` (root, global)
- `icon` multiplies every step of the icon ladder (56 → 48 → 48, all × `icon`) on home tiles and folder tiles.
- **"Fits" checks both axes** (Pragma B4): a step fits when the icon ≤ the cell's content width (`pitchW − 12 dp`)
  **and** icon + one label line ≤ the content height. The drawn icon is
  `min(step size, content width, content height − label line when shown)`.
- **Dock and rail:** icon = `min(56 × icon, slot − 16 dp)`. Slots never grow.
- **Touch targets never drop below 48 dp**, whatever the scale.
- `labels: never` hides home labels (TalkBack still reads them). `labels: always` first steps the icon down to
  `min(current step, max(32 dp, 40 × icon))`. If even that doesn't fit, labels hide anyway, because **a label is whole
  or hidden, never cut**. The dock has no labels.
- `validate.py` prints an info line when `icon` ≥ 1.5 and any layout has ≥ 4 columns: `Labels will likely be hidden
  in narrow windows at this icon size.`
- L-check for Grace: `icon: 2` on s360 at font scale 1.3.

## 6. Positions: which coordinates are drawn, and which an edit writes

**The rule:** an edit on an override layout changes only that layout. An edit on main changes main, and so changes
every override layout **for the items it inherits from main** and for nothing else. "Landscape follows portrait except
where you've placed things in landscape."

### 6.1 How a layout places things
- **Main** (the root `grid`) places each item at its **main spot**: its `page`/`x`/`y` (plus `w`/`h` for elements), as
  today. **Main is validated strictly, as today:** every main spot is inside the main grid, and none overlap (§10.1).
- **The main spot is optional in a file with `windowOverrides`.** A page item or grid-placed element may leave out
  `x` and `y` together. An element keeps `page`, `w` and `h` as its default page and size. This is how an item added on
  an override layout with no room on main exists (§6.2). Main doesn't show it: it's counted in `warn_main_hidden` and
  listed in main's `Hidden here`. Without `windowOverrides`, `x` and `y` stay required.
- **On an override layout** (an entry with `grid`), each grid-placed thing is placed by the first of these that
  applies:
  1. **Its `positions` entry**, `{"page"?, "x", "y"}`, plus `"w"`, `"h"` for elements. `page` defaults to the item's
     own page. This is validated strictly against **this entry's grid**: outside the grid, or overlapping another
     `positions` entry on the same page, is an error.
  2. **Its inherited main spot**, if it has one, the spot fits this grid, and it doesn't overlap a step-1 entry.
  3. **Otherwise it isn't shown on this layout.** It isn't drawn, moved or written. It's counted in
     `warn_override_hidden` and listed in `Hidden here` (§6.4).
- **Placement doesn't depend on file order.** Explicit entries can't overlap each other, and main spots can't overlap
  each other. So the only possible collision is an inherited spot under an explicit entry, and **the explicit entry
  wins**.
- Skipped or disabled elements still reserve their effective cells, as today.
- `validate.py` prints info lines per layout for the inherited items, the items that aren't shown, and the items with
  no main spot.
- **Shared by all layouts:** which items exist, the pages and their order, folder contents, `dock[]`, and every
  element's `content`/`style`.
- The phone keeps `positions` verbatim (canonical reformat, AQ-2). An agent may write it by hand.

### 6.2 What a user edit writes
Every edit is computed against the **active layout**: its grid and its occupancy (the places from §6.1, including
inherited ones, plus reserved cells). It's one write, replayed by ID at write time. This applies to app items,
shortcuts, folders and elements.

| Edit | While main is active | While override layout `L` is active |
|---|---|---|
| Move / drag / `Move to…` / `Resize…` | Writes the main spot. Override layouts that inherit it follow, by design | Writes only `L.positions[id]` |
| `Add to home` / widget picker / out of a folder | **Never refused** (Christopher, Q1). The chosen cell, or the first free cell as in ux.md §4.2 (a new page if every page is full; `home_full` at 20 pages). Override layouts inherit it where it fits and is free | The chosen cell, or `L`'s first free cell by the same rule. **If those coordinates fit main and are free there**, they become the main spot, and `L` inherits it. **Otherwise** the add writes `L.positions[id]` and **no main spot**. Main then doesn't show the item: the new-items snackbar announces it, and it's listed in `Hidden here` |
| `Remove from home` | Deletes the item and **every** `positions[id]` entry, in one write | Same |
| Into or out of the dock | Membership is shared, so the change applies to every layout, and the `positions[id]` entries are deleted with it | Same |
| **Folder creation** (drop an item on another) | On every layout, the folder takes the **target** item's place, and the dragged item leaves its place | Same |
| Into an existing folder | As Remove, for the moved item's places | Same |
| Undo of a remove | Puts the object back at its old index, **with its main spot and its `positions` entries**. **On the active layout:** its old cells if they're free, otherwise the free-spot scan, written as a move. `undo_element_moved` shows only when it lands off its old cells there. **If the active layout has no room** (Pragma S-c), in a file with `windowOverrides`: it's restored anyway, not shown on this layout (with no main spot on main, or no `L` entry on `L`), and the snackbar is `Put "%1$s" back. It's in Hidden here until there's room.` (`undo_element_hidden`). `undo_element_no_room` stays for files without `windowOverrides`. **On other layouts:** a restored `positions` entry or main spot whose cells are now taken is dropped. Only the restored item's places change | Same, with `L` as the active layout |

**Pages are shared by all layouts.** Going past the last page creates a new empty page, as today (ux.md §4.2). "Empty"
means empty in the file: no main item on it and no `positions[…].page` pointing at it.

**Why this model** (rejected options in brackets):
- **[Every edit writes the main spot.]** Dragging an item to column 5 in landscape would make it vanish in portrait.
- **[Pins on main edits.]** That was the earlier design. It kept every layout frozen against main edits, at the cost of
  a `skipped` marker and writes into every override layout. On one device, letting inherited items follow main is
  simpler and shows up the moment you rotate. If that turns out to surprise people, pins can come back without
  changing any existing file.

### 6.3 Which layout am I editing? (Pragma B1)
- **Edit bar:** on main, the title stays `Editing layout`. On an override layout it's `Editing layout · %1$s`
  (`edit_layout_title_named`), using `label`, else `id`.
- **Rail mode:** the same text shows as a **chip in the notice row**, in edit mode only, and only while an override
  layout is active. ux.md §1.5's rail bullet needs amending to say so (§16).
- **TalkBack on entering edit mode on an override layout:** `Editing the %1$s layout. Moving or resizing here changes
  only this layout. Double-tap Done when finished.` (`edit_layout_a11y_named`). Add, Remove, the dock and folders apply
  to every layout (§0), and an Add on an override layout can create the main spot (§6.2), so the line claims only moves
  and resizes.
- **Entering edit mode on main** in a file with at least one override layout that has `grid`: a 6 s announced
  snackbar, `edit_main_follow` (plurals, keyed on how many override layouts have `grid`):
  - one: `Moving or resizing things here also changes them on the %1$s layout, unless you've placed them there.`
  - several: `Moving or resizing things here also changes them on your other layouts, unless you've placed them there.`

  It shows **once per app session**, held in device-local memory and never written. It's the cue for §6.2's "main edits
  reach the items override layouts inherit", so a rotate after a portrait move doesn't surprise anyone.
- **A rotate, fold or resize that changes the active layout while in edit mode:** a 4 s announced snackbar, `Now editing
  the %1$s layout.` (`edit_layout_switched`). On main, the name is `main` (`layout_name_main`).

### 6.4 Hidden here: finding and bringing back items (Pragma B3, N2)
- **Count:** the items the active layout doesn't show. On an override layout these are the items that don't fit, whose
  spot is taken, or that have neither a main spot nor an entry here. On main, they're the items with no main spot.
- **Entry points:**
  - In edit mode, when the count is > 0, the edit bar gets a `Hidden here (%d)` button (`hidden_here_button`,
    plurals). In rail mode it's in `⋮`.
  - **Outside edit mode**, the home long-press menu gets `Hidden here (%d)` when the count is > 0. This is available
    from O4.
- **New-items snackbar** (Pragma N2; the rule is from Pragma's re-reviews of 5348e14 and c469d27):
  a 6 s announced snackbar, `%1$d new items aren't shown on this screen.` / `1 new item isn't shown on this screen.`
  (`hidden_here_load`, plurals). Its action is `Show` (`hidden_here_load_action`), which opens `Hidden here`.
  - It fires on **switching to a layout** (rotate, fold, unfold, resize), on the first layout shown after the app
    starts, and on **a load or reload that adds items to the active layout's hidden set**. An agent's rewrite is the
    main way that happens while someone is looking at home. The same once-per-item set applies, so a reload that hides
    nothing new stays quiet.
  - It counts each **newly hidden item once**. The device keeps, per layout `id` (or main), the set of item ids it has
    already announced. The snackbar counts only hidden items not in that set, and adds them when it shows. An item
    leaves the set when that layout shows it again. So the same set never fires twice, and an item that's hidden again
    later is announced again. The first time a device shows a layout, every hidden item there counts as new.
  - The set is device-local UI state (app-private), **never written to the file**. The window still never writes.
  - **While a drag is in progress it's held**, and it shows when the drag ends or is cancelled. In edit mode, after a
    layout switch, it follows the `Now editing the %1$s layout.` snackbar rather than replacing it.
- **The sheet** is titled `Not shown on this layout` (`hidden_here_title`). Each row is the item's label with one reason
  (Pragma S-a):
  - `Doesn't fit this grid` (`hidden_here_reason_no_fit`): its spot is outside this grid;
  - `Its spot is used by "%1$s"` (`hidden_here_reason_taken`): its inherited main spot is under an explicit entry;
  - `Not placed on this layout` (`hidden_here_reason_unplaced`): no main spot and no `positions` entry here. That's
    main, or any override layout.
  - Each row has the action `Show here` (`hidden_here_show`). TalkBack reads a row as
    `<Label>, <reason>, Show here, button`.
- **`Show here`** places the item at the first free cell, scanning row-major from the **page on screen**, then the
  pages after it, then the pages before it. If no page has room, it goes on a new page at the end, as `Add to home`
  does (ux.md §4.2). Only at 20 full pages is the row disabled with `No room`; activating it announces `home_full`. If
  the item lands on a page other than the one on screen, the sheet closes and home scrolls to that page. On an override
  layout it writes `L.positions[id]`. On main it writes the main spot.
- **In O4:** `Show here` works on main. On an override layout the sheet shows without it (§11.1).

### 6.5 Rotating, folding or resizing mid-edit
- **A drag in progress is cancelled** (ux.md §1.5), with no write: the item springs back.
- **Edit mode stays on** and now edits the layout that's active after the change, with the §6.3 switch snackbar.
- **An open `Move to…`, `Resize…` or `Hidden here` sheet closes without writing if the active layout changed.** The
  announced snackbar is `Screen size changed, so nothing was moved.` (`edit_sheet_closed_layout_changed`). If only
  chrome changed, the sheet stays open.
- **Queued (debounced) writes** carry their target layout's entry `id` (or main), and write there at replay. If that
  entry is gone or the target cell is taken, nothing is written and `move_element_taken` shows.
- **Undo after a rotate:** the Undo snackbar survives the change. Its scan runs on the layout that's active **when Undo
  is tapped**.
- **Reserved cells:** a hatch marks only cells held by disabled or unknown-type elements: 45° lines in white at 16 %.
  TalkBack reads `Reserved, column %1$d, row %2$d` (`cell_reserved_a11y`). A drop there is refused with the shake, or a
  red outline under reduced motion. Items that aren't shown have no cells on that layout.

## 7. Orientation lock (root `orientationLock`)

### 7.1 Rule
- `orientationLock` is one global root field, with no matcher. `system` (the default) is
  `SCREEN_ORIENTATION_UNSPECIFIED`, today's behaviour. `portrait` is `USER_PORTRAIT` and `landscape` is
  `USER_LANDSCAPE`: both directions of it, honouring the user's auto-rotate for the 180° flip.
- `setRequestedOrientation` is called in `onCreate` **before the first frame**, from the config already in memory or
  `last-good.json`. A reload that changes it calls it again, and the Activity recreates with the ux.md §1.5 state kept.
- **Scope** (Pragma S9): the **home activity** only: home, the drawer, folders, and the item and Pages sheets. First
  run, Settings, the detail view and system pickers follow the system.

### 7.2 Entries the lock makes unreachable
- Matching always reads the real window, because split screen and freeform ignore the lock.
- `validate.py` prints an info line for an entry whose `orientation` contradicts the lock: `windowOverrides[%1$d]
  ("%2$s") only applies in split screen or a freeform window, because orientationLock is %3$s.` There's no phone
  warning.

### 7.3 ⚠ Technically limited: Android 16+ large screens
- For apps **targeting API 36+**, Android **ignores `setRequestedOrientation` on displays with smallest width
  ≥ 600 dp**: tablets, a fold's inner screen, and desktop windowing. The opt-out property lasts only until target 37.
- The app targets 35 today, so the lock works everywhere **for now**. Play will require target 36.
- **Recommended:** document the lock as **reliable on phone-sized screens only**, including a fold's cover screen. Don't
  use the opt-out property. On load, if the lock isn't `system` and this display would ignore it (API ≥ 36, target
  ≥ 36, sw ≥ 600), write `warn_orientation_lock_ignored`.

## 8. Versioning and how old phones fail

**The Q-A10 route** (optional root keys, `schemaVersion` 1), not `schemaVersion` 2.
- **FEATURE_LEVEL 5** means the app understands `windowOverrides`, `orientationLock` and `scale`. `Elements.FEATURE_LEVEL`
  and `validate.py` go to 5 in O1. **`net.fetch` moves from its proposed level 5 to 6**, and the docs that say "net.fetch,
  level 5" are updated in O1.
- **Apps at FEATURE_LEVEL 4 or below (including dev8):** the root keys are strict, so the file is **B2: rejected,
  with the last good layout kept**, via the existing `unknown key "…"` error. Nothing is written. Once the app updates,
  the file loads. This is acceptable pre-release (Christopher, Q3). Older `validate.py` gives the same error.
- **Level-5 `validate.py`** prints an info line when any of these keys is present: `windowOverrides, orientationLock and
  scale need Config Launcher feature level 5 or later; older versions reject this file.`
- **Later additions** (a new matcher or field) take the same route: the next feature level understands them, and
  older apps reject the file. With one device and one app version that's enough, so there's no per-entry `requires`
  (§2).
- **Why not `schemaVersion` 2:** the outcome is the same, plus a migration path and a second schema to maintain. And
  this change is purely additive.
- **Why not an `elements[]` type:** layouts change where every item sits, and they aren't something drawn.

## 9. Folds (⚠ every behaviour unverified on real hardware)

Christopher has no foldable, so these are Archimedes' best guesses. **The defaults are the conservative ux.md §1.5
ones, they can be overridden, and they're tested only on emulators that report a real `FoldingFeature`**: the
*7.6" Fold-in with outer display*, *Pixel Fold* and *Pixel 9 Pro Fold* AVDs, with posture driven from *Virtual
sensors → Posture* and the hinge angle slider. They aren't tested with `wm size`, which reports no hinge. No fold
question goes to Christopher.

- **`posture`** needs `androidx.window:window` (`WindowInfoTracker.windowLayoutInfo`), collected in the Activity and
  passed into the pure geometry as data. `book` is `HALF_OPENED` with a vertical hinge, and `tabletop` is with a
  horizontal one. A `FoldingFeature` is only reported when the window overlaps the hinge.
- **Unfolded flat:** the hinge is ignored (ux.md §1.5). The inner screen is a medium or expanded window, so
  `widthClass` matches it.
- **Cover screen:** a compact window, matched by `widthClass: "compact"`. It needs no detection.
- **`fold.halfOpened`** applies only while `isSeparating` is true:
  - **`auto`** (the default): book uses the start half, and tabletop puts the grid on top with the dock below (ux.md
    §1.5).
  - `start` is the first half: the reading-start side in book, the top in tabletop. `end` is the other half.
  - `span` ignores the hinge and lays out over the whole window. On a hinge that **occludes** (`occlusionType ==
    FULL`), content under it is hidden. That's documented, not prevented.
- The chosen half is laid out "as if it were the window", so the chrome rules come from the half. `when` still matches
  the **whole window**, so choosing a half can't change which entries match.
- **No write on fold or unfold, ever.** The page is kept by `id`.

## 10. Validation and warnings

### 10.1 Errors (`validate.py`/CI always; B2 on the phone)
The existing generic messages are reused for type, range, missing keys, unknown keys and duplicate IDs. New messages:

| Path | Message |
|---|---|
| `windowOverrides[i].label` | `%1$s must be 1 to 40 characters.` |
| `….when.widthClass` | `%1$s must be compact, medium or expanded.` |
| `….when.orientation` / `orientationLock` | `%1$s must be portrait or landscape.` / `%1$s must be portrait, landscape or system.` |
| `….when.posture` | `%1$s must be book or tabletop.` |
| `pages[p].items[i]` / `elements[e]` (main spot) | Unchanged and strict: inside the main grid, with no overlap (the existing messages) |
| `pages[p].items[i]` / `elements[e]` with only one of `x`, `y` | `%1$s needs both x and y, or neither (no spot on the main layout).` |
| `pages[p].items[i]` / `elements[e]` with neither, in a file without `windowOverrides` | The existing `missing required "x"` |
| `….positions` without `grid` | `%1$s needs a grid in the same entry; positions belong to that grid.` |
| `….positions.<id>` | `x %2$d + w %3$d is outside this entry's grid (%4$d columns)` / `y … (%4$d rows)` |
| `….positions.<id>` overlap | `overlaps %2$s on page "%3$s" in this entry's grid` |
| `….positions.<id>.page` / `w`, `h` on an item | `page "%2$s" doesn't exist` / `w and h are only for elements` |
| `….dock.edge` | `%1$s must be auto, bottom, top, start or end.` |
| `….cellCap.maxWidthDp` / `.maxAspect` | `%1$s must be a whole number from 64 to 480.` / `%1$s must be a number from 1 to 3.` |
| `scale.icon` / `scale.labels` | `%1$s must be a number from 0.5 to 2.` / `%1$s must be auto, always or never.` |
| `….fold.halfOpened` | `%1$s must be auto, start, end or span.` |

### 10.2 Phone warnings (`status.json` `warnings`; no banner)
These are in code, as in ux.md §15.1, and every count has a singular form. They're computed at load or reload from the
file (Q-A12), never from the window: each override entry is checked **as if** it were active. The one exception is
`warn_orientation_lock_ignored`, which also reads the display and API level.

| Code | Text |
|---|---|
| `warn_override_hidden` (one per entry) | `windowOverrides[%1$d] ("%2$s"): %3$d items aren't shown on windows that use its %4$d × %5$d grid (%6$s), because they don't fit it, their spot is used by an item placed there, or they have no spot on main or here. Give them positions there, or change the grid.` `%6$s` = the first 3 ids, then `and %d more` |
| `warn_main_hidden` | `Main layout: %1$d items have no spot on the main layout (%2$s), so windows that use it don't show them. Give them x and y, or use Show here on the phone.` These are items added on another layout where main had no room |
| `warn_override_position_id` | `windowOverrides[%1$d] ("%2$s").positions: "%3$s" doesn't match any item, so it was ignored.` |
| `warn_orientation_lock_ignored` | `orientationLock "%1$s": this screen ignores app orientation locks (Android 16 and later), so home rotates freely here.` |

The **title-bar fit check** (Q-A12) runs on a synthetic portrait window at `smallestScreenWidthDp` and applies the
entries that would match that window, so a `cellCap` override can change the content width it checks.

## 11. Staging after dev8 (PR-sized slices for Sprite)

Strict keys keep any field from a later slice rejected until that slice lands.

| Slice | Contents | Tests |
|---|---|---|
| **O1** schema + matcher | `windowOverrides` (`id`, `label`, `when`), root `scale`, the validators, FEATURE_LEVEL → 5 and `net.fetch` → 6 in the docs; pure `resolve(entries, WindowContext) → Resolved` (leaf merge); `cellCap` and `scale` in `HomeGeometry` | Fixtures valid and invalid; resolver unit tests; L1 (no write on rotate); the `icon: 2` L-check |
| **O2** dock edge | `dock.edge` (top bar, start/end rail), the drag index on both axes, insets, RTL | L5/L6 rerun with each edge |
| **O3** orientation lock | Root `orientationLock`, the lock before the first frame, the `validate.py` unreachable line, `warn_orientation_lock_ignored` | A landscape-only phone emulator; a tablet AVD at API 36 with a test build targeting 36 |
| **O4** grid layouts, read-only | `grid` + `positions` parse and validate; the optional main spot; §6.1 placement; `warn_override_hidden`, `_position_id`, `warn_main_hidden`; §11.1 read-only behaviour; `Hidden here` from the long-press menu and the edit bar (`Show here` on main only); the new-items snackbar; `edit_main_follow` (main stays editable) | Rotate between layouts with the file hash unchanged; O9; O12 on main; §11.1 copy and TalkBack |
| **O5** edits on any layout | §6.2 writes on override layouts, including adds with no main spot; Undo with no room; folder placement; queued writes keyed by entry `id`; §6.3 naming; `Show here` everywhere; §6.5 mid-edit rules and reserved-cell hatching; the Settings `Grid` hint (§14) | O9b, O10–O13 |
| **O6** folds | `androidx.window`, the `posture` matcher, `fold.halfOpened`; with `span`, focus runs straight across the hinge | Foldable AVDs with posture simulation only |

New test-plan checks for Grace:
- **O9 (layouts stay apart):** main 4 × 5 (portrait) and `landscape` 6 × 4. Drag in landscape, then rotate: portrait is
  unchanged. Drag an item in portrait that landscape inherits: landscape follows. An item with a landscape entry
  doesn't move.
- **O9b (add never refused):** add at column 5 in landscape, then rotate to portrait. Portrait doesn't show it, says `1
  new item isn't shown on this screen.`, lists it in `Hidden here`, and writes `warn_main_hidden`. No other position
  changes.
- **O10:** a rotate mid-`Move to…` closes the sheet with no write, and a rotate mid-drag cancels the drag.
- **O11:** Undo after a rotate scans the layout that's active when Undo is tapped.
- **O12 (Hidden here):**
  - Rotating into a layout with a new hidden item shows the snackbar once, and rotating back and forth doesn't repeat
    it. It's held during a drag.
  - A reload that adds an item with no main spot while portrait is showing fires the snackbar once; repeating the same
    reload doesn't.
  - An item with no main spot and no entry on a second override layout is listed there as `Not placed on this layout`
    and counted in `warn_override_hidden`.
  - `Show here` scans the page on screen, then the later pages, then the earlier pages, then adds a new page. At 20
    full pages it's disabled.
- **O13 (layout name):** the edit bar, the rail chip and TalkBack name the layout, and a rotate across layouts in edit
  mode announces `Now editing the … layout.` Entering edit mode on main with a landscape layout shows
  `edit_main_follow` once per session.

### 11.1 What O4 (read-only) shows (Pragma B5)
While an override layout with `grid` is active and O5 hasn't shipped:
- **Long-press menu:** `Edit layout` is shown **disabled**, with the reason `Not editable here yet`
  (`layout_readonly_reason`).
- **Long-press-drag** doesn't start a drag. It shows the snackbar `The %1$s layout can't be edited here yet. Change it in
  launcher.json.` (`layout_readonly`).
- **Disabled, with the reason `Not editable here yet`:** the drawer's `Add to home`, `Move to…`, `Move to dock`,
  `Add to folder…` and `Remove from home`. TalkBack lists them as `<action>, Not editable here yet` and announces
  `layout_readonly` on activation.
- **Still available:** `Hide` (`drawer.hidden`) and `App info`.
- **Rotating into an override layout while in edit mode** leaves edit mode, with the same snackbar.
- **`Hidden here`** is reachable from the long-press menu, without `Show here`.
- **Main stays editable.** Override layouts follow main for the items they inherit (§6), as they will after O5.

## 12. Hard parts (flagged, not dropped)

| Hard part | Approach |
|---|---|
| Edits with several layouts on one device | A per-layout `positions` map, with main inherited where it fits (§6). Override editing is the largest slice (O5), so O4 ships override layouts read-only first |
| A main edit changes inherited items on other layouts | By design (§6.2), and visible on rotate. Pins can return later with no change to existing files |
| Orientation lock on screens ≥ sw600 at target 36+ | The OS ignores it. It's documented as reliable on phone-sized screens, with `warn_orientation_lock_ignored` (§7.3) |
| Fold behaviour | Unverified on hardware. Conservative defaults, overridable, and emulator-only tests (§9) |
| `span` over an occluding hinge | Allowed and documented (§9) |
| The lock's first frame | Applied in `onCreate` from the config in memory or last-good (§7.1) |
| Class flapping near 600/840 dp while resizing | It can switch layouts mid-resize. Nothing is written, and a drag is cancelled. Accepted |

## 13. Christopher's answers: **decided**

**Re-scope, 2026-10-26**, in his words: "The goal is ultimately to let an AI or system manage a launcher. If we're
adding complexity without much foreseeable value, cut that thread. Functional, not one-size-fits-all. Configs are
cheap: if the user wants the same config on a tablet and a phone, their AI can do it, or they write a translation layer
to keep them in sync. Allowing different layouts for a single device makes sense, since turning your device is common
and pushing a different config every rotation is infeasible." (§0, §2)

The earlier answers, as they stand after the re-scope:
1. **Adding is never refused: decided, kept.** "The item goes into the active layout's spot. Every other layout uses
   the inherited base spot if it fits there. If it doesn't fit, it isn't shown on that layout." With main strictly
   validated, that means an add on an override layout becomes the main spot when it fits and is free there, and
   otherwise the item has no main spot (§6.2). Saved positions are never rewritten.
2. **Global changes: decided, reworded.** "You're editing the one config" now means one config for one device: add,
   remove, dock and folder membership apply to every layout, and only position and size are per layout.
3. **Older app versions reject these files: decided, fine pre-release** (§8).
4. **Orientation lock reliable on phones only (Android 16+): decided, accepted.** The lock is now global (§7).
5. **Read-only first: decided.** O4, then O5.
6. **Icon scale: decided,** 0.5–2×, now a global root field (§5.4).

## 14. Settings `Grid` on an override layout (Pragma S3)
- When an override layout with `grid` is active, ux.md §8's `Grid` pickers still change **main**, and a helper line goes
  under them: `This screen uses the %1$s layout (%2$d × %3$d) from launcher.json. These pickers change the main layout.`
  (`settings_grid_override_hint`).
- Out of scope: editing an override layout's grid from Settings.

## 15. Phone-UI strings (`strings.xml`)
These are the 20 strings Pragma reviewed, minus `hide_on_layout` and `hidden_here_reason_hidden` (cut, §2). Changed:
`hidden_here_reason_pinned` is renamed `hidden_here_reason_unplaced` (same text), and `edit_layout_a11y_named` is
reworded. `edit_main_follow` is new (Pragma's re-review of c469d27). That makes **19**:

| ID | Text |
|---|---|
| `edit_layout_title_named` | `Editing layout · %1$s` |
| `edit_layout_a11y_named` | `Editing the %1$s layout. Moving or resizing here changes only this layout. Double-tap Done when finished.` |
| `edit_main_follow` (plurals) | `Moving or resizing things here also changes them on the %1$s layout, unless you've placed them there.` / `Moving or resizing things here also changes them on your other layouts, unless you've placed them there.` |
| `edit_layout_switched` | `Now editing the %1$s layout.` |
| `layout_name_main` | `main` |
| `edit_sheet_closed_layout_changed` | `Screen size changed, so nothing was moved.` |
| `hidden_here_button` (plurals) | `Hidden here (%d)` |
| `hidden_here_title` | `Not shown on this layout` |
| `hidden_here_reason_no_fit` | `Doesn't fit this grid` |
| `hidden_here_reason_taken` | `Its spot is used by "%1$s"` |
| `hidden_here_reason_unplaced` | `Not placed on this layout` |
| `hidden_here_load` (plurals) | `%1$d new items aren't shown on this screen.` / `1 new item isn't shown on this screen.` |
| `hidden_here_load_action` | `Show` |
| `hidden_here_show` | `Show here` |
| `undo_element_hidden` | `Put "%1$s" back. It's in Hidden here until there's room.` |
| `layout_readonly_reason` | `Not editable here yet` |
| `layout_readonly` | `The %1$s layout can't be edited here yet. Change it in launcher.json.` |
| `cell_reserved_a11y` | `Reserved, column %1$d, row %2$d` |
| `settings_grid_override_hint` | `This screen uses the %1$s layout (%2$d × %3$d) from launcher.json. These pickers change the main layout.` |

Reused unchanged: `No room`, `home_full`, `move_element_taken`, `undo_element_moved`, `undo_element_back`, and
`undo_element_no_room` (files without `windowOverrides` only).

## 16. Review status and follow-ups
- **Pragma's UX review is cleared** for the re-scope (re-review of c469d27). Applied from it:
  - blocker 1: `edit_layout_a11y_named` claims only moves and resizes, and the main-only `edit_main_follow` snackbar
    makes inheritance visible (§6.3, O13);
  - blocker 2: the new-items snackbar fires again on a load or reload that grows the active layout's hidden set, with
    the same once-per-item set (§6.4, O12); that row is gone from §2;
  - should-fix: items with neither a main spot nor an entry are unplaced on override layouts too (§6.4 count and
    reason, `warn_override_hidden`).
  - Agreed as written: cutting pins (reverses N1), cutting `Hide on this layout`, and the `unplaced` rename.
- **Once accepted, Pragma (ux.md):**
  - §1.5: replace "the same columns × rows on every device" and the multi-device placement wording with §0 and §6 here;
  - §4.2: `Add to home` is never refused short of `home_full`, and pages are shared by all layouts;
  - §1.5's rail bullet: the edit-mode layout chip;
  - §8: the `Grid` helper line;
  - add the §15 strings.
- **Grace (test-plan):** O9, O9b, O10–O13, the `icon: 2` L-check, and the emulator-only fold note on O6. Drop O7.
- **Archimedes:**
  - an ARCHITECTURE §19 entry;
  - the schema and `validate.py` text in O1;
  - the `net.fetch` → level 6 renumbering.
