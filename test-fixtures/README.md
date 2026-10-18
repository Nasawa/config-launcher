# Test fixtures

`configs/valid/` and `configs/invalid/` hold real `launcher.json` fixtures; `configs/manifest.json` gives each file's expected outcome, the layer that must catch it, and the ARCHITECTURE.md reference.

Layers: `both` = the JSON Schema and the Kotlin validator must both reject (or accept); `parser` = the JSON reader (encoding, BOM, depth, duplicate keys, NUL); `kotlin` = Kotlin-only rules the schema can't express (unique IDs, overlapping cells, cells inside grid, totals, string caps); `store` = enforced by `ConfigStore` before parsing (size cap).

**Move:** there is no Gradle project yet. When `:app` lands, move `configs/valid` and `configs/invalid` to `app/src/test/resources/configs/{valid,invalid}/` (ARCHITECTURE.md §11) and keep `manifest.json` beside them.

`python3 test-fixtures/check_schema.py` (needs `pip install jsonschema`) checks the schema against every `valid/` and `both` fixture. Only Python's standard `json` parser is used, so `parser` fixtures are skipped.
