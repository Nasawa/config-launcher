# v1.0 scope

> **Status: Decided (Christopher, 2026-11-10).** Christopher's five decisions from 2026-11-10 are in section 7. Nothing here creates a milestone or labels, moves or closes an issue; that's a separate step.
>
> Author: Archimedes (architect). Date: 2026-11-10.

This scope is based on main `d37afdb` (6 commits after the `v0.0.1-dev9` tag at `d5cb88b`: #246, #249, #248) and the 24 open issues as of 2026-11-10.

**In short:** 1.0 is lean. It's a launcher that an agent can manage safely through `launcher.json`, signed with a real key, and checked by hand on a real device. Dashboard card rendering (S3) is **1.1**. 1.0 has 3 must-fix issues and 5 blockers filed as [#253](https://github.com/Nasawa/reflow-launcher/issues/253)–[#257](https://github.com/Nasawa/reflow-launcher/issues/257) (N1–N5). Nice-to-haves never block 1.0. #76 is closed as done. #188 is closed with the pager fix, and its leftovers move to [#258](https://github.com/Nasawa/reflow-launcher/issues/258) (nice-to-have). #79 stays open as the post-1.0 title-bar tracker.

## 1. S3 dashboard cards: after 1.0, as 1.1 (decided)

**Where S3 stands:** S3-A (schema, #147), S3-B (consent store), S3-C (consent tile, review list, B9) and S3-D (local data files, #151) are merged. `FEATURE_LEVEL` is already 4. What's left is the actual drawing: S3-E #140 (render), S3-F #141 (templates and taps) and S3-G #143 (fixtures). So today, an approved card is an **empty target** (`LiveCardTarget`, "the #140 renderer draws its content here").

**Why after 1.0** (the reasoning Christopher agreed with):
- **No compatibility cost.** The card schema is already in v1, and cards are gated by `requires: 4`. Cards can land in 1.1 with no schema break and no file migration. Agents can already write cards that validate.
- **E is the biggest single piece left.** It has Compose primitives, responsive `layouts`, cell-relative sizing and stale/as-of chrome. Then F adds template parsing and the host-matching tap rules, and that's where the security-sensitive work is. Putting them in 1.0 means at least 3 PRs plus a QA pass, while the budget is tight.
- **Christopher's earlier answer:** widgets are "critical to the end vision, not a v1 gate" (ARCHITECTURE §18.1, §14 Q9).
- **A lean 1.0 is still useful to an AI:** pages, dock, drawer rules, folders, title bar, status.json, apps.json, AGENTS.md and RELOAD are all done.

**The one thing 1.0 must not do** is ship half a feature. A card that asks for your OK and then draws nothing looks broken. So **N5, [#257](https://github.com/Nasawa/reflow-launcher/issues/257)** is a 1.0 must-have: until 1.1, release builds show cards honestly as "not drawn yet".

## 2. Must-have for 1.0 (3 open issues + #253–#257)

| Issue | Why it gates 1.0 |
|---|---|
| [#244](https://github.com/Nasawa/reflow-launcher/issues/244) SAF duplicate `.configlauncher (N)` folders | The app keeps filling the user's synced folder with junk and stops updating status.json, so agents read stale state. The fix is small: check the returned name, then fail closed. |
| [#191](https://github.com/Nasawa/reflow-launcher/issues/191) first swipe-up lost after cold start | The main drawer gesture fails once after every reboot or process kill. That's the first thing a new user tries. |
| [#120](https://github.com/Nasawa/reflow-launcher/issues/120) phone accepts `00` / bare `-` that validate.py rejects | The phone and validate.py must agree on what's valid. Tightening the phone **after** 1.0 would start rejecting files that 1.0 accepted, which is a breaking change. So fix it now while it's free. |
| N1–N5: [#253](https://github.com/Nasawa/reflow-launcher/issues/253), [#254](https://github.com/Nasawa/reflow-launcher/issues/254), [#255](https://github.com/Nasawa/reflow-launcher/issues/255), [#256](https://github.com/Nasawa/reflow-launcher/issues/256), [#257](https://github.com/Nasawa/reflow-launcher/issues/257) | The blockers in section 6, filed 2026-11-10. N5 (#257) is in because cards wait for 1.1. |

## 3. Nice-to-have (never blocks 1.0; done after if not ready)

| Issue | Reasoning |
|---|---|
| [#192](https://github.com/Nasawa/reflow-launcher/issues/192) red ⋮ badge dot 2.45:1 over the backdrop | It's a one-colour accessibility fix (it needs 3:1). It's cheap, so do it if Sprite is nearby. |
| [#195](https://github.com/Nasawa/reflow-launcher/issues/195) title-bar date shrinks to "O…" | It's a real visual bug, but it only shows at a large size on narrow screens, and an agent can work around it with a shorter date format. |
| [#50](https://github.com/Nasawa/reflow-launcher/issues/50) folder replay can't detect a removed member | It's a correctness gap in conflict merge, which matters more when an agent and the phone edit at once. It's rare, and history keeps both versions. |
| [#241](https://github.com/Nasawa/reflow-launcher/issues/241) flaky ShowSourceTest | Hygiene for CI. A flaky suite slows every release-candidate PR. |
| [#240](https://github.com/Nasawa/reflow-launcher/issues/240) matrix misses icon spill | QA tooling. The bug it would have caught (#235) is already fixed. |
| [#91](https://github.com/Nasawa/reflow-launcher/issues/91) reorder `drawerRule.required` | It's cosmetic and validates the same either way. Do it only if the schema is touched before 1.0 for another reason, because every schema change also means re-uploading the hosted copy and adding a shipped hash. |
| [#173](https://github.com/Nasawa/reflow-launcher/issues/173) follow-ups from #172 QA: chip strip height, D-pad Enter on live cards, Warnings after a failed move, labels cut at 320 dp | Four separate pieces. **L1:** the count chip's 48 dp strip takes height from the grid. **L2:** a D-pad user can focus a live card but Enter/centre doesn't open it. This is related to #190 but not the same, and #174/#176 already addressed parts of it, so it needs re-checking. **L3:** after a failed folder move, the Warnings list stays empty until the next poll. That isn't card-related, and #174 targeted it. **Later comment:** at 320 dp the chip strip and B9 cut app labels partway through the letters. L1, L2 and the cut labels matter more once cards draw. Re-check L2 and L3 against main, and close whatever is already fixed. |
| [#190](https://github.com/Nasawa/reflow-launcher/issues/190) held Enter closes the widget sheet | This affects D-pad users on live cards, and it has only been seen on the emulator. It goes with S3. |
| [#258](https://github.com/Nasawa/reflow-launcher/issues/258) (#188 follow-up): keep edit mode, the drawer, an open folder and sheets across rotate/fold/resize | The page is already kept (#214), and the drag is cancelled with no write. What's left is UI state that resets, with no data loss. It's cheap if done with `rememberSaveable`, but it never gates 1.0. test-plan L7 expects it, so mark L7's "edit mode, drawer and open sheets kept" part advisory until it lands. |

## 4. After 1.0

| Issue | Reasoning |
|---|---|
| **1.1:** [#137](https://github.com/Nasawa/reflow-launcher/issues/137), [#140](https://github.com/Nasawa/reflow-launcher/issues/140), [#141](https://github.com/Nasawa/reflow-launcher/issues/141), [#142](https://github.com/Nasawa/reflow-launcher/issues/142), [#143](https://github.com/Nasawa/reflow-launcher/issues/143) S3 cards | Decided: card rendering is 1.1 (section 1). #137 is already merged (#147) and #142's UI is merged (S3-C.1/.2). Those two stay open only as trackers until the render lands. |
| [#224](https://github.com/Nasawa/reflow-launcher/issues/224) `fold.halfOpened` override | It's already designed (FEATURE_LEVEL 5). It's opt-in and nobody here has a foldable to test it on. |
| [#226](https://github.com/Nasawa/reflow-launcher/issues/226) overlays off a separating hinge | Same reason: foldable-only polish, and the flat or no-hinge behaviour doesn't change. |
| [#208](https://github.com/Nasawa/reflow-launcher/issues/208) reclaim chrome on short split-screen panes | The spec already has an interim floor of 32 dp (#209). This only matters in rare, very short multi-window panes. |
| [#204](https://github.com/Nasawa/reflow-launcher/issues/204) phone-landscape banner pushes the grid under 48 dp | It's temporary, because it goes away when the banner is dismissed, and ux.md §1.5 step 4 already documents it. |
| [#79](https://github.com/Nasawa/reflow-launcher/issues/79) config-driven title bar | **Post-1.0 title-bar tracker; stays open.** The first-release scope shipped (#112, #115, #130, #167: FEATURE_LEVEL 2, on/off, slots, file styling, overflow ladder). "Position" isn't fully done: `$defs/titleBar.anchor` is `"const": "top"` (`schema/launcher-config.v1.schema.json` ~642) and `TitleBar.kt:33` rejects anything else. `docs/design/title-bar.md` §10 defers a `bottom` anchor, plus style editing on the phone. Neither gates 1.0. |
| [#121](https://github.com/Nasawa/reflow-launcher/issues/121) canonicalize override keys | Writing them back verbatim is already the v1 ruling. I see no foreseeable value, so I'd close it as won't-do unless someone objects. |

## 5. Closed or tracked elsewhere (2026-11-10)

| Issue | Status |
|---|---|
| [#76](https://github.com/Nasawa/reflow-launcher/issues/76) agent onboarding | **Closed as done.** #84 (`155f6af`), `b58c93a`, `d7a329b`, `eb96935`, `ca35718` and #236. The one leftover is #91. |
| [#188](https://github.com/Nasawa/reflow-launcher/issues/188) window changes restore by index | **Closed with the pager fix; leftovers move to [#258](https://github.com/Nasawa/reflow-launcher/issues/258).** #214 (`39323d6`, dev8) keeps the page by id, and Claw confirmed it on the onn tablet. #214 says it's only "Part of #188": ux.md §1.5 also asks that edit mode, the drawer (search and scroll), an open folder and the Pages and item sheets survive recreation. Those still reset on a rotate. #258 is a nice-to-have (section 3), never gating. |
| [#137](https://github.com/Nasawa/reflow-launcher/issues/137) S3-A card schema | Merged as #147. It stays open as an S3 tracker until 1.1. |

#79 is in section 4 as the post-1.0 title-bar tracker.

## 6. 1.0 blockers (filed as #253–#257)

#253 and #254 have their full bodies: the drag checklist with the hinge waiver (now decided), and the proposed signing plan.

- **N1 ([#253](https://github.com/Nasawa/reflow-launcher/issues/253)). Hands-on edge drag check.** Christopher made this blocking for v1.0 on 2026-11-02. It's recorded in `docs/test-plan-screen-matrix.md` §4 (lines 163–177, by #232). The hands-on pass covers what CI can't: drag feel, touch slop, long-press timing, **the dragged tile** and its spring-back near edges, **a real hinge**, and OEM edge-gesture zones. **Checklist (§4):** for each of 411 dp phone, 7" and 10" tablet (each portrait and landscape) and fold-open (flat), long-press a home item and drop it on **every grid edge and corner**, then into **each side gutter**. **Pass:** edge and corner drops snap to a real cell, a gutter drop is **refused** (the item returns), and there's **no crash**. One pass per size and orientation is enough. **Devices (Christopher, 2026-11-10):** he does it by hand on a Galaxy A51 or A53 for the phone size, and on the onn 10.1 tablet for 10" if it's available. A hardware-accelerated emulator covers 7", fold-open, and 10" if the onn isn't used. **Real hinge: waived for 1.0 (decided, Christopher, 2026-11-10: "Yes, waive the real-hinge check for 1.0 and list it as a known gap.").** No foldable is available, and an emulator can't stand in for a real hinge. See "Known gaps in 1.0".
- **N2 ([#254](https://github.com/Nasawa/reflow-launcher/issues/254)). A real release signing key and a CI release job.** Every release so far, dev1–dev9, is a debug build signed with the **public** debug key, so anyone could build an "update" that installs over it. ARCHITECTURE §18.1 (Q7) says 1.0 is a "signed release APK built by CI and attached to a Release", and `app/build.gradle.kts` has no release signing config yet. **Decided in principle (Christopher, 2026-11-10):** 1.0 uses a private release key. The **proposed** signing plan (key specs, backups, CI secrets in a protected environment, the published fingerprint) is in #254. Key creation, storage and the CI details get confirmed with Christopher during Grok Bot's crash course. The plan proposes that **one key signs every channel** (see the implication under decision 5). **Side effect:** moving from the debug key to the release key means testers uninstall and set up again, one last time, so it's done once and the key is kept forever.
- **N3 ([#255](https://github.com/Nasawa/reflow-launcher/issues/255)). Version numbers.** `versionName` is still `0.1.0` and `versionCode` is still `1`, while the tags run `v0.0.1-dev1…dev9`. 1.0 needs one rule, such as a `versionCode` that rises with every tag and a `versionName` taken from the tag, so updates install cleanly and the app shows the right version.
- **N4 ([#256](https://github.com/Nasawa/reflow-launcher/issues/256)). A test pass on the release APK.** Every test so far has run on debug builds. The release build turns on R8 shrinking (`isMinifyEnabled = true`), which can break JSON and reflection code. It also behaves differently: there's no app-only FileObserver and no `run-as`. Before 1.0 ships, smoke and the key test-plan rows need to run on the signed release APK.
- **N5 ([#257](https://github.com/Nasawa/reflow-launcher/issues/257)). Honest card state in release builds (must-have, since cards are 1.1).** Until #140 lands, a release build should not show an approved card as an empty box. The smallest fix is to keep parsing and validating cards (they stay in the file, and the cells stay reserved) but draw a plain "Cards arrive in a later version" tile, with a status.json warning so agents know why. Debug builds keep the current behaviour for S3 work.

**Checked, and not blockers:**
- **Schema hosting is done.** `https://reflowlauncher.com/schema/launcher-config.v1.schema.json` now returns 200, and it's byte-for-byte the same as main's schema (SHA-256 `669d1197…e725`, checked 2026-11-10). From now on, any schema change (for example #91) has to be re-uploaded there, so that goes on the release checklist.
- **No store listing for 1.0.** 1.0 ships on GitHub Releases only. F-Droid and then Play come later (decision 5), and neither blocks 1.0.
- **No privacy policy needed for 1.0.** The app's manifest declares **no** permissions, and in particular no `INTERNET`. INTERNET only comes with `net.fetch` in stage 5 (dynamic-widgets.md Q-C1). Nothing leaves the phone except through the user's own sync folder. A two-line privacy note in the README would be cheap and nice, but it's optional.

## Known gaps in 1.0

- **Real-hinge drag check (#253).** Waived for 1.0 (Christopher, 2026-11-10). No foldable is available, so fold behaviour (fold-open drag, the hinge split, overlays near the hinge) is emulator-tested best effort. It gets re-checked on a real foldable when one is available. The 1.0 release notes list this gap.

## 7. Decisions (Christopher, 2026-11-10, relayed by Grok Bot)

1. **Cards:** "We can do the widgets after 1.0 if that makes it easier." Card rendering (#140, #141, #143) is **1.1**. N5 is a 1.0 must-have.
2. **Release key:** "I've signed and released Android apps before, but never for an open source one like this... I'll gladly take a crash course." A private release key is **agreed in principle**. The proposed plan is in #254. Key creation, storage and the CI details get confirmed with him during Grok Bot's crash course.
3. **Drag check:** "I can do the drag check on an A51 or A53 or an onn tablet." Christopher runs N1 (#253) on one of those. An emulator covers the 7" and fold-open sizes, and 10" if the onn isn't used. Real hinge, **decided:** "Yes, waive the real-hinge check for 1.0 and list it as a known gap." See "Known gaps in 1.0".
4. **Nice-to-haves:** "nice to haves shouldn't block deployment. We can get to those after." Section 3 never blocks 1.0.
5. **Distribution:** "B, with Play added as a lower priority since I anticipate it being a pain." Channel order: **GitHub Releases for 1.0, then F-Droid, then Google Play.**

**Implication (Archimedes):** one private signing key should serve all three channels, so users can move between them without reinstalling. F-Droid can publish our own signed APK after a reproducible-build check, and Play App Signing can be set up with our existing key plus a separate upload key. Details and sources are in [#254](https://github.com/Nasawa/reflow-launcher/issues/254).
