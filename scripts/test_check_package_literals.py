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
            if g.SHORTHAND in line:
                bad.append(f"{rel}:{i}: shorthand")
                continue
            if g.NEEDLE in line and not g.needle_allowed(line):
                bad.append(f"{rel}:{i}: needle")
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


if __name__ == "__main__":
    unittest.main()
