# Pre-release screen matrix (emulator, free tooling)

Owner: Grace (QA). Status: **approved by Christopher** (#177); run 1 done (§6), a rerun after the run-1 harness fixes is next. Extends test-plan §15.2 (emulator matrix) and §18
(automation). It doesn't replace the device passes in test-plan §15.1/§17. Spec references: `ux.md §x` is
docs/ux.md and `A§x` is ARCHITECTURE.md. When this plan and a spec disagree, the spec wins, so fix this file.

**Why.** The app is going public. So far every layout check has run at Christopher's A51 widths (320 dp at max display
size, 411 dp default) on one API 29 emulator (evidence/pr167, dev6-a51). This matrix covers small, typical and large
phones, 7" and 10" tablets in both orientations, and an unfolded foldable. It also covers font scales 1.0/1.3/2.0 and
dark/light, using only free emulator images and adb.

**How.** There are three system images: API 29 `default` x86 (the minSdk floor), API 35 and API 36 `google_apis`
x86_64, the same images as test-plan §15.2. Inside each image, a point is set with adb:
`wm size` / `wm density` (screen), `settings put system font_scale`, `cmd uimode night yes|no` (theme), and
`settings put system user_rotation 1` with `accelerometer_rotation 0` (landscape). Each image boots once per shard,
and each point costs about 2–3 minutes, not a full AVD boot.

## 1. Matrix

`scripts/screen-matrix/points.tsv` is the source of truth. Each row is one *point* (a geometry plus a variant).

**Variants** (pairwise, not full factorial):

| Variant | Font scale | Theme | Fixture |
|---|---|---|---|
| v1 | 1.0 | light | `launcher.json` |
| v2 | 1.3 | dark | `launcher.json` |
| v3 | 2.0 (ux.md §12 maximum) | light | `launcher.json` |
| c1 | 1.0 | light | `launcher-chip.json` (adds a card that needs approval, so the §17.4 chip shows) |

**Geometries:**

