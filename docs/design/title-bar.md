# Design: config-driven title bar (#79)

Status: proposal, 2026-10-18. Author: Archimedes. Docs only: no change to `schema/` or app code until Sprite's #27–#31 rework has landed and this is accepted. Not a v1 gate (Christopher: wanted "eventually").

Issue #79: the home title bar becomes an optional element defined in `launcher.json`, with on/off, position, contents (page name, clock, date…) and styling (font size, colour, backdrop, alignment). It should fit the future widgets work.

## 1. Decisions in brief
- The title bar is the **first non-app element**, built on a general **element model** (`id`, `type`, `anchor` or grid `cell`, `style`) that widgets reuse later (§5).
- **Schema version 2, not an additive v1 change** (§2). The migration from v1 is mechanical.
- **Global by default, with per-page overrides** (§3).
- **Content is a short template** with a fixed token set (`{page}`, `{time}`, `{date}`, `{weekday}`). An agent-writable status line comes later, from a separate file (§4).
- **Styling is a closed, validated subset**: size and weight enums, alignment, colour tokens or `#RRGGBB` with light/dark variants, and a backdrop enum. No free-form fonts, pixel sizes or alpha (§6).
- **Not draggable in v1.** The phone can only toggle it and edit the text; the launcher round-trips it untouched otherwise (§8).

## 2. Additive v1 change or a version bump? → bump to `schemaVersion: 2`
The v1 schema has `additionalProperties: false` at the root and on pages, by design (§3 of ARCHITECTURE: unknown non-`x-` keys are errors so typos fail loudly). So a file with a `titleBar` key is **invalid to every existing v1 launcher**. ARCHITECTURE §3 rule 1 only allows additive fields without a bump "if older launchers would still render correctly", and here they wouldn't render at all: they'd keep last-good and show B2 "1 problem: unknown key titleBar", which looks like the user's mistake.

With a bump, an older launcher shows the intended "This file needs a newer Config Launcher" banner (§3 rule 4), which is the correct message. Other reasons:
- v2 can also **remove the deprecated `unsorted`** block (AF-2, §18.5), which already needs v2.
- The migration is trivial and lossless: `migrate1to2` = drop `unsorted`; no title bar is added (absent = off, same as v1 behaviour).
- Per §3 rule 3, the launcher doesn't rewrite a v1 file as v2 until the user edits or taps *Upgrade now* (with a backup), so git users aren't surprised.

Alternative considered: put the title bar under an `x-titleBar` key in v1. Rejected: `x-` keys are user/agent notes the launcher promises never to interpret.

Widgets come later as **v3** with the same element model, so the version bumps stay small and each has one purpose.

## 3. Placement
- **Global:** a root `titleBar` object applies to every page.
- **Per page:** `pages[].titleBar` either overrides selected fields of the global one (`text`, `style`, `enabled`) or is `false` to hide it on that page. A page override with no global bar defines a bar for that page only.
- **Anchor:** `top` only in v2 (below the status bar, above the grid). `bottom` (above the dock) is a later option, listed for Pragma (Q-T2).
- **Space:** the bar takes a fixed height from its size (one line), and the grid's rows share the remaining height (ux.md §3 cell sizing). It never overlaps icons. A missing bar gives the space back.

## 4. Content model
`text` is a template string, at most 120 characters after token expansion, one line, ellipsized at the end.

| Token | Renders | Notes |
|---|---|---|
| `{page}` | The page's `title`, or "Page n" if none | Updates on page swipe. |
| `{time}` | Current time, system 12/24 h setting | Ticks once per minute (`ACTION_TIME_TICK`), only while home is visible. |
| `{date}` | Medium date in the device locale (`Oct 18`) | `{date:short}` / `{date:long}` variants. |
| `{weekday}` | Locale weekday name | |
| `{{` `}}` | Literal braces | |

Unknown tokens are a validation error (not rendered literally), so typos are caught. Default when `text` is omitted: `"{page}"`.

**Agent-writable status text (later, not v2-MVP):** a `{status}` token reading one plain-text line from `title-status.txt` at the folder root (≤ 120 chars, first line only, never executed or parsed as markup). It's a separate file so an agent can update a status line every few minutes without rewriting `launcher.json`, which would churn git history and trigger merges with phone edits. Polled with the same mechanism as `launcher.json`. Not available in app-only mode.

