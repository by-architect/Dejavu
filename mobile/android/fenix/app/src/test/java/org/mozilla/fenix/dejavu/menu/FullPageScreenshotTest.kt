/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullPageScreenshotTest {
    @Test
    fun `keeps screenshots within the limits`() {
        assertEquals(1080 to 5000, cappedScreenshotSize(1080, 5000))
        assertEquals(235 to 16_384, cappedScreenshotSize(1440, 100_000))
        assertEquals(2896 to 5792, cappedScreenshotSize(4096, 8192))
        listOf(1440 to 100_000, 4096 to 8192, 1 to 1_000_000, 20_000 to 100).forEach { (width, height) ->
            val (w, h) = cappedScreenshotSize(width, height)
            assertTrue("$w x $h", w in 1..MAX_SCREENSHOT_DIMENSION && h in 1..MAX_SCREENSHOT_DIMENSION)
            assertTrue("$w x $h", w.toLong() * h * 4 <= MAX_SCREENSHOT_BYTES)
        }
    }
}
