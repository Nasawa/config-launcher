#!/usr/bin/env python3
"""Tests for scripts/version.py (#255). Run from the repo root: python3 scripts/test_version.py

No test uses the real clock.
"""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
import version as V  # noqa: E402


def _code(tag: str) -> int:
    return V.parse_tag(tag)[1]


class ParseTag(unittest.TestCase):
    def test_release_1_0_0(self):
        self.assertEqual(V.parse_tag("v1.0.0"), ("1.0.0", 1_000_000 + 99))

    def test_rc_and_release_order(self):
        rc1 = V.parse_tag("v1.0.0-rc1")
        rc2 = V.parse_tag("v1.0.0-rc2")
        rel = V.parse_tag("v1.0.0")
        self.assertEqual(rc1, ("1.0.0-rc1", 1_000_000 + 50 + 1))
        self.assertEqual(rc2, ("1.0.0-rc2", 1_000_000 + 50 + 2))
        self.assertLess(rc1[1], rc2[1])
        self.assertLess(rc2[1], rel[1])

    def test_next_patch_is_higher(self):
        self.assertLess(_code("v1.0.0"), _code("v1.0.1-rc1"))
        self.assertLess(_code("v1.0.1-rc1"), _code("v1.0.1"))
        self.assertLess(_code("v1.0.1"), _code("v1.1.0"))
        self.assertLess(_code("v1.1.0"), _code("v2.0.0"))

    def test_dev_below_rc_and_release(self):
        dev9 = V.parse_tag("v0.0.1-dev9")
        self.assertEqual(dev9, ("0.0.1-dev9", 100 + 9))
        self.assertLess(_code("v1.0.0-dev1"), _code("v1.0.0-rc1"))
        self.assertLess(_code("v1.0.0-dev9"), _code("v1.0.0-rc1"))
        self.assertLess(_code("v1.0.0-rc1"), _code("v1.0.0"))

    def test_one_oh_is_above_every_earlier_build(self):
        # Every shipped build before this rule was versionCode 1.
        self.assertGreater(_code("v1.0.0-rc1"), 1)
        self.assertGreater(_code("v1.0.0"), 1)
        self.assertGreater(_code("v0.0.1-dev9"), 1)

    def test_untagged(self):
        self.assertEqual(V.untagged(), ("0.0.0-dev", 1))

    def test_rejects_bad_tags(self):
        for tag in (
            "",
            "v",
            "vfoo",
            "v1.0",
            "v1.0.0-rc",
            "v1.0.0-rc0",
            "v1.0.0-rc01",
            "v1.0.0-beta1",
            "v01.0.0",
            "v1.00.0",
            "v1.0.00",
            "v1.0.0-",
            "v1.0.0-rc.1",
            "1.0.0",  # parse_tag requires the v; normalize_tag is separate
        ):
            with self.subTest(tag=tag):
                with self.assertRaises(V.VersionError):
                    V.parse_tag(tag)

    def test_rejects_out_of_range(self):
        with self.assertRaises(V.VersionError):
            V.parse_tag("v1.100.0")
        with self.assertRaises(V.VersionError):
            V.parse_tag("v1.0.100")
        with self.assertRaises(V.VersionError):
            V.parse_tag("v1.0.0-dev50")
        with self.assertRaises(V.VersionError):
            V.parse_tag("v1.0.0-rc49")
        with self.assertRaises(V.VersionError):
            V.parse_tag("v2148.0.0")

    def test_normalize_tag(self):
        self.assertEqual(V.normalize_tag("1.0.0-rc1"), "v1.0.0-rc1")
        self.assertEqual(V.normalize_tag("v1.0.0-rc1"), "v1.0.0-rc1")
        self.assertEqual(V.normalize_tag("refs/tags/v1.0.0"), "v1.0.0")


