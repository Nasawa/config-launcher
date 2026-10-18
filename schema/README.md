# Schema
- `launcher-config.v1.schema.json`: JSON Schema 2020-12 for `launcher.json`. See ARCHITECTURE.md §3.
- `examples/minimal.json`: a valid example.

Validate locally:
```
pip install jsonschema
python -c "import json,jsonschema;jsonschema.validate(json.load(open('schema/examples/minimal.json')),json.load(open('schema/launcher-config.v1.schema.json')),cls=jsonschema.Draft202012Validator)"
```
