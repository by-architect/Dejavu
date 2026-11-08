/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.fenix.dejavu.settings.BarButton
import org.mozilla.fenix.dejavu.settings.SidebarLook

class SidebarMoreMenuTest {
    private fun entry(item: MoreMenuItem) = MoreMenuEntry.BuiltIn(item)

    @Test
    fun `the default sidebar shows the controls and the bottom bar`() {
        assertEquals(
            setOf(
                MoreMenuItem.BACK, MoreMenuItem.FORWARD, MoreMenuItem.REFRESH, MoreMenuItem.DOWNLOADS,
                MoreMenuItem.EXTENSIONS, MoreMenuItem.BOOKMARKS, MoreMenuItem.HISTORY,
            ),
            sidebarShownItems(SidebarLook()),
        )
    }

    @Test
    fun `parts the sidebar does not show stay in the menu`() {
        val look = SidebarLook(
            showPageControls = false,
            hiddenBarButtons = setOf(BarButton.HISTORY, BarButton.EXTENSIONS),
        )

        assertEquals(
            setOf(MoreMenuItem.DOWNLOADS, MoreMenuItem.BOOKMARKS),
            sidebarShownItems(look),
        )
        val bare = SidebarLook(showPageControls = false, showBottomBar = false)
        assertEquals(emptySet<MoreMenuItem>(), sidebarShownItems(bare))
    }

    @Test
    fun `the compact menu keeps the order and fills rows of three`() {
        val rows = listOf(
            listOf(MoreMenuItem.BACK, MoreMenuItem.FORWARD, MoreMenuItem.SHARE, MoreMenuItem.REFRESH).map(::entry),
            listOf(
                MoreMenuItem.FIND_IN_PAGE, MoreMenuItem.HISTORY, MoreMenuItem.DESKTOP_SITE, MoreMenuItem.PIN_TAB,
            ).map(::entry),
            listOf(entry(MoreMenuItem.EXTENSIONS)),
            listOf(entry(MoreMenuItem.SETTINGS)),
        )

        val compact = compactRows(rows, sidebarShownItems(SidebarLook()), COMPACT_MENU_COLUMNS)

        assertEquals(
            listOf(
                listOf(entry(MoreMenuItem.SHARE), entry(MoreMenuItem.FIND_IN_PAGE), entry(MoreMenuItem.DESKTOP_SITE)),
                listOf(entry(MoreMenuItem.PIN_TAB), entry(MoreMenuItem.SETTINGS)),
            ),
            compact,
        )
    }

    @Test
    fun `an extensions item kept in the menu keeps its own row`() {
        val rows = listOf(
            listOf(entry(MoreMenuItem.SHARE), entry(MoreMenuItem.EXTENSIONS)),
            listOf(entry(MoreMenuItem.SETTINGS)),
        )

        val compact = compactRows(rows, emptySet(), COMPACT_MENU_COLUMNS)

        assertEquals(
            listOf(
                listOf(entry(MoreMenuItem.SHARE)),
                listOf(entry(MoreMenuItem.EXTENSIONS)),
                listOf(entry(MoreMenuItem.SETTINGS)),
            ),
            compact,
        )
    }
}
