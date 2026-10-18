package dev.nasawa.configlauncher.ui

import androidx.compose.ui.Modifier
import org.junit.Assert.*
import org.junit.Test

class LabelBackdropTest {
    @Test fun offIsANoOp() = assertSame(Modifier, LabelBackdrop.modifier(false))
    @Test fun onAddsScrim() = assertNotSame(Modifier, LabelBackdrop.modifier(true))
    @Test fun scrimIsSixtyPercentBlack() {
        assertEquals(0.6f, LabelBackdrop.scrim.alpha, 0.01f)
        assertEquals(0f, LabelBackdrop.scrim.red, 0f)
    }
}
