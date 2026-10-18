# Test plan: config-launcher

Source of truth: [`docs/research-spike.md`](research-spike.md) ("spike"). References use spike section numbers, e.g. **§4 Inbound**. Where the spike does not decide something, the case is marked **[OQ-n]** and the question is listed in *Open questions*; expected results there are left blank on purpose rather than invented.

Test levels: **U** = JVM unit test, **R** = Robolectric (JVM, Android framework shims), **I** = instrumented (emulator/device), **M** = manual on a real phone.

There is no code yet. Class and function names are not assumed; cases describe behaviour.

## 1. Schema validation (§3 Always, §4 Minimal architecture)

| ID | Steps | Expected | Level | Ref |
|---|---|---|---|---|
| SV-1 | Load a minimal valid config: one page, one `app` item, empty dock, empty `drawer.rules`. | Parses into the model; the UI renders it; no banner. | U, R | §4 |
| SV-2 | Validate every example config in the repo against the published JSON Schema. | All pass. Schema and parser agree (a schema-valid file never fails parsing, and vice versa). | U | §4 "Publish a JSON Schema" |
| SV-3 | Config with each item `type` (app, shortcut, widget, folder, text). | All parse. MVP excludes widgets: what a `widget` item does in v1 is [OQ-1]. | U | §4, §4 MVP |
| SV-4 | Unknown `type`, e.g. `"type": "intent"`. | Rejected by validation; old model kept; banner shown. | U, R | §3 Always |
| SV-5 | Missing required field (no `id`, no `pages`). | Rejected; old model kept; banner names the path. | U | §4 Inbound |
| SV-6 | Wrong types (`x: "1"`, `w: -1`, `pages: {}`). | Rejected. Whether negative/zero/overlapping x,y,w,h are errors or are clamped is [OQ-2]. | U | §4 |
| SV-7 | Schema `version` missing, older, or newer than supported. | Behaviour [OQ-3] (migration vs refuse). Must never silently drop data. | U | §4 "versioned schema" |
| SV-8 | Unknown extra keys. | [OQ-4] reject vs ignore vs preserve on write-back. If preserved, a round trip keeps them. | U | §4 Outbound |
| SV-9 | Duplicate item ids on one page / same app in dock and page. | [OQ-5]. | U | §4 |
| SV-10 | File format: JSON vs YAML vs JSON5. | Tests target whichever format is chosen [OQ-6]; if YAML, include comment/key-order round trip (§4 Risks). | U | §4, Risks |

## 2. Malformed and hostile configs (§3 Always)

Principle under test: **config content is data, never code.** The only thing a config may cause to launch is an installed app/shortcut via LauncherApps (§3).

