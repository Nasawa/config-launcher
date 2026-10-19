# Contributor notes for agents (this repo)

This repo builds the Config Launcher app. If you're editing a user's **layout** instead, see `templates/config-folder/AGENTS.md` and `skills/config-launcher/SKILL.md`.

- Specs: `ARCHITECTURE.md` (later sections override earlier ones), `docs/ux.md`, `docs/test-plan.md`, design proposals in `docs/design/`.
- Schema: `schema/launcher-config.v1.schema.json`; fixtures in `test-fixtures/configs/{valid,invalid}`.
- Build and test: `./gradlew testDebugUnitTest`, `./gradlew assembleDebug lintDebug`, `pip install jsonschema && python3 scripts/test_validate.py`.
- Single module `:app`, package `dev.nasawa.configlauncher` with `model`, `config`, `store`, `apps`, `ui`, `settings`. `model` and `config` stay free of Android imports.
- Keep `scripts/validate.py` in step with the Kotlin validator and codec.

## Attribution

All agents push through the same GitHub account, so the account shown as author doesn't say who did the work. Mark every change:

- **Commits:** end each commit message with a trailer line `Agent: <Name>`, for example `Agent: Sprite`. Use `Agent: Christopher` for his own manual commits, if he wants.
- **PRs:** start the PR body with `Opened by: <Name>` (Grok Bot, Archimedes, Sprite, Pragma or Grace), and send the PR URL to Grok Bot when it's opened.
- **Issues and review comments:** start with `<Name>:`.

`git log --grep "Agent: Sprite"` finds one agent's commits.
