/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.features

import android.content.Context
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FeatureTourTest {
    private val newest = FeatureTour.Releases.last()

    private fun newSettings(): DejavuSettings =
        DejavuSettings::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(testContext)

    @Test
    fun `a fresh install shows the basics, then what the newest version brought`() {
        val settings = newSettings()

        assertEquals(listOf(FeatureTour.Basics, newest), FeatureTour.dueChapters(settings, updated = false))
    }

    @Test
    fun `an update from a version before 2_0 shows every version since, without the basics`() {
        val fromOld = newSettings()
        assertEquals(FeatureTour.Releases, FeatureTour.dueChapters(fromOld, updated = true))

        // Dejavu 1.6 showed the basics, so its users get the versions since even when the update looks fresh.
        val from16 = newSettings().apply { featureTourSeen = FeatureTour.VERSION }
        assertEquals(FeatureTour.Releases, FeatureTour.dueChapters(from16, updated = false))
    }

    @Test
    fun `once seen, only later versions show`() {
        val settings = newSettings()
        FeatureTour.markSeen(settings)

        assertEquals(newest.version, settings.whatsNewSeen)
        assertTrue(FeatureTour.dueChapters(settings, updated = true).isEmpty())
        settings.whatsNewSeen = "1.9"
        val since19 = FeatureTour.Releases.filter { FeatureTour.compareVersions(it.version!!, "1.9") > 0 }
        assertEquals(since19, FeatureTour.dueChapters(settings, updated = true))
    }

    @Test
    fun `versions compare part by part`() {
        assertTrue(FeatureTour.compareVersions("2.0", "1.8") > 0)
        assertTrue(FeatureTour.compareVersions("1.10", "1.9") > 0)
        assertEquals(0, FeatureTour.compareVersions("2", "2.0"))
        assertTrue(FeatureTour.compareVersions("2.0", "2.0.1") < 0)
    }
}
