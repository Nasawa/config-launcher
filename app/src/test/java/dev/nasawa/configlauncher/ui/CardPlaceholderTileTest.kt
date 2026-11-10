package dev.nasawa.configlauncher.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.R
import dev.nasawa.configlauncher.config.CardPlaceholder
import dev.nasawa.configlauncher.config.Elements
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #257: the release placeholder is non-interactive and TalkBack reads its title and later-version text. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w320dp-h711dp-xhdpi")
class CardPlaceholderTileTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun tile(title: String? = "Weather", w: Int = 2, h: Int = 2) =
        CardPlaceholder.Tile("w-1", Elements.Placement("home", 0, 0, w, h), title)

    private fun said(title: String = "Weather"): String =
        ctx.getString(R.string.tile_later_version_a11y, title, ctx.getString(R.string.tile_later_version))

    @Test fun talkBackReadsTitleAndLaterVersionAndHasNoClick() {
        rule.setContent { MaterialTheme { Box(Modifier.size(160.dp)) { CardPlaceholderTile(tile()) } } }
        val later = ctx.getString(R.string.tile_later_version)
        assertEquals("Cards arrive in a later version.", later)
        val n = rule.onNodeWithContentDescription(said())
        val c = n.fetchSemanticsNode().config
        assertTrue("focusable", SemanticsProperties.Focused in c)
        assertFalse("non-interactive: no click", SemanticsActions.OnClick in c)
        assertFalse("non-interactive: no long click", SemanticsActions.OnLongClick in c)
        // One TalkBack node, so on-screen Text isn't a separate semantics node (same as ConsentTile).
        assertEquals("Weather, widget, $later", said())
        rule.onNodeWithContentDescription(said()).performClick()
        rule.onNodeWithContentDescription(said()).assertExists()
    }

    @Test fun untitledCardUsesItsId() {
        rule.setContent { MaterialTheme { Box(Modifier.size(160.dp)) { CardPlaceholderTile(tile(title = null)) } } }
        rule.onNodeWithContentDescription(said("w-1")).assertExists()
    }

    @Test fun oneByOneKeepsTalkBackText() {
        rule.setContent { MaterialTheme { Box(Modifier.size(80.dp)) { CardPlaceholderTile(tile(w = 1, h = 1)) } } }
        rule.onNodeWithContentDescription(said()).assertExists()
    }
}