| Geometry | px @ dpi | dp | Kind | Variants | Shard (image) |
|---|---|---|---|---|---|
| s320-ldpi | 240x427 @ 120 | 320×569 | simulated | v1 v2 v3 | small-a (API 29) |
| s320-mdpi | 320x640 @ 160 | 320×640 | simulated (the image's native size) | v1 v2 | small-a |
| s360-mdpi | 360x640 @ 160 | 360×640 | simulated | v1 (small-a), v2 (small-b) | small-a/b |
| s320-a51 | 640x1422 @ 320 | 320×711 | **device-shaped**: A51 at max display size (1080x2400 @ 540 → 320×711 dp, dev6-a51/widths) | v1 v2 v3 c1 | small-b (API 29) |
| s360-ldpi | 270x480 @ 120 | 360×640 | simulated | v1 | small-b |
| p360-xxhdpi | 1080x1920 @ 480 | 360×640 | simulated | v1 | phones (API 35) |
| p411-a51 | 1080x2400 @ 420 | 411×914 | **device-shaped**: A51 default, exact pixels and density | v1 v2 v3 c1; v1 again on API 36 | phones (API 35), fold (API 36) |
| l448 | 1344x2992 @ 480 | 448×997 | simulated large phone | v1 v2 | phones |
| t7-port / t7-land | 1200x1920 @ 320 | 600×960 / 960×600 | simulated 7" tablet | v1 v2 each | tablets (API 35) |
| t10-port / t10-land | 1600x2560 @ 320 | 800×1280 / 1280×800 | simulated 10" tablet | v1 v2 each | tablets |
| fold-open | 2208x1840 @ 420 | 841×701 | simulated unfolded inner screen | v1 v2 v3 | fold (API 36) |

In total there are 31 points across 5 shards (small-a 6, small-b 6, phones 7, tablets 8, fold 4).

- *Device-shaped* means the dp size and density bucket match a real device we own. It still runs AOSP fonts and
  system UI, not One UI, so it doesn't replace the A51 pass. In dev6-a51 the One UI font measured wider than the emulator's.
- *Simulated* means a plausible class size with no claim about any specific model. The generic 7" and 10" profiles stay
  (Christopher's decision); his exact tablet model is added as a device-shaped point later (Q1).
- No point is a real foldable posture: `wm size` can't do hinge, fold state or multi-display. A foldable device image
  is a later add-on.

**Fixtures** (`scripts/screen-matrix/`, AOSP packages only, so they work on every image; `scripts/validate.py` passes):
- `launcher.json`: two pages, `Home` and `Second`.
  - Tiles: `QA Settings`, one 40-character label (ellipsis), one wide label (`Wide WWWW`), and one package that isn't
    installed (`org.example.notinstalled`, tests the missing-app tile).
  - A Settings dock slot.
  - The A51 title bar from dev6-recheck: `{date:EEE d MMM yyyy}` / `{page}` / `{time:HH:mm}`, size `large`, with a backdrop.
- `launcher-chip.json`: the same, plus a card with `requires: 4` that needs approval (from pr172 `c172-launcher.json`).
- `settings.xml`: app-only mode, home prompt already shown, drawer keyboard off.

Fixtures live outside `test-fixtures/` on purpose, because CI diffs that tree against gen_fixtures.py.

## 2. Flows per point

`scripts/screen-matrix.sh` runs these on every point. Before the flows, it runs `pm clear`, sets the launcher as home,
and seeds the fixture with `run-as` (debug APK).

| ID | Flow | Pass | Spec |
|---|---|---|---|
| F1 | Cold start: `am start -W`, then RELOAD | No `FATAL EXCEPTION` or `ANR in dev.nasawa.configlauncher` in logcat, and no "Config Launcher isn't responding" dialog. System UI ANR dialogs (common on software-only emulators) are recorded and dismissed, not failed | test-plan §15.2, A§11 |
| F2 | Seeded layout + RELOAD broadcast | Home shows the seeded title bar (`Home, Pages`) and the dock | ux.md §5 (Reload) |
| F3 | Long-press a home item (800 ms) | Item sheet with `App info` and the real package, and the launcher still on top (nothing launched) | ux.md §1.1 gestures; test-plan §19 LP-2 |
| F4 | Long-press empty grid space | Home menu with `Edit layout`, `Reload layout`, `Settings`, and not the item sheet | ux.md §1.1; test-plan §19 LP-6 |
| F5 | Swipe up from the grid (70 % → 25 % of the real screen height) | Drawer with `Search apps`. If only the `Apps` button opens it, the swipe failed | ux.md §1.4, §2 |
| F5b | Long-press the first app in the drawer (`Calendar`/`Camera`/`Clock`/`Contacts`, else `Settings`) | `Add to home`, `Hide`, `App info` | ux.md §2; test-plan §19 LP-7 |
| F6 | Tap the `{page}` slot, then `Second, page 2 of 2` | Pages list, then page 2 (`Second, Pages`) | ux.md §1.3, §15 #78 |
| F7 | ⋮ → `Config folder` → `Layout loaded · N warnings` | Warnings list reachable (app-only mode has no status.json, A§18.5) | ux.md §15 "Interim (#129)" |

## 3. Checks and pass criteria

`scripts/screen_matrix_check.py check <dir>` decides every check from the dumps alone, with no adb, so it can be
re-run on old evidence. Measurements are in dp (px ÷ density/160). A check that can't run because a dump is missing
is `SKIP`. On a real run, a SKIP of a blocking check makes the point `INCOMPLETE`, which also fails.

**Harness** (every point):

| ID | Check | Pass |
|---|---|---|
| X0 | Requested display size applied | The `wm size` override in `run.txt` equals the point's `size_px`. Android clamps an override to a multiple of the AVD panel, so CI sets a panel per shard (§5). A FAIL means the point ran at another size, so its other results describe that size, not the point |
| X1 | Requested night mode applied | advisory. `cmd uimode night` reads back the variant's theme. The API 29 image ignored `cmd uimode night yes` in run 1 (as in #150), so the driver retries with `settings put secure ui_night_mode`, and TH1 is judged against the theme that really applied |

**Title bar** (both pages; ux.md §15 "Overflow ladder (narrow phones, #149)"):

| ID | Check | Pass |
|---|---|---|
| T1 | Ladder order | The visible step is inferred from the dump. `{page}` with text means step 0. `{page}` showing only ▾ means step 1. A visible date that differs from its spoken date means steps 2–3. A date ending in `…` means step 4. Fail when the date is shortened or cut while `{page}` still shows text (step 2 before step 1). Steps 2 and 3 can't be told apart from a dump (step 3 changes only the size); the unit tests cover step 3 |
| T2 | Clock never truncates | The clock text matches `H:mm`/`HH:mm` and equals its spoken text |
| T3 | Warning exactly once | `warn_titlebar_fit` appears in the Warnings list exactly once when any page is at step ≥ 2, and is absent at step 0/1 (§15 "Step 1 alone writes no warning", the paragraph after the ladder). The step the warning names is recorded |
| T4 | `{page}` touch bounds | The touch area is the smallest clickable node around the slot. At step ≥ 1: 48×48 dp, or ≥ 32 dp only beside a tappable neighbour (§15 ladder step 1). At step 0: ≥ 32 dp wide (#152, §15 centre-slot rule) and 48 dp tall. It never intersects another clickable node |
| T5 | ≥ 8 dp gap between visible slots (0.5 dp px-rounding slack) | advisory |
| T6 | Step 0 at ≥ 411 dp, font 1.0 (phones) | advisory. This is our expectation from pr167 r4, not a spec rule (§7) |
| A1 | No duplicate title-bar strings in the a11y tree | advisory. Catches unplaced ladder candidates leaking to TalkBack |

**Grid** (both pages; ux.md §1.1 Cell, §12):

| ID | Check | Pass |
|---|---|---|
| G1 | Labels whole or hidden, never cut | Every visible grid label is ≥ 12 sp × font scale × 0.85 tall. The 0.85 allows for tight font metrics; a normal label is 16 dp at 1.0. A hidden label passes ("labels are hidden first … the layout is never clipped", §1.1; hidden allowed at ≥ 150 %, §12) |
| G2 | No overlaps | No two grid labels intersect, and no two tiles partially overlap |
| G3 | Targets and bounds | Tiles and dock slots are ≥ 48 dp (§12 Touch targets), fully on screen, and no label is under the title bar (§15 "never overlaps icons") |
| G5a | Count chip (c1 only) | The first load shows the B9 banner, so the driver taps its `×` (ux.md §17.4: "`×` collapses it into the count chip") and dumps `home-chip`. The chip is present there with a ≥ 48×48 dp hit area (§17.4, §12) |
| G5b | Chip shares the page-label row (c1, `home-chip`) | **pending** #175 (merged spec, decides #173): collapsed chips share one row with the page label. Advisory until the #175 implementation lands (Sprite, #178 at the time of writing), then blocking |
| G5c | Labels whole or hidden with the banner/chip showing (c1) | **pending** #175: grid labels are whole or hidden, never cut. Advisory until the #175 implementation lands, then blocking. Expected to fail until then (pr174 item e; run 1: 4.5 dp labels at s320-a51-c1) |
| D3 | Drawer labels not clipped | advisory (§2.1, §12: the drawer always shows labels). Rows cut by the real screen bottom (the list scrolls) don't count |
| TH1 | Theme | advisory. The drawer surface pixel is within 16/255 of `#141218` (dark) or `#FEF7FF` (light), per ux.md §12.1 Palettes. Needs Pillow |
| P1 | `TotalTime` recorded | advisory. Emulator timing is not PF-2, which is device-only (test-plan §15.3) |

Page-2 variants (`T1p2`, `G1p2`, `G5cp2`, …) have the same severity as their check. Every point directory also keeps `home1/home-chip/home2/lp-item/lp-empty/drawer/drawer-lp/pages/config-folder/warnings` `.xml` and
`.png`, plus `start.txt`, `crash.txt`, `anr.txt`, `focus.txt`, `meta.txt`, `result.json` and `result.md`. The
summary is `summary.md`, which CI also posts to the job summary.

## 4. dev7 gate

A point is **blocking** when a FAIL there fails the run, so dev7 isn't tagged. **Advisory** results are listed in the
summary and need a human look. **Pending** results are reported, never block, and switch to blocking when the cited implementation lands.
The policy is coded in `severity()` in `screen_matrix_check.py`.

**Blocks dev7:**
1. The build and the install on all three images, and X0 (the point really ran at its size) wherever the point blocks.
2. F1 (no launcher crash or ANR) at **every** point, including tablets, the fold and font 2.0.
3. F2–F7 at every point at font 1.0 and 1.3, tablets and fold included. A flow that breaks in landscape is a bug
   even before Q-S2 is answered (Q2).
4. T1–T4 (ladder order, clock, warning exactly once, touch bounds) at every point at font 1.0 and 1.3.
5. G1–G3 and G5a on **phone** points (≤ 448 dp wide, portrait) at font 1.0 and 1.3.

**Advisory for dev7:**
- G1–G3 on tablets, landscape and the fold. ux.md §1.1 says "Phones only in MVP" and landscape is Q-S2 (ux.md open
  questions). These become blocking once Christopher answers Q-S2 (Q2).
- Everything at font 2.0 except F1 (crashes always block). Decided: ux.md §12 allows hidden labels at ≥ 150 % and
  Q-S5 (fitting cells at 200 %) is open, so at 2.0 only a crash or ANR is a dev7 blocker; the rest needs a human look.
- T5, T6, A1, D3, TH1, X1 and P1.

**Pending:** G5b and G5c: advisory until the #175 implementation lands (#178 at the time of writing), then blocking.
The policy is approved as written (Christopher, #177).

**Minimum on-box gate** (when CI isn't available, see §5): `--gate` runs `s320-a51-v1`, `s320-a51-v2`, `s320-mdpi-v1`,
`p411-a51-v1`, `p411-a51-v2` and `t7-land-v1`.

**dev7 gates on T1–T4** (Christopher's decision). ux.md §15 is headed "later, not v1-gating": that is about v1.0
scope (the config-driven title bar isn't required for v1.0), while dev7's headline is the #149 overflow-ladder fix,
so dev7 can't be tagged with the ladder broken.

## 5. CI vs on-box

**CI** (`.github/workflows/screen-matrix.yml`, GitHub-hosted `ubuntu-latest` with KVM, free for this public repo):
- **Triggers:**
  - `workflow_dispatch`, with inputs `shards` (comma list or `all`) and `points` (an id regex).
  - Pushes of `v*-dev*` / `v*-rc*` tags, so `v0.0.1-dev7` runs it.
  - A PR getting the label `screen-matrix` (the label exists). Only the `labeled` event fires: pushing to a labelled
    PR doesn't rerun it, so re-apply the label or dispatch.
  - Not on every push or PR. The cheap per-PR smoke stays in emulator-smoke.yml.
- **Jobs:**
  1. `plan` picks the shards.
  2. `build` runs assembleDebug and uploads the APK (~6 min).
  3. One `shard` job per image group, with `reactivecircus/android-emulator-runner@v2.38.0`, `disable-animations`,
     4 GB RAM, a 60 min timeout, Pillow (TH1) and an AVD panel (`-skin`) per shard: small 720x1440, phones 1344x2992,
     tablets 1600x2560, fold 2208x2400. The runner's default panel is 320x640, and Android clamps `wm size` to a
     multiple of the panel (2× on API 29, 1920 px on API 35/36 in run 1), so run 1's large points ran smaller (X0).
  4. `summary` merges `result.json` into the job summary and fails if any point isn't PASS.
- **Estimate** (KVM; made before run 1, kept for comparison):
  - Each shard spends ~5 min on runner setup, image download and boot, then ~2.5 min per point.
  - small-a ≈ 20 min, small-b ≈ 20, phones ≈ 23, tablets ≈ 25, fold ≈ 15.
  - Build 6 and plan/summary 2.
  - **≈ 110 runner-minutes (range 90–150) and ≈ 35 min wall clock** for the full matrix.
  - A single-shard dispatch is ~25–30 runner-minutes.
- **Measured, run 1** ([40373955590](https://github.com/Nasawa/config-launcher/actions/runs/40373955590), label
  trigger on #177): **≈ 56 runner-minutes (≈ 61 billed with per-job rounding) and 16 min wall clock**. That is low:
  most large points ran clamped to smaller screens and F5b was skipped on 17 points, so expect more after the fixes.
  The rerun will replace these numbers. Minutes are free while the repo is public; a private repo would bill each run.

**On-box fallback** (the box AVD has no working KVM, so it runs software-only emulation and is very slow):
- Use only when CI is unavailable, and run only the gate subset: `scripts/screen-matrix.sh --gate --settle 20 --apk <apk> --out <dir>`.
- That is ~8–10 min per point, so ~1 h for the 6 gate points. The full matrix would be ~5 h, so don't.
- One AVD at a time. Check that no other job owns it first.
- System UI ANR dialogs are expected there. The script records them in `anr.txt` and taps `Wait`. Only a Config
  Launcher ANR fails.

**Local run on your own machine** (any emulator or test phone with a debug build):
```sh
./gradlew assembleDebug
emulator -avd <api35_avd> -no-snapshot &   # or a USB device; adb must see exactly one
scripts/screen-matrix.sh --shard phones --out screen-matrix-out          # one shard
scripts/screen-matrix.sh --points '^p411-a51-v1$' --out screen-matrix-out # one point
python3 scripts/screen_matrix_check.py check screen-matrix-out/p411-a51-v1   # re-judge a point from its dumps
```
The script changes the display size, density, font scale, night mode and rotation, and resets them at the end
(`wm size reset`, `wm density reset`, font 1.0, night no, rotation 0). Don't point it at a phone you're using:
`pm clear` wipes the launcher's app data.

## 6. Validation

- `actionlint` (with shellcheck) and `yamllint` on the workflow, and `shellcheck` on the script.
- `pyflakes` on the checker, and `scripts/validate.py` on the fixtures.
- The checker was dry-run against existing dumps in `evidence/pr167/runs/*-70b1c26` (r1–r5), the dev6-recheck 40–43
  flow dumps, and pr174 `s2-chip`/`f1`.
  - It agreed with the hand analysis: step 2 with the warning once at 320 dp; step 1 with no warning (r5 step1b);
    step 0 with no warning at 411 dp; a 48×48 wrapper.
  - It flagged the known pr174 item-e label clipping (8 dp labels) as G1/G5c.
- The driver ran against a stub `adb` to exercise the point loop, filtering, rotation and the summary.
- **Run 1** (CI run 40373955590, fd0618f, 31 points): 11 PASS, 11 FAIL, 9 INCOMPLETE. Triage in the #177 comment and
  `evidence/matrix/run1/`:
  - Real app bugs: the drawer opens as a half sheet (#179, ux.md §2.1), and the drawer stays dark in light mode
    (#180, §12.1).
  - Known, tracked by #173/#175 (fix in #178): grid labels cut at s320-ldpi (2.7 dp), l448 at font 1.3, t7-land
    (advisory) and the c1 chip point (G5c, pending).
  - Harness: wm size clamped (X0 added, panels per shard), the F5 swipe started below the real screen, F5b looked
    for `Settings` below the half sheet, G5a read the banner instead of the chip, TH1 had no Pillow, `G5cp2` missed
    the pending severity, a duplicate point id merged two results, and D3 used the requested height.
  - Re-judging run 1's dumps with the fixed checker (`evidence/matrix/run1/rejudged/`) leaves only X0, the known
    label cuts, TH1 and the skipped F5b; F5/F5b/G5a and the API 29 night-mode retry need the rerun.
  - Also harness: the API 29 points asked for dark but stayed light (X1).

## 7. Open questions

**Decided** (Christopher, on #177):
- dev7 gates on T1–T4 (§4).
- The blocking/advisory split in §4 is approved as written.
- G5b/G5c stay advisory until the #175 implementation lands, then block.
- The generic 7" and 10" tablet profiles stay; his exact tablet model is added later.

**Resolved by QA** (reasons inline; say if you disagree):
- Font 2.0: advisory except crashes (§4). ux.md §12 allows hidden labels at ≥ 150 % and Q-S5 is open.
- `warn_titlebar_fit` in app-only mode: T3 reads the in-app Warnings list. App-only mode has no `status.json`
  (A§18.5), and since #168 the Warnings list shows the same lines as `status.json`, so the list is the contract there.
- TalkBack: the AOSP/google_apis images have none, so a11y is checked from the uiautomator dumps (content-desc, A1,
  T4 bounds). A spoken TalkBack pass stays manual (ux.md §12; test-plan §15.1/§17).
- G1 with the chip showing: the c1 label check is G5c, pending #175, so G1 doesn't double-count it.
- T6 (step 0 at ≥ 411 dp) stays an advisory expectation; no spec says when the ladder must not engage.
- Triggers: the `screen-matrix` label exists; the `v*-dev*` / `v*-rc*` tag globs stay.
- test-plan §15.2 said emulator-smoke runs "on PRs labelled `emulator`", but emulator-smoke.yml runs on every PR and on
  pushes to main. The workflow is right, so test-plan.md is corrected in this PR.
- Minutes: free while the repo is public; run 1's measured numbers are in §5.

**Needs Christopher:**
- **Q1 (later, not blocking)** Christopher's tablet model, dp size and API, for a device-shaped tablet point beside t7/t10.
- **Q2 (needs Christopher)** Q-S2 / "Phones only in MVP" (ux.md §1.1): when do tablet, landscape and fold layout
  checks (G1–G3) become blocking, and is the ux.md §1.1 landscape layout (same grid, dock on the right edge) final?
