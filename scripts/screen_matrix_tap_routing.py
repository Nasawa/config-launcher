#!/usr/bin/env python3
"""Split-phone / short-pane tap-routing probe (#201 / #197).

Static analysis + tap plan for live driver (screen-matrix.sh). Covers:
  (a) T4 title-bar {page} clickable ≥ 48 dp tall
  (b) tap each tile visual centre → expected item
  (c) tap overlap band (top (48 − pitchH) dp of row N+1) → record winner
  (d) last-row extended hit must not steal from page dots / dock / gesture
  (e) tile visual sizes vs G3/T4 rules

Usage:
  scripts/screen_matrix_tap_routing.py plan <point-dir> [density]
  scripts/screen_matrix_tap_routing.py score <point-dir>
  scripts/screen_matrix_tap_routing.py analyze <point-dir>   # static only → tap-routing.md
"""
from __future__ import annotations

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import screen_matrix_check as c  # noqa: E402

MIN_TOUCH = 48.0
CHROME_DESCS = {
    'Home, Pages', 'Button',
}
CHROME_DESC_SUFFIXES = (', dock', ', widget', ', Pages')
CHROME_DESC_PREFIXES = ('Page ',)
HEADER_TEXTS = getattr(c, 'HEADER_TEXTS', {'Apps', 'Done', '⋮'})


def _is_chrome_desc(desc: str) -> bool:
    if not desc:
        return True
    if desc in CHROME_DESCS:
        return True
    if any(desc.endswith(s) for s in CHROME_DESC_SUFFIXES):
        return True
    if any(desc.startswith(p) for p in CHROME_DESC_PREFIXES):
        return True
    # clock / date spoken on title bar Views
    if re.fullmatch(r'\d{1,2}:\d{2}', desc):
        return True
    if re.search(r'\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\b', desc):
        return True
    return False


def discover_tiles(nodes, tb, screen, s):
    """Tiles by spoken desc child + smallest clickable holder (works when labels are hidden)."""
    W, H = screen
    top = tb['rect'][3] if tb else 0
    # Allow first-row encroachment into the title (the pre-fix case) so we still find them.
    grid_top = max(0, top - int(16 * s))
    ind = [n.b[1] for n in nodes if n.desc and re.fullmatch(r'Page \d+ of \d+', n.desc)]
    dock = [n for n in nodes if (n.desc or '').endswith(', dock')]
    is_rail, side, rl, rr = c.rail_geom(dock, W, H, nodes=nodes)
    bottom = min(ind + [H]) if is_rail else min(ind + [n.b[1] for n in dock] + [H])

    def in_rail(n):
        if not is_rail:
            return False
        return n.cx >= rl - 2 if side == 'right' else n.cx <= rr + 2

    spoken = [
        n for n in nodes
        if n.desc and not n.click and not _is_chrome_desc(n.desc)
        and n.b[1] >= grid_top and n.b[3] <= bottom + 4
        and n.w * n.h < (W * H) / 8 and not in_rail(n)
        and not (tb and c.inter(n.b, tb['rect']) > n.w * n.h * 0.5)
    ]
    clicks = [
        n for n in nodes
        if n.click and n.b[1] >= grid_top - 4 and n.b[3] <= bottom + 8
        and n.w * n.h < (W * H) / 4 and not in_rail(n)
        and not (n.desc or '').endswith(', dock')
        and n.text not in HEADER_TEXTS
    ]
    tiles = []
    used = set()
    for sp in spoken:
        holders = [
            n for n in clicks
            if id(n) not in used and (
                c.contains(n.b, sp.b, 6)
                or (n.b[0] - 4 <= sp.cx <= n.b[2] + 4 and n.b[1] - 4 <= sp.cy <= n.b[3] + 4)
            )
        ]
        holders.sort(key=lambda n: n.w * n.h)
        if not holders:
            continue
        hit = holders[0]
        used.add(id(hit))
        tiles.append({
            'name': sp.desc,
            'visual': list(sp.b),
            'hit': list(hit.b),
            'visual_dp': [round(sp.w / s, 1), round(sp.h / s, 1)],
            'hit_dp': [round(hit.w / s, 1), round(hit.h / s, 1)],
            'visual_cx': sp.cx,
            'visual_cy': sp.cy,
            'hit_top': hit.b[1],
            'hit_bottom': hit.b[3],
        })
    # Also clickables with no spoken child (empty cells aren't taps we care about)
    return tiles, dock, ind, (grid_top, bottom), is_rail


