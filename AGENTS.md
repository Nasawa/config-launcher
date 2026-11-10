# Contributor notes for agents (this repo)

This repo builds the Reflow Launcher app (formerly config-launcher). If you're editing a user's **layout** instead, see `templates/config-folder/AGENTS.md` and `skills/reflow-launcher/SKILL.md`.

- Specs: `ARCHITECTURE.md` (later sections override earlier ones), `docs/ux.md`, `docs/test-plan.md`, design proposals in `docs/design/`.
- Schema: `schema/launcher-config.v1.schema.json`; fixtures in `test-fixtures/configs/{valid,invalid}`.
- Build and test: `./gradlew testDebugUnitTest`, `./gradlew assembleDebug lintDebug`, `pip install jsonschema && python3 scripts/test_validate.py`, `python3 scripts/test_version.py`.
- Single module `:app`, Kotlin packages/namespace `dev.nasawa.configlauncher` (unchanged); `applicationId` `com.anigeek.reflowlauncher` with `model`, `config`, `store`, `apps`, `ui`, `settings`. `model` and `config` stay free of Android imports.
- Keep `scripts/validate.py` in step with the Kotlin validator and codec.

## Attribution

All agents push through the same GitHub account, so the account shown as author doesn't say who did the work. Mark every change:

- **Commits:** end each commit message with a trailer line `Agent: <Name>`, for example `Agent: Sprite`. Use `Agent: Christopher` for his own manual commits, if he wants.
- **PRs:** start the PR body with `Opened by: <Name>` (Grok Bot, Archimedes, Sprite, Pragma or Grace), and send the PR URL to Grok Bot when it's opened.
- **Issues and review comments:** start with `<Name>:`.

`git log --grep "Agent: Sprite"` finds one agent's commits.

## Version numbers (#255)

`versionName` and `versionCode` are computed from the git tag by `scripts/version.py`. Nobody edits the numbers in `app/build.gradle.kts`.

- Tags are `vMAJOR.MINOR.PATCH`, `vMAJOR.MINOR.PATCH-rcN` or `vMAJOR.MINOR.PATCH-devN` (N ≥ 1, no leading zeros). `versionName` is the tag without the leading `v` (`1.0.0`, `1.0.0-rc1`).
- `versionCode` = `MAJOR * 1_000_000 + MINOR * 10_000 + PATCH * 100 + suffix`. A final release uses suffix 99, `-rcN` uses 50 + N (N = 1..48), `-devN` uses N (N = 1..49). So for the same X.Y.Z: dev < rc < release, and each later tag goes higher. `v1.0.0-rc1` is 1000051, `v1.0.0` is 1000099. Both are higher than every earlier shipped build (those were all `versionCode` 1).
- Untagged local and CI builds use `versionName` `0.0.0-dev` and `versionCode` 1.
- Override with `REFLOW_TAG=v1.0.0-rc1` when git tags are not available. A tagged CI run also reads `GITHUB_REF`.
- The ⋮ menu shows `Version <versionName>`, which must match the GitHub Release tag without the `v`. CI runs `scripts/test_version.py` and, after assemble, `scripts/version.py --check-apk` on the debug and release APKs so a tagged build's `versionName` matches its tag.
- Agents do not create tags or releases.

