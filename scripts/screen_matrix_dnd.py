#!/usr/bin/env python3
"""Scripted drag-and-drop + pager-direction probe (#187 / PR #210, test-plan §1.5 RTL).

The live driver is screen-matrix.sh (dnd_probe). This file plans gestures from an edit-mode
dump and scores what happened. All targets are *physical* screen points; the expected config
result depends on layout direction, so the same physical drop must give a mirrored column in RTL.

Cases (matrix fixture launcher.json: 4x5 grid, "Row two" at (0,1), one dock item d-settings):
  grid        drag Row two to physical column 1 (2nd from the left), row 2 → LTR x=1, RTL x=2
  in-left     drop 3 dp inside the block's physical left edge, row 2  → LTR x=0, RTL x=3
  in-right    drop 3 dp inside the block's physical right edge, row 2 → LTR x=3, RTL x=0
  out-left    drop (pad+4) dp outside the block's left edge  → cancelled: Row two stays (0,1)
  out-right   same on the right                              → cancelled
  lp-margin   long-press in the left margin                  → home menu (Edit layout)
  dock-left   bottom dock only: drop at 15 % of the dock     → LTR index 0, RTL index 1
  dock-right  bottom dock only: drop at 85 % of the dock     → LTR index 1, RTL index 0
  rail-top    rail only: drop on the rail at dock[0]'s Y     → index 0 (both directions)
  rail-bottom rail only: drop low on the rail                → index 1 (both directions)
Pager (non-edit home):
  pg-back     swipe toward "previous" from page 0 → still page 1
  pg-fwd      swipe toward "next" (RTL: finger left→right; LTR: right→left) → page 2
  Dot order: PageDots draws the current dot white, others dark grey; score reads the PNGs when
  Pillow is available (else 'dots' can be run offline).

Usage:
  screen_matrix_dnd.py plan <point-dir> <density>     # reads dnd-edit.xml → dnd-cases.tsv
  screen_matrix_dnd.py score <point-dir>              # → dnd.json / dnd.md
  screen_matrix_dnd.py dots <png> <xml>               # dot order from one screenshot
"""
from __future__ import annotations

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import screen_matrix_check as c  # noqa: E402

FIXTURE_ITEMS = ['QA Settings', 'A very long label that must ellipsize', 'Missing', 'Wide WWWW', 'Row two']
DRAG = 'Row two'
ARABIC_DIGITS = str.maketrans('٠١٢٣٤٥٦٧٨٩', '0123456789')


def is_rtl(m):
    loc = (m.get('locale') or '').lower()
    return 'rtl' in (m.get('shape') or '').lower() or loc.startswith('ar')


def label_of(n):
    return (n.text or n.desc or '').strip()


def holders(nodes):
    """{label: clickable cell holder} for fixture tiles (labels may be text or desc)."""
    clicks = [n for n in nodes if n.click]
    out = {}
    for lab in FIXTURE_ITEMS:
        sp = next((n for n in nodes if label_of(n) == lab and not n.click and n.w > 0), None)
        if sp is None:
            continue
        hs = [h for h in clicks if c.contains(h.b, sp.b, 6)]
        hs.sort(key=lambda h: h.w * h.h)
        if hs:
            out[lab] = hs[0]
    return out


def dock_nodes(nodes):
    return [n for n in nodes if (n.desc or '').endswith(', dock')]


