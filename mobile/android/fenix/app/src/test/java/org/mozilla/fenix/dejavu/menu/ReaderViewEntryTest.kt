/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderViewEntryTest {
    @Test
    fun `the menu starts with Reader view where Back was`() {
        assertEquals(listOf("reader_view", "forward", "share", "refresh"), MoreMenuLayout.DEFAULT.first())
        val entry = MoreMenuLayout.entryOf("reader_view", emptyList())
        assertEquals(MoreMenuEntry.BuiltIn(MoreMenuItem.READER_VIEW), entry)
    }

    @Test
    fun `a menu that kept the first row it had before Reader view still gives the actions bar its default`() {
        val withBack = listOf(listOf("back", "forward", "share", "refresh"), listOf("history"))

        assertEquals(ActionsBarLayout.DEFAULT, ActionsBarLayout.fromMenu(withBack))
        assertEquals(ActionsBarLayout.DEFAULT, ActionsBarLayout.fromMenu(MoreMenuLayout.DEFAULT))
        val changed = ActionsBarLayout.fromMenu(listOf(listOf("back", "share")))
        assertEquals(listOf("back", "home", "share", "search"), changed)
    }
}
