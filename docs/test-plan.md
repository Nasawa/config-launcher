# Test plan: config-launcher

Sources: [`ARCHITECTURE.md`](../ARCHITECTURE.md) (cited as **A§n**; the resolved questions as **A§15 OQ-n** and **A§16 AQ-n**), [`schema/launcher-config.v1.schema.json`](../schema/launcher-config.v1.schema.json), [`docs/ux.md`](ux.md) (banners B1-B6, **UX§n**), and [`docs/research-spike.md`](research-spike.md) (**spike §n**). A§15 overrides anything that conflicts with it. The open questions from v1 of this plan (OQ-1 to OQ-31) are now decided, and each case cites the decision. The questions still open are listed at the end.

Levels: **U** = JVM unit test (`model`/`config`, no Android), **S** = store test against the fake `DocumentAccess` (A§11), **R** = Robolectric, **I** = instrumented (emulator API 26 and 35, test `DocumentsProvider`), **M** = manual on a real phone.

Fixtures are real files in [`test-fixtures/configs/`](../test-fixtures/configs/). Each one's expected outcome is in `manifest.json` (see §2 below). They move to `app/src/test/resources/configs/{valid,invalid}/` once the Gradle project exists (A§11).

## 1. Schema validation (A§3, A§15)

| ID | Steps | Expected | Level | Ref |
|---|---|---|---|---|
| SV-1 | Load `valid/minimal.json`. | Model built and rendered, no banner. | U, R | A§3 |
| SV-2 | Conformance: run every fixture through networknt and the Kotlin validator. | They agree on every `both` fixture. `kotlin` fixtures are accepted by the schema but rejected by Kotlin (see open question Q2). CI also runs `test-fixtures/check_schema.py` (Python `jsonschema`). | U | A§11 |
| SV-3 | `widget`, `text`, `intent` item types. | Rejected, B2, last-good kept. No v1 type is ever silently dropped. | U | A§15 OQ-1 |
| SV-4 | Grid out of 2-12, cell outside the grid, overlapping cells, non-integer x. | Errors that are never clamped: the whole file is rejected with B2 listing each problem. | U | A§15 OQ-2, AQ-3 |
| SV-5 | `schemaVersion` missing, `"1"`, or `2`. | Missing or a string: invalid. 2: refused with the banner "This file needs a newer Config Launcher" and never downgrade-written. | U, R | A§3 rule 4, A§15 OQ-3 |
| SV-6 | Migration (once v2 exists): load a v1 file. | Migrated in memory, with no write until a user edit or "Upgrade file". Then a backup goes to `.configlauncher/backup/launcher.v1.<ts>.json` first, `x-` keys and IDs are unchanged, and there are golden-file tests per step. | U, S | A§3 rules 2-5 |
| SV-7 | `x-` keys at the root, on a page, and on an item (`valid/x-keys.json`), then make an edit. | Preserved verbatim, written after the known keys. Any other unknown key, typos included, is B2. | U | A§15 OQ-4 |
| SV-8 | Duplicate `id` anywhere; same app in the dock and on a page; same app twice on one page. | Duplicate id: error. Dock plus page: valid. Twice on one page or in one folder: warning only. | U | A§15 OQ-5 |
| SV-9 | File named `layout.yaml`, `launcher.json5`, or a JSON file with comments or trailing commas. | Only `launcher.json` at the folder root is read. Comments and trailing commas: B1. | U, S | A§15 OQ-6 |
| SV-10 | Canonical write: make one edit and compare bytes. | 2-space indent, schema key order, `x-` keys last, LF, trailing newline, no BOM, `revision` = max seen + 1. | U | A§3 Canonical, A§15 OQ-20, AQ-2 |
| SV-11 | Missing `revision`, or `revision` lower than the last seen. | Valid and accepted, because the hash decides. The next launcher write uses max + 1. | U, S | A§15 OQ-19, OQ-20 |

## 2. Malformed and hostile configs (A§8 "data, never code", A§15 OQ-4, OQ-9, OQ-11, OQ-12)

Principle: nothing in the file ever builds an Intent. The launcher only launches (package, activity) pairs resolved through `LauncherApps`, and shortcuts through `startShortcut`.

