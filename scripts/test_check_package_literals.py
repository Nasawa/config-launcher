#!/usr/bin/env python3
"""Unit tests for scripts/check_package_literals.py (#242 / #243 F)."""
import tempfile
import textwrap
import unittest
from pathlib import Path
import check_package_literals as g


class GrepGateRepo(unittest.TestCase):
    def test_repo_is_clean(self):
        bad = g.check()
        self.assertEqual([], bad[:25], f"disallowed hits (showing up to 25 of {len(bad)})")


class GrepGateRules(unittest.TestCase):
    """Synthetic mutations that must be caught (Grace #243)."""

    def _scan(self, rel: str, body: str) -> list[str]:
        """Run the allow rules on a fake file body; return disallowed line descriptions."""
        bad = []
        for i, line in enumerate(body.splitlines(), 1):
            for p in g.line_problems(line):
                bad.append(f"{rel}:{i}: {'shorthand' if 'shorthand' in p or 'slash-dot' in p else 'needle'}")
        return bad

    def test_kotlin_string_literal_is_flagged(self):
        body = textwrap.dedent('''\
            package dev.nasawa.configlauncher.ui
            class X {
                val a = "dev.nasawa.configlauncher"
            }
        ''')
        bad = self._scan("app/src/main/java/dev/nasawa/configlauncher/ui/X.kt", body)
        self.assertTrue(any("needle" in b for b in bad), bad)
        # package line itself is fine
        self.assertFalse(any(b.endswith(":1: needle") for b in bad), bad)

    def test_import_and_package_allowed(self):
        body = textwrap.dedent('''\
            package dev.nasawa.configlauncher
            import dev.nasawa.configlauncher.store.ConfigStore
            class Ok
        ''')
        self.assertEqual([], self._scan("app/src/main/java/dev/nasawa/configlauncher/Ok.kt", body))

    def test_component_shorthand_is_flagged(self):
        body = "adb shell am start -n com.anigeek.reflowlauncher/.MainActivity\n"
        bad = self._scan("README.md", body)
        self.assertTrue(any("shorthand" in b for b in bad), bad)

    def test_fq_component_allowed(self):
        body = "adb shell am start -n com.anigeek.reflowlauncher/dev.nasawa.configlauncher.MainActivity\n"
        self.assertEqual([], self._scan("README.md", body))

    def test_namespace_as_package_in_script_flagged(self):
        body = "freeform = 'dev.nasawa.configlauncher' in top\n"
        bad = self._scan("scripts/screen_matrix_pager.py", body)
        self.assertTrue(any("needle" in b for b in bad), bad)

    def test_gradle_namespace_allowed(self):
        body = '    namespace = "dev.nasawa.configlauncher"\n'
        self.assertEqual([], self._scan("app/build.gradle.kts", body))

    def test_backtick_namespace_docs_allowed(self):
        body = "Kotlin `namespace` is `dev.nasawa.configlauncher` (unchanged).\n"
        self.assertEqual([], self._scan("ARCHITECTURE.md", body))


