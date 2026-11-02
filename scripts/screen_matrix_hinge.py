#!/usr/bin/env python3
"""Real-hinge checks for the opt-in `hinge` screen-matrix shard (PR #219 QA, fold/hinge layout).

  meta <hinge.txt> <shape>        meta.txt lines from QaHingeProbeTest output (fold, split, home/wall halves)
  wall <meta.txt>                 "x y y_top y_bottom" inside the wallpaper half (book only)
  drag-plan <dump.xml> <meta.txt> "x1 y1 x2 y2": 'Row two' to an empty grid cell (as screen_matrix_dnd.py 'grid')
  focused <dump.xml>              bounds + label of the input-focused node, or "none"
  score <point dir>               hinge-result.md / hinge-result.json
  parity <dir A> <dir B> [out]    home dumps of the same points under two APKs (e.g. main vs PR), node by node

Statuses: PASS / FAIL / NOT-VERIFIABLE (the emulator or harness could not show it; never counted as a pass).
"""
import json, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import screen_matrix_check as c  # noqa: E402

PKG = 'dev.nasawa.configlauncher'
NV = 'NOT-VERIFIABLE'


def parse_probe(path):
    out = {'features': None, 'folds': []}
    txt = c.read(path)
    for ln in txt.splitlines():
        ln = ln.strip()
        if ln.startswith('window='):
            out['window'] = [int(v) for v in ln[7:].split(',')]
        elif ln.startswith('layout_dir='):
            mm = re.match(r'layout_dir=(\d+)', ln)
            out['layout_dir'] = int(mm.group(1)) if mm else None
            out['config'] = ln
        elif ln.startswith('features='):
            out['features'] = int(ln.split('=')[1])
        elif ln.startswith('windowLayoutInfo=timeout'):
            out['features'] = 'timeout'
        elif ln.startswith('extension_version='):
            out['extension_version'] = ln.split('=', 1)[1]
        elif ln.startswith('feature=FoldingFeature'):
            f = dict(re.findall(r'(\w+)=(\S+)', ln))
            f['bounds'] = [int(v) for v in f['bounds'].split(',')]
            out['folds'].append(f)
    out['raw'] = txt.strip()
    return out


def cmd_meta(probe, shape):
    p = parse_probe(probe)
    print(f"hinge_features={p['features'] if p['features'] is not None else 'no-probe-output'}")
    print(f"hinge_extension_version={p.get('extension_version', '')}")
    if p.get('window'):
        print('hinge_window=' + ','.join(map(str, p['window'])))
    if p.get('layout_dir') is not None:
        print(f"hinge_layout_dir={p['layout_dir']}")
    for f in p['folds']:
        print(f"hinge_fold={','.join(map(str, f['bounds']))};{f.get('state')};{f.get('orientation')};"
              f"isSeparating={f.get('isSeparating')};occlusion={f.get('occlusion')}")
    sep = [f for f in p['folds'] if f.get('isSeparating') == 'true']
    if not sep or not p.get('window'):
        print('hinge_split=none')
        return
    f = sep[0]; wl, wt, wr, wb = p['window']; l, t, r, b = f['bounds']
    rtl = p.get('layout_dir') == 1 if p.get('layout_dir') is not None else 'rtl' in shape.lower()
    if f.get('orientation') == 'VERTICAL':
        left, right = [wl, wt, l, wb], [r, wt, wr, wb]
        home, wall = (right, left) if rtl else (left, right)
        print('hinge_split=book')
        print('home_half=' + ','.join(map(str, home)))
        print('wall_half=' + ','.join(map(str, wall)))
    else:
        print('hinge_split=tabletop')
        print(f'hinge_top={t}'); print(f'hinge_bottom={b}')


def rect(v):
    return [int(x) for x in v.split(',')] if v else None


