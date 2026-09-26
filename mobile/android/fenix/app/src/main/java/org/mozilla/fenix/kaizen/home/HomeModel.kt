/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.home

import mozilla.components.browser.state.state.TabSessionState
import org.mozilla.fenix.kaizen.actions.TabAction
import org.mozilla.fenix.kaizen.workspaces.PinnedItem
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState

/** Tabs and pinned tabs picked in selection mode. */
data class Selection(
    val tabIds: Set<String> = emptySet(),
    val pinIds: Set<String> = emptySet(),
) {
    val size: Int
        get() = tabIds.size + pinIds.size

    fun toggleTab(id: String) = copy(tabIds = if (id in tabIds) tabIds - id else tabIds + id)

    fun togglePin(id: String) = copy(pinIds = if (id in pinIds) pinIds - id else pinIds + id)
}

/**
 * What an action applies to.
 *
 * @property tabs Open tabs that are not pinned.
 * @property pins Pinned tabs, open or closed.
 * @property pinnedTabs Open browser tabs backing [pins].
 */
data class ActionTargets(
    val tabs: List<TabSessionState> = emptyList(),
    val pins: List<PinnedItem> = emptyList(),
    val pinnedTabs: List<TabSessionState> = emptyList(),
) {
    val openTabs: List<TabSessionState>
        get() = tabs + pinnedTabs

    val awakeTabs: List<TabSessionState>
        get() = openTabs.filter { it.isAwake }

    /** URL and title of every target, preferring the live page of open pinned tabs. */
    val links: List<Pair<String, String>>
        get() = tabs.map { it.content.url to it.content.title } + pins.map { pin ->
            val tab = pinnedTabs.firstOrNull { it.id == pin.tabId }
            if (tab != null) tab.content.url to tab.content.title else pin.url.orEmpty() to pin.title
        }

    fun isEmpty() = tabs.isEmpty() && pins.isEmpty()

    companion object {
        fun of(state: WorkspaceState, tabs: List<TabSessionState>, selection: Selection): ActionTargets =
            ofPins(state.pins.filter { it.id in selection.pinIds && !it.isFolder }, tabs)
                .copy(tabs = tabs.filter { it.id in selection.tabIds })

        /** Every pinned tab inside [folderId], including its subfolders. */
        fun ofFolder(state: WorkspaceState, tabs: List<TabSessionState>, folderId: String): ActionTargets {
            val nested = state.descendantIds(folderId)
            return ofPins(state.pins.filter { it.id in nested && !it.isFolder }, tabs)
        }

        private fun ofPins(pins: List<PinnedItem>, tabs: List<TabSessionState>): ActionTargets {
            val pinnedTabIds = pins.mapNotNull { it.tabId }.toSet()
            return ActionTargets(pins = pins, pinnedTabs = tabs.filter { it.id in pinnedTabIds })
        }
    }
}

/** Whether the tab currently holds a loaded page in memory, as opposed to sleeping or not restored yet. */
val TabSessionState.isAwake: Boolean
    get() = engineState.engineSession != null

val TabSessionState.displayTitle: String
    get() = content.title.ifBlank { content.url }

/** Whether [this] action can do anything for [targets]. */
fun TabAction.appliesTo(targets: ActionTargets): Boolean = when (this) {
    TabAction.CLOSE -> targets.openTabs.isNotEmpty()
    TabAction.PIN -> targets.tabs.isNotEmpty()
    TabAction.UNPIN -> targets.pins.isNotEmpty()
    TabAction.SLEEP -> targets.awakeTabs.isNotEmpty()
    else -> !targets.isEmpty()
}

/**
 * The buttons of one tab row: the user's choice, where Close becomes Unpin on a pinned tab that is closed, without
 * the actions that do nothing for this tab.
 */
fun rowActions(configured: List<TabAction>, targets: ActionTargets, isPinned: Boolean): List<TabAction> =
    configured
        .map { if (it == TabAction.CLOSE && isPinned && targets.openTabs.isEmpty()) TabAction.UNPIN else it }
        .distinct()
        .filter { it.appliesTo(targets) }

/** A pinned item and how deep it is nested in folders. */
data class PinnedEntry(val item: PinnedItem, val depth: Int)

/**
 * Flattens the pinned tree of [workspaceId] in display order. Children of collapsed folders are left out unless
 * [expandAll] is set. Items whose parent folder is missing are shown at the top level.
 */
fun WorkspaceState.pinnedTree(workspaceId: String, expandAll: Boolean = false): List<PinnedEntry> {
    val items = pins.filter { it.workspaceId == workspaceId }
    val folderIds = items.filter { it.isFolder }.map { it.id }.toSet()
    val byParent = items.groupBy { item -> item.parentId?.takeIf { it in folderIds } }
    val result = mutableListOf<PinnedEntry>()
    val visited = mutableSetOf<String>()

    fun visit(parentId: String?, depth: Int) {
        byParent[parentId].orEmpty().forEach { item ->
            if (!visited.add(item.id)) return@forEach
            result += PinnedEntry(item, depth)
            if (item.isFolder && (expandAll || !item.collapsed)) visit(item.id, depth + 1)
        }
    }
    visit(null, 0)
    return result
}

/** Dialogs of the home screen. */
sealed interface HomeDialog {
    data class EditWorkspace(val workspaceId: String?) : HomeDialog
    data class DeleteWorkspace(val workspaceId: String) : HomeDialog
    data class NewFolder(
        val workspaceId: String,
        val parentId: String?,
        val targets: ActionTargets = ActionTargets(),
    ) : HomeDialog
    data class RenameFolder(val folder: PinnedItem) : HomeDialog
    data class DeleteFolder(val folder: PinnedItem) : HomeDialog
    data class MoveToFolder(
        val workspaceId: String,
        val targets: ActionTargets,
        val folderIds: Set<String> = emptySet(),
    ) : HomeDialog
    data class MoveToWorkspace(
        val workspaceId: String,
        val targets: ActionTargets,
        val folderIds: Set<String> = emptySet(),
    ) : HomeDialog
}
