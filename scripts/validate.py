#!/usr/bin/env python3
"""Validate launcher.json the way the phone does, and optionally rewrite it canonically.

    python3 scripts/validate.py launcher.json            # check; exit 0 = valid, 1 = invalid
    python3 scripts/validate.py --format launcher.json   # check, then rewrite in canonical form
    python3 scripts/validate.py --format --check f.json  # exit 1 if the file isn't already canonical

Mirrors the app (ARCHITECTURE.md §2, §17 AQ-3; app/.../config/{JsonPreScan,Validator,ConfigCodec}.kt):
  1. byte cap (2 MiB), strict UTF-8, BOM stripped, git conflict markers;
  2. pre-scan: nesting depth <= 16, no duplicate keys, no NUL, no lone surrogates;
  3. JSON syntax, schemaVersion (only 1);
  4. JSON Schema (schema/launcher-config.v1.schema.json) when `jsonschema` is installed;
  5. the phone's semantic rules: unique IDs across the file, no two page items in one cell, cells inside
     `grid`, dock/folder items without x/y, no nested folders, page items need x/y, <= 2000 items.
Error lines use the phone's wording and paths, so they match `.configlauncher/status.json`.

--format writes what the phone writes (AQ-2): 2-space indent, schema key order, `x-` keys after known
keys (sorted), LF, trailing newline, UTF-8 without BOM; the deprecated `unsorted` key is dropped (AF-2).
Item order and unspecified keys like `grid` are kept as written; only a phone edit changes those.
Stdlib only, plus optional `jsonschema`.
"""
import argparse, decimal, json, math, pathlib, re, sys

MAX_BYTES = 2 * 1024 * 1024
MAX_DEPTH = 16
MAX_ITEMS = 2000
MAX_STRING = 256
MAX_TAGS = 16
CURRENT = 1
ID = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
PKG = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
CLS = re.compile(r"^[A-Za-z0-9_.$]+$")
CATEGORIES = {"game", "audio", "video", "image", "social", "news", "maps", "productivity", "accessibility", "undefined"}
MAX_ELEMENTS = 32
MAX_OVERRIDES = 32
FEATURE_LEVEL = 4          # Elements.kt: 1 = #79 container release, 2 = title bar, 3 = appWidget (reserved), 4 = card
ELEMENT_TYPES = {}         # known element types, name -> class (Elements.types); filled below the classes
PLACEMENT = ("page", "x", "y", "w", "h")
TYPE = re.compile(r"^[a-zA-Z][a-zA-Z0-9]{0,31}$")
SCHEMA = pathlib.Path(__file__).resolve().parent.parent / "schema" / "launcher-config.v1.schema.json"


class Num(str):
    """A JSON number kept as its literal text, so x- values round-trip verbatim (like kotlinx JsonPrimitive)."""
    def is_int(self):
        return re.fullmatch(r"-?\d+", self) is not None


class DupKey(ValueError):
    pass


def _pairs(pairs):
    out = {}
    for k, v in pairs:
        if k in out:
            raise DupKey(k)
        out[k] = v
    return out


# ---------------------------------------------------------------- parsing (ConfigCodec.decode + JsonPreScan)

def git_marker_line(text):
    lines = text.split("\n")
    for i, l in enumerate(lines):
        if l.startswith("<<<<<<<"):
            rest = lines[i + 1:]
            if any(r.startswith("=======") for r in rest) and any(r.startswith(">>>>>>>") for r in rest):
                return i + 1
            return None
    return None


def prescan(text):
    """Line-for-line port of JsonPreScan.scan: depth, duplicate keys, NUL, lone surrogates, same messages."""
    stack, expect_key, line, i, n = [], False, 1, 0, len(text)
    esc = {"n": 0x0A, "t": 0x09, "r": 0x0D, "b": 0x08, "f": 0x0C}
    while i < n:
        c = text[i]
        if c == "\n":
            line += 1
        elif c in "{[":
            stack.append(set() if c == "{" else None)
            if len(stack) > MAX_DEPTH:
                return f"nested deeper than {MAX_DEPTH} levels (line {line})"
            expect_key = c == "{"
        elif c in "}]":
            if stack:
                stack.pop()
            expect_key = False
        elif c == ",":
            expect_key = bool(stack) and stack[-1] is not None
        elif c == ":":
            expect_key = False
        elif c == '"':
            units = []  # UTF-16 code units, as Kotlin sees the string
            i += 1
            while i < n and text[i] != '"':
                ch = text[i]
                if ch == "\\" and i + 1 < n:
                    i += 1
                    e = text[i]
                    if e == "u":
                        h = text[i + 1:i + 5]
                        i += 4
                        try:
                            u = int(h, 16)
                        except ValueError:
                            return f"bad \\u escape (line {line})"
                        units.append(u)
                    else:
                        units.append(esc.get(e, ord(e)))
                else:
                    cp = ord(ch)
                    units += [0xD800 + ((cp - 0x10000) >> 10), 0xDC00 + ((cp - 0x10000) & 0x3FF)] if cp > 0xFFFF else [cp]
                if units and units[-1] == 0:
                    return f"NUL character in a string (line {line})"
                i += 1
            k = 0
            while k < len(units):
                u = units[k]
                if 0xD800 <= u <= 0xDBFF:
                    if k + 1 >= len(units) or not 0xDC00 <= units[k + 1] <= 0xDFFF:
                        return f"unpaired surrogate in a string (line {line})"
                    k += 1
                elif 0xDC00 <= u <= 0xDFFF:
                    return f"unpaired surrogate in a string (line {line})"
                k += 1
            if expect_key:
                keys = stack[-1] if stack else None
                key = tuple(units)
                if keys is not None:
                    if key in keys:
                        name = struct_utf16(units)
                        return f'duplicate key "{name}" (line {line})'
                    keys.add(key)
                expect_key = False
        i += 1
    return None


def struct_utf16(units):
    return b"".join(u.to_bytes(2, "little") for u in units).decode("utf-16-le", "replace")


def parse(raw):
    """Returns (tree, errors)."""
    if len(raw) > MAX_BYTES:
        return None, [f"file is larger than {MAX_BYTES // 1024 // 1024} MiB"]
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError:
        return None, ["not valid UTF-8"]
    text = text.removeprefix("\ufeff")
    gl = git_marker_line(text)
    if gl is not None:
        return None, [f"not valid JSON: git merge conflict markers (line {gl}); finish the merge in git"]
    p = prescan(text)
    if p:
        return None, [f"not valid JSON: {p}"]
    try:
        tree = json.loads(text, object_pairs_hook=_pairs, parse_int=Num, parse_float=Num,
                          parse_constant=lambda c: (_ for _ in ()).throw(ValueError(c)))
    except DupKey as e:
        return None, [f'not valid JSON: duplicate key "{e.args[0]}"']
    except (ValueError, RecursionError) as e:
        return None, [f"not valid JSON: {str(e).splitlines()[0]}"]
    if isinstance(tree, dict):
        v = tree.get("schemaVersion")
        if isinstance(v, Num) and v.is_int():
            v = int(v)
            if v > CURRENT:
                return tree, [f"schemaVersion {v} is newer than this launcher supports ({CURRENT}); update the app"]
            if v < CURRENT:
                return tree, [f"schemaVersion {v} is not supported"]
    return tree, []


