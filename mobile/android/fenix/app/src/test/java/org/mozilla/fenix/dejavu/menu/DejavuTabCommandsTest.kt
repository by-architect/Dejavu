/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import mozilla.components.browser.state.state.recover.TabState
import org.junit.Assert.assertEquals
import org.junit.Test

class DejavuTabCommandsTest {
    private val normal = TabState(id = "normal", url = "https://normal.example")
    private val privateTab = TabState(id = "private", url = "https://private.example", private = true)

    @Test
    fun `undoes private tabs in private browsing`() {
        assertEquals(RestoreChoice.Undo, restoreChoiceOf(private = true, undoTabs = listOf(privateTab), closedTabs = listOf(normal)))
    }

    @Test
    fun `reopens normal tabs in normal browsing`() {
        assertEquals(RestoreChoice.Reopen, restoreChoiceOf(private = false, undoTabs = listOf(normal), closedTabs = emptyList()))
        assertEquals(
            RestoreChoice.Reopen,
            restoreChoiceOf(private = false, undoTabs = listOf(privateTab), closedTabs = listOf(normal)),
        )
    }

    @Test
    fun `never restores tabs of the other browsing mode`() {
        assertEquals(RestoreChoice.None, restoreChoiceOf(private = true, undoTabs = listOf(normal), closedTabs = listOf(normal)))
        assertEquals(RestoreChoice.None, restoreChoiceOf(private = false, undoTabs = listOf(privateTab), closedTabs = emptyList()))
        assertEquals(RestoreChoice.None, restoreChoiceOf(private = false, undoTabs = emptyList(), closedTabs = emptyList()))
    }
}
