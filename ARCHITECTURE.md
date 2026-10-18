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
| minSdk | **26** (Android 8.0) | Pinned shortcuts (`LauncherApps.PinItemRequest`, `ShortcutManager.requestPinShortcut`) and `ACTION_CONFIRM_PIN_SHORTCUT` arrive in 26; adaptive icons arrive in 26; persisted SAF permissions and `DocumentsContract` work fine from 21 but 26 removes the need for a second shortcut code path (legacy `INSTALL_SHORTCUT` broadcasts). Below 26 is <1% of active devices and Christopher's phone is far newer. |
| target/compile | **35** | Current Play requirement baseline; 35 brings edge-to-edge by default, which a launcher wants anyway. Move to 36 when the toolchain in the scaffold supports it; nothing here depends on 36. |
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
**Folder selection.** First run (or Settings → "Config folder") launches `ACTION_OPEN_DOCUMENT_TREE`. We call `takePersistableUriPermission(READ|WRITE)` and store the tree URI in DataStore. On every start we check `contentResolver.persistedUriPermissions`; if the grant is gone, show the "Reconnect folder" banner and keep rendering last-good. If the folder has no `launcher.json`, offer "Create from current layout" (seeded with dock defaults and everything else Unsorted).

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
1. Read bytes (cap 256 KiB, checked via `COLUMN_SIZE` before reading and enforced while streaming; larger is rejected, see §15 OQ-9).
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

Why `Keep mine` replays instead of overwriting (a small deviation from ux.md's "write my version"): an agent's bulk reorganization and the user's one drag usually touch different items, so replay keeps both; a whole-file overwrite would silently erase the agent's work, which `.history` could recover but the user wouldn't know to look for. **Departs from spike:** the spike's last-writer-wins plus counter is rejected for the same reason; `revision` is a hint, not the conflict key.

Every merge or discarded edit writes the pre-merge local model to `filesDir/config/history/` so a "Restore previous layout" action in Settings can recover anything.

## 7. Model and app reconciliation
- `model`: immutable data classes (`LauncherConfig`, `Page`, `Item` sealed: `AppItem`, `ShortcutItem`, `FolderItem`, `DrawerRule`…) plus `x-` extras as `JsonObject`. Pure, no Android imports, JVM-testable.
- `apps`: `LauncherApps.getActivityList(null, user)` for each profile from `LauncherApps.getProfiles()` (API 26+), `LauncherApps.Callback` for install/remove/change, `getShortcuts` for shortcut metadata, icons via `LauncherActivityInfo.getBadgedIcon` cached in memory (LRU) keyed by component+user.
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

**Default home role.** On API 29+ use `RoleManager.createRequestRoleIntent(ROLE_HOME)`; on 26-28 open `Settings.ACTION_HOME_SETTINGS`. Onboarding shows a "Set as Home" step, and the app works (as a normal activity) without the role, minus shortcuts. `MainActivity` declares `MAIN` + `HOME` + `DEFAULT`, `launchMode=singleTask`, `stateNotNeeded`, `excludeFromRecents`, and handles `onNewIntent` (Home pressed while on home → go to page 0 / close drawer).

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
- **Instrumented tests (emulator API 26 and 35):** real SAF via a test `DocumentsProvider` in the androidTest APK; LauncherApps listing; pin-shortcut flow; Compose UI tests for drag-drop and banners.
- **Manual QA matrix (Grace):** Syncthing-synced folder edited from a PC, git client folder (e.g. MGit), a cloud provider (Google Drive) to document the expected degraded behaviour, work profile, private space on 35, Pixel + one OEM (Samsung).
- **Performance:** startup to first frame with a 300-item config < 500 ms on a mid device; poll cost measured.

## 12. MVP scope versus later
**MVP (v0.1):**
- Home role + onboarding (folder pick, set as Home).
- Pages (pager), dock, drawer (A-Z, hidden list, rule groups), folders (one level), Unsorted modes.
- App and pinned-shortcut items, personal + work profiles.
- SAF folder store: poll + observer, validate, atomic model swap, last-good, history (10), status.json.
- Write-back for move, add from drawer, remove, rename, create/merge folder; debounce; self-write guard; op-replay merge.
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
| Startup cost on large configs | Render last-good first; icons loaded lazily; 256 KiB cap. |
| Two validators drifting | Conformance test (§11). |
| GPL if Kvaesitso code is copied | Reference only; copying code makes the app GPL-3, which conflicts with this repo's MIT license. **Departs from spike** (it says GPL is "fine"): it is fine only if we relicense, so we don't copy. |
| OEM launcher quirks (home role resets, Samsung) | Manual QA on one OEM device. |

## 14. Open questions for Christopher
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
| 9 | 256 KiB file (refused before full read via `COLUMN_SIZE` and a streaming cap), JSON nesting depth 8 at the parser and folder depth 1 in the model (see 10), 2000 items total, 20 pages, 144 items per page, labels 60 chars, IDs 64 chars, other strings 256. **Override:** coordinator said depth 4; the legal v1 structure (root, pages, page, items, folder, items, item) already needs 7 JSON levels, so the parser limit is 8 (one spare) and the *folder* nesting limit is stricter than 4. The parser counts depth itself, so deep input fails fast without StackOverflowError. |
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
