/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.workspaces

/**
 * A workspace groups tabs. Every normal tab belongs to exactly one workspace.
 *
 * @property id Stable identifier, shared with other devices when syncing.
 * @property name User visible name.
 * @property containerId Container (GeckoView context ID) new tabs of this workspace open in, or `null`.
 * @property icon Emoji shown with the name, or the file name of one of Zen's built-in icons as Zen stores it.
 * @property theme Background of the workspace, or `null` for the plain background.
 * @property createdAt Creation time in milliseconds.
 * @property updatedAt Last change time in milliseconds, used to resolve sync conflicts.
 */
data class Workspace(
    val id: String,
    val name: String,
    val containerId: String? = null,
    val icon: String? = null,
    val theme: WorkspaceTheme? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * A workspace background, like Zen's space themes: a gradient of one to three colors.
 *
 * @property colors ARGB colors of the gradient.
 * @property opacity How strongly the gradient covers the background, from 0 to 1.
 * @property texture Strength of the grain drawn over the gradient, from 0 to 1.
 */
data class WorkspaceTheme(
    val colors: List<Int>,
    val opacity: Float = DEFAULT_OPACITY,
    val texture: Float = 0f,
) {
    companion object {
        const val DEFAULT_OPACITY = 0.35f

        /** The gold of Dejavu's logo, the theme of the workspace a fresh Dejavu starts with. */
        val Gold = WorkspaceTheme(colors = listOf(0xFFFFEDC2, 0xFFFFD67A, 0xFFF2B84B).map { it.toInt() })
    }
}

/** Deepest folder nesting allowed, matching Zen's default `zen.folders.max-subfolders`. */
const val MAX_FOLDER_DEPTH = 5

/** Most essentials allowed, matching Zen's default `zen.tabs.essentials.max`. */
const val MAX_ESSENTIALS = 12

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
 * @property workspaceId Workspace the item belongs to, or `null` for essentials, which every workspace shows.
 * @property parentId Folder containing the item, or `null` at the top of the pinned section.
 * @property kind Whether this is a pinned tab or a folder.
 * @property title Folder name, or the pinned tab's title.
 * @property url URL a pinned tab reopens; `null` for folders.
 * @property containerId Container a pinned tab reopens in; `null` for folders and tabs without a container.
 * @property collapsed Whether a folder is collapsed. Local to this device, like in Zen.
 * @property icon Folder icon name, as Zen stores it. Not shown yet.
 * @property essential Whether this pinned tab is one of the essentials shared by all workspaces, like in Zen.
 * @property staticLabel Whether [title] was given by the user and is shown instead of the page title, like Zen's
 *   renamed tabs.
 * @property tabId Open browser tab currently backing a pinned tab. Local to this device and never synced.
 * @property openUrl Page a pinned tab was on when its tab closed, when that is not [url]: the pin opens there again
 *   until it is reset. Local to this device and never synced.
 * @property openTitle Title of the page at [openUrl].
 * @property createdAt Creation time in milliseconds.
 * @property updatedAt Last change time in milliseconds, used to resolve sync conflicts.
 */
data class PinnedItem(
    val id: String,
    val workspaceId: String?,
    val parentId: String?,
    val kind: PinKind,
    val title: String,
    val url: String? = null,
    val containerId: String? = null,
    val collapsed: Boolean = false,
    val icon: String? = null,
    val essential: Boolean = false,
    val staticLabel: Boolean = false,
    val tabId: String? = null,
    val openUrl: String? = null,
    val openTitle: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isFolder: Boolean
        get() = kind == PinKind.FOLDER
}

/**
 * Two tabs shown together in the browser, like Zen's split views. Local to this device, like tab assignments.
 *
 * @property id Stable identifier.
 * @property tabIds The two browser tabs of the split.
 */
data class SplitView(val id: String, val tabIds: List<String>)

/**
 * @property workspaces Workspaces in display order. Never empty.
 * @property pins Pinned tabs and folders of all workspaces.
 * @property activeWorkspaceId The workspace shown on the home screen. New tabs are assigned to it.
 * @property assignments Tab ID to workspace ID. Local to this device.
 * @property splits Tabs shown together in the browser. Local to this device.
 * @property tabTitles Titles the user gave to tabs that are not pinned, by tab ID. Local to this device.
 * @property tabSyncIds Sync ids of open tabs that sync under another id than their own, by tab ID: the id of the pin
 *   they were the tab of, once it was unpinned, so the tab stays the same tab on other devices. Local to this device.
 */
data class WorkspaceState(
    val workspaces: List<Workspace>,
    val pins: List<PinnedItem>,
    val activeWorkspaceId: String,
    val assignments: Map<String, String>,
    val splits: List<SplitView> = emptyList(),
    val tabTitles: Map<String, String> = emptyMap(),
    val tabSyncIds: Map<String, String> = emptyMap(),
) {
    val activeIndex: Int
        get() = workspaces.indexOfFirst { it.id == activeWorkspaceId }.coerceAtLeast(0)

    val activeWorkspace: Workspace?
        get() = workspaces.firstOrNull { it.id == activeWorkspaceId }

    /** Essentials in display order. */
    val essentials: List<PinnedItem>
        get() = pins.filter { it.essential }

    /**
     * The essentials shown in a workspace with default container [containerId]: all of them, or with [perContainer]
     * only those whose [essentialsGroupOf] is that container, like Zen's container-specific essentials.
     */
    fun essentialsFor(containerId: String?, perContainer: Boolean): List<PinnedItem> =
        if (perContainer) essentials.filter { essentialsGroupOf(it.containerId) == containerId } else essentials

    /**
     * Which workspaces show essentials of container [containerId] when every container has its own essentials: those
     * with that default container, or `null` for those without one, which also show the essentials of containers no
     * workspace has as its default, like in Zen.
     */
    fun essentialsGroupOf(containerId: String?): String? =
        containerId?.takeIf { id -> workspaces.any { it.containerId == id } }

    /** Returns the workspace ID for [tabId], falling back to the active workspace for unassigned tabs. */
    fun workspaceOf(tabId: String): String = assignments[tabId] ?: activeWorkspaceId

    /** Returns the id the open tab [tabId] syncs under when it is not pinned. */
    fun syncIdOf(tabId: String): String = tabSyncIds[tabId] ?: tabId

    /** Returns the pin backed by the open tab [tabId], if any. */
    fun pinOf(tabId: String): PinnedItem? = pins.firstOrNull { it.tabId == tabId }

    /** Returns the split view tab [tabId] is part of, if any. */
    fun splitOf(tabId: String): SplitView? = splits.firstOrNull { tabId in it.tabIds }

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

/** Whether [a] and [b] are the same page, leaving out the part after "#" and a slash at the end. */
fun isSamePage(a: String, b: String): Boolean = a.substringBefore('#').trimEnd('/') == b.substringBefore('#').trimEnd('/')