Not in scope: weather, battery, notifications, next calendar event (they'd need permissions or network, which the project rules out), arbitrary URLs or intents (nothing in the file is executed).

## 5. Element model (shared with widgets later)
Every non-app home element has the same three blocks, so the validator, codec, conflict replay and edit UI treat them uniformly:

| Block | Title bar (v2) | Widget (v3, later) |
|---|---|---|
| `id` | Same ID pattern and file-wide uniqueness as items | Same |
| `type` | `"title"` | `"widget"` |
| Placement | `anchor: "top"` (outside the grid) | `x`, `y`, `w`, `h` on a page grid (inside it) |
| `style` | The shared style block (§6) | The same block (backdrop and corner radius apply to the widget frame; text fields are ignored) |
| Type-specific | `text`, `enabled` | `provider` (component), `size` hints; the AppWidget ID stays on the phone, never in the file |

Decision: **an element is an object with `id`, `type`, a placement and an optional `style`**. Anchored elements (title bar) live in named root/page slots; grid elements (widgets) live in `pages[].items[]` beside apps. Replay ops already work on IDs, so `Move`, `Remove` and a new `SetStyle(id, style)` op apply to any element. The `style` block is defined once in the schema (`$defs/style`) and referenced by each element type.

## 6. Styling (closed subset)
```json
"style": {
  "size": "large",
  "weight": "medium",
  "align": "start",
  "color": { "light": "#202124", "dark": "onWallpaper" },
  "backdrop": "scrim",
  "shadow": true
}
```
| Field | Values | Default |
|---|---|---|
| `size` | `small` 14 sp, `medium` 18 sp, `large` 24 sp, `xlarge` 32 sp | `medium` |
| `weight` | `regular`, `medium`, `bold` | `medium` |
| `align` | `start`, `center`, `end` (mirrored in RTL) | `start` |
| `color` | A token (`onWallpaper`, `primary`, `secondary`, `onSurface`) or `#RRGGBB`, either as one value or `{ "light": …, "dark": … }` | `onWallpaper` |
| `backdrop` | `none`, `scrim` (40 % black, same as the label backdrop, Q-C4), `surface` (Material surface colour, follows theme) | `none` |
| `shadow` | boolean (1 dp 50 % black, same as home labels) | `true` |

- **No alpha, fonts, pixel sizes, padding or arbitrary CSS.** Each value maps to one Compose parameter, which keeps rendering predictable and the attack surface nil.
- **Theme and dark mode:** tokens follow the system light/dark theme. A single hex value is used in both; the `{light, dark}` form lets agents pick per theme. `onWallpaper` is white with the shadow, which is the safest choice on unknown wallpapers.
- **Contrast:** the wallpaper is unknown, so contrast can't be validated against it. With `backdrop: surface` the launcher computes the contrast of the text colour against the surface and, below 4.5:1, renders with `onSurface` instead and adds a warning to `status.json` (`titleBar.style.color: contrast 2.1:1 on surface, using onSurface`). With `scrim`, the same check runs against the scrim. With `none`, a hex colour other than a light colour gets a warning (not an error) suggesting a backdrop.
- **Font scale:** sizes are in sp, so the system font size applies, capped at 1.5× so the bar stays one line.

## 7. Proposed schema (v2 draft, not in `schema/` yet)
Root:
```json
"titleBar": { "$ref": "#/$defs/titleBar" }
```
Page:
```json
"titleBar": { "oneOf": [ { "const": false }, { "$ref": "#/$defs/titleBarOverride" } ] }
```
Definitions:
```json
"style": {
  "type": "object", "additionalProperties": false,
  "properties": {
    "size":   { "enum": ["small", "medium", "large", "xlarge"] },
    "weight": { "enum": ["regular", "medium", "bold"] },
    "align":  { "enum": ["start", "center", "end"] },
    "color":  { "oneOf": [ { "$ref": "#/$defs/colorValue" },
                           { "type": "object", "additionalProperties": false, "minProperties": 1,
                             "properties": { "light": { "$ref": "#/$defs/colorValue" }, "dark": { "$ref": "#/$defs/colorValue" } } } ] },
    "backdrop": { "enum": ["none", "scrim", "surface"] },
    "shadow": { "type": "boolean" }
  }
},
"colorValue": { "oneOf": [ { "enum": ["onWallpaper", "primary", "secondary", "onSurface"] },
                           { "type": "string", "pattern": "^#[0-9A-Fa-f]{6}$" } ] },
"titleBar": {
  "type": "object", "required": ["id", "type"], "additionalProperties": false,
  "patternProperties": { "^x-": true },
  "properties": {
    "id": { "$ref": "#/$defs/id" },
    "type": { "const": "title" },
    "enabled": { "type": "boolean", "default": true },
    "anchor": { "const": "top", "default": "top" },
    "text": { "type": "string", "maxLength": 120, "default": "{page}" },
    "style": { "$ref": "#/$defs/style" }
  }
},
"titleBarOverride": {
  "type": "object", "additionalProperties": false, "minProperties": 1,
  "patternProperties": { "^x-": true },
  "properties": {
    "id": { "$ref": "#/$defs/id" },
    "enabled": { "type": "boolean" },
    "text": { "type": "string", "maxLength": 120 },
    "style": { "$ref": "#/$defs/style" }
  }
}
```
Example:
```json
{
  "schemaVersion": 2,
  "titleBar": { "id": "t-main", "type": "title", "text": "{weekday}, {date} · {time}",
                "style": { "size": "large", "align": "center", "backdrop": "scrim" } },
  "pages": [
    { "id": "home", "title": "Home", "items": [] },
    { "id": "work", "title": "Work", "titleBar": { "text": "{page}" }, "items": [] },
    { "id": "games", "titleBar": false, "items": [] }
  ]
}
```
Canonical key order: `id, type, enabled, anchor, text, style`, then `x-` keys; inside `style`: `size, weight, align, color, backdrop, shadow`. Defaults are omitted when the phone writes.

## 8. Validation, limits and write-back
- **Kotlin-only rules** (not expressible in the schema): tokens must be known; expanded text ≤ 120 chars is a render-time ellipsis, not an error; a per-page override needs a global bar or must set `enabled: true` with its own `text`; title IDs are unique file-wide alongside item IDs.
- **Limits:** one global bar, one override per page (20 pages), template ≤ 120 chars, ≤ 8 tokens. These add well under 10 KB to the 2 MiB budget (§17 Q16).
- **Write-back:** not draggable in v1. Phone-side edits are limited to long-press on the bar → `Edit title bar` (Pragma, Q-T4) with a text field and an on/off toggle, and to page rename (which changes what `{page}` shows). Each is a normal edit op (`SetTitleBar(id, fields)`) with the usual debounce, hash check and replay. A missing global bar is never created by the launcher on its own.
- **Conflicts:** replayed by ID like items. If the agent removed the bar and the phone edited it, the edit is skipped (existing rule).
- **Error UX:** title-bar errors are ordinary B2 problems with paths (`titleBar.text: unknown token {wether}`), and last-good stays.

## 9. Performance and accessibility
- One `Text` composable per visible page; time tokens recompose once a minute, driven by a single shared ticker that stops when home isn't visible. No measurable battery cost beyond the existing poll.
- The bar's height is resolved once per configuration change, so swiping pages doesn't relayout the grid.
- Accessibility: semantics `heading()`; TalkBack reads the expanded text (dates and times in spoken form). It's not a live region, so the clock doesn't announce every minute. Contrast handled as in §6; font scale respected up to 1.5×. Touch target ≥ 48 dp when it's tappable (long-press edit).

## 10. MVP versus later
**MVP of this feature (schema v2):** global bar plus per-page override or hide, `top` anchor, the template with `{page}`, `{time}`, `{date}`, `{weekday}`, the style subset, contrast fallback with a `status.json` warning, toggle and text edit from the phone.

**Later:** `bottom` anchor, `{status}` from `title-status.txt`, tap actions (open the drawer, a page jump; never intents), a second line, more tokens (next alarm via `AlarmManager.getNextAlarmClock`, which needs no permission), style editing on the phone, widgets (v3) on the same element model.

## 11. Open UX questions for Pragma
- **Q-T1** Default look when a user first turns it on: `"{page}"` start-aligned medium, or a clock/date centred large?
- **Q-T2** Is a `bottom` anchor (above the dock) worth offering later, or is top-only enough?
- **Q-T3** Where is the on/off toggle: Settings → Home, the bar's long-press menu, or both?
- **Q-T4** Phone editing scope in the first release: text and on/off only (proposed), or also size and alignment pickers?
- **Q-T5** Should tapping the bar do anything (open the drawer, page overview), or only long-press to edit?
- **Q-T6** How does the bar interact with the label backdrop setting and the status bar: same scrim, one combined scrim, or independent?
- **Q-T7** Behaviour on the wall-display devices and tablets in landscape: same single line, or a larger default size?
- **Q-T8** Copy for the contrast fallback (in the details view and status.json) and for the "needs a newer Config Launcher" banner when a v2 file meets a v1 launcher.

## 12. Follow-ups once accepted (not now)
- **Archimedes:** `schema/launcher-config.v2.schema.json`, examples, and the ARCHITECTURE §3 migration entry.
- **Sprite:** model (`TitleBar`, `Style`), codec key order, Kotlin validator rules, `migrate1to2`, the shared ticker, `SetTitleBar` op and replay, `scripts/validate.py` support for v2.
- **Grace:** fixtures for valid and invalid title bars (unknown token, bad hex, override without global), v1-launcher-meets-v2-file, contrast fallback, TalkBack and font-scale checks.
