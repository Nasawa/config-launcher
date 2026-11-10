# v1.0 scope

> **Status: Decided (Christopher, 2026-11-10).** Christopher answered the four questions on 2026-11-10 (section 7). Nothing here creates a milestone or labels, moves or closes an issue; that's a separate step.
>
> Author: Archimedes (architect). Date: 2026-11-10.

This scope is based on main `d37afdb` (dev9) and the 24 open issues as of 2026-11-10.

**In short:** 1.0 is lean. It's a launcher that an agent can manage safely through `launcher.json`, signed with a real key, and checked by hand on a real device. Dashboard card rendering (S3) is **1.1**. 1.0 has 3 must-fix issues and 5 blockers (N1–N5) that don't have an issue yet. Nice-to-haves never block 1.0. #188 and #76 (and #137, as a tracker) are already done and only need closing.

## 1. S3 dashboard cards: after 1.0, as 1.1 (decided)

**Where S3 stands:** S3-A (schema, #147), S3-B (consent store), S3-C (consent tile, review list, B9) and S3-D (local data files, #151) are merged. `FEATURE_LEVEL` is already 4. What's left is the actual drawing: S3-E #140 (render), S3-F #141 (templates and taps) and S3-G #143 (fixtures). So today, an approved card is an **empty target** (`LiveCardTarget`, "the #140 renderer draws its content here").

**Why after 1.0** (the reasoning Christopher agreed with):
- **No compatibility cost.** The card schema is already in v1, and cards are gated by `requires: 4`. Cards can land in 1.1 with no schema break and no file migration. Agents can already write cards that validate.
- **E is the biggest single piece left.** It has Compose primitives, responsive `layouts`, cell-relative sizing and stale/as-of chrome. Then F adds template parsing and the host-matching tap rules, and that's where the security-sensitive work is. Putting them in 1.0 means at least 3 PRs plus a QA pass, while the budget is tight.
- **Christopher's earlier answer:** widgets are "critical to the end vision, not a v1 gate" (ARCHITECTURE §18.1, §14 Q9).
- **A lean 1.0 is still useful to an AI:** pages, dock, drawer rules, folders, title bar, status.json, apps.json, AGENTS.md and RELOAD are all done.

**The one thing 1.0 must not do** is ship half a feature. A card that asks for your OK and then draws nothing looks broken. So **N5** (below) is a 1.0 must-have: until 1.1, release builds show cards honestly as "not drawn yet".

## 2. Must-have for 1.0 (3 open issues + N1–N5)

| Issue | Why it gates 1.0 |
|---|---|
| [#244](https://github.com/Nasawa/reflow-launcher/issues/244) SAF duplicate `.configlauncher (N)` folders | The app keeps filling the user's synced folder with junk and stops updating status.json, so agents read stale state. The fix is small: check the returned name, then fail closed. |
| [#191](https://github.com/Nasawa/reflow-launcher/issues/191) first swipe-up lost after cold start | The main drawer gesture fails once after every reboot or process kill. That's the first thing a new user tries. |
| [#120](https://github.com/Nasawa/reflow-launcher/issues/120) phone accepts `00` / bare `-` that validate.py rejects | The phone and validate.py must agree on what's valid. Tightening the phone **after** 1.0 would start rejecting files that 1.0 accepted, which is a breaking change. So fix it now while it's free. |
| N1–N5 (section 6) | Blockers that have no issue yet. N5 is in because cards wait for 1.1. |

## 3. Nice-to-have (never blocks 1.0; done after if not ready)

| Issue | Reasoning |
|---|---|
| [#192](https://github.com/Nasawa/reflow-launcher/issues/192) red ⋮ badge dot 2.45:1 over the backdrop | It's a one-colour accessibility fix (it needs 3:1). It's cheap, so do it if Sprite is nearby. |
| [#195](https://github.com/Nasawa/reflow-launcher/issues/195) title-bar date shrinks to "O…" | It's a real visual bug, but it only shows at a large size on narrow screens, and an agent can work around it with a shorter date format. |
| [#50](https://github.com/Nasawa/reflow-launcher/issues/50) folder replay can't detect a removed member | It's a correctness gap in conflict merge, which matters more when an agent and the phone edit at once. It's rare, and history keeps both versions. |
| [#241](https://github.com/Nasawa/reflow-launcher/issues/241) flaky ShowSourceTest | Hygiene for CI. A flaky suite slows every release-candidate PR. |
| [#240](https://github.com/Nasawa/reflow-launcher/issues/240) matrix misses icon spill | QA tooling. The bug it would have caught (#235) is already fixed. |
| [#91](https://github.com/Nasawa/reflow-launcher/issues/91) reorder `drawerRule.required` | It's cosmetic and validates the same either way. Do it only if the schema is touched before 1.0 for another reason, because every schema change also means re-uploading the hosted copy and adding a shipped hash. |
| [#173](https://github.com/Nasawa/reflow-launcher/issues/173) chip strip height, labels cut at 320 dp | These follow-ups are mostly about the consent chip and B9, so they matter more once cards draw. Fix the cut labels if cheap. |
| [#190](https://github.com/Nasawa/reflow-launcher/issues/190) held Enter closes the widget sheet | This affects D-pad users on live cards, and it has only been seen on the emulator. It goes with S3. |

## 4. After 1.0

| Issue | Reasoning |
|---|---|
| **1.1:** [#137](https://github.com/Nasawa/reflow-launcher/issues/137), [#140](https://github.com/Nasawa/reflow-launcher/issues/140), [#141](https://github.com/Nasawa/reflow-launcher/issues/141), [#142](https://github.com/Nasawa/reflow-launcher/issues/142), [#143](https://github.com/Nasawa/reflow-launcher/issues/143) S3 cards | Decided: card rendering is 1.1 (section 1). #137 is already merged (#147) and #142's UI is merged (S3-C.1/.2). Those two stay open only as trackers until the render lands. |
| [#224](https://github.com/Nasawa/reflow-launcher/issues/224) `fold.halfOpened` override | It's already designed (FEATURE_LEVEL 5). It's opt-in and nobody here has a foldable to test it on. |
| [#226](https://github.com/Nasawa/reflow-launcher/issues/226) overlays off a separating hinge | Same reason: foldable-only polish, and the flat or no-hinge behaviour doesn't change. |
| [#208](https://github.com/Nasawa/reflow-launcher/issues/208) reclaim chrome on short split-screen panes | The spec already has an interim floor of 32 dp (#209). This only matters in rare, very short multi-window panes. |
| [#204](https://github.com/Nasawa/reflow-launcher/issues/204) phone-landscape banner pushes the grid under 48 dp | It's temporary, because it goes away when the banner is dismissed, and ux.md §1.5 step 4 already documents it. |
| [#121](https://github.com/Nasawa/reflow-launcher/issues/121) canonicalize override keys | Writing them back verbatim is already the v1 ruling. I see no foreseeable value, so I'd close it as won't-do unless someone objects. |

## 5. Already done, only need closing (no work)

| Issue | Evidence |
|---|---|
| [#188](https://github.com/Nasawa/reflow-launcher/issues/188) pager restores by index | Fixed by #214 and shipped in dev8. Claw confirmed it on the onn tablet. |
| [#137](https://github.com/Nasawa/reflow-launcher/issues/137) S3-A card schema | Merged as #147, and `FEATURE_LEVEL = 4` is on main. It can close now, or stay as an S3 tracker. |
| [#76](https://github.com/Nasawa/reflow-launcher/issues/76) agent onboarding | Everything is on main: the AGENTS.md template plus the in-app write (`AgentDocs.kt`, #236), `skills/reflow-launcher/SKILL.md`, the README "Canonical details", canonical drawer rules and the `a-`/`s-`/`f-`/`r-` ID prefixes. Its one leftover is #91. |
| [#79](https://github.com/Nasawa/reflow-launcher/issues/79) config-driven title bar | **Not fully done, so keep it open.** The first-release scope shipped (#112, #115, #130, #167: FEATURE_LEVEL 2, on/off, slots, styling, overflow ladder). But #79 also asks for "position", and the schema only allows `anchor: "top"`. `docs/design/title-bar.md` §10 lists a `bottom` anchor and style editing on the phone as later work. Either close it and file those as a post-1.0 issue, or keep it open as the tracker. Neither gates 1.0. |

I haven't closed any of these. That's for Christopher or Grok Bot.

## 6. 1.0 blockers with no issue yet

- **N1. Hands-on edge drag check.** Christopher made this blocking for v1.0 on 2026-11-02. It's recorded in `docs/test-plan-screen-matrix.md` §4 by #232, but **it has no issue**. It covers drag feel, touch slop, long-press timing, spring-back near edges and OEM edge-gesture zones, which CI can't check. The doc asks for a "stable host (a hardware-accelerated emulator or a real device)" at 411 dp phone, 7" tablet, 10" tablet (each portrait and landscape) and fold-open. **Devices (Christopher, 2026-11-10):** he does the pass by hand on a Galaxy A51 or A53 for the phone size, and on the onn 10.1 tablet for the 10" size if it's available. A hardware-accelerated emulator covers 7", fold-open, and 10" if the onn doesn't.
- **N2. A real release signing key and a CI release job.** Every release so far, dev1–dev9, is a debug build signed with the **public** debug key, so anyone could build an "update" that installs over it. ARCHITECTURE §18.1 (Q7) says 1.0 is a "signed release APK built by CI and attached to a Release", and `app/build.gradle.kts` has no release signing config yet. **Decided in principle (Christopher, 2026-11-10):** 1.0 uses a private release key. **TBD** after Grok Bot's crash course with him: how the key is created and stored, the CI secrets and job, and publishing the certificate's SHA-256. **Side effect:** moving from the debug key to the release key means testers uninstall and set up again, one last time, so it's done once and the key is kept forever.
- **N3. Version numbers.** `versionName` is still `0.1.0` and `versionCode` is still `1`, while the tags run `v0.0.1-dev1…dev9`. 1.0 needs one rule, such as a `versionCode` that rises with every tag and a `versionName` taken from the tag, so updates install cleanly and the app shows the right version.
- **N4. A test pass on the release APK.** Every test so far has run on debug builds. The release build turns on R8 shrinking (`isMinifyEnabled = true`), which can break JSON and reflection code. It also behaves differently: there's no app-only FileObserver and no `run-as`. Before 1.0 ships, smoke and the key test-plan rows need to run on the signed release APK.
- **N5. Honest card state in release builds (must-have, since cards are 1.1).** Until #140 lands, a release build should not show an approved card as an empty box. The smallest fix is to keep parsing and validating cards (they stay in the file, and the cells stay reserved) but draw a plain "Cards arrive in a later version" tile, with a status.json warning so agents know why. Debug builds keep the current behaviour for S3 work.

**Checked, and not blockers:**
- **Schema hosting is done.** `https://reflowlauncher.com/schema/launcher-config.v1.schema.json` now returns 200, and it's byte-for-byte the same as main's schema (SHA-256 `669d1197…e725`, checked 2026-11-10). From now on, any schema change (for example #91) has to be re-uploaded there, so that goes on the release checklist.
- **No store listing.** Distribution is GitHub Releases only (§18.1 Q7), so no Play or F-Droid work.
- **No privacy policy needed for 1.0.** The app's manifest declares **no** permissions, and in particular no `INTERNET`. INTERNET only comes with `net.fetch` in stage 5 (dynamic-widgets.md Q-C1). Nothing leaves the phone except through the user's own sync folder. A two-line privacy note in the README would be cheap and nice, but it's optional.

## 7. Decisions (Christopher, 2026-11-10, relayed by Grok Bot)

1. **Cards:** "We can do the widgets after 1.0 if that makes it easier." Card rendering (#140, #141, #143) is **1.1**. N5 is a 1.0 must-have.
2. **Release key:** "I've signed and released Android apps before, but never for an open source one like this... I'll gladly take a crash course." A private release key is **decided in principle**. Key creation and storage and the CI setup are TBD (N2), after Grok Bot's crash course.
3. **Drag check:** "I can do the drag check on an A51 or A53 or an onn tablet." Christopher runs N1 on one of those. An emulator covers the 7" and fold sizes, and 10" if the onn doesn't.
4. **Nice-to-haves:** "nice to haves shouldn't block deployment. We can get to those after." Section 3 never blocks 1.0.
