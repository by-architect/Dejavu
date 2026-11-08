/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */


package org.mozilla.fenix.dejavu.home

import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.browser.tabstrip.SIDEBAR_DEFAULT_WIDTH_DP
import org.mozilla.fenix.browser.tabstrip.SIDEBAR_MAX_WINDOW_FRACTION
import org.mozilla.fenix.browser.tabstrip.SIDEBAR_MIN_WIDTH_DP
import org.mozilla.fenix.browser.tabstrip.sidebarSettlesOpen
import org.mozilla.fenix.browser.tabstrip.sidebarWidthDp
import org.mozilla.fenix.utils.Settings
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TabSidebarTest {
    @Test
    fun `no wished width, or a reset one, is the default width`() {
        assertEquals(SIDEBAR_DEFAULT_WIDTH_DP, sidebarWidthDp(0f, 1600f))
        assertEquals(SIDEBAR_DEFAULT_WIDTH_DP, sidebarWidthDp(-5f, 1600f))
    }

    @Test
    fun `a wished width stays between the minimum and a share of the window`() {
        assertEquals(SIDEBAR_MIN_WIDTH_DP, sidebarWidthDp(50f, 1600f))
        assertEquals(1600f * SIDEBAR_MAX_WINDOW_FRACTION, sidebarWidthDp(5000f, 1600f))
        assertEquals(400f, sidebarWidthDp(400f, 1600f))
    }

    @Test
    fun `a window too narrow for both gives the minimum`() {
        assertEquals(SIDEBAR_MIN_WIDTH_DP, sidebarWidthDp(0f, 300f))
        assertEquals(SIDEBAR_MIN_WIDTH_DP, sidebarWidthDp(500f, 300f))
    }

    @Test
    fun `the auto hidden sidebar opens past a third of its width or on a fling out`() {
        assertTrue(sidebarSettlesOpen(pulled = 200f, width = 400f, velocity = 0f, flingVelocity = 100f))
        assertFalse(sidebarSettlesOpen(pulled = 100f, width = 400f, velocity = 0f, flingVelocity = 100f))
        assertTrue(sidebarSettlesOpen(pulled = 10f, width = 400f, velocity = 150f, flingVelocity = 100f))
        assertFalse(sidebarSettlesOpen(pulled = 390f, width = 400f, velocity = -150f, flingVelocity = 100f))
    }

    @Test
    fun `the sidebar replaces the strip only where it is on and the window is large`() {
        val settings = Settings(testContext)
        settings.isVerticalTabsEnabled = true
        assertTrue(settings.isTabSidebarShown(isLargeWindow = true))
        assertFalse(settings.isTabSidebarShown(isLargeWindow = false))

        settings.isVerticalTabsEnabled = false
        assertFalse(settings.isTabSidebarShown(isLargeWindow = true))
    }
}
