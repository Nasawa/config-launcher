#!/usr/bin/env python3
"""Tests for scripts/validate.py (#56). Run from the repo root: python3 scripts/test_validate.py

The phone's answers for every fixture are pinned in test-fixtures/phone-output.json by the JVM test
PhoneOutputGoldenTest; here the script must give the same error lines and the same canonical bytes.
"""
import hashlib, json, re, pathlib, subprocess, sys, tempfile, unittest

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
import validate as V  # noqa: E402

FIX = ROOT / "test-fixtures" / "configs"
PARSER_TEXT = re.compile(r"not valid JSON: (Unexpected JSON token|Cannot read Json element)")
GOLDEN = json.loads((ROOT / "test-fixtures" / "phone-output.json").read_text())


class RuleOrder(unittest.TestCase):
    def test_drawer_rule_keys_canonical(self):
        tree, errs = V.validate((FIX / "valid" / "drawer-rules-key-order.json").read_bytes(), use_schema=False)
        self.assertEqual([], errs)
        r = json.loads(V.canonical(tree))["drawer"]["rules"][0]
        self.assertEqual(["id", "group", "match"], list(r))
        self.assertEqual(["packages", "packagePrefix", "tag", "category"], list(r["match"]))


class MatchesPhone(unittest.TestCase):
    def test_every_fixture_same_errors_and_canonical_bytes(self):
        problems = []
        for name, want in GOLDEN.items():
            raw = (FIX / name).read_bytes()
            tree, errs = V.validate(raw, use_schema=False)   # phone rules only, like the app
            if "errors" in want:
                if len(want["errors"]) == 1 and PARSER_TEXT.match(want["errors"][0]):
                    # Syntax errors are worded by each JSON library; only the verdict has to match.
                    if len(errs) != 1 or not errs[0].startswith("not valid JSON: "):
                        problems.append(f"{name}: script {errs[:2]} != phone syntax error")
                elif errs != want["errors"]:
                    problems.append(f"{name}: script {errs[:3]} != phone {want['errors'][:3]}")
            else:
                if errs:
                    problems.append(f"{name}: script rejects {errs[:2]}, phone accepts")
                    continue
                sha = hashlib.sha256(V.canonical(tree).encode("utf-8")).hexdigest()
                if sha != want["canonicalSha256"]:
                    problems.append(f"{name}: canonical output differs from the phone's")
                if V.warnings(tree) != want.get("warnings", []):
                    problems.append(f"{name}: script warnings {V.warnings(tree)} != phone {want.get('warnings', [])}")
        self.assertEqual([], problems)

    def test_schema_pass_agrees_with_manifest(self):
        for e in json.loads((FIX / "manifest.json").read_text()):
            _, errs = V.validate((FIX / e["file"]).read_bytes())
            self.assertEqual(e["file"].startswith("valid/"), not errs, (e["file"], errs[:2]))


class Rules(unittest.TestCase):
    base = {"schemaVersion": 1, "grid": {"columns": 4, "rows": 5}, "pages": [{"id": "home", "items": []}]}

    def errs(self, doc):
        return V.validate(json.dumps(doc).encode(), use_schema=False)[1]

    def test_issue_example_duplicate_id_and_same_cell(self):
        d = json.loads(json.dumps(self.base))
        d["pages"][0]["items"] = [{"id": "a", "type": "app", "package": "a.b", "x": 0, "y": 0},
                                  {"id": "a", "type": "app", "package": "c.d", "x": 0, "y": 0}]
        e = self.errs(d)
        self.assertIn('pages[0].items[1].id: duplicate id "a" (also at pages[0].items[0])', e)
        self.assertIn("pages[0].items[1]: overlaps pages[0].items[0] at cell (0, 0)", e)

    def test_ids_unique_across_pages_dock_folders_and_rules(self):
        d = json.loads(json.dumps(self.base))
        d["dock"] = [{"id": "home", "type": "app", "package": "a.b"}]
        self.assertTrue(any("duplicate id" in x for x in self.errs(d)))

    def test_grid_dock_folder_rules(self):
        d = json.loads(json.dumps(self.base))
        d["pages"][0]["items"] = [{"id": "a", "type": "app", "package": "a.b", "x": 4, "y": 0},
                                  {"id": "f", "type": "folder", "x": 1, "y": 0, "items": [
                                      {"id": "g", "type": "folder", "items": []},
                                      {"id": "c", "type": "app", "package": "c.d", "x": 0, "y": 0}]}]
        d["dock"] = [{"id": "d", "type": "app", "package": "e.f", "x": 0, "y": 0}]
        e = self.errs(d)
        self.assertIn("pages[0].items[0].x: 4 is outside the grid (4 columns)", e)
        self.assertIn("pages[0].items[1].items[0]: folders cannot be nested", e)
        self.assertIn("pages[0].items[1].items[1]: x/y are not allowed in folders", e)
        self.assertIn("dock[0]: x/y are not allowed in the dock", e)


class Format(unittest.TestCase):
    hand = ('{"pages":[{"items":[{"y":0,"x":1,"x-note":"hi","package":"a.b","type":"app","id":"a"}],"id":"home"}],'
            '"x-z":1.50,"schemaVersion":1,"revision":3,"unsorted":{"mode":"page"}}')

    def test_canonical_order_xkeys_last_lf_newline(self):
        tree, errs = V.validate(self.hand.encode(), use_schema=False)
        self.assertEqual([], errs)
        out = V.canonical(tree)
        self.assertEqual(out, """{
  "schemaVersion": 1,
  "revision": 3,
  "pages": [
    {
      "id": "home",
      "items": [
        {
          "id": "a",
          "type": "app",
          "package": "a.b",
          "x": 1,
          "y": 0,
          "x-note": "hi"
        }
      ]
    }
  ],
  "x-z": 1.50
}
""")

    def test_cli_format_is_idempotent_and_check_mode(self):
        with tempfile.TemporaryDirectory() as d:
            f = pathlib.Path(d) / "launcher.json"
            f.write_text(self.hand)
            run = lambda *a: subprocess.run([sys.executable, str(HERE / "validate.py"), "--no-schema", *a, str(f)], capture_output=True, text=True)
            self.assertEqual(1, run("--format", "--check").returncode)
            self.assertEqual(0, run("--format").returncode)
            first = f.read_bytes()
            self.assertFalse(first.startswith(b"\xef\xbb\xbf"))
            self.assertTrue(first.endswith(b"}\n") and b"\r" not in first)
            self.assertEqual(0, run("--format", "--check").returncode)
            self.assertEqual(first, f.read_bytes())

    def test_invalid_file_is_never_rewritten(self):
        with tempfile.TemporaryDirectory() as d:
            f = pathlib.Path(d) / "launcher.json"
            f.write_text('{"schemaVersion":1,"pages":[]}')
            r = subprocess.run([sys.executable, str(HERE / "validate.py"), "--no-schema", "--format", str(f)], capture_output=True, text=True)
            self.assertEqual(1, r.returncode)
            self.assertIn(".pages: needs at least 1 entries", r.stdout)
            self.assertEqual('{"schemaVersion":1,"pages":[]}', f.read_text())


if __name__ == "__main__":
    unittest.main()
