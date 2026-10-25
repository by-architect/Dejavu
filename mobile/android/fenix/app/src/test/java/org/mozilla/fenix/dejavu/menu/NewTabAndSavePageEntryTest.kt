/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewTabAndSavePageEntryTest {
    @Test
    fun `new tab and save page can be put in the menu and on the bars`() {
        assertEquals(MoreMenuEntry.BuiltIn(MoreMenuItem.NEW_TAB), MoreMenuLayout.entryOf("new_tab", emptyList()))
        assertEquals(MoreMenuEntry.BuiltIn(MoreMenuItem.SAVE_PAGE), MoreMenuLayout.entryOf("save_page", emptyList()))

        val menu = MoreMenuLayout.available(emptyList())
        assertTrue(MoreMenuEntry.BuiltIn(MoreMenuItem.NEW_TAB) in menu)
        assertTrue(MoreMenuEntry.BuiltIn(MoreMenuItem.SAVE_PAGE) in menu)
        val bar = ActionsBarLayout.available(emptyList())
        assertTrue(MoreMenuEntry.BuiltIn(MoreMenuItem.NEW_TAB) in bar)
        assertTrue(MoreMenuEntry.BuiltIn(MoreMenuItem.SAVE_PAGE) in bar)
    }

    @Test
    fun `the default menu keeps its entries`() {
        val keys = MoreMenuLayout.DEFAULT.flatten()

        assertFalse("new_tab" in keys)
        assertFalse("save_page" in keys)
    }

    @Test
    fun `save page sits next to save as PDF in the list of entries`() {
        val items = MoreMenuItem.entries

        assertEquals(items.indexOf(MoreMenuItem.SAVE_AS_PDF) - 1, items.indexOf(MoreMenuItem.SAVE_PAGE))
    }
}
