# Contributor notes for agents (this repo)

This repo builds the Config Launcher app. If you're editing a user's **layout** instead, see `templates/config-folder/AGENTS.md` and `skills/config-launcher/SKILL.md`.

- Specs: `ARCHITECTURE.md` (later sections override earlier ones), `docs/ux.md`, `docs/test-plan.md`, design proposals in `docs/design/`.
- Schema: `schema/launcher-config.v1.schema.json`; fixtures in `test-fixtures/configs/{valid,invalid}`.
- Build and test: `./gradlew testDebugUnitTest`, `./gradlew assembleDebug lintDebug`, `pip install jsonschema && python3 scripts/test_validate.py`.
- Single module `:app`, package `dev.nasawa.configlauncher` with `model`, `config`, `store`, `apps`, `ui`, `settings`. `model` and `config` stay free of Android imports.
- Keep `scripts/validate.py` in step with the Kotlin validator and codec.
