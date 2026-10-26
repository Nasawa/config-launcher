package dev.nasawa.configlauncher.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * #149 (Grace's question): does the bar's text inherit Material 3's 0.5 sp letterSpacing? The bar's `Text` is both
 * measured (maxIntrinsicWidth) and drawn, so the style it lays out with is the one that counts for both.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TitleBarStyleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun noInheritedLetterSpacing() {
        var themed: TextStyle? = null
        var laid: TextLayoutResult? = null
        rule.setContent {
            MaterialTheme {
                themed = LocalTextStyle.current
                Text("Mon 19 Oct 2026", style = TitleBarLayout.textStyle(Color.White, 24f, 400), maxLines = 1, onTextLayout = { laid = it })
            }
        }
        rule.waitForIdle()
        assertEquals("the theme's body style has it", 0.5.sp, themed!!.letterSpacing)
        val spacing = laid!!.layoutInput.style.letterSpacing
        assertTrue("the bar's text doesn't: $spacing", spacing == androidx.compose.ui.unit.TextUnit.Unspecified || spacing.value == 0f)
        assertEquals(24.sp, laid!!.layoutInput.style.fontSize)
    }
}