# ---------------------------------------------------------------- semantic validation (Validator.kt)

def _is_str(v):
    return isinstance(v, str) and not isinstance(v, Num)


def _cps(s):
    return len(s)  # Python str length is code points, like JSON Schema


class Run:
    def __init__(self):
        self.errors, self.ids, self.items, self.columns, self.rows = [], {}, 0, 4, 5
        self.page_cells = {}   # page id -> {(x, y): path}, for grid-placed elements (#79 Stage 1)
        self.singles = {}      # type -> path of the first shown element (one titleBar per file)
        self.shown_ids = {}    # element id -> type, for shown elements
        self.override_entries = []   # (element id, path, value), checked once elements are known

    def err(self, path, msg):
        self.errors.append(f"{path}: {msg}" if path else msg)

    def obj(self, e, path):
        if isinstance(e, dict):
            return e
        self.err(path, "must be an object")
        return None

    def keys(self, o, path, allowed, required, x_keys=True):
        for k in o:
            if k not in allowed and not (x_keys and k.startswith("x-")):
                self.err(path, f'unknown key "{k}"')
        for k in required:
            if k not in o:
                self.err(path, f'missing required "{k}"')

    def str(self, o, k, path, mx=None, mn=0, rx=None):
        if k not in o:
            return None
        s = o[k]
        if not _is_str(s):
            self.err(f"{path}.{k}", "must be a string")
            return None
        if _cps(s) > (mx or MAX_STRING):
            self.err(f"{path}.{k}", f"longer than {mx or MAX_STRING} characters")
        if _cps(s) < mn:
            self.err(f"{path}.{k}", "must not be empty")
        if rx is not None and not rx.match(s):
            self.err(f"{path}.{k}", f'"{s}" is not valid')
        return s

    def int(self, o, k, path, mn, mx=None):
        if k not in o:
            return None
        v = o[k]
        n = int(v) if isinstance(v, Num) and v.is_int() else None
        if n is None:
            self.err(f"{path}.{k}", "must be an integer")
            return None
        if n < mn or (mx is not None and n > mx):
            self.err(f"{path}.{k}", f"{n} is out of range {mn}..{mx if mx is not None else '∞'}")
        return n

    def arr(self, o, k, path, mx=None, mn=0):
        if k not in o:
            return None
        a = o[k]
        if not isinstance(a, list):
            self.err(f"{path}.{k}", "must be an array")
            return None
        if mx is not None and len(a) > mx:
            self.err(f"{path}.{k}", f"has {len(a)} entries, at most {mx} allowed")
        if len(a) < mn:
            self.err(f"{path}.{k}", f"needs at least {mn} entries")
        return a

    def root(self, e):
        o = self.obj(e, "")
        if o is None:
            return
        self.keys(o, "", {"$schema", "schemaVersion", "revision", "grid", "pages", "dock", "drawer", "elements", "unsorted"}, ["schemaVersion", "pages"])
        self.str(o, "$schema", "")
        if "schemaVersion" in o and not (isinstance(o["schemaVersion"], Num) and o["schemaVersion"] == "1"):
            self.err("schemaVersion", "must be 1")
        self.int(o, "revision", "", 0)
        if "grid" in o:
            go = self.obj(o["grid"], "grid")
            if go is not None:
                self.keys(go, "grid", {"columns", "rows"}, [], x_keys=False)
                c = self.int(go, "columns", "grid", 2, 12)
                if c is not None:
                    self.columns = min(max(c, 2), 12)
                r = self.int(go, "rows", "grid", 2, 12)
                if r is not None:
                    self.rows = min(max(r, 2), 12)
        for i, p in enumerate(self.arr(o, "pages", "", mx=20, mn=1) or []):
            self.page(p, f"pages[{i}]")
        for i, it in enumerate(self.arr(o, "dock", "", mx=7) or []):
            self.item(it, f"dock[{i}]", "dock")
        if "drawer" in o:
            self.drawer(o["drawer"])
        for i, e in enumerate(self.arr(o, "elements", "", mx=MAX_ELEMENTS) or []):
            self.element(e, f"elements[{i}]")
        for k, vp, vo in self.override_entries:
            if k in self.shown_ids:
                ELEMENT_TYPES[self.shown_ids[k]].override(self, vo, vp)
        if self.items > MAX_ITEMS:
            self.err("", f"{self.items} items in total, at most {MAX_ITEMS} allowed")
        if "unsorted" in o:
            uo = self.obj(o["unsorted"], "unsorted")
            if uo is not None:
                self.keys(uo, "unsorted", {"mode", "pageTitle"}, [], x_keys=False)
                m = self.str(uo, "mode", "unsorted")
                if m is not None and m not in ("drawer", "page", "hidden"):
                    self.err("unsorted.mode", "must be drawer, page or hidden")
                self.str(uo, "pageTitle", "unsorted", mx=40)

    def bool(self, o, k, path):
        if k not in o:
            return None
        if not isinstance(o[k], bool):
            self.err(f"{path}.{k}", "must be true or false")
            return None
        return o[k]

    def element(self, e, path):
        """#79: id, type, requires for every element; a skipped one (see skip()) gets nothing more."""
        o = self.obj(e, path)
        if o is None:
            return
        for k in ("id", "type"):
            if k not in o:
                self.err(path, f'missing required "{k}"')
        if "id" in o:
            self.id(o, path)
        t = self.str(o, "type", path, rx=TYPE)
        self.int(o, "requires", path, 1)
        self.placement(o, path)
        if t is None or skip(o) is not None or t not in ELEMENT_TYPES:
            return
        kind = ELEMENT_TYPES[t]
        if kind.SINGLE:
            if t in self.singles:
                self.err(path, f"only one {t} is allowed ({self.singles[t]} is already one).")
            else:
                self.singles[t] = path
        kind.validate(self, o, path)
        if _is_str(o.get("id")):
            self.shown_ids.setdefault(o["id"], t)

    def placement(self, o, path):
        """#79 Stage 1: grid placement for every element, before the type check; skipped ones still reserve cells."""
        placed = [k for k in PLACEMENT if k in o]
        self.str(o, "anchor", path, mx=32)
        if "anchor" in o and placed:
            self.err(path, 'has both "anchor" and a grid position; use one')
        if not placed:
            return
        for k in PLACEMENT:
            if k not in o:
                self.err(path, f'missing required "{k}" (grid-placed elements need page, x, y, w and h)')
        page = self.str(o, "page", path, rx=ID)
        x, y = self.int(o, "x", path, 0, 11), self.int(o, "y", path, 0, 11)
        w, h = self.int(o, "w", path, 1, 12), self.int(o, "h", path, 1, 12)
        if None in (page, x, y, w, h):
            return
        cells = self.page_cells.get(page)
        if cells is None:
            self.err(f"{path}.page", f'no page with id "{page}"')
            return
        if x + w > self.columns:
            self.err(path, f"x {x} + w {w} is outside the grid ({self.columns} columns)")
        if y + h > self.rows:
            self.err(path, f"y {y} + h {h} is outside the grid ({self.rows} rows)")
        for cy in range(y, min(y + h, self.rows)):
            for cx in range(x, min(x + w, self.columns)):
                other = cells.get((cx, cy))
                cells[(cx, cy)] = path
                if other is not None:
                    self.err(path, f"overlaps {other} at cell ({cx}, {cy})")
                    return

    def overrides(self, e, path):
        o = self.obj(e, path)
        if o is None:
            return
        if len(o) > MAX_OVERRIDES:
            self.err(path, f"has {len(o)} entries, at most {MAX_OVERRIDES} allowed")
        for k, v in o.items():
            if not ID.match(k):
                self.err(path, f'"{k}" is not a valid element id')
                continue
            vp = f"{path}.{k}"
            vo = self.obj(v, vp)
            if vo is None:
                continue
            self.keys(vo, vp, {"enabled", "content", "style"}, [], x_keys=False)
            if not vo:
                self.err(vp, "needs at least one of enabled, content or style")
            self.bool(vo, "enabled", vp)
            c = self.obj(vo["content"], f"{vp}.content") if "content" in vo else None
            st = self.obj(vo["style"], f"{vp}.style") if "style" in vo else None
            if ("content" not in vo or c is not None) and ("style" not in vo or st is not None):
                self.override_entries.append((k, vp, vo))

    def id(self, o, path):
        s = self.str(o, "id", path, rx=ID)
        if s is None:
            return
        prev = self.ids.get(s)
        self.ids[s] = path
        if prev is not None:
            self.err(f"{path}.id", f'duplicate id "{s}" (also at {prev})')

    def page(self, e, path):
        o = self.obj(e, path)
        if o is None:
            return
        self.keys(o, path, {"id", "title", "items", "overrides"}, ["id", "items"])
        self.id(o, path)
        self.str(o, "title", path, mx=40)
        if "overrides" in o:
            self.overrides(o["overrides"], f"{path}.overrides")
        cells = {}
        if _is_str(o.get("id")):
            self.page_cells.setdefault(o["id"], cells)
        for i, it in enumerate(self.arr(o, "items", path, mx=144) or []):
            ip = f"{path}.items[{i}]"
            self.item(it, ip, "page")
            if not isinstance(it, dict):
                continue
            x, y = it.get("x"), it.get("y")
            x = int(x) if isinstance(x, Num) and x.is_int() else None
            y = int(y) if isinstance(y, Num) and y.is_int() else None
            if x is not None and y is not None:
                if x >= self.columns:
                    self.err(f"{ip}.x", f"{x} is outside the grid ({self.columns} columns)")
                if y >= self.rows:
                    self.err(f"{ip}.y", f"{y} is outside the grid ({self.rows} rows)")
                other = cells.get((x, y))
                cells[(x, y)] = ip
                if other is not None:
                    self.err(ip, f"overlaps {other} at cell ({x}, {y})")

    def item(self, e, path, ctx):
        o = self.obj(e, path)
        if o is None:
            return
        self.items += 1
        t = o.get("type") if _is_str(o.get("type")) else None
        common = {"id", "type", "label", "x", "y"}
        if t == "app":
            self.keys(o, path, common | {"package", "activity", "profile", "tags"}, ["id", "type", "package"])
            self.str(o, "package", path, rx=PKG)
            self.str(o, "activity", path, rx=CLS)
            self.profile(o, path)
            tags = self.arr(o, "tags", path, mx=MAX_TAGS)
            if tags is not None:
                seen = set()
                for i, tg in enumerate(tags):
                    if not _is_str(tg):
                        self.err(f"{path}.tags[{i}]", "must be a string")
                    else:
                        if len(tg) > 40:
                            self.err(f"{path}.tags[{i}]", "longer than 40 characters")
                        if tg in seen:
                            self.err(f"{path}.tags", f'duplicate tag "{tg}"')
                        seen.add(tg)
        elif t == "shortcut":
            self.keys(o, path, common | {"package", "shortcutId", "profile"}, ["id", "type", "package", "shortcutId"])
            self.str(o, "package", path, rx=PKG)
            self.str(o, "shortcutId", path, mx=256, mn=1)
            self.profile(o, path)
        elif t == "folder":
            self.keys(o, path, common | {"items"}, ["id", "type", "items"])
            if ctx == "folder":
                self.err(path, "folders cannot be nested")
            else:
                for i, it in enumerate(self.arr(o, "items", path, mx=48) or []):
                    self.item(it, f"{path}.items[{i}]", "folder")
        elif t is None:
            self.err(f"{path}.type", "must be a string" if "type" in o else 'missing required "type"')
            return
        else:
            self.err(f"{path}.type", f'unknown type "{t}" (app, shortcut or folder)')
            return
        if "id" in o:
            self.id(o, path)
        self.str(o, "label", path, mx=60)
        self.int(o, "x", path, 0, 11)
        self.int(o, "y", path, 0, 11)
        if ctx == "page":
            if "x" not in o:
                self.err(path, 'page items need "x"')
            if "y" not in o:
                self.err(path, 'page items need "y"')
        elif "x" in o or "y" in o:
            self.err(path, f"x/y are not allowed in {'the dock' if ctx == 'dock' else 'folders'}")

    def profile(self, o, path):
        p = self.str(o, "profile", path)
        if p is not None and p not in ("personal", "work"):
            self.err(f"{path}.profile", "must be personal or work")

    def drawer(self, e):
        o = self.obj(e, "drawer")
        if o is None:
            return
        self.keys(o, "drawer", {"sort", "hidden", "rules"}, [], x_keys=False)
        s = self.str(o, "sort", "drawer")
        if s is not None and s not in ("label", "installTime"):
            self.err("drawer.sort", "must be label or installTime")
        h = self.arr(o, "hidden", "drawer")
        if h is not None:
            seen = set()
            for i, p in enumerate(h):
                if not _is_str(p) or not PKG.match(p) or len(p) > MAX_STRING:
                    self.err(f"drawer.hidden[{i}]", f"must be a package name (at most {MAX_STRING} characters)")
                elif p in seen:
                    self.err("drawer.hidden", f'duplicate "{p}"')
                else:
                    seen.add(p)
        for i, r in enumerate(self.arr(o, "rules", "drawer", mx=64) or []):
            rp = f"drawer.rules[{i}]"
            ro = self.obj(r, rp)
            if ro is None:
                continue
            self.keys(ro, rp, {"id", "match", "group"}, ["id", "match", "group"], x_keys=False)
            if "id" in ro:
                self.id(ro, rp)
            self.str(ro, "group", rp, mx=40, mn=1)
            if "match" in ro:
                mo = self.obj(ro["match"], f"{rp}.match")
                if mo is None:
                    continue
                self.keys(mo, f"{rp}.match", {"packages", "packagePrefix", "tag", "category"}, [], x_keys=False)
                if not mo:
                    self.err(f"{rp}.match", "needs at least one condition")
                for j, p in enumerate(self.arr(mo, "packages", f"{rp}.match") or []):
                    if not _is_str(p) or not PKG.match(p) or len(p) > MAX_STRING:
                        self.err(f"{rp}.match.packages[{j}]", f"must be a package name (at most {MAX_STRING} characters)")
                self.str(mo, "packagePrefix", f"{rp}.match", mx=128)
                self.str(mo, "tag", f"{rp}.match", mx=40)
                c = self.str(mo, "category", f"{rp}.match")
                if c is not None and c not in CATEGORIES:
                    self.err(f"{rp}.match.category", f'unknown category "{c}"')


