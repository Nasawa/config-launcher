# Config Launcher: architecture

Status: proposal for review, 2026-10-18. Author: Archimedes (architecture). Builds on [docs/research-spike.md](docs/research-spike.md) and departs from it where noted (**Departs from spike**).

## 1. Goals and non-goals
- **The home screen is a file.** One JSON document in a folder the user picks is the source of truth. The launcher renders it, and on-device edits (drag, add, remove) write back to the same file.
- **Machine-assisted organization.** An agent on a PC edits the file in a synced folder (Syncthing, a git client). The launcher never runs agent code or talks to the network.
- **No risky permissions.** No storage, QUERY_ALL_PACKAGES, Accessibility, Device Admin, usage stats or network in v1.
- Non-goals for v1: widgets, icon packs, search providers, gestures beyond basics, an on-device AI, a ContentProvider/CLI API.

## 2. Platform and stack
| Choice | Decision | Why |
|---|---|---|
| Language/UI | Kotlin 2.x, Jetpack Compose (Material 3), single `MainActivity` with the HOME category | Matches the spike; Compose renders an immutable model naturally. |
| minSdk | **29** (Android 10), see §18.3 (supersedes the earlier 26 and 23 rulings) | Covers his daily and test phones (S25 Ultra, A53, A51) with no compatibility gates except private space (35): RoleManager, quiet-mode requests, shortcuts, adaptive icons and `LauncherApps.getProfiles()` are all available. The old devices are test-only, not targets. |
| target / compile | **targetSdk 35, compileSdk 37** (§18) | compileSdk 37 is required by core-ktx 1.19 and Compose BOM 2026.09; targetSdk stays 35 (edge-to-edge default, no 36/37 behaviour changes taken yet). |
| JSON | `kotlinx.serialization` for the model; `org.json`/kotlinx `JsonElement` tree for preserving `x-` keys | No reflection, multiplatform-friendly, fast. |
| Validation | Hand-written Kotlin validator mirroring the schema, plus schema tests on the JVM with a JSON Schema library (networknt) against the same examples | Pulling a full 2020-12 validator into the APK costs size and startup; the Kotlin validator also gives friendlier, path-based messages. The test suite keeps the two in lockstep. |
| Async | Coroutines + `StateFlow` | Single source of truth flow into Compose. |
| DI | Manual (one `AppGraph` object) | One module, few singletons; Hilt is not worth the build time. |
| Persistence of app state | DataStore (Preferences) for settings and the persisted folder URI; app-private files for last-good copies | No database. |

**Departs from spike: JSON, not YAML.** The spike leans YAML (`layout.yaml`) but also lists YAML round-trip loss as a top risk. Write-back is a core feature, so we pick JSON: agents generate it reliably, the schema is directly applicable, and a canonical serializer makes diffs stable. Comments are not supported; use `x-` keys (preserved verbatim) for notes. JSON5/YAML can be revisited later as an *input* format only.

## 3. Config schema (v1)
File: `launcher.json` in the picked folder. Schema: [`schema/launcher-config.v1.schema.json`](schema/launcher-config.v1.schema.json) (JSON Schema 2020-12). Example: [`schema/examples/minimal.json`](schema/examples/minimal.json).

Top level: `schemaVersion` (const 1), `revision`, `grid {columns, rows}`, `pages[]`, `dock[]`, `drawer {sort, hidden[], rules[]}`, `unsorted {mode, pageTitle}`.

Items (discriminated by `type`):
- `app`: `package`, optional `activity` (omit to use the default launcher activity), optional `profile` (`personal`|`work`), `label` override, `tags[]`.
- `shortcut`: `package` + `shortcutId` (a pinned shortcut owned by that package, see §8), optional `profile`, `label`.
- `folder`: `label`, `items[]` of apps/shortcuts. No nested folders in v1.
- Page items need `x` and `y`; dock and folder items must not have them (order is the array order). Every item is 1x1 in v1; `w/h` arrive with widgets.

**Unsorted.** Installed launchable apps that appear nowhere in pages/dock/folders and are not in `drawer.hidden` are "unsorted". `unsorted.mode`: `drawer` (default: they simply appear in the drawer, which always lists all apps), `page` (also shown on a synthetic last page, not written to the file), or `hidden`. The launcher never writes unsorted apps into the file on its own; placing one by drag is an explicit edit. This keeps installs from churning the file.

**Drawer rules** group apps in the drawer by `packages`, `packagePrefix`, `tag` or Android `category`. First matching rule wins. Rules never move items on the home pages.

**Stable IDs.** Every page, item and rule has an `id` matching `^[a-z0-9][a-z0-9_-]{0,63}$`, unique across the file. IDs, not positions or package names, are what drags, conflict merges and agents refer to. The launcher generates IDs as a short type prefix plus random base32 (`a-7k2m9q`); agents may use readable ones (`a-settings`). Duplicate IDs are a validation error. IDs are never reused after deletion within a session (the store keeps a tombstone set for merge purposes, §7).

**Validation beyond the schema** (Kotlin validator only): IDs unique; no two page items share a cell; positions inside `grid`; at most one occurrence of the same (package, activity, profile) on a page (warning, not error). Unknown packages are **not** errors: they render greyed out ("not installed") and are never deleted automatically (spike §4).

**Unknown keys.** Keys prefixed `x-` are allowed at the root, on pages and on items, and are round-tripped verbatim. Any other unknown key is an error, so typos fail loudly instead of silently being dropped on the next write-back.

**Versioning and migration rules.**
1. `schemaVersion` is an integer; a new major version gets a new file `launcher-config.vN.schema.json`. Additive optional fields do *not* bump the version only if older launchers would still render correctly; otherwise bump.
2. The launcher reads any version ≤ its own, migrates in memory through a chain of pure functions `migrate1to2`, `migrate2to3`…, each with golden-file tests.
3. It **does not write back** a migrated file until the user makes an edit or taps "Upgrade file" in the error/notice banner; before overwriting, the original is saved as `.configlauncher/backup/launcher.v{N}.{timestamp}.json` in the folder.
4. A file with `schemaVersion` greater than supported is rejected (keep last-good, banner "made by a newer version"). Never downgrade-write.
5. Migrations never drop `x-` keys and never change IDs.

**Canonical form.** The launcher writes 2-space indented JSON, keys in schema order, `x-` keys after known keys, LF endings, trailing newline, UTF-8 without BOM. Agents are encouraged (not required) to emit the same so git diffs stay small. `revision` is incremented on every launcher write; agents should increment it too but correctness does not depend on it.

## 4. Config store (SAF folder)
**Folder selection.** First run (or Settings → "Config folder") launches `ACTION_OPEN_DOCUMENT_TREE`. We call `takePersistableUriPermission(READ|WRITE)` and store the tree URI in DataStore. On every start we check `contentResolver.persistedUriPermissions`; if the grant is gone, show the "Reconnect folder" banner and keep rendering last-good. If the folder has no `launcher.json`, never create it silently: show B4 with `Create from current layout` and `Reconnect` (§17 a, AQ-8).

Why a tree, not a single document: we need to create temp/backup files beside the config and re-find `launcher.json` by name after an external tool replaces it (Syncthing and git replace files by rename, which changes the document ID on some providers).

**Change detection: why not FileObserver.** `FileObserver` watches a filesystem path via inotify. A SAF URI is not a path: the document may live in another app's sandbox, on a cloud provider, or behind a FUSE layer we have no read access to without storage permissions. Converting a tree URI to `/storage/emulated/0/...` and watching it fails under scoped storage (no permission to inotify the real directory) and is meaningless for non-local providers.

**ContentObserver is unreliable too.** `registerContentObserver` on a document or children URI only fires if the provider calls `notifyChange`. `ExternalStorageProvider` (the local "Internal storage" provider, which is where Syncthing folders live) notifies only for directories it is currently watching for an open children cursor, and only for changes made through the provider itself or picked up while that cursor lives; changes by Syncthing writing directly to disk are not reliably reported. Cloud providers vary.