# Grace #243 review (690b4b0 GO notes): forms the gate still missed. Each must be flagged.
MUTATIONS = {
    "pkg_var_shorthand": ('scripts/emulator-smoke.sh', 'adb shell am start -W -n "$PKG/.MainActivity"\n'),
    "pkg_brace_shorthand": ('scripts/screen-matrix.sh', 'sh_ am broadcast -a "$PKG.RELOAD" -n "${PKG}/.ReloadReceiver"\n'),
    "py_concat_single_quote": ('scripts/screen_matrix_check.py', "ok = (PKG + '/.MainActivity') in start\n"),
    "py_concat_double_quote": ('scripts/screen_matrix_check.py', 'ok = (PKG + "/.MainActivity") in start\n'),
    "py_fstring_shorthand": ('scripts/x.py', 'cmp = f"{PKG}/.MainActivity"\n'),
    "placeholder_slashdot_component": ('docs/spec.md', 'component `<applicationId>/.ReloadReceiver`\n'),
    "regex_escaped_ns": ('scripts/x.py', 'NODE = re.compile(r"dev\\.nasawa\\.configlauncher")\n'),
    "double_escaped_ns": ('app/src/test/X.kt', 'val r = Regex("dev\\\\.nasawa\\\\.configlauncher")\n'),
    "bracket_escaped_ns": ('scripts/x.py', 'NODE = re.compile(r"dev[.]nasawa[.]configlauncher")\n'),
    "partial_ns_package_attr": ('scripts/screen_matrix_check.py', "if 'package=\"dev.nasawa' not in t:\n"),
    "partial_ns_bare": ('README.md', 'Old app id starts with dev.nasawa and is gone.\n'),
    "partial_ns_truncated": ('scripts/x.py', "PREFIX = 'dev.nasawa.config'\n"),
    "unquoted_old_action_adb": ('README.md', 'adb shell am broadcast -a dev.nasawa.configlauncher.RELOAD\n'),
    "unquoted_old_action_xml_comment": ('app/src/main/AndroidManifest.xml', '<!-- action dev.nasawa.configlauncher.RELOAD -->\n'),
    "literal_pkg_receiver_shorthand": ('README.md', 'adb shell am broadcast -n com.anigeek.reflowlauncher/.ReloadReceiver\n'),
    "quoted_old_action_manifest": ('app/src/main/AndroidManifest.xml', '<action android:name="dev.nasawa.configlauncher.RELOAD" />\n'),
    # Grace #247 (#246 review): forms the narrowed prefix list let through.
    "gradle_placeholder_receiver_comment": ('app/src/main/AndroidManifest.xml', '<!-- -n ${applicationId}/.ReloadReceiver -->\n'),
    "gradle_placeholder_receiver_adb": ('app/src/main/AndroidManifest.xml', '`adb shell am broadcast -a ${applicationId}.RELOAD -n ${applicationId}/.ReloadReceiver`\n'),
    "brace_placeholder_main": ('docs/spec.md', '-n {applicationId}/.MainActivity\n'),
    "brace_placeholder_receiver": ('docs/spec.md', '-n {applicationId}/.ReloadReceiver\n'),
    "pkg_ns_slashdot_main": ('scripts/emulator-smoke.sh', 'am start -n "$PKG/$NS/.MainActivity"\n'),
    "pkg_brace_ns_slashdot_receiver": ('scripts/screen-matrix.sh', 'am broadcast -n "${PKG}/${NS}/.ReloadReceiver"\n'),
    "py_fstring_ns_slashdot": ('scripts/x.py', 'cmp = f"{PKG}/{NS}/.MainActivity"\n'),
}

# Correct forms that must stay clean.
CLEAN = {
    "pkg_ns_component": 'sh_ am start -W -n "$PKG/$NS.MainActivity"\n',
    "pkg_ns_receiver": 'sh_ am broadcast -a "$PKG.RELOAD" -n "$PKG/$NS.ReloadReceiver"\n',
    "pkg_p_reload": 'adb shell am broadcast -a "$PKG.RELOAD" -p "$PKG"\n',
    "start_timeout_prefix": "start_timeout = 'Status: timeout' in start and (PKG + '/') in start\n",
    "fq_new_receiver": 'adb shell am broadcast -a com.anigeek.reflowlauncher.RELOAD -n com.anigeek.reflowlauncher/dev.nasawa.configlauncher.ReloadReceiver\n',
    "kotlin_import": 'import dev.nasawa.configlauncher.store.ConfigStore\n',
    "manifest_relative_name": '            android:name=".ReloadReceiver"\n',
    # Another app's component shorthand is not ours (Grok Bot #245 follow-up): only our package / PKG / <applicationId>.
    "unrelated_pkg_main": 'adb shell am start -n com.example/.MainActivity\n',
    "unrelated_pkg_receiver": 'adb shell am broadcast -n com.example.other/.ReloadReceiver\n',
    # #247: the manifest's real RELOAD action (dot, no slash) and the full placeholder component stay clean.
    "manifest_placeholder_action": '                <action android:name="${applicationId}.RELOAD" />\n',
    "placeholder_fq_receiver": 'adb shell am broadcast -a ${applicationId}.RELOAD -n ${applicationId}/dev.nasawa.configlauncher.ReloadReceiver\n',
    "unrelated_applicationid_word": 'adb shell am start -n com.example.applicationId/.MainActivity\n',
}


class GrepGateMutations(unittest.TestCase):
    def test_each_mutation_is_flagged(self):
        for name, (_rel, body) in MUTATIONS.items():
            with self.subTest(name):
                self.assertTrue(g.line_problems(body.rstrip("\n")), f"{name} not caught: {body!r}")

    def test_correct_forms_stay_clean(self):
        for name, body in CLEAN.items():
            with self.subTest(name):
                self.assertEqual([], g.line_problems(body.rstrip("\n")), name)

    def test_check_finds_every_mutation_in_a_tree(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for i, (name, (rel, body)) in enumerate(MUTATIONS.items()):
                f = root / f"m{i:02d}" / rel
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_text(body)
            bad = g.check(root)
            self.assertEqual(len(MUTATIONS), len(bad), bad)


if __name__ == "__main__":
    unittest.main()
