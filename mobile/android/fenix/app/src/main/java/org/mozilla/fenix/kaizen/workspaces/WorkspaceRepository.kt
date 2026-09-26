/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.workspaces

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mozilla.fenix.R
import java.util.UUID

/**
 * What is needed to pin an open tab.
 */
data class PinSource(
    val tabId: String,
    val url: String,
    val title: String,
    val containerId: String?,
)

/** Where dropped items go in a workspace's pinned section. */
sealed interface PinPlacement {
    /** At the end of [folderId]. */
    data class Into(val folderId: String) : PinPlacement

    /** Next to [itemId], as its sibling. */
    data class Next(val itemId: String, val after: Boolean) : PinPlacement

    /** At the start or the end of the top level. */
    data class Edge(val atEnd: Boolean) : PinPlacement
}

/**
 * Keeps workspaces, pinned tabs, folders and tab assignments, and persists them in [SharedPreferences].
 */
@Suppress("TooManyFunctions")
class WorkspaceRepository private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val defaultName = context.getString(R.string.kaizen_workspace_default_name)
    private val _state = MutableStateFlow(
        WorkspaceSerializer.read(prefs.getString(KEY_STATE, null), defaultName, System.currentTimeMillis()),
    )

    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    fun selectWorkspace(id: String) = mutate { state ->
        if (state.workspaces.none { it.id == id }) state else state.copy(activeWorkspaceId = id)
    }

    fun addWorkspace(name: String, containerId: String?) = mutate { state ->
        val now = now()
        val workspace = Workspace(
            id = WorkspaceSerializer.newWorkspaceId(),
            name = name.trim().ifEmpty { defaultName },
            containerId = containerId,
            createdAt = now,
            updatedAt = now,
        )
        state.copy(workspaces = state.workspaces + workspace, activeWorkspaceId = workspace.id)
    }

    fun updateWorkspace(id: String, name: String, containerId: String?) = mutate { state ->
        state.copy(
            workspaces = state.workspaces.map {
                if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }, containerId = containerId, updatedAt = now()) else it
            },
        )
    }

    /** Deletes a workspace, moving its tabs and pinned items to a neighbouring workspace. The last one is kept. */
    fun deleteWorkspace(id: String) = mutate { state ->
        val index = state.workspaces.indexOfFirst { it.id == id }
        if (index < 0 || state.workspaces.size == 1) return@mutate state
        val target = state.workspaces[if (index > 0) index - 1 else 1].id
        state.copy(
            workspaces = state.workspaces.filterNot { it.id == id },
            pins = state.pins.map { if (it.workspaceId == id) it.copy(workspaceId = target, updatedAt = now()) else it },
            assignments = state.assignments.mapValues { (_, ws) -> if (ws == id) target else ws },
            activeWorkspaceId = if (state.activeWorkspaceId == id) target else state.activeWorkspaceId,
        )
    }

    /** Points every workspace and pinned tab using container [oldId] to [newId], or to no container. */
    fun replaceContainer(oldId: String, newId: String?) = mutate { state ->
        val now = now()
        state.copy(
            workspaces = state.workspaces.map {
                if (it.containerId == oldId) it.copy(containerId = newId, updatedAt = now) else it
            },
            pins = state.pins.map { if (it.containerId == oldId) it.copy(containerId = newId, updatedAt = now) else it },
        )
    }

    /** Removes the pinned tabs of container [containerId] or backed by one of [tabIds]. */
    fun removePinsOf(containerId: String, tabIds: Set<String>) = mutate { state ->
        state.copy(
            pins = state.pins.filterNot { !it.isFolder && (it.containerId == containerId || it.tabId in tabIds) },
        )
    }

    /** Hands the workspace and pin of tab [oldTabId] over to [newTabId], which replaces it. */
    fun replaceTab(oldTabId: String, newTabId: String) = mutate { state ->
        val workspaceId = state.assignments[oldTabId] ?: state.pinOf(oldTabId)?.workspaceId
        state.copy(
            pins = state.pins.map { if (it.tabId == oldTabId) it.copy(tabId = newTabId) else it },
            assignments = (state.assignments - oldTabId).let { if (workspaceId != null) it + (newTabId to workspaceId) else it },
        )
    }

    /**
     * Moves pinned items [itemIds] of [workspaceId] and pins the open tabs [newPins] at [placement]. Essentials among
     * [itemIds] leave the essentials and become pinned tabs of [workspaceId], like in Zen. Folders that would end up
     * inside themselves or deeper than [MAX_FOLDER_DEPTH] stay where they are.
     */
    fun placePins(
        workspaceId: String,
        itemIds: Set<String>,
        newPins: List<PinSource>,
        placement: PinPlacement,
    ) = mutate { state ->
        val parentId = when (placement) {
            is PinPlacement.Into -> placement.folderId
            is PinPlacement.Next -> state.pins.firstOrNull { it.id == placement.itemId }?.parentId
            is PinPlacement.Edge -> null
        }
        if (parentId != null && state.pins.none { it.id == parentId && it.isFolder && it.workspaceId == workspaceId }) {
            return@mutate state
        }
        val moving = state.pins.filter { item ->
            item.id in itemIds &&
                (item.workspaceId == workspaceId || item.essential) &&
                !(
                    item.isFolder && parentId != null && (
                        parentId == item.id ||
                            parentId in state.descendantIds(item.id) ||
                            state.folderDepth(parentId) + state.folderHeight(item.id) > MAX_FOLDER_DEPTH
                        )
                    )
        }
        val now = now()
        val alreadyPinned = state.pins.mapNotNull { it.tabId }.toSet()
        val created = newPins.filter { it.tabId !in alreadyPinned }.distinctBy { it.tabId }.map { source ->
            PinnedItem(
                id = newId(),
                workspaceId = workspaceId,
                parentId = parentId,
                kind = PinKind.TAB,
                title = source.title.ifBlank { source.url },
                url = source.url,
                containerId = source.containerId,
                tabId = source.tabId,
                createdAt = now,
                updatedAt = now,
            )
        }
        val placed = moving.map {
            it.copy(parentId = parentId, workspaceId = workspaceId, essential = false, updatedAt = now)
        } + created
        if (placed.isEmpty()) return@mutate state

        val movingIds = moving.map { it.id }.toSet()
        val rest = state.pins.filterNot { it.id in movingIds }
        val index = when (placement) {
            is PinPlacement.Next -> rest.indexOfFirst { it.id == placement.itemId }
                .let { if (it < 0) rest.size else if (placement.after) it + 1 else it }
            is PinPlacement.Edge -> if (placement.atEnd) {
                rest.size
            } else {
                rest.indexOfFirst { it.workspaceId == workspaceId && it.parentId == null }.let { if (it < 0) rest.size else it }
            }
            is PinPlacement.Into -> rest.size
        }
        val placedTabs = placed.mapNotNull { it.tabId }
        state.copy(
            pins = rest.toMutableList().apply { addAll(index, placed) },
            assignments = state.assignments + placedTabs.associateWith { workspaceId },
        )
    }

    /**
     * Makes pinned tabs [pinIds] and the open tabs [sources] essentials, shown in every workspace, as long as there is
     * room for them below [MAX_ESSENTIALS].
     */
    fun addToEssentials(sources: List<PinSource>, pinIds: Set<String>) = mutate { state ->
        val now = now()
        val room = (MAX_ESSENTIALS - state.essentials.size).coerceAtLeast(0)
        val converted = state.pins
            .filter { it.id in pinIds && !it.isFolder && !it.essential }
            .take(room)
            .map { it.copy(essential = true, workspaceId = null, parentId = null, updatedAt = now) }
        val alreadyPinned = state.pins.mapNotNull { it.tabId }.toSet()
        val fresh = sources.filter { it.tabId !in alreadyPinned }.distinctBy { it.tabId }
        val created = fresh.take(room - converted.size).map { source ->
            PinnedItem(
                id = newId(),
                workspaceId = null,
                parentId = null,
                kind = PinKind.TAB,
                title = source.title.ifBlank { source.url },
                url = source.url,
                containerId = source.containerId,
                essential = true,
                tabId = source.tabId,
                createdAt = now,
                updatedAt = now,
            )
        }
        if (converted.isEmpty() && created.isEmpty()) return@mutate state
        val convertedIds = converted.map { it.id }.toSet()
        state.copy(pins = state.pins.filterNot { it.id in convertedIds } + converted + created)
    }

    /** Removes essentials [pinIds]; their open tabs stay open as normal tabs of [workspaceId]. */
    fun removeFromEssentials(pinIds: Set<String>, workspaceId: String) = mutate { state ->
        val removed = state.pins.filter { it.id in pinIds && it.essential }
        if (removed.isEmpty()) return@mutate state
        val removedIds = removed.map { it.id }.toSet()
        state.copy(
            pins = state.pins.filterNot { it.id in removedIds },
            assignments = state.assignments + removed.mapNotNull { it.tabId }.associateWith { workspaceId },
        )
    }

    /** Moves essential [pinId] to position [index] among the essentials. */
    fun moveEssential(pinId: String, index: Int) = mutate { state ->
        val essentials = state.essentials.toMutableList()
        val item = essentials.firstOrNull { it.id == pinId } ?: return@mutate state
        essentials.remove(item)
        essentials.add(index.coerceIn(0, essentials.size), item)
        state.copy(pins = state.pins.filterNot { it.essential } + essentials)
    }

    /**
     * Removes pinned items: folders with everything inside them, pinned tabs and essentials. The caller closes the
     * open tabs of the removed pins.
     */
    fun deleteItems(itemIds: Set<String>) = mutate { state ->
        val removed = itemIds + itemIds.flatMap { state.descendantIds(it) }
        state.copy(pins = state.pins.filterNot { it.id in removed })
    }

    /** Pins open tabs in their workspaces, optionally inside [parentId]. Tabs that are already pinned are skipped. */
    fun pinTabs(sources: List<PinSource>, parentId: String? = null) = mutate { state -> state.withPins(sources, parentId) }

    /** Removes pinned tabs. Their open tabs stay open as normal tabs. */
    fun unpin(pinIds: Set<String>) = mutate { state ->
        state.copy(pins = state.pins.filterNot { it.id in pinIds && !it.isFolder })
    }

    /** Links a reopened browser tab to its pin and keeps it in the pin's workspace. */
    fun attachPinned(pinId: String, tabId: String) = mutate { state ->
        val pin = state.pins.firstOrNull { it.id == pinId } ?: return@mutate state
        state.copy(
            pins = state.pins.map { if (it.id == pinId) it.copy(tabId = tabId) else it },
            assignments = state.assignments + (tabId to (pin.workspaceId ?: state.activeWorkspaceId)),
        )
    }

    /**
     * Creates a folder in [workspaceId] below [parentId], then moves the pinned items [itemIds] into it and pins
     * [newPins] inside it.
     */
    fun createFolder(
        workspaceId: String,
        parentId: String?,
        name: String,
        itemIds: Set<String> = emptySet(),
        newPins: List<PinSource> = emptyList(),
    ) = mutate { state ->
        if (state.folderDepth(parentId) >= MAX_FOLDER_DEPTH) return@mutate state
        val now = now()
        val folder = PinnedItem(
            id = newId(),
            workspaceId = workspaceId,
            parentId = parentId,
            kind = PinKind.FOLDER,
            title = name.trim().ifEmpty { defaultName },
            createdAt = now,
            updatedAt = now,
        )
        state.copy(pins = state.pins + folder).moved(itemIds, folder.id).withPins(newPins, folder.id)
    }

    fun renameFolder(folderId: String, name: String) = mutate { state ->
        state.copy(
            pins = state.pins.map {
                if (it.id == folderId && name.isNotBlank()) it.copy(title = name.trim(), updatedAt = now()) else it
            },
        )
    }

    fun toggleFolder(folderId: String) = mutate { state ->
        state.copy(pins = state.pins.map { if (it.id == folderId) it.copy(collapsed = !it.collapsed) else it })
    }

    /** Removes a folder and moves its content one level up, to where the folder was, like Zen's "Unpack Folder". */
    fun unpackFolder(folderId: String) = mutate { state ->
        val folder = state.pins.firstOrNull { it.id == folderId && it.isFolder } ?: return@mutate state
        val now = now()
        val children = state.pins.filter { it.parentId == folderId }.map { it.copy(parentId = folder.parentId, updatedAt = now) }
        val childIds = children.map { it.id }.toSet()
        state.copy(
            pins = state.pins.filterNot { it.id in childIds }.flatMap { if (it.id == folderId) children else listOf(it) },
        )
    }

    /** Moves unpinned tabs and pinned items (with everything inside folders) to another workspace. */
    fun moveToWorkspace(tabIds: Set<String>, itemIds: Set<String>, workspaceId: String) = mutate { state ->
        if (state.workspaces.none { it.id == workspaceId }) return@mutate state
        val nested = itemIds.flatMap { state.descendantIds(it) }.toSet()
        val movedPins = itemIds + nested
        val movedTabs = tabIds + state.pins.filter { it.id in movedPins }.mapNotNull { it.tabId }
        val now = now()
        state.copy(
            pins = state.pins.map { pin ->
                when (pin.id) {
                    in itemIds -> pin.copy(workspaceId = workspaceId, parentId = null, essential = false, updatedAt = now)
                    in nested -> pin.copy(workspaceId = workspaceId, updatedAt = now)
                    else -> pin
                }
            },
            assignments = state.assignments + movedTabs.associateWith { workspaceId },
        )
    }

    /**
     * Assigns tabs that have no workspace yet to the active workspace, and forgets tabs that no longer exist once
     * the browser state has been restored. Pins whose tab was closed stay pinned without a tab.
     */
    fun syncWithTabs(tabIds: Set<String>, restoreComplete: Boolean) = mutate { state ->
        val kept = if (restoreComplete) state.assignments.filterKeys { it in tabIds } else state.assignments
        val added = tabIds.filterNot { it in kept }.associateWith { state.activeWorkspaceId }
        val pins = if (restoreComplete) {
            state.pins.map { if (it.tabId != null && it.tabId !in tabIds) it.copy(tabId = null) else it }
        } else {
            state.pins
        }
        state.copy(pins = pins, assignments = kept + added)
    }

    private fun WorkspaceState.withPins(sources: List<PinSource>, parentId: String?): WorkspaceState {
        val now = now()
        val alreadyPinned = pins.mapNotNull { it.tabId }.toSet()
        val parent = parentId?.let { id -> pins.firstOrNull { it.id == id && it.isFolder } }
        val added = sources.filter { it.tabId !in alreadyPinned }.distinctBy { it.tabId }.map { source ->
            val workspaceId = parent?.workspaceId ?: workspaceOf(source.tabId)
            PinnedItem(
                id = newId(),
                workspaceId = workspaceId,
                parentId = parent?.id,
                kind = PinKind.TAB,
                title = source.title.ifBlank { source.url },
                url = source.url,
                containerId = source.containerId,
                tabId = source.tabId,
                createdAt = now,
                updatedAt = now,
            )
        }
        return copy(
            pins = pins + added,
            assignments = assignments + added.associate { it.tabId!! to (it.workspaceId ?: activeWorkspaceId) },
        )
    }

    private fun WorkspaceState.moved(itemIds: Set<String>, folderId: String?): WorkspaceState {
        val folder = folderId?.let { id -> pins.firstOrNull { it.id == id && it.isFolder } }
        if (folderId != null && folder == null) return this
        val blocked = if (folder == null) {
            emptySet()
        } else {
            itemIds.filter { id ->
                val item = pins.firstOrNull { it.id == id }
                folder.id == id ||
                    folder.id in descendantIds(id) ||
                    (item?.isFolder == true && folderDepth(folder.id) + folderHeight(id) > MAX_FOLDER_DEPTH)
            }
        }
        val moving = pins.filter { it.id in itemIds && it.id !in blocked }
        if (moving.isEmpty()) return this
        val now = now()
        val workspaceId = folder?.workspaceId
        val movingIds = moving.map { it.id }.toSet()
        val nested = movingIds.flatMap { descendantIds(it) }.toSet()
        val updated = moving.map {
            it.copy(parentId = folderId, workspaceId = workspaceId ?: it.workspaceId, essential = false, updatedAt = now)
        }
        val rest = pins.filterNot { it.id in movingIds }.map {
            if (workspaceId != null && it.id in nested && it.workspaceId != workspaceId) it.copy(workspaceId = workspaceId) else it
        }
        val movedTabs = (updated + rest.filter { it.id in nested }).mapNotNull { it.tabId }
        return copy(
            pins = rest + updated,
            assignments = if (workspaceId == null) assignments else assignments + movedTabs.associateWith { workspaceId },
        )
    }

    private fun mutate(transform: (WorkspaceState) -> WorkspaceState) {
        var changed: WorkspaceState? = null
        _state.update { old -> transform(old).also { if (it != old) changed = it } }
        changed?.let { prefs.edit { putString(KEY_STATE, WorkspaceSerializer.write(it)) } }
    }

    private fun now() = System.currentTimeMillis()

    private fun newId() = UUID.randomUUID().toString()

    companion object {
        private const val PREFS_NAME = "kaizen_workspaces"
        private const val KEY_STATE = "state"

        @Volatile
        private var instance: WorkspaceRepository? = null

        /** Returns the process wide [WorkspaceRepository]. Reads from disk on first use. */
        fun get(context: Context): WorkspaceRepository =
            instance ?: synchronized(this) {
                instance ?: WorkspaceRepository(context.applicationContext).also { instance = it }
            }

        /** Returns the repository if it has already been loaded, without touching the disk. */
        fun peek(): WorkspaceRepository? = instance
    }
}