| ID | Steps | Expected | Level | Ref |
|---|---|---|---|---|
| HC-1 | For each fixture in `manifest.json`, load it as the folder file while a valid layout is showing. | Matches the `expect` column. Every invalid fixture leaves the current model unchanged and never crashes. | U, S, R | manifest |
| HC-2 | `hostile-key-{action,uri,extras,script,cmd,exec}`, `intent-type`, `package-{intent-uri,tel-uri,http-url}`. | B2. A Robolectric assertion confirms no activity was started and `Intent.parseUri` was never called. | U, R | A§8, A§15 OQ-4 |
| HC-3 | `valid/unknown-package.json`, then tap the item. | Greyed out. Tapping launches nothing and shows the hint "Not installed" plus "Remove from layout". | R | A§3, A§15 OQ-28 |
| HC-4 | Shortcut whose `shortcutId` the package doesn't publish. | Valid file. Greyed out at runtime and never launched ("Shortcut no longer available"). | R | A§15 OQ-8 |
| HC-5 | `oversize-257k.json`, served by a fake provider whose `COLUMN_SIZE` lies (reports 1 KiB). | Refused before a full read based on `COLUMN_SIZE`, and the streaming cap stops a lying size at 256 KiB. No OOM. | S | A§4 step 1, A§15 OQ-9 |
| HC-6 | `deep-nesting.json` (100k levels), `depth-9.json`, `valid/max-depth-8.json`. | Depth 9 or more: parse error, fails fast, no StackOverflowError. Exactly 8: valid. | U | A§15 OQ-9 |
| HC-7 | Totals: `2016-items.json`, `21-pages.json`, 145 items on one page, 8 dock items, 49 folder items. | All rejected (2000 items, 20 pages, 144 per page, 7 dock, 48 per folder). | U | A§15 OQ-9, schema |
| HC-8 | String caps: label of 61 chars and of 200 KiB, id of 65 chars, package of 1000 chars, shortcutId of 257 chars. | All rejected. | U | A§15 OQ-9 |
| HC-9 | Unicode: `valid/bidi-label.json`, `valid/emoji-label.json`, `invalid-utf8`, `lone-surrogate`, `nul-in-label`, `utf16`, `valid/bom.json`. | Bidi, zero-width and other Cf/Cc characters are stripped at render and the file is untouched. The real icon is shown, and long-press shows the real name and package. Invalid UTF-8, lone surrogates, NUL and UTF-16: B1. A BOM is accepted, and the next write has none. | U, R | A§15 OQ-7, OQ-11 |
| HC-10 | `duplicate-key.json`. | B1 with a path. | U | A§15 OQ-12 |
| HC-11 | Package names: empty, `..`, leading digit, `/`, space, no dot, non-ASCII. Activity with `/` or `;`. | B2 from schema patterns. Nothing reaches `LauncherApps`. | U | schema `packageName`, `className` |
| HC-12 | Fuzz the parser and validator with 10k mutations of the valid fixtures. | Only "valid" or a typed error. Never an uncaught exception, and never a launch. | U | A§4 step 4 |
| HC-13 | Error mirror: load any invalid fixture. | `.configlauncher/status.json` gets {result, errors with JSON paths, hash, time} and no private-space data. | S | A§9, A§15 OQ-31 |

### Fixture index (`test-fixtures/configs/manifest.json`)

