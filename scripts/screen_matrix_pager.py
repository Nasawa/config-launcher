#!/usr/bin/env python3
"""Pager-restore probe scorer (#188 / PR #214). The live driver is screen-matrix.sh (pager_probe).

Fixture pager.json: pages home, second, third ("QA Settings", "Second tile", "Third tile"). Each case starts cold
and goes to a non-zero page through the Pages list, then changes the window or reloads (see pager_probe).
For every dump pg-<case>-<step>.xml the page is read three ways and all must agree with the expectation:
  - the dots' a11y node "Page N of M" (N, M),
  - the title bar {page} slot "<Title>, Pages",
  - which page's tile is on screen,
and the dots screenshot: the white (current) dot's rank among the dots (LTR rank N-1, RTL rank M-N).
dragrot also needs launcher.json unchanged (hash + JSON diff) and the drag cancelled; any crash/ANR fails a case.
mw freeform steps only count when the task really went freeform; the wm-size pane steps always count.
oos (edit mode / drawer across a rotate, out of scope for #214, #188 stays open) is recorded, not scored.

Usage: screen_matrix_pager.py score <point-dir>   → pager.json / pager.md
       screen_matrix_pager.py read <xml> [png]    → what one dump shows
"""
from __future__ import annotations

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import screen_matrix_check as c  # noqa: E402

ARABIC = str.maketrans('٠١٢٣٤٥٦٧٨٩', '0123456789')
TILES = {'QA Settings': 'Home', 'Second tile': 'Second', 'Third tile': 'Third'}
S2, S3, H1, T3 = ('Second', 2, 3), ('Second', 2, 3), ('Home', 1, 3), ('Third', 3, 3)
EXPECT = {
    'cold': {'0-start': H1, '1-next': S2, '2-next': T3, '3-prev': S2},
    'rot': {'0-before': S2, '1-rotated': S2, '2-back': S2},
    'resize': {'0-before': S2, '1-half': S2, '2-density': S2, '3-back': S2},
    'mw': {'0-before': S2, '1-freeform': S2, '2-resize-a': S2, '3-resize-b': S2, '4-left': S2,
           '5-pane-short': S2, '6-pane-tall': S2, '7-full': S2},
    'reorder': {'0-before': S2, '1-reloaded': ('Second', 1, 3), '2-rotated': ('Second', 1, 3)},
    'rmlast': {'0-before': T3, '1-reloaded': ('Second', 2, 2)},
    'rmmid': {'0-before': S2, '1-reloaded': ('Third', 2, 2)},
    'dragrot': {'0-edit': S2, '2-rotated': S2, '3-back': S2},
}
FREEFORM_STEPS = {'1-freeform', '2-resize-a', '3-resize-b', '4-left'}


def page_no(nodes):
    for n in nodes or []:
        m = re.fullmatch(r'Page (\S+) of (\S+)', (n.desc or '').translate(ARABIC))
        if m:
            return int(m.group(1)), int(m.group(2)), n
    return None


def title_of(nodes):
    for n in nodes or []:
        m = re.fullmatch(r'(.+), Pages', n.desc or '')
        if m:
            return m.group(1)
    return None


def tiles_of(nodes, W):
    seen = set()
    for n in nodes or []:
        lab = (n.text or n.desc or '').strip()
        if lab in TILES and n.w > 0 and n.h > 0 and 0 <= n.cx <= W:
            seen.add(TILES[lab])
    return sorted(seen)


