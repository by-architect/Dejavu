/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */


package org.mozilla.fenix.dejavu.settings

import android.content.Context
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SidebarLookTest {
    private fun newSettings(): DejavuSettings =
        DejavuSettings::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(testContext)

    @Test
    fun `sizes saved out of range are brought into range when read`() {
        newSettings().setSidebarLook(SidebarLook(rowHeight = 500, siteIconSize = 1, barIconSize = 99))

        val look = newSettings().sidebarLook.value
        assertEquals(SidebarLook.ROW_HEIGHTS.last, look.rowHeight)
        assertEquals(SidebarLook.SITE_ICON_SIZES.first, look.siteIconSize)
        assertEquals(SidebarLook.BAR_ICON_SIZES.last, look.barIconSize)
    }

    @Test
    fun `a look is kept and reset goes back to the default`() {
        val settings = newSettings()
        val look = SidebarLook(
            rowHeight = 40,
            showBottomBar = false,
            hiddenBarButtons = setOf(BarButton.HISTORY, BarButton.EXTENSIONS),
        )
        settings.setSidebarLook(look)
        assertEquals(look, newSettings().sidebarLook.value)

        settings.resetSidebarLook()
        assertEquals(SidebarLook(), settings.sidebarLook.value)
        assertEquals(SidebarLook(), newSettings().sidebarLook.value)
    }

    @Test
    fun `the docked full screen home takes the sizes only`() {
        val look = SidebarLook(rowHeight = 40, showBottomBar = false, hiddenBarButtons = setOf(BarButton.DOWNLOADS))
        assertEquals(SidebarLook(rowHeight = 40), look.sizesOnly())
    }
}
