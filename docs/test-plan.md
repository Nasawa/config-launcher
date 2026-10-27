# Test plan: config-launcher

Sources: [`ARCHITECTURE.md`](../ARCHITECTURE.md) (cited as **A§n**; resolved questions as **A§15 OQ-n**, **A§16 AQ-n**, **A§17 Qn** and **A§18.n**), [`schema/launcher-config.v1.schema.json`](../schema/launcher-config.v1.schema.json), [`docs/ux.md`](ux.md) (banners B1-B8, **UX§n**), and [`docs/research-spike.md`](research-spike.md) (**spike §n**). Later sections override earlier ones: A§18 > A§17 > A§15/§16. **v3** (this revision) replaces Q1-Q16 of v2 with the A§17 rulings and adds the A§18 changes: **minSdk 29** (coordinator ruling of 2026-10-18 15:27 MDT, now in A§18.3 via #32; A§18.6 resolves the v3 questions), automatic merge, Skip for now, the keyboard-up drawer, the label backdrop, the device matrix and performance budgets. Each case cites its ruling. The questions still open are listed at the end.

Levels: **U** = JVM unit test (`model`/`config`, no Android), **S** = store test against the fake `DocumentAccess` (A§11), **R** = Robolectric, **I** = instrumented (emulator matrix API 29, 35, 36, test `DocumentsProvider`), **M** = manual on a real phone.

Fixtures are real files in [`test-fixtures/configs/`](../test-fixtures/configs/). Each one's expected outcome is in `manifest.json` (see §2 below). `test-fixtures/gen_fixtures.py` regenerates them deterministically, and `test-fixtures/check_schema.py` checks them against the schema (0 mismatches; one fixture can't be checked because Python's parser hits its recursion limit). A copy lives in `app/src/test/resources/configs/` for the JVM tests (A§11); keep the two in sync by regenerating them.

## 1. Schema validation (A§3, A§15)

| ID | Steps | Expected | Level | Ref |
|---|---|---|---|---|
| SV-1 | Load `valid/minimal.json`. | Model built and rendered, no banner. | U, R | A§3 |
| SV-2 | Conformance (A§17 Q2), in `KotlinOnlyConformanceTest` and `check_schema.py`. | Both validators accept everything in `valid/`. Kotlin rejects everything the schema rejects (`both`). Each fixture tagged with a Kotlin-only rule in `manifest.json` (`kotlinOnly`: duplicate-ids, overlapping-cells, cell-outside-grid, item-total, duplicate-keys, parser-depth, byte-cap, nul-or-encoding) **must be accepted by the schema**, so the list stays honest, and must be rejected by Kotlin. | U | A§17 Q2 |
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
| HC-5 | 2 MiB cap: `valid/size-257k.json`, `valid/size-just-under-2mib.json` (exactly 2,097,152 bytes), `invalid/size-just-over-2mib.json` (2,097,153). Repeat the over-cap file with a fake provider whose `COLUMN_SIZE` lies (reports 1 KiB). | The 257 KiB and exactly-2 MiB files load. The cap is inclusive. Over 2 MiB is refused before a full read, using `COLUMN_SIZE`, and the streaming cap stops a lying size at 2 MiB. No OOM, and last-good is kept. | U, S | A§4 step 1, A§17 Q16 |
| HC-6 | `deep-nesting.json` (100k levels), `depth-9.json`, `valid/max-depth-8.json`. | Depth 9 or more: parse error, fails fast, no StackOverflowError. Exactly 8: valid. | U | A§15 OQ-9 |
| HC-7 | Totals: `2016-items.json` (compact so it stays under the byte cap), `21-pages.json`, 145 items on one page, 8 dock items, 49 folder items, `tags-17.json`. Plus `valid/2000-items-long-strings.json`: 2000 items with 100-char packages, 152-char activities, 60-emoji labels and 64-char IDs, 1.46 MiB canonical. | All the over-limit files are rejected: 2000 items, 20 pages, 144 per page, 7 dock, 48 per folder, 16 tags. The long-string file is valid, and its canonical re-encode is ≤ 2 MiB (`CanonicalSizeTest`). | U | A§15 OQ-9, A§17 Q1, Q16 |
| HC-8 | String caps: label of 61 chars and of 200 KiB, id of 65 chars, package of 1000 chars, shortcutId of 257 chars, and 257-char `activity`, `drawer.hidden[]`, `packagePrefix` and `match.packages[]`. | All rejected, by the schema too (A§17 Q1 added `maxLength: 256`). | U | A§15 OQ-9, A§17 Q1 |
| HC-8b | **Writer refusal** (WB-REFUSE): load `valid/writer-overflow-seed.json` (compact, 1.89 MiB, canonical form 2.13 MiB) and make any edit. | Write refused with B5 `Couldn't save your change: launcher.json would be over 2 MB.` The file stays byte-identical, and the edit is kept in memory (`WriterRefusalTest`). | S | A§17 Q16 |
| HC-9 | Unicode: `valid/bidi-label.json`, `valid/emoji-label.json`, `invalid-utf8`, `lone-surrogate`, `nul-in-label` (escaped `\u0000`), `nul-raw` (raw 0x00 byte), `utf16`, `valid/bom.json`. | Bidi, zero-width and other Cf/Cc characters are stripped at render and the file is untouched. The real icon is shown, and long-press shows the real name and package. Invalid UTF-8, lone surrogates, NUL (escaped or raw) and UTF-16: **B1** (parse error, A§17 Q3). A BOM is accepted, and the next write has none. | U, R | A§15 OQ-7, OQ-11 |
| HC-10 | `duplicate-key.json`. | B1 with a path. | U | A§15 OQ-12 |
| HC-11 | Package names: empty, `..`, leading digit, `/`, space, no dot, non-ASCII. Activity with `/` or `;`. | B2 from schema patterns. Nothing reaches `LauncherApps`. | U | schema `packageName`, `className` |
| HC-12 | Fuzz the parser and validator with 10k mutations of the valid fixtures. | Only "valid" or a typed error. Never an uncaught exception, and never a launch. | U | A§4 step 4 |
| HC-12b | Q1 cap in the schema: `package-1000-chars` is now a `both` fixture. | The schema rejects it too. | U | A§17 Q1 |
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
| `invalid/utf16.json` | parse error, B1 | kotlin-only (nul-or-encoding) | §15 OQ-7 |
| `invalid/invalid-utf8.json` | parse error, B1 | kotlin-only (nul-or-encoding) | §15 OQ-11 |
| `invalid/lone-surrogate.json` | parse error, B1 | kotlin-only (nul-or-encoding) | §15 OQ-11 |
| `invalid/nul-in-label.json` | parse error, B1 (escaped NUL) | kotlin-only (nul-or-encoding) | §17 Q3 |
| `invalid/nul-raw.json` | parse error, B1 (raw NUL byte: an unescaped control character, so a syntax error for any JSON parser) | parser | §17 Q3 |
| `invalid/duplicate-key.json` | parse error with path, B1 | kotlin-only (duplicate-keys) | §15 OQ-12 |
| `invalid/deep-nesting.json` | parse error, depth > 8, no StackOverflowError | kotlin-only (parser-depth) | §15 OQ-9 |
| `invalid/depth-9.json` | parse error, depth 9 > 8 | kotlin-only (parser-depth) | §15 OQ-9 |
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
| `invalid/package-1000-chars.json` | invalid, B2 (256-char cap, now in the schema) | both | §17 Q1 |
| `invalid/activity-injection.json` | invalid, B2 | both | schema className |
| `invalid/id-uppercase.json` | invalid, B2 | both | §3 Stable IDs |
| `invalid/id-65-chars.json` | invalid, B2 | both | §15 OQ-9 |
| `invalid/duplicate-id.json` | invalid, B2 | kotlin-only (duplicate-ids) | §3, §15 OQ-5 |
| `invalid/overlapping-cells.json` | invalid, B2 | kotlin-only (overlapping-cells) | §15 OQ-2, AQ-3 |
| `invalid/cell-outside-grid.json` | invalid, B2 (grid is 4 wide) | kotlin-only (cell-outside-grid) | §15 OQ-2 |
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
| `invalid/2016-items.json` | invalid, B2 (>2000 items total) | kotlin-only (item-total) | §15 OQ-9 |
| `valid/size-257k.json` | valid (under the 2 MiB cap) | store | §17 Q16 |
| `valid/size-just-under-2mib.json` | valid: exactly 2 MiB (2,097,152 bytes) is accepted | store | §17 Q16 |
| `invalid/size-just-over-2mib.json` | refused before full read (COLUMN_SIZE + streaming cap), last-good kept | kotlin-only (byte-cap) | §4 step 1, §17 Q16 |
| `valid/2000-items-long-strings.json` | valid: 2000 items, 100-char packages, 152-char activities, 60-emoji labels, 64-char IDs (~1.46 MiB canonical) | both | §17 Q16 |
| `valid/writer-overflow-seed.json` | valid to read (compact, under 2 MiB); after any edit the canonical output is over 2 MiB, so the writer refuses with B5 "launcher.json would be over 2 MB." and keeps the edit in memory | store | §17 Q16 |
| `invalid/tags-17.json` | invalid, B2 (tags maxItems 16) | both | §17 Q1 |
| `invalid/activity-257-chars.json` | invalid, B2 | both | §17 Q1 |
| `invalid/hidden-257-chars.json` | invalid, B2 | both | §17 Q1 |
| `invalid/package-prefix-257-chars.json` | invalid, B2 | both | §17 Q1 |
| `invalid/match-packages-257-chars.json` | invalid, B2 | both | §17 Q1 |
| `invalid/git-conflict-markers.json` | parse error, B1; with PR #21: "git merge conflict markers (line 3); finish the merge in git", last-good kept, no write | parser | A§18.1 git primary, CM-21, MG-4 |

## 3. Last-good fallback (A§4, A§15 OQ-13, OQ-14, OQ-15, OQ-16, AQ-7)

| ID | Steps | Expected | Level | Ref |
|---|---|---|---|---|
| LG-1 | Valid A is showing, then the file becomes invalid. | A stays, B1 or B2 appears, the folder file is not touched, and `last-good.json` is not overwritten. | S, R | A§4 step 4 |
| LG-2 | Fix the file to valid B. | B is swapped in atomically (one StateFlow assignment) and the banner clears. `last-good.json` = B, and A goes to history. | S, R | A§4 steps 6-7, A§9 |
| LG-3 | Kill the process, put an invalid folder file in place, cold start. | `filesDir/config/last-good.json` renders in the first frame, then the banner appears. | R, I | A§4 Fallback, AQ-7 |
| LG-4 | Fresh install: no last-good, invalid or missing file. | Built-in default (one empty page, dock from role queries, all apps in the drawer). B4 `No launcher.json yet.` with `Create from current layout` and `Reconnect`, or B1/B2. No crash loop, and nothing is ever auto-created. | R, I | A§15 OQ-14, A§17 (a), AQ-8 |
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
| FI-10 | Disk full on app-private storage (the last-good write). | The folder write still counts as success. One snackbar `Couldn't update the backup copy (phone storage full).`, logged in status.json, no banner, retried on the next load or write. The previous last-good stays intact. | S | A§17 Q4 |
| FI-11 | `launcher.json` deleted externally while the folder stays readable. | B4 with `Create from current layout` and `Reconnect`. Never recreated automatically. Tapping Create writes last-good (the starter only when there is no last-good). B4 isn't cleared by our own create alone: it clears when the next poll loads a valid file. | S, R | A§17 (a), AQ-8 |
| FI-12 | Torn read: the reader sees half of an external write. | The 300 ms stability re-check restarts. If it still parses, it fails validation and is rejected, never applied. | S |
| FI-13 | `.configlauncher/` can't be created (read-only provider). | B5 on write. Reads still work. | S |
| FI-15 | Grant revoked vs file missing, side by side: (a) remove the URI from `persistedUriPermissions`; (b) tree query throws `SecurityException` or `FileNotFoundException` for the tree itself; (c) tree readable but no `launcher.json` child. | (a) and (b): **B3** `Can't reach your config folder…` with **`Reconnect` only** (no Create). (c): **B4** with `Create from current layout` and `Reconnect`. B3 and B4 never show in app-private mode (A§18.2), and B4's Reconnect is hidden there (#16). | S, R | A§17 AQ-8, A§18.2 |
| FI-14 | Provider whose `COLUMN_LAST_MODIFIED` never changes (always 0 or a constant), with a same-size external edit (e.g. one digit changed). | Not caught by the stamp. Caught by the hash read on **every 5th foreground poll** (≤ 10 s) and on every resume. Assert: with a fake clock and constant stamp, the edit is applied within 5 polls, and immediately after `onStart`. | S | A§17 Q5 |

## 6. Concurrent edits: the conflict and drag matrix (A§5, A§6, A§17 Q6-Q9, A§18.2)

**Default since A§18.2: automatic merge.** On a hash mismatch at write time, or when a file arrives while ops are pending, the launcher replays the pending ops by ID onto the file and writes the result with no prompt. Snackbar: `Layout updated from launcher.json. Kept your N changes.` with `Undo`, which writes the file's version back. Both pre-merge versions go to history. **`Ask before merging`** (Settings, off by default) brings back the B6 `Use file` / `Keep mine` prompt. If the replay fails validation, B6 appears whatever the setting.

"Agent edit" means the file's hash changes to something that is neither base nor ours. Logic is tested at **S**, snackbars and B6 at **R**, and the rows marked † also get a Compose drag test at **I**. "Auto" is the default; "Ask" is with `Ask before merging` on.

| ID | Timing / setup | Expected (Auto, default) | Expected (Ask) |
|---|---|---|---|
| CM-1 | Agent edit, no pending local edits. | Applied directly. No merge snackbar beyond `Layout updated from launcher.json.` The replaced version goes to history. | Same. |
| CM-2 | Agent edit lands mid-drag. † | No model swap mid-gesture, and the file is parked as `incoming`. On drop, the move is replayed onto `incoming` and written. Snackbar `…Kept your 1 change.` + Undo. | On drop: B6. |
| CM-3 | CM-2, but the drop is cancelled (no op). † | `incoming` applied as a normal reload with `Layout updated from launcher.json.` No merge, no B6 (Q6). | Same (nothing is pending). |
| CM-4 | Agent edit within the 750 ms debounce after a drop. | Write-time hash ≠ base, so auto replay and write. Snackbar + Undo. | B6. |
| CM-5 | Agent edit arrives after our write but before our poll sees it. | External, applied (nothing pending). | Same. |
| CM-6 | **Undo** on the merge snackbar. | **Undo safety rule (A§18.2, A§18.6 Q-v3-6):** Undo re-reads and hashes the file. Unchanged since the merge: the pre-merge file content is restored through the normal write path. Undo must **never** overwrite an external change. The pre-merge file is always in history. See CM-22–CM-26. Tests: `AutoMergeTest`, `UndoSafetyTest` (#37). | n/a |
| CM-7 | Agent touched different items from mine. | Both changes survive. `revision` = max + 1. Only my ops' targets ("just touched") come from me; everything else comes from the file. | `Keep mine` gives the same result. `Use file` discards mine (snapshotted). |
| CM-8 | Agent deleted the item I moved. | Op dropped. Skip snackbar `1 change skipped because the item was removed in the file` + Undo. | Same after `Keep mine`. |
| CM-9 | Agent put something in my target cell. | Placed in the nearest free cell on the same page, else the first free cell on any page, else a new page. | Same after `Keep mine`. |
| CM-10 | My `Add` duplicates an app the agent already added to that container. | Skipped as a duplicate. | Same. |
| CM-11 | Replay result fails validation (injected). | Nothing written. **B6 regardless of the setting**, with only `Use file`. | Same. |
| CM-12 | The agent writes `revision` lower than, equal to, or missing compared with ours. | Ignored for conflicts: the hash decides. Our next write = max seen + 1. | Same. |
| CM-13 | Agent writes twice within 300 ms. | The stability re-check applies only the final content. | Same. |
| CM-14 | Q7 (a): the file was broken, I edit, and the file is **still the same broken bytes** at write time. | Broken copy to `.configlauncher/broken/launcher.<ts>.json`, then write mine (OQ-15). Snackbar `Your broken launcher.json was saved…`. | Same. |
| CM-15 | Q7 (b): the file was broken, I edit, and the agent **replaced it** before my write. | The hash check comes first: the new file is validated. If it's valid, it's a normal merge (Auto: replay + snackbar; Ask: B6), and **no** broken copy is made. If it's still invalid but different, it's treated as broken again (snapshot those bytes). | B6 when the new file is valid. |
| CM-16 | Q8: I make two more edits while the merge is pending (Auto) or B6 is up (Ask). | Auto: all queued ops replay together, and the snackbar counts them (`Kept your 3 changes.`). | Edits stay on screen and join the queue. Nothing is written until I pick. The banner counts them (`3 changes`). `Use file` discards all, `Keep mine` replays all. |
| CM-17 | Q9: replay `CreateFolder(f, [a,b,c])` where the agent removed `b` and moved `c` into another folder. | Partial folder from the members that still exist and aren't in another folder. Here only `a` is left, which is fewer than 2, so the folder is skipped and `a` placed on its own. Snackbar per A§18.4: `Folder "<Label>" wasn't recreated because its other apps were removed or moved in the file. "<App>" was placed on its own.` + Undo. With 2+ survivors the folder is built and the skipped members are reported. With none: `…because its apps were removed or moved in the file.` | Same after `Keep mine`. |
| CM-18 | Rename vs an agent rename of the same item. | Mine wins (the item is "just touched"). | Same after `Keep mine`. |
| CM-19 | Toggle `Ask before merging` on and off with ops pending. | The next conflict follows the new value. The setting is in DataStore, not launcher.json, so toggling it doesn't write the file. | — |
| CM-20 | Syncthing conflict siblings `launcher.sync-conflict-*.json`, or `launcher (1).json`. | Ignored by the loader. A dismissible notice names them, and status.json lists them. Never merged or deleted. | Same. |
| CM-22 | Undo, file unchanged since the merge. | Pre-merge content restored (compare by ID; item order isn't semantic). Snapshots: pre-merge file + my merged model in history. Undo is one-shot. | n/a |
| CM-23 | Undo after an external change that doesn't touch the merged items (git pull via rename, **before** the next poll). | Only merged items are reverted by ID onto the new file; the external change is kept. The pulled file, pre-merge file and merged model are all in history. | n/a |
| CM-24 | Undo after an external change that moved/edited a merged item, or occupies the cell Undo would restore. | Cancelled, no write. Exact copy: `File changed again, Undo cancelled.` File bytes unchanged. Pre-merge file still in history (from the merge). | n/a |
| CM-25 | Undo when the file is now invalid or deleted. | Cancelled with the same message. Invalid bytes untouched; a deleted file isn't recreated. | n/a |
| CM-26 | git pull during the 10 s snackbar, polled (model reloads). | Undo stays offered (an external reload doesn't expire it) and then follows CM-23/CM-24. A local edit written after the merge, or ✕, expires Undo. Residual risk: an external write between Undo's hash read and its write (milliseconds) isn't detectable through SAF. | n/a |
| CM-21 | git: the pull brings a merge-conflicted file (`<<<<<<<` markers). | B1 with line and column, last-good kept. My pending ops stay queued until a valid file arrives, then merge as above. | Same (B6 once it's valid). |

Unit-level table test for the replay engine: rows for {Move, Add, Remove, Rename, CreateFolder} × {target exists, removed, cell occupied, duplicate, moved to another folder}.

## 7. Live reload and latency targets (A§4, A§15 OQ-22, OQ-23, A§11)

| ID | Steps | Expected / target | Level |
|---|---|---|---|
| LR-1 | File lands, then the user presses Home (`onStart`/`onResume` poll). | Visible **≤ 1 s** after landing on the device. | I, M |
| LR-2 | File lands while the home screen is already showing. | Visible **≤ 3 s** (2 s poll + 300 ms stability + parse). | S (fake clock), I, M |
| LR-3 | ContentObserver callback fires. | Immediate poll. | S, R |
| LR-4 | Launcher in the background. | Zero polls (assert with the fake clock and no wakeups). | S |
| LR-5 | Cold start, 300-item config. | First frame **< 500 ms** on a mid device, from last-good. | I (macrobenchmark) |
| LR-6 | Write-back debounce. | Write starts 750 ms after the last edit and never mid-drag. | S |
| LR-7 | **Poll cost and battery** (A§17 Q10). Targets: ≤ 5 ms CPU per poll (one children query, plus a ≤ 2 MiB hash read every 5th poll), < 1 %/h battery with home on screen, zero polls when home isn't visible. | **Per-poll cost:** a Macrobenchmark or instrumented trace with `Trace.beginSection("cl-poll")` around `engine.poll()`, then read the slice CPU time in Perfetto. Report the median and p95 over 300 polls with a 5 KB file and with a 2 MiB file (the hash poll). Run on the A53. **Battery:** A53, screen on at fixed brightness, launcher in front, airplane mode on, for 60 min. `adb shell dumpsys batterystats --reset`, then afterwards `adb bugreport` and load it in **Battery Historian**. Compare the app's estimated mAh with the battery capacity: < 1 % of capacity per hour. Repeat with home in the background for 15 min and assert 0 `cl-poll` slices and no wakeups. | I, M |
| LR-8 | The file is replaced via rename (new docId), as git or Syncthing do. | Re-found by name and reloaded. | S, I |

Sync transfer time is excluded from all targets (A§15 OQ-23).

## 8. New and uninstalled apps, Unsorted (A§3 Unsorted, A§7, A§15 OQ-25 to OQ-28)

| ID | Steps | Expected | Level |
|---|---|---|---|
| UN-1 | Install an unplaced app, `unsorted.mode: drawer` (default). | In the drawer. Home pages untouched, file untouched. | R, M |
| UN-2 | Same with `mode: page`. | Also shown on a synthetic last page titled `pageTitle`. Not written. | R |
| UN-3 | Same with `mode: hidden`. | The synthetic Unsorted page is removed (only that). The app **still shows in the drawer**. | R | A§17 Q11 |
| UN-4 | App in `drawer.hidden`. | Not in the drawer and not in Unsorted. Still shows and launches where it's placed explicitly. Listed under Settings → Hidden apps, where unhiding writes the file. | R | A§17 (b) |
| UN-4b | **The drawer includes placed apps:** an app placed on a page, one in the dock and one in a folder. | All three are listed in the drawer (personal plus badged work), minus `drawer.hidden`. | R, I | A§17 (b) |
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
| WP-3 | File contains `profile: work` items while "Include work apps in config" is off. | Rendered greyed with the work badge and the hint `Turn on "Include work apps in config" to use this.` Not invalid, never deleted. Turning the setting on makes them live **without a write** (assert 0 writes). | R | A§17 Q12 |
| WP-4 | Same package in personal and work. | Two distinct items keyed by (package, activity, profile). | R |
| WP-5 | Work profile paused. | Items greyed with the badge. Tap calls `requestQuietModeEnabled(false)` (system prompt). Not deleted. | I, M |
| WP-6 | Work profile removed, or the file synced from a phone without one. | Greyed, kept in the file, "Remove from layout" offered. | R |
| WP-7 | Two work profiles. | First one wins. | R |
| WP-8 | Numeric user IDs or serials. | Never written: grep the written file and status.json. `profile-numeric.json` and `profile-private.json` are rejected. | U |
| PS-1 | Private space (API 35+) unlocked. | Private apps appear only in the private-space section at the bottom of the drawer. Not in Unsorted, and not affected by `drawer.hidden`. **Drag is blocked:** long-press offers only the system actions, and they can't be dropped on a page, the dock or a folder. Compose test: start a drag on a private tile and assert no `Edit` is emitted. | I, M | A§17 Q13 |
| PS-2 | Private space locked. | Its apps appear nowhere. | I, M |
| PS-3 | Any write or status.json update while private space is unlocked. | Contains no private-space package. | I |

## 10. Banners B3, B4 and B8 (A§17 AQ-8, AQ-9)

| ID | Steps | Expected | Level |
|---|---|---|---|
| BN-1 | B3 vs B4: see FI-15. Also check what each banner offers. | B3: `Reconnect` only. B4: `Create from current layout` and `Reconnect`. | S, R |
| BN-2 | B4, then tap `Create from current layout`. | Writes last-good (the starter only if there's no last-good). The banner stays until the next poll loads the valid file, then clears. It isn't cleared by the tap itself. | S, R |
| BN-3 | B4 while the git client is mid-pull (the file shows up 2 s later). | No write from us. B4 clears on its own when the file appears. | S, M |
| BN-4 | B8: load a file with an older `schemaVersion`. Needs a v0/v2 migration fixture once v2 exists; until then use a fake migration in a unit test. | Notice `launcher.json uses an older format (v{N}). It will be upgraded on your next change.` with `Upgrade now` and `✕`. Lowest priority: hidden while any B1-B6 is showing, and stacked below them. | R |
| BN-5 | B8 `Upgrade now`. | The original goes to `.configlauncher/backup/launcher.v{N}.{timestamp}.json`, then a write through the normal path (hash check; auto-merge or B6 on conflict). | S, R |
| BN-6 | **B8 `✕`, dismissed per hash:** dismiss, then (a) reload the same bytes, (b) restart the launcher, (c) have the agent write a *different* older-format file, (d) make an edit. | (a) and (b): B8 stays hidden, because the dismissed hash is in DataStore. (c): B8 comes back for the new hash. (d): the edit still upgrades, with a backup first. | R |
| BN-7 | B8 while B2 is up. | Only B2 shows. B8 appears once B2 clears. | R |

## 11. Skip for now and app-private storage (A§18.2)

| ID | Steps | Expected | Level |
|---|---|---|---|
| SK-1 | Onboarding step 2 → `Skip for now`. | Source = `filesDir/config/source/launcher.json`, created explicitly by this choice. Settings shows `Layout stored on this phone only` with `Choose folder`. No B3 or B4 ever. | R, I, M |
| SK-2 | App-private mode: make an edit. | True atomic temp + `renameTo`. `.configlauncher/` artifacts go under `filesDir/config/source/.configlauncher/`. FileObserver-driven, with no 2 s polling (assert 0 `cl-poll` slices). | S, R |
| SK-3 | App-private mode, disk full. | B5. The edit is kept in memory. | S |
| SK-4 | **Phone → folder**, folder **without** `launcher.json`. | One write of the current layout to the folder, then the folder becomes the source. The app-private file is not deleted. | S, R, M |
| SK-5 | Phone → folder, folder **with a valid** `launcher.json`. | Dialog `This folder already has a launcher.json.` (a) `Use the folder's layout`: the folder file is loaded and the app-private file is kept as a history entry. (b) `Replace it with mine`: the folder file is copied to `.configlauncher/backup/` first, then mine is written. | S, R, M |
| SK-6 | Phone → folder, folder with an **invalid** `launcher.json`. | Same dialog plus `See problems`, which opens the error detail. Neither choice loses either file. | R |
| SK-7 | **Folder → phone** (Settings → `Use phone storage`). | The current layout is copied into the app-private file, and the persisted grant is released (it's gone from `persistedUriPermissions`). No B3 afterwards. | R, I |
| SK-8 | Round trip: phone → folder → phone. | `Use phone storage` **overwrites** the app-private file with the on-screen layout (temp + rename) after snapshotting the previous app-private version to history (A§18.6 Q-v3-1). Folder → phone → folder then follows the ux.md §7 connect-later flow. | R |
| SK-9 | Debug build: `adb push` a new `launcher.json` into the app-private path (`run-as`). | FileObserver picks it up within 1 s. | I |

## 12. Drawer, keyboard and label backdrop (A§17 (b), A§18.1, A§18.2)

| ID | Steps | Expected | Level |
|---|---|---|---|
| DR-1 | Open the drawer (default settings). | The search field is focused and the keyboard is up (Q-C2). The list includes placed apps (UN-4b). | I, M |
| DR-2 | Keyboard up, press **Back**. | First Back hides the keyboard only. A second Back closes the drawer. | I, M |
| DR-3 | Keyboard up, **swipe down** on the list. | Hides the keyboard first. A further swipe down closes the drawer. | I, M |
| DR-4 | Settings → `Open keyboard with drawer` **off**, then open the drawer. | No focus, no keyboard. Tapping the field shows it. The setting survives a restart (DataStore) and doesn't touch launcher.json. | R, I |
| DR-5 | Keyboard up with 2000 apps: type 3 letters. | Results filter on each keystroke (UX§2 rules). Enter launches the first result. | I |
| DR-6 | Hardware keyboard or Samsung DeX (A53/S25 if available). | Search field always focused. The IME is requested only when `hardKeyboardHidden != HARDKEYBOARDHIDDEN_NO`; with a hardware keyboard/DeX none is forced. `Open keyboard with drawer` controls focus only (A§18.6 Q-v3-2). Bug: #39. | M |
| LB-1 | Settings → `Label backdrop` (off by default). | Off: white label with a 1 dp 50% black shadow. On: a rounded 40% black scrim behind each home label. Stored in DataStore, never written to launcher.json (assert 0 writes on toggle). | R, I |
| LB-2 | Backdrop on, on a busy, bright wallpaper. | Label contrast ≥ 4.5:1 against the scrim (screenshot + contrast check). Applies to home-grid labels incl. the label under a folder icon; **not** the open folder sheet, the drawer or the dock (A§18.6 Q-v3-3). | M |
| LB-3 | Backdrop on with a 60-emoji label and with a bidi-stripped label. | The scrim wraps the ellipsized text, with no overflow outside the cell. | I |

## 13. API-level gates (minSdk 29)

minSdk is 29 (Android 10). RoleManager, `LauncherApps.getProfiles`, shortcuts, adaptive icons and `requestQuietModeEnabled` are therefore always available. The pre-29 cases from the minSdk-23 draft are dropped: shortcuts below 26, plain icons, the home-settings fallback, the SAF `Show internal storage` hint. The old wall-display devices are **test-only** (Q-v3-9). Robolectric runs at `@Config(sdk = [29, 35])`. Android Lint `NewApi` is an error in CI.

| ID | Steps | Expected | Level |
|---|---|---|---|
| API-1 | Install on API 28 (the S8+, if it's used as a test-only device, or an API 28 emulator locally). | `INSTALL_FAILED_OLDER_SDK`. Documented as unsupported, not a bug. | M |
| API-2 | Onboarding `Set as Home` on 29+. | `RoleManager.createRequestRoleIntent(ROLE_HOME)`. If the user declines, the onboarding step stays, and the app still works as a normal activity minus shortcuts (A§8). | R (sdk 29), I |
| API-3 | Shortcut items on 29+. | Launch when we're the default launcher. Otherwise disabled with `Set as Home to use shortcuts` (A§8). | R, I |
| API-4 | Private space below 35. | No private-space API calls. Gated at 35. | R (sdk 29) |
| API-5 | Quiet mode on 29+: paused work app. | Greyed with the badge. Tap brings up the system unpause prompt (`requestQuietModeEnabled`). | R, I |
| API-6 | Lint `NewApi` = error. | A deliberately ungated API 30+ call in a scratch branch fails CI (one-time check). | CI |

## 14. Samsung One UI quirks

Marked **(R)** where the expectation comes from research (public reports or docs), not from a device run. Confirm on the A53 first.

| ID | Steps | Expected / what to record | Basis |
|---|---|---|---|
| OU-1 | **Default launcher prompt:** install, open, `Set as Home`. Then press Home after installing another launcher, and after an OTA. | The RoleManager dialog on One UI 8 (A53/S25). Record whether One UI resets the default home after an update or an app update (reported on some OEMs, A§13 "home role resets"). | (R) for resets |
| OU-2 | **Secure Folder as a profile.** One UI 8 reclassified Secure Folder as a **private** profile. Older One UI implemented it as a **work (managed) profile** (Android Authority, Android Police reports on One UI 8). A53/S25: Secure Folder exists, open or hidden. | One UI 8: not in our drawer as a work profile, and not shown unless we hold ACCESS_HIDDEN_PROFILES + HOME (we don't). On older One UI (e.g. the optional A51 on Android 13): Secure Folder may show up as the "first managed profile" and be treated as `work`. Record which profile `InstalledApps` picks when both a real work profile and Secure Folder exist. Ruling A§18.6 Q-v3-4: skip managed profiles containing `com.samsung.knox.securefolder`; real work profile wins; detected Secure Folder is treated like private space. Heuristic **unverified**: see SF-A51. Bug: #40. | (R) |
| OU-3 | **Game Launcher / Game Booster.** | Game Launcher is an app, not a profile. Record whether games are hidden from LauncherApps when "Hide game apps" is on in Game Launcher. Expected: still listed by LauncherApps (they're normal launchable activities), unless Samsung hides them. | (R), unverified |
| OU-4 | **Battery optimisation and app sleeping:** Settings → Battery → Background usage limits (Sleeping / Deep sleeping apps), and "Put unused apps to sleep". | Our polling runs only while home is visible, so sleep should not affect it. Check: (a) after 1 h idle with the launcher in "Sleeping apps", pressing Home polls immediately (LR-1 ≤ 1 s); (b) the launcher is never auto-added to Deep sleeping; (c) the git client's own sync isn't killed (that's the git client's problem, but record it). The default home is normally exempt from app standby (R). | (R) |
| OU-5 | **One UI Home gestures:** Settings → Display → Navigation bar → Swipe gestures, with our launcher as Home. | Gestures stay enabled with a third-party home. Record (a) swipe-up-to-home latency before the first tap registers (a known One UI delay with third-party launchers: recents are provided by One UI Home), and (b) whether the recents animation targets our icon. Document it, but don't treat it as our bug. | (R) |
| OU-6 | **Edge panels:** enable the Apps edge panel and open apps from it. | The panel works independently of our launcher. Opening an app from it and returning Home leaves our state intact (no reload, no re-poll beyond the resume poll). | (R) |
| OU-7 | Samsung `Show internal storage` and the "Samsung My Files" provider in the SAF picker (A53). | The picked git folder's authority is `com.android.externalstorage.documents`, so no cloud warning (Q15). | (R) |
| OU-8 | One UI "Lock Home screen layout" and "Home screen only" settings. | Apply to One UI Home only. No effect on us. Record it. | (R) |

## 15. Devices, emulators and performance

### 15.1 Device matrix
API levels are the latest **official** Android for each model, as listed in A§18.3. The version actually installed on Christopher's units isn't known; record it on first run (`adb shell getprop ro.build.version.sdk`, and the One UI version from Settings → About).

| Order | Device | Latest official Android (API) | Coverage |
|---|---|---|---|
| 1 | **Galaxy A53** | 16 (36) | **Full manual pass**, git sync first, One UI quirks (§14), poll cost and battery (LR-7), cold start (PF-2) |
| 2 | **Galaxy S25 Ultra** | 16 (36) | Full pass after the A53. Private space, Secure Folder (OU-2) |
| optional | Galaxy A51 | 13 (33) | Smoke. Secure Folder on older One UI (OU-2). No private space |
| test-only | S8+ (28), Tab S2 (24), OnePlus 2 (23), OnePlus One (modded, ROM version unknown) | Below minSdk 29 | Not supported. At most API-1 (install refused). No performance targets |

### 15.2 Emulator matrix (CI)
| API | Image | Purpose |
|---|---|---|
| 29 | `default` x86 | minSdk floor. RoleManager, `getProfiles`, quiet-mode request |
| 35 | `google_apis` x86_64 | Private space. Edge-to-edge |
| 36 | `google_apis` x86_64 | Current (A53/S25 level) |

Implemented as `.github/workflows/emulator-smoke.yml` (PR #23): nightly, on demand, on every PR and on pushes to main. It installs and cold-starts with crash/ANR detection, plus `am start -W` timings. Instrumented tests (`connectedDebugAndroidTest`) join the same matrix once `app/src/androidTest` exists.

### 15.3 Performance budgets and how to measure them
| ID | Budget | Device | Method | Pass |
|---|---|---|---|---|
| PF-2 | Cold start to first frame < 500 ms with a 300-item config (A§11) | A53 | `adb shell am force-stop dev.nasawa.configlauncher`, then `adb shell am start -W -n dev.nasawa.configlauncher/.MainActivity` and read `TotalTime`. 10 runs after one warm-up. Cross-check with Macrobenchmark `StartupTimingMetric` (`timeToInitialDisplayMs`), R8-minified release build with a baseline profile. | Median < 500 ms |
| PF-5 | Poll cost and battery | A53 | See LR-7 | ≤ 5 ms per poll, < 1%/h |

## 16. Manual QA matrix, for later

**git is tested first** (A§18.1). Syncthing and Drive come after.

| ID | Setup | Steps | Expected |
|---|---|---|---|
| MG-1 | **git**, primary: a git client folder on the A53 (e.g. MGit, or Termux + git), cloned from a repo the PC agent pushes to. | Agent commits and pushes; pull on the phone; press Home. | Reload ≤ 1 s after the pull on Home, ≤ 3 s if home is showing. The file is replaced, so the docId may change and it's re-resolved by name. |
| MG-2 | git | Drag on the phone, commit and push from the git client, pull on the PC. | A small canonical diff (2-space, schema order). `revision` + 1. |
| MG-3 | git | Phone drag not yet committed, then pull a PC change to a different item. | Depends on the git client: it may refuse the pull (dirty tree). If it merges: auto-merge snackbar `Kept your 1 change.` (CM-7). Record the client's behaviour. |
| MG-4 | git | Pull a conflicted merge (markers; fixture `invalid/git-conflict-markers.json`). | B1 with line/column, last-good kept, no write from us (CM-21). |
| MG-5 | git | Check `git status` after a week of use. | Only `launcher.json` (and backup/broken, by preference) changes. `.configlauncher/launcher.json.tmp` and `status.json` are gitignored per the README snippet (A§17 Q14). |
| MG-6 | git | `git checkout` an old commit (file replaced while home is showing). | Reloads the old layout. Undo is possible with git, and the launcher's history has the previous one. |
| MS-1 | Syncthing (second) | Edit on the PC, then drag on the phone, then edit on both at once. | As MG-1/MG-3. A Syncthing conflict copy shows the notice (CM-20). |
| GD-1 | Google Drive (third, best effort) | Pick a Drive folder. | Soft warning `Cloud folders can take a while to sync. A Syncthing or git folder works best.` with `Use anyway` / `Choose another folder`. Record latency. Airplane mode leads to B5 or a delayed write, and the edit is never lost. |
| WP-M | Work profile (Test DPC on an emulator if no phone has one) | WP-1, WP-2, WP-3, WP-5 | As in §9 |
| PS-M | Private space on the S25 Ultra or A53 (Android 15+) | PS-1, PS-2, PS-3 | As in §9 |
| SF-M | Secure Folder on the A53 and A51 | OU-2 | Record it |

### Pending manual case (unverified heuristic, do later)
| ID | Device | Steps | Record / expected |
|---|---|---|---|
| SF-A51 | Galaxy A51 (Android 13, pre-One UI 8), Secure Folder set up; optionally also a real work profile. Later repeat on A53/S25 (One UI 8). | `adb shell pm list users`; `adb shell dumpsys user`; for each managed user `adb shell cmd package query-activities --user <id> -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \| grep -i securefolder`; open our drawer. | **Pending.** Confirm Secure Folder appears as a *managed profile* whose launchable activities include `com.samsung.knox.securefolder`; that it's excluded and the real work profile wins (A§18.6 Q-v3-4). On One UI 8 record what shows (expected: private profile, invisible to us). Paste the outputs here. |

## 17. Manual checklist: Galaxy A53 (≈10 min, git sync, no app-killing)

Before you start: the launcher is set as Home, the config folder is the git repo folder in your git app, and the PC side can commit and push `launcher.json`. Have one small app ready to install.

1. **Home (30 s).** Press Home. The layout matches the file. No banner.
2. **Agent edit via git (2 min).** On the PC, move one app, commit and push. In the git app on the phone, pull. Press Home: it updates within about a second.
3. **Your edit via git (1.5 min).** Long-press and drag one icon. In the git app, commit and push. On the PC, pull: the file shows the move as a small diff.
4. **Merge (1.5 min).** Drag one icon, and before committing, pull a PC change that moved a *different* icon. Expected: `Layout updated from launcher.json. Kept your 1 change.` Both moves are kept. Tap **Undo** once to see the PC version come back, then drag again if you like. (If your git app refuses to pull with local changes, note that and skip.)
5. **Broken file (1 min).** On the PC, delete one `}`, commit, push and pull. The layout stays and the banner says the file can't be read, with the line number. Revert, push and pull: the banner goes away.
6. **Drawer (1 min).** Open the drawer: the keyboard comes up. Type 2 letters to filter. Check that an app you placed on the home screen is also in the list. Press Back twice (keyboard, then drawer).
7. **New app (1 min).** Install the small app: it appears in the drawer, not on a home page.
8. **Settings (1 min).** Turn on `Label backdrop`: dark pills behind the home labels. Turn it off again. Optionally turn off `Open keyboard with drawer` and reopen the drawer.
9. **Gestures (30 s).** Open any app and swipe up to go Home. Note if the first tap after returning feels delayed (a known One UI effect, just record it).

Report anything that differs, with a screenshot.

## 18. Automation and CI

- `build` job (existing): `./gradlew testDebugUnitTest` (JVM + Robolectric + store fakes, including `FixtureConformanceTest`, `KotlinOnlyConformanceTest`, `WriterRefusalTest`) and `assembleDebug`.
- Add a step for `python3 test-fixtures/check_schema.py` (needs `pip install jsonschema`).
- `emulator-smoke` job (PR #23): API 29/35/36 matrix, nightly, on demand, on every PR and on pushes to main.
- Robolectric at `@Config(sdk = [29, 35])` for the API-gate cases. Lint `NewApi` as an error.
- A test that fails on `android.*` imports in `model` and `config` (A§10, exists as `PureLayerTest`).

## 19. Long-press, tap and drag (ux.md §1 l.99–100, §2 l.150, §4 l.172, §12; A§15 #11, A§17 Q13; issue #53)

**Why v3 missed #53:** every UI test called `performClick()` or tested pure models, and the smoke script only cold-starts and checks for a crash or ANR. No test held a pointer or asserted a sheet or menu, so the `clickable` + `detectDragGesturesAfterLongPress` path on a stationary hold went unexercised.

| ID | Case | Expected | Auto |
|---|---|---|---|
| LP-1 | Tap a home app, shortcut or dock tile (< long-press timeout). | Launches. No sheet. | R `LongPressGestureTest.tapLaunches` |
| LP-2 | Stationary long-press (≥ `ViewConfiguration.longPressTimeout`, ~400–500 ms; jitter < 8 dp), release. | Item sheet opens with the **real app name and package** (even with a `label` override, A§15 #11), `Edit layout`, `App info`, `Remove from home`. **Nothing launches** on release. | R `longPressDoesNotLaunch`, E `scripts/longpress-smoke.sh` |
| LP-3 | Long-press on a folder tile; on a shortcut tile. | Same item sheet (folder: label + member count; shortcut: owning app's real name/package). | R/M |
| LP-4 | Hold then move > 8 dp (after 300 ms). | Drag starts (lifted tile), no sheet. Release on a new cell writes once. Moving back to the origin before release is still a drag, not a menu. | R (state machine) + M |
| LP-5 | Move > 8 dp *before* the timeout. | Page swipe / scroll, neither drag nor sheet. | M |
| LP-6 | Long-press empty grid space. | Home menu: `Edit layout`, `Reload layout`, `Settings` (app-only mode adds `Choose folder`). | E/M |
| LP-7 | Long-press a drawer app. | `Add to home` (first free cell, page 1 onward, else a new page), `Hide` (one write), `App info`. Work apps: `Add to home` only with "Include work apps". Drawer tiles don't drag from a stationary hold. | R/E |
| LP-8 | Long-press a folder child (open folder sheet). | Item sheet for the child; no launch. | R/M |
| LP-9 | Long-press a private-space app (A§17 Q13). | Only the system actions (`App info`); no `Add to home`, not draggable. | M (API 35+) |
| LP-10 | Item sheet actions: `Remove from home` (one write; folder → Undo snackbar per ux §14 R3), `App info` (opens system app details for the right user/profile), `Edit layout` (enters edit mode). | As described; one write per action. | R/M |
| LP-11 | Haptics. | ux.md doesn't specify haptics. **Not tested**: if added, expect `HapticFeedbackType.LongPress` at the threshold only, not on tap. Noted as an open question. | — |
| LP-12 | TalkBack: double-tap-and-hold on a tile; the tile node exposes `AccessibilityAction.ACTION_LONG_CLICK` **on the same merged node that carries the label**. | Opens the same sheet. The label isn't a separate focus stop without actions. | R `tileExposesLongClickAction` |
| LP-13 | TalkBack custom actions (ux.md §12): `Move to…`, `Add to folder…`, `Move to dock`, `Remove from home`, `Hide`. | Present on every placed item; each is one write. | R (semantics `CustomActions`) |
| LP-14 | Back / Home while a sheet or menu is open. | Back closes it; Home closes it and leaves edit mode (ux §14 R4). | M/E |

Automation: R = Robolectric Compose UI test (`performTouchInput { longClick() }`), E = `scripts/longpress-smoke.sh` (seeds app-only mode with one tile via `run-as`, `input swipe x y x y 800`, then a `uiautomator dump` that must contain `App info` and the real package, and the launcher must still be on top).

## 20. Tablets, landscape and foldables (ux.md §1.5, Archimedes' large-screen notes)

Evidence so far: QA run 2 ([CI run 40377723279](https://github.com/Nasawa/config-launcher/actions/runs/40377723279) on bee4fe3; the #177 matrix), points t7-port, t7-land,
t10-port, t10-land and fold-open at font 1.0 / 1.3 / 2.0. Until these checks exist, #177's Q2 interim stands: tablet,
landscape and fold G1–G3 are advisory through dev7 and blocking at dev8.

**Gaps in that evidence** (each needs a new point or fixture before its check can block):
- The fold points are a **simulated** inner screen (`wm size` 841 × 701). No live `FoldingFeature` on the matrix AVDs;
  L9 covers hinge geometry with `androidx.window:window-testing` injection. A real fold ↔ unfold / cover-screen matrix
  row stays later and advisory.
- No phone-landscape point, no RTL point, no width-class boundary points (599/600, 839/840 dp). The fold's 841 dp is 1 dp
  above Expanded.
- No cards on large screens, and no 6–7 item dock.

| # | Check | Expected | How |
|---|---|---|---|
| L1 | **No write on a window change.** Rotate ×4, fold/unfold ×2, resize in multi-window | `launcher.json` hash and `revision` unchanged, no `status.json` write, history unchanged | File hash before/after; logcat for writes |
| L2 | **Positions are exact** on every point | Every item and card is in column `x`, row `y` (span `w` × `h`) | From the xml: `floor((centre − blockLeft) / pitchW)` |
| L3 | **Cap and centring** on Medium/Expanded | `pitchW` ≤ 144 dp. Cell aspect: on-screen `W ≤ 1.25·H + 1 px` and `H ≤ 1.5·W + 1 px` (hard user-visible cap; +1 px is measurement/rounding slack — Sprite derives `W` from `H` in px with floor so the cap never rounds up). Left margin = right margin **within 1 px, measured inside the system-bar/cutout safe area** (`WindowInsets.safeDrawing`), not from the raw screen edge. Compact phone output unchanged (verified by `PhoneOutputGoldenTest`'s config JSON/SHA check; there are no screenshot goldens) | xml bounds inside safe insets; `PhoneOutputGoldenTest` |
| L4 | **Minimum and labels** (ladder-aware) | **Step 1 pass threshold:** `pitchH` ≥ **84 dp** at font 1.0 on t7-land and t10-land **once the end-edge rail is present** (L5). That is 56+16+12 — step 1 (56 dp + label) **runs only at ≥ 84 dp**, so a pass can never clip a label. §1.1's **80 dp** stays the preferred cell floor (touch/design intent), not an L4 pass number; between 80 and 84 the ladder is already on step 2. The rail's expected ~85 dp clears 84 — no column/row reflow. **When pitchH &lt; 84** (bottom dock still eating height, Compact landscape, etc.) the §1.5 icon ladder runs: step 2 needs `pitchH` ≥ **76 dp** (48+label, labels whole); step 3 needs `pitchH` ≥ **60 dp** (48, labels cleanly hidden). **On every point** every label is full height or absent, never clipped (L15 / G1) — no step may pass with a clipped label. Until L5 lands, t7-land with a bottom dock (~64.5 dp on #194) is expected step 3 — the ≥84 half stays advisory there; the labels-whole-or-absent half still applies. No title-bar fit step for short windows (Q-A12) | xml pitch + label bounds ≥ 1 line or no label node |
| L5 | **Dock placement** | Rail on t7-land and t10-land (end edge; left in RTL), with `Apps`/`⋮` at its head; bottom on t7-port, t10-port and fold-open. Rail inside `safeDrawing` (nav bar on the same side, cutout side) | xml + insets dump |
| L6 | **Dock count.** Fixture `dock7` at t7-land, phone landscape and t7-port | All 7 fit, slots ≥ 48 dp, in file order (top → bottom on the rail); the rail scrolls rather than hides | New fixture `dock7` (dev8). Rail-geometry half stays **advisory** until the dock-rail PR lands; bottom-dock points can block earlier |
| L7 | **Page continuity.** On page 2, rotate and fold/unfold | Still `Second`, `Page 2 of 2` (restored by page `id`); edit mode, drawer and open sheets kept | Screenshot + xml |
| L8 | **Drag on a config change.** Start a drag, then rotate | Drag cancelled, nothing written, item in its old cell | Instrumented rotate-mid-drag (dev8) |
| L9 | **Hinge.** `FoldingFeature` injected via `androidx.window:window-testing` (`WindowLayoutInfoPublisherRule` + FoldingFeature test factory: book, tabletop, flat). Fold and posture come only from `WindowInfoTracker` | No cell, card or dock slot intersects the hinge bounds; book uses the start half, tabletop puts the grid on top and the dock below. Flat: hinge ignored | Instrumented test with window-testing. A real fold-emulator posture row stays a later **advisory** matrix point (current fold points are `wm size` only) |
| L10 | **Placement is window-independent** | `Move to…` shows Column 1–4 / Row 1–5 everywhere; Undo's free spot, `Add to home` and the widget picker give the same answer on every point for the same file | Pure unit test on placement functions (dev8, cheap on JVM) + one UI smoke |
| L11 | **Margins** | Long-press in a side margin opens the home menu, swipe-up there opens the drawer, a drop there is refused, and a tap at each column centre hits the right item | Instrumented (dev8 if time, else dev9) |
| L12 | **Class boundaries, phone landscape, RTL** | Points at 599/600 and 839/840 dp width, phone landscape (e.g. 800 × 360: rail, labels hidden whole, icons ≥ 48 dp) and one RTL point (grid mirrored, rail on the left) | New matrix rows (dev8) |
| L13 | **Cards.** Fixture with a 2×2 and a 4×1 card | `k` ≤ 1.5 on t10-land, no stretching, nothing crosses the hinge | New fixture `cards-large` (dev8) |
| L14 | **Fit warning comes from the load only** (Q-A12). Fixture with a long `{date}` bar. Load on the fold's inner screen, fold to the cover screen, rotate, resize in multi-window, then reload | No `status.json` write on fold, rotate or resize; the cover-screen bar steps down the ladder and truncates on screen with no new warning. The reload rewrites the warnings from a portrait bar at the cover screen's `smallestScreenWidthDp`. The matrix T3 check compares the warning with that load-time check, not with the step on screen (they match on a phone in portrait) | File hash + logcat; xml of the bar |
| L15 | **Icon ladder at 360 dp, font 1.3** (§1.5). `s360-mdpi-v2` (360 × 640 dp, font 1.3, title bar, 2 pages) after the 56 dp change | **Pass: every label is whole or cleanly hidden (no label node, still read by TalkBack), never clipped.** Expected today is step 2 (48 dp icons, labels whole): row pitch 77.6 dp, tile 73.6 dp, 48 + 17 + 8 = 73 fits with only 0.6 dp spare, so rounding or density on a real device may land on step 3 (48 dp, labels hidden), which also passes. The check doesn't pin the step. With a notice-row chip up it's step 3. **Fail:** a cut label (G1), a label overlapping its icon, or a 56 dp icon with its label cut | xml: each label height ≥ one full line or no label node; G1 |

**dev8 automation scope (Pragma + Archimedes, from #193 / #194 eeff98f).** Automate now: **L10** (JVM unit), **L8** (instrumented rotate mid-drag), **L12** (matrix rows), **L6** (`dock7`) and **L13** (`cards-large`) as new fixtures — L6's rail half advisory until the rail lands — and **L9** via `androidx.window:window-testing` (not a real fold AVD). **L11** if time else defer to dev9. L1–L5 and L7 stay on the existing large-tier matrix (L3/L4 wording above; L5 advisory until the rail PR). A real fold-emulator posture row is a later advisory add-on for L9.

**Config overrides (after dev8; placeholders until Q-A11 has a schema shape).** dev8 ships the §1.5 defaults only. Until
then the only check is O0.

| # | Check | Expected | When |
|---|---|---|---|
| O0 | An override key (e.g. `windowOverrides`) in a dev8 file | Rejected like any unknown key: B2 on the phone, an error in `validate.py` | dev8 |
| O1 | Columns/rows override per width class and orientation | That window uses the override grid; the other windows keep theirs; no write on rotate | after dev8 |
| O2 | Items outside a smaller override grid | Skipped on that window only, never moved or written; shown again on a window whose grid holds them; the warning comes from the file and matches on every device | after dev8 |
| O3 | Cell cap off, and a custom cap size | Cells stretch, or cap at the given size; still centred when on | after dev8 |
| O4 | Dock edge per orientation and posture (`top`, `bottom`, `start`, `end`) | Dock on that edge, inside `safeDrawing`, in `dock[]` order, mirrored for `start`/`end` in RTL | after dev8 |
| O5 | Orientation lock `portrait`, `landscape`, `system`, incl. a landscape-only phone | The lock holds; the rule doesn't flip itself after the lock rotates the screen | after dev8 |
| O6 | Fold: half-folded `span`, flat `asPhone` | `span`: home across both halves; `asPhone`: phone layout on the inner screen; no write on fold or unfold | after dev8 |
| O7 | Icon scale | Icons at scale × 56 dp, the ladder still runs, touch targets ≥ 48 dp, labels whole or hidden | after dev8 |
| O8 | Several matching entries | Later entries win field by field; an empty `when` matches every window | after dev8 |

Manual (TalkBack and keyboard, one tablet in landscape and one RTL point): focus order is rail head → banner → notice row
→ page label or title bar → grid in reading order → page dots → rail dock items top to bottom, whichever side the rail
is on. D-pad from a grid cell next to the rail moves to the nearest rail slot and back. Side margins take no focus.

## Open questions (v3)

All v3 questions are resolved in A§18.6 (Q-v3-1, -2, -3, -4, -6, -9); the rulings are folded into SK-8, DR-6, LB-2, OU-2, CM-6/CM-22–26 and §13. Remaining unknown: the Secure Folder detection heuristic (SF-A51, pending manual run).
