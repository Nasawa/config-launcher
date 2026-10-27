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
    def _run(self, rtl):
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
                mirror_xml(os.path.join(src, 'home1.xml'), os.path.join(t, 'home1.xml'), W)
            open(os.path.join(t, 'meta.txt'), 'w').write(meta)
            _m, res = c.run_checks(t)
            return next((x for x in res if x['id'] == 'R1'), None)
        finally:
            shutil.rmtree(t)

    def test_ltr_pass(self):
        r = self._run(False)
        self.assertIsNotNone(r); self.assertEqual(r['status'], 'PASS'); self.assertEqual(r['severity'], 'advisory')

    def test_rtl_mirrored_pass(self):
        r = self._run(True)
        self.assertIsNotNone(r); self.assertEqual(r['status'], 'PASS', r['detail'])


if __name__ == '__main__':
    unittest.main()
