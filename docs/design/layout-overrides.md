# Design: layout overrides for windows, orientation and folds (`windowOverrides`, Q-A11)

Status: **proposal**, 2026-10-26. Author: Archimedes. Docs only: nothing changes in `schema/` or app code until this
is accepted and dev8 has shipped the §1.5 defaults.
**Stacked on [#185](https://github.com/Nasawa/config-launcher/pull/185)** (`ux/large-screen-layout` at 7dd5842, not
merged yet). This branch (`docs/layout-overrides`) starts from it, so it should be rebased onto `main` once #185 merges.

Christopher decided Q-L1–Q-L6 on 2026-10-26: the ux.md §1.5 defaults stand, and the config should be able to change
each one: "if the user wants to do weird stuff, we should provide the tools to facilitate it where possible." This
doc turns Pragma's candidate (ux.md Q-A11, #185 1feae3f) into a schema and its rules. Where it differs from that
candidate or from the §1.5 proposal text, the change is marked **(refined)** and the reason is given.

## 1. Decisions in brief

1. **Shape.** An optional top-level **`windowOverrides[]`** array. Each entry has an `id`, an optional `requires`, a
   `when` matcher and override fields (§3). Entries apply in file order, and a later matching entry wins field by
   field. A window that no entry matches uses the §1.5 defaults.
2. **Versioning.** This takes the **Q-A10 route**: an optional root key on **`schemaVersion` 1**, with no bump. The release
   that understands it is **FEATURE_LEVEL 5**. A phone below level 5 rejects a file that has the key (B2, last good
   layout kept). After level 5, each entry carries its own `requires`, so later override fields degrade per entry
   instead of rejecting the file (§7).
