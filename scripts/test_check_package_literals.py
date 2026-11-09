#!/usr/bin/env python3
"""Unit test for scripts/check_package_literals.py (#242 F)."""
import unittest
import check_package_literals as g


class GrepGate(unittest.TestCase):
    def test_repo_is_clean(self):
        bad = g.check()
        self.assertEqual([], bad[:20], f"disallowed hits (showing up to 20 of {len(bad)})")


if __name__ == "__main__":
    unittest.main()