| ID | Steps | Expected | Level | Ref |
|---|---|---|---|---|
| HC-1 | Truncated JSON, trailing garbage, empty file, 0-byte file, binary/random bytes, BOM-prefixed file. | Parse error; last-good model kept; banner; no crash. BOM accept/reject [OQ-7]. | U, R | §4 Inbound |
| HC-2 | Item `id` that is an intent URI (`intent:#Intent;action=android.intent.action.CALL;...`), `http://`, `content://`, `file://`, `javascript:`, `tel:`, `market://`. | Rejected at validation; tapping is impossible because it never reaches the model. Assert no `startActivity`/`Intent.parseUri` is called (Robolectric `ShadowApplication` shows no started activity). | U, R | §3 "no arbitrary intents" |
| HC-3 | Fields named `action`, `extras`, `uri`, `script`, `cmd`, `exec` on any item. | Rejected or ignored (see OQ-4); never used to build an Intent. | U, R | §3 |
| HC-4 | `id` with a non-existent but well-formed package (`com.example.doesnotexist/.Main`). | Not launched; item shown greyed out per reconciliation; tapping does nothing harmful. | R | §3 "refuse unknown package names", §4 Reconciliation |
| HC-5 | Malformed package names: empty, `..`, spaces, `/`, `com..x`, 1000-char name, uppercase/unicode, activity in a different package than declared. | Rejected at validation; no lookup that could throw uncaught. Activity must belong to the declared package and be exported/launchable via LauncherApps. | U, R | §3 |
| HC-6 | Shortcut item whose shortcut id is not published by that package. | Not launched; greyed out or rejected [OQ-8]. | R | §3, §4 |
| HC-7 | File size limit: 1 MB, 10 MB, 100 MB file. | Files above the limit are refused before full read (no OOM); last-good kept; banner. Limit value [OQ-9]. | U, R | §3, Risks "large configs" |
| HC-8 | Nesting depth: folders nested 10, 1 000, 100 000 deep; JSON arrays nested 100 000 deep. | No StackOverflowError; rejected above the depth limit [OQ-9]. Whether folders can nest at all [OQ-10]. | U | §4 |
| HC-9 | Item count: 10 000 items on one page, 1 000 pages. | Refused above limit or rendered without ANR; load time measured. | U, I | Risks |
| HC-10 | Huge strings: 1 MB `text` item, 1 MB label. | Refused above string limit or truncated on render; no layout freeze. | U, R | §3 |
| HC-11 | Unicode: emoji, RTL override (U+202E), zero-width joiners, combining-mark stacks ("zalgo"), lone surrogates, NUL, NFC vs NFD duplicates of the same label. | Valid unicode renders without crash; invalid encoding (lone surrogate, invalid UTF-8) → parse error. RTL override in labels must not visually disguise a different app [OQ-11]. | U, R | §3 |
| HC-12 | Numeric edge values: `1e309`, `-0`, `NaN` literal, 2^63 integers. | Rejected or clamped; no overflow in layout math. | U | §4 |
| HC-13 | Duplicate JSON keys in one object. | Deterministic: reject [OQ-12]. | U | — |
| HC-14 | Symlink / path traversal in any file reference (if config ever references files, e.g. icons). | Out of MVP (icon packs excluded); if added, confined to the SAF tree. | — | §4 MVP |
| HC-15 | Fuzz: run the parser+validator over a corpus of mutated valid configs (e.g. 10 000 iterations). | Only "valid" or a typed validation error; never an uncaught exception or a launch. | U | §3 |

## 3. Last-good fallback (§4 Inbound, §3 "keep the last N versions")

| ID | Steps | Expected | Level | Ref |
|---|---|---|---|---|
| LG-1 | Running with valid config A; replace file with invalid content. | Model stays A; error banner with reason; file on disk **not** overwritten by the launcher. | R | §4 Inbound |
| LG-2 | After LG-1, fix the file to valid config B. | Model becomes B; banner clears. | R | §4 |
| LG-3 | Cold start (process death) with an invalid file on disk. | Launcher starts using last-good (persisted copy) rather than an empty screen. Where last-good is persisted [OQ-13]. | R, I | §4, Risks "Process death" |
| LG-4 | Cold start, first ever run, invalid file, no last-good. | Usable fallback (e.g. drawer with all apps) plus banner; never a crash loop. [OQ-14]. | R, I | — |
| LG-5 | While invalid, user drags an icon on the phone. | [OQ-15]: does write-back overwrite the user's broken file (losing agent work), get blocked, or write elsewhere? Must not silently discard the broken file without a snapshot. | R | §4 Outbound, Conflicts |
| LG-6 | Write N+1 successive valid versions. | Only the last N snapshots kept in `.history/`; oldest pruned; each snapshot is valid. N [OQ-16]. | U, R | §3, §4 Conflicts |
| LG-7 | SAF grant revoked or folder deleted. | Last-good kept; banner prompts to re-pick folder; no crash. | R, M | §3 option 1 |

## 4. Self-write guard (§4 Outbound "content hash to suppress echo reloads")