def plan(d, dens):
    s = float(dens) / 160.0
    m = c.meta_of(d)
    rtl = is_rtl(m)
    nodes = c.load(os.path.join(d, 'dnd-edit.xml'))
    if not nodes:
        return {'error': 'no dnd-edit.xml'}
    W, H = c.screen_of(nodes)
    hs = holders(nodes)
    row0 = [hs[k] for k in FIXTURE_ITEMS[:4] if k in hs]
    if DRAG not in hs or len(row0) < 2:
        return {'error': f'fixture tiles not found ({sorted(hs)})'}
    left = min(h.b[0] for h in row0); right = max(h.b[2] for h in row0)
    pw = (right - left) / 4.0
    top = min(h.b[1] for h in row0)
    ph = hs[DRAG].b[1] - top if hs[DRAG].b[1] > top else row0[0].h
    dock = dock_nodes(nodes)
    is_rail, side, rl, rr = c.rail_geom(dock, W, H, nodes=nodes)
    width_dp = W / s
    compact = (not is_rail) and width_dp < 600
    pad = 8 * s if compact else 0.0           # Compact: 8 dp block padding inside gridRect; capped: none
    y2 = int(top + 2.5 * ph)
    src = hs[DRAG]
    x1, y1 = src.cx, src.cy
    # page column for margin sanity
    col_l, col_r = 0, W
    if is_rail:
        if side == 'left':
            col_l = rr
        else:
            col_r = rl
    cases = []

    def drag(cid, x, y, expect):
        cases.append({'id': cid, 'how': 'drag', 'x1': x1, 'y1': y1, 'x2': int(x), 'y2': int(y), 'expect': expect})

    cols = 4
    def phys_to_cfg(pc):
        return cols - 1 - pc if rtl else pc
    drag('grid', left + 1.5 * pw, y2, {'cell': [phys_to_cfg(1), 2]})
    drag('in-left', left - pad + 3 * s, y2, {'cell': [phys_to_cfg(0), 2]})
    drag('in-right', right + pad - 3 * s, y2, {'cell': [phys_to_cfg(3), 2]})
    for cid, x in (('out-left', left - pad - 4 * s), ('out-right', right + pad + 4 * s)):
        if col_l + 2 <= x <= col_r - 2:
            drag(cid, x, y2, {'cancel': True})
        else:
            cases.append({'id': cid, 'how': 'na', 'why': f'no side margin (x={x:.0f} outside page column {col_l}..{col_r})'})
    xm = left - pad - 4 * s
    if col_l + 2 <= xm <= col_r - 2:
        cases.append({'id': 'lp-margin', 'how': 'hold', 'x1': int(xm), 'y1': y2, 'x2': int(xm), 'y2': y2, 'expect': {'menu': True}})
    else:
        cases.append({'id': 'lp-margin', 'how': 'na', 'why': 'no side margin'})
    if dock:
        d0 = dock[0]
        if is_rail:
            drag('rail-top', d0.cx, d0.cy, {'dock': 0})
            drag('rail-bottom', d0.cx, int(H * 0.88), {'dock': 1})
        else:
            dl, dr = col_l + 8 * s, col_r - 8 * s
            drag('dock-left', dl + 0.15 * (dr - dl), d0.cy, {'dock': 1 if rtl else 0})
            drag('dock-right', dl + 0.85 * (dr - dl), d0.cy, {'dock': 0 if rtl else 1})
    # pager: horizontal swipe on row 3 (empty), 300 ms
    ys = int(top + 3.5 * ph)
    a, b = int(col_l + (col_r - col_l) * 0.2), int(col_l + (col_r - col_l) * 0.8)
    fwd = (a, b) if rtl else (b, a)        # RTL: page 1 is to the left → finger moves left→right
    cases.append({'id': 'pg-back', 'how': 'swipe', 'x1': fwd[1], 'y1': ys, 'x2': fwd[0], 'y2': ys, 'expect': {'page': 1}})
    cases.append({'id': 'pg-fwd', 'how': 'swipe', 'x1': fwd[0], 'y1': ys, 'x2': fwd[1], 'y2': ys, 'expect': {'page': 2}})
    p = {'rtl': rtl, 'screen': [W, H], 'density': dens, 'block_px': [left, top, right], 'pitch_px': [pw, ph],
         'compact': compact, 'pad_px': pad, 'rail': bool(is_rail), 'rail_side': side,
         'dock0': list(dock[0].b) if dock else None, 'src': [x1, y1], 'cases': cases}
    json.dump(p, open(os.path.join(d, 'dnd-plan.json'), 'w'), indent=1)
    with open(os.path.join(d, 'dnd-cases.tsv'), 'w') as f:
        f.write('id\thow\tx1\ty1\tx2\ty2\n')
        for k in cases:
            if k['how'] != 'na':
                f.write(f"{k['id']}\t{k['how']}\t{k['x1']}\t{k['y1']}\t{k['x2']}\t{k['y2']}\n")
    return p


