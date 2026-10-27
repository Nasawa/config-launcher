package dev.nasawa.configlauncher.ui

import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * #188 / ux.md §1.5, test-plan L7: a real activity recreation (onSaveInstanceState → a new activity, as on a
 * rotate, fold, resize or multi-window change, since MainActivity declares no configChanges). The page list
 * after recreation can differ (a per-window page set, or a reload in between). Driven on Robolectric's paused
 * looper, so no real clock.
 */
class PagerHostActivity : ComponentActivity() {
    lateinit var pager: PagerState
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides PageByIdRecreateTest.dir) {
                val ids = PageByIdRecreateTest.ids
                val p = if (PageByIdRecreateTest.stock) rememberPagerState { ids.size } else rememberPagerById(ids)
                pager = p
                HorizontalPager(p, Modifier.fillMaxSize(), key = { ids.getOrNull(it) ?: it }) { Text("page-" + ids.getOrNull(it)) }
            }
        }
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PageByIdRecreateTest {
    companion object {
        var ids by mutableStateOf(listOf<String>())
        var dir = LayoutDirection.Ltr
        var stock = false
    }

    @org.junit.Before fun reset() { stock = false; dir = LayoutDirection.Ltr; ids = emptyList() }

    private fun frames() = repeat(10) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
    }

    private fun run(d: LayoutDirection, before: List<String>, at: Int, after: List<String>): String? {
        dir = d; ids = before
        val c = Robolectric.buildActivity(PagerHostActivity::class.java).setup()
        try {
            frames()
            runBlocking { c.get().pager.scrollToPage(at) }
            frames()
            assertEquals("before recreate", before[at], before.getOrNull(c.get().pager.settledPage))
            ids = after
            c.recreate() // the same controller now holds the new activity
            frames()
            val pager = c.get().pager
            assertEquals(pager.currentPage, pager.settledPage)
            return ids.getOrNull(pager.currentPage)
        } finally {
            c.pause().stop().destroy() // so no activity from an earlier test keeps composing
            frames()
        }
    }

    @Test fun recreateKeepsPageIdWhenPagesReorderLtr() =
        assertEquals("second", run(LayoutDirection.Ltr, listOf("home", "second", "third"), 1, listOf("third", "home", "second")))

    @Test fun recreateKeepsPageIdWhenPagesReorderRtl() =
        assertEquals("second", run(LayoutDirection.Rtl, listOf("home", "second", "third"), 1, listOf("third", "home", "second")))

    @Test fun recreateWithSamePagesStaysPut() =
        assertEquals("third", run(LayoutDirection.Ltr, listOf("home", "second", "third"), 2, listOf("home", "second", "third")))

    /** The id is gone in the new window's page set: the old index, clamped (here the new last page). */
    @Test fun recreateFallsBackWhenPageIdRemovedLtr() =
        assertEquals("second", run(LayoutDirection.Ltr, listOf("home", "second", "third"), 2, listOf("home", "second")))

    @Test fun recreateFallsBackWhenPageIdRemovedRtl() =
        assertEquals("third", run(LayoutDirection.Rtl, listOf("home", "second", "third"), 1, listOf("home", "third")))

    /** Control: the stock index-only pager is what #188 reported. If this starts passing by id, Compose changed. */
    @Test fun stockPagerRestoresByIndexOnly() {
        stock = true
        assertEquals("home", run(LayoutDirection.Ltr, listOf("home", "second", "third"), 1, listOf("third", "home", "second")))
    }
}
