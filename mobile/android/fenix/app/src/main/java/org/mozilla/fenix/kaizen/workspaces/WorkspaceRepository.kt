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

    /** Drops every reference to a deleted container. */
    fun forgetContainer(containerId: String) = mutate { state ->
        state.copy(
            workspaces = state.workspaces.map { if (it.containerId == containerId) it.copy(containerId = null) else it },
            pins = state.pins.map { if (it.containerId == containerId) it.copy(containerId = null) else it },
        )
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
            assignments = state.assignments + (tabId to pin.workspaceId),
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

    /** Moves pinned items and folders into [folderId], or to the top of the pinned section when it is `null`. */
    fun moveToFolder(itemIds: Set<String>, folderId: String?) = mutate { state -> state.moved(itemIds, folderId) }

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

    /**
     * Removes a folder with everything inside it, like Zen's "Delete Folder". The caller closes the open tabs of the
     * removed pins.
     */
    fun deleteFolder(folderId: String) = mutate { state ->
        val removed = state.descendantIds(folderId) + folderId
        state.copy(pins = state.pins.filterNot { it.id in removed })
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
                    in itemIds -> pin.copy(workspaceId = workspaceId, parentId = null, updatedAt = now)
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
            assignments = assignments + added.associate { it.tabId!! to it.workspaceId },
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
        val updated = moving.map { it.copy(parentId = folderId, workspaceId = workspaceId ?: it.workspaceId, updatedAt = now) }
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