**Decision: polling, with ContentObserver as a hint.**
- While the launcher is in the foreground (home visible): query the tree's children for `launcher.json` (`COLUMN_DOCUMENT_ID`, `COLUMN_LAST_MODIFIED`, `COLUMN_SIZE`) every **2 s**. One small cursor query; cheap.
- On `onStart`/`onResume` (the user pressing Home): poll immediately. This is the moment users look for the change, so perceived latency is ~0.
- In the background: no polling (no wakeups, no battery cost). Optional later: a WorkManager job every 15 min is pointless for a launcher that is not visible.
- Also register a ContentObserver on the children URI; a callback just triggers an immediate poll.
- A (docId, lastModified, size) change triggers a read. Because mtime has 1-2 s granularity on some filesystems, a read also computes SHA-256 and compares against the last applied hash; equal hash = no-op.

**Load pipeline (inbound binding).**
1. Read bytes (cap 2 MiB, see §17 Q16; checked via `COLUMN_SIZE` before reading and enforced while streaming; larger is rejected, see §15 OQ-9).
2. If SHA-256 equals the last applied or last self-written hash, stop (§5 self-write guard).
3. Wait for stability: re-stat after 300 ms; if size/mtime changed, restart (handles a sync client mid-write).
4. Parse → migrate → validate. On any failure: keep the current model, show the error banner (§9), do not touch last-good.
5. Reconcile with installed apps (resolve labels/icons, mark missing).
6. **Atomic swap:** publish the new immutable `LauncherConfig` to the `StateFlow` in one assignment. Compose recomposes from it; there is no partially applied state.
7. Write the raw bytes to app-private `filesDir/config/last-good.json` (temp file + `File.renameTo`, which is atomic on app-private ext4/f2fs) and keep the previous 10 as `history/<timestamp>.json`.

**Fallback order at startup:** folder file (valid) → `last-good.json` → built-in default (dock with dialer/messages/browser/camera via role queries, everything else in the drawer). The launcher must always draw something within the first frame budget, so startup renders last-good immediately and swaps when the folder read completes.

## 5. Write-back (outbound binding)
Triggered by drag-drop, add-to-home, remove, rename, folder create/merge, and the "Upgrade file" action.

1. The UI applies the mutation to the in-memory model immediately (optimistic) and records it as an `Edit` (op + target IDs) in a pending queue, together with the **base hash** (hash of the file version the model was derived from).
2. **Debounce:** 750 ms after the last edit, and never during an active drag gesture (the drag end starts the timer). A burst of rearranging becomes one write.
3. Before writing, re-read the file and compare its hash to the base hash. Different → conflict path (§6).
4. Serialize canonically, bump `revision`, compute SHA-256, add it to the **self-write set** (last 4 hashes), then write.
5. When the poller next sees the file, a hash in the self-write set is swallowed (no reload, no flicker). If the file shows a hash that is neither ours nor base, it is an external edit.

**Atomic writes under SAF: the provider limits.**
- SAF has no "replace" primitive. `DocumentsContract.renameDocument` fails or auto-renames (`launcher (1).json`) if the target name exists, and `moveDocument` (API 24+) is optional per provider (`FLAG_SUPPORTS_MOVE`) and also does not overwrite. So true temp-then-rename-over is **not possible** through SAF in general.
- `openOutputStream(uri, "wt")` truncates then writes. On some older providers `"w"` did not truncate (Android 10-era bug), so we always use `"wt"` and verify by reading back.
- Delete-then-rename (delete `launcher.json`, rename temp → `launcher.json`) is a two-step operation with a window where the file is missing; a sync client could propagate a delete. Rejected.

**Decision:**
1. Write the new content to `.configlauncher/launcher.json.tmp` (created once, reused) and read it back to verify the hash. This protects against provider write failures (disk full, cloud provider offline) before we touch the real file.
2. Write the same bytes to `launcher.json` with `"wt"`, flush, close, and read back to verify the hash.
3. If step 2 fails or verification fails, restore from the temp copy with `"wt"`; if that also fails, banner "Couldn't save layout" with Retry, and the in-memory model plus `last-good.json` keep the user's edit.
4. Update `last-good.json` after verification.

The window where another reader could see a half-written `launcher.json` is the duration of one small write (typically < 50 KB, < 10 ms on local storage). Readers on the agent side should treat a parse failure as "retry in a second", which the README will say. If a provider advertises `FLAG_SUPPORTS_RENAME` and we are on local `ExternalStorageProvider`, a future optimization could use rename-based replacement, but v1 keeps one code path.

## 6. Conflict handling
Cases:
- **External edit arrives with no pending local edits:** apply it (normal inbound path).
- **External edit arrives during a drag:** do not swap the model mid-gesture. Park the new file as `incoming`. On drag end, the drop becomes a pending edit and we go to the merge below.
- **External edit detected at write time (hash ≠ base):** three-way merge by ID.

**When the hash check fails, the user decides** (adopted from docs/ux.md §4.4 and the coordinator, AQ-6). Nothing is overwritten; banner B6 `launcher.json changed while you were editing.` offers:
- **`Use file`**: discard the pending local ops and load the file. The discarded local model is snapshotted to history.
- **`Keep mine`**: replay the pending ops by ID onto the *file's* version (not a whole-file overwrite), validate, and write. The file version as it was is snapshotted first.

**Replay rule (ID-based).** Local edits are stored as operations, not as a whole-file diff: `Move(id, container, cell|index)`, `Add(item, container, cell)`, `Remove(id)`, `Rename(id, label)`, `CreateFolder(id, memberIds, cell)`. On `Keep mine`, each op is applied to the file's model:
- Target ID still exists → apply. If the target cell is now occupied, place in the nearest free cell on the same page, else the first free cell on any page, else a new page.
- Target ID was removed in the file → drop the op, snackbar "1 change skipped because the item was removed in the file".
- `Add` whose (package, activity, profile) already exists in the destination container → skip as duplicate.
If the result fails validation (should not happen), nothing is written and the B6 banner stays with only `Use file`.