| File | Expected | Layer | Ref |
|---|---|---|---|
| `valid/minimal.json` | valid | both | §3 |
| `valid/bom.json` | valid (BOM stripped; next write has no BOM) | parser | §15 OQ-7 |
| `valid/x-keys.json` | valid; x- keys round-trip verbatim | both | §15 OQ-4 |
| `valid/no-revision.json` | valid | both | §15 OQ-20 |
| `valid/same-app-dock-and-page.json` | valid | both | §15 OQ-5 |
| `valid/same-app-twice-on-page.json` | valid with warning | kotlin | §3, §15 OQ-5 |
| `valid/unknown-package.json` | valid; renders greyed, never launched | both | §3, §15 OQ-28 |
| `valid/bidi-label.json` | valid; bidi/zero-width stripped at render, file untouched | both | §15 OQ-11 |
| `valid/emoji-label.json` | valid | both | §15 OQ-11 |
| `valid/grid-12x12-max.json` | valid | both | §15 OQ-2 |
| `valid/work-profile.json` | valid; work item honoured only if "Include work apps" on | both | §15 OQ-29 |
| `valid/revision-lower.json` | valid; accepted even if lower than last seen (hash decides) | both | §15 OQ-19 |
| `valid/max-depth-8.json` | valid (exactly depth 8) | parser | §15 OQ-9 |
| `invalid/empty.json` | parse error, B1, last-good kept | parser | §4, §15 OQ-14 |
| `invalid/truncated.json` | parse error, B1 | parser | §4 step 4, §15 OQ-18 |
| `invalid/trailing-garbage.json` | parse error, B1 | parser | §15 OQ-6 |
| `invalid/comments.json` | parse error, B1 (no comments) | parser | §15 OQ-6 |
| `invalid/json5-trailing-comma.json` | parse error, B1 | parser | §15 OQ-6 |
| `invalid/utf16.json` | parse error, B1 | parser | §15 OQ-7 |
| `invalid/invalid-utf8.json` | parse error, B1 | parser | §15 OQ-11 |
| `invalid/lone-surrogate.json` | parse error, B1 | parser | §15 OQ-11 |
| `invalid/nul-in-label.json` | parse error, B1 | parser | §15 OQ-11 |
| `invalid/duplicate-key.json` | parse error with path, B1 | parser | §15 OQ-12 |
| `invalid/deep-nesting.json` | parse error, depth > 8, no StackOverflowError | parser | §15 OQ-9 |
| `invalid/depth-9.json` | parse error, depth 9 > 8 | parser | §15 OQ-9 |
| `invalid/oversize-257k.json` | refused before full read (COLUMN_SIZE + stream cap), B2/B1, last-good kept | store | §4 step 1, §15 OQ-9 |
| `invalid/missing-schemaVersion.json` | invalid, B2 | both | §15 OQ-3 |
| `invalid/newer-schemaVersion.json` | refused, "needs a newer Config Launcher" banner, never downgrade-written | both | §3 rule 4, §15 OQ-3 |
| `invalid/schemaVersion-string.json` | invalid, B2 | both | §15 OQ-3 |
| `invalid/missing-pages.json` | invalid, B2 | both | §3 |
| `invalid/zero-pages.json` | invalid, B2 | both | schema minItems |
| `invalid/21-pages.json` | invalid, B2 | both | §15 OQ-9 |
| `invalid/widget-type.json` | invalid, B2 | both | §15 OQ-1 |
| `invalid/text-type.json` | invalid, B2 | both | §15 OQ-1 |
| `invalid/intent-type.json` | invalid, B2; nothing launched | both | §8, §15 OQ-4 |
| `invalid/hostile-key-action.json` | invalid, B2 (unknown key); no Intent built, nothing launched | both | §8, §15 OQ-4 |
| `invalid/hostile-key-uri.json` | invalid, B2 (unknown key); no Intent built, nothing launched | both | §8, §15 OQ-4 |
| `invalid/hostile-key-extras.json` | invalid, B2 (unknown key); no Intent built, nothing launched | both | §8, §15 OQ-4 |
| `invalid/hostile-key-script.json` | invalid, B2 (unknown key); no Intent built, nothing launched | both | §8, §15 OQ-4 |
| `invalid/hostile-key-cmd.json` | invalid, B2 (unknown key); no Intent built, nothing launched | both | §8, §15 OQ-4 |
| `invalid/hostile-key-exec.json` | invalid, B2 (unknown key); no Intent built, nothing launched | both | §8, §15 OQ-4 |
| `invalid/unknown-key-typo.json` | invalid, B2 | both | §3, §15 OQ-4 |
| `invalid/package-intent-uri.json` | invalid, B2; nothing launched | both | §3 schema packageName, spike §3 |
| `invalid/package-tel-uri.json` | invalid, B2; nothing launched | both | §3 schema packageName, spike §3 |
| `invalid/package-http-url.json` | invalid, B2; nothing launched | both | §3 schema packageName, spike §3 |
| `invalid/package-empty.json` | invalid, B2; nothing launched | both | §3 schema packageName, spike §3 |
| `invalid/package-dotdot.json` | invalid, B2; nothing launched | both | §3 schema packageName, spike §3 |
| `invalid/package-leading-digit.json` | invalid, B2; nothing launched | both | §3 schema packageName, spike §3 |
| `invalid/package-slash.json` | invalid, B2; nothing launched | both | §3 schema packageName, spike §3 |
| `invalid/package-space.json` | invalid, B2; nothing launched | both | §3 schema packageName, spike §3 |
| `invalid/package-no-dot.json` | invalid, B2; nothing launched | both | §3 schema packageName, spike §3 |
| `invalid/package-unicode.json` | invalid, B2; nothing launched | both | §3 schema packageName, spike §3 |
| `invalid/package-1000-chars.json` | invalid: other strings are capped at 256 (schema has no maxLength on package) | kotlin | §15 OQ-9 |
| `invalid/activity-injection.json` | invalid, B2 | both | schema className |
| `invalid/id-uppercase.json` | invalid, B2 | both | §3 Stable IDs |
| `invalid/id-65-chars.json` | invalid, B2 | both | §15 OQ-9 |
| `invalid/duplicate-id.json` | invalid, B2 | kotlin | §3, §15 OQ-5 |
| `invalid/overlapping-cells.json` | invalid, B2 | kotlin | §15 OQ-2, AQ-3 |
| `invalid/cell-outside-grid.json` | invalid, B2 (grid is 4 wide) | kotlin | §15 OQ-2 |
| `invalid/negative-x.json` | invalid, B2 | both | §15 OQ-2 |
| `invalid/grid-13.json` | invalid, B2 | both | §15 OQ-2 |
| `invalid/grid-1.json` | invalid, B2 | both | §15 OQ-2 |
| `invalid/x-float.json` | invalid, B2 | both | §15 OQ-2 |
| `invalid/x-huge.json` | invalid, B2/B1; no overflow | both | §15 OQ-2 |
| `invalid/page-item-missing-xy.json` | invalid, B2 | both | §3 |
| `invalid/dock-item-with-xy.json` | invalid, B2 | both | §3 |
| `invalid/folder-child-with-xy.json` | invalid, B2 | both | §3 |
| `invalid/nested-folder.json` | invalid, B2 | both | §15 OQ-10 |
| `invalid/dock-8-items.json` | invalid, B2 | both | schema maxItems 7 |
| `invalid/label-61-chars.json` | invalid, B2 | both | §15 OQ-9 |
| `invalid/label-1mb.json` | invalid, B2 | both | §15 OQ-9 |
| `invalid/profile-private.json` | invalid, B2 | both | §15 OQ-31 |
| `invalid/profile-numeric.json` | invalid, B2 | both | §15 OQ-29 |
| `invalid/revision-negative.json` | invalid, B2 | both | schema |
| `invalid/unsorted-mode-bad.json` | invalid, B2 | both | §15 OQ-25 |
| `invalid/2016-items.json` | invalid, B2 (>2000 items total) | kotlin | §15 OQ-9 |