| ID | Steps | Expected | Level | Ref |
|---|---|---|---|---|
| SW-1 | Launcher writes the file after a drag; observer fires. | Hash matches the last self-written hash → no reload, no recomposition, no second write. Assert exactly one write and zero reloads. | R | §4 Outbound |
| SW-2 | Repeat SW-1 for 100 rapid drags. | Writes ≤ 100 (debounced allowed); reloads triggered by own writes = 0; no ping-pong. | R | §4 |
| SW-3 | External edit produces content byte-identical to the last self write. | No reload (harmless). | U | §4 |
| SW-4 | External edit that differs only in whitespace/key order. | Reloads (hash differs) and the model is equal → no UI change, and **no** write-back triggered by the reload. Whether the launcher re-canonicalises the file is [OQ-17]. | R | §4, Risks "canonical formatter" |
| SW-5 | Self write, then external edit arrives within the debounce window. | External edit is not suppressed by the guard (hash differs). | R | §4 |
| SW-6 | Sync tool (Syncthing/git) rewrites the file with same content after the self write (touch/mtime change). | No reload. | R, M | §3 option 1 |

## 5. Concurrent edits, conflicts, atomic writes (§4 Conflicts, §3 "write temp then rename", Risks "Write races")

| ID | Steps | Expected | Level | Ref |
|---|---|---|---|---|
| CE-1 | Inspect every write path. | Write to temp in the same directory, then rename/replace. Kill the process (simulated exception) between temp write and rename → original file intact. | U, R | §3 |
| CE-2 | SAF reality check: does the chosen provider support atomic rename (`DocumentsContract.renameDocument`)? | If not, the fallback strategy must still never leave a partially written primary file [OQ-18]. | I, M | §3, Risks "SAF quirks" |
| CE-3 | Reader observes the file mid-write by an external writer (partial content). | Parse fails → last-good kept; when the write completes, reload succeeds. No partial apply. | R | §4 Inbound |
| CE-4 | Version counter: agent writes version 7 while the phone holds version 6 and drags. | Last-writer-wins per spike; counter increments monotonically. Exact rule for who wins and whether the losing edit is snapshotted [OQ-19]. | U, R | §4 Conflicts |
| CE-5 | Phone drag → write v7; agent's stale copy (based on v6) is synced over it. | Per LWW the agent's file wins; the phone's v7 must exist in `.history/` (or git) so it can be recovered. Whether a stale version (lower counter) is rejected instead [OQ-19]. | R | §4 Conflicts |
| CE-6 | Agent omits or doesn't bump the counter. | [OQ-20]: auto-bump, accept, or reject. | U | §4 |
| CE-7 | Syncthing conflict file (`layout.sync-conflict-*.json`) appears in the folder. | Ignored by the loader (only the configured filename is read); optionally surfaced [OQ-21]. | R, M | §3 option 1 |
| CE-8 | Two rapid external writes within the debounce window. | Only the final content is applied. Debounce value [OQ-22]. | R | §4 "debounced file" |
| CE-9 | Phone edit while a reload is in progress. | Edit is applied to the newest model, not a stale one, or rejected with a visible message; never a lost update without snapshot. | R | Risks |

## 6. Live reload (§4 Inbound, §1 OhmLauncher hot reload, Risks "SAF observation latency")

| ID | Steps | Expected | Level | Ref |
|---|---|---|---|---|
| LR-1 | Edit the file externally (move an app to page 2). | UI updates without restarting the launcher. | R, I, M | §4 |
| LR-2 | Measure edit→visible latency via ContentObserver and via polling fallback. | Within target [OQ-23]. Note: FileObserver doesn't work on SAF URIs; test the actual mechanism. | I, M | §3 option 1 |
| LR-3 | Launcher backgrounded (another app in front) during the edit. | Change visible on return to home at the latest. | I, M | — |
| LR-4 | Reload while user is mid-drag. | Drag not corrupted; [OQ-24] whether reload is deferred until drop. | I | Risks |
| LR-5 | Device reboot / cold start after an external edit while off. | New content loaded on start. | I, M | — |
| LR-6 | Config renamed, replaced by a new file (new document id) as git checkout does. | Still detected. | I, M | §3 option 1 |

