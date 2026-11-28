/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureTourTest {
    @Test
    fun `every feature shows once, under the version that brought it`() {
        val features = FeatureTour.Releases.flatMap { it.features }

        assertEquals(DejavuFeature.entries.toSet(), features.toSet())
        assertEquals(features.size, features.toSet().size)
    }

    @Test
    fun `the versions go from the oldest to the newest`() {
        FeatureTour.Releases.zipWithNext().forEach { (older, newer) ->
            assertTrue(FeatureTour.compareVersions(older.version, newer.version) < 0)
        }
    }

    @Test
    fun `versions compare part by part`() {
        assertTrue(FeatureTour.compareVersions("2.0", "1.8") > 0)
        assertTrue(FeatureTour.compareVersions("1.10", "1.9") > 0)
        assertEquals(0, FeatureTour.compareVersions("2", "2.0"))
        assertTrue(FeatureTour.compareVersions("2.0", "2.0.1") < 0)
    }

    @Test
    fun `data keeps the version it began with`() {
        assertEquals("4.1.0", startVersionOf(saved = "4.1.0", began = 0L))
        assertEquals("4.2.3", startVersionOf(saved = "4.2.3", began = null))
    }

    @Test
    fun `data from before 4_1 began with 4_0 when it began after 4_0 came out`() {
        // 2026-11-21 and 2026-11-13, the days after the first 4.0 build and 3.2.0 came out.
        assertEquals("4.0", startVersionOf(saved = null, began = 1_795_219_200_000L))
        assertEquals("3.0", startVersionOf(saved = null, began = 1_794_528_000_000L))
    }

    @Test
    fun `data without a start began before 3_0, which kept none`() {
        assertEquals("1.0", startVersionOf(saved = null, began = null))
    }
}