## 3. Last-good fallback (A§4, A§15 OQ-13, OQ-14, OQ-15, OQ-16, AQ-7)

| ID | Steps | Expected | Level | Ref |
|---|---|---|---|---|
| LG-1 | Valid A is showing, then the file becomes invalid. | A stays, B1 or B2 appears, the folder file is not touched, and `last-good.json` is not overwritten. | S, R | A§4 step 4 |
| LG-2 | Fix the file to valid B. | B is swapped in atomically (one StateFlow assignment) and the banner clears. `last-good.json` = B, and A goes to history. | S, R | A§4 steps 6-7, A§9 |
| LG-3 | Kill the process, put an invalid folder file in place, cold start. | `filesDir/config/last-good.json` renders in the first frame, then the banner appears. | R, I | A§4 Fallback, AQ-7 |
| LG-4 | Fresh install: no last-good, invalid or missing file. | Built-in default (one empty page, dock from role queries, all apps in the drawer). Banner "No launcher.json yet: Create" or "N problems". No crash loop, and nothing written over the bad file. | R, I | A§15 OQ-14, UX§6.1 |
| LG-5 | While the file is invalid, drag an icon. | The broken file is copied to `.configlauncher/broken/launcher.<ts>.json`. Then the edited last-good layout is written to `launcher.json`, with the snackbar "Your broken launcher.json was saved to .configlauncher/broken/". | S, R | A§15 OQ-15, A§16 item 12 |
| LG-6 | Make 11 successful loads. | `filesDir/config/history/` holds 10. None are in the synced folder. | S | A§15 OQ-16 |
| LG-7 | `last-good.json` is written via temp + `File.renameTo`; kill between the two steps. | The previous last-good is intact. | U, R | A§4 step 7 |
| LG-8 | "Restore previous layout" (Settings) and "Restore last-good to file" (error sheet). | The restored version is written. The replaced folder file is first backed up to `.configlauncher/backup/`. | S, R | A§6, A§9, A§16 item 9 |
| LG-9 | Android backup and restore (`adb shell bmgr`). | `last-good.json` and history are excluded (`dataExtractionRules`). | I | A§15 OQ-13 |

