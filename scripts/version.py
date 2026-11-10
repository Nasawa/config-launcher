#!/usr/bin/env python3
"""Compute versionName and versionCode from a git tag (#255).

Rule (also in AGENTS.md and the README):
  versionName is the tag without the leading v (1.0.0, 1.0.0-rc1).
  versionCode = major * 1_000_000 + minor * 10_000 + patch * 100 + suffix
    - a final release (no suffix) uses 99
    - -rcN uses 50 + N (N = 1..48)
    - -devN uses N (N = 1..49), so dev < rc < release for the same X.Y.Z
  Untagged builds are versionName 0.0.0-dev, versionCode 1 (same as every
  build shipped before this rule, which were all versionCode 1).

Run from the repo root:
  python3 scripts/version.py --tag v1.0.0-rc1
  python3 scripts/version.py --from-git
  python3 scripts/version.py --check-apk path/to/app.apk
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent

UNTAGGED_NAME = "0.0.0-dev"
UNTAGGED_CODE = 1
RELEASE_SUFFIX = 99
RC_BASE = 50
DEV_MAX = 49
RC_MAX = 48
MINOR_MAX = 99
PATCH_MAX = 99
MAJOR_MAX = 2147  # signed 32-bit versionCode

# vMAJOR.MINOR.PATCH, optional -devN or -rcN. No leading zeros.
TAG_RE = re.compile(
    r"^v(?P<major>0|[1-9][0-9]*)\.(?P<minor>0|[1-9][0-9]*)\.(?P<patch>0|[1-9][0-9]*)"
    r"(?:-(?P<pre>dev|rc)(?P<pre_n>[1-9][0-9]*))?$"
)
BADGING_RE = re.compile(
    r"versionCode='(?P<code>\d+)'.*versionName='(?P<name>[^']*)'"
)


class VersionError(ValueError):
    pass


def parse_tag(tag: str) -> tuple[str, int]:
    """Return (versionName, versionCode) for a v-prefixed tag, or raise VersionError."""
    raw = (tag or "").strip()
    m = TAG_RE.fullmatch(raw)
    if not m:
        raise VersionError(
            f"invalid version tag {raw!r}: expected vMAJOR.MINOR.PATCH, "
            f"vMAJOR.MINOR.PATCH-rcN or vMAJOR.MINOR.PATCH-devN "
            f"(no leading zeros; N >= 1)"
        )
    major = int(m.group("major"))
    minor = int(m.group("minor"))
    patch = int(m.group("patch"))
    pre = m.group("pre")
    pre_n = int(m.group("pre_n")) if m.group("pre_n") else None
    if major > MAJOR_MAX:
        raise VersionError(f"major {major} is too large for versionCode (max {MAJOR_MAX})")
    if minor > MINOR_MAX:
        raise VersionError(f"minor {minor} is too large (max {MINOR_MAX})")
    if patch > PATCH_MAX:
        raise VersionError(f"patch {patch} is too large (max {PATCH_MAX})")
    if pre is None:
        suffix = RELEASE_SUFFIX
    elif pre == "dev":
        if pre_n > DEV_MAX:
            raise VersionError(f"dev number {pre_n} is too large (max {DEV_MAX})")
        suffix = pre_n
    else:
        if pre_n > RC_MAX:
            raise VersionError(f"rc number {pre_n} is too large (max {RC_MAX})")
        suffix = RC_BASE + pre_n
    code = major * 1_000_000 + minor * 10_000 + patch * 100 + suffix
    name = raw[1:]  # strip the leading v
    return name, code


def untagged() -> tuple[str, int]:
    return UNTAGGED_NAME, UNTAGGED_CODE


def normalize_tag(value: str) -> str:
    """Accept v1.0.0 or 1.0.0; always return the v-prefixed form."""
    value = (value or "").strip()
    if value.startswith("refs/tags/"):
        value = value[len("refs/tags/") :]
    if not value.startswith("v"):
        value = "v" + value
    return value


def tag_from_env(env: dict[str, str] | None = None) -> str | None:
    """Explicit tag from REFLOW_TAG or a GITHUB_REF refs/tags/v* value."""
    env = os.environ if env is None else env
    explicit = (env.get("REFLOW_TAG") or "").strip()
    if explicit:
        return normalize_tag(explicit)
    ref = (env.get("GITHUB_REF") or "").strip()
    if ref.startswith("refs/tags/"):
        return normalize_tag(ref)
    return None


def tags_pointing_at_head(repo: Path) -> list[str]:
    r = subprocess.run(
        ["git", "tag", "--points-at", "HEAD"],
        cwd=repo,
        capture_output=True,
        text=True,
    )
    if r.returncode != 0:
        return []
    return [t.strip() for t in r.stdout.splitlines() if t.strip()]


def version_tags(tags: list[str]) -> list[str]:
    found = []
    for t in tags:
        if TAG_RE.fullmatch(t):
            found.append(t)
    return found


def resolve(repo: Path | None = None, env: dict[str, str] | None = None) -> tuple[str, int]:
    """Resolve the version for this checkout.

    Precedence: REFLOW_TAG, then GITHUB_REF if it is a v* tag, then a
    version tag pointing at HEAD, else the untagged dev version.
    An explicit or HEAD version tag that doesn't match the grammar fails.
    """
    env = os.environ if env is None else env
    forced = tag_from_env(env)
    if forced is not None:
        return parse_tag(forced)
    repo = ROOT if repo is None else repo
    tags = tags_pointing_at_head(repo)
    versioned = version_tags(tags)
    if len(versioned) == 1:
        return parse_tag(versioned[0])
    if len(versioned) > 1:
        # Same commit, several version tags: pick the highest versionCode.
        # If two tags share a code (shouldn't happen with this grammar), fail.
        parsed = [(t, *parse_tag(t)) for t in versioned]
        parsed.sort(key=lambda row: row[2])
        best_code = parsed[-1][2]
        winners = [row for row in parsed if row[2] == best_code]
        if len(winners) != 1:
            names = ", ".join(row[0] for row in winners)
            raise VersionError(f"HEAD has more than one version tag with versionCode {best_code}: {names}")
        return winners[0][1], winners[0][2]
    # A non-version tag on HEAD does not force a version; that's an untagged build.
    return untagged()


def format_output(name: str, code: int) -> str:
    return f"VERSION_NAME={name}\nVERSION_CODE={code}\n"


def parse_output(text: str) -> tuple[str, int]:
    name = code = None
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("VERSION_NAME="):
            name = line.split("=", 1)[1]
        elif line.startswith("VERSION_CODE="):
            code = int(line.split("=", 1)[1])
    if not name or code is None:
        raise VersionError(f"could not parse version output: {text!r}")
    return name, code


def parse_badging(text: str) -> tuple[str, int]:
    for line in text.splitlines():
        if line.startswith("package:"):
            m = BADGING_RE.search(line)
            if not m:
                break
            return m.group("name"), int(m.group("code"))
    raise VersionError("aapt badging did not include versionName/versionCode")


def find_build_tool(name: str) -> Path:
    candidates: list[Path] = []
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(key)
        if not root:
            continue
        bt = Path(root) / "build-tools"
        if not bt.is_dir():
            continue
        for d in bt.iterdir():
            exe = d / name
            if exe.is_file() and os.access(exe, os.X_OK):
                candidates.append(exe)
    if candidates:
        return sorted(candidates, key=lambda p: p.parent.name)[-1]
    found = shutil.which(name)
    if found:
        return Path(found)
    raise VersionError(f"could not find {name} (set ANDROID_HOME)")


def apk_version(apk: Path) -> tuple[str, int]:
    apk = apk.resolve()
    if not apk.is_file():
        raise VersionError(f"APK not found: {apk}")
    tool = find_build_tool("aapt2")
    r = subprocess.run(
        [str(tool), "dump", "badging", str(apk)],
        capture_output=True,
        text=True,
    )
    if r.returncode != 0:
        raise VersionError(f"aapt2 dump badging failed: {r.stderr.strip() or r.stdout.strip()}")
    return parse_badging(r.stdout)


def check_apk(apk: Path, repo: Path | None = None, env: dict[str, str] | None = None) -> tuple[str, int]:
    expected_name, expected_code = resolve(repo, env)
    actual_name, actual_code = apk_version(apk)
    if actual_name != expected_name or actual_code != expected_code:
        raise VersionError(
            f"APK version {actual_name} ({actual_code}) does not match "
            f"the version rule: {expected_name} ({expected_code})"
        )
    return actual_name, actual_code


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    src = p.add_mutually_exclusive_group()
    src.add_argument("--tag", help="parse this tag (with or without the leading v)")
    src.add_argument("--from-git", action="store_true", help="resolve from env and git (default)")
    p.add_argument("--check-apk", metavar="APK", help="require this APK to match the resolved version")
    p.add_argument("--repo", default=str(ROOT), help="repo root for git lookups")
    args = p.parse_args(argv)
    repo = Path(args.repo)
    try:
        if args.check_apk:
            name, code = check_apk(Path(args.check_apk), repo)
        elif args.tag is not None:
            name, code = parse_tag(normalize_tag(args.tag))
        else:
            name, code = resolve(repo)
        sys.stdout.write(format_output(name, code))
        return 0
    except VersionError as e:
        sys.stderr.write(f"version.py: {e}\n")
        return 1


if __name__ == "__main__":
    sys.exit(main())
