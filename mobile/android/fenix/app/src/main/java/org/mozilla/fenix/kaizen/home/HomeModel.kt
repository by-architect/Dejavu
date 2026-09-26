/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.home

import mozilla.components.browser.state.state.TabSessionState
import org.mozilla.fenix.kaizen.actions.RowAction
import org.mozilla.fenix.kaizen.actions.TabAction
import org.mozilla.fenix.kaizen.workspaces.MAX_FOLDER_DEPTH
import org.mozilla.fenix.kaizen.workspaces.PinnedItem
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState

/** Tabs, pinned tabs (essentials included) and folders picked in selection mode. */
data class Selection(
    val tabIds: Set<String> = emptySet(),
    val pinIds: Set<String> = emptySet(),
    val folderIds: Set<String> = emptySet(),
) {
    val size: Int
        get() = tabIds.size + pinIds.size + folderIds.size

    fun toggleTab(id: String) = copy(tabIds = tabIds.toggle(id))

    fun togglePin(id: String) = copy(pinIds = pinIds.toggle(id))

    fun toggleFolder(id: String) = copy(folderIds = folderIds.toggle(id))

    operator fun plus(other: Selection) =
        Selection(tabIds + other.tabIds, pinIds + other.pinIds, folderIds + other.folderIds)

    private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id
}

/**
 * What an action applies to.
 *
 * @property tabs Open tabs that are not pinned.
 * @property pins Pinned tabs and essentials, open or closed, that are not inside one of [folders].
 * @property pinnedTabs Open browser tabs backing [pins].
 * @property folders Folders that are not inside another one of [folders].
 * @property folderPins Pinned tabs anywhere inside [folders].
 * @property folderTabs Open browser tabs backing [folderPins].
 * @property canAddSubfolder Whether [folders] is a single folder that can hold one more level of folders.
 * @property splitTabIds Open tabs among the targets that are part of a split view.
 */
data class ActionTargets(
    val tabs: List<TabSessionState> = emptyList(),
    val pins: List<PinnedItem> = emptyList(),
    val pinnedTabs: List<TabSessionState> = emptyList(),
    val folders: List<PinnedItem> = emptyList(),
    val folderPins: List<PinnedItem> = emptyList(),
    val folderTabs: List<TabSessionState> = emptyList(),
    val canAddSubfolder: Boolean = false,
    val splitTabIds: Set<String> = emptySet(),
) {
    /** Every pinned tab the action reaches, the ones inside [folders] included. */
    val allPins: List<PinnedItem>
        get() = pins + folderPins

    val openTabs: List<TabSessionState>
        get() = tabs + pinnedTabs + folderTabs

    val awakeTabs: List<TabSessionState>
        get() = openTabs.filter { it.isAwake }

    /** Pinned items that move as a whole: [pins] and [folders] with everything inside them. */
    val itemIds: Set<String>
        get() = (pins + folders).map { it.id }.toSet()

    /** Whether the targets are exactly one folder. */
    val isSingleFolder: Boolean
        get() = folders.size == 1 && tabs.isEmpty() && pins.isEmpty()

    /** URL and title of every target, preferring the live page of open pinned tabs. */
    val links: List<Pair<String, String>>
        get() {
            val open = (pinnedTabs + folderTabs).associateBy { it.id }
            return tabs.map { it.content.url to it.content.title } + allPins.map { pin ->
                val tab = pin.tabId?.let { open[it] }
                if (tab != null) tab.content.url to tab.content.title else pin.url.orEmpty() to pin.title
            }
        }

    /** Containers the targets are in; `null` stands for no container. */
    val containerIds: Set<String?>
        get() {
            val open = (pinnedTabs + folderTabs).associateBy { it.id }
            val pinContainers = allPins.map { pin ->
                val tab = pin.tabId?.let { open[it] }
                if (tab != null) tab.contextId else pin.containerId
            }
            return (tabs.map { it.contextId } + pinContainers).toSet()
        }

    /** Pinned tabs whose open tab has left the pinned page, with that tab. */
    val changedPins: List<Pair<PinnedItem, TabSessionState>>
        get() {
            val open = (pinnedTabs + folderTabs).associateBy { it.id }
            return allPins.mapNotNull { pin ->
                val tab = pin.tabId?.let { open[it] } ?: return@mapNotNull null
                val url = pin.url ?: return@mapNotNull null
                (pin to tab).takeIf { comparable(tab.content.url) != comparable(url) }
            }
        }

    fun isEmpty() = tabs.isEmpty() && pins.isEmpty() && folders.isEmpty()

    companion object {
        fun of(state: WorkspaceState, tabs: List<TabSessionState>, selection: Selection): ActionTargets {
            val selectedFolders = state.pins.filter { it.isFolder && it.id in selection.folderIds }
            val nested = selectedFolders.flatMap { state.descendantIds(it.id) }.toSet()
            val folders = selectedFolders.filter { it.id !in nested }
            val pins = state.pins.filter { !it.isFolder && it.id in selection.pinIds && it.id !in nested }
            val folderPins = state.pins.filter { !it.isFolder && it.id in nested }
            val targets = ActionTargets(
                tabs = tabs.filter { it.id in selection.tabIds },
                pins = pins,
                pinnedTabs = tabs.backing(pins),
                folders = folders,
                folderPins = folderPins,
                folderTabs = tabs.backing(folderPins),
                canAddSubfolder = folders.singleOrNull()?.let { state.folderDepth(it.id) < MAX_FOLDER_DEPTH } == true,
            )
            return targets.copy(splitTabIds = targets.openTabs.map { it.id }.filter { state.splitOf(it) != null }.toSet())
        }

        /** A pinned tab or essential with its open tab, if any. */
        fun ofPin(pin: PinnedItem, tab: TabSessionState?) = ActionTargets(pins = listOf(pin), pinnedTabs = listOfNotNull(tab))

        private fun List<TabSessionState>.backing(pins: List<PinnedItem>): List<TabSessionState> {
            val tabIds = pins.mapNotNull { it.tabId }.toSet()
            return filter { it.id in tabIds }
        }

        private fun comparable(url: String) = url.substringBefore('#').trimEnd('/')
    }
}