## 4. Self-write guard (A§5, A§15 OQ-17)

| ID | Steps | Expected | Level | Ref |
|---|---|---|---|---|
| SW-1 | Drag, write, then the next poll. | The hash is in the self-write set: zero reloads, zero recompositions from the reload, one write. | S | A§5 steps 4-5 |
| SW-2 | 20 drags within 750 ms of each other. | One write after the last drop. Zero self-reloads. | S | A§5 step 2, A§15 OQ-22 |
| SW-3 | Five writes in a row, then the oldest content is externally restored. | The self-write set holds 4, so the oldest hash is treated as external and reloaded. That's correct, because it is a real change. | S | A§5 step 4 |
| SW-4 | An external rewrite that only changes whitespace or key order. | Reloads with an equal model. No write-back, and no re-canonicalisation. | S | A§15 OQ-17, AQ-2 |
| SW-5 | Syncthing or git touches the mtime only, or rewrites identical bytes. | The hash equals the last applied hash: no-op. | S | A§4 "SHA-256" |
| SW-6 | The provider changes the document ID on replace, with the same content. | Re-resolved by name, the hash is equal: no-op. | S, I | A§4, A§13 |

## 5. Store fault injection (fake `DocumentAccess`, A§5 Atomic writes, A§11, A§15 OQ-18, AQ-4)

Write sequence under test: write `.configlauncher/launcher.json.tmp` and verify by reading back → `"wt"` write to `launcher.json` and verify → on failure, restore from tmp → on failure, B5 Retry. The in-memory model and `last-good.json` keep the edit.

| ID | Fault injected | Expected | Level |
|---|---|---|---|
| FI-1 | `renameDocument`/`moveDocument` unsupported (throws or auto-renames to `launcher (1).json`). | Never called on the write path (assert zero calls), and the write succeeds. | S |
| FI-2 | Tmp write fails halfway. | `launcher.json` untouched. B5. Edit kept in memory and in last-good. | S |
| FI-3 | Tmp read-back hash mismatch (silent corruption). | `launcher.json` untouched. B5. | S |
| FI-4 | `launcher.json` write fails halfway (partial bytes on disk). | Restored from tmp with `"wt"`. The read-back hash equals the pre-edit content, and B5 offers a retry. | S |
| FI-5 | FI-4 and the restore also fails. | B5 "Couldn't save your change". Memory and last-good keep the edit, and Retry writes again. Nothing claims success. | S |
| FI-6 | Provider ignores truncate (old `"w"` bug: longer old content leaves a tail). | Always opened with `"wt"`. The read-back detects a tail and goes to restore, then B5. | S |
| FI-7 | Persisted permission revoked between reads (`SecurityException`). | B3 "Can't reach your layout folder", last-good rendered, edits queued in memory. No crash. On start, the `persistedUriPermissions` check shows B3. | S, R, I |
| FI-8 | Provider unavailable: cloud offline, provider process dead, `query` returns null, `openInputStream` throws `FileNotFoundException`. | Poll fails quietly with last-good kept. Writes give B5. Recovers on the next successful poll. | S |
| FI-9 | Disk full (`IOException: ENOSPC`) on the tmp write. | Caught before `launcher.json` is touched. B5. | S |
| FI-10 | Disk full on app-private storage (last-good write). | The folder write still counts as success. Last-good failure is logged, and the previous last-good stays intact. Behaviour not specified, see Q4. | S |
| FI-11 | `launcher.json` deleted externally. | B4 "launcher.json is missing", with Recreate and Choose folder. Never recreated automatically. | S, R |
| FI-12 | Torn read: the reader sees half of an external write. | The 300 ms stability re-check restarts. If it still parses, it fails validation and is rejected, never applied. | S |
| FI-13 | `.configlauncher/` can't be created (read-only provider). | B5 on write. Reads still work. | S |
| FI-14 | `COLUMN_LAST_MODIFIED` is 0 or constant (some providers). | Change still detected through size or docId, and the hash catches same-size edits on resume. Same-size edits while the home screen is showing may be missed, see Q5. | S |