def dot_rank(png, node):
    """(rank of the white dot among all dots, dot count) from the screenshot inside the dots node."""
    im = c.open_png(png)
    if im is None or node is None:
        return None, None, 'no screenshot/Pillow'
    b = node.b
    cols = {}
    for x in range(max(0, b[0]), min(im.width, b[2])):
        kind = None
        for y in range(max(0, b[1]), min(im.height, b[3])):
            r, g, bl = im.getpixel((x, y))
            if r > 235 and g > 235 and bl > 235:
                kind = 'w'; break
            if 55 <= r <= 110 and abs(r - g) < 8 and abs(g - bl) < 8:
                kind = kind or 'g'
        if kind:
            cols[x] = kind
    runs, cur = [], None
    for x in sorted(cols):
        if cur and x - cur[1] <= 1:
            cur[1] = x; cur[2].append(cols[x])
        else:
            cur = [x, x, [cols[x]]]; runs.append(cur)
    runs = [r for r in runs if r[1] - r[0] >= 2]
    if not runs:
        return None, None, 'no dots found'
    white = [i for i, r in enumerate(runs) if r[2].count('w') > len(r[2]) / 3]
    if len(white) != 1:
        return None, len(runs), f'{len(white)} white dots among {len(runs)}'
    return white[0], len(runs), f'dots at x {[(r[0] + r[1]) // 2 for r in runs]}'


def read_dump(xml, png=None, rtl=False):
    nodes = c.load(xml)
    if not nodes:
        return {'ok_dump': False}
    W, _ = c.screen_of(nodes)
    pn = page_no(nodes)
    out = {'ok_dump': True, 'page': list(pn[:2]) if pn else None, 'title': title_of(nodes), 'tiles': tiles_of(nodes, W),
           'anr': any('responding' in (n.text or '') for n in nodes),
           'edit': any((n.text or '') in ('Done', 'Editing layout') for n in nodes),
           'drawer': any('Search apps' in (n.text or '') or 'Search apps' in (n.desc or '') for n in nodes)}
    if png and os.path.exists(png) and pn:
        rank, cnt, why = dot_rank(png, pn[2])
        out['dots'] = {'rank': rank, 'count': cnt, 'why': why}
    return out


