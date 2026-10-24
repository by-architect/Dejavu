/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mozilla.fenix.dejavu.menu.AddressBarButtons
import org.mozilla.fenix.dejavu.menu.BarSide.END
import org.mozilla.fenix.dejavu.menu.BarSide.START
import org.mozilla.fenix.dejavu.settings.MenuLinesEditing.BAR

class AddressBarEditingTest {
    private val sides = AddressBarButtons(start = "reload", end = "home")
    private val lines = listOf(
        listOf("forward", "share", "home", "reload"),
        listOf("back", "forward", "share"),
        listOf("history", "bookmarks", "downloads", "passwords"),
    )

    @Test
    fun `a new button takes the side it is dropped on`() {
        assertEquals(
            AddressBarButtons("print", "home") to lines,
            AddressBarEditing.dropOnSide(sides, lines, MenuDragSource.New("print"), START),
        )
        assertEquals(
            AddressBarButtons(null, "print") to lines,
            AddressBarEditing.dropOnSide(AddressBarButtons(), lines, MenuDragSource.New("print"), END),
        )
    }

    @Test
    fun `the buttons of the two sides swap`() {
        val moved = AddressBarEditing.dropOnSide(sides, lines, MenuDragSource.Side("home", END), START)

        assertEquals(AddressBarButtons("home", "reload") to lines, moved)
    }

    @Test
    fun `a button from a line leaves it, and the one on the side takes its place there where it can`() {
        val fromRow = AddressBarEditing.dropOnSide(sides, lines, MenuDragSource.Placed("bookmarks", 2, 1), START)
        assertEquals(AddressBarButtons("bookmarks", "home"), fromRow.first)
        assertEquals(listOf("history", "reload", "downloads", "passwords"), fromRow.second[2])

        // Home only goes into the actions bar, so the row just loses the button.
        val homeOut = AddressBarEditing.dropOnSide(sides, lines, MenuDragSource.Placed("share", 1, 2), END)
        assertEquals(AddressBarButtons("reload", "share"), homeOut.first)
        assertEquals(listOf("back", "forward"), homeOut.second[1])

        val intoBar = AddressBarEditing.dropOnSide(sides, lines, MenuDragSource.Placed("share", BAR, 1), END)
        assertEquals(listOf("forward", "home", "home", "reload"), intoBar.second[BAR])
    }

    @Test
    fun `a button from a side goes into a line and leaves the side, or swaps with the button of a full line`() {
        val intoRow = AddressBarEditing.dropOnLine(sides, lines, START, 1, 3)!!
        assertEquals(AddressBarButtons(null, "home"), intoRow.first)
        assertEquals(listOf("back", "forward", "share", "reload"), intoRow.second[1])

        val swapped = AddressBarEditing.dropOnLine(sides, lines, START, 2, 1)!!
        assertEquals(AddressBarButtons("bookmarks", "home"), swapped.first)
        assertEquals(listOf("history", "reload", "downloads", "passwords"), swapped.second[2])
    }

    @Test
    fun `home and search only go from a side into the actions bar`() {
        assertNull(AddressBarEditing.dropOnLine(sides, lines, END, 1, 0))
        val intoBar = AddressBarEditing.dropOnLine(sides, lines, END, BAR, 0)!!
        assertEquals(listOf("home", "forward", "share", "home", "reload"), intoBar.second[BAR])
        assertEquals(AddressBarButtons("reload", null), intoBar.first)
    }

    @Test
    fun `a tapped button goes to the first side without one`() {
        assertEquals(AddressBarButtons("search", null), AddressBarEditing.fill(AddressBarButtons(), "search"))
        assertEquals(
            AddressBarButtons("reload", "search"),
            AddressBarEditing.fill(AddressBarButtons("reload"), "search"),
        )
        assertEquals(sides, AddressBarEditing.fill(sides, "search"))
    }
}
