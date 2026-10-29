/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.fenix.dejavu.actions.TabAction

class ReaderViewEntryTest {
    @Test
    fun `the menu starts with Back, Forward, Reload and Close, then Reader view`() {
        assertEquals(listOf("back", "forward", "refresh", "close"), MoreMenuLayout.DEFAULT.first())
        assertEquals("reader_view", MoreMenuLayout.DEFAULT[1].first())
        assertEquals(MoreMenuEntry.Tab(TabAction.CLOSE), MoreMenuLayout.entryOf("close", emptyList()))
        val entry = MoreMenuLayout.entryOf("reader_view", emptyList())
        assertEquals(MoreMenuEntry.BuiltIn(MoreMenuItem.READER_VIEW), entry)
    }

    @Test
    fun `every entry of the default menu exists, five rows with the extensions on one of their own`() {
        val rows = MoreMenuLayout.resolve(MoreMenuLayout.DEFAULT, emptyList())

        assertEquals(MoreMenuLayout.DEFAULT.map { it.size }, rows.map { it.size })
        assertEquals(listOf(MoreMenuEntry.BuiltIn(MoreMenuItem.EXTENSIONS)), rows[2])
        assertEquals(5, rows.size)
    }

    @Test
    fun `a menu that kept a first row the default had before still gives the actions bar its default`() {
        val withBack = listOf(listOf("back", "forward", "share", "refresh"), listOf("history"))
        val withReaderView = listOf(listOf("reader_view", "forward", "share", "refresh"), listOf("history"))

        assertEquals(ActionsBarLayout.DEFAULT, ActionsBarLayout.fromMenu(withBack))
        assertEquals(ActionsBarLayout.DEFAULT, ActionsBarLayout.fromMenu(withReaderView))
        assertEquals(ActionsBarLayout.DEFAULT, ActionsBarLayout.fromMenu(MoreMenuLayout.DEFAULT))
        val changed = ActionsBarLayout.fromMenu(listOf(listOf("back", "share")))
        assertEquals(listOf("back", "home", "share", "search"), changed)
    }
}
