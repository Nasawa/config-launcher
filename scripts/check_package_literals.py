#!/usr/bin/env python3
"""#242 F: fail if `dev.nasawa.configlauncher` appears outside the allowlist.

Allowed:
  - Kotlin package/namespace directories and directory paths under
    app/src/{main,test,androidTest}/java/dev/nasawa/configlauncher/
  - `import dev.nasawa.configlauncher…` / `package dev.nasawa.configlauncher…`
  - `.configlauncher/` path strings (sidecar folder name, unchanged)
  - ARCHITECTURE.md / README "formerly" / history notes that explain the rename
  - This checker and its test
  - Gradle `namespace = "dev.nasawa.configlauncher"`
"""
from __future__ import annotations
import re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NEEDLE = "dev.nasawa.configlauncher"
SKIP_DIRS = {".git", "build", ".gradle", "node_modules", ".idea"}

# Path prefixes (relative to ROOT) that may contain the needle freely.
ALLOW_PATH_PREFIXES = (
    "app/src/main/java/dev/nasawa/configlauncher/",
    "app/src/test/java/dev/nasawa/configlauncher/",
    "app/src/androidTest/java/dev/nasawa/configlauncher/",
)

ALLOW_FILE_NAMES = {
    "scripts/check_package_literals.py",
    "scripts/test_check_package_literals.py",
}

# Line-level allows when the path is not under a Kotlin package dir.
LINE_ALLOW = [
    re.compile(r"^\s*package\s+dev\.nasawa\.configlauncher"),
    re.compile(r"^\s*import\s+dev\.nasawa\.configlauncher"),
    re.compile(r"namespace\s*=\s*\"dev\.nasawa\.configlauncher\""),
    re.compile(r"\.configlauncher/"),  # sidecar folder
    re.compile(r"configlauncher/"),  # path segment in java dir mentions outside allow prefixes (rare)
    re.compile(r"formerly.*config-launcher|formerly.*configlauncher|Kotlin packages.*dev\.nasawa\.configlauncher", re.I),
    re.compile(r"namespace\s+\*\*`dev\.nasawa\.configlauncher`"),  # ARCHITECTURE table/docs
    re.compile(r"`dev\.nasawa\.configlauncher(\.[a-z]+)?`"),  # docs: namespace + subpackages table
    re.compile(r"dev/nasawa/configlauncher"),  # path in docs
]


def allowed_line(line: str) -> bool:
    if NEEDLE not in line:
        return True
    # .configlauncher/ contains "configlauncher" but not the full needle with dots... 
    # needle is dev.nasawa.configlauncher — .configlauncher/ alone does NOT match NEEDLE.
    return any(p.search(line) for p in LINE_ALLOW)


def check() -> list[str]:
    bad = []
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(ROOT).as_posix()
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        if rel in ALLOW_FILE_NAMES:
            continue
        if rel.startswith(ALLOW_PATH_PREFIXES):
            continue
        # skip binaries
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, IsADirectoryError):
            continue
        if NEEDLE not in text:
            continue
        for i, line in enumerate(text.splitlines(), 1):
            if NEEDLE in line and not allowed_line(line):
                bad.append(f"{rel}:{i}: {line.strip()[:160]}")
    return bad


def main() -> int:
    bad = check()
    if bad:
        print(f"grep-gate (#242 F): {len(bad)} disallowed `{NEEDLE}` hit(s):", file=sys.stderr)
        for b in bad[:50]:
            print(b, file=sys.stderr)
        if len(bad) > 50:
            print(f"... and {len(bad) - 50} more", file=sys.stderr)
        return 1
    print(f"grep-gate (#242 F): ok (no disallowed `{NEEDLE}`)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