## 6. Concurrent edits: the conflict and drag matrix (A§5, A§6, A§15 OQ-19, OQ-22, OQ-24, AQ-6)

"Agent edit" means the folder file's hash changes to something that is neither base nor ours. All cases: **S** for logic, **R** for B6 and snackbars, plus a Compose drag test at **I** for the rows marked †.

| ID | Timing | Expected |
|---|---|---|
| CM-1 | Agent edit, no pending local edits. | Applied directly. No B6. The replaced version goes to history. |
| CM-2 | Agent edit lands mid-drag. † | No model swap mid-gesture, and the file is parked as `incoming`. On drop the move becomes a pending edit, the hash check fails, and B6 appears. |
| CM-3 | CM-2 but the drop is cancelled (dropped back where it started, no op). † | `incoming` applied on drag end with no B6, because nothing is pending. Not stated in the spec, see Q6. |
| CM-4 | Agent edit within the 750 ms debounce after a drop. | Write-time hash check ≠ base, so B6. |
| CM-5 | Agent edit arrives after our write but before our poll sees it. | Hash is neither ours nor base, so it counts as external and is applied (no pending edits). |
| CM-6 | B6, then `Use file`. | Pending ops discarded, the file is loaded, and the local model is snapshotted to history. |
| CM-7 | B6, then `Keep mine`, where the agent touched different items. | Ops are replayed by ID onto the file's model: both changes survive, the file version is snapshotted first, and `revision` = max + 1. |
| CM-8 | `Keep mine`, where the agent deleted the item I moved. | Op dropped. Snackbar "1 change skipped because the item was removed in the file", with Undo. |
| CM-9 | `Keep mine`, where the agent put something in my target cell. | Placed in the nearest free cell on the same page, else the first free cell on any page, else a new page. |
| CM-10 | `Keep mine` with `Add` of an app the agent already added to that container. | Skipped as a duplicate. |
| CM-11 | `Keep mine` replay result fails validation (inject it). | Nothing written. B6 stays with only `Use file`. |
| CM-12 | The agent writes `revision` lower than, equal to, or missing compared with ours. | Ignored for conflicts: only the hash decides, and the content is accepted. Our next write = max seen + 1. |
| CM-13 | Agent writes twice within 300 ms. | The stability re-check applies only the final content. |
| CM-14 | Agent edit while the file was invalid and the phone made an edit (OQ-15 path). | The broken copy is saved first. Then the hash check against the base. Order not specified, see Q7. |
| CM-15 | Two phone edits while B6 is showing. | Not specified, see Q8. |
| CM-16 | Ops on IDs: rename vs agent rename of the same item; CreateFolder whose members the agent moved. | Rename: mine wins on `Keep mine`. CreateFolder: members that still exist are used, and skipped members get the snackbar. Partial-member rule not specified, see Q9. |
| CM-17 | Syncthing conflict siblings `launcher.sync-conflict-*.json` or `launcher (1).json` appear. | Ignored by the loader. A dismissible notice names them and status.json lists them. Never merged or deleted. |

Unit-level table test for the replay engine: rows for {Move, Add, Remove, Rename, CreateFolder} × {target exists, removed, cell occupied, duplicate}.

## 7. Live reload and latency targets (A§4, A§15 OQ-22, OQ-23, A§11)

| ID | Steps | Expected / target | Level |
|---|---|---|---|
| LR-1 | File lands, then the user presses Home (`onStart`/`onResume` poll). | Visible **≤ 1 s** after landing on the device. | I, M |
| LR-2 | File lands while the home screen is already showing. | Visible **≤ 3 s** (2 s poll + 300 ms stability + parse). | S (fake clock), I, M |
| LR-3 | ContentObserver callback fires. | Immediate poll. | S, R |
| LR-4 | Launcher in the background. | Zero polls (assert with the fake clock and no wakeups). | S |
| LR-5 | Cold start, 300-item config. | First frame **< 500 ms** on a mid device, from last-good. | I (macrobenchmark) |
| LR-6 | Write-back debounce. | Write starts 750 ms after the last edit and never mid-drag. | S |
| LR-7 | Poll cost. | One children-cursor query per 2 s. Measure the time per poll and report it (no target set, see Q10). | I |
| LR-8 | The file is replaced via rename (new docId), as git or Syncthing do. | Re-found by name and reloaded. | S, I |

