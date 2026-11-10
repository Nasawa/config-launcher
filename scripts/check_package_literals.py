#!/usr/bin/env python3
"""#242 / #243 F: fail on disallowed `dev.nasawa.configlauncher` and `applicationId/.` shorthand.

Allowed uses of the Kotlin namespace string:
  - `package dev.nasawa.configlauncher…` / `import dev.nasawa.configlauncher…` lines
  - Gradle `namespace = "dev.nasawa.configlauncher"`
  - unquoted fully-qualified class/type references (Kotlin/Java code, KDoc)
  - explicit `$PKG/dev.nasawa.configlauncher.Class` component strings
  - backtick-wrapped bare namespace (optional `.subpackage`) in docs
  - `NS=…` in pkg.env and `$NS` / `$NS.Class` uses in scripts
  - this checker and its tests

NOT allowed:
  - the string literal `"dev.nasawa.configlauncher"` (or any quoted occurrence) outside the
    Gradle namespace line — including inside Kotlin source files
  - `com.anigeek.reflowlauncher/.` (slash-dot after the applicationId) anywhere
  - the same shorthand built from the variable: `$PKG/.`, `${PKG}/.`, `{PKG}/.`,
    `PKG + '/.'` / `PKG + "/."`, and a `/.MainActivity` / `/.ReloadReceiver` component after our
    package, one of those PKG forms, `<applicationId>` / `${applicationId}` / `{applicationId}`, or a
    `$NS` segment (`$PKG/$NS/.MainActivity`); another app's `com.example/.MainActivity` is fine
  - a regex-escaped namespace (`dev\\.nasawa…`, `dev[.]nasawa…`)
  - a partial namespace (`dev.nasawa` not followed by `.configlauncher`, e.g. `package="dev.nasawa`)
  - the old RELOAD action `dev.nasawa.configlauncher.RELOAD`, quoted or not (adb lines, XML comments)

Whole Kotlin package directories are not whitelisted.
"""
from __future__ import annotations
import re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NEEDLE = "dev.nasawa.configlauncher"
_PKG = next(
    line.split("=", 1)[1].strip()
    for line in Path(__file__).with_name("pkg.env").read_text().splitlines()
    if line.startswith("PKG=")
)
SHORTHAND = _PKG + "/."

# Grace #243 review gaps.
VAR_SHORTHAND = re.compile(r"(\$PKG|\$\{PKG\}|\{PKG\})/\.|\bPKG\s*\+\s*[\"']/\.")
# Only our package (literal, $PKG / ${PKG} / {PKG}, `PKG + '/.'`), the `<applicationId>` /
# `${applicationId}` / `{applicationId}` placeholders (#247: the manifest-comment form 9d6474a shipped),
# or a `$NS` / `${NS}` / `{NS}` segment (`$PKG/$NS/.MainActivity`); an unrelated package such as
# `com.example/.MainActivity` is not ours and stays clean.
SLASHDOT_COMPONENT = re.compile(
    r"(?:" + re.escape(_PKG) + r"|\$PKG|\$\{PKG\}|\{PKG\}|\bPKG\s*\+\s*[\"']|<applicationId>"
    r"|\$\{applicationId\}|\{applicationId\}|\$NS|\$\{NS\}|\{NS\})"
    r"/\.(MainActivity|ReloadReceiver)\b"
)
ESCAPED_NS = re.compile(r"dev(\\+|\[\.\])\.?nasawa")
PARTIAL_NS = re.compile(r"dev\.nasawa(?!\.configlauncher\b)")
OLD_ACTION = re.compile(r"dev\.nasawa\.configlauncher\.RELOAD\b")

SKIP_DIRS = {".git", "build", ".gradle", "node_modules", ".idea"}
ALLOW_FILES = {
    "scripts/check_package_literals.py",
    "scripts/test_check_package_literals.py",
}