def cfg_loc(cfg, item_id='a-set3'):
    for pg in cfg.get('pages', []):
        for it in pg.get('items', []):
            if it.get('id') == item_id:
                return ('page', pg.get('id'), it.get('x'), it.get('y'))
    for i, it in enumerate(cfg.get('dock') or []):
        if it.get('id') == item_id:
            return ('dock', i, len(cfg['dock']))
    return None


def page_no(nodes):
    for n in nodes or []:
        mm = re.fullmatch(r'Page (\S+) of (\S+)', (n.desc or '').translate(ARABIC_DIGITS))
        if mm:
            return int(mm.group(1)), int(mm.group(2)), n
    return None


def dots(png, xml):
    """'right'|'left'|None: where the white (current) dot sits relative to the grey dot(s)."""
    try:
        from PIL import Image
    except Exception:
        return None, 'no Pillow'
    nodes = c.load(xml)
    pn = page_no(nodes)
    if not pn:
        return None, 'no Page N of M node'
    b = pn[2].b
    im = Image.open(png).convert('RGB')
    wh, gr = [], []
    for y in range(max(0, b[1]), min(im.height, b[3])):
        for x in range(max(0, b[0]), min(im.width, b[2])):
            r, g, bl = im.getpixel((x, y))
            if r > 235 and g > 235 and bl > 235:
                wh.append(x)
            elif 55 <= r <= 80 and abs(r - g) < 6 and abs(g - bl) < 6:
                gr.append(x)
    if not wh or not gr:
        return None, f'white {len(wh)} grey {len(gr)} px'
    wx, gx = sum(wh) / len(wh), sum(gr) / len(gr)
    return ('right' if wx > gx else 'left'), f'white x≈{wx:.0f}, grey x≈{gx:.0f}'


