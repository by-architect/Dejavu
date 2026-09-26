/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.workspaces

/**
 * A workspace groups tabs. Every normal tab belongs to exactly one workspace.
 *
 * @property id Stable identifier, shared with other devices when syncing.
 * @property name User visible name.
 * @property containerId Container (GeckoView context ID) new tabs of this workspace open in, or `null`.
 * @property icon Emoji or icon name, as Zen stores it for spaces. Not shown yet.
 * @property createdAt Creation time in milliseconds.
 * @property updatedAt Last change time in milliseconds, used to resolve sync conflicts.
 */
data class Workspace(
    val id: String,
    val name: String,
    val containerId: String? = null,
    val icon: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Deepest folder nesting allowed, matching Zen's default `zen.folders.max-subfolders`. */
const val MAX_FOLDER_DEPTH = 5

/** Kind of a [PinnedItem]. */
enum class PinKind(val key: String) {
    TAB("tab"),
    FOLDER("folder"),
}

/**
 * An entry of a workspace's pinned section: a pinned tab or a folder. Items are stored as a flat list where each item
 * points to its workspace and parent folder, so folders can be nested and the list maps one to one to sync records.
 * The order of the list is the display order among siblings.
 *
 * @property id Stable identifier, shared with other devices when syncing.
 * @property workspaceId Workspace the item belongs to.
 * @property parentId Folder containing the item, or `null` at the top of the pinned section.
 * @property kind Whether this is a pinned tab or a folder.
 * @property title Folder name, or the pinned tab's title.
 * @property url URL a pinned tab reopens; `null` for folders.
 * @property containerId Container a pinned tab reopens in; `null` for folders and tabs without a container.
 * @property collapsed Whether a folder is collapsed. Local to this device, like in Zen.
 * @property icon Folder icon name, as Zen stores it. Not shown yet.
 * @property tabId Open browser tab currently backing a pinned tab. Local to this device and never synced.
 * @property createdAt Creation time in milliseconds.
 * @property updatedAt Last change time in milliseconds, used to resolve sync conflicts.
 */
data class PinnedItem(
    val id: String,
    val workspaceId: String,
    val parentId: String?,
    val kind: PinKind,
    val title: String,
    val url: String? = null,
    val containerId: String? = null,
    val collapsed: Boolean = false,
    val icon: String? = null,
    val tabId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isFolder: Boolean
        get() = kind == PinKind.FOLDER
}

/**
 * @property workspaces Workspaces in display order. Never empty.
 * @property pins Pinned tabs and folders of all workspaces.
 * @property activeWorkspaceId The workspace shown on the home screen. New tabs are assigned to it.
 * @property assignments Tab ID to workspace ID. Local to this device.
 */
data class WorkspaceState(
    val workspaces: List<Workspace>,
    val pins: List<PinnedItem>,
    val activeWorkspaceId: String,
    val assignments: Map<String, String>,
) {
    val activeIndex: Int
        get() = workspaces.indexOfFirst { it.id == activeWorkspaceId }.coerceAtLeast(0)

    val activeWorkspace: Workspace?
        get() = workspaces.firstOrNull { it.id == activeWorkspaceId }

    /** Returns the workspace ID for [tabId], falling back to the active workspace for unassigned tabs. */
    fun workspaceOf(tabId: String): String = assignments[tabId] ?: activeWorkspaceId

    /** Returns the pin backed by the open tab [tabId], if any. */
    fun pinOf(tabId: String): PinnedItem? = pins.firstOrNull { it.tabId == tabId }

    /** Returns the direct children of [parentId] in [workspaceId], in display order. */
    fun children(workspaceId: String, parentId: String?): List<PinnedItem> =
        pins.filter { it.workspaceId == workspaceId && it.parentId == parentId }

    /** Returns how many folders [folderId] is nested in, counting itself: 1 for a top level folder. */
    fun folderDepth(folderId: String?): Int {
        var depth = 0
        var current = folderId?.let { id -> pins.firstOrNull { it.id == id && it.isFolder } }
        val seen = mutableSetOf<String>()
        while (current != null && seen.add(current.id)) {
            depth++
            current = current.parentId?.let { id -> pins.firstOrNull { it.id == id && it.isFolder } }
        }
        return depth
    }

    /** Returns how many folder levels [folderId] spans, counting itself: 1 for a folder without subfolders. */
    fun folderHeight(folderId: String, seen: Set<String> = emptySet()): Int {
        if (folderId in seen) return 0
        val children = pins.filter { it.parentId == folderId && it.isFolder }
        return 1 + (children.maxOfOrNull { folderHeight(it.id, seen + folderId) } ?: 0)
    }

    /** Returns the IDs of every item nested below [folderId]. */
    fun descendantIds(folderId: String): Set<String> {
        val result = mutableSetOf<String>()
        var level = setOf(folderId)
        while (level.isNotEmpty()) {
            level = pins.filter { it.parentId in level && it.id !in result }.map { it.id }.toSet()
            result += level
        }
        return result
    }
}
