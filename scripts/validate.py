#!/usr/bin/env python3
"""Validate launcher.json the way the phone does, and optionally rewrite it canonically.

    python3 scripts/validate.py launcher.json            # check; exit 0 = valid, 1 = invalid
    python3 scripts/validate.py --format launcher.json   # check, then rewrite in canonical form
    python3 scripts/validate.py --format --check f.json  # exit 1 if the file isn't already canonical

Mirrors the app (ARCHITECTURE.md §2, §17 AQ-3; app/.../config/{JsonPreScan,Validator,ConfigCodec}.kt):
  1. byte cap (2 MiB), strict UTF-8, BOM stripped, git conflict markers;
  2. pre-scan: nesting depth <= 8, no duplicate keys, no NUL, no lone surrogates;
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
MAX_DEPTH = 8
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
FEATURE_LEVEL = 1          # Elements.kt: #79 container release, no element type known yet
ELEMENT_TYPES = {}         # known element types (name -> level); the title bar adds itself at level 2
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
        if t is None or skip(o) is not None:
            return
        # Known element types validate here (none at feature level 1).

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
            if "content" in vo:
                self.obj(vo["content"], f"{vp}.content")
            if "style" in vo:
                self.obj(vo["style"], f"{vp}.style")

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
    return None if t in ELEMENT_TYPES else ("type", t)


def warnings(tree):
    """Elements.warnings (ux.md §15.1): never reject the file; the phone puts them in status.json `warnings`."""
    out, els = [], tree.get("elements") or []
    for i, e in enumerate(els):
        s = skip(e)
        if s and s[0] == "level":
            out.append(f"elements[{i}] needs feature level {s[1]}, but this Config Launcher supports up to {FEATURE_LEVEL}, "
                       "so it was skipped. It stays in the file. Update the app to show it.")
        elif s:
            out.append(f'elements[{i}]: type "{s[1]}" isn\'t supported by this Config Launcher, so it was skipped. It stays in the file.')
    ids = {e["id"] for e in els if _is_str(e.get("id"))}
    for j, p in enumerate(tree["pages"]):
        for k in p.get("overrides") or {}:
            if k not in ids:
                out.append(f'pages[{j}].overrides: "{k}" doesn\'t match any element, so it was ignored.')
    return out


def validate(raw, use_schema=True):
    """Returns (tree or None, errors). Semantic (phone) errors first, then schema-only findings."""
    tree, errors = parse(raw)
    if errors:
        return tree, errors
    r = Run()
    r.root(tree)
    errors = r.errors
    _too_large(tree, "", errors)
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
        out["elements"] = list(tree["elements"])   # verbatim, file order (#79)
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
        tree, errors = validate(raw, use_schema=not a.no_schema)
        if errors:
            status = 1
            print(f"{f}: invalid ({len(errors)} problem{'s' if len(errors) != 1 else ''})")
            for e in errors:
                print(f"  {e}")
            continue
        ws = warnings(tree)
        say = lambda msg: print(msg + "".join(f"\n  warning: {w}" for w in ws))
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