PACKAGE_LINE = re.compile(r"^\s*package\s+dev\.nasawa\.configlauncher(\.|$)")
IMPORT_LINE = re.compile(r"^\s*import\s+dev\.nasawa\.configlauncher(\.|$)")
NAMESPACE_GRADLE = re.compile(r'namespace\s*=\s*"dev\.nasawa\.configlauncher"')
FQ_COMPONENT = re.compile(re.escape(_PKG) + r"/dev\.nasawa\.configlauncher\.[A-Za-z0-9_]+")
BACKTICK_NS = re.compile(r"`dev\.nasawa\.configlauncher(\.[a-z]+)?`")
NS_ASSIGN = re.compile(r"^NS=dev\.nasawa\.configlauncher\s*$")
NS_VAR_USE = re.compile(r"\$NS\.|/\$NS\.|\$\{NS\}")
# Unquoted FQCN: identifier.path after the needle (code / KDoc), not inside quotes.
FQCN_CODE = re.compile(r"(?<![\"'`])dev\.nasawa\.configlauncher(\.[A-Za-z_][A-Za-z0-9_]*)+")


def _quoted_spans(line: str) -> list[tuple[int, int]]:
    """Byte ranges of double- or single-quoted string literals on the line (best-effort)."""
    spans = []
    i = 0
    while i < len(line):
        c = line[i]
        if c in "\"'":
            q = c
            j = i + 1
            while j < len(line):
                if line[j] == "\\" and j + 1 < len(line):
                    j += 2
                    continue
                if line[j] == q:
                    spans.append((i, j + 1))
                    i = j + 1
                    break
                j += 1
            else:
                break
        else:
            i += 1
    return spans


def _in_span(pos: int, spans: list[tuple[int, int]]) -> bool:
    return any(a <= pos < b for a, b in spans)


def needle_allowed(line: str) -> bool:
    if NEEDLE not in line:
        return True
    if PACKAGE_LINE.search(line) or IMPORT_LINE.search(line):
        return True
    if NAMESPACE_GRADLE.search(line):
        return True
    if NS_ASSIGN.search(line.strip()) or NS_VAR_USE.search(line):
        return True
    # Strip allowed forms, then see what's left.
    stripped = line
    stripped = FQ_COMPONENT.sub("", stripped)
    stripped = BACKTICK_NS.sub("", stripped)
    stripped = FQCN_CODE.sub("", stripped)
    if NEEDLE not in stripped:
        return True
    # Any remaining needle must not be inside a string literal — and even outside, it's disallowed
    # once FQCN/backtick/component forms are removed (e.g. a bare word in prose or a script).
    # Exception: if every remaining hit is still somehow only in an already-handled form — no.
    # Flag if any NEEDLE occurrence is inside quotes, OR if anything remains after stripping.
    spans = _quoted_spans(line)
    for m in re.finditer(re.escape(NEEDLE), line):
        if _in_span(m.start(), spans):
            # Quoted: only OK on the Gradle namespace line (already returned).
            return False
    # Unquoted remainder that isn't FQCN/backtick/component → fail
    return NEEDLE not in stripped


def line_problems(line: str) -> list[str]:
    """Every rule a single line breaks (empty list = clean)."""
    out: list[str] = []
    if SHORTHAND in line:
        out.append(f"shorthand {SHORTHAND!r}")
    if VAR_SHORTHAND.search(line):
        out.append("shorthand built from PKG (slash-dot after $PKG)")
    if SLASHDOT_COMPONENT.search(line):
        out.append("slash-dot component (class is under NS, not PKG)")
    if ESCAPED_NS.search(line):
        out.append("regex-escaped namespace")
    if PARTIAL_NS.search(line):
        out.append("partial namespace `dev.nasawa`")
    if OLD_ACTION.search(line):
        out.append("old RELOAD action")
    elif NEEDLE in line and not needle_allowed(line):
        out.append("namespace literal")
    return out


def check(root: Path = ROOT) -> list[str]:
    bad: list[str] = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        if any(part in SKIP_DIRS for part in path.relative_to(root).parts):
            continue
        if rel in ALLOW_FILES:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, IsADirectoryError):
            continue
        for i, line in enumerate(text.splitlines(), 1):
            problems = line_problems(line)
            if problems:
                bad.append(f"{rel}:{i}: {'; '.join(problems)}: {line.strip()[:140]}")
    return bad


def main() -> int:
    bad = check()
    if bad:
        print(f"grep-gate (#242 F): {len(bad)} disallowed hit(s):", file=sys.stderr)
        for b in bad[:60]:
            print(b, file=sys.stderr)
        if len(bad) > 60:
            print(f"... and {len(bad) - 60} more", file=sys.stderr)
        return 1
    print(f"grep-gate (#242 F): ok (no disallowed `{NEEDLE}` or `{SHORTHAND}`)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
