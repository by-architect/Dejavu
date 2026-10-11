/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import io.mockk.mockk
import mozilla.components.browser.state.state.createTab
import mozilla.components.concept.engine.EngineSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.fenix.dejavu.actions.RowAction
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.workspaces.PinKind
import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.SyncedDevice
import org.mozilla.fenix.dejavu.workspaces.Workspace

class RowActionsTest {
    private val session: EngineSession = mockk(relaxed = true)
    private val awake = createTab(url = "https://awake.example/", id = "awake", engineSession = session)
    private val asleep = createTab(url = "https://asleep.example/", id = "asleep")

    private fun pin(id: String, tabId: String?, kind: PinKind = PinKind.TAB, parentId: String? = null) = PinnedItem(
        id = id,
        workspaceId = "workspace",
        parentId = parentId,
        kind = kind,
        title = id,
        url = "https://$id.example/".takeIf { kind == PinKind.TAB },
        tabId = tabId,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun keys(actions: List<RowAction>) = actions.map { it.key }

    private val close = listOf(RowAction.BuiltIn(TabAction.CLOSE))

    @Test
    fun `Close puts an awake pinned tab to sleep, and unpins it once it sleeps or is closed`() {
        val awakePin = pin("a", awake.id)
        val asleepPin = pin("b", asleep.id)
        val closedPin = pin("c", tabId = null)

        assertEquals(listOf("sleep"), keys(rowActions(close, ActionTargets.ofPin(awakePin, awake), isPinned = true)))
        assertEquals(listOf("unpin"), keys(rowActions(close, ActionTargets.ofPin(asleepPin, asleep), isPinned = true)))
        assertEquals(listOf("unpin"), keys(rowActions(close, ActionTargets.ofPin(closedPin, null), isPinned = true)))
    }

    @Test
    fun `Close keeps closing unpinned tabs, awake or asleep`() {
        assertEquals(listOf("close"), keys(rowActions(close, ActionTargets(tabs = listOf(awake)), isPinned = false)))
        assertEquals(listOf("close"), keys(rowActions(close, ActionTargets(tabs = listOf(asleep)), isPinned = false)))
    }

    @Test
    fun `Close on a folder sleeps its tabs and goes away once all of them sleep`() {
        val folder = pin("folder", tabId = null, kind = PinKind.FOLDER)
        val awakeTargets = ActionTargets(folders = listOf(folder), folderPins = listOf(pin("a", awake.id)), folderTabs = listOf(awake))
        val asleepTargets = ActionTargets(folders = listOf(folder), folderPins = listOf(pin("b", asleep.id)), folderTabs = listOf(asleep))

        assertEquals(listOf("sleep"), keys(rowActions(close, awakeTargets, isPinned = true)))
        assertEquals(emptyList<String>(), keys(rowActions(close, asleepTargets, isPinned = true)))
    }

    @Test
    fun `Sleep only sleeps and shows once next to Close`() {
        val sleepAndClose = listOf(RowAction.BuiltIn(TabAction.SLEEP), RowAction.BuiltIn(TabAction.CLOSE))
        val awakePin = pin("a", awake.id)
        val asleepPin = pin("b", asleep.id)

        assertEquals(listOf("sleep"), keys(rowActions(sleepAndClose, ActionTargets.ofPin(awakePin, awake), isPinned = true)))
        assertEquals(listOf("unpin"), keys(rowActions(sleepAndClose, ActionTargets.ofPin(asleepPin, asleep), isPinned = true)))
        assertEquals(
            listOf("sleep", "close"),
            keys(rowActions(sleepAndClose, ActionTargets(tabs = listOf(awake)), isPinned = false)),
        )
    }

    @Test
    fun `in the workspace of a device, tabs can only be opened and closed, pinned ones too`() {
        val everything = TabAction.entries.map { RowAction.BuiltIn(it) }
        val tab = ActionTargets(tabs = listOf(asleep), restricted = true)
        val pinned = ActionTargets.ofPin(pin("a", awake.id), awake, restricted = true)

        assertEquals(
            listOf("close", "bookmark", "share", "copy_link", "duplicate"),
            keys(rowActions(everything, tab, isPinned = false)).filterNot { it == "sleep" },
        )
        assertEquals(listOf("close"), keys(rowActions(close, pinned, isPinned = true)))
        assertEquals(listOf("close"), keys(rowActions(close, ActionTargets.ofPin(pin("c", null), null, true), isPinned = true)))
        val folder = ActionTargets(
            folders = listOf(pin("folder", tabId = null, kind = PinKind.FOLDER)),
            folderPins = listOf(pin("b", awake.id)),
            folderTabs = listOf(awake),
            restricted = true,
        )
        assertEquals(listOf("sleep"), keys(rowActions(close + RowAction.BuiltIn(TabAction.RENAME_FOLDER), folder, isPinned = true)))
    }

    @Test
    fun `the workspace of a device only takes tabs, not pinned tabs or folders`() {
        val own = Workspace(id = "own", name = "Own", createdAt = 1L, updatedAt = 1L)
        val computer = own.copy(id = "computer", device = SyncedDevice.DESKTOP)
        val tabs = ActionTargets(tabs = listOf(awake, asleep))
        val pinned = ActionTargets(tabs = listOf(awake), pins = listOf(pin("a", asleep.id)))
        val folder = ActionTargets(folders = listOf(pin("folder", tabId = null, kind = PinKind.FOLDER)))

        assertTrue(computer.canTake(tabs))
        assertFalse(computer.canTake(pinned))
        assertFalse(computer.canTake(folder))
        assertTrue(own.canTake(pinned))
        assertTrue(own.canTake(folder))
    }
}