def _plain(v):
    """Num -> int/float for jsonschema."""
    if isinstance(v, Num):
        return int(v) if v.is_int() else float(v)
    if isinstance(v, dict):
        return {k: _plain(x) for k, x in v.items()}
    if isinstance(v, list):
        return [_plain(x) for x in v]
    return v


def schema_errors(tree):
    try:
        import jsonschema
    except ImportError:
        return None
    v = jsonschema.Draft202012Validator(json.loads(SCHEMA.read_text()))
    out = []
    for e in sorted(v.iter_errors(_plain(tree)), key=lambda e: list(map(str, e.absolute_path))):
        path = "".join(f"[{p}]" if isinstance(p, int) else (f".{p}" if i else p) for i, p in enumerate(e.absolute_path))
        out.append(f"schema: {path or '(root)'}: {e.message}")
    return out


# ---------------------------------------------------------------- title bar (TitleBar.kt, Template.kt; #79 level 2)

class Template:
    NAMES = ("page", "pageNumber", "pageCount", "time", "date")
    SHORTHANDS = {"pageTitle": "page", "clock": "time", "date": "date"}
    LETTERS = {"y": 19, "M": 5, "d": 2, "E": 5, "a": 1, "H": 2, "h": 2, "k": 2, "K": 2, "m": 2}
    PUNCTUATION = " .,:/-"
    MAX_TEMPLATE, MAX_TOKENS, MAX_PATTERN = 120, 8, 32

    @staticmethod
    def check(s, path):
        """The ux.md §15.1 message for the first problem in template s, or None."""
        if s in Template.SHORTHANDS:
            return None
        if len(s) > Template.MAX_TEMPLATE:
            return f"{path} is {len(s)} characters; the maximum is {Template.MAX_TEMPLATE}."
        if "\n" in s or "\r" in s:
            return f"{path}: A template is one line. Remove the line break."
        i, tokens, n = 0, 0, len(s)
        while i < n:
            c = s[i]
            if c == "{" and s[i + 1:i + 2] == "{":
                i += 2
            elif c == "}" and s[i + 1:i + 2] == "}":
                i += 2
            elif c == "}":
                return f'{path}: the "}}" at character {i + 1} has no opening "{{". For a literal brace, write }}}}.'
            elif c == "{":
                j = s.find("}", i + 1)
                if j < 0:
                    return f'{path}: the "{{" at character {i + 1} isn\'t closed. For a literal brace, write {{{{.'
                inner = s[i + 1:j]
                name, colon, pattern = inner.partition(":")
                if name not in Template.NAMES:
                    return f"{path}: unknown token {{{name}}}. Use page, pageNumber, pageCount, time or date."
                if colon:
                    if name not in ("time", "date"):
                        return f"{path}: {{{inner}}} doesn't take a pattern. Only {{time}} and {{date}} do."
                    if not pattern:
                        return f"{path}: {{{name}:}} has an empty pattern. Write {{{name}}} for the default format."
                    if len(pattern) > Template.MAX_PATTERN:
                        return f"{path}: the pattern in {{{name}:…}} is {len(pattern)} characters; the maximum is {Template.MAX_PATTERN}."
                    bad = Template.bad_in_pattern(pattern)
                    if bad == "'":
                        return f"{path}: A ' quote isn't closed. Add a closing ' after the text."
                    if bad is not None and (len(bad) > 1 or bad in Template.LETTERS):
                        mx = Template.LETTERS[bad[0]]
                        return f"{path}: {bad} is too long. Use at most {mx}, like {bad[:mx]}."
                    if bad is not None:
                        return (f'{path}: "{bad}" isn\'t allowed in {{{inner}}}. '
                                "Use y M d E a H h k K m; seconds and time zones aren't supported.")
                tokens += 1
                i = j + 1
            else:
                i += 1
        if tokens > Template.MAX_TOKENS:
            return f"{path} has {tokens} tokens; the maximum is {Template.MAX_TOKENS}."
        return None

    @staticmethod
    def bad_in_pattern(p):
        i = 0
        while i < len(p):
            c = p[i]
            if c == "'":
                if p[i + 1:i + 2] == "'":
                    i += 2
                    continue
                j = i + 1
                while True:
                    if j >= len(p):
                        return "'"
                    if p[j] == "'":
                        if p[j + 1:j + 2] == "'":
                            j += 2
                            continue
                        break
                    j += 1
                i = j + 1
            elif c in Template.LETTERS:
                j = i
                while j < len(p) and p[j] == c:
                    j += 1
                if j - i > Template.LETTERS[c]:
                    return p[i:j]
                i = j
            elif c in Template.PUNCTUATION:
                i += 1
            else:
                return c
        return None