/** Whether the tab currently holds a loaded page in memory, as opposed to sleeping or not restored yet. */
val TabSessionState.isAwake: Boolean
    get() = engineState.engineSession != null

val TabSessionState.displayTitle: String
    get() = content.title.ifBlank { content.url }

/** Whether [this] action can do anything for [targets]. */
@Suppress("CyclomaticComplexMethod")
fun TabAction.appliesTo(targets: ActionTargets): Boolean = when (this) {
    TabAction.CLOSE -> targets.openTabs.isNotEmpty()
    TabAction.PIN -> targets.tabs.isNotEmpty()
    TabAction.UNPIN -> targets.pins.any { !it.essential }
    TabAction.SLEEP -> targets.awakeTabs.isNotEmpty()
    TabAction.BOOKMARK, TabAction.SHARE, TabAction.COPY_LINK -> targets.links.any { it.first.isNotBlank() }
    TabAction.DUPLICATE -> targets.folders.isEmpty() && (targets.tabs.isNotEmpty() || targets.pins.isNotEmpty())
    TabAction.RESET_PIN -> targets.changedPins.isNotEmpty()
    TabAction.ADD_TO_ESSENTIALS ->
        targets.folders.isEmpty() && (targets.tabs.isNotEmpty() || targets.pins.any { !it.essential })
    TabAction.REMOVE_FROM_ESSENTIALS -> targets.pins.any { it.essential }
    TabAction.NEW_FOLDER -> !targets.isEmpty() && !targets.isSingleFolder
    TabAction.NEW_SUBFOLDER -> targets.isSingleFolder && targets.canAddSubfolder
    TabAction.RENAME_FOLDER -> targets.isSingleFolder
    TabAction.UNPACK_FOLDER -> targets.folders.isNotEmpty() && targets.tabs.isEmpty() && targets.pins.isEmpty()
    TabAction.DELETE -> targets.pins.isNotEmpty() || targets.folders.isNotEmpty()
    TabAction.MOVE_TO_FOLDER, TabAction.MOVE_TO_WORKSPACE -> !targets.isEmpty()
    TabAction.CHANGE_CONTAINER -> targets.tabs.isNotEmpty() || targets.allPins.isNotEmpty()
    TabAction.SPLIT_VIEW -> targets.folders.isEmpty() && (targets.tabs + targets.pinnedTabs).size == 2
    TabAction.UNSPLIT -> targets.splitTabIds.isNotEmpty()
}

/** Whether [this] action can do anything for [targets]. */
fun RowAction.appliesTo(targets: ActionTargets): Boolean = when (this) {
    is RowAction.BuiltIn -> action.appliesTo(targets)
    is RowAction.Custom -> targets.links.any { it.first.isNotBlank() }
}

/**
 * The buttons of one tab row: the user's choice, where Close becomes Unpin on a pinned tab that is closed, without
 * the actions that do nothing for this tab.
 */
fun rowActions(configured: List<RowAction>, targets: ActionTargets, isPinned: Boolean): List<RowAction> =
    configured
        .map {
            val closesClosedPin = it is RowAction.BuiltIn && it.action == TabAction.CLOSE && isPinned &&
                targets.openTabs.isEmpty()
            if (closesClosedPin) RowAction.BuiltIn(TabAction.UNPIN) else it
        }
        .distinctBy { it.key }
        .filter { it.appliesTo(targets) }

/** Folder that a new folder grouping [targets] goes in: their common parent, while it can hold one more level. */
fun WorkspaceState.newFolderParent(targets: ActionTargets): String? =
    (targets.pins + targets.folders).map { it.parentId }.distinct().singleOrNull()
        ?.takeIf { folderDepth(it) < MAX_FOLDER_DEPTH }

/** Where dragged tabs, pinned items and folders are dropped. */
sealed interface DropTarget {
    /** Into a folder of the pinned section. */
    data class IntoFolder(val folderId: String) : DropTarget

    /** Next to a pinned tab or folder, as its sibling. */
    data class NextToPin(val pinId: String, val after: Boolean) : DropTarget

    /** At the start or the end of the pinned section's top level. */
    data class PinnedEdge(val atEnd: Boolean) : DropTarget

    /** Next to an unpinned tab. */
    data class NextToTab(val tabId: String, val after: Boolean) : DropTarget

    /** At the start of the unpinned tabs, right below New Tab. */
    data object UnpinnedStart : DropTarget

    /** Into the essentials shared by every workspace. */
    data object Essentials : DropTarget
}

/** Whether a drag on a workspace page can be dropped into the essentials, and whether it would be now. */
enum class EssentialsDrop { NONE, AVAILABLE, ACTIVE }

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
    data class DeleteItems(val targets: ActionTargets) : HomeDialog
    data class MoveToFolder(val workspaceId: String, val targets: ActionTargets) : HomeDialog
    data class MoveToWorkspace(val workspaceId: String, val targets: ActionTargets) : HomeDialog
    data class ChangeContainer(val targets: ActionTargets) : HomeDialog
}
