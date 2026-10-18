#!/usr/bin/env python3
"""Run every fixture through the JSON Schema (Python jsonschema) and compare with manifest.json.
Only checks fixtures whose layer is 'both' (schema must catch them) or valid ones; 'parser',
'kotlin' and 'store' fixtures need the app's own parser/validator/store."""
import json, sys, pathlib
import jsonschema
root = pathlib.Path(__file__).parent
schema = json.load(open(root.parent / "schema/launcher-config.v1.schema.json"))
V = jsonschema.Draft202012Validator(schema)
bad = 0
for e in json.load(open(root / "configs/manifest.json")):
    p = root / "configs" / e["file"]
    if e["layer"] not in ("both",) and not e["file"].startswith("valid/"):
        continue
    raw = p.read_bytes().removeprefix(b"\xef\xbb\xbf")
    try:
        ok = V.is_valid(json.loads(raw))
    except ValueError:
        ok = False
    want = e["file"].startswith("valid/")
    if ok != want:
        bad += 1; print("MISMATCH", e["file"], "schema says", "valid" if ok else "invalid")
print("mismatches:", bad); sys.exit(1 if bad else 0)
