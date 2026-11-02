#!/usr/bin/env python3
"""Drop spring-back probe helper (#212, PR #218).

  targets <xml> <png> <rtl 0|1> <density>   shell assignments for the drop probe (run on CI, needs Pillow)

Fixture drop.json: page 1 "Drag" holds "Drag A" at (0,0) and "Drag B" at (3,4) on a 4x5 grid, so the column and
row pitch come from the two tile centres. A tile's rect is its icon (blue settings gear found in the screenshot)
united with its label; the probe presses the icon centre, and DragController drops at
startRect.center + finger displacement, so a finger target F = wanted drop point + (icon centre - tile centre).
Targets that do not exist on a layout (no side margin on an uncapped phone, no gap above the dock) print empty.
"""
from __future__ import annotations

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import screen_matrix_check as c  # noqa: E402


def blue_mask(png):
    from PIL import Image
    im = Image.open(png).convert('RGB')
    W, H = im.size
    px = im.load()
    return im, W, H, px


def is_gear(p):
    r, g, b = p
    return b > 200 and r < 110 and 100 < g < 170


def icon_box(img, lb, scan_up):
    """bbox of gear pixels in a column above (or inside, for a desc-only node) label box lb."""
    im, W, H, px = img
    l, t, r, b = lb
    w = r - l
    x0, x1 = max(0, l - w // 2), min(W, r + w // 2)
    y0, y1 = (max(0, t - scan_up), t) if scan_up else (t, b)
    xs, ys = [], []
    for y in range(y0, y1, 2):
        for x in range(x0, x1, 2):
            if is_gear(px[x, y]):
                xs.append(x); ys.append(y)
    if len(xs) < 20:
        return None
    return (min(xs), min(ys), max(xs) + 1, max(ys) + 1)


def node(nodes, rx):
    for n in nodes:
        if re.search(rx, n.text or '') or re.search(rx, n.desc or ''):
            return n
    return None


def tile(nodes, img, label, dpx):
    n = None
    for x in nodes:
        if (x.text or '') == label:
            n = x; break
    hidden = False
    if n is None:
        for x in nodes:
            if (x.desc or '').startswith(label):
                n = x; hidden = True; break
    if n is None:
        return None
    ib = icon_box(img, n.b, 0 if hidden else int(90 * dpx))
    if ib is None:
        return None
    u = ib if hidden else (min(ib[0], n.b[0]), ib[1], max(ib[2], n.b[2]), n.b[3])
    # DragController.drop() uses the tile node's own rect (rects[key] = boundsInRoot of the .tile() node, the
    # same node as the merged clickable), not the icon+label union. On tall cells that centre sits well below
    # the icon (p411-a51-v1-a36: 76 px), so use the smallest clickable node that contains the label.
    best = None
    for x in nodes:
        if x.click and x is not n and x.b[0] <= n.b[0] and x.b[1] <= n.b[1] and x.b[2] >= n.b[2] and x.b[3] >= n.b[3]:
            a = (x.b[2] - x.b[0]) * (x.b[3] - x.b[1])
            if a < W_LIMIT * (n.b[2] - n.b[0]) * (n.b[3] - n.b[1]) and (best is None or a < best[0]):
                best = (a, x.b)
    if hidden and n.click:
        best = (0, n.b)
    if best is not None:
        u = best[1]
    return {'icon': ((ib[0] + ib[2]) // 2, (ib[1] + ib[3]) // 2), 'iw': ib[2] - ib[0],
            'c': ((u[0] + u[2]) // 2, (u[1] + u[3]) // 2), 'rect': u}


W_LIMIT = 60  # a tile node is at most ~60x its label's area (rules out page/pager containers)


def targets(xml, png, rtl, dens):
    nodes = c.load(xml) or []
    dpx = dens / 160.0
    img = blue_mask(png)
    W, H = img[1], img[2]
    out = {'W': W, 'H': H}
    A, B = tile(nodes, img, 'Drag A', dpx), tile(nodes, img, 'Drag B', dpx)
    if not A or not B:
        out['ERR'] = 'tiles'
        return out
    pw = abs(B['c'][0] - A['c'][0]) / 3.0
    ph = (B['c'][1] - A['c'][1]) / 4.0
    out.update(AX=A['icon'][0], AY=A['icon'][1], BX=B['icon'][0], BY=B['icon'][1], PW=int(pw), PH=int(ph),
               AIW=A['iw'])
    da = (A['icon'][0] - A['c'][0], A['icon'][1] - A['c'][1])
    db = (B['icon'][0] - B['c'][0], B['icon'][1] - B['c'][1])
    fa = lambda x, y: (int(x + da[0]), int(y + da[1]))  # finger target for drop point (x,y) dragging A
    fb = lambda x, y: (int(x + db[0]), int(y + db[1]))
    left = min(A['c'][0], B['c'][0]) - pw / 2
    right = max(A['c'][0], B['c'][0]) + pw / 2
    pad = 8 * dpx + 4 * dpx  # gridRect may include 8 dp padding outside the cells (uncapped); keep 4 dp clear
    row_y = A['c'][1]
    dock = node(nodes, r', dock$')
    rail = None
    if dock is not None and (dock.cy if hasattr(dock, 'cy') else (dock.b[1] + dock.b[3]) // 2) < H * 0.6:
        dcx = (dock.b[0] + dock.b[2]) // 2
        if dcx < W * 0.25:
            rail = ('left', dock.b[2] + int(16 * dpx))
        elif dcx > W * 0.75:
            rail = ('right', dock.b[0] - int(16 * dpx))
    out['RAIL'] = rail[0] if rail else ''
    lo = rail[1] if rail and rail[0] == 'left' else 0
    hi = rail[1] if rail and rail[0] == 'right' else W
    mL = (lo, left - pad); mR = (right + pad, hi)
    def mid(m):
        return int((m[0] + m[1]) / 2) if m[1] - m[0] >= 12 * dpx else None
    near = mR if rtl else mL  # A sits at the start column
    for k, m in (('SIDE_NEAR', near), ('SIDE_LEFT', mL), ('SIDE_RIGHT', mR)):
        x = mid(m)
        if x is not None:
            out[k + '_X'], out[k + '_Y'] = fa(x, row_y)
            out[k + '_TOL'] = int((m[1] - m[0]) / 2)
    t = node(nodes, r', Pages$')
    if t is not None:
        out['TITLE_X'], out['TITLE_Y'] = fa((t.b[0] + t.b[2]) / 2, (t.b[1] + t.b[3]) / 2)
        out['TITLE_TOL'] = (t.b[3] - t.b[1]) // 2
    dots = node(nodes, r'^Page \S+ of \S+$')
    if dots is not None:
        out['DOTS_X'], out['DOTS_Y'] = fa(W / 2 if not rail else (dots.b[0] + dots.b[2]) / 2, (dots.b[1] + dots.b[3]) / 2)
        out['DOTS_TOL'] = (dots.b[3] - dots.b[1]) // 2
        if dock is not None and not rail:
            box_top = dock.b[1] - int(6 * dpx)  # dockRect = the rounded box; its 6 dp padding sits above the item
            g = (dots.b[3], box_top)
            if g[1] - g[0] >= 6:
                out['GAP_X'], out['GAP_Y'] = fa(W / 2 + pw / 2, (g[0] + g[1]) / 2)  # off the dock item's x
                out['GAP_TOL'] = (g[1] - g[0]) // 2
    if dock is not None:
        dx, dy = (dock.b[0] + dock.b[2]) // 2, (dock.b[1] + dock.b[3]) // 2
        out['DOCKI_X'], out['DOCKI_Y'] = dx, dy
        if rail:
            out['DOCK_X'], out['DOCK_Y'] = fa(dx, dock.b[3] + int(8 * dpx))  # below the dock item, inside the rail
        else:
            out['DOCK_X'], out['DOCK_Y'] = fa(dock.b[2] + int(12 * dpx), dy)  # beside it, inside the dock box
    s = -1 if rtl else 1
    # valid cells: A -> (1,1); RTL valid "toward the left" = A -> (1,0) (physical -x); B -> (2,4)
    out['CELL11_X'], out['CELL11_Y'] = fa(A['c'][0] + s * pw, A['c'][1] + ph)
    out['CELL10_X'], out['CELL10_Y'] = fa(A['c'][0] + s * pw, A['c'][1])
    out['B24_X'], out['B24_Y'] = fb(B['c'][0] - s * pw, B['c'][1])
    # pager swipe across the middle of the page, toward the next page
    sy = int(A['c'][1] + 2 * ph)
    # Same gesture the #214 cold-start check uses (80% -> 20% of the width, 300 ms): run 41498516476's 150 ms
    # swipe across the grid block (~38% of a wide screen) did not page on fold-open-v1 / rtl-t7-land-v1 even as a control.
    x1, x2 = int(W * 0.8), int(W * 0.2)
    if rtl:
        x1, x2 = x2, x1
    out.update(SW_X1=x1, SW_X2=x2, SW_Y=sy)
    return out


def main(argv):
    if len(argv) >= 6 and argv[1] == 'targets':
        o = targets(argv[2], argv[3], argv[4] == '1', int(argv[5]))
        for k, v in o.items():
            print(f'{k}={v}')
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv))
