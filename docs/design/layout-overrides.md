# Design: layout overrides for windows, orientation and folds (`windowOverrides`, Q-A11)

Status: **proposal**, 2026-10-26, revised the same day after Pragma's UX review (all blocking items, should-fix items
and nits applied, except the AGENTS.md line, which is deferred to O1, and S1, which Christopher's Q1 answer replaces:
§15), then updated with Christopher's answers to §12 (all six decided). Author: Archimedes. Docs only: nothing changes in `schema/` or app code until this
is accepted and dev8 has shipped the §1.5 defaults.
Builds on [#185](https://github.com/Nasawa/config-launcher/pull/185) (ux.md §1.5), which merged into `main` on
2026-10-26. This doc was written against #185 at 7dd5842, which matches what merged in ux.md §1.5.

**UI naming:** the base layout is called **main** in every string people see. An override layout shows its `label`,
or its `id` when it has no label.

Christopher decided Q-L1–Q-L6 on 2026-10-26: the ux.md §1.5 defaults stand, and the config should be able to change
each one: "if the user wants to do weird stuff, we should provide the tools to facilitate it where possible." This
doc turns Pragma's candidate (ux.md Q-A11, #185 1feae3f) into a schema and its rules. Where it differs from that
candidate or from the §1.5 proposal text, the change is marked **(refined)** and the reason is given.

## 0. Principle: you're editing the one config every device uses

In Christopher's words: **"You're editing the one config every device uses."**
- Adding, removing, dock membership and folder membership are **global**: they change the config, so every device
  sees them. **Remove is global.**
- Only **position and size** are per layout (§5). An edit on one layout never moves anything on another.
- **Any number of devices can share one config.**
- **One device can switch between any number of configs**, but something **outside the launcher** does the switching,
  for example a script that swaps the file. Swapping configs is just an ordinary reload (ARCHITECTURE §4), with nothing
  special about it.
- **`windowOverrides` only adapts one config to different windows. It never chooses which config is active.**
- So **`when` has only window matchers** (§3.2): no weather, time of day, location, or anything like that. A matcher
  that isn't about the window is out of scope for this key.

This answers most "what happens on my other device?" questions: if it's about *what* is on home, it's the config and
it's everywhere. If it's about *where* on this screen, it's the layout.

## 1. Decisions in brief

1. **Shape.** An optional top-level **`windowOverrides[]`** array. Each entry has an `id`, an optional `label` and
   `requires`, a `when` matcher and override fields (§3). Entries apply in file order, and a later matching entry wins field by
   field. A window that no entry matches uses the §1.5 defaults.
2. **Versioning.** This takes the **Q-A10 route**: an optional root key on **`schemaVersion` 1**, with no bump. The release
   that understands it is **FEATURE_LEVEL 5**. A phone below level 5 rejects a file that has the key (B2, last good
   layout kept). After level 5, each entry carries its own `requires`, so later override fields degrade per entry
   instead of rejecting the file (§7).
3. **Positions.** **(refined)** An entry that sets `grid` defines its own *layout*. It can hold its own positions
   (`positions`, keyed by item or element `id`). An item with no entry there falls back to its base `x`/`y` when that
   fits, and is otherwise **skipped on that window** with a load-time warning, and listed in `Hidden here` (§5.4) so it
   can be brought back on the device. The same is true of main: an item added on a bigger layout that doesn't fit the
   main grid is skipped there, never moved. **Adding is never refused, and saved positions are never rewritten**
   (Christopher, Q1). A user edit writes only to the layout
   it was made in. When the edit is in the base layout, the same write first **pins** the item's old place in every
   other layout that was borrowing it (`{"skipped": true}` where it wasn't shown). As a result, **an edit on one device never makes an item move on a device that
   shows a different layout** (Pragma's constraint, §5).
4. **A window change never writes.** Matching reads only window metrics and the file, and the overrides never feed back
   into matching. The one field that changes the window, `orientationLock`, may be keyed on `deviceClass` only (§6).
5. **Folds.** Every fold behaviour here is a **best guess, unverified on real hardware.** Christopher has no foldable.
   The defaults are the conservative §1.5 ones (a flat hinge is ignored; half-folded puts home in one half). Each can
   be overridden, and each is tested only on emulators that report a real `FoldingFeature` (§8). `posture: "folded"`
   (the cover screen) has no public API to detect it, so it's deferred, with a heuristic ready (§8.3).

## 2. Example

```json
"windowOverrides": [
  { "id": "tablet-land", "label": "Tablet landscape", "when": { "deviceClass": "tablet", "orientation": "landscape" },
    "grid": { "columns": 6, "rows": 4 },
    "positions": { "a-maps": { "x": 5, "y": 0 }, "a-camera": { "hidden": true } },
    "cellCap": { "enabled": true, "maxWidthDp": 160 },
    "dock": { "edge": "start" } },
  { "id": "dock-top", "when": { "orientation": "portrait" }, "dock": { "edge": "top" } },
  { "id": "fold-span", "when": { "posture": "halfOpened" }, "fold": { "halfOpened": "span" } },
  { "id": "phone-lock", "when": { "deviceClass": "phone" }, "orientationLock": "landscape" },
  { "id": "big-icons", "when": {}, "scale": { "icon": 1.15, "labels": "always" } }
]
```

## 3. Schema shape

### 3.1 The array and its entries
- `windowOverrides`: optional array, **0–16 entries**. If it's absent or empty, every window gets the §1.5 defaults.
- Each entry is an object with strict keys (`x-` keys are allowed):

| Key | Type | Notes |
|---|---|---|
| `id` | id, **required** **(refined)** | Same pattern as other IDs, unique file-wide. Warnings, write targets and conflict replay (AQ-6) refer to the entry by `id`, never by index, because an agent may reorder the list |
| `requires` | integer ≥ 1, optional | As on elements: above the phone's FEATURE_LEVEL, the entry is **skipped with a warning** before strict key checks (§7) |
| `label` | string, 1–40 characters, optional | The name people see (edit bar, TalkBack, `Hidden here`, Settings). Falls back to `id` |
| `when` | object, **required** | The matcher (§3.2). `{}` matches every window |
| `grid` | `{columns, rows}`, both required, 2–12 | Atomic: both values come from the same entry. Defines a layout (§5) |
| `positions` | object, id → position, max 2000 keys | Only allowed in an entry that also sets `grid` (§5.1) |
| `cellCap` | `{enabled?, maxWidthDp?, maxAspect?}` | `enabled` (bool, default true on Medium/Expanded and false on Compact, as in §1.5) turns both the cap and centring on or off. `maxWidthDp` is 64–480 (default 144). `maxAspect` is 1.0–3.0 (default 1.25, the width : height bound in §1.5). **(refined: adds `maxAspect`, for the "aspect" request)** |
| `dock` | `{edge}` | `auto` (the §1.5 rule) \| `bottom` \| `top` \| `start` \| `end` (§4.3) |
| `scale` | `{icon?, labels?}` | `icon` is 0.5–2.0 × the 56 dp size (default 1). `labels` is `auto` \| `always` \| `never` (§4.4). **(refined: adds `labels`, for "label visibility")** |
| `fold` | `{halfOpened?, flat?}` | `halfOpened`: `auto` \| `start` \| `end` \| `top` \| `bottom` \| `span`. `flat`: `asTablet` \| `asPhone` (§8). **(refined: names which half, where Pragma had `oneHalf`)** |
| `orientationLock` | `portrait` \| `landscape` \| `system` | Only in an entry whose `when` uses `deviceClass` alone, or is `{}` (§6) |

### 3.2 `when`: the matcher
**Every key that's present must match.** An empty `when` matches every window. Matching uses the current window
(`currentWindowMetrics`, as in §1.5) and never any override's output:

| Key | Values | Computed from |
|---|---|---|
| `widthClass` | `compact` \| `medium` \| `expanded` | Window width: < 600, 600–839, ≥ 840 dp |
| `heightClass` **(refined)** | `compact` \| `medium` \| `expanded` | Window height: < 480, 480–899, ≥ 900 dp |
| `orientation` | `portrait` \| `landscape` | `landscape` when the window's width > height, otherwise `portrait` (a square window counts as portrait). This is the window's shape, not the device's rotation, so split screen follows the window |
| `deviceClass` | `phone` \| `tablet` | The **display's** smallest side: the smaller of the two sides of `computeMaximumWindowMetrics`. Below 600 dp it's `phone`, otherwise `tablet`. It doesn't change on rotate or resize, only when the display does: a fold's cover screen is `phone` and its inner screen `tablet` |
| `posture` | `flat` \| `halfOpened` \| `book` \| `tabletop` | `FoldingFeature` on this window (§8). `book` and `tabletop` are `halfOpened` with a vertical or horizontal hinge. A window with no `FoldingFeature` (any phone, tablet or cover screen) matches **none** of them. `folded` is reserved and not accepted at level 5 (§8.3) |
| `minWidthDp`, `maxWidthDp`, `minHeightDp`, `maxHeightDp` **(refined)** | integer 0–10000, inclusive | Window size. For setups the classes don't cover, e.g. "only my 7-inch tablet" |

**Nothing else.** The matchers are about the window only (§0). Anything outside that, such as time, weather, location,
battery or Wi-Fi, is out of scope, and an unknown `when` key is an error (strict keys).

### 3.3 Precedence
1. Take every entry whose `when` matches and whose `requires` is ≤ FEATURE_LEVEL. An entry with an unsupported value
   in `when` never matches (§7).
2. Walk them in **file order**. For each **leaf** field (`cellCap.maxWidthDp`, `dock.edge`, `scale.icon`, …), the last
   entry that sets it wins. `grid` is a single leaf: columns and rows always come from the same entry, together with
   that entry's `positions`.
3. Anything no matching entry sets keeps its §1.5 default.
4. The **active layout** is the last matching entry that sets `grid`, or **base** (the root `grid` and the items'
   own `x`/`y`) if none does.

Why file order and not "most specific wins": it's what CSS-literate agents expect, it's one sentence long, and it
lets a broad entry come first with narrow fixes after it. "Most specific" would need a scoring rule that nobody
remembers, and ties would need breaking anyway.

## 4. What each override field does

### 4.1 `grid`
It replaces columns × rows on the windows it matches. The §1.5 cell maths runs on the active grid. Positions are covered in §5.

### 4.2 `cellCap`
- `enabled: false`: no cap and no centring. Cells fill the page area, as on Compact today.
- `enabled: true` on Compact: the cap and centring apply there too.
- `maxWidthDp` and `maxAspect` replace 144 and 1.25 in the §1.5 formulas. The `pitchH ≤ 1.5 × pitchW` bound stays
  fixed, because it guards against cells too tall for one icon plus one label.

### 4.3 `dock.edge`
- `auto` is the §1.5 rule.
- `bottom` is a bar at the content width, as today.
- `top` is a bar at the content width. Order from top to bottom: top row (`Apps`, `⋮`) → banner → notice row →
  **dock** → page label or title bar → grid → page dots. The banner and notice row stay where people expect them, and
  the dock stays inside `safeDrawing`, clear of the status bar. **While dragging with a top dock, the `Remove` and
  `App info` targets move to the bottom of the page area**, so they aren't right beside the dock slots. TalkBack and
  D-pad order follow the visual order above.
- `start` and `end` are the §1.5 rail, with the top row at its head, mirrored in RTL.
- In every case: inside `safeDrawing`, items in `dock[]` order (start to end, or top to bottom on a rail), and the
  §1.5 slot shrink and scroll rules.
- A drag drop index follows the bar's or rail's axis. The written `dock[]` index means the same thing on every edge.
- When `fold.halfOpened` isn't `span`, the dock goes in the half that holds home. In tabletop mode with `auto` it stays
  in the bottom half (§1.5).

### 4.4 `scale`
- `icon` multiplies every step of the icon ladder (56 → 48 → 48, all × `icon`) on home tiles and folder tiles.
- **"Fits" checks both axes** (Pragma B4): a step fits when the icon ≤ the cell's content width (`pitchW − 12 dp`)
  **and** icon + one label line ≤ the content height. The drawn icon is
  `min(step size, content width, content height − label line when shown)`, so a large scale never cuts an icon or
  overlaps a neighbour.
- **Dock and rail:** icon = `min(56 × icon, slot − 16 dp)`. Slots never grow, so 7 items still fit (§1.5 slot rules).
- **Touch targets never drop below 48 dp** (§12), whatever the scale: a cell or dock slot is at least 48 × 48 dp of hit
  area even when the icon drawn is 28 dp.
- `labels: never` hides home labels (TalkBack still reads them).
- `labels: always` takes the label-hiding step off the ladder: the icon steps down to
  `min(current step, max(32 dp, 40 × icon))` first. That never grows a small icon. If even that doesn't fit, labels
  hide anyway, because **a label is whole or hidden, never cut** (§12). No warning is written, since a fit that depends
  on the window can't write (§1.5).
- The dock has no labels, so `labels` doesn't apply there.
- `validate.py` prints an info line when `scale.icon` ≥ 1.5 in an entry whose active grid has ≥ 4 columns:
  `Labels will likely be hidden on phones at this icon size.` It isn't a phone warning, because the result depends on
  the window.
- New L-check for Grace: `icon: 2` on s360 at font scale 1.3. Icons are whole and inside their cells, labels are whole
  or hidden, and dock icons are ≤ slot − 16 dp.

### 4.5 `fold` and `orientationLock`
See §8 and §6.

## 5. Positions: which coordinates are drawn, and which an edit writes

**Pragma's constraint: an edit made on one device must never make an item move on another device.** More exactly:
*an edit changes positions only in the layout it was made in. A window that uses a different layout (another device,
or this device after a rotate or fold) shows no change.* Two windows that use the **same** layout (two phones in
portrait) still move together, as they do today. That's sync, not a jump. Pragma confirmed this reading in review.

### 5.1 How a layout places things
- **Each item's own spot** is its `page`/`x`/`y` (plus `w`/`h` for elements), as today. It's the **inherited spot**:
  every layout that has no `positions` entry for the item uses it. **Main** (the root `grid`) has no `positions` map,
  so it always uses the inherited spots.
- **Validation of inherited spots** **(changed by Christopher's Q1)**:
  - An inherited spot must fit the **largest grid in the file**: the root `grid` or any `windowOverrides[].grid`.
    Outside all of them is an error (§9.1). Without `windowOverrides`, that's the root grid, exactly as today.
  - Two inherited spots that overlap are an **error when the file has no `windowOverrides`**, as today, because with
    one layout an overlap can only be a mistake. **With `windowOverrides`, they're a warning**: an item added on a
    tablet layout can legitimately sit where a phone item sits on main. On each layout, the later one in file order is
    skipped.
- **Placing on any layout** (main or override), for each grid-placed thing (page item or grid-placed element):
  1. **An entry in `positions`** (override layouts only). One of:
     - `{"page"?, "x", "y"}`, plus `"w"`, `"h"` for elements. `page` defaults to the item's own page. It's validated
       against **this entry's grid**: outside it, or overlapping another `positions` entry on the same page, is an
       error (§9.1).
     - `{"hidden": true}`: **hidden on purpose**, by a person (`Hide on this layout`, §5.4) or an agent. Not counted in
       `warn_override_hidden`.
     - `{"skipped": true}`: **written by a pin** (§5.2) where the item wasn't shown when it was pinned. It's treated
       like an inherited spot that doesn't fit: counted in `warn_override_hidden` and listed in `Hidden here` as
       `Doesn't fit this grid`. Keeping it apart from `hidden` means an item that was only skipped by accident never
       becomes silently invisible (Pragma B2).
  2. **Otherwise the inherited spot**, if it fits this layout's grid **and** doesn't overlap anything already placed
     (step 1 entries first, then inherited spots in file order: pages in order, each page's items, then `elements[]`).
  3. **Otherwise the item is skipped on this layout:** not drawn, not moved, not written. It's listed in one load-time
     warning for this layout (`warn_override_hidden`, or `warn_main_hidden` on main, §9.2) and in `Hidden here`
     (§5.4). Its spot in the file is never rewritten.
  - Skipped or disabled elements still reserve their effective cells in every layout, as today.
- `validate.py` prints an info line per layout for each `skipped` and each `hidden` item, and for each item an override
  layout inherits.
- **What stays shared across all layouts** (§0): which items exist, the pages and their order, folder contents,
  `dock[]`, every element's `content`/`style`. Only places and sizes differ.
- The phone keeps `positions` verbatim (canonical reformat, AQ-2). An agent may write it by hand, and `validate.py`
  checks it.

### 5.2 What a user edit writes
Every edit (drag, `Move to…`, `Resize…`, the dock, `Add to home`, the widget picker, folders, Undo) is computed against
the **active layout**: its grid and its occupancy, meaning the places from §5.1 including borrowed ones, plus reserved
cells. It's one write (§4.3), replayed by ID at write time (§4.4). This applies to **app items, shortcuts and folders
as well as elements**.

| Edit, made while… | …the main layout is active | …override layout `L` is active |
|---|---|---|
| Move / drag / `Move to…` / `Resize…` | Writes the item's own (inherited) `page`/`x`/`y` (`w`/`h`). **Pin:** the same write adds, to every override layout with no `positions` entry for this item, the place it was borrowing *before* the edit, or `{"skipped": true}` if it wasn't shown there | Writes only `L.positions[id]`. Main and other layouts are untouched (they never borrow from `L`) |
| `Add to home` (drawer) / widget picker / out of a folder | **Never refused** (Christopher, Q1). Writes only the new item's own spot: the chosen cell, or the first free spot on main as today. Every override layout inherits it if it fits and is free there, and otherwise skips it (§5.1: load-time warning, `Hidden here`). Nothing is written to any other layout, and no saved position is rewritten | The same, on `L`: the new item's own spot is the chosen cell or `L`'s first free spot, so `L` shows it there. Every other layout, **main included**, inherits it if it fits and is free there, and otherwise skips it (warning, `Hidden here`). No `positions` entry is needed, and nothing else is written. Example: added at column 6 on a 6-column tablet layout, the item isn't shown on a 4-column phone, and the phone's `Hidden here` lists it |
| `Remove from home` | Deletes the item or element and **every** `positions[id]` entry, in one write (as `pages[].overrides`, §16.7.1) | Same. Removal is file-wide: one item set, and people expect "remove from home" to mean home everywhere |
| Into the dock / out of the dock | Membership is shared, so it's file-wide in every layout, and the `positions[id]` entries are deleted with it. This is the documented exception: the item changes kind, so it doesn't "jump" | Same |
| **Folder creation** (drop an item on another) | In **every** layout the new folder takes the **target** item's place (its `positions` entry, borrowed spot or skipped state), and the dragged item leaves its place. In user terms (for ux.md): "Making a folder on the tablet makes it on the phone too, where the target app was." | Same |
| Into an existing folder | As Remove for the moved item's places (folder membership is global, §0) | Same |
| Undo of a remove (§16.7.1, and the same for app items) | Puts the object back verbatim **with its own spot and its `positions` entries**. On the **active layout**: old cells if free, otherwise the free-spot scan on its grid, written as a move on that layout (on main, a move of the own spot, with pins). **Every other layout** places it by the §5.1 rules. Where its old place is now taken, it's skipped there (warning, `Hidden here`) and nothing is rewritten. No message for that: it's a restore, not a move. `undo_element_moved` (`Put "%1$s" back on %2$s.`) shows only when it lands off its old cells **on the active layout**. No room on the active layout → `undo_element_no_room`, as today | Same rules, with `L` as the active layout |

**Pages are global.** Going past the last page creates a new empty page on every device, as today (ux.md §4.2).
"An empty page is deleted when you leave edit mode" means **empty in the file**: no main item on it **and** no
`positions[…].page` pointing at it. A page that's empty on this layout only is kept, so the phone never loses a page.

**Why this model** (rejected options in brackets):
- **[Every edit writes the shared spot, the §1.5 proposal.]** A tablet *drag* of an existing item to column 6 makes it
  vanish on the phone. That's exactly the cross-device jump Pragma rules out. (A tablet *add* at column 6 that the
  phone doesn't show is different: the item is new, so nothing moved. Christopher chose that in Q1.)
- **[Edits write main only, and override layouts are read-only.]** A phone edit would still move the item on a tablet
  that borrows main positions. That's a jump.
- **[Per-override maps with no pin.]** The same phone-edit jump for every borrowed item.
- **Maps plus pin-on-main-edit** is the only option that makes the invariant total (Pragma agreed). It's also
  compatible with "a resize never writes": pins and map entries are written only as part of an explicit user edit,
  never because a window matched. Rotating or folding just switches which layout is read.
- **Cost:** the file gains `positions` entries as people edit. That's bounded by items × grid overrides (≤ 2000 × 16;
  in practice a few dozen), and agents see plain `{x, y}` objects. `validate.py` lists the borrowed items, so an agent
  knows that a main edit by hand moves them there too. Hand edits are the agent's explicit choice; only phone edits pin.

### 5.3 Which layout am I editing? (Pragma B1)
- **Edit bar:** on main, the title stays `Editing layout` (unchanged, no golden churn). On an override layout it's
  `Editing layout · %1$s` (`edit_layout_title_named`), e.g. `Editing layout · Tablet landscape`, using `label`, else `id`.
- **Rail mode** (§1.5 hides the edit-bar text there): the same text shows as a **chip in the notice row**. It gets its
  own 48 dp row, **in edit mode only, and only while an override layout is active**. ux.md §1.5's rail bullet needs
  amending to say so (§15).
- **TalkBack on entering edit mode on an override layout:** `Editing the %1$s layout. Changes stay on screens that use
  it. Double-tap Done when finished.` (`edit_layout_a11y_named`).
- **A rotate, fold or resize that changes the active layout while in edit mode:** a 4 s snackbar, announced too:
  `Now editing the %1$s layout.` (`edit_layout_switched`). On main, the name is `main` (`layout_name_main`), so it reads
  `Now editing the main layout.`

### 5.4 Hidden here: finding and bringing back items (Pragma B3, O5)
- In edit mode on **any** layout, main included, when the count of items it doesn't show (skipped + `skipped` +
  `hidden`) is > 0, the edit bar gets a button `Hidden here (%d)` (`hidden_here_button`, plurals). In rail mode it lives in `⋮`.
- It opens a sheet titled `Not shown on this layout` (`hidden_here_title`).
  - Each row is the item's label, with a secondary line `Doesn't fit this grid` (`hidden_here_reason_skipped`: skipped
    or `skipped: true`) or `Hidden on this layout` (`hidden_here_reason_hidden`: `hidden: true`).
  - Each row has the action `Show here` (`hidden_here_show`).
  - TalkBack reads a row as `<Label>, doesn't fit this grid, Show here, button`.
- `Show here` places the item at the first free spot, scanning row-major from the item's own page, then later pages.
  On an override layout it writes `L.positions[id]`. On main it's a move of the own spot, with pins (§5.2), so no other
  layout changes. With no room, the row is disabled with the existing `No room`.
- **Long-press on an override layout** gets `Hide on this layout` (`hide_on_layout`), which writes
  `{"hidden": true}` into `L.positions[id]`. It isn't offered on main: hiding there is `drawer.hidden` or Remove.
- In O4 (read-only), the button and sheet show, but without `Show here` (§10).

### 5.5 Rotating, folding or resizing mid-edit
- **A drag in progress is cancelled** (§1.5), with no write and no copy: the item springs back.
- **Edit mode stays on** and now edits the layout that's active after the change, with the §5.3 switch snackbar.
- **An open `Move to…`, `Resize…` or `Hidden here` sheet closes without writing if the active layout changed.** Its
  ranges belonged to the old grid. The snackbar (announced) is `Screen size changed, so nothing was moved.`
  (`edit_sheet_closed_layout_changed`). If only chrome changed (the same layout), the sheet stays open.
- **Queued (debounced) writes** carry their target layout's entry `id` (or main). At replay they write to that layout,
  even if the window has moved on. If that entry is gone by then (an agent deleted it) or the target is taken, nothing
  is written and the existing `move_element_taken` snackbar shows.
- **Undo after a rotate:** the Undo snackbar survives the change. Its scan runs on the layout that's active **when Undo
  is tapped**.
- **Reserved cells:** the hatch marks only cells held by **disabled or unknown-type elements** (and elements above the
  FEATURE_LEVEL) on this layout. Items skipped because they don't fit have no cells on this layout, and items skipped
  by overlap sit under the item that holds those cells, so neither is hatched: they're in `Hidden here`.
  - Hatch: 45° lines in white at 16 % (the same as the cell outlines).
  - TalkBack: `Reserved, column %1$d, row %2$d` (`cell_reserved_a11y`).
  - A drop there is refused with the shake, or the red outline under reduced motion.

## 6. Orientation lock

### 6.1 Rule
- `orientationLock` is allowed only in an entry whose `when` is `{}` or contains **only `deviceClass`**. Anything else
  is a validation error (§9.1). `deviceClass` comes from the display, so the lock can't change it, and the rule can't
  flip itself.
- The effective lock is the last such matching entry. `system` (the default) = `SCREEN_ORIENTATION_UNSPECIFIED`, which
  is today's behaviour. `portrait` = `USER_PORTRAIT` and `landscape` = `USER_LANDSCAPE`: both directions, honouring the
  user's auto-rotate for the 180° flip.
- **A landscape-only phone** is `{ "when": { "deviceClass": "phone" }, "orientationLock": "landscape" }`.
- Applying it:
  - `setRequestedOrientation` is called in `onCreate` **before the first frame**, from the config already in memory
    or `last-good.json` (no extra storage). This avoids a portrait flash and then a rotation.
  - A reload that changes the lock calls it again, and the Activity recreates with the §1.5 state kept.
  - A fold re-evaluates it (cover vs inner changes `deviceClass`). That isn't a loop, because the lock never folds.

**Scope** (Pragma S9): the lock applies to the **home activity** only: home, the drawer, folders, and the item and
Pages sheets. First run, Settings, the §6.2 detail view and system pickers (SAF, the role request) follow the system.
They're full-screen forms, and first run has no config anyway. A reload that changes the lock recreates the Activity
and keeps the drawer's search text (the §1.5 state rule).

### 6.2 "A lock removes the variants that can no longer match": only statically
- The phone never removes entries. Matching always reads the real window, because a locked device can still show other
  shapes: in split screen and freeform the window's shape ignores the lock, and on large screens the OS may ignore the
  lock entirely (§6.3).
- So the lock prunes at **validation**: an entry whose `when` contradicts the lock that's sure to apply with it is
  reported as unreachable outside multi-window. Example: the lock is `portrait` for `phone`, and an entry has
  `{"deviceClass": "phone", "orientation": "landscape"}`. That gives `validate.py` an info line and the phone's load-time
  `warn_override_unreachable`, file-derived and the same on every device. It's a warning, not an error, because split
  screen can still reach it.

### 6.3 ⚠ Technically limited: Android 16+ large screens
- For apps **targeting API 36+**, Android **ignores `screenOrientation` and `setRequestedOrientation` on displays with
  smallest width ≥ 600 dp**: tablets, a fold's inner screen, and desktop windowing.
  - Games are exempt. The user can re-enable the app's request in the per-app aspect-ratio settings.
  - The `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` opt-out exists only until target 37.
- The app targets 35 today (`app/build.gradle.kts`), so the lock works everywhere **for now**. It stops working on
  `tablet` when the app's target moves to 36, which Play will require.
- **Recommended:**
  - Document `orientationLock` as **reliable on `phone` only**.
  - Don't use the opt-out property: it's temporary, and the launcher is adaptive anyway.
  - On load, if a lock for `tablet` (or for `{}` on a tablet) applies to this device and the device would ignore it
    (API ≥ 36 and target ≥ 36, display ≥ sw600), write the phone-only `warn_orientation_lock_ignored`, marked
    `checked on this device`. It's derived from the device and the file at load, like Q-A12, never from the window.
- A phone's cover screen is < 600 dp, so the lock holds there.

## 7. Versioning and how old phones fail

**Choice: the Q-A10 route** (an optional root key, `schemaVersion` 1), **not** `schemaVersion` 2.
- **FEATURE_LEVEL 5** = "understands `windowOverrides`".
  - `Elements.FEATURE_LEVEL` and `validate.py`'s `FEATURE_LEVEL` go to 5 in slice O1.
  - **`net.fetch` moves from its proposed level 5 to 6.** It's unshipped, and a level-5 phone that meets a
    `requires: 5` card using `net.fetch` still skips just that card through the unknown-`kind` path (Q-A7), so nothing
    breaks.
  - The docs and schema descriptions that say "net.fetch, level 5" are updated in O1.
- **Old phones (FEATURE_LEVEL ≤ 4, including dev8):**
  - The root keys are strict, so a file with `windowOverrides` is **B2: the whole file is rejected and the last working
    layout is kept**.
  - The error is the existing `unknown key "windowOverrides"` at the root, and the banner and error view are as in §6.
  - The old phone writes nothing and the file is untouched. When the app updates, it loads.
  - This is the same as Q-A10's `theme` and acceptable pre-release.
  - **`validate.py` from before level 5** gives the same error, so CI on an old checkout fails loudly too.
  - test-plan O0 already expects this.
- **Level-5 `validate.py`** prints an info line whenever the key is present: `windowOverrides needs Config Launcher
  feature level 5 or later; older versions reject this file.`
- **Why not `schemaVersion` 2:**
  - It gives old phones the same outcome (rejection), with a migration path, B8 upgrade notices and a second schema
    file to maintain, forever.
  - v2 is reserved for breaking changes (widgets.md §5), and this is purely additive.
- **Why not an `elements[]` type** (an old phone would skip it gracefully):
  - Override layouts with `positions` are edited by the phone and change where every item sits. An old phone ignoring
    them while editing base would silently diverge.
  - An element is something drawn, and this isn't.
  - Rejecting is the honest failure here.
- **After level 5, per entry:**
  - An entry with `requires` > FEATURE_LEVEL is skipped with `warn_override_level`, before strict key checks. That's how
    later fields (say a level-7 `scale.title`) avoid B2 on level-5 phones.
  - `validate.py` computes the needed `requires` and checks it.
  - Inside an entry with `requires` ≤ level, unknown keys are errors (B2), like the known element types.
- **Unknown enum strings** (Q-A10 style):
  - In `validate.py`/CI, every bad value is an error.
  - On the phone, a wrong JSON type or an out-of-range number is B2 with the same message.
  - An **unknown string** doesn't reject the file:
    - in `when`, the entry never matches (`warn_override_when_value`);
    - in a field, that field keeps its default (`warn_override_value`).
  - This lets a level-6 value (`posture: "folded"`) degrade on a level-5 phone without a `requires`.

## 8. Folds (⚠ every behaviour unverified on real hardware)

Christopher has no foldable, so these are Archimedes' best guesses. **The defaults are the conservative §1.5 ones,
every one can be overridden, and each is tested only on emulators that report a real `FoldingFeature`**:
- Android Studio's *7.6" Fold-in with outer display*, *Pixel Fold* and *Pixel 9 Pro Fold* AVDs, plus the resizable AVD;
- posture driven from the Extended Controls *Virtual sensors → Posture* (closed, half-open, open) and the hinge angle
  slider;
- **not** with `wm size`, which reports no hinge. Run 2's fold points were only that.

No question about folds goes to Christopher.

### 8.1 `posture` matcher
- It needs the `androidx.window:window` dependency: `WindowInfoTracker.windowLayoutInfo(activity)`, collected in the
  Activity and passed into the pure geometry as data.
- `flat` = `FoldingFeature.state == FLAT`.
- `halfOpened` = `HALF_OPENED`. `book` = `HALF_OPENED` with a `VERTICAL` orientation, and `tabletop` = with a
  `HORIZONTAL` one.
- A `FoldingFeature` is only reported when the window overlaps the hinge. So a split-screen window on one half sees no
  posture, which is correct: it isn't straddling anything.

### 8.2 `fold` fields and their defaults
- **`fold.flat`:**
  - **Default `asTablet`**: a flat hinge is ignored, as in §1.5.
  - `asPhone` applies the **Compact-width rules** to the window: no cap, full-width chrome, `auto` dock as on a phone,
    drawer columns = `grid.columns`.
  - It never changes what `when` sees (`widthClass` is still the real one), so it can't loop.
  - Why `asTablet` is the default: the inner screen *is* tablet-sized, and the §1.5 rules were designed for it.
- **`fold.halfOpened`** applies only while `isSeparating` is true:
  - **Default `auto`**: book uses the start half, tabletop puts the grid on top with the dock below (§1.5).
  - `start`, `end`, `top` and `bottom` choose the half. A value that doesn't fit the hinge direction (`top` in book
    posture) falls back to `auto`, and `validate.py` warns when the entry's `when` makes the clash certain (e.g.
    `posture: "book"`).
  - `span` ignores the hinge and lays out over the whole window. Cells may sit under the hinge, and on a device whose
    hinge **occludes** (`occlusionType == FULL`, dual-screen) that content is hidden. That's the user's choice, and it's
    documented, not prevented.
  - Why the conservative default: one half is guaranteed to show everything. `span` is the "weird stuff" knob.
- The half chosen is laid out "as if it were the window" (§1.5), so `widthClass`/`heightClass` for *chrome* come from
  the half. `when` still matches the **whole window**, so choosing a half can't change which entries match.
- **No write on fold or unfold, ever.** The page is kept by `id`, and the rest follows §1.5.

### 8.3 ⚠ Hard: `posture: "folded"` (the cover screen). **Deferred, heuristic ready.**
- The cover screen reports **no `FoldingFeature`**, so `windowLayoutInfo` can't tell it from an ordinary phone.
- The API that would say so (`DeviceStateManager` / `DeviceState` foldable properties) is a **`@SystemApi`**, so the
  app can't call it.
- **Heuristic** (only if Christopher or a user ever needs it):
  - `folded` = the device has `PackageManager.FEATURE_SENSOR_HINGE_ANGLE` (API 30+; treated as false on API 29) **and**
    this window has no `FoldingFeature` **and** the display's smallest side is < 600 dp.
  - Known false results: flip phones whose cover screen hosts no launcher, and devices without the hinge sensor
    feature flag.
  - Emulator-testable (the foldable AVDs declare the sensor).
- **Recommendation:** don't accept `folded` at level 5. The way to say "the cover screen" is
  `{"deviceClass": "phone"}`, which matches it already (and ordinary phones too, which for one person's files is the
  same thing). Add `folded` with the heuristic in slice O7 only if asked. Level-5 phones treat it as an unknown `when`
  value (§7).

## 9. Validation and warnings

### 9.1 Errors (`validate.py`/CI always; phone B2 for type and range errors, as in §7)
These are path-based, in the existing `Validator.kt` style. The existing generic messages are reused for type, range,
missing keys and unknown keys (`unknown key "x"`, `missing required "id"`, `… must be an integer from 2 to 12`,
duplicate IDs). New messages:

| Path | Message |
|---|---|
| `windowOverrides` | `windowOverrides has %1$d entries; the maximum is 16. Combine entries that share the same "when".` (a person on the phone only meets it as B2) |
| `windowOverrides[i].label` | `%1$s must be 1 to 40 characters.` |
| `windowOverrides[i].when.widthClass` / `.heightClass` | `%1$s must be compact, medium or expanded.` |
| `….when.orientation` | `%1$s must be portrait or landscape.` |
| `….when.deviceClass` | `%1$s must be phone or tablet.` |
| `….when.posture` | `%1$s must be flat, halfOpened, book or tabletop.` |
| `….when.minWidthDp` … | `%1$s must be a whole number from 0 to 10000.` / `minWidthDp is larger than maxWidthDp, so this entry never applies.` |
| `….orientationLock` | `%1$s must be portrait, landscape or system.` |
| `….orientationLock` (keyed wrong) | `%1$s can only be used when "when" is empty or has only deviceClass, because the lock itself changes the window's shape and size.` |
| `pages[p].items[i]` / `elements[e]` (own spot) | `x %1$d + w %2$d is outside every grid in this file (the largest has %3$d columns)` / `y … (%3$d rows)`. Without `windowOverrides` it's the existing message, unchanged |
| own spots overlapping | The existing overlap error **only when the file has no `windowOverrides`**. With it, it's a warning (§9.2, `warn_main_hidden` / `warn_override_hidden`) |
| `….positions` without `grid` | `%1$s needs a grid in the same entry; positions belong to that grid.` |
| `….positions.<id>` | `x %2$d + w %3$d is outside this entry's grid (%4$d columns)` / `y … (%4$d rows)` (the existing wording, scoped to the entry) |
| `….positions.<id>` overlap | `overlaps %2$s on page "%3$s" in this entry's grid` |
| `….positions.<id>.page` | `page "%2$s" doesn't exist` |
| `….positions.<id>` (bad keys) | `hidden and skipped can't be combined with each other or with page, x, y, w or h` / `w and h are only for elements` |
| `….dock.edge` | `%1$s must be auto, bottom, top, start or end.` |
| `….cellCap.maxWidthDp` / `.maxAspect` | `%1$s must be a whole number from 64 to 480.` / `%1$s must be a number from 1 to 3.` |
| `….scale.icon` / `.labels` | `%1$s must be a number from 0.5 to 2.` / `%1$s must be auto, always or never.` |
| `….fold.halfOpened` / `.flat` | `%1$s must be auto, start, end, top, bottom or span.` / `%1$s must be asTablet or asPhone.` |

### 9.2 Phone warnings (`status.json` `warnings`, the Warnings list; no banner, no snackbar)
These are code, not `strings.xml`, as in ux.md §15.1. Every count in them has a singular form (`1 item isn't`, `1 entry`),
and the code picks it. They're **all computed at load or reload from the file (Q-A12), never
from the window.** Each override entry is checked at load **as if** it were active: its grid, its `positions`, what
it borrows and what's skipped. So every device writes the same list, except `warn_orientation_lock_ignored`, which is checked on this device.
Each warning names the entry by index **and** `id`:

| Code | Text |
|---|---|
| `warn_override_hidden` (one per entry) | `windowOverrides[%1$d] ("%2$s"): %3$d items aren't shown on windows that use its %4$d × %5$d grid (%6$s), because they don't fit it or overlap another item. Give them positions there, or change the grid.` With a count of 1: `… 1 item isn't shown …`. `%6$s` = the first 3 ids, then `and %d more`. **Counts skipped items and `{"skipped": true}` pins.** Only `{"hidden": true}` (chosen on purpose) isn't counted |
| `warn_main_hidden` (one, for main) | `Main layout: %1$d items aren't shown on windows that use the main %2$d × %3$d grid (%4$s), because they don't fit it or overlap another item. Move them there, or change the grid.` With a count of 1: `… 1 item isn't shown …`. Typically items added on a bigger layout |
| `warn_override_position_id` | `windowOverrides[%1$d] ("%2$s").positions: "%3$s" doesn't match any item, so it was ignored.` (kept, as with `pages[].overrides`) |
| `warn_override_level` | `windowOverrides[%1$d] ("%2$s") needs feature level %3$d, but this Config Launcher supports up to %4$d, so it was skipped. It stays in the file. Update the app to use it.` |
| `warn_override_when_value` | `windowOverrides[%1$d] ("%2$s").when.%3$s "%4$s" isn't supported by this Config Launcher, so this entry never applies. Update the app, or check for a typo.` |
| `warn_override_value` | `windowOverrides[%1$d] ("%2$s").%3$s "%4$s" isn't supported by this Config Launcher, so the default is used. Update the app, or check for a typo.` |
| `warn_override_unreachable` | `windowOverrides[%1$d] ("%2$s") only applies in split screen here, because the orientation lock from windowOverrides[%3$d] keeps the full screen %4$s.` |
| `warn_orientation_lock_ignored` (checked on this device) | `windowOverrides[%1$d] ("%2$s"): this tablet ignores app orientation locks (Android 16 and later), so home rotates freely. Checked on this tablet.` |
| `warn_override_fold_side` | `windowOverrides[%1$d] ("%2$s").fold.halfOpened "%3$s" doesn't fit a %4$s hinge, so the default half is used.` (only when `when.posture` makes the clash certain) |

The **title-bar fit check** (Q-A12) runs on a synthetic portrait window at `smallestScreenWidthDp`, so it applies the
entries that would match **that** window (with no posture). A `cellCap` override can therefore change the checked
content width, and the result is still file-derived.

## 10. Staging after dev8 (PR-sized slices for Sprite)

Each slice is mergeable on its own. Strict keys keep any field from a later slice rejected (B2/CI error) until that
slice lands, so a half-built release never ignores something silently.

| Slice | Contents | Tests |
|---|---|---|
| **O1** schema + matcher | `windowOverrides` in the schema, `Validator.kt` and `validate.py` (entries, `id`, `requires`, `when` with all level-5 keys, the §7 unknown-value rule); FEATURE_LEVEL → 5 and `net.fetch` → 6 in the docs; pure `resolve(entries, WindowContext) → Resolved` (leaf merge); `cellCap` and `scale` wired into `HomeGeometry`; warnings `_level`, `_when_value`, `_value` | Fixtures valid/invalid; resolver unit tests; O3, O7, O8; L1 (no write on rotate) |
| **O2** dock edge | `dock.edge` (top bar, start/end rail), the drag index on both axes, insets, RTL | O4; L5/L6 rerun with each edge |
| **O3** orientation lock | The `deviceClass`-only rule, the lock before the first frame, `warn_override_unreachable`, `warn_orientation_lock_ignored` | O5 (landscape-only phone on an emulator), a tablet AVD at API 36 with a test build targeting 36 |
| **O4** grid layouts, read-only | `grid` + `positions` + `label` parse and validate; §5.1 placement (map → borrow → skip); `warn_override_hidden`, `_position_id`; the read-only behaviour in §10.1; `Hidden here` without `Show here` | O1, O2; rotate between layouts, hash unchanged; §10.1 copy and TalkBack |
| **O5** edits on any layout | §5.2 writes, pins (`skipped`) and `hidden`; folder placement; global pages; Undo across layouts; queued writes keyed by entry `id`; §5.3 naming (edit bar, rail chip, announcements); §5.4 `Hidden here` with `Show here` and `Hide on this layout`; §5.5 rotate-mid-edit rules and reserved-cell hatching; the Settings `Grid` hint (§13) | New O9–O13 (below) |
| **O6** folds | `androidx.window`, the `posture` matcher, `fold.flat`, `fold.halfOpened`, `warn_override_fold_side`; with `span`, grid focus runs straight across the hinge in reading order | O6 on foldable AVDs with posture simulation only |
| **O7** (only if asked) | The `posture: "folded"` heuristic (§8.3) | Foldable AVD closed vs a normal phone AVD |

New test-plan checks for Grace (the O-table in #185):
- **O9 (no cross-layout jump):** with base 4 × 5 and a `tablet-land` 6 × 4 layout, drag on the phone, then load on the
  tablet: tablet places unchanged; pins written. The reverse: tablet drag, phone unchanged.
- **O10:** a rotate mid-`Move to…` closes the sheet with no write; a rotate mid-drag cancels it.
- **O9b (add never refused):** add at column 6 on the tablet's 6-column layout. The phone (4 columns) doesn't show it,
  writes `warn_main_hidden` and lists it in `Hidden here`. No other position in the file changes.
- **O11:** Undo after a rotate scans the layout that's active when Undo is tapped.
- **O12 (Hidden here):** a main edit pins `skipped` on the tablet layout; the tablet's count and `warn_override_hidden`
  include it; `Show here` places it; `Hide on this layout` removes it from the count.
- **O13 (layout name):** the edit bar, the rail chip and TalkBack name the layout; a rotate across layouts in edit
  mode announces `Now editing the … layout.`
- **L-check:** `scale.icon: 2` on s360 at font scale 1.3 (§4.4).

### 10.1 What O4 (read-only) shows (Pragma B5)
While an override layout with `grid` is active and O5 hasn't shipped:
- **Long-press menu:** `Edit layout` is shown **disabled**, with the reason `Not editable here yet`
  (`layout_readonly_reason`).
- **Long-press-drag** doesn't start a drag. It shows the snackbar `The %1$s layout can't be edited here yet. Change it in
  launcher.json.` (`layout_readonly`).
- **Disabled, with the reason `Not editable here yet`:** the drawer's `Add to home`, `Move to…`, `Move to dock`,
  `Add to folder…` and `Remove from home`. TalkBack keeps them listed as `<action>, Not editable here yet` and announces
  the `layout_readonly` snackbar text on activation, as in the §16.7.2 `No room` pattern.
- **Still available:** `Hide` (`drawer.hidden`) and `App info`, since they carry no coordinates.
- **Rotating into an override layout while in edit mode** leaves edit mode, with the same snackbar. Rotating back
  doesn't re-enter it.
- **`Hidden here`** (§5.4) is visible, with no `Show here`.

## 11. Hard parts (flagged, not dropped)

| Hard part | Approach |
|---|---|
| Edits under several layouts without cross-device jumps | Per-layout `positions` plus pin-on-main-edit (§5). It's the largest slice (O5), so O4 ships override grids read-only first |
| Orientation lock on ≥ sw600 under target 36+ | The OS ignores it. Documented as phone-only, with the `warn_orientation_lock_ignored` load-time warning; no opt-out property (§6.3) |
| `posture: "folded"` | No public API. Deferred, with the hinge-sensor heuristic ready (§8.3); use `deviceClass: "phone"` |
| Fold behaviour in general | Unverified on hardware. Conservative defaults, every one overridable, emulator-only tests with a real `FoldingFeature` (§8) |
| `span` over an occluding hinge | Allowed and documented; content under the hinge may be hidden (§8.2) |
| The lock's first frame | Apply it in `onCreate` from the config in memory or last-good, before `setContent` (§6.1) |
| Class flapping near 600/840 dp while resizing | It can switch layouts, so items may appear or disappear while dragging a window edge. No writes; a drag is cancelled. Accepted |
| Agents editing main positions by hand | Borrowed places follow the hand edit (only phone edits pin). `validate.py` info lines list the borrowed items per entry |

## 12. Christopher's answers: **decided** (2026-10-26)

Pragma agreed with all six, and Christopher answered them, in his own words where quoted. None were about folds:
Archimedes picks those (§8).

1. **Adding on a layout: decided, never refused.** "The item goes into the active layout's spot. Every other layout
   uses the inherited base spot if it fits there. If it doesn't fit, it isn't shown on that layout": load-time warning,
   `skipped`, and listed in `Hidden here`. Saved positions are never rewritten. The "refused if the phone is full" rule
   is dropped, and Pragma's S1 (giving other layouts their own first free spot) and `add_no_room_main` with it (§5.1,
   §5.2). In earlier words: "Anything that doesn't fit the smaller grid is simply not shown on that screen, and you get
   a warning when the config loads. Its saved position is never moved or rewritten."
2. **Remove and other global changes: decided.** "You're editing the one config every device uses" (§0). Add, remove,
   dock membership and folder membership are global, and only position and size are per layout. Remove is global.
   `Hide on this layout` covers "not on this screen".
3. **Older app versions reject override files: decided, fine pre-release** (§7).
4. **Orientation lock reliable on phones only (Android 16+): decided, accepted** (§6.3).
5. **Read-only first: decided.** Ship O4 (custom grids shown, not editable, §10.1), then O5 (editing).
6. **Icon scale: decided,** `scale.icon` 0.5–2× (28–112 dp), with tap areas never below 48 dp (§4.4).

## 13. Settings `Grid` on an override layout (Pragma S3)
- When an override layout with `grid` is active, ux.md §8's `Grid` pickers still change **main**. A helper line goes
  under them: `This screen uses the %1$s layout (%2$d × %3$d) from launcher.json. These pickers change the main
  layout.` (`settings_grid_override_hint`).
- **Out of scope:** editing an override layout's grid from Settings. Agents edit `windowOverrides[].grid` in the file.

## 14. Phone-UI strings (`strings.xml`, Pragma's table)
Pragma's table had 16 strings. `add_no_room_main` is dropped, because adding is never refused (Christopher, Q1), which
leaves 15.
§9.2 warnings stay in code (as in ux.md §15.1). Every string people see on the phone gets a `strings.xml` id:

| ID | Text |
|---|---|
| `edit_layout_title_named` | `Editing layout · %1$s` |
| `edit_layout_a11y_named` | `Editing the %1$s layout. Changes stay on screens that use it. Double-tap Done when finished.` |
| `edit_layout_switched` | `Now editing the %1$s layout.` |
| `layout_name_main` | `main` |
| `edit_sheet_closed_layout_changed` | `Screen size changed, so nothing was moved.` |
| `hidden_here_button` (plurals) | `Hidden here (%d)` |
| `hidden_here_title` | `Not shown on this layout` |
| `hidden_here_reason_skipped` | `Doesn't fit this grid` |
| `hidden_here_reason_hidden` | `Hidden on this layout` |
| `hidden_here_show` | `Show here` |
| `hide_on_layout` | `Hide on this layout` |
| `layout_readonly_reason` | `Not editable here yet` |
| `layout_readonly` | `The %1$s layout can't be edited here yet. Change it in launcher.json.` |
| `cell_reserved_a11y` | `Reserved, column %1$d, row %2$d` |
| `settings_grid_override_hint` | `This screen uses the %1$s layout (%2$d × %3$d) from launcher.json. These pickers change the main layout.` |

Reused unchanged: `No room`, `move_element_taken`, `undo_element_moved`, `undo_element_no_room`, `undo_element_back`.

## 15. Follow-ups once accepted (not now)
- **Pragma (ux.md):**
  - §1.5: align "Placement on a window uses that window's grid … the phone then skips that item" with §5 here. Adds
    work that way (own spot, skipped where it doesn't fit), but moves on an override layout write that layout's
    `positions`. "Valid as long as each position fits the largest grid" stands for own spots;
  - add §0 ("the one config every device uses", switching configs from outside the launcher) near the top of §1.5;
  - §1.5 rail bullet: the edit-mode layout chip in the notice row (§5.3);
  - §4.2: pages are global, and "empty" means empty in the file (§5.2);
  - folders: "Making a folder on the tablet makes it on the phone too, where the target app was.";
  - §8: the `Grid` helper line (§13);
  - add the §14 strings.
- **Grace (test-plan):** O9–O13, the `icon: 2` L-check, and the emulator-only fold note on O6.
- **Archimedes:**
  - ARCHITECTURE §19 entry;
  - the schema and `validate.py` text in O1;
  - the `net.fetch` → level 6 renumbering in ux.md §17, the schema descriptions and dynamic-widgets.md.
- **Sprite, in O1 (deferred from Pragma's nit):** one line in `templates/config-folder/AGENTS.md`, the layout-editing
  guide for agents: "`windowOverrides` holds at most 16 entries; combine entries that share the same `when`." It goes
  in with the feature, not now. The root `AGENTS.md` is about developing this repo, and documenting a key that no
  shipped build accepts would lead agents into B2.