def pitch_from_tiles(tiles, s):
    if len(tiles) < 1:
        return None, None
    tops = c._cluster_1d([t['hit_top'] for t in tiles], min_gap=max(8, 0.25 * s * 48))
    cxs = c._cluster_1d([t['visual_cx'] for t in tiles], min_gap=max(8, 0.25 * s * 48))
    if len(tops) >= 2:
        gaps = [tops[i + 1] - tops[i] for i in range(len(tops) - 1)]
        pitchH = sorted(gaps)[len(gaps) // 2] / s
    else:
        hs = sorted(t['hit'][3] - t['hit'][1] for t in tiles)
        pitchH = max(hs[len(hs) // 2] / s, 1.0)
    if len(cxs) >= 2:
        gaps = [cxs[i + 1] - cxs[i] for i in range(len(cxs) - 1)]
        pitchW = sorted(gaps)[len(gaps) // 2] / s
    else:
        pitchW = tiles[0]['hit_dp'][0]
    return pitchW, pitchH, tops


def title_t4(nodes, tb, s):
    if not tb or not tb.get('page_v'):
        return {'ok': None, 'detail': 'no title bar'}
    pv = tb['page_v']
    holders = [
        n for n in nodes
        if n.click and n.b[0] <= pv.cx <= n.b[2] and n.b[1] <= pv.cy <= n.b[3]
        and n.w * n.h <= 8 * max(1, pv.w * pv.h)
    ]
    P = min(holders, key=lambda n: n.w * n.h).b if holders else pv.b
    pw, ph = (P[2] - P[0]) / s, (P[3] - P[1]) / s
    others = [
        n for n in nodes
        if n.click and c.inter(n.b, P) > 0
        and not c.contains(n.b, P, 2) and not c.contains(P, n.b, 2)
    ]
    ok = pw >= 31.5 and ph >= 47.5 and not others
    return {
        'ok': bool(ok),
        'touch_dp': [round(pw, 1), round(ph, 1)],
        'bounds': list(P),
        'intersecting': [(n.desc or n.text, list(n.b)) for n in others],
        'detail': f'touch area {pw:.1f}×{ph:.1f} dp; intersecting={len(others)}',
    }


def g3_treatment(tiles, dock, tb, s, nodes, notice_chrome):
    """How current G3 rules would treat these tiles (harness grid_tiles may miss short first row)."""
    small = []
    intobar = []
    for t in tiles:
        w, h = t['hit_dp']
        if w < 47.5 or h < 47.5:
            small.append((t['name'], w, h))
        if tb and c.inter(tuple(t['hit']), tb['rect']) > 0:
            intobar.append(t['name'])
    # Official checker path
    W = max(n.b[2] for n in nodes)
    H = max(n.b[3] for n in nodes)
    labels, gtiles, gdock, _, _ = c.grid_parts(nodes, tb, (W, H))
    official_small = [
        (n.desc or n.text or 'tile', round(n.w / s), round(n.h / s))
        for n in gtiles + gdock
        if n.w / s < 47.5 or n.h / s < 47.5
    ]
    return {
        'probe_tile_count': len(tiles),
        'harness_grid_tiles_count': len(gtiles),
        'probe_under48_hit': small,
        'probe_hit_into_title': intobar,
        'harness_under48': official_small,
        'notice_chrome': notice_chrome,
        'g3_note': (
            'Harness G3 uses grid_tiles (top ≥ title_bottom−2), so first-row hits that start '
            'inside the title band are omitted; probe lists them separately.'
        ),
    }


def build_plan(point_dir, density=None):
    home = os.path.join(point_dir, 'home1.xml')
    if not os.path.isfile(home):
        raise SystemExit(f'missing {home}')
    nodes = c.load(home)
    meta = c.meta_of(point_dir) if hasattr(c, 'meta_of') else {}
    if density is None:
        density = float(meta.get('density') or 160)
    s = float(density) / 160.0
    W, H = c.screen_of(nodes)
    tb = c.title_bar(nodes, s)
    tiles, dock, ind_tops, (grid_top, grid_bottom), is_rail = discover_tiles(nodes, tb, (W, H), s)
    pitch = pitch_from_tiles(tiles, s)
    pitchW = pitchH = None
    row_tops = []
    if pitch:
        pitchW, pitchH, row_tops = pitch

    t4 = title_t4(nodes, tb, s)
    texts = [n.text or '' for n in nodes] + [n.desc or '' for n in nodes]
    notice = any('needs your OK.' in t or 'need your OK.' in t for t in texts) or any(
        t.startswith('Pending') for t in texts
    )
    g3 = g3_treatment(tiles, dock, tb, s, nodes, notice)

    # Assign row index by nearest row top cluster
    for t in tiles:
        if row_tops:
            t['row'] = min(range(len(row_tops)), key=lambda i: abs(row_tops[i] - t['hit_top']))
        else:
            t['row'] = 0

    band_dp = max(0.0, MIN_TOUCH - (pitchH or MIN_TOUCH)) if pitchH is not None else None
    band_px = (band_dp * s) if band_dp is not None else None

    centre_taps = []
    for i, t in enumerate(tiles):
        centre_taps.append({
            'id': f'centre-{i}',
            'kind': 'centre',
            'expect': t['name'],
            'x': int(t['visual_cx']),
            'y': int(t['visual_cy']),
            'row': t['row'],
            'how': 'longpress',  # identify via sheet; also record launch if tap path used
        })

    overlap_taps = []
    if band_px and band_px >= 1 and pitchH and pitchH < MIN_TOUCH - 0.5:
        # For each tile in row N+1, tap midpoint of the overlap band (visible top of that cell)
        by_row = {}
        for t in tiles:
            by_row.setdefault(t['row'], []).append(t)
        for r, row_tiles in sorted(by_row.items()):
            if r == 0:
                continue
            prev = by_row.get(r - 1)
            if not prev:
                continue
            # Extended hit bottom of a typical prev-row tile (cell top + 48)
            # Use actual hit bottoms after the grow-down fix when available.
            for t in row_tiles:
                # Overlap band = top band_px of this tile's visual/hit top
                y0 = t['hit_top']
                y = int(y0 + band_px / 2)
                # Which prev-row tile shares column (nearest cx)?
                donor = min(prev, key=lambda p: abs(p['visual_cx'] - t['visual_cx']))
                overlap_taps.append({
                    'id': f'overlap-r{r}-{t["name"][:12]}',
                    'kind': 'overlap',
                    'x': int(t['visual_cx']),
                    'y': y,
                    'band_dp': round(band_dp, 1),
                    'row': r,
                    'visible_tile': t['name'],
                    'donor_row_tile': donor['name'],
                    'note': (
                        f'Tap in top {band_dp:.1f} dp of row {r} ({t["name"]}). '
                        f'Per #197/ux.md a tap on visible area should hit that tile; '
                        f'if {donor["name"]} (row {r-1}) wins → finding.'
                    ),
                    'how': 'longpress',
                })

    # (d) below last row extended hit
    below_taps = []
    if tiles and pitchH is not None:
        last_row = max(t['row'] for t in tiles)
        last = [t for t in tiles if t['row'] == last_row]
        # Extended hit bottom = hit_top + max(hit_h, 48dp) for short cells
        ext_bottoms = []
        for t in last:
            hit_h = t['hit'][3] - t['hit'][1]
            # After grow-down, short cells report ~48 already; use max(hit_h, 48*s) from hit_top
            ext_bottoms.append(t['hit_top'] + max(hit_h, int(MIN_TOUCH * s)))
        ext_bottom = max(ext_bottoms)
        # Page dots
        page_ind = [n for n in nodes if n.desc and re.fullmatch(r'Page \d+ of \d+', n.desc)]
        if page_ind:
            pi = page_ind[0]
            below_taps.append({
                'id': 'below-page-dots',
                'kind': 'below',
                'x': pi.cx,
                'y': pi.cy,
                'expect': 'page-indicator',  # must NOT open a tile
                'how': 'tap',
                'note': f'page dots {list(pi.b)}; last-row ext_bottom={ext_bottom}',
            })
            # Gap between extended hit and page dots (if any)
            gap_y = (ext_bottom + pi.b[1]) // 2
            if pi.b[1] > ext_bottom + 2:
                below_taps.append({
                    'id': 'below-gap-before-dots',
                    'kind': 'below',
                    'x': int(sum(t['visual_cx'] for t in last) / len(last)),
                    'y': int(gap_y),
                    'expect': 'empty-or-chrome',
                    'how': 'longpress',
                    'note': f'gap between last-row ext hit ({ext_bottom}) and dots ({pi.b[1]})',
                })
        # Dock (rail or bottom)
        if dock:
            d0 = dock[0]
            below_taps.append({
                'id': 'below-dock',
                'kind': 'below',
                'x': d0.cx,
                'y': d0.cy,
                'expect': 'dock',
                'how': 'longpress',
                'note': f'dock {d0.desc} {list(d0.b)}; must not open a grid tile',
            })
        # Gesture / system nav band: bottom 24 dp of screen, page-column centre
        below_taps.append({
            'id': 'below-gesture',
            'kind': 'below',
            'x': int(sum(t['visual_cx'] for t in last) / len(last)),
            'y': int(H - 12 * s),
            'expect': 'gesture-or-nothing',
            'how': 'tap',
            'note': f'bottom gesture/nav band; last-row ext_bottom={ext_bottom}, H={H}',
        })

    plan = {
        'point': meta.get('id') or os.path.basename(point_dir.rstrip('/')),
        'screen_px': [W, H],
        'density': density,
        'pitchW_dp': round(pitchW, 1) if pitchW else None,
        'pitchH_dp': round(pitchH, 1) if pitchH else None,
        'band_dp': round(band_dp, 1) if band_dp is not None else None,
        'title_bar_rect': list(tb['rect']) if tb else None,
        't4': t4,
        'g3': g3,
        'tiles': tiles,
        'taps': centre_taps + overlap_taps + below_taps,
    }
    return plan


def write_analyze_md(plan, path):
    lines = [
        f"# Tap-routing probe: {plan['point']}",
        '',
        f"Screen {plan['screen_px'][0]}×{plan['screen_px'][1]} @ dens {plan['density']}; "
        f"pitchW={plan['pitchW_dp']} pitchH={plan['pitchH_dp']} dp; "
        f"overlap band={plan['band_dp']} dp (48−pitchH).",
        '',
        '## (a) T4 title clickable',
        f"- {'PASS' if plan['t4'].get('ok') else 'FAIL' if plan['t4'].get('ok') is False else 'N/A'}: "
        f"{plan['t4'].get('detail')}",
        '',
        '## (e) Tile sizes vs G3/T4',
        f"- Probe tiles: {plan['g3']['probe_tile_count']}; harness grid_tiles: {plan['g3']['harness_grid_tiles_count']}",
        f"- Probe hit under 48 dp: {plan['g3']['probe_under48_hit'] or 'none'}",
        f"- Probe hit into title: {plan['g3']['probe_hit_into_title'] or 'none'}",
        f"- Harness G3 under-48 list: {plan['g3']['harness_under48'] or 'none'}",
        f"- Note: {plan['g3']['g3_note']}",
        '',
        '### Tiles',
        '| name | row | visual dp | hit dp | visual bounds | hit bounds |',
        '|---|---|---|---|---|---|',
    ]
    for t in plan['tiles']:
        lines.append(
            f"| {t['name'][:40]} | {t['row']} | {t['visual_dp'][0]}×{t['visual_dp'][1]} | "
            f"{t['hit_dp'][0]}×{t['hit_dp'][1]} | {t['visual']} | {t['hit']} |"
        )
    lines += [
        '',
        '## Tap plan',
        f"{len(plan['taps'])} taps "
        f"({sum(1 for t in plan['taps'] if t['kind']=='centre')} centre, "
        f"{sum(1 for t in plan['taps'] if t['kind']=='overlap')} overlap, "
        f"{sum(1 for t in plan['taps'] if t['kind']=='below')} below).",
        '',
    ]
    with open(path, 'w') as f:
        f.write('\n'.join(lines) + '\n')


def identify_from_dump(xml_path):
    """Return best-effort identity of a long-press sheet or launched activity dump."""
    if not os.path.isfile(xml_path):
        return None
    nodes = c.load(xml_path)
    texts = [n.text for n in nodes if n.text]
    descs = [n.desc for n in nodes if n.desc]
    # Item sheet actions
    actions = {'Edit layout', 'App info', 'Remove from home', 'Add to home', 'Hide', 'Uninstall'}
    has_sheet = bool(actions & set(texts))
    # Title-ish: first non-action TextView near top, or spoken
    skip = actions | HEADER_TEXTS | {'Cancel', 'OK', 'Wait', 'Close', 'Edit layout', 'Pages',
                                     'Reload layout', 'Settings', 'Wallpaper', 'Home menu'}
    title = next((t for t in texts if t not in skip and len(t) > 1), None)
    return {
        'has_sheet': has_sheet,
        'title': title,
        'texts': texts[:20],
        'descs': descs[:20],
    }


def score(point_dir):
    plan_path = os.path.join(point_dir, 'tap-routing-plan.json')
    if not os.path.isfile(plan_path):
        raise SystemExit(f'missing {plan_path}')
    with open(plan_path) as f:
        plan = json.load(f)
    results = []
    for tap in plan['taps']:
        tid = tap['id']
        out = {
            'id': tid,
            'kind': tap['kind'],
            'x': tap['x'],
            'y': tap['y'],
        }
        id_path = os.path.join(point_dir, f'tap-{tid}.xml')
        top_path = os.path.join(point_dir, f'tap-{tid}-top.txt')
        ident = identify_from_dump(id_path)
        top = open(top_path).read().strip() if os.path.isfile(top_path) else ''
        out['ident'] = ident
        out['top'] = top
        if tap['kind'] == 'centre':
            expect = tap['expect']
            got = (ident or {}).get('title')
            # Match expect name against sheet title or desc list
            ok = False
            if ident:
                blob = ' '.join(filter(None, [ident.get('title'), *ident.get('texts', []), *ident.get('descs', [])]))
                ok = expect.lower() in blob.lower() or (got or '').lower() == expect.lower()
            # Launch path: package in top activity
            if not ok and top and 'configlauncher' not in top.lower():
                out['launched'] = top
            out['expect'] = expect
            out['ok'] = ok
            out['finding'] = None if ok else f'expected {expect!r}, got title={got!r} top={top!r}'
        elif tap['kind'] == 'overlap':
            visible = tap['visible_tile']
            donor = tap['donor_row_tile']
            blob = ''
            if ident:
                blob = ' '.join(filter(None, [ident.get('title'), *ident.get('texts', []), *ident.get('descs', [])]))
            winner = None
            if visible.lower() in blob.lower():
                winner = visible
            elif donor.lower() in blob.lower():
                winner = donor
            out['visible_tile'] = visible
            out['donor_row_tile'] = donor
            out['band_dp'] = tap.get('band_dp')
            out['winner'] = winner
            # Finding if visible tile loses its top to donor
            out['ok'] = winner == visible
            out['finding'] = (
                None if winner == visible
                else f'overlap band {tap.get("band_dp")} dp: visible {visible!r} lost to {winner!r} '
                     f'(#197/ux.md: tap on visible area should hit that tile)'
            )
        else:  # below
            expect = tap.get('expect')
            tile_names = [t['name'] for t in plan['tiles']]
            # Home dump always lists tile spoken descs — only count a steal when a sheet
            # opened for a grid tile, or another app launched.
            launched_away = bool(top) and 'configlauncher' not in top.lower()
            sheet = bool((ident or {}).get('has_sheet'))
            title = ((ident or {}).get('title') or '')
            stole = None
            if sheet:
                # Item sheet title matching a grid tile = steal; dock sheet / home menu OK.
                home_menu = set((ident or {}).get('texts') or []) & {
                    'Reload layout', 'Wallpaper', 'Pages',
                }
                if not home_menu:
                    stole = next((n for n in tile_names if n.lower() == title.lower()
                                  or n.lower() in title.lower()), None)
                    if stole is None and title and title not in (
                        'com.android.settings', 'Settings',
                    ):
                        # Sheet with unknown title — still a steal if title looks like a tile
                        stole = next((n for n in tile_names if n.lower() in title.lower()), None)
            if launched_away and stole is None:
                stole = f'launched:{top}'
            if expect == 'dock':
                # Dock long-press should open the dock item sheet, not a grid tile.
                ok = stole is None and (sheet or launched_away or True)
                # Prefer: sheet present and not a grid tile
                ok = stole is None
            else:
                ok = stole is None
            out['expect'] = expect
            out['ok'] = ok
            out['finding'] = None if ok else f'last-row/extended hit stole tap → opened {stole!r}'
        results.append(out)

    summary = {
        'point': plan['point'],
        't4': plan['t4'],
        'pitchH_dp': plan['pitchH_dp'],
        'band_dp': plan['band_dp'],
        'g3': plan['g3'],
        'results': results,
        'centre_pass': sum(1 for r in results if r['kind'] == 'centre' and r.get('ok')),
        'centre_total': sum(1 for r in results if r['kind'] == 'centre'),
        'overlap_findings': [r for r in results if r['kind'] == 'overlap' and r.get('finding')],
        'below_findings': [r for r in results if r['kind'] == 'below' and r.get('finding')],
    }
    summary['go_bits'] = {
        't4_ok': plan['t4'].get('ok'),
        'centres_ok': summary['centre_pass'] == summary['centre_total'] and summary['centre_total'] > 0,
        'overlap_ok': len(summary['overlap_findings']) == 0,
        'below_ok': len(summary['below_findings']) == 0,
    }
    out_json = os.path.join(point_dir, 'tap-routing.json')
    with open(out_json, 'w') as f:
        json.dump({'plan': plan, 'summary': summary}, f, indent=2)
    # Markdown
    md = os.path.join(point_dir, 'tap-routing.md')
    lines = [
        f"# Tap-routing results: {plan['point']}",
        '',
        f"(a) T4: {'PASS' if plan['t4'].get('ok') else 'FAIL'} — {plan['t4'].get('detail')}",
        f"pitchH={plan['pitchH_dp']} dp; overlap band={plan['band_dp']} dp",
        f"(b) centres: {summary['centre_pass']}/{summary['centre_total']}",
        f"(c) overlap findings: {len(summary['overlap_findings'])}",
        f"(d) below-grid steals: {len(summary['below_findings'])}",
        '',
        '| id | kind | ok | detail |',
        '|---|---|---|---|',
    ]
    for r in results:
        detail = r.get('finding') or (
            f"winner={r.get('winner')}" if r['kind'] == 'overlap'
            else f"expect={r.get('expect')} title={(r.get('ident') or {}).get('title')}"
        )
        lines.append(f"| {r['id'][:40]} | {r['kind']} | {r.get('ok')} | {detail} |")
    lines += ['', '## (e) sizes', f"```json\n{json.dumps(plan['g3'], indent=2)}\n```", '']
    with open(md, 'w') as f:
        f.write('\n'.join(lines) + '\n')
    print(md)
    return 0 if summary['go_bits']['t4_ok'] and summary['go_bits']['centres_ok'] and summary['go_bits']['below_ok'] else 1


def main(argv):
    if len(argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    cmd, point_dir = argv[1], argv[2]
    if cmd == 'plan':
        dens = float(argv[3]) if len(argv) > 3 else None
        plan = build_plan(point_dir, dens)
        with open(os.path.join(point_dir, 'tap-routing-plan.json'), 'w') as f:
            json.dump(plan, f, indent=2)
        write_analyze_md(plan, os.path.join(point_dir, 'tap-routing-analyze.md'))
        # Driver-friendly: one line per tap
        taps_txt = os.path.join(point_dir, 'tap-routing-taps.tsv')
        with open(taps_txt, 'w') as f:
            f.write('id\tkind\thow\tx\ty\texpect\n')
            for t in plan['taps']:
                exp = t.get('expect') or t.get('visible_tile') or ''
                f.write(f"{t['id']}\t{t['kind']}\t{t['how']}\t{t['x']}\t{t['y']}\t{exp}\n")
        print(taps_txt)
        print(f"taps={len(plan['taps'])} pitchH={plan['pitchH_dp']} band={plan['band_dp']} t4={plan['t4'].get('ok')}")
        return 0
    if cmd == 'analyze':
        plan = build_plan(point_dir)
        write_analyze_md(plan, os.path.join(point_dir, 'tap-routing-analyze.md'))
        with open(os.path.join(point_dir, 'tap-routing-plan.json'), 'w') as f:
            json.dump(plan, f, indent=2)
        print(os.path.join(point_dir, 'tap-routing-analyze.md'))
        return 0
    if cmd == 'score':
        return score(point_dir)
    print('unknown command', cmd, file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv))