## 7. New and uninstalled apps → Unsorted (§4 Reconciliation, §3 LauncherApps callbacks)

| ID | Steps | Expected | Level | Ref |
|---|---|---|---|---|
| UN-1 | Install an app not in the config. | Appears on the Unsorted page or in the drawer (spike says either) [OQ-25]. | R (ShadowLauncherApps / callback), I, M | §4 |
| UN-2 | Is the Unsorted placement written back to the file? | [OQ-26]. If written, it triggers no echo reload (SW-1). | R | §4 |
| UN-3 | Uninstall an app that is on a page and in the dock. | Entries greyed out, **not** deleted; file unchanged. | R, M | §4 "greyed out, not deleted" |
| UN-4 | Reinstall that app. | Entries become active again in their original positions. | R, M | §4 |
| UN-5 | App update (package replaced), app disabled, app's launcher activity renamed. | Update: no change. Disabled: greyed. Activity renamed: [OQ-27]. | R | §3 |
| UN-6 | App with multiple launcher activities. | Each launchable activity is a separate candidate for Unsorted. | R | §3 |
| UN-7 | Package changes while launcher process is dead. | Reconciled on next start. | R, I | §3 callbacks |
| UN-8 | Tap a greyed entry. | Nothing launches; optional hint [OQ-28]. | R | §3 refuse unknown |

## 8. Work profiles and private space (§3 LauncherApps across profiles, Risks "Work profile and private space IDs")

| ID | Steps | Expected | Level | Ref |
|---|---|---|---|---|
| WP-1 | Same package in personal and work profile. | Two distinct items; config must say which profile. How profile is identified in config (stable across reboots/reprovisioning, since user serial numbers can change) [OQ-29]. | R, I | Risks |
| WP-2 | Launch a work app from the config. | Launched via LauncherApps in the work profile, not personal. | I | §3 |
| WP-3 | Work profile paused ("work apps off"). | Work items greyed/badged; tap shows system unpause flow or nothing [OQ-30]; not deleted. | I, M | §3 |
| WP-4 | Work profile removed. | Entries greyed not deleted (consistent with UN-3) [OQ-30]. | I | §4 |
| WP-5 | Private space (Android 15+) locked. | Private apps must not appear in Unsorted or be written into the agent-readable file while locked — privacy leak risk [OQ-31]. | I, M | §3, Risks |
| WP-6 | New work-profile app installed. | Goes to Unsorted with profile badge. | R, I | §4 |
| WP-7 | Config references a profile that doesn't exist on this device (config synced from another phone). | Greyed, not launched, no crash. | R | §3 |

## 9. Automation and CI

- JVM unit tests: parser, validator, limits, fuzz, hash guard, version counter, reconciliation logic (pure Kotlin, no Android).
- Robolectric: loader + observer wiring with a fake document store, LauncherApps shadows for package callbacks, intent-launch assertions for HC-2/3.
- Instrumented (emulator, API 34+): SAF observation, atomic rename on the real provider, work profile (`adb shell pm create-user --profileOf 0 --managed`), private space on API 35.
- CI: GitHub Actions job `./gradlew testDebugUnitTest` on every PR once a Gradle project exists. Emulator job (reactivecircus/android-emulator-runner) only for the SAF/profile subset, nightly or on label, because it is slow.

## 10. Manual phone checklist (≈10 min, no app-killing)

Prereqs: launcher installed and set as Home, config folder picked, a PC/agent able to edit the synced file. Have one small free app ready to install (e.g. from F-Droid/Play).