def cmd_wall(meta):
    m = c.meta_of(os.path.dirname(meta)) if os.path.isdir(os.path.dirname(meta)) else {}
    w = rect(m.get('wall_half'))
    if not w:
        return 1
    x = (w[0] + w[2]) // 2; h = w[3] - w[1]
    print(x, w[1] + h // 2, w[1] + int(h * 0.25), w[1] + int(h * 0.70))
    return 0


def cmd_drag_plan(xml, meta):
    nodes = c.load(xml)
    if not nodes:
        return 1
    import screen_matrix_dnd as dnd
    hs = dnd.holders(nodes)
    row0 = [hs[k] for k in dnd.FIXTURE_ITEMS[:4] if k in hs]
    if dnd.DRAG not in hs or len(row0) < 2:
        print(f'fixture tiles not found ({sorted(hs)})', file=sys.stderr)
        return 1
    left = min(h.b[0] for h in row0); right = max(h.b[2] for h in row0)
    pw = (right - left) / 4.0; top = min(h.b[1] for h in row0)
    ph = hs[dnd.DRAG].b[1] - top if hs[dnd.DRAG].b[1] > top else row0[0].h
    src = hs[dnd.DRAG]
    print(src.cx, src.cy, int(left + 1.5 * pw), int(top + 2.5 * ph))
    return 0


def focused_of(nodes):
    fs = [n for n in nodes or [] if n.focus]
    if not fs:
        return None
    fs.sort(key=lambda n: n.w * n.h)
    return fs[0]


def cmd_focused(xml):
    n = focused_of(c.load(xml))
    if n is None:
        print('none')
    else:
        print('%d,%d,%d,%d %s' % (*n.b, json.dumps((n.text or n.desc or n.cls)[:40])))
    return 0


def meaningful(nodes, W, H):
    """App nodes a user can see or reach: labelled or clickable, not a whole-window container."""
    out = []
    for n in nodes or []:
        if n.pkg != PKG or n.w <= 0 or n.h <= 0:
            continue
        if not (n.text or n.desc or n.click):
            continue
        if n.w >= 0.9 * W and n.h >= 0.9 * H:
            continue
        out.append(n)
    return out


def inside(b, r, slack=2):
    return b[0] >= r[0] - slack and b[1] >= r[1] - slack and b[2] <= r[2] + slack and b[3] <= r[3] + slack


def center_in(n, r):
    return r[0] <= n.cx < r[2] and r[1] <= n.cy < r[3]


def lab(n):
    return (n.text or n.desc or n.cls)[:32]


def has(nodes, rx):
    return any(re.search(rx, n.text or '') or re.search(rx, n.desc or '') for n in nodes or [])


def score(d):
    m = c.meta_of(d)
    shape = m.get('shape', '')
    kind = 'book' if 'hinge:book' in shape else 'tabletop' if 'hinge:tabletop' in shape else \
        'cover' if 'hinge:cover' in shape else 'flat'
    res = []

    def add(cid, name, status, detail):
        res.append({'id': cid, 'name': name, 'status': status, 'detail': detail})

    p = parse_probe(os.path.join(d, 'hinge.txt'))
    pe = parse_probe(os.path.join(d, 'hinge-end.txt')) if os.path.exists(os.path.join(d, 'hinge-end.txt')) else None
    folds = p['folds']
    fdesc = '; '.join(f"{f['bounds']} {f.get('state')} {f.get('orientation')} sep={f.get('isSeparating')}" for f in folds) or 'none'
    if p['features'] is None:
        add('HF', 'WindowInfoTracker read', NV, 'probe produced no output (see hinge-instr.txt)')
    elif kind in ('book', 'tabletop'):
        want = 'VERTICAL' if kind == 'book' else 'HORIZONTAL'
        ok = any(f.get('isSeparating') == 'true' and f.get('orientation') == want for f in folds)
        add('HF', f'Separating {want} FoldingFeature reported', 'PASS' if ok else NV,
            f"features={p['features']}: {fdesc}" + ('' if ok else ' — emulator did not expose a separating hinge here'))
    elif kind == 'flat':
        ok = not any(f.get('isSeparating') == 'true' for f in folds)
        add('HF', 'Flat: no separating feature', 'PASS' if ok else 'FAIL', f"features={p['features']}: {fdesc}")
    else:
        add('HF', 'Cover: feature list', 'PASS' if not any(f.get('isSeparating') == 'true' for f in folds) else 'FAIL',
            f"features={p['features']}: {fdesc}")
    if pe is not None:
        same = [f['bounds'] for f in pe['folds']] == [f['bounds'] for f in folds] and \
               [f.get('state') for f in pe['folds']] == [f.get('state') for f in folds]
        add('HF2', 'Posture held through the point', 'PASS' if same else 'FAIL',
            'end read: ' + ('; '.join(f"{f['bounds']} {f.get('state')}" for f in pe['folds']) or 'none'))
    split = m.get('hinge_split', 'none')
    hv = split in ('book', 'tabletop') and split == kind
    home1 = c.load(os.path.join(d, 'home1.xml'))
    W, H = c.screen_of(home1) if home1 else (0, 0)
    mn = meaningful(home1, W, H)

    if kind == 'book':
        hh, wh = rect(m.get('home_half')), rect(m.get('wall_half'))
        if not hv or not home1:
            add('HB', 'Book: home in the start half, nothing in/across the hinge', NV, 'no separating vertical fold' if not hv else 'no home1.xml')
        else:
            bad = [n for n in mn if not inside(n.b, hh)]
            dock = [n for n in mn if (n.desc or '').endswith(', dock')]
            dots = [n for n in mn if re.search(r'^Page [0-9٠-٩]+ of', n.desc or '')]
            add('HB', 'Book: home in the start half, nothing in/across the hinge', 'PASS' if not bad and mn else 'FAIL',
                f"home_half={hh} ({'right' if hh[0] > 0 else 'left'}); {len(mn)} nodes; outside: "
                + (', '.join(f'{lab(n)}{list(n.b)}' for n in bad[:8]) or 'none')
                + f"; dock items {len(dock)}, dots {len(dots)}")
            add('HA', 'Wallpaper half: no accessibility nodes', 'PASS' if not any(center_in(n, wh) for n in mn) else 'FAIL',
                'nodes centred in wall half: ' + (', '.join(lab(n) for n in mn if center_in(n, wh)) or 'none'))
        for cid, f, rx, name in (('HW1', 'wall-swipe', r'Search apps', 'Wallpaper half swipe-up opens the drawer'),
                                 ('HW2', 'wall-lp', r'^Edit layout$', 'Wallpaper half long-press opens the home menu')):
            nd = c.load(os.path.join(d, f + '.xml'))
            if not hv or nd is None:
                add(cid, name, NV, 'no separating fold' if not hv else f'no {f}.xml')
            else:
                add(cid, name, 'PASS' if has(nd, rx) else 'FAIL', f"{f}.xml {'has' if has(nd, rx) else 'lacks'} /{rx}/")
        fw = c.read(os.path.join(d, 'focus-walk.txt')).splitlines()
        seen = [ln for ln in fw if ' none' not in ln and re.search(r' \d+,\d+,\d+,\d+ ', ln + ' ')]
        if not hv or not fw:
            add('HK', 'Keyboard/D-pad focus never in the wallpaper half', NV, 'no separating fold' if not hv else 'no focus walk')
        elif not seen:
            add('HK', 'Keyboard/D-pad focus never in the wallpaper half', NV, f'{len(fw)} key presses, uiautomator never showed a focused node')
        else:
            inwall = []
            for ln in seen:
                b = [int(v) for v in re.search(r' (\d+),(\d+),(\d+),(\d+) ', ln + ' ').groups()]
                cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
                if wh[0] <= cx < wh[2]:
                    inwall.append(ln)
            add('HK', 'Keyboard/D-pad focus never in the wallpaper half', 'PASS' if not inwall else 'FAIL',
                f'{len(seen)}/{len(fw)} presses showed focus, {len(set(l.split(" ", 2)[2] for l in seen))} distinct; in wall half: '
                + ('; '.join(inwall[:4]) or 'none'))
        pk = [c.load(os.path.join(d, f'pk-{i}-{n}.xml')) for i, n in enumerate(('flat', 'book', 'tabletop', 'flat'))]
        if any(x is None for x in pk):
            add('HP', 'Page 2 kept through flat → book → tabletop → flat', NV, 'page-kept dumps missing')
        else:
            pages = []
            for x in pk:
                dd = [n.desc for n in x if re.search(r'^Page [0-9٠-٩]+ of', n.desc or '')]
                pages.append(dd[0] if dd else '?')
            hb, ha = c.read(os.path.join(d, 'pk-hash-before.txt')), c.read(os.path.join(d, 'pk-hash-after.txt'))
            ok = all(re.search(r'[2٢] of', pg) for pg in pages)
            add('HP', 'Page 2 kept through flat → book → tabletop → flat', 'PASS' if ok and hb == ha and hb else 'FAIL',
                f"dots: {' → '.join(pages)}; config {'unchanged' if hb == ha and hb else 'CHANGED' if hb else 'not read'}")

    if kind == 'tabletop':
        if not hv or not home1:
            add('HT', 'Tabletop: grid + title above the hinge, dots + bottom dock below', NV, 'no separating horizontal fold' if not hv else 'no home1.xml')
        else:
            top, bot = int(m['hinge_top']), int(m['hinge_bottom'])
            dock = [n for n in mn if (n.desc or '').endswith(', dock')]
            dots = [n for n in mn if re.search(r'^Page [0-9٠-٩]+ of', n.desc or '')]
            lower = set(id(n) for n in dock + dots)
            upper = [n for n in mn if id(n) not in lower]
            bad_up = [n for n in upper if n.b[3] > top + 2]
            bad_lo = [n for n in dock + dots if n.b[1] < bot - 2]
            across = [n for n in mn if n.b[1] < top - 2 and n.b[3] > bot + 2]
            bottom_dock = bool(dock) and (max(n.cy for n in dock) - min(n.cy for n in dock) < 8) and len(dock) >= 2
            ok = not bad_up and not bad_lo and not across and dock and dots and bottom_dock
            add('HT', 'Tabletop: grid + title above the hinge, dots + bottom dock below', 'PASS' if ok else 'FAIL',
                f"hinge y={top}..{bot}; upper {len(upper)} nodes, below-hinge offenders: "
                + (', '.join(f'{lab(n)}{list(n.b)}' for n in bad_up[:6]) or 'none')
                + '; lower offenders: ' + (', '.join(f'{lab(n)}{list(n.b)}' for n in bad_lo[:6]) or 'none')
                + f"; across: {len(across)}; dock {len(dock)} ({'bottom row' if bottom_dock else 'NOT a bottom row'}), dots {len(dots)}")

    # Posture change mid-drag
    plans = c.read(os.path.join(d, 'md-plan.txt'))
    tags = re.findall(r'^md-(\S+) ', plans, re.M)
    ctrl = [t for t in tags if t.startswith('control')]
    ctrl_wrote = None
    for t in ctrl:
        hb, ha = c.read(os.path.join(d, f'md-{t}-hash-before.txt')), c.read(os.path.join(d, f'md-{t}-hash-after.txt'))
        ctrl_wrote = bool(hb) and hb != ha
        add('HM0', f'Control drag without posture change writes ({t})', 'PASS' if ctrl_wrote else NV,
            'config changed: the before/after diff can see a write' if ctrl_wrote else 'control drag did not change the config — the no-write diff is not shown live')
    for t in tags:
        if t in ctrl:
            continue
        hb, ha = c.read(os.path.join(d, f'md-{t}-hash-before.txt')), c.read(os.path.join(d, f'md-{t}-hash-after.txt'))
        bn = c.load(os.path.join(d, f'md-{t}-before.xml')); rn = c.load(os.path.join(d, f'md-{t}-restored.xml'))
        import screen_matrix_dnd as dnd
        sb = dnd.holders(bn or []).get(dnd.DRAG); sr = dnd.holders(rn or []).get(dnd.DRAG)
        cell = 'tile not found' if not (sb and sr) else ('same place' if max(abs(a - b) for a, b in zip(sb.b, sr.b)) <= 8 else f'moved {list(sb.b)} → {list(sr.b)}')
        crash = c.read(os.path.join(d, f'md-{t}-crash.txt')).strip()
        if not hv:
            st = NV; why = 'no separating fold, so no posture change reached the app'
        elif not hb:
            st = NV; why = 'config hash not read'
        elif hb != ha or crash:
            st = 'FAIL'; why = 'config CHANGED' if hb != ha else 'crash'
        elif ctrl_wrote is not True:
            st = NV; why = 'config unchanged, but the control drag did not write either'
        else:
            st = 'PASS'; why = 'config byte-identical (all files/**/*.json)'
        add('HM', f'Posture change mid-drag cancels with no write ({t})', st, f'{why}; Row two after restore: {cell}')

    md = [f"# Hinge {d.rstrip('/').split('/')[-1]} ({kind}; split={split})", '', '| id | check | status | detail |', '|---|---|---|---|']
    md += [f"| {r['id']} | {r['name']} | {r['status']} | {r['detail'].replace('|', '/')} |" for r in res]
    open(os.path.join(d, 'hinge-result.md'), 'w').write('\n'.join(md) + '\n')
    json.dump({'kind': kind, 'split': split, 'probe': p, 'results': res}, open(os.path.join(d, 'hinge-result.json'), 'w'), indent=1)
    print(md[0])
    return 0


def sig(nodes):
    W, H = c.screen_of(nodes)
    return sorted((lab(n), n.cls, n.b) for n in meaningful(nodes, W, H))


def cmd_parity(a, b, out=None):
    lines = ['| point | dump | main nodes | PR nodes | differing | first differences |', '|---|---|---|---|---|---|']
    tot = 0
    for pt in sorted(os.listdir(a)):
        if not os.path.isdir(os.path.join(b, pt)):
            continue
        for dump in ('home1', 'home2', 'drawer', 'lp-empty', 'lp-item'):
            na, nb = c.load(os.path.join(a, pt, dump + '.xml')), c.load(os.path.join(b, pt, dump + '.xml'))
            if na is None or nb is None:
                continue
            sa, sb = sig(na), sig(nb)
            diff = sorted(set(sa) ^ set(sb))
            tot += bool(diff)
            lines.append(f"| {pt} | {dump} | {len(sa)} | {len(sb)} | {len(diff)} | "
                         + '; '.join(('main ' if x in sa else 'PR ') + f'{x[0]}{list(x[2])}' for x in diff[:4]).replace('|', '/') + ' |')
    txt = '\n'.join(lines) + f'\n\ndumps with any difference: {tot}\n'
    if out:
        open(out, 'w').write(txt)
    print(txt)
    return 0


if __name__ == '__main__':
    a = sys.argv[1:]
    cmd = a[0] if a else ''
    if cmd == 'meta':
        cmd_meta(a[1], a[2] if len(a) > 2 else ''); sys.exit(0)
    if cmd == 'wall':
        sys.exit(cmd_wall(a[1]))
    if cmd == 'drag-plan':
        sys.exit(cmd_drag_plan(a[1], a[2]))
    if cmd == 'focused':
        sys.exit(cmd_focused(a[1]))
    if cmd == 'score':
        sys.exit(score(a[1]))
    if cmd == 'parity':
        sys.exit(cmd_parity(a[1], a[2], a[3] if len(a) > 3 else None))
    print(__doc__); sys.exit(2)
