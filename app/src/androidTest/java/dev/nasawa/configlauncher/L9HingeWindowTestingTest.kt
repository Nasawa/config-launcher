package dev.nasawa.configlauncher

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * test-plan §20 L9 (#197): FoldingFeature via androidx.window:window-testing
 * (`WindowLayoutInfoPublisherRule` + book / tabletop / flat factories).
 *
 * BLOCKED for Sprite: production does not yet read `WindowInfoTracker`. Injection alone cannot
 * assert hinge avoidance until the home grid / dock / cards consume `FoldingFeature`. Re-enable
 * and assert no cell/card/dock intersects the hinge (book = start half; tabletop = grid on top /
 * dock below; flat = hinge ignored) once that lands. Rule is present so the dependency resolves.
 */
@RunWith(AndroidJUnit4::class)
class L9HingeWindowTestingTest {
    @get:Rule
    val publisher = WindowLayoutInfoPublisherRule()

    @Ignore("Sprite: wire WindowInfoTracker / FoldingFeature into home layout before L9 can assert hinge geometry")
    @Test fun bookTabletopFlatNoHingeIntersection() {
        error("unreachable until Sprite lands WindowInfoTracker consumption")
    }
}