3. **Positions.** **(refined)** An entry that sets `grid` defines its own *layout*. It can hold its own positions
   (`positions`, keyed by item or element `id`). An item with no entry there falls back to its base `x`/`y` when that
   fits, and is otherwise **skipped on that window** with a load-time warning. A user edit writes only to the layout
   it was made in. When the edit is in the base layout, the same write first **pins** the item's old place in every
   other layout that was borrowing it. As a result, **an edit on one device never makes an item move on a device that
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
  { "id": "tablet-land", "when": { "deviceClass": "tablet", "orientation": "landscape" },
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
- `bottom` and `top` are a bar at the content width. `top` sits under the top row (`Apps`, `⋮`) and above the banner.
  The top row stays where it is.
- `start` and `end` are the §1.5 rail, with the top row at its head, mirrored in RTL.
- In every case: inside `safeDrawing`, items in `dock[]` order (start to end, or top to bottom on a rail), and the
  §1.5 slot shrink and scroll rules.
- A drag drop index follows the bar's or rail's axis. The written `dock[]` index means the same thing on every edge.
- With half-folded `oneHalf`, the dock goes in the half that holds home. In tabletop mode with `auto` it stays in the
  bottom half (§1.5).

### 4.4 `scale`
- `icon` multiplies every step of the icon ladder (56 → 48 → 48, all × `icon`) on home tiles, folder tiles and the dock.
- **Touch targets never drop below 48 dp** (§12), whatever the scale: a cell or dock slot is at least 48 × 48 dp of hit
  area even when the icon drawn is 28 dp.
- `labels: never` hides home labels (TalkBack still reads them).
- `labels: always` takes the label-hiding step off the ladder: the icon steps down to `max(32 dp, 40 × icon)` first.
  If even that doesn't fit, labels hide anyway, because **a label is never cut** (§12). No warning is written, since
  a fit that depends on the window can't write (§1.5).
- The dock has no labels, so `labels` doesn't apply there.

### 4.5 `fold` and `orientationLock`
See §8 and §6.

## 5. Positions: which coordinates are drawn, and which an edit writes

**Pragma's constraint: an edit made on one device must never make an item move on another device.** More exactly:
*an edit changes positions only in the layout it was made in. A window that uses a different layout (another device,
or this device after a rotate or fold) shows no change.* Two windows that use the **same** layout (two phones in
portrait) still move together, as they do today. That's sync, not a jump. Pragma to confirm this reading.

### 5.1 How a layout places things
- **Base layout:** the root `grid` and each item's `x`/`y` (plus `page`/`w`/`h` for elements), exactly as today and
  validated as today. Base positions must fit the **base** grid. This differs from the §1.5 proposal text ("fits the
  largest grid"): with per-layout positions, nothing ever needs base positions beyond the base grid.
- **Override layout** (an entry with `grid`). Each grid-placed thing (page item or grid-placed element) gets its place
  in this order:
  1. **An entry in `positions`:** `{"page"?, "x", "y"}`, plus `"w"`, `"h"` for elements, or `{"hidden": true}`.
     `page` defaults to the item's base page. An entry is validated against **this entry's grid**: outside it, or
     overlapping another `positions` entry on the same page, is an error (§9.1), just like base.
  2. **Otherwise the base place is borrowed:** the base `page`/`x`/`y`/`w`/`h`, if it fits this grid **and** doesn't
     overlap anything placed by step 1.
  3. **Otherwise the item is skipped on this layout:** not drawn, not moved, not written. It's listed in one load-time
     warning for this entry (§9.2). Its cells in other layouts stay its own.
  - Skipped or disabled elements still reserve their effective cells in every layout, as today.
- **What stays shared across all layouts:** which items exist, the pages and their order, folder contents, `dock[]`,
  every element's `content`/`style`, and the item set on each page *as far as the base is concerned*. Only places differ.
- The phone keeps `positions` verbatim (canonical reformat, AQ-2). An agent may write it by hand, and `validate.py`
  checks it.

### 5.2 What a user edit writes
Every edit (drag, `Move to…`, `Resize…`, the dock, `Add to home`, the widget picker, Undo) is computed against the
**active layout**: its grid and its occupancy, meaning the places from §5.1 including borrowed ones, plus reserved
cells. It's one write (§4.3), replayed by ID at write time (§4.4).

| Edit, made while… | …the base layout is active | …override layout `L` is active |
|---|---|---|
| Move / drag / `Move to…` / `Resize…` | Writes the base `page`/`x`/`y` (`w`/`h`). **Pin:** the same write adds, to every other override layout with no `positions` entry for this item, the place it was borrowing *before* the edit, or `{"hidden": true}` if it was skipped there | Writes only `L.positions[id]`. Base and other layouts are untouched (they never borrow from `L`) |
| `Add to home` (drawer) / widget picker | Base: the first free spot as today. Other override layouts borrow it if it fits (a new item appearing isn't a jump) | `L.positions[id]` at the chosen spot or `L`'s first free spot, **plus** a base place: the first free base cell, row-major, on the same page, then the following pages (the base needs `x`/`y`, schema). No base room anywhere → `No room` (the add isn't made), with the reason `No room on the phone layout`. Pragma owns the wording |
| `Remove from home` | Deletes the item or element and **every** `positions[id]` entry, in one write (as `pages[].overrides`, §16.7.1) | Same. Removal is file-wide: one item set |
| Into the dock / into a folder / out of the dock | Membership is shared, so it's file-wide in every layout, and the `positions[id]` entries are deleted with it. This is the documented exception: the item changes kind, so it doesn't "jump" | Same |
| Undo of a remove (§16.7.1) | Puts the object back verbatim **with its `positions` entries**. Old cells first, then the free-spot scan **on the active layout's grid**, writing that layout's coordinates. Any other layout whose old place is now taken gets `{"hidden": true}` (override) or the first free base cell (base). No room on the active layout → `undo_element_no_room`, as today | Same rules, with `L` as the active layout |

**Why this model** (rejected options in brackets):
- **[Shared coordinates valid against the largest grid, the §1.5 proposal.]** A tablet drag to column 6 makes the item
  vanish on the phone. That's exactly the cross-device jump Pragma rules out.
- **[Edits write base only, and override layouts are read-only.]** A phone edit would still move the item on a tablet
  that borrows base positions. That's a jump.
- **[Per-override maps with no pin.]** The same phone-edit jump for every borrowed item.
- **Maps plus pin-on-base-edit** is the only option that makes the invariant total. It's also compatible with "a resize
  never writes": pins and map entries are written only as part of an explicit user edit, never because a window
  matched. Rotating or folding just switches which layout is read.
- **Cost:** the file gains `positions` entries as people edit. That's bounded by items × grid overrides (≤ 2000 × 16;
  in practice a few dozen), and agents see plain `{x, y}` objects. `validate.py` prints an info line for each borrowed
  item, so an agent knows that a base edit by hand moves it there too. Hand edits are the agent's explicit choice; only
  phone edits pin.

### 5.3 Rotating, folding or resizing mid-edit
- **A drag in progress is cancelled** (§1.5), with no write.
- **Edit mode stays on** and now edits the layout that's active after the change. The edit-mode hint names it when it's
  not base, e.g. `Editing the 6 × 4 layout` (Pragma owns the wording). This tells the user why the phone won't follow.
- **An open `Move to…` or `Resize…` sheet closes without writing if the active layout changed.** Its row and column
  ranges belonged to the old grid. If only chrome changed (the same layout), it stays open.
- **Queued (debounced) writes** carry their target layout's entry `id` (or `base`). At replay they write to that
  layout, even if the window has moved on. If that entry is gone by then (an agent deleted it) or the target is taken,
  nothing is written and the existing `move_element_taken` snackbar shows.
- **Undo after a rotate:** the Undo snackbar survives the change. Its scan runs on the layout that's active **when Undo
  is tapped**.
- **Cells of items skipped on the active layout are drawn as reserved in edit mode** (a hatched cell, Pragma's design),
  so an empty-looking cell that refuses a drop explains itself.

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
    `checked on this phone`. It's derived from the device and the file at load, like Q-A12, never from the window.
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
| `windowOverrides` | `windowOverrides can have at most 16 entries.` |
| `windowOverrides[i].when.widthClass` / `.heightClass` | `%1$s must be compact, medium or expanded.` |
| `….when.orientation` | `%1$s must be portrait or landscape.` |
| `….when.deviceClass` | `%1$s must be phone or tablet.` |
| `….when.posture` | `%1$s must be flat, halfOpened, book or tabletop.` |
| `….when.minWidthDp` … | `%1$s must be a whole number from 0 to 10000.` / `minWidthDp is larger than maxWidthDp, so this entry never applies.` |
| `….orientationLock` | `%1$s must be portrait, landscape or system.` |
| `….orientationLock` (keyed wrong) | `%1$s can only be used when "when" is empty or has only deviceClass. Locking the screen changes orientation and size, so any other key would flip the rule.` |
| `….positions` without `grid` | `%1$s needs a grid in the same entry; positions belong to that grid.` |
| `….positions.<id>` | `x %2$d + w %3$d is outside this entry's grid (%4$d columns)` / `y … (%4$d rows)` (the existing wording, scoped to the entry) |
| `….positions.<id>` overlap | `overlaps %2$s on page "%3$s" in this entry's grid` |
| `….positions.<id>.page` | `page "%2$s" doesn't exist` |
| `….positions.<id>` (bad keys) | `hidden can't be combined with page, x, y, w or h` / `w and h are only for elements` |
| `….dock.edge` | `%1$s must be auto, bottom, top, start or end.` |
| `….cellCap.maxWidthDp` / `.maxAspect` | `%1$s must be a whole number from 64 to 480.` / `%1$s must be a number from 1 to 3.` |
| `….scale.icon` / `.labels` | `%1$s must be a number from 0.5 to 2.` / `%1$s must be auto, always or never.` |
| `….fold.halfOpened` / `.flat` | `%1$s must be auto, start, end, top, bottom or span.` / `%1$s must be asTablet or asPhone.` |

### 9.2 Phone warnings (`status.json` `warnings`, the Warnings list; no banner, no snackbar)
These are code, not `strings.xml`, as in §15.1. They're **all computed at load or reload from the file (Q-A12), never
from the window.** Each override entry is checked at load **as if** it were active: its grid, its `positions`, what
it borrows and what's skipped. So every device writes the same list, except the two marked "checked on this phone".
Each warning names the entry by index **and** `id`:

| Code | Text |
|---|---|
| `warn_override_hidden` (one per entry) | `windowOverrides[%1$d] ("%2$s"): %3$d items aren't shown on windows that use its %4$d × %5$d grid (%6$s), because they don't fit it. Give them positions there, or change the grid.` (`%6$s` = the first 3 ids, then `and %d more`). Entries marked `{"hidden": true}` are intentional and not counted |
| `warn_override_position_id` | `windowOverrides[%1$d] ("%2$s").positions: "%3$s" doesn't match any item, so it was ignored.` (kept, as with `pages[].overrides`) |
| `warn_override_level` | `windowOverrides[%1$d] ("%2$s") needs feature level %3$d, but this Config Launcher supports up to %4$d, so it was skipped. It stays in the file. Update the app to use it.` |
| `warn_override_when_value` | `windowOverrides[%1$d] ("%2$s").when.%3$s "%4$s" isn't supported by this Config Launcher, so this entry never applies. Update the app, or check for a typo.` |
| `warn_override_value` | `windowOverrides[%1$d] ("%2$s").%3$s "%4$s" isn't supported by this Config Launcher, so the default is used. Update the app, or check for a typo.` |
| `warn_override_unreachable` | `windowOverrides[%1$d] ("%2$s") only applies in split screen here, because the orientation lock from windowOverrides[%3$d] keeps the full screen %4$s.` |
| `warn_orientation_lock_ignored` (checked on this phone) | `windowOverrides[%1$d] ("%2$s"): this tablet ignores app orientation locks (Android 16 and later), so home rotates freely. Checked on this phone.` |
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
| **O4** grid layouts, read-only | `grid` + `positions` parse and validate; §5.1 placement (map → borrow → skip); `warn_override_hidden`, `_position_id`. **Edit mode is off** while an override layout is active (hint: `This layout is set in launcher.json`, wording by Pragma). `Move to…`/Add are disabled there | O1, O2; rotate between layouts, hash unchanged |
| **O5** edits on any layout | §5.2 writes, pins and `hidden` marks; Undo across layouts; queued writes keyed by entry `id`; the §5.3 rotate-mid-edit rules; reserved-cell hatching | New O9–O11 (below) |
| **O6** folds | `androidx.window`, the `posture` matcher, `fold.flat`, `fold.halfOpened`, `warn_override_fold_side` | O6 on foldable AVDs with posture simulation only |
| **O7** (only if asked) | The `posture: "folded"` heuristic (§8.3) | Foldable AVD closed vs a normal phone AVD |

New test-plan checks for Grace (the O-table in #185):
- **O9 (no cross-layout jump):** with base 4 × 5 and a `tablet-land` 6 × 4 layout, drag on the phone, then load on the
  tablet: tablet places unchanged; pins written. The reverse: tablet drag, phone unchanged.
- **O10:** a rotate mid-`Move to…` closes the sheet with no write; a rotate mid-drag cancels it.
- **O11:** Undo after a rotate scans the layout that's active when Undo is tapped.

## 11. Hard parts (flagged, not dropped)

| Hard part | Approach |
|---|---|
| Edits under several layouts without cross-device jumps | Per-layout `positions` plus pin-on-base-edit (§5). It's the largest slice (O5), so O4 ships override grids read-only first |
| Orientation lock on ≥ sw600 under target 36+ | The OS ignores it. Documented as phone-only, with the `warn_orientation_lock_ignored` load-time warning; no opt-out property (§6.3) |
| `posture: "folded"` | No public API. Deferred, with the hinge-sensor heuristic ready (§8.3); use `deviceClass: "phone"` |
| Fold behaviour in general | Unverified on hardware. Conservative defaults, every one overridable, emulator-only tests with a real `FoldingFeature` (§8) |
| `span` over an occluding hinge | Allowed and documented; content under the hinge may be hidden (§8.2) |
| The lock's first frame | Apply it in `onCreate` from the config in memory or last-good, before `setContent` (§6.1) |
| Class flapping near 600/840 dp while resizing | It can switch layouts, so items may appear or disappear while dragging a window edge. No writes; a drag is cancelled. Accepted |
| Agents editing base by hand | Borrowed places follow the hand edit (only phone edits pin). `validate.py` info lines list the borrowed items per entry |

## 12. Open questions for Christopher (recommended default in brackets)

None about folds: Archimedes picks those (§8).

1. **Adding an app on the tablet.** When your tablet uses its own grid (say 6 × 4) and you add an app there, it also
   needs a spot on your phone. Should it go in the phone's first free spot? [Yes, first free spot on the same page, then
   later pages. If the phone has no room at all, the add is refused with `No room on the phone layout`.]
2. **Removing on one device.** Removing an app from home on the tablet removes it from home everywhere, because there's
   one list of apps and only their places differ per layout. OK? [Yes. Hiding it on just one screen size is possible in
   the file (`"hidden": true`), with a phone button for it later if you want one.]
3. **Older app versions.** A phone running an older Config Launcher can't read a file that uses these overrides: it
   keeps its last layout and shows the "can't use launcher.json" error until it's updated. OK while we're
   pre-release? [Yes. Update every device before adding overrides, as with `theme`.]
4. **Rotation lock on tablets.** From Android 16, Android ignores rotation locks from apps on tablets once the app is
   updated for it. The lock will be reliable on phones only. OK? [Yes. The tablet gets a warning in the Warnings list
   when it's ignoring your lock.]
5. **Editing a custom-grid screen, for one release.** The first release with custom grids (O4) shows them but won't let
   you drag on them; edits come in the next one (O5). OK, or hold the whole thing until editing is ready? [Ship O4 first.]
6. **Icon size range.** Allow 0.5× to 2× the normal icon (28 to 112 dp), with tap areas never below 48 dp? [Yes.]

## 13. Follow-ups once accepted (not now)
- **Pragma (ux.md §1.5, Q-A11):**
  - replace "Placement on a window uses that window's grid … the phone then skips that item" and "valid as long as each
    position fits the largest grid" with §5 here;
  - add copy for the edit-mode layout hint, `No room on the phone layout`, the read-only hint (O4) and the reserved-cell hatch;
  - add the §9.2 strings to the §15.1-style string block.
- **Grace (test-plan):** O9–O11, and the emulator-only fold note on O6.
- **Archimedes:**
  - ARCHITECTURE §19 entry;
  - the schema and `validate.py` text in O1;
  - the `net.fetch` → level 6 renumbering in ux.md §17, the schema descriptions and dynamic-widgets.md.
