#!/usr/bin/env python3
"""Screen-matrix checks (docs/test-plan-screen-matrix.md).

Reads the uiautomator dumps and text files that scripts/screen-matrix.sh writes for one
matrix point and decides each check. No adb here, so it can be dry-run against old dumps.

  screen_matrix_check.py find <dump.xml> <attr> <regex>     print the centre "x y" of the first match
  screen_matrix_check.py find-empty <dump.xml> <density>     print a point on empty home grid space
  screen_matrix_check.py check <run-dir>                     write result.json + result.md for one point
  screen_matrix_check.py summarize <out-dir>                 merge every */result.json; exit 1 on a blocking FAIL

Severity per check: block (fails the dev7 gate), advisory (reported only) or pending (rule not in
docs yet; reported, never blocks). See the "dev7 gate" section of the test plan.
"""
import json, os, re, sys
import xml.etree.ElementTree as ET

PKG = 'dev.nasawa.configlauncher'
CLOCK_RE = re.compile(r'^\d{1,2}:\d{2}( ?[AaPp]\.?[Mm]\.?)?$')
HEADER_TEXTS = {'App storage', 'Apps', '⋮'}


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


def find(nodes, attr, rx):
    r = re.compile(rx)
    for n in nodes or []:
        if r.search(getattr(n, attr)) and n.w > 0 and n.h > 0:
            return n
    return None


def texts(nodes):
    return [n.text for n in nodes or [] if n.text]


# ---------------------------------------------------------------- title bar model
def title_bar(nodes, s):
    """The three §15 slots from one home dump (date View/TextView, {page} wrapper, clock View/TextView)."""
    clock_v = next((n for n in nodes if n.cls == 'View' and CLOCK_RE.match(n.desc) and n.w > 0), None)
    page_v = next((n for n in nodes if n.desc.endswith(', Pages') and n.w > 0), None)
    if not clock_v or not page_v:
        return None
    y0, y1 = min(clock_v.b[1], page_v.b[1]), max(clock_v.b[3], page_v.b[3])
    band = [n for n in nodes if n.b[1] >= y0 - 4 and n.b[3] <= y1 + 4 and n.w > 0]
    clock_t = next((n for n in band if n.cls == 'TextView' and contains(clock_v.b, n.b, 2)), None)
    date_v = next((n for n in band if n.cls == 'View' and n is not clock_v and n is not page_v
                   and n.desc and not n.desc.endswith(', Pages')), None)
    date_t = None
    if date_v:
        date_t = next((n for n in band if n.cls == 'TextView' and contains(date_v.b, n.b, 2)
                       and not contains(page_v.b, n.b)), None)
    page_t = [n for n in band if n.cls == 'TextView' and contains(page_v.b, n.b, 2)
              and (not date_t or n is not date_t) and (not clock_t or n is not clock_t)]
    page_texts = [n.text for n in page_t]
    bar_rect = [min(n.b[0] for n in band), y0, max(n.b[2] for n in band), y1]
    return dict(clock_v=clock_v, clock_t=clock_t, page_v=page_v, page_texts=page_texts,
                date_v=date_v, date_t=date_t, band=band, rect=bar_rect, s=s)


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
def grid_region(nodes, tb, screen):
    """Home grid area: below the title bar (or header), above the page indicator / dock."""
    top = tb['rect'][3] if tb else max([n.b[3] for n in nodes if n.text in HEADER_TEXTS] or [0])
    ind = [n.b[1] for n in nodes if re.fullmatch(r'Page \d+ of \d+', n.desc)]
    dock = [n.b[1] for n in nodes if n.desc.endswith(', dock')]
    bottom = min(ind + dock + [screen[1]])
    return top, bottom


def grid_parts(nodes, tb, screen):
    top, bottom = grid_region(nodes, tb, screen)
    area = screen[0] * screen[1]
    cards = [n for n in nodes if n.desc.endswith(', widget') or ', widget,' in n.desc]
    in_card = lambda n: any(contains(c.b, n.b, 2) for c in cards)
    labels = [n for n in nodes if n.cls == 'TextView' and n.text and n.b[1] >= top and n.b[3] <= bottom + 2
              and n.text not in HEADER_TEXTS and not in_card(n)
              and not (tb and inter(n.b, tb['rect']) > 0)]
    tiles = [n for n in nodes if n.click and n.b[1] >= top - 2 and n.b[3] <= bottom + 2
             and n.w * n.h < area / 4 and not in_card(n) and n not in cards]
    dock = [n for n in nodes if n.desc.endswith(', dock')]
    return labels, tiles, dock, cards, (top, bottom)


# ---------------------------------------------------------------- helpers for the driver
def cmd_find(path, attr, rx):
    n = find(load(path), {'text': 'text', 'desc': 'desc', 'content-desc': 'desc'}[attr], rx)
    if n:
        print(n.cx, n.cy)
    return 0 if n else 1