Sync transfer time is excluded from all targets (A§15 OQ-23).

## 8. New and uninstalled apps, Unsorted (A§3 Unsorted, A§7, A§15 OQ-25 to OQ-28)

| ID | Steps | Expected | Level |
|---|---|---|---|
| UN-1 | Install an unplaced app, `unsorted.mode: drawer` (default). | In the drawer only. Home pages untouched, file untouched. | R, M |
| UN-2 | Same with `mode: page`. | Also shown on a synthetic last page titled `pageTitle`. Not written. | R |
| UN-3 | Same with `mode: hidden`. | Not shown in Unsorted. The drawer still lists all apps. Whether "hidden" also hides it from the drawer list: see Q11. | R |
| UN-4 | App in `drawer.hidden`. | Never in the drawer or Unsorted, but launches if placed. | R |
| UN-5 | Drag an Unsorted app onto a page. | The only path that writes it. Explicit edit. | R |
| UN-6 | Uninstall a placed app. | Greyed, kept in the file. Tap shows "Not installed" plus "Remove from layout". | R, M |
| UN-7 | Reinstall it. | Active again in place. | R, M |
| UN-8 | Item without `activity`, and the package changes its default activity. | Follows the new default. | R |
| UN-9 | Item with `activity` that disappears: (a) the package has one launcher activity, (b) several. | (a) Falls back, file untouched until the next edit. (b) Greyed. | R |
| UN-10 | Package changes while the process is dead. | Reconciled on start. | R, I |
| UN-11 | Drawer rules: first match wins, and rules never move home items. | | U |

## 9. Work profiles and private space (A§7, A§15 OQ-29 to OQ-31)

| ID | Steps | Expected | Level |
|---|---|---|---|
| WP-1 | "Include work apps in config" **off** (default). | Work apps in the drawer with the badge. They can't be placed on pages, and nothing about them reaches the file or status.json. | R, I |
| WP-2 | Toggle **on**, drag a work app to a page. | Written with `"profile": "work"`. Launches in the work profile through LauncherApps. | I |
| WP-3 | File contains `profile: work` while the toggle is off. | Not specified (render, grey, or ignore?), see Q12. | R |
| WP-4 | Same package in personal and work. | Two distinct items keyed by (package, activity, profile). | R |
| WP-5 | Work profile paused. | Items greyed with the badge. Tap calls `requestQuietModeEnabled(false)` (system prompt). Not deleted. | I, M |
| WP-6 | Work profile removed, or the file synced from a phone without one. | Greyed, kept in the file, "Remove from layout" offered. | R |
| WP-7 | Two work profiles. | First one wins. | R |
| WP-8 | Numeric user IDs or serials. | Never written: grep the written file and status.json. `profile-numeric.json` and `profile-private.json` are rejected. | U |
| PS-1 | Private space (API 35) unlocked. | Private apps appear only in the system private-space section of the drawer. Not in Unsorted. Not draggable to pages (implied, see Q13). | I, M |
| PS-2 | Private space locked. | Its apps appear nowhere. | I, M |
| PS-3 | Any write or status.json update while private space is unlocked. | Contains no private-space package. | I |

## 10. Manual QA matrix (A§11), for later

Run on Christopher's phone plus a Pixel emulator. A Samsung device if one is available.

| ID | Setup | Steps | Expected |
|---|---|---|---|
| MS-1 | **Syncthing** folder shared with the PC. | Edit on the PC and save, then press Home. Drag on the phone and watch the PC. Edit on both at once. | Reload within sync time + 1 s on Home (+3 s while visible). The phone's write shows on the PC. B6 on a simultaneous edit. A `.sync-conflict` copy, if Syncthing makes one, produces the notice. |
| MS-2 | Syncthing with "ignore delete" and versioning on. | Drag 5 times quickly. | One file version (debounce). No deletes are ever propagated. |
| MG-1 | **git** client folder (e.g. MGit). | Commit and push from the PC, pull on the phone, press Home. Drag on the phone, commit, pull on the PC. | Reloads after the pull (the file is replaced, so the docId may change). The phone's write gives a canonical, small diff. `.configlauncher/` should be in `.gitignore` or committed deliberately (see Q14). |
| MG-2 | git, with the agent's checkout leaving conflict markers. | Pull a merge conflict. | B1 with line/column, last-good kept. |
| GD-1 | **Google Drive** folder via SAF. | Edit on the PC, wait, press Home. Drag on the phone. Turn on airplane mode and drag. | Best effort (A§13): document the observed latency. Airplane mode gives B5 or a delayed write, and the edit is never lost. Record the actual behaviour, since Drive SAF support for tree pick and `"wt"` varies. |
| WP-M | Work profile (Test DPC on an emulator if the phone has none). | WP-1, WP-2, WP-5. | As in §9. |
| PS-M | Private space on Android 15+. | PS-1, PS-2. | As in §9. |

