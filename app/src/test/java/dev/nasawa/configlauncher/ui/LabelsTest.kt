package dev.nasawa.configlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelsTest {
    @Test fun stripsBidiZeroWidthAndControls() {
        assertEquals("evil.exe", Labels.clean("\u202Eevil\u200B.exe\u0007"))
        assertEquals("Bank", Labels.clean("\u2066Bank\u2069"))
        assertEquals("😀 ok", Labels.clean("😀 ok"))
        assertEquals("?", Labels.clean("\u200E"))
    }
}