Why `Keep mine` replays instead of overwriting (a small deviation from ux.md's "write my version"): an agent's bulk reorganization and the user's one drag usually touch different items, so replay keeps both; a whole-file overwrite would silently erase the agent's work, which history could recover but the user wouldn't know to look for. **Departs from spike:** the spike's last-writer-wins plus counter is rejected for the same reason; `revision` is a hint, not the conflict key.

Every merge or discarded edit writes the pre-merge local model to `filesDir/config/history/` so a "Restore previous layout" action in Settings can recover anything.

## 7. Model and app reconciliation
- `model`: immutable data classes (`LauncherConfig`, `Page`, `Item` sealed: `AppItem`, `ShortcutItem`, `FolderItem`, `DrawerRule`…) plus `x-` extras as `JsonObject`. Pure, no Android imports, JVM-testable.
- `apps`: `LauncherApps.getActivityList(null, user)` for each profile from `LauncherApps.getProfiles()`, `LauncherApps.Callback` for install/remove/change, `getShortcuts` for shortcut metadata, icons via `LauncherActivityInfo.getBadgedIcon` cached in memory (LRU) keyed by component+user.
- Profiles: `personal` = the current user; `work` = managed profile (`UserManager.isManagedProfile` via `LauncherApps`); `private` = Android 15 private space (`LauncherUserInfo` on API 35); **never written to the file** and never shown in Unsorted (§15 OQ-31). The schema has no `private` value. Config never stores numeric user IDs or serials, since those differ per device and after reprovisioning. If several work profiles exist (rare), the first wins in v1.
- Reconciliation output is a derived `ResolvedLayout` (config + install state + icons/labels) that Compose renders. It never mutates the config.

## 8. Shortcuts, permissions and the home role
**Pinned shortcuts.** As the default launcher we are the shortcut host: `LauncherApps.hasShortcutHostPermission()` returns true. We handle `LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT` (an exported activity with that intent filter) via `getPinItemRequest(intent)`; on accept we call `request.accept()`, which pins the shortcut for us, then add a `shortcut` item to the first free cell and write back. Our set of pinned shortcuts per package is kept equal to the shortcuts referenced in the file: on load we call `LauncherApps.pinShortcuts(package, ids, user)` with the referenced IDs, so an agent can add a shortcut by ID if the app publishes it as a dynamic/manifest shortcut. Shortcuts that no longer exist render greyed out. Launch via `startShortcut`. If we are not the default home, shortcut items render but are disabled with "Set as Home to use shortcuts".

**Permission posture (manifest):** none of the dangerous or special permissions. Specifically:
- **No `QUERY_ALL_PACKAGES`.** `LauncherApps` is the launcher API and is the right way to list launchable activities across profiles. We add `<queries><intent><action MAIN/><category LAUNCHER/></intent></queries>` as belt-and-braces for `PackageManager` lookups (labels, categories via `ApplicationInfo.category`). We never need non-launchable packages. Keeps the Play/F-Droid story trivial and matches "no risky permissions".
- **No storage permissions** (`READ/WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`). SAF gives scoped, user-chosen, revocable access to exactly one folder, which is the strongest possible consent model. Contrast OhmLauncher's /sdcard access in the spike.
- **No INTERNET.** The launcher never fetches anything; sync is someone else's job. This is also a security property: a malicious config cannot exfiltrate.
- Normal permissions only if needed by the scaffold: `EXPAND_STATUS_BAR` (swipe down for notifications), `SET_WALLPAPER_HINTS` is not needed. `REQUEST_DELETE_PACKAGES` for an "Uninstall" menu item is deferred to later (it shows the system confirmation anyway).
- Config content is data, never code: no arbitrary intents/URIs in v1, launch only resolved (package, activity) pairs from `LauncherApps` and shortcuts from `startShortcut`.

**Default home role.** Use `RoleManager.createRequestRoleIntent(ROLE_HOME)` (minSdk 29, so no fallback). Onboarding shows a "Set as Home" step, and the app works (as a normal activity) without the role, minus shortcuts. `MainActivity` declares `MAIN` + `HOME` + `DEFAULT`, `launchMode=singleTask`, `stateNotNeeded`, `excludeFromRecents`, and handles `onNewIntent` (Home pressed while on home → go to page 0 / close drawer).

## 9. Error UX
Principle: **the home screen always works**; errors never blank it.
- **Invalid file:** keep rendering the current (or last-good) layout; a slim, dismissible banner at the top: "launcher.json has 2 problems. Showing the last working layout." Tap → an error sheet listing each problem with a JSON path (`pages[0].items[3]: cell (2,0) already used by "a-settings"`) and line/column when parsing fails, plus actions: Copy errors, Restore last-good to file, Dismiss. The banner clears automatically when a valid version appears.
- **Folder access lost / folder missing:** banner "Can't reach your config folder" → Reconnect (re-pick) / Use built-in copy.
- **Save failed:** banner "Couldn't save your change" → Retry; the edit is kept in memory and last-good.
- **Merge notices:** a snackbar for discarded ops, with Undo.
- **Newer schema:** banner "This file needs a newer Config Launcher".
- **Missing apps:** greyed icon with a small badge; tap shows "Not installed" with "Remove from layout" (explicit write) or "Open store page" (later).
- An `errors.json` mirror in the folder (`.configlauncher/status.json` with the last load result: ok/errors list/hash/time) so an agent can read why its edit was rejected without the phone in hand. This is cheap and important for the machine-assisted loop.

## 10. Module and package layout
Single Gradle module **`:app`**, `applicationId` and `namespace` **`dev.nasawa.configlauncher`**. Subpackages:

| Package | Contents | Android deps? |
|---|---|---|
| `dev.nasawa.configlauncher.model` | Immutable config model, `Edit` ops, ID generator, merge/replay, grid placement | None (pure Kotlin) |
| `dev.nasawa.configlauncher.config` | JSON codec (canonical serializer), validator, migrations, hashing | None (pure Kotlin + kotlinx.serialization) |
| `dev.nasawa.configlauncher.store` | `ConfigStore`: SAF access, poller + observer, load pipeline, write-back queue/debounce, self-write guard, last-good/history, status.json | SAF, DataStore |
| `dev.nasawa.configlauncher.apps` | `AppRepository`: LauncherApps, profiles, shortcuts, icon cache, pin requests, launching | LauncherApps |
| `dev.nasawa.configlauncher.ui` | `MainActivity`, Compose screens: home pager, dock, drawer, folder sheet, drag-and-drop, banners, onboarding; `PinShortcutActivity` | Compose |
| `dev.nasawa.configlauncher.settings` | Settings screen, folder picker, home-role request, restore/history, DataStore prefs | Compose, DataStore |

Root: `ConfigLauncherApp` (Application, holds `AppGraph`). Data flow: `store` emits `StateFlow<LoadState>`, `apps` emits `StateFlow<InstalledApps>`, a `HomeViewModel` in `ui` combines them into `ResolvedLayout`; UI events go to `ConfigStore.edit(op)`. `model` and `config` must stay Android-free so they test on the JVM; enforce with a simple lint/test that fails on `android.*` imports in those packages.

## 11. Testing strategy
- **JVM unit tests (bulk of coverage):** codec round-trip (including `x-` keys and canonical output byte-for-byte), validator cases mirroring every schema rule, migrations with golden files, merge/replay table tests (move vs external delete, occupied cell, duplicate add, drag during external edit), placement, self-write guard logic with a fake clock.
- **Schema conformance:** a test runs every file in `schema/examples/` and `app/src/test/resources/configs/{valid,invalid}/` through both the networknt JSON Schema validator and the Kotlin validator and asserts they agree. CI also validates `schema/examples/*.json` with Python `jsonschema` (no Android toolchain needed).
- **Store tests:** `ConfigStore` against a fake `DocumentAccess` interface (in-memory files with mtime, failures injectable: truncate-not-honoured, write error midway, rename unsupported, permission revoked).
- **Instrumented tests (emulator API 29 and 35):** real SAF via a test `DocumentsProvider` in the androidTest APK; LauncherApps listing; pin-shortcut flow; Compose UI tests for drag-drop and banners.
- **Manual QA matrix (Grace):** Syncthing-synced folder edited from a PC, git client folder (e.g. MGit), a cloud provider (Google Drive) to document the expected degraded behaviour, work profile, private space on 35, Pixel + one OEM (Samsung).
- **Performance:** startup to first frame with a 300-item config < 500 ms on a mid device; poll cost measured.

## 12. MVP scope versus later
**MVP (v0.1):**
- Home role + onboarding (folder pick, set as Home).
- Pages (pager), dock, drawer (A-Z, hidden list, rule groups), folders (one level); unplaced apps in the drawer only (§18.5).
- App and pinned-shortcut items, personal + work profiles.
- SAF folder store: poll + observer, validate, atomic model swap, last-good, history (10), status.json.
- Write-back for move, add from drawer, remove, rename, create/merge folder; debounce; self-write guard; B6 conflict prompt with ID-based replay for `Keep mine`.
- Error banners and error sheet; Restore previous layout.
- Schema v1 published; README with agent instructions.

**Later:** widgets (AppWidgetHost + bind consent, `w/h` in schema v2), private-space polish, icon packs, search, gestures, a signature-protected ContentProvider and an `adb`/companion CLI (the spike's outerctl idea), propose-and-approve diffs for on-device agents, notification badges (optional listener toggle), JSON5/YAML input, multiple config profiles (e.g. work/weekend files).

## 13. Risks
| Risk | Mitigation |
|---|---|
| SAF change detection latency/quirks | Poll on resume + 2 s foreground poll; observer as hint; documented per provider. |
| Non-atomic SAF writes; agent reads half a file | Temp copy + verify, "wt", read-back; README tells agents to retry parse failures; small files. |
| Sync clients creating conflict copies (`launcher.sync-conflict-*.json`) | Only `launcher.json` is read; a notice lists sibling conflict files so the user can resolve. |
| Agent and drag write races | Op-replay merge by ID, history snapshots, Undo. |
| Provider changes document ID on replace | Always re-resolve `launcher.json` by name in the tree. |
| Cloud providers (Drive) slow/offline | Supported but "best effort"; recommend Syncthing/local folder. |
| Work/private profile identity | Symbolic profile names, never numeric IDs. |
| Startup cost on large configs | Render last-good first; icons loaded lazily; 2 MiB cap, writer refuses output over it (§17 Q16). |
| Two validators drifting | Conformance test (§11). |
| GPL if Kvaesitso code is copied | Reference only; copying code makes the app GPL-3, which conflicts with this repo's MIT license. **Departs from spike** (it says GPL is "fine"): it is fine only if we relicense, so we don't copy. |
| OEM launcher quirks (home role resets, Samsung) | Manual QA on one OEM device. |
| minSdk 29 excludes Christopher's older devices (S8+, Tab S2, OnePlus 2/One) | Accepted: they are test-only (§18.3). A custom ROM on Android 10+ brings one back with no code change. |

## 14. Open questions for Christopher (resolved 2026-10-18, see §18.1)
1. **File name and location:** is `launcher.json` in a folder of your choice OK, and which sync tool will you use (Syncthing, a git client, Drive)? This decides what Grace tests first.
2. **JSON vs YAML:** OK to go JSON-only (no comments; use `x-note` keys) for reliable write-back?
3. **Unsorted default:** should unplaced apps just live in the drawer (proposed), or also appear on an "Unsorted" home page by default?
4. **Conflicts:** is "external file wins, except the items you just touched" right, or would you rather be asked (a dialog) when a conflict happens?
5. **Agent feedback:** OK to write `.configlauncher/status.json` (load result and errors) and backups into your folder?
6. **Profiles:** do you use a work profile or private space we must support in the MVP?
7. **Distribution:** sideload/GitHub Releases only, or F-Droid/Play later? (Affects nothing now, but Play would review the HOME use case.)
8. **Your phone:** model and Android version, so QA targets the right device.
9. **Widgets:** confirm they can wait until after the MVP.

## 15. Resolved test-plan questions
Answers to OQ-1 to OQ-31 in [docs/test-plan.md](docs/test-plan.md). Coordinator starting points were adopted unless an override reason is given. These override anything above that conflicts.

| OQ | Answer |
|---|---|
| 1 | `widget` (and `text`) are not v1 types: the schema's `type` discriminator rejects them, so the file is invalid and last-good stays. They arrive in schema v2. Rejecting beats silently ignoring, because a later write-back would otherwise drop them. |
| 2 | Errors, never clamped. `grid` is 2-12 x 2-12 (default 4x5); `x`,`y` must be inside it; overlapping cells are an error. No `w`/`h` in v1 (all items 1x1), so negative sizes cannot occur. |
| 3 | Field is `schemaVersion` (integer). Older: migrated in memory, written back only on the next user edit or "Upgrade file", with a backup first. Newer: refused, last-good kept, banner. Missing: invalid. Never downgrade-written (§3). |
| 4 | Keys starting `x-` are preserved verbatim on write-back; any other unknown key, including `action`/`extras`/`uri`/`script`/`cmd`/`exec`, is a validation error. Nothing in the file ever builds an Intent. |
| 5 | Duplicate `id` anywhere in the file is an error. The same app in several places (e.g. dock and a page) is allowed; twice on one page or in one folder is a warning only. |
| 6 | JSON only (RFC 8259, no comments, no JSON5/YAML). Filename `launcher.json` at the root of the picked folder. |
| 7 | A UTF-8 BOM is accepted on read and stripped; the launcher always writes without one. Other encodings (UTF-16 etc.) are a parse error. |
| 8 | Grey out at runtime, not a validation error: whether a shortcut is published depends on device state, not the file. Greyed shortcuts never launch. |
| 9 | ~~256 KiB~~ **2 MiB file since §17 Q16** (refused before full read via `COLUMN_SIZE` and a streaming cap), JSON nesting depth 8 at the parser and folder depth 1 in the model (see 10), 2000 items total, 20 pages, 144 items per page, labels 60 chars, IDs 64 chars, other strings 256. **Override:** coordinator said depth 4; the legal v1 structure (root, pages, page, items, folder, items, item) already needs 7 JSON levels, so the parser limit is 8 (one spare) and the *folder* nesting limit is stricter than 4. The parser counts depth itself, so deep input fails fast without StackOverflowError. |
| 10 | No. Folders hold apps and shortcuts only (enforced by the schema). |
| 11 | Bidi controls (U+202A-202E, U+2066-2069), zero-width characters and other Cf/Cc characters are stripped from labels at render time; the file is left as written. A label override is always shown with the app's real icon, and the long-press sheet shows the real app name and package, so a label cannot disguise a different app. NUL or invalid UTF-8/lone surrogates are a parse error. |
| 12 | Duplicate keys in one object are rejected (parse error with a path). |
| 13 | App-private `filesDir/config/last-good.json` (temp + rename, atomic). It does not survive uninstall/reinstall and is excluded from Android backup (`dataExtractionRules`), because the folder file is the real store. |
| 14 | Built-in default layout: one empty page, dock filled via role queries (dialer, SMS, browser, camera) when resolvable, every app in the drawer; banner explains the problem ("No launcher.json yet: Create" or "launcher.json has N problems"). No crash loop: nothing in the load path throws past the store. |
| 15 | Allowed, after a snapshot. The first edit while invalid copies the broken file to `.configlauncher/broken/launcher.<timestamp>.json` in the folder (agent-visible, so the agent's work is never lost), then writes the edited last-good layout to `launcher.json`. The banner says so with "Open broken copy" guidance. Blocking edits would make the home screen feel broken over a typo. |
| 16 | N = 10, kept in app-private `filesDir/config/history/`, not in the synced folder. The folder only gets `.configlauncher/` (status.json, temp, broken and migration backups). Always kept even with git, since it costs ~2.5 MB max and covers the on-phone side; git covers the agent side. |
| 17 | No. The launcher reformats only when it writes because of a user edit or upgrade; a reload never triggers a write (no echo). |
| 18 | Adopted: write `.configlauncher/launcher.json.tmp`, read back and verify the hash; then truncate-write (`"wt"`) `launcher.json`, read back and verify; restore from temp on failure. Readers validate and fall back to last-good. SAF has no replace-rename, so this is used on every provider (§5); a torn read is rejected by validation, never applied. |
| 19 | Content hash decides, not the counter. A file whose hash differs from what we applied is a new version and is accepted even if its `revision` is lower or equal (adopted: keep the newer content, log it). Identical hash is a no-op. Every replaced version is snapshotted to history (10). If the phone has unsaved edits when the file changes, the user picks `Use file` or `Keep mine` (B6, §6); `Keep mine` replays the edits by ID onto the file rather than overwriting it. A full 3-way text merge is deferred (adopted). |
| 20 | The launcher bumps `revision` on its own writes (max seen + 1). Agents should bump it; if they don't, the file is accepted (hash decides) and the launcher's next write continues from max + 1. Missing `revision` is valid. |
| 21 | Ignored by the loader (only `launcher.json` is read). If siblings matching `launcher*.sync-conflict-*` or `launcher (n).json` exist, a dismissible notice names them and status.json lists them. Never auto-merged or deleted. |
| 22 | Write-back debounce 750 ms after the last edit and never mid-drag. Inbound: foreground poll every 2 s, immediate poll on resume and on any ContentObserver callback, and a 300 ms stability re-check before parsing, so two writes inside one window apply only the final content. |
| 23 | Edit visible within 1 s of the file landing on the device when the user presses Home (poll on resume), and within 3 s while the home screen is already showing (2 s poll + parse). Sync transfer time is not counted. |
| 24 | Deferred until drop. If the drop then produces a write, the hash check fails and B6 (`Use file` / `Keep mine`) decides (§6). |
| 25 | The drawer by default (`unsorted.mode: drawer`); an optional synthetic "Unsorted" page via `unsorted.mode: page`. New installs don't touch home pages. |
| 26 | No. Unsorted is derived at runtime and never written. Only an explicit user drag onto a page/dock/folder writes. |
| 27 | Re-map by package. An `app` item without `activity` follows the package's current default launcher activity. One with an `activity` that no longer exists falls back to the package's single launcher activity if there is exactly one (the file is left unchanged until the next edit), and is greyed if there are several. |
| 28 | Nothing launches. A hint sheet says why ("Not installed", "Shortcut no longer available", "Work apps are paused") with "Remove from layout"; "Open store page" comes later. |
| 29 | A symbolic `profile`: `personal` (default) or `work`. Never user IDs or serial numbers. Multiple work profiles: first one (rare). **Override in part:** coordinator said work apps are never written; instead work-profile items are written only if the user turns on "Include work apps in config" (default **off**). With it off, work apps show in the drawer with the work badge but can't be placed on pages, and nothing about them reaches the file. Reason: some users (maybe Christopher, see §14 Q6) will want them organized, and the opt-in keeps the safe default. |
| 30 | Paused: work items stay, shown greyed with the work badge; tap calls `UserManager.requestQuietModeEnabled(false, ...)` (the system unpause prompt). Removed: items greyed, kept in the file, "Remove from layout" offered. Never deleted automatically. |
| 31 | Never. Private-space apps are never written to the file, never shown in Unsorted, and never appear in status.json, whether locked or unlocked; they appear only in the system private-space section of the drawer while it is unlocked. The schema has no `private` profile value. |

## 16. Reconciling docs/ux.md (shape decision and AQ-1 to AQ-7)
**Canonical shape and file name: `launcher.json`, this repo's schema** ([`schema/launcher-config.v1.schema.json`](schema/launcher-config.v1.schema.json)). I took two things from Pragma's sketch because they are simpler for agents: **`dock` is a plain array**, and **grid coordinates are flat `x`/`y`** on page items (the schema originally nested them in `position`). I kept the rest of the schema shape for the reasons below.

**What docs/ux.md needs to change** (Pragma):
1. §1 example: `"schema": 1` → `"schemaVersion": 1`.
2. §1 example: `id` is a stable opaque ID, not the component. Apps carry `"package"` and optional `"activity"` (full class name, no leading-dot shorthand). E.g. `{ "id": "a-chrome", "type": "app", "package": "com.android.chrome", "x": 0, "y": 0 }`. Reason: the component changes (OQ-27), the same app can sit in two places (OQ-5), and drags and conflict replay refer to items by ID.
3. Pages need an `id` (`{ "id": "home", "items": [...] }`); `title` is optional.
4. Folders use `"label"`, not `"name"` (one field name for all display overrides). Folder children have no `x`/`y`.
5. `drawer.hidden[]` holds **package names** (`"com.example.bloat"`), not components: hiding is per app.
6. Work-profile apps use `"profile": "work"`, not an ID marker (AQ-5), and are written only when "Include work apps in config" is on. Private-space apps are never written (OQ-31).
7. §3 grid limits: 2–12 columns × 2–12 rows in the file (AQ-3). The UI may offer a narrower picker (3–6 × 4–7); that's a UX choice, not a validation rule.
8. §4.3 step 3: unknown keys are preserved only if prefixed `x-`; other unknown keys make the file invalid (OQ-4). Key order is canonical, not preserved (AQ-2).
9. §4.3 step 5 and everywhere `.history/` appears: history lives in **app-private storage, last 10** (OQ-16), not `.history/` in the folder. The folder gets `.configlauncher/` only (status.json, temp file, `broken/` and migration backups). The "Restore last working version" and onboarding "Start fresh" backups go to `.configlauncher/backup/` so the replaced file stays reachable from the PC.
10. §4.3 step 6: replace "temp file, then replace atomically" with the AQ-4 sequence.
11. §4.4 `Keep mine`: "replays my edits onto the file's version" rather than "write my version" (§6).
12. §2/§3: "Edit blocked" (`Fix launcher.json first…`) no longer applies: edits while invalid are allowed after the broken file is copied to `.configlauncher/broken/` (OQ-15). Replace that message with B2 plus a snackbar `Your broken launcher.json was saved to .configlauncher/broken/.`
13. Add `unsorted` (drawer/page/hidden) and `drawer.rules` / `drawer.sort` to the shape description; the starter layout uses `unsorted.mode: "drawer"`.

| AQ | Answer |
|---|---|
| AQ-1 | `launcher.json`, shape as above, JSON Schema 2020-12 published at `schema/launcher-config.v1.schema.json` with `schema/examples/`. |
| AQ-2 | Canonical reformat on every launcher write: 2-space indent, schema key order, `x-` keys after known keys, LF, no BOM. User formatting and key order are not preserved, and JSON has no comments (use `x-note`). The README and onboarding say so. Reloads never trigger a write (OQ-17). |
| AQ-3 | Errors, the whole file is rejected with last-good kept and B2 listing each problem: out-of-range or overlapping cells, grid outside 2–12. Skipping items would make the next write-back delete them. |
| AQ-4 | Same as OQ-18: write `.configlauncher/launcher.json.tmp`, read back and verify the hash, truncate-write `launcher.json` (`"wt"`), read back and verify, restore from temp on failure. Partial writes are caught by read-side parse/validation and by the 300 ms size/mtime stability re-check; last-good is the fallback. No SAF rename is relied on. |
| AQ-5 | Separate fields: opaque `id` (`^[a-z0-9][a-z0-9_-]{0,63}$`), `package`, optional `activity`, optional `profile` (`personal` default, `work` opt-in). No profile marker inside IDs; private space is never written. |
| AQ-6 | Hash check before writing (adopted). On mismatch, B6 with `Use file` / `Keep mine`; `Keep mine` replays my ops by ID onto the file, and the version that loses is snapshotted. `revision` isn't enough: sync tools and agents may not bump it, so the content hash is the conflict key. During a drag the incoming file is held until drop (OQ-24). |
| AQ-7 | App-private `filesDir/config/last-good.json`, written by temp + rename after every successful load and verified write; survives process death and reboots, not uninstall (OQ-13). Rendered at cold start before the folder read finishes. |

## 17. Resolved: test plan v2 and ux v2
Rulings on docs/test-plan.md v2 (#5) Q1-Q16 and docs/ux.md v2 (#6) AQ-8/AQ-9. These override earlier sections where they differ.

### Q16 (blocking): byte cap vs item cap. Ruling: **2 MiB read cap**, items stay at 2000, writer never emits more than the cap.
The math (canonical 2-space JSON, measured with a generator, 20 pages × 100 apps, 12×12 grid, deepest indentation):
- Short realistic strings (20-char package, 60 ASCII-char labels, 64-char IDs): **~663 KiB**. Grace's 301 KB measured shorter IDs/labels, so 256 KiB was never safe.
- Long legal strings (100-char package, 152-char activity, 60 emoji labels at 4 bytes each, 64-char IDs, `profile` written): **~1.46 MiB**.
So the coordinator's 1 MiB is **not** enough for a legal config with long labels; it fails before any `x-` keys or tags are added. 2 MiB leaves ~0.5 MiB for `x-` keys and tags on top of the long-string worst case. A fully theoretical maximum (every string at 256 chars, every tag filled, unlimited `x-` values) has no useful bound, so the cap is enforced as an invariant rather than proven: **the writer serializes, measures, and refuses to write anything over 2 MiB** (B5 `Couldn't save your change: launcher.json would be over 2 MB.`, edit kept in memory). The launcher therefore can never write a file it refuses to read. Parsing 2 MiB with kotlinx is tens of ms on a mid-range phone, and the cap is checked via `COLUMN_SIZE` and a streaming limit before reading, so OOM protection is unchanged. Compact JSON was rejected because it makes agent and git diffs unreadable. Schema: `tags` gets `maxItems: 16`, and `x-` values are documented as counted against the 2 MiB budget.

### Section 3 and 4 confirmations
- **(a) Missing `launcher.json`: confirmed, never auto-created.** B4 shows with an explicit create button. **Override on the label:** `Create from current layout` (Pragma's existing wording) instead of "Create starter file", because the usual cause is an external delete, and recreating from what's on screen (last-good) keeps the user's layout; the starter is used only when there is no last-good. First-run onboarding keeps its own "Create layout" step, which is explicit too.
- **(b) Drawer lists all apps: confirmed.** The drawer shows every launchable app in the personal profile (and the work profile, badged), **including apps already placed** on pages, the dock or in folders, minus packages in `drawer.hidden`. `drawer.hidden` hides from the drawer and from Unsorted only; a hidden app placed explicitly on a page still shows and launches there. Hidden apps stay reachable through Settings → Hidden apps (unhide = write). Private-space apps never come from the file: they are shown only in the system-style private-space section at the bottom of the drawer while it is unlocked, are not affected by `drawer.hidden`, are never counted as Unsorted, and can't be dragged out of that section (Q13).

### Q1-Q15
| Q | Ruling |
|---|---|
| Q1 | Yes: the schema adds `maxLength: 256` to `package`, `activity`, `packagePrefix`, `drawer.hidden[]` and `drawer.rules[].match.packages[]` (`shortcutId` already has it). The schema should state every per-field limit it can. |
| Q2 | Yes: the conformance test asserts "Kotlin rejects ⊇ schema rejects" on `invalid/`, both accept everything in `valid/`, and a named Kotlin-only list: duplicate IDs, overlapping cells, cells outside `grid`, the 2000-item total, duplicate JSON keys, parser depth, byte cap, NUL/invalid encoding. A fixture in that list must be accepted by the schema validator, so the list stays honest. |
| Q3 | Parse error (B1). The parser rejects `\u0000` and raw NUL in any string, matching OQ-11. |
| Q4 | Non-blocking: the folder write already succeeded, so the layout is saved. Show a one-time snackbar `Couldn't update the backup copy (phone storage full).`, log it in status.json, retry on the next successful load or write. No banner. |
| Q5 | Yes: every 5th foreground poll (~10 s) reads the file and compares hashes regardless of mtime/size, and every resume always hashes. Cost is one read of ≤2 MiB, usually a few KB. |
| Q6 | Yes: on cancel there are no pending ops, so `incoming` is applied as a normal reload with the usual `Layout updated from launcher.json.` snackbar. |
| Q7 | The B6 hash check comes first. If the file changed since we loaded it, the change is a new version: it is validated, and if valid, the broken-file path no longer applies (B6 prompt as usual). The broken-copy snapshot happens only when the file on disk is still the same broken bytes (hash unchanged) at write time. |
| Q8 | They join the pending queue and stay on screen; nothing is written until the user picks. `Use file` discards all of them, `Keep mine` replays all of them. The banner counts them (`2 changes`). |
| Q9 | Build a partial folder from the members that still exist and aren't already in another folder; if fewer than 2 remain, skip the folder and place the remaining member as a plain item. Report skips in the snackbar. |
| Q10 | Yes: a poll is one children query (plus a hash read every 5th), target ≤ 5 ms CPU each, < 1% battery per hour with the home screen on, no polling at all when home isn't visible. Grace measures with Battery Historian on one device. |
| Q11 | Yes, the drawer still lists them (see b). `hidden` only removes the synthetic Unsorted page. To keep an app out of the drawer, use `drawer.hidden`. |
| Q12 | Render them greyed with the work badge and the hint `Turn on "Include work apps in config" to use this.`; not invalid, never deleted. Turning the setting on makes them live without a write. |
| Q13 | Blocked in the UI: private-space apps can't be dragged to pages, the dock or folders; long-press offers only the system actions. |
| Q14 | Commit `status.json`? No. The README should recommend gitignoring `.configlauncher/tmp`-style churn: ignore `.configlauncher/launcher.json.tmp` and `.configlauncher/status.json`; commit or ignore `backup/` and `broken/` by preference (git already has history, so ignoring is fine). Pragma adds a sample `.gitignore` snippet. |
| Q15 | Ship as best effort, with a soft warning in onboarding when the picked tree's authority is a cloud provider (not `com.android.externalstorage.documents`): `Cloud folders can take a while to sync. A Syncthing or git folder works best.` with `Use anyway`. Not blocked. |

### AQ-8 and AQ-9
- **AQ-8: yes.** Two distinct states: **B3** (persisted grant missing from `persistedUriPermissions`, or the tree query throws `SecurityException`/`FileNotFoundException` for the tree itself) shows `Reconnect` only, since we can't create anything there. **B4** (tree readable, no `launcher.json` child) shows `Create from current layout` and `Reconnect` (picked the wrong folder, or the sync client hasn't delivered the file yet). B4 is never auto-dismissed by our own create; it clears when a valid file appears.
- **AQ-9:** B8 is a notice (lowest priority: shown only when no B1-B6 banner is up, and below them in the stack). Copy: `launcher.json uses an older format (v{N}). It will be upgraded on your next change.` Actions `Upgrade now` and `✕`. `Upgrade now` writes immediately, after saving the original to `.configlauncher/backup/launcher.v{N}.{timestamp}.json`; it goes through the normal write path (hash check, B6 on conflict). This is consistent with §3 rule 3, which already allows an explicit upgrade action. `✕` dismisses B8 for that file hash (stored in DataStore), so it returns only if a different older-format file arrives; the next edit still upgrades with a backup. Rename the action from `Upgrade file` to `Upgrade now` since the body already says "launcher.json".

### Follow-ups
**Sprite (code):**
1. Read cap 256 KiB → 2 MiB (pre-read size check and streaming limit); writer measures output and refuses over 2 MiB with B5 (Q16). Update `CanonicalSizeTest` to assert the 2000-item long-string config is under the cap.
2. Kotlin validator: 256-char limits on package/activity/packagePrefix/hidden/match.packages; `tags` ≤ 16 (Q1). NUL rejected in the parser (Q3).
3. Poller: hash every 5th poll and on resume (Q5).
4. Store: B6 check before the broken-copy snapshot (Q7); edits during B6 join the queue (Q8); partial-folder replay (Q9); drag-cancel applies `incoming` (Q6); last-good write failure is a snackbar plus status.json entry (Q4).
5. Drawer: all apps including placed ones minus `drawer.hidden`; `unsorted.mode: hidden` only removes the Unsorted page (b, Q11); private-space section not draggable (Q13); work items greyed when the opt-in is off (Q12).
6. B3 vs B4 detection and actions (AQ-8); B8 `Upgrade now` with backup, notice priority, dismiss per hash (AQ-9); never auto-create on B4 (a).
7. Onboarding cloud-provider warning by tree authority (Q15).

**Pragma (docs):** README line 98 and ux.md B1 "over 256 KiB" → 2 MiB; B4 adds `Reconnect`; B8 copy and `Upgrade now`; B5 adds the over-2 MB message; Q4 snackbar, Q12 hint and Q15 warning copy; drawer section states "includes placed apps"; README `.gitignore` snippet (Q14).

**Grace (QA):** update HC-7 and the size fixtures to 2 MiB (just-under / just-over), add a 2000-item long-string valid fixture (~1.46 MiB) and a writer-refusal case; conformance test with the Kotlin-only list (Q2); NUL fixture as B1 (Q3); constant-mtime provider case (Q5); Q6-Q9 rows in the conflict matrix; battery/poll measurement (Q10); B3 vs B4 and B8 dismiss-per-hash cases; drawer-with-placed-apps and private-space drag-blocked cases.

## 18. Christopher's answers, SDK levels and minSdk ruling (2026-10-18)
These override earlier sections where they differ.

### 18.1 Answers to §14 and docs/ux.md Q-C
| Question | Answer | Effect on the design |
|---|---|---|
| §14 Q8 devices | Daily phone **Galaxy S25 Ultra**; tests first on a **Galaxy A53**; also A51, S8+, Galaxy Tab S2, OnePlus 2, and a modded OnePlus One as wall displays. | minSdk 29 (§18.3). QA order: A53 → S25 Ultra → A51. The other devices are out of scope (test-only, and only if a custom ROM gives them Android 10+). |
| §14 Q1 sync tool | **git is the primary sync target.** | Grace tests a git client folder first; Syncthing and Drive second. README leads with git. |
| §14 Q2 / Q-C5 | **JSON only is OK.** | No change. |
| §14 Q3 / Q-C3 | **Unsorted apps go in the drawer only.** | Unplaced apps live only in the drawer; `unsorted` is accepted and ignored (§18.5 AF-2). |
| §14 Q4 conflicts | **The file wins by default, except items the user just touched.** | §18.2: automatic ID-based replay, no prompt by default. Replaces the always-on B6 prompt from §6/§16 AQ-6. |
| §14 Q5 | **status.json and backups in the folder: yes.** | No change (`.configlauncher/`). |
| Q-C1 | **Add "Skip for now" with app-only storage.** | §18.2: app-private source. |
| Q-C2 | **The drawer opens with the keyboard up.** | Drawer search field focused on open, IME shown (§18.2). |
| Q-C4 | **Label backdrop: wanted if easy; flag if costly.** | Cheap: one boolean setting drawing a rounded 40 % black scrim behind each home label (a `Modifier.background` on the label). In v1 settings as `Label backdrop`, off by default. Stored in DataStore, not in launcher.json. Not costly, so no flag. |
| Q-C6 | **10 history copies is fine.** | No change. |
| §14 Q9 widgets | **Critical to the end vision, not a v1 gate.** | Stay in Later, but widgets are the first post-MVP item. The schema v2 plan (`widget` type, `w`/`h`) should not be designed around anything that blocks them. |
| §14 Q7 distribution | **GitHub Releases for now.** | Signed release APK built by CI and attached to a Release; no Play/F-Droid work. Releases are cut by Grok Bot or Christopher, not this doc (no tags from agents). |
| §14 Q6 profiles | Not answered. | Keep the §15 OQ-29 defaults (work opt-in off, private never written). |

### 18.2 Store design changes
**"Skip for now" (app-only storage).** Specified in full by §18.5 AF-1, which supersedes the earlier draft here. In short: an app-private `launcher.json` is the source of truth, written with a true temp-then-rename; no verify step, no polling, no `status.json` and no `.configlauncher/` folder; a persisted "no folder" state; and the connect-later flow from docs/ux.md §7 (#20).

**Conflict default: the file wins, except items the user just touched.** Replaces the always-on B6 prompt.
- On a hash mismatch at write time, or an incoming file with pending ops, the launcher **automatically** replays the pending ops by ID onto the file (the §6 replay rules, Q9 partial folders) and writes the result. No prompt.
- "Just touched" means exactly the items targeted by pending ops (ops not yet written). Everything else comes from the file. Once an op is written it is no longer special.
- Feedback: snackbar `Layout updated from launcher.json. Kept your 2 changes.` with `Undo` (Undo writes the file's version back, i.e. "Use file"). Skipped ops use the existing skip snackbar.
- **Undo safety rule (2026-10-18, from Grace's Q-v3-6 / CM-6 and Sprite's #30).** Undo is a write like any other and goes through the normal write path: hash check first, never a blind write-back. At merge time the engine records three things: the pre-merge file bytes (`undoBytes`), the **hash it wrote** (`mergedHash`), and the **IDs the replay touched** with their file-side state (the items targeted by the replayed ops). On Undo:
  1. Re-read `launcher.json` and hash it.
  2. **Unchanged** (hash == `mergedHash`): write `undoBytes` back through `writeLocked` (self-write guard, read-back verify), i.e. a normal "Use file".
  3. **Changed since the merge** (git pull, agent edit, sync): don't overwrite. Build the inverse ops for the touched IDs only (move back to the file-side cell or container, remove items the merge added, restore items it removed, restore labels) and replay them by ID onto the **new** file, with the §6 rules. Write the result through the normal path.
  4. If any inverse op can't apply cleanly (the touched item was removed, moved or edited in the new file, or its target cell is now taken), **cancel the whole Undo**: write nothing, the new file stays, snackbar `File changed again, Undo cancelled.` Partial undos aren't allowed.
  5. Always, before any Undo write: the current model and the pre-merge file go to history (the merge already snapshotted both, and that stays), so `Restore previous layout` can recover anything.
  6. Undo is one-shot. It expires when the snackbar is dismissed or times out (10 s), or when a further local edit is written. An external reload during the snackbar doesn't expire it; it just takes the step 3 path.
- The same rule applies to B6 `Use file` when it's tapped after the file changed again: re-check the hash, and load the newest file instead of an old copy.
- Both pre-merge versions (the file's and our local model) go to history, as before.
- Setting `Ask before merging` (off by default) restores the B6 `Use file` / `Keep mine` prompt for users who want it.
- If replay fails validation (shouldn't happen), fall back to B6 regardless of the setting.

**Drawer keyboard (Q-C2).** Opening the drawer focuses the search field and shows the keyboard. Swipe-down or Back with the keyboard up hides the keyboard first, then a second Back closes the drawer. On the wall-display devices (no hardware keyboard, rarely used for search) the user can turn off `Open keyboard with drawer` (default on).

### 18.3 minSdk ruling: **29** (supersedes the API 23 ruling, 2026-10-18)
Christopher clarified that the old devices are for testing only, not targets. minSdk 23 was not near-free: about 8 API checks, an extra emulator and dedicated performance work for 2015 hardware. **minSdk 29 (Android 10)**; compileSdk 37 and targetSdk 35 unchanged. (The dependency floor is still 23, so 29 is our choice, not a constraint.)

| Device | Latest official Android (API) | In scope at 29? |
|---|---|---|
| Galaxy S25 Ultra (daily) | 16 (36) | **Yes**, full features incl. private space |
| Galaxy A53 (first test device) | 16 (36) | **Yes**, full features |
| Galaxy A51 | 13 (33) | **Yes**, all but private space |
| Galaxy S8+ | 9 (28) | No (unless a custom ROM gives Android 10+) |
| Galaxy Tab S2 | 7.0 (24) | No (unless a custom ROM gives Android 10+) |
| OnePlus 2 | 6.0.1 (23) | No (unless a custom ROM gives Android 10+) |
| OnePlus One (modded) | Stock 6.0.1 (23) | Only if its custom ROM is Android 10+ |

**What this removes:** the RoleManager fallback (`ACTION_HOME_SETTINGS` / `ACTION_SETTINGS` and the "Open Apps → Default apps → Home app" hint), the quiet-mode request check (28) and `isQuietModeEnabled` check (24), the shortcut and pin-request checks (25/26) and the "Shortcuts need Android 8" hint, the adaptive-icon check (26), the `getProfiles()` fallback (26), and the "Show internal storage" picker hint. **The only version gate left is private space (35)**, already in code (`getLauncherUserInfo`). Dynamic colour (31) and themed icons (33) get gates only if those features are added.

**Also removed:** the API 23-28 code-gate list, the 1.5 s OnePlus 2 cold-start target and the Tab S2 drawer target, and the API 23 and 26 emulators.

**Testing:** Robolectric at `@Config(sdk = [29, 35])`; emulators API 29, 35 and 36; manual on A53 (primary), S25 Ultra, and A51 when convenient. Lint `NewApi` stays an error. R8-minified release builds and a baseline profile remain normal practice, with no special old-hardware budget.

**Sprite:** set `minSdk = 29` in `app/build.gradle.kts`, delete any 23-28 gates already added, and keep the 35 private-space gate.

### 18.4 Copy rulings on docs/ux.md
- **Q15 cloud warning, `Choose another folder`: approved as written** (`Use anyway` · `Choose another folder`, reopening the picker).
- **Q9 folder message: replace.** The current text says the folder "was skipped because its apps were removed in the file", which is wrong when a member was *moved* into another folder, and it doesn't mention the survivor that gets placed alone. Exact replacement:
  - One member left: `Folder "<Label>" wasn't recreated because its other apps were removed or moved in the file. "<App>" was placed on its own.`
  - None left: `Folder "<Label>" wasn't recreated because its apps were removed or moved in the file.`
  - Both with `[Undo]`. The trigger becomes "an automatic merge (or `Keep mine`) skipped a folder", per §18.2.

### 18.5 AF-1 and AF-2 (from docs/ux.md #20), accepted
**AF-1: app-only mode.**
- With no folder connected, the source of truth is **`filesDir/config/source/launcher.json`** in app-private storage. It uses the same parser, validator, canonical serializer, last-good copy and history (10) as folder mode.
- **Writes are truly atomic:** write `launcher.json.tmp` in the same directory, `fsync`, then `File.renameTo` over the target (same filesystem, so atomic on ext4/f2fs). There's **no temp-file read-back verify step**: that step exists only because SAF can't rename.
- **No polling, no `status.json`, no `.configlauncher/` folder** (no backup/ or broken/ either; migration and restore backups go to app-private history). Nothing external writes there, so a **FileObserver is optional** (useful only for `adb` edits on debug builds); the store reloads after its own writes without one, through the in-memory model.
- Banners B3, B4 and B6 can't occur; B1/B2 only if the file was corrupted (then last-good); B5 on disk-full; B8 on an older format.
- **Persisted "no folder" state:** DataStore stores `source = APP_ONLY` (distinct from "never onboarded"), so onboarding doesn't show again, and the only reminder is ux.md's one-time hint chip.
- **Connecting a folder later** follows ux.md §7 exactly (#20): with no file there, the current layout is written; with an existing file, the user chooses between the folder's layout and replacing it with a backup first. **History and last-good carry over unchanged** (they already live in `filesDir/config/`), and the app-private source file becomes the last-good copy. After connecting, folder mode applies in full (polling, banners, `status.json`, `.configlauncher/`).
- This replaces the earlier §18.2 draft, which put `.configlauncher/` under the app-private directory.

**AF-2: `unsorted` accepted and ignored in v1.**
- `unsorted.mode` and `unsorted.pageTitle` stay in schema v1 with **no version bump**. "Ignored" means they're validated for type (enum and maxLength as today, so a typo is still an error) and then not acted on: unplaced apps always live in the drawer, whatever the mode. The launcher never writes `unsorted`, so a write-back drops it. That's acceptable because the field has no effect.
- The schema description marks both fields **deprecated** (also `"deprecated": true`). Removing them is a **schema v2 change** with a `migrate1to2` that deletes the object (§3 rules).
- Superseded: §3 Unsorted modes, §15 OQ-25 and Q11's mention of the Unsorted page, §12's "Unsorted modes" MVP item (now: drawer only), and the minimal example (which no longer includes `unsorted`).
- Sprite: drop the Unsorted-page code path if one exists; the validator keeps type-checking `unsorted`; the codec doesn't write it.

### 18.6 Resolved: test plan v3 (#26) Q-v3
| Q | Ruling |
|---|---|
| Q-v3-1 | `Use phone storage` **overwrites** the app-private source with the current on-screen layout (temp + rename), after snapshotting the previous app-private version to history. "Copies" in §18.2 meant this. Folder → phone → folder then follows the ux.md §7 connect-later flow, so nothing is lost on either side. |
| Q-v3-2 | Always **focus** the search field. Request the on-screen keyboard only when there's no hardware keyboard: `resources.configuration.hardKeyboardHidden != HARDKEYBOARDHIDDEN_NO`. With a hardware keyboard or DeX, typing goes straight to search and no IME is forced. The `Open keyboard with drawer` setting then only controls focus. |
| Q-v3-3 | Home-page grid labels only, including the label under a folder icon on the grid. Not inside the open folder sheet (it has its own opaque surface), not in the drawer, and not the dock (no labels). |
| Q-v3-4 | Best effort, and the real work profile wins. When choosing the work profile, skip any managed profile whose launchable activities include Samsung's Secure Folder app (`com.samsung.knox.securefolder`). If more than one managed profile remains, take the first. A detected Secure Folder profile is treated like private space: never written, not in Unsorted, not draggable, and listed only if it shows up in our activity list at all. The package heuristic is *unverified*; Grace confirms it on the A51 (Android 13) and records what a S25/A53 on One UI 8 shows. If detection fails, the fallback is OQ-29's "first managed profile", and the "Include work apps in config" opt-in (default off) keeps it out of the file anyway. |
| Q-v3-6 | Undo re-checks the hash. If the file changed, it reverts only the merged items by ID onto the new file, or cancels with `File changed again, Undo cancelled.` It never writes the old file blindly. See the Undo safety rule in §18.2. |
| Q-v3-9 | Done: §18.3 says minSdk 29 (merged in #32), and the rest of §18 mentions 23 only in the superseded note and device table. Sprite's #31 (minSdk 23 with API checks) is superseded: close it or re-scope it to `minSdk = 29` plus the 35 private-space gate and the lint `NewApi` CI step. |

## 19. Design proposals (not yet accepted)
- **Config-driven title bar (#79):** [docs/design/title-bar.md](docs/design/title-bar.md). The first instance of a general element model (`id`, `type`, placement, `content`, `style`) in a root `elements[]` array, with optional per-page overrides; widgets reuse it later. Stays on `schemaVersion` 1 through a forward-compatibility rule (unknown element types are skipped with a warning; unknown keys inside known types are errors; a `requires` feature level). Pragma's ux.md §15 slots (#80) are the default preset. Nothing changes in `schema/` until it's accepted.

## 20. Agent onboarding (#76)
Docs side done: [`templates/config-folder/AGENTS.md`](templates/config-folder/AGENTS.md) (the guidance that travels with `launcher.json`), [`skills/config-launcher/SKILL.md`](skills/config-launcher/SKILL.md), a contributor [`AGENTS.md`](AGENTS.md), and README "Canonical details" and "Agent kit" sections closing #76's four gaps. No MCP server, by design: the file is the API (#76 point 4, agreed).

**Specified for Sprite (not implemented):**
1. **Write `AGENTS.md` into the folder** once, when a folder is chosen or connected, if the setting *Add agent instructions to folder* is on (default on; mentioned on the first-run folder step). Content = the template above, bundled as an asset.
   - Never overwrite a user-changed file: store the SHA-256 of each shipped version; replace `AGENTS.md` only if its current hash equals a shipped hash (then update it to the new version). If it differs, leave it alone. If it's absent after the first write, don't recreate it (the user deleted it on purpose); the setting's *Add again* action rewrites it.
   - Goes through the normal SAF write path (temp + verify). Not in app-only mode.
   - It's meant to be committed, so it's not in the suggested `.gitignore`.
2. **Optional offline copies:** write `.configlauncher/launcher-config.v1.schema.json` (bundled asset) on the same trigger and on app updates, so agents can validate without network. `validate.py` stays a download from the repo (it isn't the launcher's to ship into a user's folder).
3. **Canonical drawer rules:** the codec currently passes `drawer.rules` through as raw JSON, so key order stays as written. Emit `id`, `group`, `match` and inside `match` `packages`, `packagePrefix`, `tag`, `category`, in both the Kotlin codec and `validate.py --format`.
4. **Generated IDs** for folders, shortcuts, pages and rules should follow the README convention (`f-`, `s-`, readable or `p-` pages, `r-`), like apps already do (`a-`).
5. **Schema tidy-up (later, with the next schema change):** reorder `$defs/drawerRule.required` to `[id, group, match]` to match `properties`. Not done now because schema/ is frozen during the rework.
