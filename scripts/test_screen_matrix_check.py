#!/usr/bin/env python3
"""Unit tests for screen_matrix_check rail / page-column fixes (HARNESS-BUGS 1–8).

Fixtures: CI XML/meta from Actions run 40499453840 (#201 @ 8f2795f + harness).
Run from repo root: python3 scripts/test_screen_matrix_check.py
"""
from __future__ import annotations

import json
import os
import pathlib
import shutil
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
import screen_matrix_check as c  # noqa: E402

FIX = HERE / "testdata" / "matrix-rail"


def _point(name: str) -> pathlib.Path:
    return FIX / name


class RailTileDetector(unittest.TestCase):
    """Bug 1: tile detector returned 0 tiles when the rail is on."""

    def test_t7_land_finds_tiles(self):
        d = _point("t7-land-v1")
        nodes = c.load(str(d / "home1.xml"))
        meta = c.meta_of(str(d))
        s = float(meta["density"]) / 160
        W, H = c.screen_of(nodes)
        tb = c.title_bar(nodes, s)
        labels, tiles, dock, cards, region = c.grid_parts(nodes, tb, (W, H))
        self.assertGreater(len(tiles), 0, f"expected tiles on rail; region={region}")
        self.assertTrue(c.dock_is_rail(dock, W, H))

    def test_phone_land_finds_tiles(self):
        d = _point("phone-land-v1")
        nodes = c.load(str(d / "home1.xml"))
        meta = c.meta_of(str(d))
        s = float(meta["density"]) / 160
        W, H = c.screen_of(nodes)
        tb = c.title_bar(nodes, s)
        labels, tiles, dock, cards, region = c.grid_parts(nodes, tb, (W, H))
        self.assertGreater(len(tiles), 0)
        self.assertTrue(c.dock_is_rail(dock, W, H))


class PitchFromRowTops(unittest.TestCase):
    """Bug 3: pitchH nonsense (7.0 dp) — derive from row tops."""

    def test_phone_land_pitch_h_from_row_tops(self):
        d = _point("phone-land-v1")
        nodes = c.load(str(d / "home1.xml"))
        meta = c.meta_of(str(d))
        s = float(meta["density"]) / 160
        W, H = c.screen_of(nodes)
        tb = c.title_bar(nodes, s)
        pairs, labels, dock = c.grid_tiles(nodes, tb, (W, H), s)
        tiles = [n for n, _ in pairs]
        pitch = c.pitch_of(tiles, s)
        self.assertIsNotNone(pitch)
        # Manual row-top gap ≈ 46 dp; must not be the old 7.0 cy-gap flake.
        self.assertGreater(pitch[1], 30.0, pitch)
        self.assertLess(pitch[1], 60.0, pitch)

    def test_t7_land_pitch_h_matches_manual(self):
        d = _point("t7-land-v1")
        nodes = c.load(str(d / "home1.xml"))
        meta = c.meta_of(str(d))
        s = float(meta["density"]) / 160
        W, H = c.screen_of(nodes)
        tb = c.title_bar(nodes, s)
        pairs, _, _ = c.grid_tiles(nodes, tb, (W, H), s)
        pitch = c.pitch_of([n for n, _ in pairs], s)
        self.assertAlmostEqual(pitch[1], 92.5, delta=1.0)


class FindEmptyPageColumn(unittest.TestCase):
    """Bug 2: F4 empty LP must land in the page column, not on a rail item."""

    def test_find_empty_outside_rail(self):
        for name in ("t7-land-v1", "phone-land-v1", "bound-840-v1"):
            d = _point(name)
            nodes = c.load(str(d / "home1.xml"))
            meta = c.meta_of(str(d))
            dens = meta["density"]
            W, H = c.screen_of(nodes)
            dock = [n for n in nodes if (n.desc or "").endswith(", dock")]
            is_rail, side, rl, rr = c.rail_geom(dock, W, H, nodes=nodes)
            self.assertTrue(is_rail, name)
            import io
            from contextlib import redirect_stdout
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = c.cmd_find_empty(str(d / "home1.xml"), dens)
            self.assertEqual(rc, 0, name)
            x, y = map(int, buf.getvalue().split())
            if side == "right":
                self.assertLess(x, rl, f"{name}: empty ({x},{y}) must be left of rail@{rl}")
            else:
                self.assertGreater(x, rr, f"{name}: empty ({x},{y}) must be right of rail@{rr}")


