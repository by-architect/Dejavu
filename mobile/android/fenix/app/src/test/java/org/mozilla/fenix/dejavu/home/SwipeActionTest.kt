/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import io.mockk.mockk
import mozilla.components.browser.state.state.createTab
import mozilla.components.concept.engine.EngineSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.fenix.dejavu.actions.CustomAction
import org.mozilla.fenix.dejavu.actions.HttpMethod
import org.mozilla.fenix.dejavu.actions.RowAction
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.actions.swipeActionOf
import org.mozilla.fenix.dejavu.actions.swipeChoices
import org.mozilla.fenix.dejavu.workspaces.PinKind
import org.mozilla.fenix.dejavu.workspaces.PinnedItem

class SwipeActionTest {
    private val session: EngineSession = mockk(relaxed = true)
    private val awake = createTab(url = "https://awake.example/", id = "awake", engineSession = session)
    private val asleep = createTab(url = "https://asleep.example/", id = "asleep")

    private fun pin(id: String, tabId: String?) = PinnedItem(
        id = id,
        workspaceId = "workspace",
        parentId = null,
        kind = PinKind.TAB,
        title = id,
        url = "https://$id.example/",
        tabId = tabId,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun action(action: TabAction) = RowAction.BuiltIn(action)

    @Test
    fun `a way without an action leaves the row unswiped`() {
        assertNull(swipeAction(null, ActionTargets(tabs = listOf(awake)), isPinned = false))
    }

    @Test
    fun `Close closes an unpinned tab and puts a pinned one to sleep, but never unpins it`() {
        val close = action(TabAction.CLOSE)

        assertEquals("close", swipeAction(close, ActionTargets(tabs = listOf(asleep)), isPinned = false)?.key)
        assertEquals("sleep", swipeAction(close, ActionTargets.ofPin(pin("a", awake.id), awake), isPinned = true)?.key)
        assertNull(swipeAction(close, ActionTargets.ofPin(pin("b", asleep.id), asleep), isPinned = true))
    }

    @Test
    fun `an action that does nothing for the row leaves it unswiped`() {
        val pinned = ActionTargets.ofPin(pin("a", awake.id), awake)
        val unpinned = ActionTargets(tabs = listOf(awake))

        assertNull(swipeAction(action(TabAction.PIN), pinned, isPinned = true))
        assertEquals("pin", swipeAction(action(TabAction.PIN), unpinned, isPinned = false)?.key)
        assertNull(swipeAction(action(TabAction.UNPIN), unpinned, isPinned = false))
        assertEquals("unpin", swipeAction(action(TabAction.UNPIN), pinned, isPinned = true)?.key)
        assertNull(swipeAction(action(TabAction.SLEEP), ActionTargets(tabs = listOf(asleep)), isPinned = false))
    }

    @Test
    fun `swipes run the actions of tab rows, the user's own included`() {
        val custom = CustomAction("id", "Save", HttpMethod.POST, "https://example.com", emptyList(), "{}")
        val keys = swipeChoices(listOf(custom)).map { it.key }

        assertTrue(keys.containsAll(listOf("close", "pin", "unpin", "delete", "custom:id")))
        assertFalse("split_view" in keys)
        assertFalse("rename_folder" in keys)
        assertEquals("custom:id", swipeActionOf("custom:id", listOf(custom))?.key)
        assertNull(swipeActionOf("custom:gone", listOf(custom)))
        assertNull(swipeActionOf("split_view", emptyList()))
    }
}