1. **Starts clean (30 s).** Press Home. Pages, dock and drawer match the file. No banner.
2. **Live reload (1 min).** On the PC, move one app to another page and save. Within the sync delay the phone updates without you doing anything. Note roughly how many seconds it took.
3. **Write-back (1 min).** On the phone, drag one icon to a new spot. On the PC, the file shows the move once synced. The phone does not flicker or reload a second time.
4. **Broken file (1 min).** On the PC, delete a closing brace and save. Phone keeps the old layout and shows an error banner. Fix it and save; the banner goes away.
5. **Hostile entry (1 min).** Add an item whose id is `tel:911` or an `intent:` string and save. It is refused (banner) and nothing dials or opens. Remove it.
6. **Unknown app (30 s).** Add `com.example.doesnotexist/.Main`. It shows greyed out; tapping does nothing.
7. **New app (1.5 min).** Install the small app. It shows up in Unsorted (or the drawer).
8. **Uninstall (1 min).** Uninstall that app. Its icon greys out and is not removed from the file. (Optionally reinstall: it comes back in place.)
9. **Conflict (1.5 min).** Drag an icon on the phone and, before it syncs, edit a different item on the PC. After sync, check which version won and that the other is recoverable from `.history/` or git.
10. **Work profile (1 min, only if you have one).** Work apps appear with a badge and launch in the work profile. Turn work apps off: they grey out, nothing disappears. Turn back on.

Report anything that differs, with a screenshot.

## Open questions (not decided by the spike)

1. OQ-1 What does a `widget` item do in v1 (widgets are out of MVP): rejected, ignored, or placeholder?
2. OQ-2 Are invalid/overlapping grid coordinates errors or clamped? Grid dimensions?
3. OQ-3 Schema version mismatch: migrate older, refuse newer?
4. OQ-4 Unknown keys: reject, ignore, or preserve on write-back?
5. OQ-5 Duplicate ids / same app in multiple places allowed?
6. OQ-6 File format: JSON, JSON5 or YAML (spike leans to JSON)? Filename?
7. OQ-7 Accept UTF-8 BOM?
8. OQ-8 Unpublished shortcut ids: reject at validation or grey out at runtime?
9. OQ-9 Concrete limits: max file size, nesting depth, item count, string length.
10. OQ-10 Can folders nest?
11. OQ-11 Labels: allow bidi control characters, or strip/reject?
12. OQ-12 Duplicate JSON keys: reject?
13. OQ-13 Where is last-good persisted (app-private storage?) and does it survive reinstall?
14. OQ-14 First run with an invalid or missing file: what is shown?
15. OQ-15 Phone edits while the file is invalid: block, overwrite (after snapshot), or write elsewhere?
16. OQ-16 N for `.history/`; is `.history/` inside the synced folder (agent-visible) or private? Skip it when git is used?
17. OQ-17 Does the launcher re-canonicalise externally written files?
18. OQ-18 Atomic-write fallback when the SAF provider can't rename.
19. OQ-19 Conflict rule detail: is a write with a lower/equal version counter rejected or does pure LWW apply? Is the loser snapshotted?
20. OQ-20 Who increments the version counter; what if an agent doesn't?
21. OQ-21 Handling of sync-conflict files.
22. OQ-22 Debounce interval; polling interval if ContentObserver doesn't fire.
23. OQ-23 Target edit→visible latency.
24. OQ-24 Reload during a drag: defer or apply?
25. OQ-25 Unsorted page vs drawer for new apps (spike allows either).
26. OQ-26 Is Unsorted placement written back to the file?
27. OQ-27 App whose launcher activity changes: re-map by package or grey out?
28. OQ-28 Tap on a greyed entry: nothing, hint, or offer to reinstall?
29. OQ-29 Stable profile identifier in config.
30. OQ-30 Paused/removed work profile behaviour.
31. OQ-31 Private space: may its apps ever be written to the shared file?