class TitleBar:
    """TitleBar.kt: the first element type (feature level 2). Widgets will register the same way."""
    LEVEL, SINGLE = 2, True
    KEYS = ("id", "type", "requires", "enabled", "anchor", "content", "style")
    SLOTS = ("start", "center", "end")
    STYLE_KEYS = ("size", "weight", "color", "align", "padding", "backdrop")
    COLOR_TOKENS = ("light", "dark", "onWallpaper", "primary", "secondary", "onSurface", "surface")
    HEX = re.compile(r"^#[0-9A-Fa-f]{6}$")
    SIZES = {"small": 14, "medium": 18, "large": 24}
    WEIGHTS = {"regular": 400, "medium": 500, "bold": 700}
    STATIC = {"light": "#FFFFFF", "onWallpaper": "#FFFFFF", "dark": "#1B1B1F"}

    @staticmethod
    def validate(r, o, path):
        r.keys(o, path, set(TitleBar.KEYS), [])
        r.bool(o, "enabled", path)
        a = r.str(o, "anchor", path)
        if a is not None and a != "top":
            r.err("", f'{path}.anchor must be "top".')
        if "content" in o:
            TitleBar.content(r, o["content"], f"{path}.content")
        if "style" in o:
            TitleBar.style(r, o["style"], f"{path}.style")

    @staticmethod
    def override(r, o, path):
        if "content" in o:
            TitleBar.content(r, o["content"], f"{path}.content")
        if "style" in o:
            TitleBar.style(r, o["style"], f"{path}.style")

    @staticmethod
    def content(r, e, path):
        o = r.obj(e, path)
        if o is None:
            return
        r.keys(o, path, {"slots"}, [], x_keys=False)
        if "slots" not in o:
            return
        slots = r.obj(o["slots"], f"{path}.slots")
        if slots is None:
            return
        r.keys(slots, f"{path}.slots", set(TitleBar.SLOTS), [], x_keys=False)
        for k in TitleBar.SLOTS:
            if k not in slots:
                continue
            if not _is_str(slots[k]):
                r.err(f"{path}.slots.{k}", "must be a string")
                continue
            m = Template.check(slots[k], f"{path}.slots.{k}")
            if m:
                r.err("", m)

    @staticmethod
    def _color(r, v, path):
        if not _is_str(v) or (v not in TitleBar.COLOR_TOKENS and not TitleBar.HEX.match(v)):
            r.err("", f"{path} must be #RRGGBB or one of " + ", ".join(TitleBar.COLOR_TOKENS) + ".")

    @staticmethod
    def style(r, e, path):
        o = r.obj(e, path)
        if o is None:
            return
        r.keys(o, path, set(TitleBar.STYLE_KEYS), [], x_keys=False)
        if "size" in o:
            v = o["size"]
            ok = v in TitleBar.SIZES if _is_str(v) else isinstance(v, Num) and 10.0 <= float(v) <= 40.0
            if not ok:
                r.err("", f"{path}.size must be small, medium or large, or a number from 10 to 40.")
        if "weight" in o:
            v = o["weight"]
            ok = v in TitleBar.WEIGHTS if _is_str(v) else (
                isinstance(v, Num) and v.is_int() and 300 <= int(v) <= 800 and int(v) % 100 == 0)
            if not ok:
                r.err("", f"{path}.weight must be regular, medium or bold, or 300 to 800 in steps of 100.")
        if "color" in o:
            v = o["color"]
            if isinstance(v, dict):
                r.keys(v, f"{path}.color", {"light", "dark"}, [], x_keys=False)
                if not v:
                    r.err("", f"{path}.color needs light or dark.")
                for k in ("light", "dark"):
                    if k in v:
                        TitleBar._color(r, v[k], f"{path}.color.{k}")
            else:
                TitleBar._color(r, v, f"{path}.color")
        if "align" in o:
            a = r.obj(o["align"], f"{path}.align")
            if a is not None:
                r.keys(a, f"{path}.align", set(TitleBar.SLOTS), [], x_keys=False)
                for k in TitleBar.SLOTS:
                    s = r.str(a, k, f"{path}.align")
                    if s is not None and s not in TitleBar.SLOTS:
                        r.err("", f"{path}.align.{k} must be start, center or end.")
        if "padding" in o:
            pd = r.obj(o["padding"], f"{path}.padding")
            if pd is not None:
                r.keys(pd, f"{path}.padding", {"horizontal", "vertical"}, [], x_keys=False)
                r.int(pd, "horizontal", f"{path}.padding", 0, 32)
                r.int(pd, "vertical", f"{path}.padding", 0, 24)
        if "backdrop" in o:
            v = o["backdrop"]
            if isinstance(v, dict):
                r.keys(v, f"{path}.backdrop", {"color", "opacity", "radius"}, ["color"], x_keys=False)
                if "color" in v:
                    TitleBar._color(r, v["color"], f"{path}.backdrop.color")
                r.int(v, "opacity", f"{path}.backdrop", 0, 100)
                r.int(v, "radius", f"{path}.backdrop", 0, 24)
            elif not (_is_str(v) and v in ("auto", "none", "scrim")):
                r.err("", f"{path}.backdrop must be auto, none or scrim, or an object with color.")

    @staticmethod
    def canonical(o):
        def order(d, keys, inner=lambda k, v: v):
            return {k: inner(k, d[k]) for k in keys if k in d}

        def style(k, v):
            if not isinstance(v, dict):
                return v
            return order(v, {"color": ("light", "dark"), "align": TitleBar.SLOTS, "padding": ("horizontal", "vertical"),
                             "backdrop": ("color", "opacity", "radius")}.get(k, tuple(v)))
        out = {}
        for k in TitleBar.KEYS:
            if k in o:
                v = o[k]
                if k == "content":
                    v = order(v, ("slots",), lambda _, s: order(s, TitleBar.SLOTS))
                elif k == "style":
                    v = order(v, TitleBar.STYLE_KEYS, style)
                out[k] = v
        out.update(_x(o))
        return out

    @staticmethod
    def _hex(s):
        if not _is_str(s):
            return None
        return s if TitleBar.HEX.match(s) else TitleBar.STATIC.get(s)

    @staticmethod
    def contrast(style):
        b = style.get("backdrop")
        if _is_str(b):
            if b != "scrim":
                return None
            bg, opacity = "#000000", 60
        elif isinstance(b, dict):
            bg = TitleBar._hex(b.get("color"))
            if bg is None:
                return None
            op = b.get("opacity")
            opacity = int(op) if isinstance(op, Num) and op.is_int() else 100
        else:
            return None
        col = style.get("color")
        if col is None:
            texts = ["light"]
        elif isinstance(col, dict):
            texts = [col.get("light", "light"), col.get("dark", "light")]
        else:
            texts = [col]
        texts = [h for h in map(TitleBar._hex, texts) if h is not None]
        if not texts:
            return None
        a = opacity / 100.0
        walls = ["#FFFFFF"] if opacity >= 100 else ["#FFFFFF", "#000000"]
        rgb = lambda h: [float(int(h[i:i + 2], 16)) for i in (1, 3, 5)]

        def lum(c):
            l = [(v / 12.92) if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in (x / 255.0 for x in c)]
            return 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2]
        m = None
        for w in walls:
            back = [bb * a + wl * (1 - a) for bb, wl in zip(rgb(bg), rgb(w))]
            for t in texts:
                la, lb = lum(rgb(t)), lum(back)
                r = (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
                m = r if m is None else min(m, r)
        return m

    THEME = ("primary", "secondary", "onSurface", "surface")

    @staticmethod
    def uses_theme(o):
        """#125: the phone measures this bar's contrast with its theme colours (TitleBar.phoneContrast); we can't."""
        st = o.get("style")
        if not isinstance(st, dict):
            return False
        b = st.get("backdrop")
        if b != "scrim" and not isinstance(b, dict):
            return False
        col = st.get("color")
        texts = [col.get("light", "light"), col.get("dark", "light")] if isinstance(col, dict) else [col]
        if isinstance(b, dict):
            texts.append(b.get("color"))
        return any(t in TitleBar.THEME for t in texts if _is_str(t))

    @staticmethod
    def warnings(o, path):
        st = o.get("style")
        if not isinstance(st, dict):
            return []
        r = TitleBar.contrast(st)
        if r is None or r >= 4.5:
            return []
        t = math.floor(r * 10)
        return [f"{path}.style.color: contrast {t // 10}.{t % 10}:1 on the backdrop (4.5:1 recommended). Shown anyway."]


ELEMENT_TYPES["titleBar"] = TitleBar


class Card:
    """Card.kt (#137 S3-A, feature level 4): dashboard card schema only (docs/design/dynamic-widgets.md §1-§4, §11)."""
    LEVEL, SINGLE = 4, False
    KEYS = ("id", "type", "requires", "page", "x", "y", "w", "h", "minSpan", "title", "author", "source", "surface", "capabilities", "data", "layouts")
    KINDS = {"data.file": (), "ui.reload": (), "ui.openApp": ("packages",), "ui.openUrl": ()}
    TAP_KINDS = {"ui.openUrl": ("url",), "ui.openApp": ("package",), "ui.reload": ()}
    NODES = {"column": ("children",), "row": ("children",), "tile": ("children",),
             "text": ("text", "role", "maxLines"), "metric": ("label", "value", "unit", "status"),
             "list": ("label", "items", "maxItems", "emptyText"), "icon": ("name", "package"),
             "progress": ("label", "value"), "button": ("label",)}
    NODE_REQUIRED = {"column": ("children",), "row": ("children",), "tile": ("children",), "text": ("text",),
                     "metric": ("value",), "list": ("items",), "progress": ("value",), "button": ("label", "tap")}
    COMMON = ("type", "priority", "weight", "tap")
    ROLES = ("caption", "body", "title")
    LAYOUT = re.compile(r"^([1-9][0-9]?)x([1-9][0-9]?)$")
    HOST = re.compile(r"^(https?://)?[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$")
    DURATION = re.compile(r"^P(?=[0-9]|T[0-9])([0-9]+D)?(T(?=[0-9])([0-9]+H)?([0-9]+M)?([0-9]+S)?)?$")
    URL = re.compile(r"^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)")
    MIN_REQUIRES, MAX_CAPABILITIES, MAX_TARGETS, MAX_LAYOUTS = 4, 8, 8, 3
    MAX_CHILDREN, MAX_DEPTH, MAX_NODES, MAX_TEXT, MAX_URL = 12, 4, 48, 200, 2048

    @staticmethod
    def unknown_capability(o):
        caps = o.get("capabilities")
        if not isinstance(caps, list):
            return None
        for c in caps:
            k = c.get("kind") if isinstance(c, dict) else None
            if _is_str(k) and k not in Card.KINDS:
                return k
        return None

    @staticmethod
    def _full(rx, s):
        return _is_str(s) and rx.fullmatch(s) is not None

    @staticmethod
    def validate(r, o, path):
        r.keys(o, path, set(Card.KEYS), ["requires", "page", "layouts"])
        q = o.get("requires")
        if isinstance(q, Num) and q.is_int() and int(q) < Card.MIN_REQUIRES:
            r.err(f"{path}.requires", f"a card needs feature level {Card.MIN_REQUIRES} or higher")
        if "minSpan" in o:
            m = r.obj(o["minSpan"], f"{path}.minSpan")
            if m is not None:
                r.keys(m, f"{path}.minSpan", {"w", "h"}, ["w", "h"], x_keys=False)
                r.int(m, "w", f"{path}.minSpan", 1, 12)
                r.int(m, "h", f"{path}.minSpan", 1, 12)
        r.str(o, "title", path, mx=40)
        r.str(o, "author", path, mx=40, mn=1)
        src = r.str(o, "source", path)   # Q-D5: the site taps open silently
        if src is not None and (len(src) > 253 + 8 or not Card._full(Card.HOST, src)):
            r.err("", f"{path}.source must be a lowercase hostname, optionally with http:// or https://.")
        sf = r.str(o, "surface", path)
        if sf is not None and sf != "auto":
            r.err("", f'{path}.surface must be "auto".')
        caps = Card.capabilities(r, o, path)
        if "data" in o:
            d = r.obj(o["data"], f"{path}.data")
            if d is not None:
                r.keys(d, f"{path}.data", {"staleAfter"}, [], x_keys=False)
                r.str(d, "staleAfter", f"{path}.data", mx=32, rx=Card.DURATION)
        if "layouts" in o:
            ls = r.obj(o["layouts"], f"{path}.layouts")
            if ls is not None:
                Card.layouts(r, ls, f"{path}.layouts", caps)

    @staticmethod
    def capabilities(r, o, path):
        kinds, packages = set(), set()
        for i, e in enumerate(r.arr(o, "capabilities", path, mx=Card.MAX_CAPABILITIES) or []):
            cp = f"{path}.capabilities[{i}]"
            co = r.obj(e, cp)
            if co is None:
                continue
            kind = r.str(co, "kind", cp)
            if kind is None:
                if "kind" not in co:
                    r.err(cp, 'missing required "kind"')
                continue
            if kind not in Card.KINDS:
                continue
            fields = Card.KINDS[kind]
            r.keys(co, cp, {"kind", *fields}, list(fields), x_keys=False)
            if kind in kinds:
                r.err(f"{cp}.kind", f'"{kind}" is listed twice')
            kinds.add(kind)
            for j, pk in enumerate(r.arr(co, "packages", cp, mx=Card.MAX_TARGETS, mn=1) or []):
                if not Card._full(PKG, pk):
                    r.err(f"{cp}.packages[{j}]", "must be a package name")
                elif pk in packages:
                    r.err(f"{cp}.packages[{j}]", f'"{pk}" is listed twice')
                else:
                    packages.add(pk)
        return kinds, packages

    @staticmethod
    def layouts(r, ls, path, caps):
        if not ls:
            r.err(path, "needs at least 1 entries")
        if len(ls) > Card.MAX_LAYOUTS:
            r.err(path, f"has {len(ls)} entries, at most {Card.MAX_LAYOUTS} allowed")
        for k in ls:
            m = Card.LAYOUT.fullmatch(k)
            if m is None or int(m.group(1)) > 12 or int(m.group(2)) > 12:
                r.err(path, f'"{k}" is not a size like "4x3" (lowercase x, 1 to 12)')
                continue
            count = [0]
            for i, n in enumerate(r.arr(ls, k, path, mx=Card.MAX_CHILDREN, mn=1) or []):
                Card.node(r, n, f"{path}.{k}[{i}]", 1, count, f"{path}.{k}", caps)

    @staticmethod
    def node(r, e, np, depth, count, lp, caps):
        count[0] += 1
        if count[0] == Card.MAX_NODES + 1:
            r.err(lp, f"has more than {Card.MAX_NODES} nodes")
        o = r.obj(e, np)
        if o is None:
            return
        t = r.str(o, "type", np)
        if t is None:
            if "type" not in o:
                r.err(np, 'missing required "type"')
            return
        if t not in Card.NODES:
            r.err(f"{np}.type", f'unknown node type "{t}". Use ' + ", ".join(Card.NODES) + ".")
            return
        fields = Card.NODES[t]
        r.keys(o, np, {*Card.COMMON, *fields}, list(Card.NODE_REQUIRED.get(t, ())), x_keys=False)
        r.int(o, "priority", np, 0, 9)
        r.int(o, "weight", np, 1, 12)
        has = lambda k: k in fields
        if has("text"):
            r.str(o, "text", np, mx=Card.MAX_TEXT)
        if has("role"):
            ro = r.str(o, "role", np)
            if ro is not None and ro not in Card.ROLES:
                r.err(f"{np}.role", "must be caption, body or title")
        if has("maxLines"):
            r.int(o, "maxLines", np, 1, 6)
        if has("label"):
            r.str(o, "label", np, mx=40)
        if t == "progress":
            if "value" in o:
                v = o["value"]
                if _is_str(v):
                    r.str(o, "value", np, mx=Card.MAX_TEXT)
                elif not (isinstance(v, Num) and 0 <= float(v) <= 1):
                    r.err(f"{np}.value", "must be a template or a number from 0 to 1")
        elif has("value"):
            r.str(o, "value", np, mx=Card.MAX_TEXT)
        if has("unit"):
            r.str(o, "unit", np, mx=16)
        if has("status"):
            r.str(o, "status", np, mx=Card.MAX_TEXT)
        if has("items"):
            r.str(o, "items", np, mx=Card.MAX_TEXT)
        if has("maxItems") and "maxItems" in o and o["maxItems"] != "auto":
            r.int(o, "maxItems", np, 1, 20)
        if has("emptyText"):
            r.str(o, "emptyText", np, mx=80)
        if t == "icon":
            r.str(o, "name", np, mx=64)
            r.str(o, "package", np, rx=PKG)
            if ("name" in o) == ("package" in o):
                r.err(np, 'needs exactly one of "name" or "package"')
        if "tap" in o:
            Card.tap(r, o["tap"], f"{np}.tap", caps)
        if has("children") and "children" in o:
            if depth >= Card.MAX_DEPTH:
                r.err(np, f"is nested too deep (at most {Card.MAX_DEPTH} levels)")
            else:
                for i, n in enumerate(r.arr(o, "children", np, mx=Card.MAX_CHILDREN, mn=1) or []):
                    Card.node(r, n, f"{np}.children[{i}]", depth + 1, count, lp, caps)

    @staticmethod
    def tap(r, e, tp, caps):
        kinds, packages = caps
        o = r.obj(e, tp)
        if o is None:
            return
        kind = r.str(o, "kind", tp)
        if kind is None:
            if "kind" not in o:
                r.err(tp, 'missing required "kind"')
            return
        if kind not in Card.TAP_KINDS:
            r.err(f"{tp}.kind", "must be ui.openUrl, ui.openApp or ui.reload")
            return
        fields = Card.TAP_KINDS[kind]
        r.keys(o, tp, {"kind", *fields}, list(fields), x_keys=False)
        if kind not in kinds:
            r.err(tp, f"{kind} isn't in this card's capabilities")
            return
        pk = r.str(o, "package", tp, rx=PKG)
        if pk is not None and pk not in packages:
            r.err(f"{tp}.package", f'"{pk}" isn\'t in the ui.openApp packages')
        url = r.str(o, "url", tp, mx=Card.MAX_URL, mn=1)
        if url is not None and "{" not in url:
            p = Card.url_problem(url)
            if p:
                r.err(f"{tp}.url", p)

    @staticmethod
    def url_problem(url):
        """Card.urlProblem: http or https, no user info; the host is decided at tap time (Q-D5)."""
        m = Card.URL.match(url)
        if m is None or m.group(1).lower() not in ("http", "https"):
            return "must be an http or https URL"
        if "@" in m.group(2):
            return "must not contain user info"
        return None

    @staticmethod
    def override(r, o, path):
        pass

    @staticmethod
    def warnings(o, path):
        return []

    @staticmethod
    def canonical(o):
        out = {}
        for k in Card.KEYS:
            if k not in o:
                continue
            v = o[k]
            if k == "minSpan":
                v = {kk: v[kk] for kk in ("w", "h") if kk in v}
            elif k == "capabilities":
                v = [{kk: c[kk] for kk in ("kind", *Card.KINDS[c["kind"]]) if kk in c} for c in v]
            out[k] = v
        out.update(_x(o))
        return out


ELEMENT_TYPES["card"] = Card


def _requires(o):
    v = o.get("requires")
    return int(v) if isinstance(v, Num) and v.is_int() else None


def skip(o):
    """Elements.skip: ("level", n) when requires > FEATURE_LEVEL, ("type", t) for an unknown type, else None."""
    r = _requires(o)
    if r is not None and r > FEATURE_LEVEL:
        return ("level", r)
    t = o.get("type")
    if not _is_str(t):
        return None
    if t not in ELEMENT_TYPES:
        return ("type", t)
    k = getattr(ELEMENT_TYPES[t], "unknown_capability", lambda o: None)(o)
    return ("kind", k) if k is not None else None


def warnings(tree):
    """Elements.warnings (ux.md §15.1): never reject the file; the phone puts them in status.json `warnings`."""
    out, els = [], tree.get("elements") or []
    for i, e in enumerate(els):
        s = skip(e)
        if s and s[0] == "level":
            out.append(f"elements[{i}] needs feature level {s[1]}, but this Config Launcher supports up to {FEATURE_LEVEL}, "
                       "so it was skipped. It stays in the file. Update the app to show it.")
        elif s and s[0] == "kind":
            out.append(f'elements[{i}]: capability "{s[1]}" isn\'t supported by this Config Launcher, so the card can\'t be shown. '
                       "It stays in the file. Update the app to show it.")
        elif s:
            out.append(f'elements[{i}]: type "{s[1]}" isn\'t supported by this Config Launcher, so it was skipped. It stays in the file.')
        elif e.get("type") in ELEMENT_TYPES:
            out.extend(ELEMENT_TYPES[e["type"]].warnings(e, f"elements[{i}]"))
    ids = {e["id"] for e in els if _is_str(e.get("id"))}
    for j, p in enumerate(tree["pages"]):
        for k in p.get("overrides") or {}:
            if k not in ids:
                out.append(f'pages[{j}].overrides: "{k}" doesn\'t match any element, so it was ignored.')
    return out


THEME_INFO = ('theme colours (primary, secondary, onSurface, surface) are only checked on the phone; '
              'see "checked on this phone" in .configlauncher/status.json.')


def infos(tree):
    """#125 (Pragma): one info line when a shown title bar's contrast depends on theme colours."""
    shown = [e for e in tree.get("elements") or [] if isinstance(e, dict) and not skip(e) and e.get("type") == "titleBar"]
    return [THEME_INFO] if any(TitleBar.uses_theme(e) for e in shown) else []


def ci_errors(tree):
    """#137: the phone skips a card whose capability kind it doesn't know (a warning); CI calls it an error so agents
    catch typos. Writers of a new kind bump the feature level and set requires instead."""
    out = []
    for i, e in enumerate(tree.get("elements") or []):
        s = skip(e) if isinstance(e, dict) else None
        if s and s[0] == "kind":
            j = next(j for j, c in enumerate(e["capabilities"]) if isinstance(c, dict) and c.get("kind") == s[1])
            out.append(f'elements[{i}].capabilities[{j}].kind: unknown capability "{s[1]}". '
                       "Use " + ", ".join(Card.KINDS) + ".")
    return out


def validate(raw, use_schema=True, ci=None):
    """Returns (tree or None, errors). Semantic (phone) errors first, then schema-only findings. [ci] (default: with
    the schema) adds the checks that are stricter than the phone (ci_errors)."""
    tree, errors = parse(raw)
    if errors:
        return tree, errors
    r = Run()
    r.root(tree)
    errors = r.errors
    _too_large(tree, "", errors)
    if (use_schema if ci is None else ci) and not errors:
        errors = ci_errors(tree)
    if use_schema and not errors:
        s = schema_errors(tree)
        errors = s or []
    return tree, errors


# ---------------------------------------------------------------- canonical encoding (ConfigCodec.encode)

LONG = (-(1 << 63), (1 << 63) - 1)
ULONG_MAX = (1 << 64) - 1


def _as_int(text):
    """kotlinx JsonLiteralSerializer: a number that fits Long (or ULong) is written as that integer."""
    if re.fullmatch(r"-?\d+", text):
        n = int(text)
        if LONG[0] <= n <= LONG[1] or 0 <= n <= ULONG_MAX:
            return n
    return None


def _java_double(d):
    """Double.toString (JDK 19+): shortest digits that round-trip, at least two when one would do."""
    if d == 0:
        return "-0.0" if math.copysign(1, d) < 0 else "0.0"
    sign = "-" if d < 0 else ""
    d = abs(d)
    digits, exp = _shortest(d)
    if len(digits) == 1:
        # Two digits closest to the exact value (JDK picks the closest 2-digit decimal, e.g. 4.9E-324).
        m, e = f"{decimal.Decimal(d):.1e}".split("e")
        digits, exp = m.replace(".", "").rstrip("0") or "0", int(e)
    if 1e-3 <= d < 1e7:
        if exp >= 0:
            ip = digits[:exp + 1].ljust(exp + 1, "0")
            fp = digits[exp + 1:] or "0"
        else:
            ip, fp = "0", "0" * (-exp - 1) + digits
        return f"{sign}{ip}.{fp}"
    return f"{sign}{digits[0]}.{digits[1:] or '0'}E{exp}"


def _shortest(d):
    """Shortest round-trip significant digits (no trailing zeros) and the decimal exponent of the first digit."""
    t = decimal.Decimal(repr(d)).normalize().as_tuple()
    return "".join(map(str, t.digits)), len(t.digits) - 1 + t.exponent


def _num_text(v):
    """A number as the phone writes it (#113): integers in Long/ULong range as is, everything else as a Java double."""
    n = _as_int(v)
    return str(n) if n is not None else _java_double(float(v))


def _too_large(tree, path, out):
    """#113: a number outside the double range can't be written back by the phone, so it's an error (any key)."""
    if isinstance(tree, Num):
        if _as_int(tree) is None and not math.isfinite(float(tree)):
            out.append(f"{path}: {tree} is too large for a number" if path else f"{tree} is too large for a number")
    elif isinstance(tree, dict):
        for k, x in tree.items():
            _too_large(x, f"{path}.{k}" if path else k, out)
    elif isinstance(tree, list):
        for i, x in enumerate(tree):
            _too_large(x, f"{path}[{i}]", out)


def _enc_str(s):
    out = ['"']
    for ch in s:
        o = ord(ch)
        if ch == '"':
            out.append('\\"')
        elif ch == "\\":
            out.append("\\\\")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ch == "\b":
            out.append("\\b")
        elif ch == "\f":
            out.append("\\f")
        elif o < 0x20:
            out.append("\\u%04x" % o)
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def dump(v, ind=0):
    pad, inner = "  " * ind, "  " * (ind + 1)
    if isinstance(v, dict):
        if not v:
            return "{}"
        return "{\n" + ",\n".join(f"{inner}{_enc_str(k)}: {dump(x, ind + 1)}" for k, x in v.items()) + "\n" + pad + "}"
    if isinstance(v, list):
        if not v:
            return "[]"
        return "[\n" + ",\n".join(inner + dump(x, ind + 1) for x in v) + "\n" + pad + "]"
    if isinstance(v, Num):
        return _num_text(v)
    if v is True:
        return "true"
    if v is False:
        return "false"
    if v is None:
        return "null"
    return _enc_str(v)


def _x(o):
    return {k: o[k] for k in sorted(o, key=lambda s: s.encode("utf-16-be")) if k.startswith("x-")}


def _num(v):
    return Num(str(int(v)))


def _item(o):
    out = {"id": o["id"], "type": o["type"]}
    t = o["type"]
    if t == "app":
        out["package"] = o["package"]
        for k in ("activity", "profile", "label"):
            if k in o:
                out[k] = o[k]
        if "tags" in o:
            out["tags"] = list(o["tags"])
    elif t == "shortcut":
        out["package"] = o["package"]
        out["shortcutId"] = o["shortcutId"]
        for k in ("profile", "label"):
            if k in o:
                out[k] = o[k]
    else:
        if "label" in o:
            out["label"] = o["label"]
        out["items"] = [_item(i) for i in o["items"]]
    for k in ("x", "y"):
        if k in o:
            out[k] = _num(o[k])
    out.update(_x(o))
    return out


def _ordered(o, keys):
    out = {k: o[k] for k in keys if k in o}
    out.update({k: v for k, v in o.items() if k not in keys})
    return out


def _rule(r):
    """ARCHITECTURE.md §20.3: id, group, match; inside match packages, packagePrefix, tag, category."""
    out = _ordered(r, ("id", "group", "match"))
    if isinstance(out.get("match"), dict):
        out["match"] = _ordered(out["match"], ("packages", "packagePrefix", "tag", "category"))
    return out


def canonical(tree):
    """Valid tree -> canonical text, as the phone writes it."""
    out = {}
    if "$schema" in tree:
        out["$schema"] = tree["$schema"]
    out["schemaVersion"] = Num("1")
    if "revision" in tree:
        out["revision"] = _num(tree["revision"])
    if "grid" in tree:
        g = tree["grid"]
        out["grid"] = {"columns": _num(g.get("columns", 4)), "rows": _num(g.get("rows", 5))}
    pages = []
    for p in tree["pages"]:
        po = {"id": p["id"]}
        if "title" in p:
            po["title"] = p["title"]
        po["items"] = [_item(i) for i in p["items"]]
        if "overrides" in p:
            po["overrides"] = p["overrides"]   # verbatim (#79)
        po.update(_x(p))
        pages.append(po)
    out["pages"] = pages
    if "dock" in tree:
        out["dock"] = [_item(i) for i in tree["dock"]]
    if "drawer" in tree:
        d, do = tree["drawer"], {}
        for k in ("sort", "hidden"):
            if k in d:
                do[k] = d[k]
        if "rules" in d:
            do["rules"] = [_rule(r) for r in d["rules"]]
        out["drawer"] = do
    if "elements" in tree:
        # File order; a shown title bar in canonical key order, anything skipped verbatim (#79).
        out["elements"] = [ELEMENT_TYPES[e["type"]].canonical(e) if skip(e) is None and e.get("type") in ELEMENT_TYPES else e
                           for e in tree["elements"]]
    out.update(_x(tree))
    return dump(out) + "\n"


# ---------------------------------------------------------------- CLI

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("files", nargs="+", type=pathlib.Path)
    ap.add_argument("--format", action="store_true", help="rewrite valid files in the phone's canonical form")
    ap.add_argument("--check", action="store_true", help="with --format: don't write, fail if not canonical")
    ap.add_argument("--no-schema", action="store_true", help="skip the JSON Schema pass")
    a = ap.parse_args(argv)
    if not a.no_schema:
        try:
            import jsonschema  # noqa: F401
        except ImportError:
            print("note: jsonschema not installed; only the phone-side rules were checked (pip install jsonschema)", file=sys.stderr)
    status = 0
    for f in a.files:
        raw = f.read_bytes()
        tree, errors = validate(raw, use_schema=not a.no_schema, ci=True)
        if errors:
            status = 1
            print(f"{f}: invalid ({len(errors)} problem{'s' if len(errors) != 1 else ''})")
            for e in errors:
                print(f"  {e}")
            continue
        ws = warnings(tree)
        notes = [f"\n  warning: {w}" for w in ws] + [f"\n  info: {i}" for i in infos(tree)]
        say = lambda msg: print(msg + "".join(notes))
        if a.format:
            text = canonical(tree).encode("utf-8")
            if text != raw:
                if a.check:
                    status = 1
                    say(f"{f}: valid, not canonical (run with --format)")
                    continue
                f.write_bytes(text)
                say(f"{f}: valid, reformatted")
                continue
        say(f"{f}: valid")
    return status


if __name__ == "__main__":
    sys.exit(main())
