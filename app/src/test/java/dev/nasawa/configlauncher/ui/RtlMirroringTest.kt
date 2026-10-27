package dev.nasawa.configlauncher.ui

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.apps.InstalledApps
import dev.nasawa.configlauncher.model.AppItem
import dev.nasawa.configlauncher.model.Edit
import dev.nasawa.configlauncher.model.Grid
import dev.nasawa.configlauncher.model.InstalledApp
import dev.nasawa.configlauncher.model.ResolvedItem
import dev.nasawa.configlauncher.model.ResolvedPage
import dev.nasawa.configlauncher.store.ConfigEngine
import dev.nasawa.configlauncher.store.LastGoodStore
import dev.nasawa.configlauncher.store.LocalFileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/**
 * #187 / ux.md §1.5 RTL: the app declares supportsRtl, so an Arabic (ldrtl) window lays out right to left
 * with no LocalLayoutDirection override: the rail goes on the left (t7-land, phone-land), column 0 is on
 * the right, and drops map a mirrored x to the mirrored column / dock slot. LTR is unchanged.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RtlMirroringTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val settings = InstalledApp("com.android.settings", "com.android.settings.Settings", "Settings")
    private val dock = (0 until 4).map { ResolvedItem(AppItem("d$it", "dock.$it", label = "Dock $it"), null) }

    @Test fun manifestDeclaresRtlSupport() {
        assertTrue("android:supportsRtl", ctx.applicationInfo.flags and ApplicationInfo.FLAG_SUPPORTS_RTL != 0)
    }

    /** Rail + page column exactly as HomeScreen's rail Row composes them, with the platform's direction. */
    private fun railWindow(w: Dp, h: Dp, override: LayoutDirection? = null) {
        rule.setContent {
            val apps = InstalledApps(ApplicationProvider.getApplicationContext())
            val drag = DragController({ error("unused") }) {}
            drag.dockVertical = true
            val body = @androidx.compose.runtime.Composable {
                Box(Modifier.size(w, h)) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().testTag("page")) {
                            Text("dir=" + LocalLayoutDirection.current.name, Modifier.testTag("dir"))
                        }
                        DockRail(dock, apps, drag, {}, {}, { _, _ -> emptyList() }) {
                            Text("Apps", maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                        }
                    }
                }
            }
            if (override != null) CompositionLocalProvider(LocalLayoutDirection provides override) { body() } else body()
        }
        rule.waitForIdle()
    }

    private fun assertRailLeft() {
        rule.onNodeWithText("dir=Rtl").assertExists()
        val page = rule.onNodeWithTag("page").getBoundsInRoot()
        val rail = rule.onNodeWithTag("dockRail").getBoundsInRoot()
        assertTrue("rail left of page in RTL: rail=$rail page=$page", rail.right.value <= page.left.value + 1f)
        assertEquals("rail at the window's left edge", 0f, rail.left.value, 1f)
    }

    @Test @Config(qualifiers = "ar-ldrtl-w960dp-h600dp-mdpi")
    fun rtlT7LandRailOnLeftFromPlatformDirection() {
        assertTrue(HomeGeometry.useRail(960f, 600f))
        railWindow(960.dp, 600.dp)
        assertRailLeft()
    }

    @Test @Config(qualifiers = "ar-ldrtl-w800dp-h360dp-mdpi")
    fun rtlPhoneLandRailOnLeftFromPlatformDirection() {
        assertTrue(HomeGeometry.useRail(800f, 360f))
        railWindow(800.dp, 360.dp)
        assertRailLeft()
    }

    @Test @Config(qualifiers = "en-w960dp-h600dp-mdpi")
    fun ltrT7LandRailStaysRight() {
        railWindow(960.dp, 600.dp)
        rule.onNodeWithText("dir=Ltr").assertExists()
        val page = rule.onNodeWithTag("page").getBoundsInRoot()
        val rail = rule.onNodeWithTag("dockRail").getBoundsInRoot()
        assertTrue("page left of rail in LTR", page.right.value <= rail.left.value + 1f)
        assertEquals("rail at the window's right edge", 960f, rail.right.value, 1f)
    }

    private fun gridWith(dir: LayoutDirection): Pair<Rect, Rect> {
        val apps = InstalledApps(ctx)
        val drag = DragController({ error("unused") }) {}
        val page = ResolvedPage("p0", "Home", listOf(
            ResolvedItem(AppItem("a0", settings.packageName, x = 0, y = 0, label = "Col0"), settings),
            ResolvedItem(AppItem("a3", settings.packageName, x = 3, y = 0, label = "Col3"), settings),
        ))
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalTitleBarNow provides ZonedDateTime.parse("2026-05-11T12:00:00Z"),
                    LocalLayoutDirection provides dir,
                ) {
                    Box(Modifier.size(880.dp, 520.dp)) {
                        PageGrid(page, Grid(columns = 4, rows = 5), apps, drag, false, {}, {}, { _, _ -> emptyList() }, {})
                    }
                }
            }
        }
        rule.waitForIdle()
        val c0 = rule.onNodeWithText("Col0", useUnmergedTree = true).getBoundsInRoot()
        val c3 = rule.onNodeWithText("Col3", useUnmergedTree = true).getBoundsInRoot()
        return Rect(c0.left.value, c0.top.value, c0.right.value, c0.bottom.value) to
            Rect(c3.left.value, c3.top.value, c3.right.value, c3.bottom.value)
    }

    @Test @Config(qualifiers = "w880dp-h520dp-mdpi")
    fun rtlGridColumnZeroOnTheRight() {
        val (c0, c3) = gridWith(LayoutDirection.Rtl)
        assertTrue("RTL: column 0 right of column 3: c0=$c0 c3=$c3", c0.left > c3.right)
        // Centring is symmetric: column 0's distance from the right edge equals column 3's from the left.
        assertEquals(880f - c0.right, c3.left, 2f)
    }

    @Test @Config(qualifiers = "w880dp-h520dp-mdpi")
    fun ltrGridColumnZeroOnTheLeft() {
        val (c0, c3) = gridWith(LayoutDirection.Ltr)
        assertTrue("LTR: column 0 left of column 3: c0=$c0 c3=$c3", c0.right < c3.left)
    }

    // --- Drop math (DragController) ---

    private fun engine(): ConfigEngine =
        ConfigEngine(LocalFileSource(tmp.newFolder()), LastGoodStore(tmp.newFolder()), stabilityMs = 0)

    private fun dropAt(point: Offset, rtl: Boolean, vertical: Boolean = false): Edit? {
        val submitted = mutableListOf<Edit>()
        val e = engine()
        val drag = DragController({ e }, submit = { submitted += it })
        drag.rtl = rtl
        drag.dockVertical = vertical
        drag.gridRect = Rect(100f, 0f, 500f, 500f) // 4 columns of 100 px, 5 rows
        drag.grid = Grid(4, 5)
        drag.currentPageId = "home"
        drag.dockRect = if (vertical) Rect(500f, 0f, 580f, 500f) else Rect(100f, 520f, 500f, 600f)
        drag.dockCount = 3 // 4 drop slots of 100 px (horizontal) / 125 px (vertical)
        val item = ResolvedItem(AppItem("a", "p.a", x = 0, y = 0), null)
        drag.start(item, Rect(-10f, -10f, 10f, 10f)) // centre at the origin
        drag.move(point)
        drag.drop()
        return submitted.singleOrNull()
    }

    @Test fun rtlGridDropMirrorsColumn() {
        // x = 150 is the physical leftmost column: column 0 in LTR, column 3 (the end column) in RTL.
        assertEquals(Edit.MoveToCell("a", "home", 0, 1), dropAt(Offset(150f, 150f), rtl = false))
        assertEquals(Edit.MoveToCell("a", "home", 3, 1), dropAt(Offset(150f, 150f), rtl = true))
        assertEquals(Edit.MoveToCell("a", "home", 0, 1), dropAt(Offset(450f, 150f), rtl = true))
    }

    @Test fun rtlBottomDockDropIndexMirrors() {
        assertEquals(Edit.MoveToDock("a", 0), dropAt(Offset(120f, 560f), rtl = false))
        assertEquals(Edit.MoveToDock("a", 3), dropAt(Offset(120f, 560f), rtl = true))
        assertEquals(Edit.MoveToDock("a", 0), dropAt(Offset(480f, 560f), rtl = true))
        assertEquals(Edit.MoveToDock("a", 1), dropAt(Offset(380f, 560f), rtl = true))
    }

    @Test fun railDropIndexFollowsYInBothDirections() {
        for (rtl in listOf(false, true)) {
            assertEquals(Edit.MoveToDock("a", 0), dropAt(Offset(540f, 10f), rtl, vertical = true))
            assertEquals(Edit.MoveToDock("a", 2), dropAt(Offset(540f, 300f), rtl, vertical = true))
            assertEquals(Edit.MoveToDock("a", 3), dropAt(Offset(540f, 499f), rtl, vertical = true))
        }
    }
}