## 11. Manual phone checklist (≈10 min, no app-killing)

Needs: the launcher set as Home, the folder picked, and the PC able to edit the synced `launcher.json`. One small app to install.

1. Press Home: the layout matches the file, with no banner.
2. On the PC, move one app and save. Press Home on the phone: it updates within about a second. Try again with the home screen already showing: within about 3 s.
3. Drag one icon on the phone. The PC file updates after sync, and the phone doesn't flicker.
4. On the PC, delete a `}` and save. The phone keeps the layout and shows "Can't read launcher.json…". Undo the change and the banner clears.
5. On the PC, add `"uri": "tel:911"` to an item. You get the "has 1 problem" banner and nothing dials. Remove it.
6. Install the small app: it appears in the drawer, not on a home page. Uninstall it from a home icon if you placed it: it greys out and stays.
7. Drag an icon on the phone, then within a second save a PC edit to a different icon. If "launcher.json changed while you were editing" appears, tap **Keep mine**: both changes stay.
8. Only if you have one: work apps show a badge in the drawer. Pause work apps and any placed work icons grey out.

## 12. Automation and CI

- When `:app` exists: a GitHub Actions job running `./gradlew testDebugUnitTest` (JVM + Robolectric + store fakes) on every PR, plus `python3 test-fixtures/check_schema.py`.
- Emulator job (API 26 and 35) for the instrumented SAF, profile and drag tests: nightly or on a label.
- A test that fails on `android.*` imports in `model` and `config` (A§10).

## Open questions (still open)

1. Q1: The schema has no `maxLength` on `package`, `activity`, `packagePrefix` and `drawer.hidden` entries, though A§15 OQ-9 caps "other strings" at 256. Should the schema add `maxLength: 256`? Currently those are Kotlin-only.
2. Q2: A§11 says the networknt and Kotlin validators "agree" on every file in `configs/invalid/`. But duplicate IDs, overlapping cells, cells outside the grid, the 2000-item total and duplicate keys can't be expressed in JSON Schema. Should the conformance test assert "Kotlin rejects ⊇ schema rejects", with a list of Kotlin-only rules?
3. Q3: Is NUL inside a JSON string (`\u0000`, legal JSON) a parse error (A§15 OQ-11 says so) or a validation error? This affects whether it's B1 or B2.
4. Q4: If writing `last-good.json` fails (app storage full) after a verified folder write, what should happen: banner, or silent?
5. Q5: Providers that report a constant `COLUMN_LAST_MODIFIED`: should a same-size external edit while the home screen is showing be caught (hash every N polls)?
6. Q6: Agent edit mid-drag, then the drag is cancelled: apply `incoming` silently?
7. Q7: OQ-15 broken-copy path combined with a conflicting agent edit: which comes first, the broken-copy snapshot or the B6 hash check?
8. Q8: Do further phone edits while B6 is showing join the pending queue, or are they blocked until the user picks?
9. Q9: `Keep mine` replay of `CreateFolder` when some members were removed or moved by the agent: build a partial folder, or skip?
10. Q10: Is there a budget for poll cost or battery while the home screen is showing?
11. Q11: `unsorted.mode: hidden`: does the drawer still list those apps? (A§3 says the drawer "always lists all apps".)
12. Q12: File has `profile: work` items but "Include work apps in config" is off: render them, grey them out, or treat them as invalid?
13. Q13: Can private-space apps be dragged anywhere at all (folders, dock), or is it blocked in the UI?
14. Q14: Should `.configlauncher/` be committed or gitignored in a git-synced folder? Should the README say?
15. Q15: Google Drive: is "best effort" shipped, or is Drive warned against in onboarding?
