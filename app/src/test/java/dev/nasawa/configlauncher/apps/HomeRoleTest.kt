package dev.nasawa.configlauncher.apps

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nasawa.configlauncher.apps.HomeRole.Route
import dev.nasawa.configlauncher.settings.Prefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #61: default-home prompt and the Settings button. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29, 35])
class HomeRoleTest {
    @Test fun routeFallsBackAfterTwoDenialsOrWhenUnavailable() {
        assertEquals(Route.ROLE_REQUEST, HomeRole.route(roleAvailable = true, denials = 0))
        assertEquals(Route.ROLE_REQUEST, HomeRole.route(true, 1))
        assertEquals(Route.HOME_SETTINGS, HomeRole.route(true, 2))
        assertEquals(Route.HOME_SETTINGS, HomeRole.route(false, 0))
    }

    @Test fun promptOnlyOnceAndOnlyWhenNotHome() {
        assertTrue(HomeRole.shouldPrompt(onboarded = true, held = false, promptShown = false))
        assertFalse("never nag after it was shown", HomeRole.shouldPrompt(true, false, promptShown = true))
        assertFalse(HomeRole.shouldPrompt(true, held = true, promptShown = false))
        assertFalse("not during first run", HomeRole.shouldPrompt(onboarded = false, held = false, promptShown = false))
    }

    @Test fun intentsAndPrefsWork() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(Settings.ACTION_HOME_SETTINGS, HomeRole.settingsIntent().action)
        HomeRole.held(ctx); HomeRole.available(ctx)   // never throw
        val p = Prefs(ctx)
        assertFalse(p.homePromptShown); assertEquals(0, p.homeRoleDenials)
        p.homePromptShown = true; p.homeRoleDenials += 1
        assertTrue(Prefs(ctx).homePromptShown); assertEquals(1, Prefs(ctx).homeRoleDenials)
    }
}
