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
 * Covered on the JVM: production reads posture via `WindowInfoTracker` (ui/FoldPosture.kt), and
 * `FoldHalfTest` (Robolectric, same rule) asserts book / tabletop / flat / no-feature, LTR and RTL,
 * page kept and drag cancelled, in CI. This instrumented copy stays ignored (CI runs no emulator).
 */
@RunWith(AndroidJUnit4::class)
class L9HingeWindowTestingTest {
    @get:Rule
    val publisher = WindowLayoutInfoPublisherRule()

    @Ignore("L9 runs on the JVM: see app/src/test/.../ui/FoldHalfTest.kt")
    @Test fun bookTabletopFlatNoHingeIntersection() {
        error("see FoldHalfTest")
    }
}
