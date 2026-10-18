# Test fixtures

`configs/valid/` and `configs/invalid/` hold real `launcher.json` fixtures. `configs/manifest.json` gives each file's expected outcome, the layer that must catch it, and the ARCHITECTURE.md reference.

Layers:
- `both`: the JSON Schema and the Kotlin validator must both reject (or, in `valid/`, both accept).
- `kotlin-only`: tagged with a `kotlinOnly` rule from the ARCHITECTURE §17 Q2 list (duplicate-ids, overlapping-cells, cell-outside-grid, item-total, duplicate-keys, parser-depth, byte-cap, nul-or-encoding). The schema **must accept** these, and Kotlin must reject them.
- `parser`: plain syntax errors that no JSON parser accepts (B1).
- `store`: size-cap cases enforced before parsing.

Regenerate with `python3 test-fixtures/gen_fixtures.py` (deterministic, run from the repo root), then copy `configs/` to `app/src/test/resources/configs/` for the JVM tests (`FixtureConformanceTest`, `KotlinOnlyConformanceTest`, `WriterRefusalTest`).

Check: `python3 test-fixtures/check_schema.py` (needs `pip install jsonschema`). It must report 0 mismatches. `deep-nesting.json` is reported as unchecked because Python's parser hits its recursion limit; the JVM test covers it.