def score(d):
    p = json.load(open(os.path.join(d, 'dnd-plan.json')))
    rtl = p['rtl']
    seed = json.load(open(os.path.join(c.FIXDIR, 'launcher.json')))
    seed_loc = cfg_loc(seed)
    res = []
    for k in p['cases']:
        r = {'id': k['id'], 'how': k['how'], 'expect': k.get('expect'), 'at': [k.get('x2'), k.get('y2')]}
        if k['how'] == 'na':
            r.update(ok=None, detail=k['why']); res.append(r); continue
        crash = c.read(os.path.join(d, f"dnd-{k['id']}-crash.txt")).strip()
        top = c.read(os.path.join(d, f"dnd-{k['id']}-top.txt"))
        alive = 'dev.nasawa.configlauncher' in top
        after = c.load(os.path.join(d, f"dnd-{k['id']}-after.xml"))
        e = k['expect']
        if k['how'] in ('drag',):
            try:
                cfg = json.load(open(os.path.join(d, f"dnd-{k['id']}-config.json")))
            except Exception as ex:
                r.update(ok=None, detail=f'no saved config ({ex.__class__.__name__})'); res.append(r); continue
            loc = cfg_loc(cfg)
            r['saved'] = loc
            ui = holders(after or []).get(DRAG)
            r['ui_at'] = [ui.cx, ui.cy] if ui else None
            if 'cell' in e:
                ok = loc is not None and loc[0] == 'page' and loc[1] == 'home' and [loc[2], loc[3]] == e['cell']
                # UI cross-check: the holder sits in the expected cell's *physical* column (RTL mirrored).
                # Compare with that column's centre, not the finger x: an edge drop in the compact 8 dp
                # inner padding still lands in the edge column, whose centre is > pitch/2 from the finger.
                pc = (3 - e['cell'][0]) if rtl else e['cell'][0]
                col_cx = p['block_px'][0] + (pc + 0.5) * p['pitch_px'][0]
                ui_ok = ui is not None and abs(ui.cx - col_cx) <= p['pitch_px'][0] / 2
                r['ui_ok'] = ui_ok
                det = (f"saved {loc}; want cell {e['cell']}; UI Row two at {r['ui_at']} (drop x {k['x2']}; "
                       f"physical col {pc} cx≈{col_cx:.0f}) ui_ok={ui_ok}")
                ok = ok and ui_ok
            elif 'cancel' in e:
                ok = loc == seed_loc
                det = f"saved {loc}; want unchanged {seed_loc}"
            else:
                ok = loc is not None and loc[0] == 'dock' and loc[1] == e['dock']
                det = f"saved {loc}; want dock index {e['dock']} (rtl={rtl})"
        elif k['how'] == 'hold':
            ok = after is not None and c.find(after, 'text', r'^Edit layout$') is not None
            det = f"home menu (Edit layout) shown: {ok}"
        else:  # swipe
            pn = page_no(after)
            ok = pn is not None and pn[0] == e['page']
            sec = after is not None and c.find(after, 'text', r'^Second tile$|^Second tile') is not None or \
                after is not None and c.find(after, 'desc', r'^Second tile') is not None
            det = f"page {pn[:2] if pn else None}; want {e['page']}; 'Second tile' visible {sec}"
            png = os.path.join(d, f"dnd-{k['id']}-after.png")
            if os.path.exists(png):
                side, why = dots(png, os.path.join(d, f"dnd-{k['id']}-after.xml"))
                # current dot: RTL page1 → right, page2 → left; LTR mirrored
                want = ('right' if e['page'] == 1 else 'left') if rtl else ('left' if e['page'] == 1 else 'right')
                r['dots'] = {'current_side': side, 'want': want, 'why': why}
                det += f"; current dot {side} (want {want}; {why})"
                if side is not None and side != want:
                    ok = False
        if crash:
            ok = False; det += f'; CRASH/ANR: {crash[:200]}'
        if not alive:
            det += '; launcher not resumed after gesture'
            ok = False if k['how'] != 'hold' else ok
        r.update(ok=ok, detail=det)
        res.append(r)
    out = {'rtl': rtl, 'rail': p['rail'], 'rail_side': p['rail_side'], 'results': res}
    json.dump(out, open(os.path.join(d, 'dnd.json'), 'w'), indent=1)
    sym = {True: 'PASS', False: 'FAIL', None: 'n/a'}
    lines = [f"# DnD / pager probe — {os.path.basename(d.rstrip('/'))} (rtl={rtl}, rail={p['rail']} {p['rail_side'] or ''})", '',
             f"block px {p['block_px']}, pitch px {[round(v, 1) for v in p['pitch_px']]}, compact {p['compact']}", '',
             '| case | result | detail |', '|---|---|---|']
    for r in res:
        lines.append(f"| {r['id']} | {sym[r['ok']]} | {r['detail']} |")
    open(os.path.join(d, 'dnd.md'), 'w').write('\n'.join(lines) + '\n')
    print(lines[0]); [print(f"  {r['id']}: {sym[r['ok']]} — {r['detail']}") for r in res]
    return out


if __name__ == '__main__':
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'plan':
        p = plan(sys.argv[2], sys.argv[3]); print(json.dumps({k: v for k, v in p.items() if k != 'cases'}))
        sys.exit(1 if 'error' in p else 0)
    elif cmd == 'score':
        score(sys.argv[2])
    elif cmd == 'dots':
        print(dots(sys.argv[2], sys.argv[3]))
    else:
        print(__doc__); sys.exit(2)
