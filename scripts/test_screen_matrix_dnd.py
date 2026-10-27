#!/usr/bin/env python3
"""Unit tests for the DnD / pager planner (screen_matrix_dnd.py) and the R1 RTL grid-mirror check (#187)."""
import json
import os
import re
import shutil
import tempfile
import unittest

import screen_matrix_check as c
import screen_matrix_dnd as dnd

TD = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'testdata', 'matrix-rail')


def mirror_xml(src, dst, W):
    """Reflect every node's x bounds (a stand-in for an RTL draw of the same layout)."""
    s = open(src, encoding='utf-8').read()

    def f(mm):
        x1, y1, x2, y2 = map(int, mm.groups())
        return f'bounds="[{W - x2},{y1}][{W - x1},{y2}]"'
    open(dst, 'w', encoding='utf-8').write(re.sub(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"', f, s))


class DndPlan(unittest.TestCase):
    def setUp(self):
        self.t = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.t)

    def _point(self, name, rtl):
        d = os.path.join(self.t, name + ('-rtl' if rtl else ''))
        os.makedirs(d)
        src = os.path.join(TD, name)
        meta = open(os.path.join(src, 'meta.txt')).read()
        if rtl:
            meta += '\nshape=rtl mirrored\nlocale=ar-EG\n'
            W = c.screen_of(c.load(os.path.join(src, 'home1.xml')))[0]
            mirror_xml(os.path.join(src, 'home1.xml'), os.path.join(d, 'dnd-edit.xml'), W)
        else:
            shutil.copy(os.path.join(src, 'home1.xml'), os.path.join(d, 'dnd-edit.xml'))
        open(os.path.join(d, 'meta.txt'), 'w').write(meta)
        return d

    def _cases(self, p):
        return {k['id']: k for k in p['cases']}

    def test_ltr_rail_targets(self):
        p = dnd.plan(self._point('phone-land-v1', False), '160')
        k = self._cases(p)
        self.assertFalse(p['rtl']); self.assertEqual(p['rail_side'], 'right')
        self.assertEqual(k['grid']['expect'], {'cell': [1, 2]})
        self.assertEqual(k['in-left']['expect'], {'cell': [0, 2]})
        self.assertEqual(k['in-right']['expect'], {'cell': [3, 2]})
        self.assertEqual(k['rail-top']['expect'], {'dock': 0})
        self.assertLess(k['pg-fwd']['x2'], k['pg-fwd']['x1'])     # LTR next page: finger right→left

    def test_rtl_mirrors_columns_not_rail_index(self):
        p = dnd.plan(self._point('phone-land-v1', True), '160')
        k = self._cases(p)
        self.assertTrue(p['rtl']); self.assertEqual(p['rail_side'], 'left')
        self.assertEqual(k['grid']['expect'], {'cell': [2, 2]})
        self.assertEqual(k['in-left']['expect'], {'cell': [3, 2]})
        self.assertEqual(k['in-right']['expect'], {'cell': [0, 2]})
        self.assertEqual(k['rail-top']['expect'], {'dock': 0})
        self.assertEqual(k['rail-bottom']['expect'], {'dock': 1})
        self.assertLess(k['rail-top']['x2'], p['screen'][0] / 2)   # rail drop goes to the left edge
        self.assertGreater(k['pg-fwd']['x2'], k['pg-fwd']['x1'])  # RTL next page: finger left→right
        # out-of-block drops sit outside the block but inside the page column (right of the rail)
        self.assertLess(k['out-left']['x2'], p['block_px'][0])
        self.assertGreater(k['out-right']['x2'], p['block_px'][2])

    def test_score_cancel_and_cell(self):
        d = self._point('phone-land-v1', True)
        p = dnd.plan(d, '160')
        seed = json.load(open(os.path.join(c.FIXDIR, 'launcher.json')))
        for k in p['cases']:
            if k['how'] == 'na':
                continue
            cfg = json.loads(json.dumps(seed))
            if 'cell' in k['expect']:
                for it in cfg['pages'][0]['items']:
                    if it['id'] == 'a-set3':
                        it['x'], it['y'] = k['expect']['cell']
            json.dump(cfg, open(os.path.join(d, f"dnd-{k['id']}-config.json"), 'w'))
            open(os.path.join(d, f"dnd-{k['id']}-top.txt"), 'w').write('topResumedActivity dev.nasawa.configlauncher/.MainActivity')
        out = dnd.score(d)
        r = {x['id']: x for x in out['results']}
        self.assertIs(r['out-left']['ok'], True)      # unchanged config == cancelled
        self.assertEqual(r['grid']['saved'][2:], (2, 2))


class R1Mirror(unittest.TestCase):
    def _run(self, rtl, ids=('R1',), mirror=True, gate=None, want_verdict=False):
        t = tempfile.mkdtemp()
        try:
            src = os.path.join(TD, 't7-land-v1')
            for f in os.listdir(src):
                shutil.copy(os.path.join(src, f), t)
            meta = open(os.path.join(t, 'meta.txt')).read()
            if 'fixture=' not in meta:
                meta += '\nfixture=launcher.json\n'
            if rtl:
                meta += '\nshape=rtl\nlocale=ar-EG\nforce_rtl=1\napp_locales=[ar-EG]\n'
                W = c.screen_of(c.load(os.path.join(src, 'home1.xml')))[0]
                if mirror:
                    mirror_xml(os.path.join(src, 'home1.xml'), os.path.join(t, 'home1.xml'), W)
            if gate:
                meta = re.sub(r'(?m)^gate=.*$', '', meta) + f'\ngate={gate}\n'
            open(os.path.join(t, 'meta.txt'), 'w').write(meta)
            _m, res = c.run_checks(t)
            if want_verdict:
                return c.verdict_of(_m, res), {x['id']: x for x in res}
            got = {x['id']: x for x in res}
            return got.get(ids[0]) if len(ids) == 1 else {i: got.get(i) for i in ids}
        finally:
            shutil.rmtree(t)

    def test_ltr_pass(self):
        r = self._run(False)       # fixture meta is gate=dev8 → R1 blocks
        self.assertIsNotNone(r); self.assertEqual(r['status'], 'PASS'); self.assertEqual(r['severity'], 'block')

    # R1 is gated like the L checks (#211): advisory at dev7, blocking at dev8.
    def test_r1_severity_policy(self):
        for gate, want in (('dev7', 'advisory'), ('dev8', 'block')):
            for tier in ('phone', 'large'):
                m = {'gate': gate, 'tier': tier, 'font_scale': '1.0'}
                self.assertEqual(c.severity('R1', m), want, (gate, tier))
                self.assertEqual(c.severity('R1p2', m), want, (gate, tier))
                self.assertEqual(c.severity('L2', m), want, (gate, tier))   # same as L*
                self.assertEqual(c.severity('F1', m), 'block', (gate, tier))  # crashes/ANRs always block
        # font 2.0 stays advisory, as for L*
        self.assertEqual(c.severity('R1', {'gate': 'dev8', 'tier': 'large', 'font_scale': '2.0'}), 'advisory')

    def test_r1_fail_advisory_at_dev7(self):
        # RTL requested, grid not mirrored → R1 FAIL; under dev7 it must not fail the point on its own.
        _v, res = self._run(True, mirror=False, gate='dev7', want_verdict=True)
        self.assertEqual(res['R1']['status'], 'FAIL', res['R1']['detail'])
        self.assertEqual(res['R1']['severity'], 'advisory')
        # The test fixture is a partial dump (other F*/T* SKIP), so judge R1's own contribution.
        self.assertEqual(c.verdict_of({}, [res['R1']]), 'PASS')

    def test_r1_fail_blocks_at_dev8(self):
        v, res = self._run(True, mirror=False, gate='dev8', want_verdict=True)
        self.assertEqual(res['R1']['status'], 'FAIL', res['R1']['detail'])
        self.assertEqual(res['R1']['severity'], 'block')
        self.assertEqual(c.verdict_of({}, [res['R1']]), 'FAIL')
        self.assertEqual(v, 'FAIL')

    def test_r1_mirrored_pass_blocking_at_dev8(self):
        v, res = self._run(True, gate='dev8', want_verdict=True)
        self.assertEqual(res['R1']['status'], 'PASS', res['R1']['detail'])
        self.assertEqual(res['R1']['severity'], 'block')
        self.assertEqual(c.verdict_of({}, [res['R1']]), 'PASS')

    def test_rtl_mirrored_pass(self):
        r = self._run(True)
        self.assertIsNotNone(r); self.assertEqual(r['status'], 'PASS', r['detail'])


    # L2/L3 must map physical → logical columns in RTL (PR #210 rtl-t7-* false FAIL).
    def test_l2_l3_ltr_pass(self):
        r = self._run(False, ids=('L2', 'L3'))
        for i in ('L2', 'L3'):
            self.assertEqual(r[i]['status'], 'PASS', r[i]['detail'])

    def test_l2_l3_rtl_mirrored_pass(self):
        r = self._run(True, ids=('L2', 'L3'))
        for i in ('L2', 'L3'):
            self.assertEqual(r[i]['status'], 'PASS', r[i]['detail'])

    def test_l2_rtl_unmirrored_fails(self):
        r = self._run(True, ids=('L2',), mirror=False)
        self.assertEqual(r['status'], 'FAIL', r['detail'])


class L5EndEdge(unittest.TestCase):
    """L5: rail on the end edge (right in LTR, left in RTL) hugging safeDrawing, page column opposite.
    Direction comes from meta (locale / force_rtl / layout_dir), never from where the rail is drawn."""

    def _run(self, src, rtl=None, mirror=False, gate=None, edit_xml=None, want_verdict=False):
        t = tempfile.mkdtemp()
        try:
            for f in os.listdir(src):
                shutil.copy(os.path.join(src, f), t)
            meta = open(os.path.join(t, 'meta.txt')).read()
            if 'fixture=' not in meta:
                meta += '\nfixture=launcher.json\n'
            if rtl is not None:   # override the direction config only (shape too, so it cannot leak RTL)
                meta = re.sub(r'(?m)^(shape|locale|force_rtl|app_locales|layout_dir)=.*$', '', meta)
                meta += ('\nshape=L5 test\nlocale=ar-EG\nforce_rtl=1\napp_locales=[ar-EG]\n' if rtl
                         else '\nshape=L5 test\nlocale=en-US\nforce_rtl=0\n')
                if os.path.exists(os.path.join(t, 'locale.txt')):
                    os.remove(os.path.join(t, 'locale.txt'))
            if gate:
                meta = re.sub(r'(?m)^gate=.*$', '', meta) + f'\ngate={gate}\n'
            open(os.path.join(t, 'meta.txt'), 'w').write(meta)
            h = os.path.join(t, 'home1.xml')
            if mirror:
                W = c.screen_of(c.load(h))[0]
                mirror_xml(os.path.join(src, 'home1.xml'), h, W)
            if edit_xml:
                xml = edit_xml(open(h, encoding='utf-8').read())
                open(h, 'w', encoding='utf-8').write(xml)
            m, res = c.run_checks(t)
            got = {x['id']: x for x in res}
            if want_verdict:
                return c.verdict_of(m, res), got
            return got.get('L5')
        finally:
            shutil.rmtree(t)

    FIX = os.path.join(TD, 't7-land-v1')
    REAL_LTR = os.path.join(TD, 'real-40581041514', 't7-land-v1')
    REAL_RTL = os.path.join(TD, 'real-40581041514', 'rtl-t7-land-v1')

    def _assert(self, r, status, side):
        self.assertIsNotNone(r)
        self.assertEqual(r['status'], status, r['detail'])
        self.assertIn(f'rail side {side}', r['detail'])

    # --- the four direction × side cases (fixture dump; mirrored dump stands in for the other side)
    def test_ltr_rail_right_passes(self):
        self._assert(self._run(self.FIX, rtl=False), 'PASS', 'right')

    def test_ltr_rail_left_fails(self):
        self._assert(self._run(self.FIX, rtl=False, mirror=True), 'FAIL', 'left')

    def test_rtl_rail_left_passes(self):
        self._assert(self._run(self.FIX, rtl=True, mirror=True), 'PASS', 'left')

    def test_rtl_rail_right_fails(self):
        self._assert(self._run(self.FIX, rtl=True, mirror=False), 'FAIL', 'right')

    # --- severity: gated like L*
    def test_l5_severity_policy(self):
        for tier in ('phone', 'large'):
            self.assertEqual(c.severity('L5', {'gate': 'dev7', 'tier': tier, 'font_scale': '1.0'}), 'advisory')
            self.assertEqual(c.severity('L5', {'gate': 'dev8', 'tier': tier, 'font_scale': '1.0'}), 'block')
            self.assertEqual(c.severity('L5', {'gate': 'dev8', 'tier': tier, 'font_scale': '2.0'}), 'advisory')
            for gate in ('dev7', 'dev8'):
                self.assertEqual(c.severity('F1', {'gate': gate, 'tier': tier, 'font_scale': '2.0'}), 'block')

    def test_l5_wrong_side_advisory_at_dev7(self):
        _v, res = self._run(self.FIX, rtl=False, mirror=True, gate='dev7', want_verdict=True)
        self.assertEqual(res['L5']['status'], 'FAIL'); self.assertEqual(res['L5']['severity'], 'advisory')
        self.assertEqual(c.verdict_of({}, [res['L5']]), 'PASS')

    def test_l5_wrong_side_blocks_at_dev8(self):
        _v, res = self._run(self.FIX, rtl=False, mirror=True, gate='dev8', want_verdict=True)
        self.assertEqual(res['L5']['status'], 'FAIL'); self.assertEqual(res['L5']['severity'], 'block')
        self.assertEqual(c.verdict_of({}, [res['L5']]), 'FAIL')

    def test_l5_pass_blocking_at_dev8(self):
        _v, res = self._run(self.FIX, rtl=False, gate='dev8', want_verdict=True)
        self.assertEqual(res['L5']['status'], 'PASS'); self.assertEqual(res['L5']['severity'], 'block')

    # --- direction source
    def test_direction_from_config_not_rail(self):
        self.assertEqual(c.layout_rtl_of({}, 'en-US', '0', '')[0], False)
        self.assertEqual(c.layout_rtl_of({}, 'ar-EG', '0', '')[0], True)
        self.assertEqual(c.layout_rtl_of({}, 'he-IL', '', '')[0], True)
        self.assertEqual(c.layout_rtl_of({}, 'en-XB', '', '')[0], True)
        self.assertEqual(c.layout_rtl_of({}, 'en-US', '1', '')[0], True)
        self.assertEqual(c.layout_rtl_of({}, 'ar-EG', '1', '0')[0], False)    # dumped layout direction wins
        self.assertEqual(c.layout_rtl_of({}, 'en-US', '0', 'rtl')[0], True)
        self.assertEqual(c.layout_rtl_of({'shape': 'RTL tablet'}, 'en-US', '', '')[0], True)

    # --- geometry unit cases (fabricated boxes)
    def test_rail_end_edge_geometry(self):
        W, tol = 1920, 2
        rail_r, rail_l = (1760, 48, 1920, 1136), (0, 48, 160, 1136)
        slot_r, slot_l = [(1776, 274, 1904, 402)], [(16, 274, 144, 402)]
        tiles_l, tiles_r = [(100, 100, 300, 300)], [(1620, 100, 1700, 300)]
        z = (0, 48, 0, 64)
        self.assertTrue(c.rail_end_edge(rail_r, slot_r, None, tiles_l, W, z, False, tol)[0])
        self.assertTrue(c.rail_end_edge(rail_l, slot_l, None, tiles_r, W, z, True, tol)[0])
        ok, side, _ = c.rail_end_edge(rail_l, slot_l, None, tiles_r, W, z, False, tol)
        self.assertFalse(ok); self.assertEqual(side, 'left')
        # gap from the edge: rail floats 24 px in from the right
        self.assertFalse(c.rail_end_edge((1736, 48, 1896, 1136), [(1752, 274, 1880, 402)], None, tiles_l,
                                         W, z, False, tol)[0])
        # page column on the rail's side (tile right of the rail's inner edge)
        self.assertFalse(c.rail_end_edge(rail_r, slot_r, None, [(1800, 500, 1900, 600)], W, z, False, tol)[0])
        # right inset 48: rail inside safeDrawing passes; rail painted edge-to-edge passes if slots are inside;
        # slots under the inset fail
        s48 = (0, 48, 48, 64)
        self.assertTrue(c.rail_end_edge((1712, 48, 1872, 1136), [(1728, 274, 1856, 402)], None, tiles_l,
                                        W, s48, False, tol)[0])
        self.assertTrue(c.rail_end_edge(rail_r, [(1728, 274, 1856, 402)], None, tiles_l, W, s48, False, tol)[0])
        self.assertFalse(c.rail_end_edge(rail_r, slot_r, None, tiles_l, W, s48, False, tol)[0])

    def test_fabricated_gap_dump_fails(self):
        # Fabricated dump: shift every node right of x=1700 (rail panel + slots) 40 px inward.
        def shift(xml):
            def f(mm):
                x1, y1, x2, y2 = map(int, mm.groups())
                if x1 >= 1700:
                    x1, x2 = x1 - 40, x2 - 40
                return f'bounds="[{x1},{y1}][{x2},{y2}]"'
            return re.sub(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"', f, xml)
        r = self._run(self.FIX, rtl=False, edit_xml=shift)
        self.assertEqual(r['status'], 'FAIL', r['detail']); self.assertIn('outer-edge gap 40 px', r['detail'])

    # --- negative control on real captures (run 40581041514, main@fbe1bed)
    def test_real_captures_pass_as_captured(self):
        self._assert(self._run(self.REAL_LTR), 'PASS', 'right')
        self._assert(self._run(self.REAL_RTL), 'PASS', 'left')

    def test_real_captures_wrong_direction_fail(self):
        # Same real dumps, direction config swapped: the rail did not move, so L5 must FAIL.
        self._assert(self._run(self.REAL_LTR, rtl=True), 'FAIL', 'right')
        self._assert(self._run(self.REAL_RTL, rtl=False), 'FAIL', 'left')

    def test_real_captures_mirrored_fail(self):
        # Real dumps reflected left↔right under their own direction config: rail lands on the start edge.
        self._assert(self._run(self.REAL_LTR, mirror=True), 'FAIL', 'left')
        self._assert(self._run(self.REAL_RTL, mirror=True), 'FAIL', 'right')


if __name__ == '__main__':
    unittest.main()