def cmd_find_empty(path, density):
    nodes = load(path) or []
    s = float(density) / 160
    W = max([n.b[2] for n in nodes] or [0]); H = max([n.b[3] for n in nodes] or [0])
    tb = title_bar(nodes, s)
    labels, tiles, dock, cards, (top, bottom) = grid_parts(nodes, tb, (W, H))
    blockers = [n.b for n in labels + tiles + cards]
    pad = int(12 * s)
    y = bottom - int(24 * s)
    while y > top + pad:
        for fx in (0.5, 0.25, 0.75, 0.125, 0.875):
            x = int(W * fx)
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


def severity(cid, m):
    """Gate policy (test plan, 'dev7 gate')."""
    fs = float(m.get('font_scale', '1.0'))
    tier = m.get('tier', 'phone')
    pending = {'G5b', 'G5c'}
    advisory_always = {'P1', 'A1', 'T5', 'T6', 'TH1', 'D3'}
    if cid in pending:
        return 'pending'
    if cid == 'F1':
        return 'block'
    if cid in advisory_always:
        return 'advisory'
    if fs >= 1.95:
        return 'advisory'
    if tier != 'phone' and cid.startswith('G'):
        return 'advisory'       # Q-S2: tablet/landscape layout not specified yet (ux.md §1.1)
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

    def add(cid, name, ok, detail, spec):
        res.append(dict(id=cid, name=name, status=('PASS' if ok is True else 'FAIL' if ok is False else 'SKIP'),
                        severity=severity(cid, m), detail=detail, spec=spec))

    home = {}
    for k in ('home1', 'home2'):
        home[k] = load(os.path.join(d, k + '.xml'))
    if not W and home['home1']:
        W = max(n.b[2] for n in home['home1']); H = max(n.b[3] for n in home['home1'])

    # F1 cold start, crash and ANR
    crash = read(os.path.join(d, 'crash.txt'))
    ours = [l for l in crash.splitlines() if 'FATAL EXCEPTION' in l or ('ANR in ' + PKG) in l or (PKG in l and 'Process:' in l)]
    anr = read(os.path.join(d, 'anr.txt'))
    our_anr = [l for l in anr.splitlines() if 'Config Launcher' in l]
    start = read(os.path.join(d, 'start.txt'))
    started = 'Status: ok' in start or 'TotalTime' in start or not start
    add('F1', 'Cold start: no crash, no launcher ANR', started and not ours and not our_anr,
        f'crash lines {len(ours)}, launcher ANR dialogs {len(our_anr)}, other ANR dialogs {len(anr.splitlines()) - len(our_anr)}',
        'test-plan §15.2 smoke; A§11')
    tt = re.findall(r'TotalTime: (\d+)', start)
    add('P1', 'Cold start time recorded (am start -W)', True if tt else None,
        f'TotalTime {tt[-1]} ms (emulator, not a PF-2 measurement)' if tt else 'no TotalTime', 'test-plan §15.3 (PF-2 is device-only)')

    # F2 seeded layout + RELOAD
    h1 = home['home1']
    seeded = bool(h1) and find(h1, 'desc', r'^Home, Pages$') is not None and find(h1, 'desc', r', dock$') is not None
    add('F2', 'Seeded layout shows after RELOAD (title bar "Home", dock)', seeded if h1 is not None else None,
        'home1.xml ' + ('ok' if seeded else ('missing or unparsable' if not h1 else 'no "Home, Pages" slot or dock item')),
        'ux.md §5 Reload; manifest ReloadReceiver (#55)')

    # F3 long-press item
    lp = load(os.path.join(d, 'lp-item.xml'))
    top = read(os.path.join(d, 'top-lp-item.txt'))
    if lp is None:
        add('F3', 'Long-press a home item opens the item sheet, nothing launches', None, 'not captured', 'ux.md §1.1 gestures; test-plan §19 LP-2')
    else:
        t = texts(lp)
        ok = 'App info' in t and any('com.android.settings' in x for x in t) and (PKG in top or not top)
        add('F3', 'Long-press a home item opens the item sheet, nothing launches', ok,
            f"App info {'App info' in t}, real package {any('com.android.settings' in x for x in t)}, launcher on top {PKG in top if top else 'n/a'}",
            'ux.md §1.1 gestures; test-plan §19 LP-2')

    # F4 long-press empty space
    le = load(os.path.join(d, 'lp-empty.xml'))
    if le is None:
        add('F4', 'Long-press empty space opens the home menu', None, 'not captured', 'ux.md §1.1 gestures; test-plan §19 LP-6')
    else:
        t = texts(le)
        need = ['Edit layout', 'Reload layout', 'Settings']
        add('F4', 'Long-press empty space opens the home menu', all(x in t for x in need) and 'App info' not in t,
            'menu: ' + ', '.join(x for x in t if x in need + ['Pages', 'Wallpaper', 'App info']),
            'ux.md §1.1 gestures; test-plan §19 LP-6')

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
        add('F5b', 'Long-press a drawer app: Add to home, Hide, App info', all(x in t for x in ('Add to home', 'Hide', 'App info')),
            'actions: ' + ', '.join(x for x in t if x in ('Add to home', 'Hide', 'App info')), 'ux.md §2 Long-press app; test-plan §19 LP-7')
    if dr is not None:
        sb = [n for n in dr if n.cls == 'TextView' and n.text and n.text not in ('Search apps', '⋮')]
        clipped = [n.text for n in sb if 0 < n.h / s < 12 * fs * 0.85 and n.b[3] < H - 2]
        add('D3', 'Drawer labels not clipped (rows above the nav bar)', not clipped,
            f'{len(sb)} labels, clipped: {clipped[:5]}', 'ux.md §2.1, §12 Font scaling')

    # F6 Pages switch
    pg = load(os.path.join(d, 'pages.xml')); h2 = home['home2']
    ok6 = None if (pg is None or h2 is None) else pg is not None and find(pg, 'desc', r'page 2 of 2') is not None and h2 is not None and find(h2, 'desc', r'^Second, Pages$') is not None
    add('F6', 'Pages list opens from the {page} slot and switches to page 2', ok6,
        f"pages list {'ok' if pg is not None and find(pg, 'desc', 'page 2 of 2') else 'missing'}, page 2 {'ok' if h2 is not None and find(h2, 'desc', '^Second, Pages$') else 'missing'}",
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
            'ux.md §1.1 Cell ("the layout is never clipped"), §12 Font scaling' + ('; Pragma ux/notice-row (pending)' if chip_fixture else ''))
        ov = [(a.text, b.text) for i, a in enumerate(labels) for b in labels[i + 1:] if inter(a.b, b.b) > 0]
        tov = [(a.b, b.b) for i, a in enumerate(tiles) for b in tiles[i + 1:]
               if inter(a.b, b.b) > 0 and not contains(a.b, b.b) and not contains(b.b, a.b)]
        add('G2' + sfx, 'No overlapping labels or tiles', not ov and not tov, f'label overlaps {ov[:4]}, tile overlaps {tov[:4]}',
            'ux.md §1.1 Grid/Cell')
        small = [(n.desc or n.text or 'tile', round(n.w / s), round(n.h / s)) for n in tiles + dock if n.w / s < 47.5 or n.h / s < 47.5]
        off = [n.b for n in tiles + dock + labels if n.b[0] < 0 or n.b[1] < 0 or (W and n.b[2] > W) or (H and n.b[3] > H)]
        intobar = [n.text for n in labels if tb and inter(n.b, tb['rect']) > 0]
        add('G3' + sfx, 'Tiles and dock slots ≥ 48 dp, on screen, nothing under the title bar', not small and not off and not intobar,
            f'{len(tiles)} tiles, {len(dock)} dock; under 48 dp {small[:4]}; off-screen {off[:3]}; into bar {intobar[:3]}',
            'ux.md §12 Touch targets, §1.1 Cell, §15 "Never overlaps icons"')
        if chip_fixture and k == 'home1':
            chip = find(nodes, 'desc', r'^Widget permissions')
            if chip is None:
                add('G5a', 'Count chip present with ≥ 48 dp hit area', False, 'no "Widget permissions" chip', 'ux.md §17.4 Count chip, §12')
            else:
                add('G5a', 'Count chip present with ≥ 48 dp hit area', chip.w / s >= 47.5 and chip.h / s >= 47.5,
                    f'chip {chip.w / s:.1f}×{chip.h / s:.1f} dp at {chip.b}', 'ux.md §17.4 Count chip, §12 Touch targets')
                same_row = tb is not None and chip.b[1] < tb['rect'][3] and chip.b[3] > tb['rect'][1]
                add('G5b', 'Collapsed chip shares one row with the page label (no extra strip)', same_row,
                    f"chip y {chip.b[1]}-{chip.b[3]}, title bar y {tb['rect'][1] if tb else '?'}-{tb['rect'][3] if tb else '?'}",
                    'Pragma ux/notice-row (pending doc, decides #173)')

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
            want = (0x14, 0x12, 0x18) if m.get('night') == 'yes' else (0xFE, 0xF7, 0xFF)
            th = None if m.get('night') not in ('yes', 'no') else all(abs(a - b) <= 16 for a, b in zip(c, want))
            detail = f'surface sample #{c[0]:02X}{c[1]:02X}{c[2]:02X}, want #{want[0]:02X}{want[1]:02X}{want[2]:02X} (night={m.get("night")})'
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
    if a[0] == 'find-empty':
        sys.exit(cmd_find_empty(a[1], a[2]))
    if a[0] == 'check':
        sys.exit(cmd_check(a[1]))
    if a[0] == 'summarize':
        sys.exit(cmd_summarize(a[1]))
    sys.exit(__doc__)
