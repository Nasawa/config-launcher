# Design: config-driven title bar as the first element (#79)

Status: proposal, revised 2026-10-18 after Pragma's UX proposal (ux.md §15, PR #80) and Christopher's clarification. Author: Archimedes. **Design only, docs only:** nothing changes in `schema/` or app code until Sprite's #27–#31 rework has landed and this is accepted. Not a v1 gate.

Christopher's framing (#79): the title bar is "an optional widget that can be styled and customized as desired". Pragma's #80 (three slots holding page title, clock, date or nothing; off by default; enum sizes and colours) is therefore **the default preset, not the ceiling**. This design gives real styling room and maps Pragma's slots onto it.

## 1. Decisions in brief
1. The title bar is **the first instance of a general element model** (`id`, `type`, placement, `content`, `style`) that widgets reuse later (§3).
2. It lives in a new root **`elements[]`** array, with optional **per-page overrides** keyed by element ID (§4, Pragma Q1).
3. **Stay on `schemaVersion: 1`.** Add the `elements` container and two forward-compatibility rules to v1 *before* the title bar ships: unknown element *types* are skipped with a warning, unknown keys inside known types are errors, and an element can declare `requires` (a launcher feature level) so newer features degrade to "skipped with a warning" on older launchers (§6, Pragma Q2). This replaces the earlier draft of this doc, which proposed v2.
4. **Content** is per-slot template strings with a strict token grammar: `{page}`, `{time:HH:mm}`, `{date:EEE d MMM}` and a few more, no code, length-capped (§5). Pragma's `pageTitle` / `clock` / `date` are shorthands for `{page}` / `{time}` / `{date}`.
5. **Style** is a validated, bounded set: hex or theme-token colours with light/dark variants, size in sp within bounds, weight, per-slot alignment, padding, and a backdrop that defaults to `auto` (follows the *Label backdrop* setting, Pragma Q3) but can be set in the file. Poor contrast is a **warning, never a rejection** (§7).
6. Off by default; not draggable in v1; the phone edits only on/off and the preset (§9).

## 2. Answers to Pragma's questions (#80)
- **Q1: Top-level only, or per-page overrides?** Top-level is the default and the only thing the phone UI edits (Pragma's preference holds for UX). The file may additionally carry an optional per-page override block, `pages[].overrides["<elementId>"]`, with any subset of `enabled`, `content` and `style`. It costs little (same validator and merge code) and lets agents do things like hide the clock on a "Focus" page. No phone UI for overrides in the first release.
- **Q2: Does `titleBar` need `schemaVersion` 2?** No, if we add the forward-compatible `elements` container to v1 first. Reasoning both ways:
  - *New launcher reading an old file:* there's no `elements` key, so the title bar is absent and the page shows today's §1.3 page label. Nothing to migrate, so this direction never needs a bump.
  - *Old launcher reading a new file:* a launcher built before the `elements` key exists rejects the file (unknown root key, B2, keeps last-good). A version bump wouldn't help that launcher render it either; it would only change the banner text to "needs a newer Config Launcher". The cost of a bump (migration code, a rewrite of every user's file, a git-visible diff) buys nothing in this direction.
  - *So:* ship the `elements` container and the skip/`requires` rules in a v1 launcher release **before** any element type is used. Launchers from that release on tolerate every future element type and feature (skipped with a warning, not rejected). Only pre-release builds older than that reject, and only test devices run those. `schemaVersion` 2 stays reserved for genuinely breaking changes, such as removing `unsorted` (AF-2).
- **Q3: Backdrop follows the app setting or set in the file?** Default `"auto"` follows the device's *Label backdrop* setting (Pragma's preference), because whether labels need a scrim depends on that phone's wallpaper, not on the shared layout. The file may override it with an explicit backdrop (`none`, `scrim`, or a custom colour/opacity/radius), and an explicit value wins over the setting. The phone never writes anything except `auto` unless the user picks a style in a later release.

