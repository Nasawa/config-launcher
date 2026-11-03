package dev.nasawa.configlauncher.ui

import android.graphics.Rect as ARect
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.ConfigLauncherApp
import dev.nasawa.configlauncher.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config
import java.io.File

/**
 * Grace's #223 QA note B: on split-phone (800×312, mdpi) with #223's zIndex(1) lifted tile held over cell (1,1),
 * the empty cells reported shrunk accessibility bounds and the covered (1,1) cell dropped out of the tree (normal
 * after release). Since #228 the lifted tile is drawn by the root [DragOverlay], whose node has no semantics
 * (cleared; its subtree reports no accessibility nodes), and the source tile stays in its cell. Here every node
 * TalkBack / uiautomator had at rest keeps the same screen bounds while the tile is held over (1,1), and after
 * release. (The rail's top button is rebuilt as a drop target while dragging, so its new ids are not compared.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LiftedTileA11yBoundsTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(Seed()).around(compose)

    private val store get() = androidx.lifecycle.ViewModelProvider(compose.activity)[HomeViewModel::class.java].store

    private class Seed : TestRule {
        override fun apply(base: Statement, d: Description) = object : Statement() {
            override fun evaluate() {
                val app = ApplicationProvider.getApplicationContext<ConfigLauncherApp>()
                app.getSharedPreferences("settings", 0).edit().putBoolean("appOnly", true).putBoolean("homePromptShown", true).commit()
                val f = File(app.filesDir, "config/source/launcher.json"); f.parentFile!!.mkdirs()
                f.writeText(javaClass.getResource("/pager/pages3.json")!!.readText())
                kotlinx.coroutines.runBlocking { app.store.engine.poll(force = true) }
                base.evaluate()
            }
        }
    }

    private fun composeView(): View {
        fun find(v: View): View? = if (v is ViewRootForTest) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        return find(compose.activity.window.decorView)!!
    }

    /** Every semantics node's accessibility node, as TalkBack / uiautomator get it: id → bounds in screen (null: not reported). */
    private fun a11y(): Map<Int, ARect?> {
        compose.waitForIdle()
        val v = composeView()
        val provider = ViewCompat.getAccessibilityDelegate(v)!!.getAccessibilityNodeProvider(v)!!
        return compose.onAllNodes(isRoot().not(), useUnmergedTree = true).fetchSemanticsNodes().associate { n ->
            n.id to provider.createAccessibilityNodeInfo(n.id)?.let { i -> ARect().also { i.getBoundsInScreen(it) } }
        }
    }

    private fun tile(label: String): SemanticsNode = compose.onAllNodes(hasText(label) or hasContentDescription(label, substring = true))
        .fetchSemanticsNodes().single { SemanticsActions.OnClick in it.config }

    private fun frames(n: Int) = repeat(n) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }

    @Test @Config(qualifiers = "w800dp-h312dp-land-mdpi")
    fun splitPhoneLiftedTileCoversNoAccessibilityNode() {
        store.localDir.mkdirs()
        kotlinx.coroutines.runBlocking { store.engine.flush(); File(store.localDir, "launcher.json").writeText(javaClass.getResource("/pager/pages3.json")!!.readText()); store.engine.poll(force = true) }
        compose.waitForIdle()
        val a = tile("QA Settings").boundsInRoot // (0,0)
        val b = tile("A very long label").boundsInRoot // (1,0)
        val c = tile("Row two").boundsInRoot // (0,1)
        val pitch = c.top - a.top
        assertTrue("split-phone: short rows ($pitch px pitch) under a taller lifted tile", pitch < a.height * 1.15f)
        val over11 = Offset(b.center.x, c.center.y)

        val rest = a11y()
        assertTrue("nodes reported at rest", rest.values.count { it != null } >= 8)

        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { down(a.center) }
        compose.mainClock.advanceTimeBy(1_000)
        val d = (over11 - a.center) / 6f
        repeat(6) { compose.onRoot().performTouchInput { moveBy(d) }; frames(2) }
        frames(30) // the lift scale-up has settled
        assertTrue("held over (1,1)", store.engine.dragging)
        val held = a11y()
        // Nodes still in the tree (the rail's top button is rebuilt as a drop target while dragging: new ids).
        val changed = rest.filter { (id, r) -> r != null && id in held && held[id] != r }
        assertTrue("the empty cells and tiles are all still there", rest.keys.count { it in held } >= 30)
        assertEquals("mid-drag over (1,1): no node's accessibility bounds shrink or drop out: ${changed.map { (id, r) -> "$id $r -> ${held[id]}" }}", emptyMap<Int, ARect?>(), changed)

        compose.onRoot().performTouchInput { up() }
        frames(60)
        compose.mainClock.autoAdvance = true
        val after = a11y()
        assertEquals("after release", rest.filter { (id, r) -> r != null && id in after }, after.filter { (id, _) -> rest[id] != null })
    }
}
