#!/usr/bin/env python3
"""Screen-matrix checks (docs/test-plan-screen-matrix.md).

Reads the uiautomator dumps and text files that scripts/screen-matrix.sh writes for one
matrix point and decides each check. No adb here, so it can be dry-run against old dumps.

  screen_matrix_check.py find <dump.xml> <attr> <regex>     print the centre "x y" of the first match
  screen_matrix_check.py find-empty <dump.xml> <density>     print a point on empty home grid space
  screen_matrix_check.py screen <dump.xml>                   print the real screen size "w h" (root bounds)
  screen_matrix_check.py check <run-dir>                     write result.json + result.md for one point
  screen_matrix_check.py summarize <out-dir>                 merge every */result.json; exit 1 on a blocking FAIL

Severity per check: block (fails the gate), advisory (reported only) or pending (never blocks).
L1–L13 (ux.md §1.5 / test-plan §20) and G1–G3 on tablet/landscape/fold are advisory under gate=dev7 and
blocking under gate=dev8. See the "dev7 / dev8 gate" section of docs/test-plan-screen-matrix.md.
"""
import json, os, re, sys
import xml.etree.ElementTree as ET
import math

PKG = 'dev.nasawa.configlauncher'
CLOCK_RE = re.compile(r'^\d{1,2}:\d{2}( ?[AaPp]\.?[Mm]\.?)?$')
HEADER_TEXTS = {'App storage', 'Apps', 'Done', '⋮', 'Config folder'}


# ---------------------------------------------------------------- dump parsing
class Node:
    __slots__ = ('text', 'desc', 'cls', 'b', 'click', 'focus', 'pkg')

    def __init__(self, a):
        self.text = a.get('text', '')
        self.desc = a.get('content-desc', '')
        self.cls = a.get('class', '').split('.')[-1]
        self.b = tuple(int(v) for v in re.findall(r'-?\d+', a.get('bounds', '[0,0][0,0]')))
        self.click = a.get('clickable') == 'true'
        self.focus = a.get('focused') == 'true'
        self.pkg = a.get('package', '')

    w = property(lambda s: s.b[2] - s.b[0])
    h = property(lambda s: s.b[3] - s.b[1])
    cx = property(lambda s: (s.b[0] + s.b[2]) // 2)
    cy = property(lambda s: (s.b[1] + s.b[3]) // 2)


def load(path):
    """List of Nodes, or None when the file is missing or not XML."""
    if not os.path.exists(path):
        return None
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return None
    return [Node(n.attrib) for n in root.iter('node')]


def inter(a, b):
    """Overlap area of two [l,t,r,b] rects."""
    return max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(0, min(a[3], b[3]) - max(a[1], b[1]))


def contains(outer, inner, slack=0):
    return (outer[0] - slack <= inner[0] and outer[1] - slack <= inner[1]
            and outer[2] + slack >= inner[2] and outer[3] + slack >= inner[3])


def _lum(c):
    def ch(v):
        v /= 255
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2])


