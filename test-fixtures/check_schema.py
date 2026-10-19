#!/usr/bin/env python3
"""Check every fixture against schema/launcher-config.v1.schema.json (Python jsonschema), per manifest.json.

ARCHITECTURE.md §17 Q2:
- valid/*                        -> the schema must accept.
- invalid/* with layer "both"    -> the schema must reject.
- invalid/* with "kotlinOnly"    -> the schema must ACCEPT (keeps the Kotlin-only list honest);
                                    only the app's parser/validator/store rejects it.
- invalid/* with layer "parser"  -> plain syntax errors (no JSON value), nothing to check here.
Encoding fixtures are decoded with errors="replace" (and UTF-16 by BOM) so the schema sees the structure.
Python's json module can't parse 100k-deep nesting (RecursionError); that one is reported as unchecked.
"""
import json, sys, pathlib
import jsonschema

KOTLIN_ONLY = {"duplicate-ids", "overlapping-cells", "cell-outside-grid", "item-total",
               "duplicate-keys", "parser-depth", "byte-cap", "nul-or-encoding",
               "template-grammar", "single-title-bar", "number-range"}
root = pathlib.Path(__file__).parent
schema = json.load(open(root.parent / "schema/launcher-config.v1.schema.json"))
V = jsonschema.Draft202012Validator(schema)

def load(raw: bytes):
    if raw.startswith((b"\xff\xfe", b"\xfe\xff")):
        return json.loads(raw.decode("utf-16"))
    return json.loads(raw.removeprefix(b"\xef\xbb\xbf").decode("utf-8", errors="replace"))

bad = unchecked = checked = 0
for e in json.load(open(root / "configs/manifest.json")):
    f, raw = e["file"], (root / "configs" / e["file"]).read_bytes()
    rule = e.get("kotlinOnly")
    if rule and rule not in KOTLIN_ONLY:
        bad += 1; print("UNKNOWN kotlinOnly rule", f, rule); continue
    if f.startswith("invalid/") and not rule and e["layer"] != "both":
        continue
    try:
        ok = V.is_valid(load(raw))
    except RecursionError:
        unchecked += 1; print("unchecked (Python recursion limit):", f); continue
    except ValueError:
        ok = None
    want = f.startswith("valid/") or bool(rule)
    checked += 1
    if ok != want:
        bad += 1; print("MISMATCH", f, "schema:", {True: "accepts", False: "rejects", None: "can't parse"}[ok], "expected:", "accept" if want else "reject")
print(f"checked: {checked}, unchecked: {unchecked}, mismatches: {bad}")
sys.exit(1 if bad else 0)
