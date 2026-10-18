package dev.nasawa.configlauncher.ui

import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Test

/** DR-6 (#39). */
class DrawerKeyboardTest {
    private fun cfg(hidden: Int) = Configuration().apply { hardKeyboardHidden = hidden }

    @Test fun hardwareKeyboardFocusesButNoIme() {
        assertTrue(DrawerKeyboard.focus(true))
        assertFalse(DrawerKeyboard.showIme(true, false, cfg(Configuration.HARDKEYBOARDHIDDEN_NO)))
    }

    @Test fun noHardwareKeyboardShowsIme() {
        assertTrue(DrawerKeyboard.showIme(true, false, cfg(Configuration.HARDKEYBOARDHIDDEN_YES)))
        assertTrue(DrawerKeyboard.showIme(true, false, cfg(Configuration.HARDKEYBOARDHIDDEN_UNDEFINED)))
    }

    @Test fun settingOffOrTalkBackNeverShowsIme() {
        assertFalse(DrawerKeyboard.focus(false))
        assertFalse(DrawerKeyboard.showIme(false, false, cfg(Configuration.HARDKEYBOARDHIDDEN_YES)))
        assertFalse(DrawerKeyboard.showIme(true, true, cfg(Configuration.HARDKEYBOARDHIDDEN_YES)))
    }
}