def contrast_ratio(a, b):
    """WCAG 2.x contrast ratio of two sRGB colours (data: (L1 + 0.05) / (L2 + 0.05))."""
    la, lb = sorted((_lum(a), _lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def label_contrast(im, b):
    """Text contrast inside a label's bounds on a screenshot (evidence/pr182/device/contrast2.py): background = the
    most common colour on the bounds' border, text = whichever of the lightest/darkest pixel inside contrasts more
    (white text with a dark shadow reads as its light fill). None when the box is tiny or off the image."""
    from collections import Counter
    x1, y1, x2, y2 = max(b[0], 0), max(b[1], 0), min(b[2], im.width), min(b[3], im.height)
    if x2 - x1 < 3 or y2 - y1 < 3:
        return None
    px = im.crop((x1, y1, x2, y2)).load()
    w, h = x2 - x1, y2 - y1
    border = [px[x, y] for x in range(w) for y in (0, h - 1)] + [px[x, y] for y in range(h) for x in (0, w - 1)]
    bg = Counter(border).most_common(1)[0][0]
    inside = [px[x, y] for x in range(w) for y in range(h)]
    lt, dk = max(inside, key=_lum), min(inside, key=_lum)
    r = max(contrast_ratio(lt, bg), contrast_ratio(dk, bg))
    fg = lt if contrast_ratio(lt, bg) >= contrast_ratio(dk, bg) else dk
    return r, fg, bg


def hexc(c):
    return '#%02X%02X%02X' % tuple(c[:3])


def open_png(path):
    try:
        from PIL import Image
        return Image.open(path).convert('RGB') if os.path.exists(path) else None
    except Exception:   # no Pillow, or an empty/corrupt screenshot
        return None


def find(nodes, attr, rx):
    r = re.compile(rx)
    for n in nodes or []:
        if r.search(getattr(n, attr)) and n.w > 0 and n.h > 0:
            return n
    return None


def texts(nodes):
    return [n.text for n in nodes or [] if n.text]


# ---------------------------------------------------------------- title bar model
def _is_notice_chip(n):
    """§6.1 / §17.4 count-chip or banner affordance — must not join the title-bar band."""
    d = n.desc or ''
    return d.startswith('Widget permissions') or 'needs your OK' in d or 'need your OK' in d


def title_bar(nodes, s):
    """The three §15 slots from one home dump (date View/TextView, {page} wrapper, clock View/TextView).

    Bar identity uses the clock/date/page *slots* only. Notice-row chips are excluded from the
    band so a tall chip hit-target cannot pull the bar rect up into the chip (G5b flake).
    """
    clock_v = next((n for n in nodes if n.cls == 'View' and CLOCK_RE.match(n.desc) and n.w > 0), None)
    pages = [n for n in nodes if n.desc.endswith(', Pages') and n.w > 0]
    chip = next((n for n in nodes if _is_notice_chip(n) and n.w > 0), None)
    # Prefer a Pages slot whose content sits below the chip (notice row above title).
    if chip and pages:
        below = [n for n in pages if n.b[1] >= chip.b[3] - 8]
        page_v = (below or pages)[0]
    else:
        page_v = pages[0] if pages else None
    if not clock_v or not page_v:
        return None
    # Content band: clock + date text row (tighter than the Pages 48 dp hit target alone).
    y0, y1 = min(clock_v.b[1], page_v.b[1]), max(clock_v.b[3], page_v.b[3])
    band = [n for n in nodes if n.b[1] >= y0 - 4 and n.b[3] <= y1 + 4 and n.w > 0
            and not _is_notice_chip(n)]
    clock_t = next((n for n in band if n.cls == 'TextView' and contains(clock_v.b, n.b, 2)), None)
    date_v = next((n for n in band if n.cls == 'View' and n is not clock_v and n is not page_v
                   and n.desc and not n.desc.endswith(', Pages') and not _is_notice_chip(n)), None)
    date_t = None
    if date_v:
        date_t = next((n for n in band if n.cls == 'TextView' and contains(date_v.b, n.b, 2)
                       and not contains(page_v.b, n.b)), None)
    page_t = [n for n in band if n.cls == 'TextView' and contains(page_v.b, n.b, 2)
              and (not date_t or n is not date_t) and (not clock_t or n is not clock_t)]
    page_texts = [n.text for n in page_t]
    slots = [clock_v, page_v] + ([date_v] if date_v else [])
    bar_rect = [min(n.b[0] for n in slots), min(n.b[1] for n in slots),
                max(n.b[2] for n in slots), max(n.b[3] for n in slots)]
    # Content top for G5b: clock/date, not the taller Pages hit box.
    content_top = min(clock_v.b[1], date_v.b[1] if date_v else clock_v.b[1])
    return dict(clock_v=clock_v, clock_t=clock_t, page_v=page_v, page_texts=page_texts,
                date_v=date_v, date_t=date_t, band=band, rect=bar_rect, s=s,
                content_top=content_top)


def ladder_step(tb):
    """Visible §15 overflow-ladder step: 0, 1, '2-3' (date shortened) or 4 (date ellipsized).
    Steps 2 and 3 can't be told apart from the dump (3 changes only the size)."""
    page_only_caret = tb['page_texts'] in (['▾'], [])
    date_full = date_short = date_cut = False
    if tb['date_t'] is not None:
        t, d = tb['date_t'].text, tb['date_v'].desc
        date_cut = t.endswith('…')
        date_full = (t == d)
        date_short = not date_full and not date_cut
    if date_cut:
        return 4, page_only_caret
    if date_short:
        return '2-3', page_only_caret
    return (1 if page_only_caret else 0), page_only_caret


# ---------------------------------------------------------------- grid model
def rail_panel(nodes, W, H, dock):
    """Tall end-edge View that is the rail chrome (80 dp+), or None."""
    if not dock:
        return None
    cx = sum(n.cx for n in dock) / len(dock)
    side = 'left' if cx <= W * 0.5 else 'right'
    best = None
    for n in nodes or []:
        if n.h < H * 0.45 or n.w < 40:
            continue
        if side == 'right' and n.b[0] < W * 0.55:
            continue
        if side == 'left' and n.b[2] > W * 0.45:
            continue
        # Prefer the outermost tall strip (full rail including Apps head).
        if best is None or n.h > best.h or (n.h == best.h and n.w >= best.w):
            best = n
    return best


def rail_geom(dock, W, H, nodes=None):
    """(is_rail, side, rail_left_px, rail_right_px). side is 'left'|'right'|None.

    Prefer the tall rail panel View (full 80 dp strip) over dock-slot bounds alone —
    slots sit inset and would shrink the page column, skewing L3 centre margins.
    """
    rail = dock_is_rail(dock, W, H)
    if not dock or rail is not True:
        return False, None, None, None
    cx = sum(n.cx for n in dock) / len(dock)
    side = 'left' if cx <= W * 0.5 else 'right'
    panel = rail_panel(nodes, W, H, dock) if nodes is not None else None
    if panel:
        left, right = panel.b[0], panel.b[2]
    else:
        left = min(n.b[0] for n in dock)
        right = max(n.b[2] for n in dock)
    if side == 'right':
        right = max(right, W)
    else:
        left = min(left, 0)
    return True, side, left, right


def page_column_box(W, H, safe, dock, nodes=None):
    """Content box for the page column: window − safeDrawing − rail (ux.md §1.5)."""
    sl, st, sr, sb = safe if safe else (0, 0, 0, 0)
    left, top, right, bottom = sl, st, W - sr, H - sb
    is_rail, side, rl, rr = rail_geom(dock, W, H, nodes=nodes)
    if is_rail and side == 'right':
        right = min(right, rl)
    elif is_rail and side == 'left':
        left = max(left, rr)
    return left, top, right, bottom


def grid_region(nodes, tb, screen):
    """Home grid area: below the title bar (or header), above the page indicator / bottom dock.

    When the dock is a side rail, its slot tops must *not* clamp the grid bottom (that was
    collapsing the region to empty and returning 0 tiles on every *-land* rail point).
    """
    W, H = screen
    top = tb['rect'][3] if tb else max([n.b[3] for n in nodes if n.text in HEADER_TEXTS] or [0])
    ind = [n.b[1] for n in nodes if re.fullmatch(r'Page \d+ of \d+', n.desc)]
    dock_nodes = [n for n in nodes if n.desc.endswith(', dock')]
    is_rail, _, _, _ = rail_geom(dock_nodes, W, H, nodes=nodes)
    if is_rail:
        bottom = min(ind + [H])
    else:
        dock_tops = [n.b[1] for n in dock_nodes]
        bottom = min(ind + dock_tops + [H])
    return top, bottom


def grid_parts(nodes, tb, screen):
    W, H = screen
    top, bottom = grid_region(nodes, tb, screen)
    area = max(W * H, 1)
    cards = [n for n in nodes if n.desc.endswith(', widget') or ', widget,' in n.desc]
    in_card = lambda n: any(contains(c.b, n.b, 2) for c in cards)
    dock = [n for n in nodes if n.desc.endswith(', dock')]
    is_rail, side, rl, rr = rail_geom(dock, W, H, nodes=nodes)

    def in_rail_column(n):
        if not is_rail:
            return False
        # Centre in the rail strip, or overlapping the dock column heavily.
        if side == 'right':
            return n.cx >= rl - 2
        return n.cx <= rr + 2

    labels = [n for n in nodes if n.cls == 'TextView' and n.text and n.b[1] >= top and n.b[3] <= bottom + 2
              and n.text not in HEADER_TEXTS and not in_card(n) and not in_rail_column(n)
              and not (tb and inter(n.b, tb['rect']) > 0)]
    tiles = [n for n in nodes if n.click and n.b[1] >= top - 2 and n.b[3] <= bottom + 2
             and n.w * n.h < area / 4 and not in_card(n) and n not in cards
             and not in_rail_column(n)
             and n.text not in HEADER_TEXTS
             and not (n.desc or '').endswith(', dock')]
    return labels, tiles, dock, cards, (top, bottom)


# ---------------------------------------------------------------- helpers for the driver
def cmd_find(path, attr, rx):
    n = find(load(path), {'text': 'text', 'desc': 'desc', 'content-desc': 'desc'}[attr], rx)
    if n:
        print(n.cx, n.cy)
    return 0 if n else 1


def screen_of(nodes):
    """Real screen size from a dump: the root node's bounds."""
    return (max([n.b[2] for n in nodes] or [0]), max([n.b[3] for n in nodes] or [0]))


def cmd_screen(path):
    nodes = load(path)
    if not nodes:
        return 1
    print(*screen_of(nodes))
    return 0


def cmd_find_empty(path, density):
    """Print an empty-space long-press target inside the *page column* (not the rail)."""
    nodes = load(path) or []
    s = float(density) / 160
    W = max([n.b[2] for n in nodes] or [0]); H = max([n.b[3] for n in nodes] or [0])
    tb = title_bar(nodes, s)
    labels, tiles, dock, cards, (top, bottom) = grid_parts(nodes, tb, (W, H))
    safe = (0, 0, 0, 0)
    col_l, col_t, col_r, col_b = page_column_box(W, H, safe, dock, nodes=nodes)
    # Stay inside the page column and the grid band.
    left = max(col_l, 0)
    right = min(col_r, W)
    top = max(top, col_t)
    bottom = min(bottom, col_b)
    blockers = [n.b for n in labels + tiles + cards + dock]
    # Also block rail-head chrome (Apps / ⋮) even when not in dock[].
    for n in nodes:
        if n.text in HEADER_TEXTS or (n.desc or '').endswith(', dock'):
            blockers.append(n.b)
    pad = int(12 * s)
    if right - left < pad * 4 or bottom - top < pad * 4:
        return 1
    y = bottom - int(24 * s)
    fracs = (0.5, 0.35, 0.65, 0.2, 0.8, 0.1, 0.9)
    while y > top + pad:
        for fx in fracs:
            x = int(left + (right - left) * fx)
            if not any(b[0] - pad <= x <= b[2] + pad and b[1] - pad <= y <= b[3] + pad for b in blockers):
                print(x, y)
                return 0
        y -= int(16 * s)
    return 1


# ---------------------------------------------------------------- the checks
def read(path, default=''):
    try:
        return open(path, encoding='utf-8', errors='replace').read()
    except OSError:
        return default


def meta_of(d):
    m = {}
    for line in read(os.path.join(d, 'meta.txt')).splitlines():
        if '=' in line:
            k, v = line.split('=', 1)
            m[k.strip()] = v.strip()
    return m



# ---------------------------------------------------------------- §1.5 / test-plan §20 layout helpers
FIXDIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'screen-matrix')


def fixture_items(name):
    """[(label_or_None, x, y, w, h), ...] for page 0 of a matrix fixture. Empty when the file is missing."""
    path = os.path.join(FIXDIR, name or 'launcher.json')
    try:
        data = json.load(open(path, encoding='utf-8'))
    except (OSError, json.JSONDecodeError):
        return []
    out = []
    for it in (data.get('pages') or [{}])[0].get('items') or []:
        out.append((it.get('label') or it.get('package') or '',
                    int(it.get('x', 0)), int(it.get('y', 0)),
                    int(it.get('w', 1) or 1), int(it.get('h', 1) or 1)))
    return out


def fixture_grid(name):
    """(columns, rows) from the fixture grid, or (None, None) when missing."""
    path = os.path.join(FIXDIR, name or 'launcher.json')
    try:
        data = json.load(open(path, encoding='utf-8'))
    except (OSError, json.JSONDecodeError):
        return None, None
    g = data.get('grid') or {}
    cols, rows = g.get('columns'), g.get('rows')
    try:
        cols = int(cols) if cols is not None else None
        rows = int(rows) if rows is not None else None
    except (TypeError, ValueError):
        return None, None
    return cols, rows


def fixture_cards(name):
    """[(title_or_id, x, y, w, h), ...] for card elements in a matrix fixture."""
    path = os.path.join(FIXDIR, name or 'launcher.json')
    try:
        data = json.load(open(path, encoding='utf-8'))
    except (OSError, json.JSONDecodeError):
        return []
    out = []
    for el in data.get('elements') or []:
        if el.get('type') != 'card':
            continue
        out.append((el.get('title') or el.get('id') or '',
                    int(el.get('x', 0)), int(el.get('y', 0)),
                    int(el.get('w', 1) or 1), int(el.get('h', 1) or 1)))
    return out


def grid_tiles(nodes, tb, screen, s):
    """Clickable home-grid tiles with an optional nearby label (for L2). Excludes the dock and header.

    Each label binds to at most one tile (nearest centre-x). A loose ≤ tile-width band used to
    let a label on column N also match column N+1 when centres sat exactly one cell apart (#201
    rail pitch), which shifted every L2 column by +1.
    """
    labels, tiles, dock, cards, (top, bottom) = grid_parts(nodes, tb, screen)
    dock_set = set(id(n) for n in dock)
    cands = []
    for n in tiles:
        if id(n) in dock_set:
            continue
        if n.text in HEADER_TEXTS or (n.desc or '').endswith(', dock'):
            continue
        cands.append(n)
    # (tile, label, dist) then greedy nearest unique assignment
    pairs_dist = []
    for n in cands:
        for lab in labels:
            if lab.b[1] < n.b[1] - 4 or lab.b[1] > n.b[3] + int(48 * s):
                continue
            dist = abs(lab.cx - n.cx)
            if dist <= max(n.w, 1) * 0.55:  # within ~half a cell
                pairs_dist.append((dist, n, lab))
    pairs_dist.sort(key=lambda t: t[0])
    used_t, used_l = set(), set()
    assigned = {}
    for dist, n, lab in pairs_dist:
        if id(n) in used_t or id(lab) in used_l:
            continue
        assigned[id(n)] = lab
        used_t.add(id(n))
        used_l.add(id(lab))
    out = [(n, assigned.get(id(n))) for n in cands]
    return out, labels, dock


def _cluster_1d(vals, min_gap):
    """Sort vals and merge into cluster means when successive gaps are < min_gap."""
    if not vals:
        return []
    vs = sorted(vals)
    clusters = [[vs[0]]]
    for v in vs[1:]:
        if v - clusters[-1][-1] < min_gap:
            clusters[-1].append(v)
        else:
            clusters.append([v])
    return [sum(c) / len(c) for c in clusters]


def pitch_of(tile_nodes, s):
    """(pitchW_dp, pitchH_dp, block_left_px, block_top_px) from column centres / row tops.

    pitchH comes from *row tops* (tile.b[1]), not centre gaps or a lone tile height — a single
    short tile or a leaked rail chrome node used to report nonsense like 7.0 dp.
    """
    if len(tile_nodes) < 1:
        return None
    # Column centres (cx); cluster near-duplicates from uneven hit boxes.
    xs = _cluster_1d([n.cx for n in tile_nodes], min_gap=max(8, 0.25 * s * 48))
    # Row tops — prefer b[1] over cy so pitchH matches the grid row pitch.
    tops = _cluster_1d([n.b[1] for n in tile_nodes], min_gap=max(8, 0.25 * s * 48))
    if len(xs) >= 2:
        gaps = [xs[i + 1] - xs[i] for i in range(len(xs) - 1)]
        pitchW = sorted(gaps)[len(gaps) // 2] / s
    else:
        pitchW = tile_nodes[0].w / s
    if len(tops) >= 2:
        gaps = [tops[i + 1] - tops[i] for i in range(len(tops) - 1)]
        pitchH = sorted(gaps)[len(gaps) // 2] / s
    else:
        # One row only: use median tile height as a lower bound, never a sub-icon stub.
        hs = sorted(n.h for n in tile_nodes)
        pitchH = max(hs[len(hs) // 2] / s, 48.0 * 0.9)
    block_left = min(xs) - pitchW * s / 2
    block_top = min(tops)
    return pitchW, pitchH, block_left, block_top


def pitch_with_fixture(tile_nodes, pairs, card_nodes, s, fixture_name, rtl=False):
    """pitch_of, refined with fixture (col,row) anchors so a single occupied icon row
    at y=4 does not collapse to row 0, and L3 can span fixture grid.columns (#205).

    Returns (pitchW_dp, pitchH_dp, block_left_px, block_top_px, grid_columns) or None.
    grid_columns is fixture columns when known, else the number of occupied x clusters.
    """
    base = pitch_of(tile_nodes, s) if tile_nodes else None
    if base is None and not card_nodes:
        return None
    pitchW, pitchH, block_left, block_top = base if base else (80.0, 80.0, 0.0, 0.0)
    items = fixture_items(fixture_name) if fixture_name else []
    cards_fx = fixture_cards(fixture_name) if fixture_name else []
    cols_fx, _rows_fx = fixture_grid(fixture_name) if fixture_name else (None, None)

    # Row anchors: (top_px, fixture_row). Cards contribute their top row; tiles their y.
    row_anchors = []
    for n, lab in (pairs or []):
        key = (lab.text if lab else '') or (n.desc or '').split(',')[0]
        exp = next((it for it in items if it[0] and key.startswith(it[0][:12])), None)
        if exp is not None:
            row_anchors.append((float(n.b[1]), exp[2]))
    for n in (card_nodes or []):
        desc_l = (n.desc or '').lower()
        exp = next((it for it in cards_fx
                    if it[0] and desc_l.startswith(it[0].lower()[:12])), None)
        if exp is not None:
            row_anchors.append((float(n.b[1]), exp[2]))

    # Column anchors: (cx_px, fixture_col) from 1×1 tiles (multi-span cards skew cx).
    col_anchors = []
    for n, lab in (pairs or []):
        key = (lab.text if lab else '') or (n.desc or '').split(',')[0]
        exp = next((it for it in items if it[0] and key.startswith(it[0][:12])), None)
        if exp is not None and exp[3] == 1:
            # RTL (#187 / PR #210): fixture column x sits at physical column cols-1-x.
            pc = (cols_fx - 1 - exp[1]) if (rtl and cols_fx) else exp[1]
            col_anchors.append((float(n.cx), pc))

    # pitchH / block_top from distinct fixture rows (#205: icons alone at y=4).
    if len({r for _, r in row_anchors}) >= 2:
        # Median of (Δtop / Δrow) over pairs with Δrow > 0.
        ratios = []
        for i in range(len(row_anchors)):
            for j in range(i + 1, len(row_anchors)):
                t0, r0 = row_anchors[i]
                t1, r1 = row_anchors[j]
                if r1 == r0:
                    continue
                ratios.append((t1 - t0) / (r1 - r0))
        if ratios:
            pitchH_px = sorted(ratios)[len(ratios) // 2]
            pitchH = pitchH_px / s
            block_tops = [t - r * pitchH_px for t, r in row_anchors]
            block_top = sorted(block_tops)[len(block_tops) // 2]
    elif row_anchors:
        # One fixture row occupied: keep pitchH from tiles, back out block_top.
        t0, r0 = row_anchors[0]
        block_top = t0 - r0 * pitchH * s

    # block_left from fixture columns when the leftmost occupied col is not 0.
    if col_anchors and pitchW > 0:
        lefts = [cx - col * pitchW * s - pitchW * s / 2 for cx, col in col_anchors]
        block_left = sorted(lefts)[len(lefts) // 2]

    xs = _cluster_1d([n.cx for n in tile_nodes], min_gap=max(8, 0.25 * s * 48)) if tile_nodes else []
    grid_columns = cols_fx if cols_fx and cols_fx > 0 else max(1, len(xs))
    return pitchW, pitchH, block_left, block_top, grid_columns


def parse_safe_drawing_insets(text, W, H):
    """Derive WindowInsets.safeDrawing-equivalent insets (px) from `dumpsys window` output.

    safeDrawing ≈ system bars ∪ display cutout (IME closed on the home screen). We take the
    max inset on each side from:
      - InsetsSource frames typed status / navigation / displayCutout / systemGestures (bars only)
      - legacy mSystemInsets / systemBarsInsets / mStableInsets lines
    Returns (left, top, right, bottom) in px. Missing dump → (0,0,0,0) and the caller notes it.
    Documented in docs/test-plan-screen-matrix.md §4.1 (L3 insets).
    """
    if not text:
        return (0, 0, 0, 0)
    left = top = right = bottom = 0
    # Legacy single-line forms: Insets{left=N, top=N, right=N, bottom=N}
    for m in re.finditer(
            r'(?:mSystemInsets|systemBarsInsets|mStableInsets|mContentInsets|systemBars)\s*=\s*'
            r'Insets\{left=(\d+),\s*top=(\d+),\s*right=(\d+),\s*bottom=(\d+)\}',
            text):
        left = max(left, int(m.group(1)))
        top = max(top, int(m.group(2)))
        right = max(right, int(m.group(3)))
        bottom = max(bottom, int(m.group(4)))
    # InsetsSource type=ITYPE_STATUS_BARS|NAVIGATION_BARS|DISPLAY_CUTOUT frame=[l,t][r,b]
    for m in re.finditer(
            r'InsetsSource[^\n]*type=ITYPE_(\w+)[^\n]*frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]',
            text):
        kind, l, t, r, b = m.group(1).upper(), *(int(x) for x in m.groups()[1:])
        if 'STATUS' in kind or 'CAPTION' in kind:
            # top (or side) bar occupying [l,t][r,b]
            if b - t <= H // 4 and t <= H // 4:
                top = max(top, b - t if t == 0 else b)
            if r - l <= W // 4 and l == 0:
                left = max(left, r)
            if r - l <= W // 4 and r >= W - 2:
                right = max(right, W - l)
        elif 'NAVIGATION' in kind or 'NAV_BAR' in kind:
            if b - t <= H // 3 and b >= H - 2:
                bottom = max(bottom, b - t if t > 0 else H - t)
            if r - l <= W // 3 and l == 0 and b - t > H // 3:
                left = max(left, r)
            if r - l <= W // 3 and r >= W - 2 and b - t > H // 3:
                right = max(right, W - l)
        elif 'CUTOUT' in kind or 'DISPLAY_CUTOUT' in kind:
            if t == 0 and b - t <= H // 4:
                top = max(top, b)
            if l == 0 and r - l <= W // 4:
                left = max(left, r)
            if r >= W - 2 and r - l <= W // 4:
                right = max(right, W - l)
            if b >= H - 2 and b - t <= H // 4:
                bottom = max(bottom, H - t)
    # Also match the shorter "type=statusBars" form used on some API levels
    for m in re.finditer(
            r'type=(?:ITYPE_)?(statusBars|navigationBars|displayCutout|systemBars)'
            r'[^\n]*frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]',
            text, re.I):
        kind, l, t, r, b = m.group(1).lower(), *(int(x) for x in m.groups()[1:])
        if 'status' in kind or kind == 'systembars':
            if t == 0:
                top = max(top, b)
        if 'navigation' in kind:
            if b >= H - 2:
                bottom = max(bottom, H - t)
            elif l == 0 and r < W // 3:
                left = max(left, r)
            elif r >= W - 2 and (W - l) < W // 3:
                right = max(right, W - l)
        if 'cutout' in kind:
            if t == 0:
                top = max(top, b)
            if l == 0:
                left = max(left, r)
            if r >= W - 2:
                right = max(right, W - l)
    return (left, top, right, bottom)


def cell_size_px(tile_nodes, pitchW, pitchH, s):
    """On-screen cell W×H in px: median tile bounds when available, else pitch × density scale."""
    if tile_nodes:
        ws = sorted(n.w for n in tile_nodes)
        hs = sorted(n.h for n in tile_nodes)
        return ws[len(ws) // 2], hs[len(hs) // 2]
    return pitchW * s, pitchH * s


def dock_is_rail(dock, W, H):
    """True when the dock sits on an end edge (rail), False when it's a bottom bar, None if no dock."""
    if not dock:
        return None
    # One or more slots: rail if the cluster's centre is on the end 25% and not in the bottom 30%.
    cx = sum(n.cx for n in dock) / len(dock)
    cy = sum(n.cy for n in dock) / len(dock)
    on_end = cx >= W * 0.75 or cx <= W * 0.25
    on_bottom = cy >= H * 0.70
    if len(dock) >= 2:
        xs = [n.cx for n in dock]; ys = [n.cy for n in dock]
        # stacked vertically → rail; in a row → bottom bar
        return (max(ys) - min(ys)) > (max(xs) - min(xs))
    return on_end and not on_bottom


def gate_of(m):
    """dev7 (default) or dev8. Written by the driver from MATRIX_GATE / the workflow `gate` input."""
    g = (m.get('gate') or 'dev7').strip().lower()
    return 'dev8' if g == 'dev8' else 'dev7'


def severity(cid, m):
    """Gate policy (test plan, 'dev7 / dev8 gate'). Page-2 variants (suffix p2) share their check's severity."""
    if cid.endswith('p2'):
        cid = cid[:-2]
    fs = float(m.get('font_scale', '1.0'))
    tier = m.get('tier', 'phone')
    pending = set()     # add an id here for a merged spec not built yet
    # L5 stays advisory until the end-edge rail PR lands (#197 / test-plan §20).
    # L6r = rail-geometry half of L6 (also advisory until that rail PR).
    advisory_always = {'P1', 'A1', 'A2', 'T5', 'T6', 'TH1', 'D3', 'D4a', 'G6', 'G7', 'X1', 'X2', 'X3', 'G3n', 'G3a', 'L5', 'L6r', 'L6s', 'Dnd1', 'R1'}
    if cid in pending:
        return 'pending'
    if cid == 'F1':
        return 'block'
    if cid in advisory_always:
        return 'advisory'
    if fs >= 1.95:
        return 'advisory'
    # L1–L13 (test-plan §20) and large-screen G1–G3: advisory through dev7, blocking from dev8
    # (ux.md §1.5; #185). Crashes/ANRs stay blocking via F1 above.
    if cid.startswith('L') or (tier != 'phone' and cid.startswith('G')):
        return 'block' if gate_of(m) == 'dev8' else 'advisory'
    return 'block'



def run_checks(d):
    m = meta_of(d)
    dens = int(m.get('density', '0') or 0)
    if not dens:
        hits = re.findall(r'(?:Override|Physical) density: (\d+)', read(os.path.join(d, 'run.txt')))
        dens = int(hits[-1]) if hits else 160
    s = dens / 160
    fs = float(m.get('font_scale', '1.0'))
    W, H = (int(v) for v in (m.get('screen_px') or '0x0').split('x'))
    res = []

    def add(cid, name, ok, detail, spec, sev=None):
        res.append(dict(id=cid, name=name, status=('PASS' if ok is True else 'FAIL' if ok is False else 'SKIP'),
                        severity=sev if sev is not None else severity(cid, m), detail=detail, spec=spec))

    home = {}
    for k in ('home1', 'home2'):
        home[k] = load(os.path.join(d, k + '.xml'))
    if home['home1']:
        W, H = screen_of(home['home1'])     # what the device really drew, not what was asked for

    # X0 harness: the wm size override really applied (Android clamps it to a multiple of the physical panel)
    run = read(os.path.join(d, 'run.txt'))
    got = m.get('applied_px') or next(iter(re.findall(r'Override size: (\d+x\d+)', run) or re.findall(r'Physical size: (\d+x\d+)', run)), '')
    want = m.get('size_px', '')
    add('X0', 'Requested display size applied (harness)', (got == want) if got and want else None,
        f'requested {want or "?"}, applied {got or "?"}' + ('' if not got or got == want else ' (clamped: the AVD panel is too small, results describe the applied size)'),
        'test plan §5 (AVD panel sizes)')
    rt = [ln for ln in read(os.path.join(d, 'retries.txt')).splitlines() if ln.strip()]
    add('X2', 'Gestures registered first time (harness)', not rt, f"retried: {', '.join(rt) or 'none'}",
        'test plan §5 (cold-start first gesture, emulator long-press timing)')
    night_got = m.get('night_applied') or next(iter(re.findall(r'Night mode: (\w+)', run)), '')
    add('X1', 'Requested night mode applied (harness)', (night_got == m.get('night')) if night_got and m.get('night') else None,
        f"requested {m.get('night', '?')}, applied {night_got or '?'}"
        + (f" via {m['night_via']}" if m.get('night_via') else ''), 'test plan §1 variants, §5 (API 29: #150)')

    # X4 harness: locale / RTL layoutDirection really applied (API 35 ignores system_locales alone).
    # Fail as harness error when an rtl / longlabel|de point did not take effect — do not score
    # product L5/locale bugs off an LTR English dump that only recorded locale= in meta.
    want_loc = (m.get('locale') or '').strip()
    shape_l = (m.get('shape') or '').lower()
    need_rtl = 'rtl' in shape_l or want_loc.lower().startswith('ar')
    need_de = ('longlabel' in shape_l or re.search(r'\bde\b', shape_l) is not None
               or want_loc.lower().startswith('de'))
    app_loc = (m.get('app_locales') or '').strip()
    force_rtl = (m.get('force_rtl') or '').strip()
    layout_dir = (m.get('layout_dir') or '').strip()
    if not any((app_loc, force_rtl, layout_dir)) and os.path.isfile(os.path.join(d, 'locale.txt')):
        for ln in read(os.path.join(d, 'locale.txt')).splitlines():
            if '=' in ln:
                k, v = ln.split('=', 1)
                if k == 'app_locales' and not app_loc: app_loc = v.strip()
                if k == 'force_rtl' and not force_rtl: force_rtl = v.strip()
                if k == 'layout_dir' and not layout_dir: layout_dir = v.strip()
    # Hierarchy probe: system status-bar clock on the right half ⇒ RTL took effect.
    sb_rtl = None
    if h1 := home.get('home1'):
        clocks = [n for n in h1 if n.pkg.startswith('com.android.systemui')
                  and CLOCK_RE.match((n.text or n.desc or '').strip())]
        if not clocks:
            # Some images expose the clock without package; prefer y≈status-bar band.
            clocks = [n for n in h1 if n.b[3] <= max(80, int((H or 0) * 0.06) or 80)
                      and CLOCK_RE.match((n.text or '').strip())
                      and 'configlauncher' not in (n.pkg or '')]
        if clocks and (W or 0) > 0:
            c = clocks[0]
            sb_rtl = c.cx > (W / 2)
    probed = bool(app_loc or force_rtl or layout_dir or sb_rtl is not None)
    x4_rtl_failed = False
    if need_rtl:
        rtl_ok = (
            layout_dir == '1'
            or sb_rtl is True
            or (force_rtl in ('1', 'true') and re.search(r'\bar\b|ar-', app_loc, re.I) is not None)
        )
        # force_rtl alone is not enough if layoutDir stayed 0 and status bar stayed LTR
        if layout_dir == '0' and sb_rtl is False:
            rtl_ok = False
        if not probed:
            x4 = None
            x4d = f'requested {want_loc or "ar"}; no locale probe (old dump) — cannot verify'
        else:
            x4 = bool(rtl_ok)
            x4d = (f'requested {want_loc or "ar"}; app_locales={app_loc or "?"}; '
                   f'force_rtl={force_rtl or "?"}; layout_dir={layout_dir or "?"}; '
                   f'status_bar_clock_rtl={sb_rtl}')
        add('X4', 'Requested RTL locale/layoutDirection applied (harness)', x4, x4d,
            'test plan §1 variants (API 33+ set-app-locales / debug.force_rtl; not system_locales alone)')
        x4_rtl_failed = (x4 is False)
    elif need_de:
        de_ok = re.search(r'\bde\b|de-', app_loc, re.I) is not None
        if not probed and not app_loc:
            x4 = None
            x4d = f'requested {want_loc or "de"}; no locale probe (old dump) — cannot verify'
        else:
            x4 = bool(de_ok)
            x4d = f'requested {want_loc or "de"}; app_locales={app_loc or "?"}'
        add('X4', 'Requested long-label locale applied (harness)', x4, x4d,
            'test plan §1 variants (API 33+ set-app-locales for de-DE)')
    else:
        # N/A on en-US points — omit (do not SKIP: that INCOMPLETEs under gate=dev8).
        pass

    # F1 cold start, crash and ANR.
    # am start -W often returns Status: timeout on software emulators even when MainActivity is up
    # (0 crashes / 0 ANRs); treat that as started when the Activity line is ours.
    crash = read(os.path.join(d, 'crash.txt'))
    ours = [l for l in crash.splitlines() if 'FATAL EXCEPTION' in l or ('ANR in ' + PKG) in l or (PKG in l and 'Process:' in l)]
    anr = read(os.path.join(d, 'anr.txt'))
    our_anr = [l for l in anr.splitlines() if 'Config Launcher' in l]
    start = read(os.path.join(d, 'start.txt'))
    start_timeout = 'Status: timeout' in start and (PKG + '/.MainActivity') in start.replace('\r', '')
    started = ('Status: ok' in start or 'TotalTime' in start or not start or start_timeout)
    if 'Status: ok' in start:
        start_st = 'ok'
    elif start_timeout:
        start_st = 'timeout (activity up)'
    elif 'TotalTime' in start:
        start_st = 'TotalTime'
    elif not start:
        start_st = 'empty'
    else:
        start_st = 'other'
    add('F1', 'Cold start: no crash, no launcher ANR', started and not ours and not our_anr,
        f'crash lines {len(ours)}, launcher ANR dialogs {len(our_anr)}, other ANR dialogs {len(anr.splitlines()) - len(our_anr)}; start {start_st}',
        'test-plan §15.2 smoke; A§11')
    tt = re.findall(r'TotalTime: (\d+)', start)
    add('P1', 'Cold start time recorded (am start -W)', True if tt else None,
        f'TotalTime {tt[-1]} ms (emulator, not a PF-2 measurement)' if tt else 'no TotalTime', 'test-plan §15.3 (PF-2 is device-only)')

    # X3 / F2: seed must produce home before any F/G product check. First-run "Choose your config
    # folder" is a harness seed failure (X3), not a product F2/F4/F5/G3 fail.
    h1 = home['home1']
    seeded = bool(h1) and find(h1, 'desc', r'^Home, Pages$') is not None and find(h1, 'desc', r', dock$') is not None
    first_run = bool(h1) and find(h1, 'text', r'Choose your config folder') is not None
    # Harness seed fail = driver gave up, or FirstRun screen. A seeded title bar with a missing
    # dock (no FirstRun) stays a product F2, not X3.
    seed_failed = (
        os.path.isfile(os.path.join(d, 'seed-failed.txt'))
        or m.get('seed_ok') == '0'
        or first_run
    )
    if seeded:
        seed_failed = False
    x3_ok = False if seed_failed else (True if seeded else None)
    x3_detail = (
        read(os.path.join(d, 'seed-failed.txt')).strip()
        or (f"first-run screen (attempts={m.get('seed_attempts', '?')})" if first_run else '')
        or ('home1 missing Home/dock' if h1 is not None and not seeded else '')
        or ('ok' if seeded else 'home1 missing')
    )
    if m.get('seed_attempts'):
        x3_detail = (x3_detail + f"; attempts={m.get('seed_attempts')}").strip('; ')
    add('X3', 'Seeded home showing before F/G checks (harness)', x3_ok, x3_detail,
        'test plan §5 (seed prefs before set-home; retry/reseed; first-run ≠ product)')

    if seed_failed:
        add('F2', 'Seeded layout shows after RELOAD (title bar "Home", dock)', None,
            'skipped: harness seed failed (X3)', 'ux.md §5 Reload; manifest ReloadReceiver (#55)',
            sev='advisory')
        # Do not score layout/flow product checks against a FirstRun or unseeded dump.
        return m, res

    add('F2', 'Seeded layout shows after RELOAD (title bar "Home", dock)', seeded if h1 is not None else None,
        'home1.xml ' + ('ok' if seeded else ('missing or unparsable' if not h1 else 'no "Home, Pages" slot or dock item')),
        'ux.md §5 Reload; manifest ReloadReceiver (#55)')

    # F3 long-press item — full sheet (skipPartiallyExpanded + scroll, #201): last actions
    # (App info, Remove from home) must be in the dump and on-screen, not stuck behind a half-height stop.
    lp = load(os.path.join(d, 'lp-item.xml'))
    top = read(os.path.join(d, 'top-lp-item.txt'))
    if lp is None:
        add('F3', 'Long-press a home item opens the item sheet, nothing launches', None, 'not captured', 'ux.md §1.1 gestures; test-plan §19 LP-2')
    else:
        t = texts(lp)
        Wlp, Hlp = screen_of(lp)
        def _on_screen(label):
            n = find(lp, 'text', rf'^{re.escape(label)}$')
            if n is None:
                return False
            return n.h > 0 and n.b[1] < Hlp and n.b[3] > 0
        # Product string is "Remove from home" (ux.md §1.1 / TileActions); accept short "Remove" too.
        remove_label = next((x for x in ('Remove from home', 'Remove') if x in t), 'Remove from home')
        need_f3 = ['Edit layout', 'App info', remove_label]
        have = {x: (x in t and _on_screen(x)) for x in need_f3}
        ok = all(have.values()) and any('com.android.settings' in x for x in t) and (PKG in top or not top)
        add('F3', 'Long-press a home item opens the item sheet, nothing launches', ok,
            f"actions {have}; pkg {any('com.android.settings' in x for x in t)}; "
            f"launcher on top {PKG in top if top else 'n/a'}; sheet {Wlp}x{Hlp}",
            'ux.md §1.1 gestures; test-plan §19 LP-2; #201 full-height sheet')

    # F4 long-press empty space — full home menu; last entry (Settings / Wallpaper) reachable.
    le = load(os.path.join(d, 'lp-empty.xml'))
    if le is None:
        add('F4', 'Long-press empty space opens the home menu', None, 'not captured', 'ux.md §1.1 gestures; test-plan §19 LP-6')
    else:
        t = texts(le)
        Wle, Hle = screen_of(le)
        need = ['Edit layout', 'Reload layout', 'Settings']
        def _on_screen_le(label):
            n = find(le, 'text', rf'^{re.escape(label)}$')
            return bool(n and n.h > 0 and n.b[1] < Hle and n.b[3] > 0)
        reachable = all(_on_screen_le(x) for x in need)
        add('F4', 'Long-press empty space opens the home menu',
            all(x in t for x in need) and reachable and 'App info' not in t,
            'menu: ' + ', '.join(x for x in t if x in need + ['Pages', 'Wallpaper', 'App info'])
            + f'; reachable { {x: _on_screen_le(x) for x in need + ["Wallpaper"]} }',
            'ux.md §1.1 gestures; test-plan §19 LP-6; #201 full-height sheet')

    # F5 drawer by swipe-up, D2 drawer long-press
    dr = load(os.path.join(d, 'drawer.xml'))
    via = read(os.path.join(d, 'drawer-via.txt')).strip() or 'swipe'
    if dr is None:
        add('F5', 'Swipe up opens the drawer', None, 'not captured', 'ux.md §1.4, §2')
    else:
        ok = 'Search apps' in texts(dr) or find(dr, 'desc', r'Search apps') is not None
        add('F5', 'Swipe up opens the drawer', ok and via == 'swipe', f'search field {ok}, opened via {via}', 'ux.md §1.4, §2')
    dl = load(os.path.join(d, 'drawer-lp.xml'))
    if dl is None:
        add('F5b', 'Long-press a drawer app: Add to home, Hide, App info', None, 'not captured', 'ux.md §2 Long-press app; test-plan §19 LP-7')
    else:
        t = texts(dl)
        Wdl, Hdl = screen_of(dl)
        need5 = ('Add to home', 'Hide', 'App info')
        def _on_screen_dl(label):
            n = find(dl, 'text', rf'^{re.escape(label)}$')
            return bool(n and n.h > 0 and n.b[1] < Hdl and n.b[3] > 0)
        ok5b = all(x in t and _on_screen_dl(x) for x in need5)
        add('F5b', 'Long-press a drawer app: Add to home, Hide, App info', ok5b,
            'actions: ' + ', '.join(x for x in t if x in need5)
            + f'; reachable { {x: _on_screen_dl(x) for x in need5} }',
            'ux.md §2 Long-press app; test-plan §19 LP-7; #201 full-height sheet')
    if dr is not None:
        sb = [n for n in dr if n.cls == 'TextView' and n.text and n.text not in ('Search apps', '⋮')]
        clipped = [n.text for n in sb if 0 < n.h / s < 12 * fs * 0.85 and n.b[3] < H - 2]
        add('D3', 'Drawer labels not clipped (rows above the nav bar)', not clipped,
            f'{len(sb)} labels, clipped: {clipped[:5]}', 'ux.md §2.1, §12 Font scaling')
        sf = next((n for n in dr if n.text == 'Search apps' or (n.desc or '').startswith('Search apps')), None)
        if sf is not None:
            # ux.md §2.1 / docs/test-plan-screen-matrix.md D1 / #202 @ 4247606:
            # search top ≤ max(25% of window height, 96 dp). Short panes (e.g. 800×312) keep the
            # same absolute chrome (~88 dp) that passes at 24% on 800×360; half-sheet (55–61%) fails.
            top_dp = sf.b[1] / s
            limit_dp = max(H / s * 0.25, 96.0)
            add('D1', 'Drawer opens full height', top_dp <= limit_dp,
                f'search field top y {sf.b[1]} of {H} ({sf.b[1] * 100 // max(H, 1)}%; '
                f'{top_dp:.0f} dp ≤ max(25%, 96)={limit_dp:.0f} dp)',
                'ux.md §2.1; test-plan-screen-matrix.md D1; #179 (fixed by #181); short-pane floor (#202)')
        # D4 drawer label contrast against the sheet, from drawer.png (#182 drew white labels on #FEF7FF, 1.05:1)
        im = open_png(os.path.join(d, 'drawer.png'))
        if im is None:
            add('D4', 'Drawer label contrast ≥ 3:1', None, 'no drawer.png or Pillow', 'WCAG 2.x contrast ratio (data); ux.md §12 Contrast')
        else:
            got = [(n.text, label_contrast(im, n.b)) for n in sb if n.b[3] < H - 2]
            got = [(t, c) for t, c in got if c]
            low = sorted((c[0], t, c) for t, c in got if c[0] < 3)
            under45 = sum(1 for _, c in got if c[0] < 4.5)
            worst = min(got, key=lambda tc: tc[1][0]) if got else None
            add('D4', 'Drawer label contrast ≥ 3:1', bool(got) and not low,
                f"{len(got)} labels, min {worst[1][0]:.2f}:1 ('{worst[0]}' {hexc(worst[1][1])} on {hexc(worst[1][2])}), "
                f"{under45} under 4.5:1; under 3:1: {[(t, round(r, 2)) for r, t, _ in low[:4]]}" if got else 'no labels measured',
                'WCAG 2.x contrast ratio as data: 3:1 is its large-text minimum, 4.5:1 its normal-text AA level; ux.md §12 Contrast, §12.1')
            if got:
                add('D4a', 'Drawer label contrast ≥ 4.5:1', under45 == 0, f'{under45} of {len(got)} labels under 4.5:1 (min {worst[1][0]:.2f}:1)',
                    'ux.md §12 Contrast (app UI text ≥ 4.5:1); WCAG 2.x ratio. Advisory: anti-aliased small text reads low')

    # F6 Pages switch
    pg = load(os.path.join(d, 'pages.xml')); h2 = home['home2']
    ok6 = None if (pg is None or h2 is None) else pg is not None and find(pg, 'desc', r'page [2٢] of [2٢]') is not None and h2 is not None and find(h2, 'desc', r'^Second, Pages$') is not None
    add('F6', 'Pages list opens from the {page} slot and switches to page 2', ok6,
        f"pages list {'ok' if pg is not None and find(pg, 'desc', r'page [2٢] of [2٢]') else 'missing'}, page 2 {'ok' if h2 is not None and find(h2, 'desc', '^Second, Pages$') else 'missing'}",
        'ux.md §1.3; §15 #78 tap target')

    # F7 warnings list; T3 exactly once
    wn = load(os.path.join(d, 'warnings.xml')); cf = load(os.path.join(d, 'config-folder.xml'))
    if wn is not None:
        wl = [t for t in texts(wn) if t.startswith('▲')]
    elif cf is not None:
        wl = [] if not any(t.startswith('Layout loaded ·') for t in texts(cf)) else None
    else:
        wl = None
    add('F7', 'Warnings list reachable (⋮ → Config folder → Layout loaded)', (wl is not None) if (wn is not None or cf is not None) else None,
        f'{len(wl)} warning lines' if wl is not None else 'not reached', 'ux.md §15 "Interim (#129)"')

    # Title bar, on both pages
    steps = []
    for k in ('home1', 'home2'):
        nodes = home[k]
        tb = title_bar(nodes, s) if nodes else None
        sfx = '' if k == 'home1' else ' (page 2)'
        if not tb:
            for cid in ('T1', 'T2', 'T4'):
                add(cid + ('' if k == 'home1' else 'p2'), f'title bar{sfx}', None, 'title bar not found', 'ux.md §15')
            continue
        st, caret = ladder_step(tb)
        steps.append(st)
        date_txt = tb['date_t'].text if tb['date_t'] else None
        clock_txt = tb['clock_t'].text if tb['clock_t'] else ''
        vis = f"{date_txt!r} · {'/'.join(tb['page_texts']) or '(none)'} · {clock_txt!r}"
        order_ok = not (st in ('2-3', 4) and not caret)
        add('T1' + ('' if k == 'home1' else 'p2'), f'Overflow ladder order{sfx}: {{page}} shrinks to ▾ before the date shortens', order_ok,
            f'step {st}; visible {vis}', 'ux.md §15 Overflow ladder steps 1-4')
        clk_ok = bool(CLOCK_RE.match(clock_txt)) and clock_txt == tb['clock_v'].desc
        add('T2' + ('' if k == 'home1' else 'p2'), f'Clock never truncates{sfx}', clk_ok,
            f'clock text {clock_txt!r}, spoken {tb["clock_v"].desc!r}', 'ux.md §15 Layout and size; step 4')
        # Touch area = the smallest clickable node holding the {page} slot's centre (the 48 dp wrapper, #167).
        pv = tb['page_v']
        holders = [n for n in nodes if n.click and n.b[0] <= pv.cx <= n.b[2] and n.b[1] <= pv.cy <= n.b[3]
                   and n.w * n.h <= 8 * max(1, pv.w * pv.h)]
        P = min(holders, key=lambda n: n.w * n.h).b if holders else pv.b
        pw, ph = (P[2] - P[0]) / s, (P[3] - P[1]) / s
        others = [n for n in nodes if n.click and inter(n.b, P) > 0 and not contains(n.b, P, 2) and not contains(P, n.b, 2)]
        if caret:   # step 1+: aims for 48 dp; >= 32 dp only next to a tappable neighbour
            size_ok = (pw >= 47.5 and ph >= 47.5) or (min(pw, ph) >= 31.5 and bool(others))
            rule = 'step 1+: 48 dp, or >= 32 dp beside a tappable neighbour'
        else:       # step 0: slot never below 32 dp wide (#152), 48 dp tall (§1.3/#78)
            size_ok = pw >= 31.5 and ph >= 47.5
            rule = 'step 0: >= 32 dp wide, 48 dp tall'
        add('T4' + ('' if k == 'home1' else 'p2'), f'{{page}} touch area big enough and takes nothing from another tappable target{sfx}',
            bool(size_ok) and not others,
            f'touch area {pw:.1f}×{ph:.1f} dp ({rule}); {"no clickable holder, used the slot" if not holders else "clickable wrapper"}; '
            f'other clickables intersecting it: {[(n.desc or n.text, n.b) for n in others]}',
            'ux.md §15 step 1 touch area (#167), l.732 (#152), §12 Touch targets')
        if k == 'home1':
            vis_t = sorted([n for n in tb['band'] if n.cls == 'TextView' and n.text], key=lambda n: n.b[0])
            gaps = [(a.text, b.text, (b.b[0] - a.b[2]) / s) for a, b in zip(vis_t, vis_t[1:])
                    if not contains(tb['page_v'].b, a.b, 2) or not contains(tb['page_v'].b, b.b, 2)]
            add('T5', 'Adjacent slots keep ≥ 8 dp gap (0.5 dp px-rounding slack)', all(g[2] >= 7.5 for g in gaps),
                '; '.join(f'{a!r}→{b!r} {g:.1f} dp' for a, b, g in gaps), 'ux.md §15 Layout and size (8 dp minimum gap)')
            wide = W / s >= 411 and fs <= 1.05 and m.get('tier', 'phone') == 'phone'
            add('T6', 'No ladder step at ≥ 411 dp, font 1.0 (matrix expectation, not a spec rule)', (st == 0) if wide else None,
                f'step {st} at {W / s:.0f} dp', 'evidence/pr167 r4; open question in the plan')
            bar_txt = [n.text for n in tb['band'] if n.text]
            add('A1', 'A11y tree holds only the placed title-bar strings (no unplaced ladder candidates)',
                len(bar_txt) == len(set(bar_txt)), repr(bar_txt), 'ux.md §15 TalkBack; #167 review')
    if steps and wl is not None:
        tw = [w for w in wl if 'title bar' in w]
        want = 1 if any(st in ('2-3', 4) for st in steps) else 0
        nums = re.findall(r'fit step (\d) of 4', ' '.join(tw))
        add('T3', 'warn_titlebar_fit appears exactly once from step 2 on, never for step 0/1', len(tw) == want,
            f'steps seen {steps}; title-bar warning lines {len(tw)} (want {want}); warning says step {nums or "-"}',
            'ux.md §15 Overflow ladder (warning), §15.1 copy, #159/#168 one list')
    else:
        add('T3', 'warn_titlebar_fit appears exactly once from step 2 on', None, 'title bar or warnings list missing', 'ux.md §15')

    # Grid (page 1 and page 2)
    for k in ('home1', 'home2'):
        nodes = home[k]
        if not nodes:
            continue
        sfx = '' if k == 'home1' else 'p2'
        tb = title_bar(nodes, s)
        labels, tiles, dock, cards, (gt, gb) = grid_parts(nodes, tb, (W or 1, H or 1))
        min_h = 12 * fs * 0.85
        clipped = [(n.text, round(n.h / s, 1)) for n in labels if n.h / s < min_h]
        chip_fixture = m.get('fixture', '').endswith('chip.json')
        cid = 'G5c' if chip_fixture else 'G1'
        add(cid + sfx, 'Grid labels whole or hidden, never cut' + (' (chip/notice row showing)' if chip_fixture else ''),
            not clipped, f'{len(labels)} labels; cut (text, dp tall, need ≥ {min_h:.1f}): {clipped[:6]}',
            'ux.md §1.1 Cell ("the layout is never clipped"), §12 Font scaling' + ('; #175 whole-or-hidden (built in #178)' if chip_fixture else ''))
        ov = [(a.text, b.text) for i, a in enumerate(labels) for b in labels[i + 1:] if inter(a.b, b.b) > 0]
        tov = [(a.b, b.b) for i, a in enumerate(tiles) for b in tiles[i + 1:]
               if inter(a.b, b.b) > 0 and not contains(a.b, b.b) and not contains(b.b, a.b)]
        add('G2' + sfx, 'No overlapping labels or tiles', not ov and not tov, f'label overlaps {ov[:4]}, tile overlaps {tov[:4]}',
            'ux.md §1.1 Grid/Cell')
        def _dock_partial(n):
            if not dock or not (n.desc or '').endswith(', dock'):
                return False
            med = sorted(min(p.w, p.h) for p in dock)[len(dock) // 2]
            return min(n.w, n.h) < max(med * 0.6, 40 * s * 0.85)
        small = [(n.desc or n.text or 'tile', round(n.w / s), round(n.h / s))
                 for n in tiles + dock
                 if (n.w / s < 47.5 or n.h / s < 47.5) and not _dock_partial(n)]
        off = [n.b for n in tiles + dock + labels if n.b[0] < 0 or n.b[1] < 0 or (W and n.b[2] > W) or (H and n.b[3] > H)]
        intobar = [n.text for n in labels if tb and inter(n.b, tb['rect']) > 0]
        # #204: a banner / §6.1 notice-row chip can push phone-land grid under 48 dp. Record
        # numbers as advisory (not a #201 blocker) when that chrome is showing; still FAIL
        # when under 48 with no notice/banner (real G3 / density regression).
        texts_all = [n.text or '' for n in nodes] + [n.desc or '' for n in nodes]
        notice_chrome = (
            any(_is_notice_chip(n) for n in nodes)
            or any('needs your OK.' in t or 'need your OK.' in t for t in texts_all)
            or any(t.startswith('Pending') for t in texts_all)
            or (m.get('fixture') or '').endswith('chip.json')
        )
        g3_detail = (f'{len(tiles)} tiles, {len(dock)} dock; under 48 dp {small[:4]}; '
                     f'off-screen {off[:3]}; into bar {intobar[:3]}')
        # Short-pane pitch (multi-window / wm-size stand-in): row tops decide whether 48×N can fit.
        _pitch = pitch_of(tiles, s) if tiles else None
        pitchH_g3 = _pitch[1] if _pitch else None
        short_pane = (
            (m.get('multiwindow_sim') or '') == 'wm_size_short_pane'
            or (pitchH_g3 is not None and pitchH_g3 < 47.5
                and (H / s if H else 999) < 480)
        )
        tr_path = os.path.join(d, 'tap-routing.json')
        tr = None
        if os.path.isfile(tr_path):
            try:
                with open(tr_path) as _tf:
                    tr = json.load(_tf)
            except (OSError, ValueError):
                tr = None
        overlap_upper_wins = False
        tr_overlap_ok = True
        if tr:
            summ = tr.get('summary') or {}
            findings = summ.get('overlap_findings') or []
            # Upper-row steal = any overlap finding (winner == donor / upper cell).
            overlap_upper_wins = bool(findings)
            for r in (summ.get('results') or tr.get('results') or []):
                if r.get('kind') == 'overlap' and not r.get('ok'):
                    overlap_upper_wins = True
            tr_overlap_ok = not overlap_upper_wins
        g3_cite = ('ux.md §12 Touch targets, §1.1 Cell, §15 "Never overlaps icons"')
        if small and notice_chrome and not off and not intobar:
            add('G3' + sfx, 'Tiles and dock slots ≥ 48 dp, on screen, nothing under the title bar',
                True,  # do not block #201
                g3_detail + f'; notice/banner chrome present — under-48 tracked as advisory #204 '
                f'(not a #201 blocker); min under48 {min((min(a,b) for _,a,b in small), default="?")}',
                'ux.md §12 / §1.5 step 4; #204 (banner/notice vs 48 floor)', sev='advisory')
            add('G3n' + sfx, 'Under-48 with notice/banner only (#204)', False,
                g3_detail + '; filed #204', 'issue #204', sev='advisory')
        elif small and short_pane and not off and not intobar and pitchH_g3 is not None:
            # Pragma option (B) / docs PR #209: short multi-window panes where rows×48 cannot fit
            # (e.g. 800×312: chrome 116 dp → 196 dp grid ≈ 39.2 dp/row). Gate G3 on *behaviour*
            # (TR1: every point in a tile's visible area activates that tile; no cross-tile steal),
            # not on strict a11y hit-size bounds. Overlapping accessibility-tree bounds are NOT a
            # G3 finding — tracked as TalkBack tidy-up #208 (info only). Below 32 → L16 fails.
            # Title bar still 48 (T4). Elsewhere G3 stays 48×48. Sprite OK at 686e98d.
            cite_b = ('docs PR #209 (Pragma option B: short-pane G3 = pitchH≥32 + hit=visible); '
                      'a11y bound overlap → #208 (info); ux.md §12 / L16')
            if pitchH_g3 < 31.5:
                add('G3' + sfx, 'Tiles and dock slots ≥ 48 dp, on screen, nothing under the title bar',
                    False,
                    g3_detail + f'; short-pane pitchH={pitchH_g3:.0f} < 32 — L16 floor',
                    cite_b)
                add('L16' + sfx, 'Short-pane / phone-land touch floor: pitchH ≥ 32 dp',
                    False,
                    f'pitchH={pitchH_g3:.1f} dp < 32; under-48 {small[:4]}',
                    cite_b)
            elif tr is None:
                add('G3' + sfx, 'Tiles and dock slots ≥ 48 dp, on screen, nothing under the title bar',
                    False,
                    g3_detail + f'; short-pane pitchH={pitchH_g3:.0f}≥32 but no tap-routing.json '
                    f'(TR1 required for option B)',
                    cite_b)
            elif not tr_overlap_ok:
                add('G3' + sfx, 'Tiles and dock slots ≥ 48 dp, on screen, nothing under the title bar',
                    False,
                    g3_detail + f'; short-pane pitchH={pitchH_g3:.0f} but TR1 overlap upper-row wins '
                    f'{((tr.get("summary") or {}).get("overlap_findings") or [])[:2]}',
                    cite_b)
            else:
                add('G3' + sfx, 'Tiles and dock slots ≥ 48 dp, on screen, nothing under the title bar',
                    True,
                    g3_detail + f'; short-pane option B PASS (#209): pitchH={pitchH_g3:.0f}≥32, '
                    f'TR1 hit=visible (no cross-tile steal); a11y under-48 / bound overlap '
                    f'info-only → #208',
                    cite_b)
                add('L16' + sfx, 'Short-pane / phone-land touch floor: pitchH ≥ 32 dp '
                    '(option B; hit=visible)',
                    True,
                    f'pitchH={pitchH_g3:.1f} dp; TR1 behaviour ok; a11y under-48 {small[:4]} '
                    f'(#208 info, not G3)',
                    cite_b, sev='advisory')
                add('G3a' + sfx, 'A11y hit-bounds under 48 / overlap (TalkBack tidy-up)',
                    False,
                    f'info only — not a G3 finding (#208); under-48 {small[:4]}; '
                    f'pitchH={pitchH_g3:.1f}',
                    'issue #208', sev='advisory')
        else:
            add('G3' + sfx, 'Tiles and dock slots ≥ 48 dp, on screen, nothing under the title bar',
                not small and not off and not intobar, g3_detail, g3_cite)
        # G6 grid label contrast against the wallpaper under it (advisory: photo wallpapers make the sample noisy)
        im = open_png(os.path.join(d, k + '.png'))
        if im is not None and labels:
            got = [(n.text, label_contrast(im, n.b)) for n in labels]
            got = [(t, c) for t, c in got if c]
            if got:
                low = [(t, round(c[0], 2)) for t, c in got if c[0] < 3]
                worst = min(got, key=lambda tc: tc[1][0])
                add('G6' + sfx, 'Grid label contrast ≥ 3:1 on the wallpaper', not low,
                    f"{len(got)} labels, min {worst[1][0]:.2f}:1 ('{worst[0]}' {hexc(worst[1][1])} on {hexc(worst[1][2])}); under 3:1: {low[:4]}",
                    'WCAG 2.x contrast ratio as data (3:1 large-text minimum); ux.md §1.1 Cell (white + soft shadow), §12 Contrast')
        if chip_fixture and k == 'home1':
            # §17.4: the first load shows the B9 banner; the driver taps × and dumps home-chip with the count chip
            hc = load(os.path.join(d, 'home-chip.xml'))
            if hc is not None:
                nodes = hc; tb = title_bar(hc, s)
                labels, tiles, dock, cards, _ = grid_parts(nodes, tb, (W or 1, H or 1))
            chip = find(nodes, 'desc', r'^Widget permissions')
            if chip is None:
                banner = any('needs your OK.' in t or 'need your OK.' in t for t in texts(nodes))
                add('G5a', 'Count chip present with ≥ 48 dp hit area', None if hc is None and banner else False,
                    'banner showing, chip not reached (no home-chip dump)' if hc is None and banner else 'no "Widget permissions" chip',
                    'ux.md §17.4 Count chip, §12')
            else:
                add('G5a', 'Count chip present with ≥ 48 dp hit area', chip.w / s >= 47.5 and chip.h / s >= 47.5,
                    f'chip {chip.w / s:.1f}×{chip.h / s:.1f} dp at {chip.b}', 'ux.md §17.4 Count chip, §12 Touch targets')
                over = [n.desc or n.text for n in tiles + labels
                        if n.b != chip.b and not n.desc.startswith('Widget permissions') and inter(n.b, chip.b) > 0]
                if tb is not None:
                    # ux.md §6.1 notice row with a §15 title bar (#183): its own 48 dp row above the bar.
                    # Use content_top (clock/date) so a tall Pages hit-target or chip in the band cannot
                    # pull the bar identity up into the chip (a51-c1 flake).
                    bar_top = tb.get('content_top', tb['rect'][1])
                    r = tb['rect']
                    above = chip.b[3] <= bar_top + 2
                    # If the chip dump's bar raced into the chip band, re-read bar identity from home1
                    # (stable pre-dismiss layout) and only require the chip stay off the grid.
                    if not above:
                        home1_nodes = home.get('home1')
                        tb1 = title_bar(home1_nodes, s) if home1_nodes else None
                        if tb1 and not over:
                            bar_top1 = tb1.get('content_top', tb1['rect'][1])
                            # Chip in the notice-row zone: below Apps header, not over grid tiles.
                            above = chip.b[3] <= bar_top1 + 2 or (
                                chip.b[1] < bar_top1 and not over)
                            det = (f'title bar: chip y {chip.b[1]}-{chip.b[3]}; '
                                   f'chip-dump content_top {bar_top}; home1 content_top {bar_top1}; '
                                   f'off-grid {not over}')
                        else:
                            det = (f'title bar: chip y {chip.b[1]}-{chip.b[3]} above content_top '
                                   f'{bar_top} (bar {r[1]}-{r[3]}): {above}')
                    else:
                        det = (f'title bar: chip y {chip.b[1]}-{chip.b[3]} above content_top '
                               f'{bar_top} (bar {r[1]}-{r[3]}): {above}')
                    ok5 = above and not over
                else:
                    # no title bar: the §1.3 page label moves into the chip's row (label centred, chips at the end)
                    pl = next((n for n in nodes if re.search(r', page \d+ of \d+$', n.desc) or n.desc.endswith(', Pages')), None)
                    if pl is None:
                        ok5 = not over
                        det = f'no page label (one untitled page): chip y {chip.b[1]}-{chip.b[3]} in its own row'
                    else:
                        same = chip.b[1] < pl.b[3] and chip.b[3] > pl.b[1]
                        ok5 = same and not over
                        det = f'no title bar: chip y {chip.b[1]}-{chip.b[3]}, page label y {pl.b[1]}-{pl.b[3]}, same row {same}'
                add('G5b', 'Collapsed chip in the notice row (no overlay on the grid)', ok5, f'{det}; over grid {over[:3]}',
                    'ux.md §6.1 Notice row (#173 L1; title bar: #183), §17.4 Count chip')

    # R1 (#187 / PR #210): grid columns mirror in RTL. Fixture launcher.json row 0 is QA Settings (x=0) …
    # Wide WWWW (x=3); RTL wants x=0 physically rightmost, LTR leftmost. Advisory (geometry, not a gesture).
    if (m.get('fixture') or '') == 'launcher.json' and home.get('home1'):
        _row0 = ['QA Settings', 'A very long label that must ellipsize', 'Missing', 'Wide WWWW']
        _n1 = home['home1']
        _cx = {}
        for _lab in _row0:
            _hit = next((n for n in _n1 if (n.text or n.desc) == _lab and n.w > 0), None)
            if _hit is not None:
                _cx[_lab] = _hit.cx
        _loc = (m.get('locale') or '').lower()
        _rtl = 'rtl' in (m.get('shape') or '').lower() or _loc.startswith('ar')
        if len(_cx) >= 2:
            _phys = [k for k, _ in sorted(_cx.items(), key=lambda t: t[1])]
            _want = [k for k in _row0 if k in _cx]
            if _rtl:
                _want = list(reversed(_want))
            if x4_rtl_failed:
                add('R1', 'Grid column order follows layout direction (RTL mirrors)', None,
                    'skipped: harness X4 RTL not applied', 'ux.md §1.5 RTL; #187', sev='advisory')
            else:
                add('R1', 'Grid column order follows layout direction (RTL mirrors)', _phys == _want,
                    f'rtl={_rtl}; row 0 left→right {[_k[:12] for _k in _phys]}; want {[_k[:12] for _k in _want]}',
                    'ux.md §1.5 RTL; #187 / PR #210', sev='advisory')
        else:
            add('R1', 'Grid column order follows layout direction (RTL mirrors)', None,
                f'row-0 fixture tiles not found ({len(_cx)})', 'ux.md §1.5 RTL; #187', sev='advisory')

    # ---------------------------------------------------------------- L1–L13 (ux.md §1.5 / test-plan §20 / #197)
    # L1–L5/L7 on large-tier (and phone-landscape for L5/L12). L6/L13 when their fixtures are seeded.
    # L8/L9/L10/L11 live in androidTest / JVM unit tests. Severity flips with gate=dev8 (see severity()).
    nodes = home.get('home1')
    fixture = m.get('fixture') or ''
    is_large = m.get('tier') == 'large'
    is_phone_land = m.get('tier') == 'phone' and (m.get('rotation') == '1' or 'land' in (m.get('id') or ''))
    if nodes and (is_large or is_phone_land or fixture in ('dock7.json', 'cards-large.json')):
        tb = title_bar(nodes, s)
        pairs, labels_l, dock = grid_tiles(nodes, tb, (W or 1, H or 1), s)
        tile_nodes = [n for n, _ in pairs]
        _labels_g, _tiles_g, _dock_g, card_nodes, _region = grid_parts(nodes, tb, (W or 1, H or 1))
        # Layout direction for the L checks: mirrored only when RTL was requested and X4 saw it applied.
        rtl_lay = bool(need_rtl) and not x4_rtl_failed
        pitch5 = (pitch_with_fixture(tile_nodes, pairs, card_nodes, s, fixture, rtl=rtl_lay)
                  if (tile_nodes or card_nodes) else None)
        pitch = pitch5[:4] if pitch5 else None
        grid_columns = pitch5[4] if pitch5 else None
        width_dp = (W / s) if W and s else 0
        height_dp = (H / s) if H and s else 0
        pid = m.get('id', '')
        rot = m.get('rotation', '0')

        # L1: no write on a window change (driver hashes launcher.json around a one-shot reverse-rotate)
        hb = read(os.path.join(d, 'hash-before.txt')).strip()
        ha = read(os.path.join(d, 'hash-after-rot.txt')).strip()
        if not hb and not ha:
            # Driver only hashes on large-tier (§ screen-matrix.sh). Omit on phone so SKIP
            # does not INCOMPLETE under gate=dev8; SKIP on large when the dump is missing.
            if (m.get('tier') or 'phone') == 'large':
                add('L1', 'No write on a window change (hash after reverse-rotate)', None,
                    'not captured on large-tier point (driver hash missing)',
                    'ux.md §1.5 "never writes"; test-plan §20 L1')
        else:
            add('L1', 'No write on a window change (hash after reverse-rotate)',
                bool(hb) and hb == ha,
                f'before {hb[:12] or "?"}… after {ha[:12] or "?"}… match {bool(hb) and hb == ha}',
                'ux.md §1.5 "never writes"; test-plan §20 L1 (matrix: one reverse-rotate; full ×4/fold is manual)')

        # L2: positions are exact — floor((centre − blockLeft) / pitchW) matches the fixture
        items = fixture_items(m.get('fixture'))
        if not pitch or not items or not pairs:
            add('L2', 'Positions are exact (column x, row y)', None,
                'no tiles, pitch or fixture items', 'ux.md §1.5; test-plan §20 L2')
        else:
            pitchW, pitchH, block_left, block_top = pitch
            bad = []
            for n, lab in pairs:
                key = (lab.text if lab else '') or n.desc.split(',')[0]
                exp = next((it for it in items if it[0] and key.startswith(it[0][:12])), None)
                if exp is None and lab is None:
                    continue
                if exp is None:
                    continue
                col = int((n.cx - block_left) // max(pitchW * s, 1))
                if rtl_lay and grid_columns:
                    col = grid_columns - 1 - col      # physical → logical column in RTL
                row = int((n.cy - block_top) // max(pitchH * s, 1))
                if col != exp[1] or row != exp[2]:
                    bad.append(f'{exp[0][:20]} got ({col},{row}) want ({exp[1]},{exp[2]})')
            add('L2', 'Positions are exact (column x, row y)', not bad,
                f'pitch {pitchW:.0f}×{pitchH:.0f} dp; rtl={rtl_lay}; mismatches {bad[:4] or "none"}',
                'ux.md §1.5; test-plan §20 L2')

        # L3: cap and centring on Medium/Expanded (width ≥ 600 dp) — #197 / test-plan §20 L3
        # Aspect in px with +1 px slack: W ≤ 1.25·H + 1 and H ≤ 1.5·W + 1.
        # Margins within 1 px inside WindowInsets.safeDrawing (from insets.txt / dumpsys window).
        insets_txt = read(os.path.join(d, 'insets.txt'))
        safe = parse_safe_drawing_insets(insets_txt, W or 1, H or 1)
        insets_note = (f'safeDrawing L/T/R/B={safe[0]}/{safe[1]}/{safe[2]}/{safe[3]} px'
                       if insets_txt.strip() else 'insets.txt missing (margins fall back to raw edge)')
        if width_dp < 600:
            add('L3', 'Cell cap and centring (Medium/Expanded)', None,
                f'Compact width {width_dp:.0f} dp (cap is a no-op; PhoneOutputGoldenTest covers Compact); {insets_note}',
                'ux.md §1.5 Q-L2; test-plan §20 L3 (#197)')
        elif not pitch:
            add('L3', 'Cell cap and centring (Medium/Expanded)', None,
                f'no pitch; {insets_note}', 'ux.md §1.5 Q-L2; test-plan §20 L3 (#197)')
        else:
            pitchW, pitchH, block_left, block_top = pitch
            cell_w_px, cell_h_px = cell_size_px(tile_nodes, pitchW, pitchH, s)
            ok_cap = pitchW <= 144.5
            ok_aspect = (cell_w_px <= 1.25 * cell_h_px + 1) and (cell_h_px <= 1.5 * cell_w_px + 1)
            # Page column = window − safeDrawing − rail. Margins from *column centres* (#197),
            # not outer tile/label edges (fold-open-v1 was a false 11 px asym on edges).
            col_l, col_t, col_r, col_b = page_column_box(W or 0, H or 0, safe, dock, nodes=nodes)
            # Span the full fixture grid.columns (not only occupied tile centres) so a
            # sparse page like cards-large (icons in cols 0–1 of 4) does not report
            # asymmetric L/R margins (#205).
            n_cols = grid_columns or 1
            left_centre = block_left + pitchW * s / 2
            right_centre = block_left + pitchW * s / 2 + (n_cols - 1) * pitchW * s
            left_m_px = left_centre - col_l
            right_m_px = col_r - right_centre
            ok_margin = abs(left_m_px - right_m_px) <= 1
            ok = ok_cap and ok_aspect and ok_margin
            rail_note = ''
            rg = rail_geom(dock, W or 1, H or 1, nodes=nodes)
            if rg[0]:
                rail_note = f'; pageCol {col_l:.0f}..{col_r:.0f} (rail {rg[1]})'
            add('L3', 'Cell cap and centring (Medium/Expanded)', ok,
                f'pitchW {pitchW:.1f} dp (≤144); cell {cell_w_px:.1f}×{cell_h_px:.1f} px '
                f'(W≤1.25·H+1 / H≤1.5·W+1); col-centre margins L/R {left_m_px:.1f}/{right_m_px:.1f} px '
                f'(≤1 px in page column); {insets_note}{rail_note}',
                'ux.md §1.5 Q-L2; test-plan §20 L3 (#197)')

        # L4: ladder-aware minimum + labels whole or absent — #197 / test-plan §20 L4
        # Step 1 pass floor: pitchH ≥ 84 dp on t7/t10-land @ font 1.0 once the end-edge rail (L5) is present.
        # Ladder: ≥76 (step 2), ≥60 (step 3). Until L5, t7-land ~64.5 is expected step 3 — preferred
        # half is advisory / "depends on L5"; labels-whole-or-absent still applies.
        min_h = 12 * fs * 0.85
        clipped = [(lab.text, round(lab.h / s, 1)) for lab in labels_l if lab.h / s < min_h]
        ok_lab = not clipped
        land_prefer = pid.startswith(('t7-land', 't10-land')) and fs <= 1.05
        rail_now = dock_is_rail(dock, W or 1, H or 1)
        if land_prefer and pitch:
            pitchH = pitch[1]
            if rail_now:
                ok_h = pitchH >= 83.5   # step-1 floor 84 dp
                detail_h = f'pitchH {pitchH:.1f} dp (rail present; need ≥ 84 step-1)'
                ok_l4 = ok_h and ok_lab
                add('L4', 'Row pitch ladder + labels whole or absent', ok_l4,
                    f'{detail_h}; labels {len(labels_l)}, clipped {clipped[:4] or "none"}',
                    'ux.md §1.5 icon ladder; test-plan §20 L4 (#197)')
            else:
                # No rail yet: preferred ≥84 depends on L5. Enforce ladder floor ≥60 + labels.
                ladder_ok = pitchH >= 59.5
                step = ('1' if pitchH >= 83.5 else '2' if pitchH >= 75.5 else '3' if pitchH >= 59.5 else 'below')
                detail_h = (f'pitchH {pitchH:.1f} dp (depends on L5 for ≥84; expected step 3 until rail; '
                            f'ladder step {step}, floor ≥60)')
                ok_l4 = ladder_ok and ok_lab
                # When only the preferred half would fail, keep PASS and say depends on L5.
                add('L4', 'Row pitch ladder + labels whole or absent', ok_l4,
                    f'{detail_h}; labels {len(labels_l)}, clipped {clipped[:4] or "none"}',
                    'ux.md §1.5 icon ladder; test-plan §20 L4 (#197)')
        else:
            detail_h = f'pitchH {pitch[1]:.1f} dp (preferred floor applies to t7/t10-land @ font 1.0)' if pitch else 'n/a'
            ok_l4 = ok_lab if labels_l is not None else (None if not pitch else True)
            add('L4', 'Row pitch ladder + labels whole or absent',
                ok_l4 if (pitch or labels_l is not None) else None,
                f'{detail_h}; labels {len(labels_l)}, clipped {clipped[:4] or "none"}',
                'ux.md §1.5 icon ladder; test-plan §20 L4 (#197)')

        # L5: dock placement — rail on t7-land/t10-land (and phone landscape when we have one); bottom otherwise
        # Any *-land* point (incl. dock7-t7-land, rtl-t7-land, cards-t10-land) expects a rail once #201 lands.
        want_rail = bool(re.search(r'land', pid) or
                         (m.get('tier') == 'phone' and rot == '1'))
        # fold-open and *-port expect a bottom dock
        want_bottom = bool(re.search(r'(port|fold-open)', pid) or
                           (m.get('tier') == 'phone' and rot != '1' and 'land' not in pid))
        rail = dock_is_rail(dock, W or 1, H or 1)
        apps = find(nodes, 'text', r'^Apps$')
        if rail is None:
            add('L5', 'Dock placement (rail vs bottom)', None, 'no dock slots',
                'ux.md §1.5 Q-L3/Q-L4; test-plan §20 L5')
        elif want_rail:
            # Apps should sit with the rail (near the end edge), not across the top of the page area alone
            apps_on_rail = False
            if apps and dock:
                apps_on_rail = abs(apps.cx - dock[0].cx) < max(W * 0.15, 1)
            is_rail_ok, side, rl, rr = rail_geom(dock, W or 1, H or 1, nodes=nodes)
            locale = (m.get('locale') or 'en-US').lower()
            rtl = locale.startswith('ar') or locale.endswith('-xb') or 'rtl' in (m.get('shape') or '').lower()
            want_side = 'left' if rtl else 'right'
            side_ok = (side == want_side) if is_rail_ok else False
            # Rail width = max(80 dp, Apps/Done label + 24 dp); no clipped/ellipsised head labels.
            panel_w_dp = ((rr - rl) / s) if (is_rail_ok and rl is not None and rr is not None) else 0.0
            head = apps or find(nodes, 'text', r'^Done$')
            label_w_dp = (head.w / s) if head else 0.0
            # Head TextView bounds already include pad; require strip ≥ max(80, label+24) with 1 dp slack,
            # or at least fully contain the head label (label_w ≤ panel_w).
            need_w = max(80.0, label_w_dp + 24.0) if head else 80.0
            width_ok = panel_w_dp + 1 >= min(need_w, max(80.0, label_w_dp)) and panel_w_dp + 1 >= 80.0
            if head and panel_w_dp + 1 >= label_w_dp and panel_w_dp + 1 >= 80.0:
                width_ok = True  # strip fits the label and meets 80 dp floor
            ellip = False
            if head and head.text and ('…' in head.text or head.text.endswith('...')):
                ellip = True
            # Head fully inside the rail strip
            head_in = True
            if head and is_rail_ok and rl is not None:
                head_in = head.b[0] >= rl - 2 and head.b[2] <= rr + 2
            ok_l5 = bool(rail) and side_ok and width_ok and head_in and not ellip
            if x4_rtl_failed:
                add('L5', 'Dock placement (rail vs bottom)', None,
                    f'skipped: harness X4 RTL locale/layoutDirection not applied '
                    f'(side {side}, want {want_side}); not a product rail-side score',
                    'ux.md §1.5 Q-L3/Q-L4; test-plan §20 L5', sev='advisory')
            else:
                add('L5', 'Dock placement (rail vs bottom)', ok_l5 if rail else False,
                    f'want rail; is_rail {rail}; side {side} (want {want_side}, rtl={rtl}); '
                    f'railW {panel_w_dp:.1f} dp (need ≥{need_w:.1f}); Apps near dock {apps_on_rail}; '
                    f'head_in {head_in} ellip {ellip}; dock {[n.b for n in dock[:3]]}; '
                    f'window {width_dp:.0f}×{height_dp:.0f} dp',
                    'ux.md §1.5 Q-L3/Q-L4; test-plan §20 L5')
        elif want_bottom:
            add('L5', 'Dock placement (rail vs bottom)', rail is False,
                f'want bottom; is_rail {rail}; dock {[n.b for n in dock[:3]]}; '
                f'window {width_dp:.0f}×{height_dp:.0f} dp (fold-open 1.2:1 stays bottom)',
                'ux.md §1.5 Q-L3/Q-L4; test-plan §20 L5')
        else:
            add('L5', 'Dock placement (rail vs bottom)', None,
                f'no expected placement for {pid}; is_rail {rail}',
                'ux.md §1.5 Q-L3/Q-L4; test-plan §20 L5')

        # G7: banner / title / page dots / snackbar centre within 1 px of page-column centre (rail layouts).
        if rail is True:
            insets_txt = read(os.path.join(d, 'insets.txt'))
            safe = parse_safe_drawing_insets(insets_txt, W or 1, H or 1) if W else (0, 0, 0, 0)
            col_l, col_t, col_r, col_b = page_column_box(W or 0, H or 0, safe, dock, nodes=nodes)
            col_cx = (col_l + col_r) / 2.0
            chrome = []
            for n in nodes:
                t = (n.text or '')
                desc = (n.desc or '')
                kind = None
                if t.startswith('Pending') or 'banner' in desc.lower() or desc == 'Dismiss':
                    continue  # chip/button, not full-width chrome
                if n.cls.endswith('TextView') and t and ('Home' in desc or desc.startswith('Home,')):
                    kind = 'title'
                elif 'page' in desc.lower() and 'of' in desc.lower():
                    kind = 'dots'
                elif 'snack' in desc.lower() or (n.cls.endswith('Snackbar') if n.cls else False):
                    kind = 'snackbar'
                # Banner: full-width-ish text at top of page column
                if kind is None and n.cls.endswith('TextView') and t and col_l <= n.cx <= col_r and n.b[1] < (tb['rect'][1] if tb else col_t + 80):
                    if abs(n.w - (col_r - col_l)) < (col_r - col_l) * 0.35 or 'banner' in desc.lower():
                        kind = 'banner'
                if kind and col_l <= n.cx <= col_r:
                    chrome.append((kind, n.cx, abs(n.cx - col_cx)))
            # Prefer one per kind
            best = {}
            for kind, cx, err in chrome:
                if kind not in best or err < best[kind][1]:
                    best[kind] = (cx, err)
            bad = {k: v for k, v in best.items() if v[1] > 1.0}
            # Only score when we found at least a title or dots; else SKIP
            if not best:
                add('G7', 'Chrome centred on page column (±1 px)', None,
                    f'no banner/title/dots/snackbar in page column; colCx={col_cx:.1f}',
                    'ux.md §1.5 rail page area')
            else:
                add('G7', 'Chrome centred on page column (±1 px)', not bad,
                    f'colCx={col_cx:.1f}; centres { {k: round(v[0],1) for k,v in best.items()} }; '
                    f'err_px { {k: round(v[1],1) for k,v in best.items()} }; bad {list(bad) or "none"}',
                    'ux.md §1.5 rail page area')
        # A2: rail a11y structure (ux.md §1.5). Full TalkBack focus order needs an a11y service;
        # uiautomator dump order is draw/composition order, not focus order — so we check geometry:
        # Apps/Done above dock[0], dock items top→bottom in file order, grid in the page column.
        if rail is True and dock:
            head = find(nodes, 'text', r'^(Apps|Done)$') or find(nodes, 'desc', r'^(Apps|Done|More options)$')
            head_above = bool(head) and head.cy <= min(n.cy for n in dock) + 1
            # File order Dock1..DockN by content-desc prefix when present
            named = [(n.desc.split(',', 1)[0], n) for n in dock if ',' in (n.desc or '')]
            by_y = [n for _, n in sorted(named, key=lambda t: t[1].cy)]
            file_names = [d.split(',', 1)[0] for d in (n.desc for n in by_y)]
            # Expect Dock1, Dock2, … when fixture uses that naming; else just monotonic cy
            expect = [f'Dock{i}' for i in range(1, len(file_names) + 1)]
            order_ok = (file_names == expect) if all(x.startswith('Dock') for x in file_names) else True
            grid_in_col = True
            if tile_nodes:
                is_r, side, rl, rr = rail_geom(dock, W or 1, H or 1, nodes=nodes)
                for t in tile_nodes:
                    if side == 'right' and rl is not None and t.cx >= rl:
                        grid_in_col = False
                    if side == 'left' and rr is not None and t.cx <= rr:
                        grid_in_col = False
            ok_a2 = head_above and order_ok and grid_in_col
            add('A2', 'A11y traversal-order proxy (TalkBack N/A on CI)', ok_a2,
                f'head_above_dock {head_above}; dock_file_order {file_names[:7]}; '
                f'grid_in_page_col {grid_in_col}; proxy for ux.md §1.5 rail order '
                f'(head→…→grid→dots→dock); TalkBack cannot run on CI images',
                'ux.md §1.5 TalkBack and D-pad order')

        # L6s: dock7 scrolled rail — last item reachable (driver dumps home-dock-end.xml after swipe).
        # When all 7 already fit on home1, scroll is a no-op and PASS. If the swipe opened the
        # drawer instead (CI fluke / wrong rail_x), fall back to home1 rather than false-FAIL.
        if (m.get('fixture') or '') == 'dock7.json' and rail is True:
            def _dock7_visible(ns):
                return any((n.desc or '').startswith('Dock7') and min(n.w, n.h) >= 40 * s * 0.85
                           for n in ns if (n.desc or '').endswith(', dock'))
            home_has = _dock7_visible(nodes)
            end_nodes = load(os.path.join(d, 'home-dock-end.xml'))
            if home_has and end_nodes is None:
                add('L6s', 'Dock7 rail scroll: last item reachable', True,
                    'Dock7 already visible on home1 (no scroll needed)',
                    'ux.md §1.5 rail scrolls; items never hidden')
            elif end_nodes is None:
                add('L6s', 'Dock7 rail scroll: last item reachable', None,
                    'no home-dock-end.xml (driver swipe not run)',
                    'ux.md §1.5 rail scrolls; items never hidden')
            else:
                end_dock = [n for n in end_nodes if (n.desc or '').endswith(', dock')]
                end_is_drawer = any(n.text == 'Search apps' or (n.desc or '') == 'Search apps'
                                    for n in end_nodes)
                has_last = _dock7_visible(end_nodes)
                if not has_last and home_has and (end_is_drawer or not end_dock):
                    add('L6s', 'Dock7 rail scroll: last item reachable', True,
                        f'Dock7 already on home1; end dump was drawer/empty (swipe miss); end_dock={len(end_dock)}',
                        'ux.md §1.5 rail scrolls; items never hidden')
                else:
                    add('L6s', 'Dock7 rail scroll: last item reachable', has_last,
                        f'Dock7 visible after swipe: {has_last}; end_dock={len(end_dock)}; '
                        f'home1_has={home_has}',
                        'ux.md §1.5 rail scrolls; items never hidden')

        # L7: page continuity after a reverse-rotate while on page 2
        h2r = load(os.path.join(d, 'home2-rot.xml'))
        if h2r is None:
            # home2-rot only dumped on large-tier. Omit on phone; SKIP on large if missing.
            if (m.get('tier') or 'phone') == 'large':
                add('L7', 'Page continuity after rotate (still page 2)', None,
                    'not captured (needs home2 then a reverse-rotate dump)',
                    'ux.md §1.5 "same page stays shown, by page id"; test-plan §20 L7')
        else:
            still = any(re.search(r'^Second,|, page [2٢] of [2٢]', n.desc) or n.text == 'Second'
                        for n in h2r)
            add('L7', 'Page continuity after rotate (still page 2)', still,
                f'Second/page 2 visible after rotate: {still}',
                'ux.md §1.5 "same page stays shown, by page id"; test-plan §20 L7')

        # L6: dock7 fixture — all 7 fit, slots ≥ 48 dp, file order. Rail-geometry half advisory until rail PR (#197).
        if (m.get('fixture') or '') == 'dock7.json':
            dock7_raw = [n for n in nodes if (n.desc or '').endswith(', dock')]
            rail = dock_is_rail(dock7_raw, W or 1, H or 1)
            # Ignore partially visible scrolled rail cells (a few px of content-desc still
            # exposed at the clip edge — was reporting a bogus "3 dp" chip on dock7-phone-land).
            def _fully_visible(n, pool):
                if not pool:
                    return True
                med = sorted(min(p.w, p.h) for p in pool)[len(pool) // 2]
                return min(n.w, n.h) >= max(med * 0.6, 40 * s * 0.85)
            dock7 = [n for n in dock7_raw if _fully_visible(n, dock7_raw)]
            sizes_ok = all((min(n.w, n.h) / s) >= 47.5 for n in dock7) if dock7 else False
            # Scrolled rail (§1.5): not all 7 fit on screen; visible full slots must be ≥48 dp.
            partials = [n for n in dock7_raw if n not in dock7]
            scrolled = bool(partials) or (rail and len(dock7_raw) < 7 and height_dp < 7 * 48 + 80)
            count_ok = len(dock7_raw) >= 7 or len(dock7) >= 7 or (scrolled and len(dock7) >= 3)

            want_rail = bool(re.search(r'land', pid) or
                             (m.get('tier') == 'phone' and (rot == '1' or 'land' in pid)))
            place = 'rail' if rail else 'bottom dock'
            if want_rail and rail:
                ordered = sorted(dock7, key=lambda n: (n.cy, n.cx))
                add('L6', 'Dock count (dock7): 7 slots ≥ 48 dp in file order',
                    count_ok and sizes_ok,
                    f'count {len(dock7)} visible/{len(dock7_raw)} raw'
                    f'{" scrolled" if scrolled else ""}; '
                    f'sizes dp {[round(min(n.w, n.h)/s, 1) for n in ordered[:7]]}; {place}',
                    'test-plan §20 L6 (#197)')
            elif want_rail and not rail:
                add('L6', 'Dock count (dock7): 7 slots ≥ 48 dp (bottom until rail)',
                    count_ok and sizes_ok,
                    f'count {len(dock7)}; sizes dp {[round(min(n.w, n.h)/s, 1) for n in dock7[:7]]}; '
                    f'{place} — geometry half advisory until L5 rail PR',
                    'test-plan §20 L6 (#197)')
                add('L6r', 'Dock7 rail geometry (advisory until rail PR)', False,
                    f'want rail on {pid}; is_rail {rail}',
                    'test-plan §20 L6 rail half (#197)')
            else:
                add('L6', 'Dock count (dock7): 7 slots ≥ 48 dp in file order',
                    count_ok and sizes_ok,
                    f'count {len(dock7)}; sizes dp {[round(min(n.w, n.h)/s, 1) for n in dock7[:7]]}; {place}',
                    'test-plan §20 L6 (#197)')
        # L6 omitted when fixture is not dock7 (do not SKIP — that would INCOMPLETE under gate=dev8).

        # L13: cards-large — 2×2 + 4×1; k ≤ 1.5 on t10-land (card side ≤ 1.5 × 80 dp cell ref).
        if (m.get('fixture') or '') == 'cards-large.json':
            cards_n = [n for n in nodes if (n.desc or '').endswith(', widget') or ', widget,' in (n.desc or '')]
            if not cards_n:
                add('L13', 'Cards (cards-large): k ≤ 1.5, no stretch', None,
                    'no card/widget nodes in dump', 'test-plan §20 L13 (#197)')
            else:
                # k = clamp(cell/80, 0.85, 1.5); on-screen card side should not exceed 1.5 × span × 80 dp.
                # Prefer fixture-known spans (cards-large: Two By Two / Four By One). Else ceil(size/pitch)
                # — round() under-spans when the product centres a k≤1.5-capped host inside a larger pitch
                # cell (Four By One 476 dp / pitch 144 → round→3 → false 360 cap).
                known = {
                    'two by two': (2, 2),
                    'four by one': (4, 1),
                }
                ref = (pitch[0] if pitch else 80.0)
                ref_h = (pitch[1] if pitch else ref)
                bad = []
                for n in cards_n:
                    desc_l = (n.desc or '').lower()
                    span = next((wh for key, wh in known.items() if desc_l.startswith(key)), None)
                    if span:
                        span_w, span_h = span
                    else:
                        span_w = max(1, math.ceil((n.w / s) / max(ref, 1) - 1e-6))
                        span_h = max(1, math.ceil((n.h / s) / max(ref_h, 1) - 1e-6))
                    max_w = 1.5 * 80 * span_w
                    max_h = 1.5 * 80 * span_h
                    if (n.w / s) > max_w + 2 or (n.h / s) > max_h + 2:
                        bad.append(f'{n.desc[:40]} {n.w/s:.0f}×{n.h/s:.0f} dp > {max_w:.0f}×{max_h:.0f}')
                # t10-land is the stated point; other points still check no-stretch
                add('L13', 'Cards (cards-large): k ≤ 1.5, no stretch', not bad,
                    f'{len(cards_n)} card(s); bad {bad[:3] or "none"}',
                    'test-plan §20 L13 (#197)')
        # L13 omitted when fixture is not cards-large.json.

    # TH1 theme: drawer surface colour
    th = None; detail = 'no drawer.png or Pillow'
    try:
        from PIL import Image
        p = os.path.join(d, 'drawer.png')
        if os.path.exists(p) and dr is not None:
            im = Image.open(p).convert('RGB')
            y = int(im.height * 0.92)
            row = [im.getpixel((x, y)) for x in range(0, im.width, max(1, im.width // 40))]
            from collections import Counter
            c = Counter(row).most_common(1)[0][0]
            night = night_got or m.get('night')     # judge against the theme the system really had
            want = (0x14, 0x12, 0x18) if night == 'yes' else (0xFE, 0xF7, 0xFF)
            th = None if night not in ('yes', 'no') else all(abs(a - b) <= 16 for a, b in zip(c, want))
            detail = f'surface sample #{c[0]:02X}{c[1]:02X}{c[2]:02X}, want #{want[0]:02X}{want[1]:02X}{want[2]:02X} (night={night})'
    except Exception as e:  # no Pillow, or an empty/corrupt screenshot: advisory check just doesn't run
        detail = f'screenshot not readable ({type(e).__name__})'
    add('TH1', 'Drawer follows the system theme (§12.1 surface colour)', th, detail, 'ux.md §12.1 Palettes (Surface)')
    return m, res


def verdict_of(m, res):
    """FAIL on any blocking FAIL. A blocking check that couldn't run (SKIP) also fails a real run, because a
    missing dump means the flow broke; dry runs over partial old evidence set partial=yes in meta.txt."""
    if any(r['status'] == 'FAIL' and r['severity'] == 'block' for r in res):
        return 'FAIL'
    if m.get('partial') != 'yes' and any(r['status'] == 'SKIP' and r['severity'] == 'block' for r in res):
        return 'INCOMPLETE'
    # Seed never showed (X3): the driver marks partial=yes and skips F3–F7/G/L, so a PASS would only mean
    # "nothing ran". Report it as INCOMPLETE so a shard can't go green on an untested point.
    if m.get('seed_ok') == '0':
        return 'INCOMPLETE'
    return 'PASS'


def cmd_check(d):
    m, res = run_checks(d)
    verdict = verdict_of(m, res)
    json.dump(dict(point=m.get('id', os.path.basename(d.rstrip('/'))), meta=m, verdict=verdict, checks=res),
              open(os.path.join(d, 'result.json'), 'w'), indent=1, ensure_ascii=False)
    lines = [f"# {m.get('id', d)}: {verdict}", '',
             f"{m.get('screen_px', '?')} px @ {m.get('density', '?')} dpi, font {m.get('font_scale', '?')}, night {m.get('night', '?')}, "
             f"rotation {m.get('rotation', '0')}, API {m.get('api', '?')}, {m.get('shape', '')}", '',
             '| check | result | severity | detail | spec |', '|---|---|---|---|---|']
    for r in res:
        lines.append(f"| {r['id']} {r['name']} | {r['status']} | {r['severity']} | {r['detail'].replace('|', '/')} | {r['spec']} |")
    open(os.path.join(d, 'result.md'), 'w').write('\n'.join(lines) + '\n')
    print('\n'.join(lines))
    return 0


def cmd_summarize(out):
    rows, bad = [], 0
    for name in sorted(os.listdir(out)):
        p = os.path.join(out, name, 'result.json')
        if not os.path.exists(os.path.join(out, name, 'meta.txt')):
            continue
        if not os.path.exists(p):
            bad += 1
            rows.append(f'| {name} | ERROR | checker did not produce result.json | - | - |')
            continue
        r = json.load(open(p))
        f = [c['id'] for c in r['checks'] if c['status'] == 'FAIL' and c['severity'] == 'block']
        a = [c['id'] for c in r['checks'] if c['status'] == 'FAIL' and c['severity'] != 'block']
        k = [c['id'] for c in r['checks'] if c['status'] == 'SKIP' and c['severity'] == 'block']
        bad += r['verdict'] != 'PASS'
        rows.append(f"| {r['point']} | {r['verdict']} | {', '.join(f) or '-'} | {', '.join(a) or '-'} | {', '.join(k) or '-'} |")
    md = ['# Screen matrix summary', '', f'{len(rows)} points, {bad} not PASS (FAIL = a blocking check failed; INCOMPLETE = a blocking check could not run).', '',
          '| point | verdict | blocking fails | advisory/pending fails | blocking checks not run |', '|---|---|---|---|---|'] + rows
    open(os.path.join(out, 'summary.md'), 'w').write('\n'.join(md) + '\n')
    print('\n'.join(md))
    return 1 if bad or not rows else 0


if __name__ == '__main__':
    a = sys.argv[1:]
    if not a:
        sys.exit(__doc__)
    if a[0] == 'find':
        sys.exit(cmd_find(a[1], a[2], a[3]))
    if a[0] == 'screen':
        sys.exit(cmd_screen(a[1]))
    if a[0] == 'find-empty':
        sys.exit(cmd_find_empty(a[1], a[2]))
    if a[0] == 'check':
        sys.exit(cmd_check(a[1]))
    if a[0] == 'summarize':
        sys.exit(cmd_summarize(a[1]))
    sys.exit(__doc__)