class PageColumnL3(unittest.TestCase):
    """Bugs 4–5: L3 column-centre margins in page column; fold 11 px → PASS."""

    def _check(self, name):
        d = _point(name)
        td = tempfile.mkdtemp()
        try:
            for f in os.listdir(d):
                shutil.copy(os.path.join(d, f), td)
            meta = open(os.path.join(td, "meta.txt")).read()
            if "gate=" not in meta:
                open(os.path.join(td, "meta.txt"), "a").write("\ngate=dev8\n")
            c.cmd_check(td)
            res = json.load(open(os.path.join(td, "result.json")))
            return {x["id"]: x for x in res["checks"]}
        finally:
            shutil.rmtree(td, ignore_errors=True)

    def test_fold_l3_pass(self):
        ch = self._check("fold-open-v1")["L3"]
        self.assertEqual(ch["status"], "PASS", ch.get("detail"))

    def test_t7_land_l3_equal_centres(self):
        ch = self._check("t7-land-v1")["L3"]
        self.assertEqual(ch["status"], "PASS", ch.get("detail"))
        self.assertIn("532.0/532.0", ch["detail"])

    def test_phone_land_l3_equal_centres(self):
        ch = self._check("phone-land-v1")["L3"]
        self.assertEqual(ch["status"], "PASS", ch.get("detail"))
        self.assertIn("264.0/264.0", ch["detail"])


class G5bBarIdentity(unittest.TestCase):
    """Bug 6: G5b bar identity robust on a51-c1 chip points."""

    def test_s320_a51_c1_g5b_pass(self):
        d = _point("s320-a51-c1")
        td = tempfile.mkdtemp()
        try:
            for f in os.listdir(d):
                shutil.copy(os.path.join(d, f), td)
            meta = open(os.path.join(td, "meta.txt")).read()
            if "gate=" not in meta:
                open(os.path.join(td, "meta.txt"), "a").write("\ngate=dev8\n")
            c.cmd_check(td)
            res = json.load(open(os.path.join(td, "result.json")))
            g5b = next(x for x in res["checks"] if x["id"] == "G5b")
            self.assertEqual(g5b["status"], "PASS", g5b.get("detail"))
        finally:
            shutil.rmtree(td, ignore_errors=True)


class L6ScrolledRail(unittest.TestCase):
    """Bugs 7–8: ignore partial scrolled cells; wording says rail not bottom dock."""

    def test_dock7_phone_land_l6(self):
        d = _point("dock7-phone-land-v1")
        td = tempfile.mkdtemp()
        try:
            for f in os.listdir(d):
                shutil.copy(os.path.join(d, f), td)
            meta = open(os.path.join(td, "meta.txt")).read()
            if "gate=" not in meta:
                open(os.path.join(td, "meta.txt"), "a").write("\ngate=dev8\n")
            c.cmd_check(td)
            res = json.load(open(os.path.join(td, "result.json")))
            l6 = next(x for x in res["checks"] if x["id"] == "L6")
            self.assertEqual(l6["status"], "PASS", l6.get("detail"))
            self.assertIn("rail", l6["detail"])
            self.assertNotIn("bottom dock", l6["detail"])
            self.assertIn("scrolled", l6["detail"])
        finally:
            shutil.rmtree(td, ignore_errors=True)


class RailGeom(unittest.TestCase):
    def test_rail_panel_80dp(self):
        d = _point("phone-land-v1")
        nodes = c.load(str(d / "home1.xml"))
        W, H = c.screen_of(nodes)
        dock = [n for n in nodes if (n.desc or "").endswith(", dock")]
        is_rail, side, rl, rr = c.rail_geom(dock, W, H, nodes=nodes)
        self.assertTrue(is_rail)
        self.assertEqual(side, "right")
        self.assertEqual(rr - rl, 80)



