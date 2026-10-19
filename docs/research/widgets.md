# Widgets: research and recommendation

> **Update (draft):** Christopher reframed the product around AI-composed **dynamic cards** ([docs/design/dynamic-widgets.md](../design/dynamic-widgets.md)). Sections that assumed first-party widgets were only `text`/`clock`, or that INTERNET must never land, are **partly superseded**. Stage 1 cell reservation, `appWidget` hosting, tap-to-bind, and phone-private widget IDs **still stand**. Design-doc §10 product Qs **Q-C1–C5, Q-C7 settled 2026-10-19** (incl. stage-5 INTERNET + HA v1 = companion widgets + agent JSON); see design §5 / §8.


Status: research and design proposal, 2026-10-19. Author: Archimedes (architecture). **Docs only: nothing in `schema/` or the app changes until this is accepted.** Builds on the element model in [docs/design/title-bar.md](../design/title-bar.md) (#81, ARCHITECTURE §19), where `appWidget` was a placeholder type, and on the spike's widget notes ([docs/research-spike.md](../research-spike.md)).

Christopher's framing: widgets are critical to the end vision and the first post-MVP item (ARCHITECTURE §18, row "§14 Q9"). Grok Bot's ask: survey existing schemas we could adapt before designing anything.

## 1. Summary
- **There is no schema we can adopt wholesale.** Nothing in the field describes "a home screen as a hand-editable file" with both third-party AppWidgets and declarative widgets. Every launcher stores widgets in a private database keyed by a device-local `appWidgetId`, and every declarative widget format is either proprietary (KWGT), bound to a server/runtime we don't have (Lovelace, Adaptive Cards), or a code API rather than a data format (Glance, WidgetKit).
- **But the field has converged on the same answers, and we should copy them:**
  1. **Size in grid cells with default/min/max**: Android's `targetCellWidth/Height` + `minResize*/maxResize*`, Lovelace's `grid_options {columns, rows, min_*, max_*}`, Launcher3/Nova's `spanX/spanY`.
  2. **Content and styling are separate**: Adaptive Cards' *HostConfig*, Lovelace themes. Our `style` block and `auto` defaults already follow this.
  3. **Typed element registry with graceful unknowns**: Lovelace `type:` plus `custom:` cards; Adaptive Cards' `fallback`. Our #81 skip rule plus `requires` is the same idea.
  4. **Device-local widget identity**: no launcher can restore third-party widget instances across devices. Nova's and Lawnchair's backups keep placement but not the widget ID or the provider's internal config. The file must name the **provider and cells, never the ID** (already ruled in #81).
- **Recommendation:** keep `schemaVersion` 1 and grow the #81 element model: grid-placed elements (`page`, `x`, `y`, `w`, `h`) reserve cells even when skipped. `appWidget` names a provider component and the phone keeps a private element-ID-to-widget-ID table. Bind consent always starts from a user tap, never from a file load. First-party widgets (`text`, `clock`) reuse the title bar's template grammar and style block. Staged plan in §6.

## 2. What we need (evaluation criteria)
- **(a) Host third-party AppWidgets** with `w`/`h` in cells on the existing grid (2–12 columns, pages, `x`/`y`).
- **(b) Custom, config-defined widgets** (clock, text, data-bound) with styling, written by hand or by an agent.
- **Two-way binding:** a phone edit (add, move, resize, reconfigure) writes back cleanly to `launcher.json`, with the existing debounce, hash guard, ID replay and merge. Bonus: whether the format lets a widget *write data* (toggle a value).
- **Complexity** for a Kotlin + Compose single-module launcher with no INTERNET, storage or QUERY_ALL_PACKAGES permission, and a hand-written validator mirrored in `scripts/validate.py`.

## 3. Candidates

### 3.1 Microsoft Adaptive Cards
JSON card schema (`TextBlock`, `Image`, `ColumnSet`, `Container`, `FactSet`, `Input.*`, `Action.*`) plus a separate **HostConfig** JSON that the host app uses to set fonts, spacing, colours and container styles [1][2]. Templating binds `${property}` expressions against a `$data` object [3]. `Input.Toggle` and the like, plus `Action.Execute` (Universal Action Model), send input values back to a bot, and `refresh` re-fetches the card [4][5]. Renderers must let the host register custom elements and remove built-in ones [2].
- **License:** source MIT. The Android binary on Maven Central (`io.adaptivecards:adaptivecards-android` 3.8.x, actively released through Aug 2026) is under a separate **Microsoft binary EULA**, not MIT [1][6]. It's View-based (appcompat, flexbox, ExoPlayer and more), not Compose.
- **(a) AppWidgets:** no. No concept of a grid, cells or embedding foreign UI.
- **(b) Config widgets:** strong as a *content* model (text, images, columns, facts, conditional `$when`). Weak on home-screen needs: no clock/time ticking, no layout-on-a-wallpaper semantics.
- **Two-way:** inputs and actions assume a server (bot) endpoint. A launcher with no network would have to reinterpret every action.
- **Complexity:** high if we ship the renderer (EULA, View interop, large dependency tree, unused features). Medium if we only borrow the shape.
- **Take:** borrow **HostConfig** (our theme defaults and `auto` styling) and the **element naming** (`TextBlock`-like text, `Container`/`ColumnSet`-like stacks) as inspiration. Don't adopt the schema or the renderer.

### 3.2 Home Assistant Lovelace dashboards and cards
Dashboards are YAML/JSON (stored in `.storage/lovelace*`, or YAML mode). A view holds cards, each `{type: <card>, ...config}`. Third-party cards use `type: custom:<name>` and register a web component [7][8]. The *sections* view (default since 2024.11) is a **12-column grid**: each card declares `grid_options {columns, rows, min_columns, max_columns, min_rows, max_rows}`, rows are 56 px with an 8 px gap, `rows: "auto"` follows content, and the UI resizes cards by dragging within those bounds [7][9][10]. Styling comes from themes (CSS variables), plus the community `card-mod`. Cards are bound to entities (`entity: light.kitchen`) and tapping calls services (`tap_action`), so they are **genuinely two-way**, but through the HA server.
- **License:** HA frontend Apache-2.0. The HA Android companion app is also Apache-2.0 and already ships Android AppWidgets: entity state, button, media, camera and a **Template widget** that renders a Jinja template [11].
- **(a) AppWidgets:** the closest conceptual fit for *sizing*: default plus min/max cells is exactly what we need. It has no AppWidget hosting itself.
- **(b) Config widgets:** excellent model (typed cards, entity binding, actions, conditional cards), but it needs a live HA connection — a **built-in HA client + auth token remain non-goals**. (Generic APK `INTERNET` for later `net.fetch` is decided in the design doc for stage 5; that is not an HA client.)
- **Two-way:** best in class, via HA services.
- **Complexity:** the sizing vocabulary is trivial to adopt. The card runtime isn't portable (web components).
- **Take:** **adopt the `grid_options` vocabulary and semantics** (default size and min/max bounds in cells; "auto" height is a later option). For HA data on the phone, **host the HA companion app's own AppWidgets** (Stage 3 gives this for free, including the Template widget) instead of building an HA client into the launcher (confirmed design-doc Q-C5). Agent-written local JSON is the v1 path for custom HA-shaped cards; no tight HA coupling.

### 3.3 KWGT / KLWP presets (Kustom)
A `.kwgt`/`.klwp` file is a ZIP with `preset.json` plus fonts and bitmaps [12][13]. The JSON is a tree of modules (`TextModule`, `ShapeModule`, `ProgressModule`, `StackLayerModule`, `OverlapLayerModule`, with `viewgroup_items`). Properties are absolute dp-ish numbers (`position_offset_x`, `text_size`), and any property can be replaced by a **formula** in `$…$` syntax (`$df(MM/dd)$`, `$if(…)$`, `$gv(global)$`) [14]. KWGT itself is an AppWidget provider: the user places a KWGT widget and loads a preset into it.
- **License:** proprietary, and the format is undocumented (reverse-engineered by the community; repacking even requires specific ZIP metadata [15]).
- **(a) AppWidgets:** not applicable, though a KWGT widget is just another third-party AppWidget we'll host in Stage 3.
- **(b) Config widgets:** the most expressive format in this survey: layers, shapes, progress, formulas, globals. It's also the warning sign: an expression language plus absolute positioning is a big surface to validate, hard for agents to edit safely, and hard to make accessible.
- **Two-way:** none. Formulas read data; the editor writes the file.
- **Complexity:** very high.
- **Take:** **don't adopt.** Learn two things: (1) *globals* (named values reused across a preset) map to our future data-source tokens, and (2) a constrained, allow-listed template grammar (our title-bar tokens) beats a general formula language. Users who want KWGT-level art can keep using KWGT through hosting.

### 3.4 Android AppWidgetProviderInfo, AppWidgetHost and Jetpack Glance
The platform contract we must implement for (a) regardless of anything else [16][17][18]:
- **Sizing (provider side):** `minWidth/minHeight` (dp) and, from Android 12, `targetCellWidth/targetCellHeight` (default size **in cells**). `minResizeWidth/Height` and `maxResizeWidth/Height` give the resize bounds in dp, `resizeMode` allows horizontal and/or vertical resizing, and `widgetFeatures` adds `reconfigurable` and `configuration_optional` (Android 12+) [16][17][19].
- **Sizing (host side):** the host reports the real size back with `AppWidgetHostView.updateAppWidgetSize` or `OPTION_APPWIDGET_SIZES` so responsive RemoteViews pick the right layout [17].
- **Binding:** `allocateAppWidgetId`, then `bindAppWidgetIdIfAllowed(id, profile, provider, options)`. If that returns false, launch `ACTION_APPWIDGET_BIND` with `EXTRA_APPWIDGET_ID`, `EXTRA_APPWIDGET_PROVIDER` and `EXTRA_APPWIDGET_PROVIDER_PROFILE`. The system dialog offers a one-off allow or an "always allow" for this launcher [18][20]. Then, if the provider has a configure activity and it isn't optional, `AppWidgetHost.startAppWidgetConfigureActivityForResult` [18].
- **Lifecycle:** `startListening` and `stopListening` with home visibility; `onAppWidgetRemoved` (API 30) when the system drops one; `deleteAppWidgetId` when the user removes one; `getAppWidgetIds` to garbage-collect orphans [21].
- **Restore:** after a system backup restore, the host gets `ACTION_APPWIDGET_HOST_RESTORED` with parallel `EXTRA_APPWIDGET_OLD_IDS` and `EXTRA_APPWIDGET_IDS` arrays and must remap its stored IDs. Launcher3's `AppWidgetsRestoredReceiver` is the reference implementation [22][23]. Providers get `OPTION_APPWIDGET_RESTORE_COMPLETED` [22].
- **Glance** (androidx, Apache-2.0) is the *provider-side* Compose-style API that compiles to RemoteViews, with a limited composable set and `SizeMode.Single/Exact/Responsive` [24][25]. It doesn't help a *host*. It matters later if we ever ship our own widgets for *other* launchers, and its `Responsive` size buckets are a good model for how our first-party widgets choose a layout per size.
- **License:** AOSP Apache-2.0.
- **(a):** this *is* (a). **(b):** n/a (Glance is for providers). **Two-way:** move and resize write-back are ours to define. Provider config lives inside the provider app, keyed by widget ID, and is opaque to us.
- **Complexity:** medium. Hosting `AppWidgetHostView` in Compose works through `AndroidView`. Kvaesitso (GPL-3, Compose) does exactly this and is our read-only reference, per the spike: read it, don't copy it [26].
- **Take:** **adopt the platform vocabulary** for provider metadata at runtime: cell defaults from `targetCell*`, falling back to `minWidth/minHeight` rounded up; resize bounds; resize mode; the reconfigurable flag. **None of it goes in the file except the chosen `w`/`h`.**

### 3.5 iOS WidgetKit
Widgets declare **families** (`systemSmall`, `systemMedium`, `systemLarge`, `systemExtraLarge`, the `accessory*` lock-screen families) via `supportedFamilies`. Content comes from a `TimelineProvider` that returns dated entries, so the OS renders snapshots without running the app, and `AppIntentConfiguration` gives typed user configuration [27][28][29].
- **License:** proprietary Apple API (a design reference only).
- **(a):** n/a. **(b):** two good ideas. **Fixed size families** instead of free sizes, which keeps layouts designable. **Timeline entries** (precomputed future states, e.g. clock or agenda) keep rendering cheap, matching our once-a-minute ticker.
- **Two-way:** interactive widgets (iOS 17) run App Intents. Not relevant without an app runtime.
- **Take:** for first-party widgets, **declare a few supported sizes per type** (like families / Glance `Responsive`) rather than letting every type stretch to anything. Our ticker plus template approach already is a tiny timeline.

### 3.6 Lawnchair, Neo Launcher and Smart Launcher (backup and layout formats)
- **Lawnchair:** Launcher3 fork, its own code GPL-3.0 on an Apache-2.0 base [30]. Layout lives in Launcher3's SQLite `favorites` table. A `.lawnchairbackup` contains the layout DB plus preferences, and its docs say widget *placement* is restored but **widget data and configuration aren't**; users must reconfigure widgets after restore [31][32].
- **Neo Launcher:** GPL-3.0-or-later Launcher3 fork (formerly Omega) with the same DB-based backup approach [33].
- **Smart Launcher:** proprietary. Backups are opaque files under `/sdcard/Documents/Smart Launcher Backups` [34]. Nothing to adopt.
- **Nova Launcher** (proprietary): `.novabackup` is a ZIP with `nova.db` (SQLite) and prefs. Its `favorites` rows carry `container, screen, cellX, cellY, spanX, spanY, itemType, appWidgetId, appWidgetProvider, profileId, options, restored`. Converters to Lawnchair reset `appWidgetId` to -1, because it's meaningless on another install [35][36].
- **(a):** these confirm the minimal *portable* widget record: **provider component, profile, cell position, span**. Plus a device-local ID and a "restored / needs rebind" state.
- **(b):** none of them define declarative widgets.
- **Two-way / complexity:** DB formats aren't hand-editable. That gap is the reason this project exists (spike §1).
- **Take:** **adopt the record shape** (`provider`, `profile`, `x`, `y`, `w`, `h`) and the **"pending / needs setup" placeholder state** that Launcher3 shows for restored-but-unbound widgets.

### 3.7 Others considered
- **Launcher3 (AOSP, Apache-2.0):** besides the restore receiver [23], its *pending widget* view ("tap to set up") and widget-resize frame are the UX reference for placeholders and resize handles [37].
- **Kvaesitso (GPL-3):** Compose launcher with widgets in a vertical list and built-in widgets (clock, weather, calendar, music). A good reference for Compose hosting and for a small first-party set; its config lives in the app, not a file [26].
- **Niagara (proprietary):** list-based, with very few widgets. A reminder that a launcher can choose a few high-quality widgets over a widget framework.
- **Material You theming:** dynamic colour (`android.R.color.system_accent1_*`, Android 12+) is applied by *providers* to their own widgets. Our first-party widgets should support a `"dynamic"` colour token later, alongside the existing `light`/`dark`/hex tokens.
- **RemoteViews limits:** third-party widgets are RemoteViews. We can't restyle them (no font, colour or corner changes inside). The host can only frame them: padding, backdrop, and corner clipping (Android 12 `system_app_widget_background_radius`). So `style` on an `appWidget` is limited to the frame, as #81 §3 already says.
- **Openlauncher issue #573:** "lots of pop-ups asking to allow widget creation", a real-world example of what happens if bind prompts fire automatically [38]. That informs the consent rule in §5.3.

## 4. Comparison
| Candidate | License | (a) Host AppWidgets on a grid | (b) Config-defined widgets | Two-way friendliness | Complexity for us | Verdict |
|---|---|---|---|---|---|---|
| Adaptive Cards | MIT source; Android binary under MS EULA | None | Good content model, no clock or home semantics | Inputs/actions need a server | High (renderer) / low (ideas) | Borrow HostConfig idea and element names |
| HA Lovelace | Apache-2.0 | Best *sizing* vocabulary (`grid_options`) | Excellent but needs a live HA server | Excellent via HA services | Low for vocabulary; runtime not portable | **Adopt sizing semantics**; host HA's own AppWidgets |
| KWGT/KLWP | Proprietary, reverse-engineered | n/a (is itself a provider) | Most expressive, formula language | None | Very high | Don't adopt; keep tokens allow-listed |
| AppWidgetProviderInfo / Host | Apache-2.0 (AOSP) | **Required platform contract** | n/a | Placement is ours; provider config is opaque | Medium | **Adopt** (runtime metadata, bind flow, restore broadcast) |
| Jetpack Glance | Apache-2.0 | Provider-side only | Model for size buckets | n/a | Low as reference | Borrow `Responsive` size idea |
| iOS WidgetKit | Proprietary API | n/a | Families and timelines | Intents (n/a) | Reference only | Borrow "few supported sizes" |
| Lawnchair / Neo | GPL-3.0 (on Apache-2.0) | Record shape: span + provider | None | DB, not hand-editable | n/a | Adopt record shape and pending state |
| Smart Launcher | Proprietary | Opaque | None | n/a | n/a | Nothing |
| Nova backup | Proprietary, reverse-engineered | Record shape (`spanX/spanY`, `appWidgetProvider`) | None | DB | n/a | Confirms the ID is device-local |
| Launcher3 / Kvaesitso | Apache-2.0 / GPL-3 | Reference implementations | Kvaesitso has a small built-in set | n/a | Reading only | Read, don't copy GPL code |

## 5. Recommendation

### 5.1 How the element model and grid grow
Keep #81's decision: **widgets are elements in root `elements[]`, not `pages[].items[]`.** App, shortcut and folder items stay 1x1 (ARCHITECTURE §3's "w/h arrive with widgets" is refined: `w`/`h` arrive on grid-placed *elements*, not on items, so old item code never sees spans).

**Grid placement (new base block, shared by every grid-placed element type):**
```json
{ "id": "w-weather", "type": "appWidget",
  "page": "home", "x": 0, "y": 0, "w": 4, "h": 2,
  "content": { "provider": { "package": "com.example.weather", "class": "com.example.weather.WeatherWidgetProvider" },
               "profile": "personal", "label": "Weather" },
  "style": { "padding": { "horizontal": 4, "vertical": 4 }, "backdrop": "none" } }
```
- `page` is a page ID. `x`, `y` are zero-based cells; `w`, `h` are ≥ 1 and must fit the grid. An element has **either** `anchor` (title bar) **or** `page`/`x`/`y`/`w`/`h`, never both.
- **Cells are reserved even when the element is skipped.** Placement keys are part of the *base* element and are validated before the type check (§6 of title-bar.md). An old launcher that skips an unknown `"type": "clock"` still marks its cells occupied and refuses to drop an app there. Otherwise an older phone could create overlaps that a newer phone then rejects as B2. The skipped area renders as nothing (Pragma's Q-T4 ruling) but is not droppable. This is the single most important rule to ship in the **container release**, before any grid type exists.
- Overlap between any two grid things (item or element) stays a **validation error** (B2), as today.
- `x-` keys allowed. Canonical order: `id, type, requires, enabled, page, x, y, w, h, content, style`, then `x-` keys.
- **Not in the file, ever:** the `appWidgetId`, the provider's internal config, provider size bounds or previews. Those are device facts. Per-page `overrides` don't apply to grid-placed elements (they already live on one page); `enabled: false` hides one and frees no cells.
- Dock and folders can't hold widgets.

**Sizing semantics (adopted from Android + Lovelace):**
- The **file's `w`/`h` is the user's choice** and is the source of truth.
- At runtime the launcher derives the provider's bounds **in cells** for this device: default from `targetCellWidth/Height`, else `minWidth/minHeight` rounded up to cells; min from `minResize*`; max from `maxResize*`; and `resizeMode`.
- If the file's size is outside the bounds, the launcher **renders at the file's size anyway** (the widget handles its own layout), adds a `status.json` warning (`elements[3]: 1x1 is smaller than this widget's minimum 2x1`), and **doesn't rewrite the file**. Writing a clamped size would churn the file and fight the agent. The resize UI on the phone does enforce bounds.
- The host reports the real dp size per cell span to the provider (`OPTION_APPWIDGET_SIZES` / `updateAppWidgetSize`) on every layout change, including grid changes from the file.
- **Different phones, same file:** cell dp size differs per device, so a 2x1 widget can be roomy on the S25 Ultra and cramped on the A51. That's expected; the warning above covers the extreme cases.

**Resize UX (high level, for Pragma):** long-press → *Resize* shows a cell-snapped frame (Launcher3 style) bounded by provider min/max and by free cells. **No automatic reflow** of other items in the first version: the frame stops at occupied cells. The result writes one `SetElement(id, {x, y, w, h})` through the existing debounce, hash and ID replay. Move works the same as for app items, with the whole span checked for collisions. Reflow can come later.

### 5.2 Device-local binding table
The phone keeps `filesDir/widgets/bindings.json` (app-private, never synced):
`elementId → { appWidgetId, provider, profile, hostId, state }`, where `state` is one of
`bound` | `needsConsent` | `needsConfig` | `providerMissing` (app not installed or widget removed by update) | `paused` (work profile in quiet mode).
- **On load:** each `appWidget` element is matched to its binding by **element ID and provider**. If the provider in the file changed, the old ID is deleted and the element goes to `needsConsent`.
- **Garbage collection:** after a successful load, `getAppWidgetIds()` entries not referenced by any element are deleted with `deleteAppWidgetId`. This only happens after the file has been stable for one load cycle, so a transient bad file (B2, last-good kept) never destroys bindings. Removing the element from the file is how an agent removes a widget.
- **Placeholder tile:** any state other than `bound` renders a placeholder in the widget's cells, with the provider's app icon, label and a one-line reason ("Tap to set up", "Install Weather to show this widget", "Work apps are paused"). This is Launcher3's pending-widget pattern.

### 5.3 ACTION_APPWIDGET_BIND consent flow
1. The user adds a widget from the phone's widget picker (home long-press → *Widgets*), or taps a placeholder created by the file.
2. `allocateAppWidgetId`, then `bindAppWidgetIdIfAllowed(id, userHandle, provider, sizeOptions)`.
3. If that returns false, launch `ACTION_APPWIDGET_BIND` (with `EXTRA_APPWIDGET_PROVIDER_PROFILE` for work widgets). On cancel, `deleteAppWidgetId` and stay `needsConsent`. The system dialog's "always allow" makes later binds silent; we never ask for `BIND_APPWIDGET` (it's signature/system-only).
4. If the provider has a configure activity and isn't `configuration_optional`, call `startAppWidgetConfigureActivityForResult`. On cancel, delete and revert (Launcher3 behaviour). `reconfigurable` widgets get a *Reconfigure* menu item.
5. **Rule: a file load never opens a bind or configure dialog by itself.** An agent adding six widgets to the file must not produce six system dialogs on the next poll (cf. [38]). File-created widgets wait as placeholders until tapped. Exception, to be decided by Christopher (Q-C1): if "always allow" was already granted (that is, `bindAppWidgetIdIfAllowed` succeeds silently) and the widget needs no configure step, the launcher may bind on load with no UI.
6. Binds happen only while home is visible and resumed. Before the system dialog, a short in-app explanation (Pragma) says why it's asked.

### 5.4 Restoring widget IDs across reinstall and sync
What **can't** be restored, by anyone:
- **The provider's internal config** (city, account, which calendar). It lives in the provider app keyed by widget ID. Lawnchair and Nova lose it too [31][35]. Widgets with `configuration_optional` come back with defaults; others need the configure step again.
- **Widget IDs after uninstall/reinstall of the launcher:** uninstalling deletes the host's widget IDs and our app-private binding table. The file survives (it's in the user's folder), so **placement survives; every widget comes back as a placeholder** and needs one tap (silent if "always allow" is re-granted).
- **Widget IDs on another device** (cross-device sync via Syncthing or git): IDs are per device and per install. Each phone keeps its own binding table against the same element IDs, so the shared file describes *what* and *where*, and each device binds *its* instance once. If the provider isn't installed there, the placeholder says so, and the element is kept untouched in the file.

What **can** be restored, as options:
- **A. Do nothing extra (recommended for the first release):** placeholders plus one-tap rebind, as above. Simple, predictable, and it matches what Lawnchair actually delivers.
- **B. Android system backup (later, optional):** include `widgets/bindings.json` in Auto Backup and handle `ACTION_APPWIDGET_HOST_RESTORED` to remap old IDs to new ones (Launcher3's receiver [23]). This only applies to a *full device restore* (Google backup or Samsung Smart Switch) to the same launcher package with providers installed, and the provider must also restore its own data. Worth it only if Christopher migrates phones that way, and it must be verified on One UI (Grace). It doesn't help Syncthing sync.
- **C. Hints in the file (rejected for now):** storing provider config hints, such as an `x-` key an agent writes. Providers expose no API for it, so it could never be applied.

### 5.5 First-party config-defined widgets
Reuse everything the title bar built: element base, `style` block (`elementStyle`), template grammar with allow-listed tokens, ticker, contrast warnings, `requires` levels.
- **`text`**: `content.template` (same grammar and token set as the title bar: `{time:…}`, `{date:…}`, `{page}`, and later `{status}`/`{nextAlarm}`), `maxLines`, plus the full text style. This covers "clock as text", greetings, and status lines.
- **`clock`**: `content.face: "digital" | "analog"`, with an optional pattern. It's a separate type because analog drawing and size-responsive layout aren't text.
- Each first-party type **declares supported sizes** (WidgetKit families / Glance `Responsive`): e.g. `text` 1x1 up to the grid width x 4, `clock` 2x1 / 2x2 / 4x2. A size outside that list gets the nearest layout plus a warning.
- **Data binding, kept local (no INTERNET):** a later `{data:key.path}` token reads a small JSON file the agent writes in the folder (`widgets-data.json`, read cap 64 KiB, polled with `launcher.json`). This is KWGT's "globals" idea, but data lives outside `launcher.json`, so updates don't churn the layout file or git (same reasoning as `{status}` in title-bar.md §5). Weather, HA states and calendars come from **hosted AppWidgets** (the HA companion app, weather apps), not from the launcher.
- **Two-way:** first-party widgets are read-only in the first release. Tap actions are limited to in-launcher targets and to launching an app or shortcut already allowed in items (`tap: {app: …}`), never URLs, intents with extras or scripts (title-bar.md §5 "not in scope, ever"). A toggle that writes data back is an open question (Q-C4); if ever allowed, it writes to the data file, never to `launcher.json`.
- **Composite (`stack`) widgets** with rows/columns of text and clock children, inspired by Adaptive Cards' `Container`/`ColumnSet`, come last and only if real use needs them.

### 5.6 Adopt vs invent
| Adopt | From | Invent (ours) |
|---|---|---|
| Size in cells with default/min/max bounds, drag-resize within bounds | Android `targetCell*`/`*Resize*`, Lovelace `grid_options` | File `w`/`h` is the source of truth; out-of-bounds is a warning, not a rewrite |
| Portable record = provider + profile + position + span; ID is device-local | Launcher3 / Nova / Lawnchair | Element-ID-keyed private binding table; provider change = rebind |
| Bind via `bindAppWidgetIdIfAllowed` → `ACTION_APPWIDGET_BIND` → configure | Android host docs | "File loads never prompt" rule |
| Pending / "tap to set up" placeholder | Launcher3 | Placeholder states and copy (Pragma) |
| Restore remap via `ACTION_APPWIDGET_HOST_RESTORED` | Launcher3 `AppWidgetsRestoredReceiver` | Optional, later (5.4 B) |
| Content vs host styling split | Adaptive Cards HostConfig, HA themes | Already our `style` + `auto` defaults |
| Typed registry, unknown types degrade | Lovelace `custom:`, Adaptive Cards `fallback` | Already #81 skip + `requires`; new: skipped elements still reserve cells |
| Few supported sizes per type; precomputed time states | WidgetKit families/timelines, Glance `Responsive` | Per-type size lists for `text`/`clock` |
| — | — | Allow-listed template tokens (not KWGT formulas); local `widgets-data.json` data source |

## 6. Staged plan (all on `schemaVersion` 1, consistent with #81)
Feature levels continue title-bar.md §6: level 1 = container, level 2 = title bar. Numbers below are proposals; the definitive list lives in ARCHITECTURE.

| Stage | What | Feature level | Owners |
|---|---|---|---|
| 0. Docs (this PR) | Research and recommendation; Pragma's UX answers; Christopher's policy answers | — | Archimedes, Pragma |
| 1. Container release (already planned) | Add to the container the **grid placement base block** (`page`, `x`, `y`, `w`, `h`) and the rule that skipped grid elements still reserve cells. No grid type yet. | 1 (amend before it ships) | Archimedes (schema), Sprite, Grace |
| 2. Schema for widgets | `appWidget` type in `schema/` (provider, profile, label, frame-only style), examples and fixtures; `validate.py` support | 3 | Archimedes, Grace |
| 3. Host AppWidgets | `AppWidgetHost` in Compose, binding table, picker, bind/configure flow, placeholders, move/resize write-back, size reporting, garbage collection, work-profile pause | 3 | Sprite, Pragma (UX), Grace (S25/A53/A51; One UI quirks) |
| 4. First-party `text` and `clock` | Reuse template grammar and style; per-type sizes | 4 | Archimedes (design + schema), Sprite |
| 5. Local data | `{status}`, `{nextAlarm}`, `{data:…}` from `widgets-data.json` | 5+ | Archimedes, Sprite |
| 6. Later / optional | System-backup restore remap (5.4 B), reflow on resize, `stack` composites, `dynamic` colour token, tap actions | 6+ | — |

The schema stays v1 because: new element *types* are skipped by launchers that predate them, `requires` covers new fields on known types, and the cell reservation rule (Stage 1) keeps older phones from creating overlaps. `schemaVersion` 2 stays reserved for genuinely breaking changes. This **supersedes** "widget type, w/h in schema v2" in ARCHITECTURE §12 and §15 row 1: those predate #81.

**Risk to flag:** Stage 1 must ship the placement block *before* any phone runs a launcher that skips grid elements without reserving cells. If the container release already shipped without it, grid elements need `requires` plus a short-lived rule that older launchers reject (not skip) them, or a test-device-only window as in title-bar.md §2.

## 7. Open questions

### For Pragma (UX)
- **Q-W1** Widget picker: where does it live (home long-press menu next to *Pages*?), how are providers grouped (by app, with previews from `previewImage`/`previewLayout`), and how are work-profile widgets shown?
- **Q-W2** Placeholder tile design and copy for each state: needs consent, needs config, provider missing, paused.
- **Q-W3** The in-app explanation before the system bind dialog: wording, and whether it's shown every time or once.
- **Q-W4** Resize: handles and frame, snapping, haptics, what happens at an occupied cell (stop, as proposed, or offer reflow), and the TalkBack alternative (a "Resize" action with width/height steppers?).
- **Q-W5** Out-of-bounds sizes from the file (e.g. 1x1 for a 2x1-minimum widget): warning only, as proposed, or also a visual cue on the widget?
- **Q-W6** How first-party `text`/`clock` widgets appear in the picker, and how much styling the phone exposes (preset-only like the title bar, with "Custom (set in the file)" for the rest).

### For Christopher (policy)
- **Q-C1** Bind consent: should a widget the *file* added stay a placeholder until you tap it (recommended), or may the launcher bind it silently on load when Android already has "always allow" for us?
- **Q-C2** Sync: is it OK that each phone needs a one-time tap per widget, and that widgets whose app isn't installed show a placeholder on that phone?
- **Q-C3** Phone migration: do you restore phones with Google backup or Smart Switch? If yes, Stage 6's restore remap is worth doing; if you set phones up fresh, it isn't.
- **Q-C4** Two-way: should first-party widgets ever *write* data (a toggle or counter into `widgets-data.json`), or stay read-only?
- **Q-C5** Home Assistant: *(decided via design-doc Q-C5, 2026-10-19)* Companion AppWidgets + agent-written local JSON are enough for v1; no built-in HA client / no tight coupling. Own HA-reading cards are advanced; distant first-class in-app widgets are a far goal only.
- **Q-C6** Which first-party widgets matter most after `text` and `clock`?

## 8. Sources
1. microsoft/AdaptiveCards (MIT; binary EULA note): https://github.com/microsoft/AdaptiveCards
2. Adaptive Cards HostConfig: https://learn.microsoft.com/en-us/adaptive-cards/rendering-cards/host-config and renderer requirements: https://learn.microsoft.com/en-us/adaptive-cards/rendering-cards/implement-a-renderer
3. Adaptive Cards template language: https://learn.microsoft.com/en-us/adaptive-cards/templating/language
4. Action.Execute / Universal Action Model: https://learn.microsoft.com/en-us/adaptive-cards/authoring-cards/universal-action-model
5. Refresh element: https://learn.microsoft.com/en-us/adaptive-cards/schema-explorer/refresh
6. adaptivecards-android on Maven Central (Binary EULA, 3.8.6 Aug 2026): https://central.sonatype.com/artifact/io.adaptivecards/adaptivecards-android
7. HA custom card (getGridOptions, 12 columns, 56 px rows): https://developers.home-assistant.io/docs/frontend/custom-ui/custom-card/
8. HA dashboard cards: https://www.home-assistant.io/dashboards/cards/
9. HA sizing support in sections view (2024.11): https://developers.home-assistant.io/blog/2024/11/06/custom-card-sections-support/
10. HA frontend PR #28003 (min_rows/min_columns overrides in YAML): https://github.com/home-assistant/frontend/pull/28003
11. HA companion Android widgets (Template widget): https://companion.home-assistant.io/docs/integrations/android-widgets/
12. KWGT FAQ: https://docs.kustom.rocks/docs/faq/faq_kwgt/
13. .kwgt is a ZIP with JSON: https://fileformatdb.com/en/extension/kwgt and https://www.reddit.com/r/kustom/comments/wcm39m/kwgt_file_format/
14. Example KWGT module JSON with formulas: https://gist.github.com/mst-mkt/ff2af3f6934fab204da3ddd85fced1a6
15. Kustom forum on repacked presets and ZIP metadata: https://forum.kustom.rocks/t/kwgt-major-issue-with-custom-widget-none-of-these-files-have-been-found-in-zip-stream-ljava-lang-string-ec87569/8943
16. AppWidgetProviderInfo reference: https://developer.android.com/reference/android/appwidget/AppWidgetProviderInfo
17. Flexible widget layouts (targetCell*, maxResize*, size reporting): https://developer.android.com/develop/ui/views/appwidgets/layouts
18. Build a widget host: https://developer.android.com/develop/ui/views/appwidgets/host
19. Widget configuration (reconfigurable, configuration_optional): https://developer.android.com/develop/ui/compose/glance/configuration
20. Create a simple widget (sizing attributes table): https://developer.android.com/develop/ui/views/appwidgets
21. AppWidgetHost reference (onAppWidgetRemoved, getAppWidgetIds): https://developer.android.com/reference/android/appwidget/AppWidgetHost
22. AppWidgetManager reference (ACTION_APPWIDGET_HOST_RESTORED, OPTION_APPWIDGET_RESTORE_COMPLETED): https://developer.android.com/reference/android/appwidget/AppWidgetManager
23. Launcher3 AppWidgetsRestoredReceiver: https://android.googlesource.com/platform/packages/apps/Launcher3/+/master/src/com/android/launcher3/AppWidgetsRestoredReceiver.java
24. Glance, build UI (SizeMode): https://developer.android.com/develop/ui/compose/glance/build-ui
25. Glance interoperability (RemoteViews limits): https://developer.android.com/develop/ui/compose/glance/interoperability
26. Kvaesitso: https://github.com/MM2-0/Kvaesitso/
27. WidgetKit supportedFamilies: https://developer.apple.com/documentation/SwiftUI/WidgetConfiguration/supportedFamilies(_:)
28. WidgetKit AppIntentConfiguration: https://developer.apple.com/documentation/widgetkit/appintentconfiguration
29. WidgetKit IntentTimelineProvider: https://developer.apple.com/documentation/widgetkit/intenttimelineprovider
30. Lawnchair license: https://github.com/LawnchairLauncher/lawnchair/blob/14-dev/LICENSE.txt
31. Lawnchair backup, restore and reset: https://docs.lawnchair.app/core-features/backup-restore-and-reset
32. Lawnchair issue: restoring a backup doesn't restore widgets: https://github.com/LawnchairLauncher/lawnchair/issues/3729
33. Neo Launcher license: https://github.com/NeoApplications/Neo-Launcher/blob/main/LICENSE
34. Smart Launcher (proprietary; backup location per its help pages): https://www.smartlauncher.net/
35. Nova Launcher backup format: http://fileformats.archiveteam.org/wiki/Nova_Launcher_Backup
36. Nova → Lawnchair converter (resets appWidgetId): https://gist.github.com/paracycle/2ec039c2e8f887b63a870c8b639ca27c
37. Launcher3 source (pending widgets, resize frame): https://android.googlesource.com/platform/packages/apps/Launcher3/
38. OpenLauncher issue #573 (repeated bind pop-ups): https://github.com/OpenLauncherTeam/openlauncher/issues/573
