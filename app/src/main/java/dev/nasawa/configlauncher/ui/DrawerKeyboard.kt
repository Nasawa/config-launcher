package dev.nasawa.configlauncher.ui

import android.content.res.Configuration

/** §18.6 Q-v3-2: the setting controls focus only; the IME is requested only without a hardware keyboard. */
object DrawerKeyboard {
    fun focus(setting: Boolean) = setting

    fun showIme(setting: Boolean, talkBack: Boolean, config: Configuration) =
        setting && !talkBack && config.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_NO
}