class CardsLargeL2L3(unittest.TestCase):
    """#205: cards-large icons alone at row 4 must not report L2 row 0 or asymmetric L3."""

    def _check(self, name):
        d = _point(name)
        td = tempfile.mkdtemp()
        try:
            for f in os.listdir(d):
                shutil.copy(os.path.join(d, f), td)
            meta = open(os.path.join(td, "meta.txt")).read()
            if "gate=" not in meta:
                open(os.path.join(td, "meta.txt"), "a").write("\ngate=dev8\n")
            import io
            from contextlib import redirect_stdout
            with redirect_stdout(io.StringIO()):
                c.cmd_check(td)
            res = json.load(open(os.path.join(td, "result.json")))
            return {x["id"]: x for x in res["checks"]}
        finally:
            shutil.rmtree(td, ignore_errors=True)

    def test_cards_t10_land_l2_l3_pass(self):
        ch = self._check("cards-t10-land-v1")
        self.assertEqual(ch["L2"]["status"], "PASS", ch["L2"].get("detail"))
        self.assertEqual(ch["L3"]["status"], "PASS", ch["L3"].get("detail"))
        # Occupied cols were only 0–1 of 4; harness must expand to grid.columns → equal margins.
        self.assertIn("768.0/768.0", ch["L3"]["detail"])


class D1ShortPaneFloor(unittest.TestCase):
    """#202 D1: short-pane floor max(25%, 96 dp); half-sheet regressions still fail."""

    def _check(self, name):
        d = _point(name)
        td = tempfile.mkdtemp()
        try:
            for f in os.listdir(d):
                shutil.copy(os.path.join(d, f), td)
            meta = open(os.path.join(td, "meta.txt")).read()
            if "gate=" not in meta:
                open(os.path.join(td, "meta.txt"), "a").write("\ngate=dev8\n")
            import io
            from contextlib import redirect_stdout
            with redirect_stdout(io.StringIO()):
                c.cmd_check(td)
            res = json.load(open(os.path.join(td, "result.json")))
            return {x["id"]: x for x in res["checks"]}
        finally:
            shutil.rmtree(td, ignore_errors=True)

    def test_split_phone_short_pane_passes(self):
        # 800×312 short pane: search at 88 dp (28%) — same absolute chrome as 24% on 800×360.
        ch = self._check("split-phone-v1")["D1"]
        self.assertEqual(ch["status"], "PASS", ch.get("detail"))
        self.assertIn("88 dp", ch["detail"])
        self.assertIn("96", ch["detail"])

    def test_half_sheet_still_fails(self):
        # Synthetic half-sheet: Search apps top at 55% of an 800 dp tall window (runs 1–3).
        td = tempfile.mkdtemp()
        try:
            xml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout"
        package="com.nasawa.configlauncher" content-desc="" checkable="false" checked="false"
        clickable="false" enabled="true" focusable="false" focused="false" scrollable="false"
        long-clickable="false" password="false" selected="false" bounds="[0,0][400,800]">
    <node index="0" text="Search apps" resource-id="" class="android.widget.EditText"
          package="com.nasawa.configlauncher" content-desc="Search apps" checkable="false"
          checked="false" clickable="true" enabled="true" focusable="true" focused="false"
          scrollable="false" long-clickable="false" password="false" selected="false"
          bounds="[16,440][384,488]" />
  </node>
</hierarchy>
"""
            open(os.path.join(td, "drawer.xml"), "w").write(xml)
            open(os.path.join(td, "home1.xml"), "w").write(xml)
            open(os.path.join(td, "meta.txt"), "w").write(
                "id=half-sheet-synth\ndensity=160\nscreen_px=400x800\n"
                "font_scale=1.0\nnight=no\nrotation=0\napi=35\ngate=dev8\n"
                "tier=phone\n")
            open(os.path.join(td, "run.txt"), "w").write(
                "Override size: 400x800\nOverride density: 160\nNight mode: no\n")
            open(os.path.join(td, "retries.txt"), "w").write("")
            import io
            from contextlib import redirect_stdout
            with redirect_stdout(io.StringIO()):
                c.cmd_check(td)
            res = json.load(open(os.path.join(td, "result.json")))
            d1 = next(x for x in res["checks"] if x["id"] == "D1")
            self.assertEqual(d1["status"], "FAIL", d1.get("detail"))
            self.assertIn("440 dp", d1["detail"])
        finally:
            shutil.rmtree(td, ignore_errors=True)



if __name__ == "__main__":
    unittest.main()