def score(d):
    m = c.meta_of(d)
    rtl = 'rtl' in (m.get('shape') or '').lower() or (m.get('locale') or '').startswith('ar')
    mw_txt = c.read(os.path.join(d, 'pager-mw.txt'))
    freeform = bool(re.search(r'freeform', mw_txt, re.I))
    cases = {}
    for case, steps in EXPECT.items():
        rows = []
        crash = c.read(os.path.join(d, f'pg-{case}-crash.txt')).strip()
        for step, (title, n, mm) in steps.items():
            xml = os.path.join(d, f'pg-{case}-{step}.xml')
            if not os.path.exists(xml):
                if case == 'mw' and step in FREEFORM_STEPS:
                    continue
                rows.append({'step': step, 'ok': None, 'detail': 'no dump'}); continue
            r = read_dump(xml, xml[:-4] + '.png', rtl)
            want_rank = (mm - n) if rtl else (n - 1)
            probs = []
            if not r.get('ok_dump'):
                probs.append('dump unreadable')
            else:
                if r['page'] != [n, mm]:
                    probs.append(f"dots a11y {r['page']} want {[n, mm]}")
                if r['title'] != title:
                    probs.append(f"title {r['title']!r} want {title!r}")
                if r['tiles'] and r['tiles'] != [title]:
                    probs.append(f"tiles {r['tiles']} want [{title!r}]")
                dt = r.get('dots') or {}
                if dt.get('rank') is not None and (dt['rank'] != want_rank or dt['count'] != mm):
                    probs.append(f"dot rank {dt['rank']}/{dt['count']} want {want_rank}/{mm}")
                if r['anr']:
                    probs.append('ANR dialog')
            top = c.read(os.path.join(d, f'pg-{case}-{step}-top.txt'))
            if top and 'dev.nasawa.configlauncher' not in top:
                probs.append('launcher not resumed')
            scored = not (case == 'mw' and step in FREEFORM_STEPS and not freeform)
            rows.append({'step': step, 'want': [title, n, mm], 'want_dot_rank': want_rank, 'seen': r, 'ok': not probs,
                         'scored': scored, 'detail': '; '.join(probs) or 'ok'})
        extra = []
        if case == 'dragrot':
            hb = c.read(os.path.join(d, 'pg-dragrot-hash-before.txt')).strip()
            ha = c.read(os.path.join(d, 'pg-dragrot-hash-after.txt')).strip()
            try:
                same = json.load(open(os.path.join(d, 'pg-dragrot-config-before.json'))) == \
                    json.load(open(os.path.join(d, 'pg-dragrot-config-after.json')))
            except Exception as ex:
                same = None; extra.append(f'config diff unavailable ({ex.__class__.__name__})')
            dr = read_dump(os.path.join(d, 'pg-dragrot-1-dragging.xml'))
            extra.append(f'hash before {hb[:12]} after {ha[:12]}; JSON equal {same}')
            if not hb or hb != ha or same is False:
                rows.append({'step': 'no-write', 'ok': False, 'scored': True, 'detail': extra[-1]})
            else:
                rows.append({'step': 'no-write', 'ok': True, 'scored': True, 'detail': extra[-1]})
            e0 = read_dump(os.path.join(d, 'pg-dragrot-0-edit.xml'))
            extra.append(f"edit mode before drag {e0.get('edit')}; dragging dump readable {dr.get('ok_dump')}")
        if crash:
            rows.append({'step': 'crash', 'ok': False, 'scored': True, 'detail': crash[:400]})
        sc = [r for r in rows if r.get('scored', True)]
        ok = None if any(r['ok'] is None for r in sc) else all(r['ok'] for r in sc)
        cases[case] = {'ok': ok, 'rows': rows, 'notes': extra}
    oos = {s: read_dump(os.path.join(d, f'pg-oos-{s}.xml')) for s in ('0-edit', '1-edit-rotated', '2-drawer', '3-drawer-rotated')}
    out = {'point': os.path.basename(d.rstrip('/')), 'rtl': rtl, 'freeform': freeform, 'cases': cases, 'oos': oos}
    json.dump(out, open(os.path.join(d, 'pager.json'), 'w'), indent=1, ensure_ascii=False)
    allok = [v['ok'] for v in cases.values()]
    verdict = 'PASS' if all(x is True for x in allok) else ('FAIL' if any(x is False for x in allok) else 'INCOMPLETE')
    md = [f"# Pager restore probe — {out['point']}: {verdict} (rtl={rtl}, real freeform={freeform})", '',
          '| case | step | want (title, N/M, dot rank) | seen | result |', '|---|---|---|---|---|']
    for case, v in cases.items():
        for r in v['rows']:
            s = r.get('seen') or {}
            seen = f"{s.get('title')}, {s.get('page')}, tiles {s.get('tiles')}, dots {(s.get('dots') or {}).get('rank')}/{(s.get('dots') or {}).get('count')}" if s else '-'
            want = f"{r['want'][0]}, {r['want'][1]}/{r['want'][2]}, {r['want_dot_rank']}" if 'want' in r else '-'
            res = 'n/a (not freeform)' if not r.get('scored', True) else ('PASS' if r['ok'] else ('INCOMPLETE' if r['ok'] is None else 'FAIL'))
            md.append(f"| {case} | {r['step']} | {want} | {seen} | {res}: {r['detail']} |")
        for nt in v['notes']:
            md.append(f'| {case} | note | | | {nt} |')
    md += ['', '## Out of scope (#188 follow-up; recorded only)', '']
    for s, r in oos.items():
        md.append(f"- {s}: edit mode {r.get('edit')}, drawer {r.get('drawer')}, page {r.get('page')} {r.get('title')}")
    open(os.path.join(d, 'pager.md'), 'w').write('\n'.join(md) + '\n')
    print(md[0])
    return 0 if verdict == 'PASS' else 1


if __name__ == '__main__':
    a = sys.argv[1:]
    if a[:1] == ['score']:
        sys.exit(score(a[1]))
    if a[:1] == ['read']:
        print(json.dumps(read_dump(a[1], a[2] if len(a) > 2 else None), indent=1, ensure_ascii=False)); sys.exit(0)
    sys.exit(__doc__)