class Resolve(unittest.TestCase):
    def test_reflow_tag_wins_over_github_ref(self):
        env = {"REFLOW_TAG": "v1.0.0-rc2", "GITHUB_REF": "refs/tags/v1.0.0"}
        self.assertEqual(V.resolve(ROOT, env), V.parse_tag("v1.0.0-rc2"))

    def test_github_ref_tag(self):
        env = {"GITHUB_REF": "refs/tags/v1.0.0-rc1", "REFLOW_TAG": ""}
        self.assertEqual(V.resolve(ROOT, env), V.parse_tag("v1.0.0-rc1"))

    def test_github_ref_branch_is_ignored(self):
        env = {"GITHUB_REF": "refs/heads/main", "REFLOW_TAG": ""}
        # This worktree is not on a version tag, so untagged.
        name, code = V.resolve(ROOT, env)
        self.assertEqual((name, code), V.untagged())

    def test_invalid_explicit_tag_fails(self):
        with self.assertRaises(V.VersionError):
            V.resolve(ROOT, {"REFLOW_TAG": "vnightly", "GITHUB_REF": ""})
        with self.assertRaises(V.VersionError):
            V.resolve(ROOT, {"REFLOW_TAG": "", "GITHUB_REF": "refs/tags/vfoo"})

    def test_from_git_on_temp_tagged_repo(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            env = {"GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t",
                   "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"}
            def git(*args):
                subprocess.check_call(["git", *args], cwd=repo, env={**os.environ, **env},
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            git("init")
            git("config", "user.email", "t@t")
            git("config", "user.name", "t")
            (repo / "f").write_text("x\n", encoding="utf-8")
            git("add", "f")
            git("commit", "-m", "init")
            empty = {"REFLOW_TAG": "", "GITHUB_REF": ""}
            self.assertEqual(V.resolve(repo, empty), V.untagged())
            git("tag", "v1.0.0-rc1")
            self.assertEqual(V.resolve(repo, empty), V.parse_tag("v1.0.0-rc1"))
            # A later commit with no new tag is untagged again.
            (repo / "f").write_text("y\n", encoding="utf-8")
            git("commit", "-am", "next")
            self.assertEqual(V.resolve(repo, empty), V.untagged())

    def test_highest_code_when_two_version_tags_share_a_commit(self):
        tags = ["v1.0.0-rc1", "v1.0.0"]
        versioned = V.version_tags(tags)
        self.assertEqual(sorted(versioned), ["v1.0.0", "v1.0.0-rc1"])
        # resolve's multi-tag branch is covered through a temp repo.
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            env = {"GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t",
                   "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"}
            def git(*args):
                subprocess.check_call(["git", *args], cwd=repo, env={**os.environ, **env},
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            git("init")
            git("config", "user.email", "t@t")
            git("config", "user.name", "t")
            (repo / "f").write_text("x\n", encoding="utf-8")
            git("add", "f")
            git("commit", "-m", "init")
            git("tag", "v1.0.0-rc1")
            git("tag", "v1.0.0")
            self.assertEqual(V.resolve(repo, {"REFLOW_TAG": "", "GITHUB_REF": ""}), V.parse_tag("v1.0.0"))


class OutputAndBadging(unittest.TestCase):
    def test_format_and_parse_output(self):
        text = V.format_output("1.0.0-rc1", 1000051)
        self.assertEqual(V.parse_output(text), ("1.0.0-rc1", 1000051))

    def test_parse_badging(self):
        text = (
            "package: name='com.anigeek.reflowlauncher' versionCode='1000051' "
            "versionName='1.0.0-rc1' platformBuildVersionName='15' compileSdkVersion='35'\n"
            "sdkVersion:'29'\n"
        )
        self.assertEqual(V.parse_badging(text), ("1.0.0-rc1", 1000051))

    def test_parse_badging_dev(self):
        text = "package: name='x' versionCode='1' versionName='0.0.0-dev'\n"
        self.assertEqual(V.parse_badging(text), ("0.0.0-dev", 1))

    def test_cli_tag(self):
        r = subprocess.run(
            [sys.executable, str(HERE / "version.py"), "--tag", "v1.0.0-rc1"],
            cwd=ROOT, capture_output=True, text=True,
        )
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(V.parse_output(r.stdout), ("1.0.0-rc1", 1000051))

    def test_cli_bad_tag_fails(self):
        r = subprocess.run(
            [sys.executable, str(HERE / "version.py"), "--tag", "vfoo"],
            cwd=ROOT, capture_output=True, text=True,
        )
        self.assertEqual(r.returncode, 1)
        self.assertIn("invalid version tag", r.stderr)


if __name__ == "__main__":
    unittest.main()