## 3. Element model
Every non-app element shares these blocks, so validation, the canonical codec, ID-based replay and the edit UI treat them uniformly.

| Block | Title bar (first element) | Widget (later) |
|---|---|---|
| `id` | Item ID pattern, unique file-wide | Same |
| `type` | `"titleBar"` | `"appWidget"` |
| `enabled` | boolean, default `true` when present | Same |
| `requires` | optional integer feature level (§6) | Same |
| Placement | `anchor: "top"` (outside the grid) | On a page: `page` + `x`, `y`, `w`, `h` (inside the grid) |
| `content` | `slots { start, center, end }` of templates | Provider component and size hints; the AppWidget ID stays on the phone, never in the file |
| `style` | Shared `$defs/elementStyle` | Same block; text fields don't apply, `padding` and `backdrop` frame the widget |

Decision: **root `elements[]` holds every element; an element is either anchored (`anchor`) or grid-placed (`page`, `x`, `y`, `w`, `h`).** Grid-placed elements take their cells into account in the "no two things in one cell" rule. Replay ops (`Move`, `Remove`, new `SetElement(id, fields)`) already work by ID. Keeping elements out of `pages[].items[]` means old item-handling code never sees a non-app type.

**Pragma's shape maps one-to-one:**
| Pragma (#80) | This design |
|---|---|
| `titleBar.enabled` | the element's `enabled` |
| `slots.start/center/end: pageTitle \| clock \| date \| none` | `content.slots.*: "{page}" \| "{time}" \| "{date}"` or omitted. The bare names are also accepted as shorthands. |
| `size: small \| medium \| large` | `style.size`: the names map to 14 / 18 / 24 sp, or a number of sp within bounds |
| `color: light \| dark` | `style.color`: `"light"` / `"dark"` tokens (white / near-black), or hex, or `{light, dark}` |
| `backdrop: auto` | `style.backdrop: "auto"` (the default) |
| centre keeps ≥ 50 %, page title truncates first | the default layout rules (§8), unchanged |

## 4. Shape (proposal, not in `schema/` yet)
Pragma's default preset, as the phone would write it when the user turns the bar on:
```json
"elements": [
  { "id": "t-main", "type": "titleBar", "anchor": "top",
    "content": { "slots": { "center": "{page}" } } }
]
```
A customized bar:
```json
"elements": [
  { "id": "t-main", "type": "titleBar", "anchor": "top",
    "content": { "slots": { "start": "{date:EEE d MMM}", "center": "{page}", "end": "{time:HH:mm}" } },
    "style": {
      "size": 20, "weight": 600,
      "color": { "light": "#1B1B1F", "dark": "#F1F0F4" },
      "align": { "start": "start", "center": "center", "end": "end" },
      "padding": { "horizontal": 16, "vertical": 6 },
      "backdrop": { "color": "#000000", "opacity": 35, "radius": 12 }
    } }
],
"pages": [
  { "id": "focus", "title": "Focus", "items": [],
    "overrides": { "t-main": { "content": { "slots": { "end": "" } } } } },
  { "id": "games", "items": [], "overrides": { "t-main": { "enabled": false } } }
]
```
Draft JSON Schema fragments:
```json
"elements": { "type": "array", "maxItems": 32, "items": { "$ref": "#/$defs/element" } },

"element": {
  "type": "object", "required": ["id", "type"],
  "properties": { "id": { "$ref": "#/$defs/id" }, "type": { "type": "string", "pattern": "^[a-zA-Z][a-zA-Z0-9]{0,31}$" },
                  "requires": { "type": "integer", "minimum": 1 } },
  "if": { "properties": { "type": { "const": "titleBar" } } },
  "then": { "$ref": "#/$defs/titleBar" }
},

"titleBar": {
  "type": "object", "additionalProperties": false, "patternProperties": { "^x-": true },
  "properties": {
    "id": {}, "type": {}, "requires": {},
    "enabled": { "type": "boolean" },
    "anchor": { "const": "top" },
    "content": { "$ref": "#/$defs/titleBarContent" },
    "style": { "$ref": "#/$defs/elementStyle" }
  }
},
"titleBarContent": {
  "type": "object", "additionalProperties": false,
  "properties": { "slots": { "type": "object", "additionalProperties": false,
    "properties": { "start": { "$ref": "#/$defs/template" }, "center": { "$ref": "#/$defs/template" }, "end": { "$ref": "#/$defs/template" } } } }
},
"template": { "type": "string", "maxLength": 120 },

"elementStyle": {
  "type": "object", "additionalProperties": false,
  "properties": {
    "size":   { "oneOf": [ { "enum": ["small", "medium", "large"] }, { "type": "number", "minimum": 10, "maximum": 40 } ] },
    "weight": { "oneOf": [ { "enum": ["regular", "medium", "bold"] }, { "type": "integer", "enum": [300, 400, 500, 600, 700, 800] } ] },
    "color":  { "oneOf": [ { "$ref": "#/$defs/color" },
                { "type": "object", "additionalProperties": false, "minProperties": 1,
                  "properties": { "light": { "$ref": "#/$defs/color" }, "dark": { "$ref": "#/$defs/color" } } } ] },
    "align":  { "type": "object", "additionalProperties": false,
                "properties": { "start": { "$ref": "#/$defs/align" }, "center": { "$ref": "#/$defs/align" }, "end": { "$ref": "#/$defs/align" } } },
    "padding": { "type": "object", "additionalProperties": false,
                 "properties": { "horizontal": { "type": "integer", "minimum": 0, "maximum": 32 },
                                 "vertical": { "type": "integer", "minimum": 0, "maximum": 24 } } },
    "backdrop": { "oneOf": [ { "enum": ["auto", "none", "scrim"] },
                  { "type": "object", "additionalProperties": false, "required": ["color"],
                    "properties": { "color": { "$ref": "#/$defs/color" },
                                    "opacity": { "type": "integer", "minimum": 0, "maximum": 100 },
                                    "radius": { "type": "integer", "minimum": 0, "maximum": 24 } } } ] }
  }
},
"color": { "oneOf": [ { "enum": ["light", "dark", "onWallpaper", "primary", "secondary", "onSurface", "surface"] },
                      { "type": "string", "pattern": "^#[0-9A-Fa-f]{6}$" } ] },
"align": { "enum": ["start", "center", "end"] },

"pageOverrides": { "type": "object", "maxProperties": 32,
  "propertyNames": { "$ref": "#/$defs/id" },
  "additionalProperties": { "type": "object", "additionalProperties": false, "minProperties": 1,
    "properties": { "enabled": { "type": "boolean" }, "content": { "type": "object" }, "style": { "$ref": "#/$defs/elementStyle" } } } }
```
Note the `element` definition doesn't set `additionalProperties: false` itself: for an unknown `type` it accepts any keys, so the file stays valid and the launcher skips that element (§6). For a known type, the type's own definition is strict. Canonical key order: `id, type, requires, enabled, anchor, content, style`, then `x-` keys. Defaults are omitted when the phone writes.

## 5. Templates and the token grammar
Each slot is a template, at most 120 characters, one line.

Grammar (parsed by our own tokenizer, never `String.format` or an expression evaluator):
```
template := ( literal | "{{" | "}}" | token )*
token    := "{" name ( ":" pattern )? "}"
name     := "page" | "pageNumber" | "pageCount" | "time" | "date"
pattern  := 1..32 chars from: y M d E a H h k K m  space . , : / - ' and letters inside '...'
```
- `{page}`: the page `title`, or `Page n`. `{pageNumber}`, `{pageCount}`: integers.
- `{time}` with no pattern uses the system 12/24 h format; `{date}` alone is the locale medium date. With a pattern, the pattern is passed to `DateTimeFormatter.ofPattern(pattern, locale)` **only after** the allow-list check, so arbitrary formatter features can't be reached and it can't throw at render time.
- Seconds (`s`, `S`) and zone names (`z`, `V`, `O`) are not allowed: the bar ticks once a minute, and zones are out of scope.
- At most 8 tokens per template. `{{` and `}}` are literal braces.
- An unknown token name, a forbidden pattern letter, an unclosed brace or a pattern over 32 characters is a **validation error** (B2 with the path, e.g. `elements[0].content.slots.end: unknown token {wether}`). The expanded text isn't limited (dates vary by locale); it's ellipsized when rendered.
- The empty string `""` in an override means "nothing in this slot".
- **Later:** `{status}` reads the first line (≤ 120 chars) of `title-status.txt` at the folder root, so an agent can update a status line without rewriting `launcher.json` (which would churn git and cause merges). `{nextAlarm}` via `AlarmManager.getNextAlarmClock` needs no permission. Each new token raises the feature level (§6).

Not in scope, ever: weather, battery percentage, notifications or calendar (permissions or network), URLs, intents, scripts.

## 6. Compatibility mechanism (keeps schemaVersion 1)
1. **Container first.** One v1 launcher release adds `elements` and `pages[].overrides` to the v1 schema and implements the rules below, but no element type yet. That's the compatibility baseline.
2. **Unknown element type → skipped with a warning.** The element is kept verbatim on write-back (like an `x-` key), listed in `status.json` warnings (`elements[2]: type "appWidget" isn't supported by this launcher; skipped`), and not rendered. The file stays valid. A small notice in the error sheet; no banner.
3. **Unknown keys inside a known type → error** (B2), as today. Typos keep failing loudly.
4. **`requires` (feature level).** The launcher has a constant `FEATURE_LEVEL` (1 for the container release, 2 for the title bar, and so on, listed in ARCHITECTURE). An element with `requires` greater than the launcher's level is treated like an unknown type: skipped with a warning and kept verbatim, **before** strict key checking. The phone writes `requires` only when the element uses something newer than the level that introduced its type (e.g. a new token), and `scripts/validate.py` computes and checks it so agents don't have to.
5. **Root-level `minLauncherFeatureLevel`** was considered (fail the whole file if too old) and rejected: it turns an optional decoration into a reason to reject the whole layout.
6. Every element stays optional; no app placement ever depends on an element, so skipping one can't break the home screen.

## 7. Styling limits, theme and contrast
| Field | Bounds | Default |
|---|---|---|
| `size` | `small`/`medium`/`large` (14/18/24 sp) or 10–40 sp | `small` (Pragma: 14 sp) |
| `weight` | `regular`/`medium`/`bold` or 300–800 in steps of 100 | `regular` |
| `color` | token or `#RRGGBB` (no alpha), or `{light, dark}` | `light` (white with the label shadow) |
| `align` | per slot `start`/`center`/`end` (mirrored in RTL) | start/center/end per slot |
| `padding` | horizontal 0–32 dp, vertical 0–24 dp | 16 / 4 |
| `backdrop` | `auto` (follows *Label backdrop*), `none`, `scrim` (60 %, as #80), or `{color, opacity 0–100, radius 0–24}` | `auto` |

- **Dark mode:** a token follows the system theme; a single hex is used in both themes; the `{light, dark}` form picks per theme.
- **Shadow:** light text always gets the 1 dp label shadow (Pragma §15); dark text never does.
- **Contrast is a warning, never a rejection.** The wallpaper is unknown, so with `none` nothing can be measured. With a known backdrop (scrim or custom), the launcher computes text-versus-backdrop contrast. Below 4.5:1 it renders anyway, adds a `status.json` warning (`elements[0].style.color: contrast 2.1:1 on backdrop`), and shows it in the error sheet's warnings section. No automatic recolouring (the user asked for control).
- **Font scale:** sizes are sp, so the system font size applies, capped so the bar stays one line (max rendered size 40 sp × 1.3).

## 8. Layout, validation and write-back
- **Layout (Pragma §15, unchanged):** one line; `center` keeps at least 50 % of the width; side slots ellipsize first, then `center`; clock and date never truncate; the page title ellipsizes at the end. The bar's height comes from size + vertical padding, and grid rows share the rest (never overlaps icons). Off = today's §1.3 page label.
- **Kotlin-only rules:** template grammar, `requires` handling, override keys must name an existing element ID (otherwise a warning; the override is ignored and kept), one `titleBar` element per file in this release (a second is an error), element IDs unique with item IDs.
- **Limits:** 32 elements, 32 overrides per page, 120-char templates, 8 tokens per template, 32-char patterns. Negligible against the 2 MiB budget.
- **Write-back:** not draggable in v1. The phone edits only on/off and the preset (Pragma's slot picker writes `{page}`/`{time}`/`{date}`), via a `SetElement(id, fields)` op with the usual debounce, hash check and ID-based replay. Any style or template the phone doesn't understand is kept verbatim. The launcher never creates or deletes an element on its own.
- **Conflicts:** same as items: if the file removed the element, a phone edit to it is skipped.

## 9. Performance and accessibility
- One row composable; time and date tokens recompose from a single once-a-minute ticker (`ACTION_TIME_TICK`), active only while home is visible. Templates are parsed once per load, not per frame.
- Accessibility (Pragma §15): one focusable node per non-empty slot in reading order; `{page}` keeps the page-list button semantics and 48 dp target; clock and date read in spoken form; not a live region. Contrast warnings as above.

## 10. MVP versus later
**First release of the feature:** the container release (feature level 1: `elements`, `overrides`, skip rules), then the title bar (level 2): `top` anchor, three slots, tokens `{page}`, `{pageNumber}`, `{pageCount}`, `{time[:p]}`, `{date[:p]}`, the full style block, `auto` backdrop, contrast warnings, phone toggle and preset picker (Pragma's #80 UI).

**Later:** per-page override UI, style editing on the phone, `{status}` and `{nextAlarm}`, a `bottom` anchor, tap actions limited to in-launcher targets (drawer, page list), then `appWidget` elements on the same model (bind consent stays on the phone; the file only names the provider and cells).

## 11. Open UX questions for Pragma
Pragma's Q1–Q3 are answered in §2. New ones raised by this design:
- **Q-T1** The phone's slot picker offers Pragma's three choices. Should it also show "Custom (set in the file)" when a slot holds a template it can't represent, and keep it read-only?
- **Q-T2** Where do contrast warnings surface: only the error sheet's warnings section, or also a one-time snackbar?
- **Q-T3** Default `size` for the bar: 14 sp (matches the §1.3 page label) or 18 sp?
- **Q-T4** Should a skipped element (unknown type or `requires`) show anything on the phone besides the error-sheet note?
- **Q-T5** Copy for the token errors and the skip warning (`… isn't supported by this launcher; skipped`).

## 12. Follow-ups once accepted (not now)
- **Archimedes:** add the container and rules to `schema/launcher-config.v1.schema.json` (feature level 1), then the title bar (level 2), with examples; ARCHITECTURE §3 note on feature levels.
- **Sprite:** model (`Element`, `TitleBar`, `ElementStyle`, overrides), codec ordering and verbatim keep of unknown elements, validator (grammar, `requires`, override references), `FEATURE_LEVEL`, the ticker, `SetElement` op and replay, `scripts/validate.py` support including computing `requires`.
- **Pragma:** reconcile ux.md §15 with this mapping (slots as presets, `auto` backdrop default, warnings).
- **Grace:** fixtures for unknown element types (skipped, valid), unknown keys in a known type (invalid), `requires` above the level, token grammar edge cases, contrast warnings, override referencing a missing ID, TalkBack and font scale.
