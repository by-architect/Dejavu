/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.backup

import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.fenix.dejavu.sync.LAYOUT_RECORD_ID
import org.mozilla.fenix.dejavu.sync.RecordKind
import org.mozilla.fenix.dejavu.sync.SpacesApplier
import org.mozilla.fenix.dejavu.sync.SpacesProjector
import org.mozilla.fenix.dejavu.sync.SpacesRecord
import org.mozilla.fenix.dejavu.sync.isSyncableUrl
import org.mozilla.fenix.dejavu.sync.putNullable
import org.mozilla.fenix.dejavu.sync.string
import org.mozilla.fenix.dejavu.sync.strings

/**
 * The records of Zen's spaces sync that the sidebar of a Zen profile maps to, made the way Zen's ZenSpacesSyncModel
 * makes them from the same data, so they come into Dejavu's workspaces the way synced records do, see
 * [SpacesApplier]: containers, spaces with the order of their pinned items, folders, pinned tabs and essentials, split
 * views, and the layout with the order of the spaces and the essentials. With [includeUnpinned], the tabs that are not
 * pinned are records too, like with Zen's "Include unpinned tabs".
 *
 * Containers go by the ids Zen syncs them under: builtin-1 to builtin-4 for Firefox's default ones, and for the others
 * the id zen-spaces-sync.json has for them, or else zen-container- followed by their number. A container the files do
 * not name is left out, and so its tabs have none, see [leftOutContainers].
 */
internal class ZenRecords(private val profile: ZenProfile, private val includeUnpinned: Boolean) {
    private val session = profile.session
    private val allTabs = session.tabs
    private val tabs = allTabs.filter(::isSyncable)

    // Live folders fill themselves from a feed on the computer, so neither they nor their items come along.
    private val folders = session.folders.filter { folder ->
        folder.text("id") != null && !isTruthy(folder.opt("splitViewGroup")) && !isTruthy(folder.opt("isLiveFolder"))
    }
    private val folderById = folders.associateBy { it.string("id").orEmpty() }
    private val splitParents: Map<String, String?> = session.folders
        .filter { isTruthy(it.opt("splitViewGroup")) && it.text("id") != null }
        .associate { it.string("id").orEmpty() to it.text("parentId") }
    private val syncableTabIds = tabs.mapNotNull { it.text("zenSyncId") }.toSet()

    // Only split views whose tabs all come along come along themselves.
    private val splits = session.splits.filter { split ->
        val members = split.strings("tabs")
        split.text("groupId") != null && members != null && members.size >= 2 && members.all(syncableTabIds::contains)
    }
    private val splitIds = splits.mapNotNull { it.text("groupId") }.toSet()
    private val splitWorkspaces: Map<String, String?> = buildMap {
        tabs.forEach { tab ->
            val groupId = tab.text("groupId")
            if (groupId != null && groupId in splitIds && !containsKey(groupId)) put(groupId, tab.text("zenWorkspace"))
        }
    }
    private val unknownContainers = mutableSetOf<Int>()

    /** The records, containers first. */
    val records: List<SpacesRecord> = buildList {
        addAll(containerRecords())
        addAll(spaceRecords())
        addAll(folderRecords())
        addAll(tabRecords())
        addAll(splitRecords())
        layoutRecord()?.let { add(it) }
    }

    /** The userContextId of each container that tabs or spaces use and the files do not name. */
    val leftOutContainers: Set<Int>
        get() = unknownContainers

    /** How many tabs that are not pinned the profile has in its spaces, which [includeUnpinned] brings along. */
    val unpinnedTabs: Int = allTabs.count { tab ->
        isMaterial(tab) && !isPinned(tab) && tab.text("zenWorkspace") != null &&
            identityOf(tab)?.first?.let(::isSyncableUrl) == true
    }

    /** Whether each folder is closed in Zen, by its id. */
    val collapsedFolders: Map<String, Boolean> =
        folders.associate { it.string("id").orEmpty() to isTruthy(it.opt("collapsed")) }

    /** Whether [tab] is a real tab of the sidebar, not an empty one, a glance, or an item of a live folder. */
    private fun isMaterial(tab: JSONObject): Boolean =
        tab.text("zenSyncId") != null && !isTruthy(tab.opt("zenIsEmpty")) && !isTruthy(tab.opt("zenIsGlance")) &&
            !isTruthy(tab.opt("zenLiveFolderItemId"))

    private fun isEssential(tab: JSONObject): Boolean = isTruthy(tab.opt("zenEssential"))

    private fun isPinned(tab: JSONObject): Boolean = isTruthy(tab.opt("pinned")) || isEssential(tab)

    private fun isSyncable(tab: JSONObject): Boolean = isMaterial(tab) && (isPinned(tab) || includeUnpinned)

    /**
     * The address and title of [tab]: for a pinned tab the ones it was pinned with, otherwise those of its current
     * page, or `null` for a tab without a page.
     */
    private fun identityOf(tab: JSONObject): Pair<String, String>? {
        val initial = if (isPinned(tab)) tab.optJSONObject("_zenPinnedInitialState")?.optJSONObject("entry") else null
        var url = initial?.text("url")
        var title = initial?.string("title")
        if (url == null || url == BLANK) {
            val entries = tab.optJSONArray("entries")
            if (entries != null && entries.length() > 0) {
                val position = (tab.opt("index") as? Number)?.toInt()?.takeIf { it > 0 } ?: entries.length()
                val entry = entries.optJSONObject((position - 1).coerceIn(0, entries.length() - 1))
                    ?: entries.optJSONObject(0)
                val entryUrl = entry?.text("url")
                if (entry != null && entryUrl != null && entryUrl != BLANK) {
                    url = entryUrl
                    title = title ?: entry.string("title")
                }
            }
        }
        if (url == null || url == BLANK) return null
        return url to title.orEmpty()
    }

    /** The id container [userContextId] goes by, or `null` for no container, see [ZenRecords]. */
    private fun guidOf(userContextId: Int): String? {
        if (userContextId <= 0) return null
        val containers = profile.containers
        val container = containers?.get(userContextId)
        // Like Zen, a container that is gone from containers.json is no container any more.
        if (containers != null && container == null) return null
        if (userContextId <= BUILTIN_CONTAINERS) return "$BUILTIN_PREFIX$userContextId"
        val synced = profile.syncIds[userContextId]
        return when {
            container?.name != null -> synced ?: "$OWN_PREFIX$userContextId"
            synced != null -> synced
            else -> {
                unknownContainers += userContextId
                null
            }
        }
    }

    private fun containerRecords(): List<SpacesRecord> = profile.containers.orEmpty().mapNotNull { (id, container) ->
        val name = container.name ?: return@mapNotNull null
        val guid = guidOf(id) ?: return@mapNotNull null
        val data = JSONObject()
            .put("guid", guid)
            .put("name", name)
            .put("icon", container.icon.orEmpty())
            .put("color", container.color.orEmpty())
        SpacesRecord.of(guid, RecordKind.CONTAINER, data)
    }

    private fun spaceRecords(): List<SpacesRecord> = session.spaces.mapNotNull { space ->
        val uuid = space.text("uuid") ?: return@mapNotNull null
        val data = JSONObject()
            .put("uuid", uuid)
            .put("name", space.string("name").orEmpty())
            .putNullable("icon", space.opt("icon")?.takeUnless { it == JSONObject.NULL })
            .putNullable("theme", space.optJSONObject("theme"))
            .putNullable("containerGuid", guidOf(userContextIdOf(space.opt("containerTabId"))))
            .put("children", JSONArray(childrenOf(space = uuid, folder = null)))
        SpacesRecord.of(uuid, RecordKind.SPACE, data)
    }

    private fun folderRecords(): List<SpacesRecord> = folders.map { folder ->
        val id = folder.string("id").orEmpty()
        val data = JSONObject()
            .put("folderId", id)
            .put("name", folder.string("name").orEmpty())
            .putNullable("icon", folder.text("userIcon"))
            .putNullable("workspaceUuid", folder.text("workspaceId"))
            .putNullable("parentFolderId", folder.text("parentId"))
            .put("live", JSONObject.NULL)
            .put("children", JSONArray(childrenOf(space = null, folder = id)))
        SpacesRecord.of(id, RecordKind.FOLDER, data)
    }

    private fun tabRecords(): List<SpacesRecord> = tabs.mapNotNull { tab ->
        val syncId = tab.text("zenSyncId") ?: return@mapNotNull null
        val (url, title) = identityOf(tab) ?: return@mapNotNull null
        val essential = isEssential(tab)
        // Zen's tab icons are not shown here, so they stay out of the records.
        val data = JSONObject()
            .put("tabId", syncId)
            .put("url", url)
            .put("title", title)
            .put("icon", "")
            .putNullable("containerGuid", guidOf(userContextIdOf(tab.opt("userContextId"))))
            .put("essential", essential)
            .put("pinned", isPinned(tab))
            .putNullable("workspaceUuid", if (essential) null else tab.text("zenWorkspace"))
            .putNullable("folderId", folderOf(tab.text("groupId")))
            .putNullable("staticLabel", tab.opt("zenStaticLabel") as? String)
            .put("hasStaticIcon", isTruthy(tab.opt("zenHasStaticIcon")))
            .put("defaultContainer", isTruthy(tab.opt("zenDefaultUserContextId")))
        SpacesRecord.of(syncId, RecordKind.TAB, data)
    }

    private fun splitRecords(): List<SpacesRecord> {
        val tabsById = tabs.associateBy { it.text("zenSyncId") }
        return splits.map { split ->
            val id = split.string("groupId").orEmpty()
            val members = split.strings("tabs").orEmpty()
            val first = tabsById[members.firstOrNull()]
            val data = JSONObject()
                .put("splitId", id)
                .put("gridType", split.text("gridType") ?: DEFAULT_GRID)
                .put("pinned", first != null && isPinned(first))
                .put("tabs", JSONArray(members))
                .putNullable("workspaceUuid", splitWorkspaces[id])
                .putNullable("folderId", splitParents[id])
            SpacesRecord.of(id, RecordKind.SPLIT, data)
        }
    }

    private fun layoutRecord(): SpacesRecord? {
        val spaces = session.spaces.mapNotNull { it.text("uuid") }
        if (spaces.isEmpty()) return null
        val essentials = JSONObject()
        tabs.filter(::isEssential).forEach { tab ->
            val key = guidOf(userContextIdOf(tab.opt("userContextId"))) ?: SpacesProjector.DEFAULT_ESSENTIALS
            val ids = essentials.optJSONArray(key) ?: JSONArray().also { essentials.put(key, it) }
            tab.text("zenSyncId")?.let { ids.put(it) }
        }
        val data = JSONObject().put("spaces", JSONArray(spaces)).put("essentials", essentials)
        return SpacesRecord.of(LAYOUT_RECORD_ID, RecordKind.LAYOUT, data)
    }

    /** The folder an item of tab group [groupId] is in: the folder itself, or the folder around a split view. */
    private fun folderOf(groupId: String?): String? = when {
        groupId == null -> null
        groupId in splitIds -> splitParents[groupId]
        groupId in folderById -> groupId
        else -> null
    }

    /**
     * The children of the pinned section of [space], or of [folder], in the order of the tab strip: tabs, split views
     * and folders, each once, like Zen's ZenSpacesSyncModel orders them.
     */
    @Suppress("CognitiveComplexMethod")
    private fun childrenOf(space: String?, folder: String?): List<String> {
        val children = LinkedHashSet<String>()
        for (tab in allTabs) {
            val syncId = tab.text("zenSyncId")
            val groupId = tab.text("groupId")
            if (groupId == null) {
                val inSpace = space != null && !isEssential(tab) && tab.text("zenWorkspace") == space
                if (inSpace && syncId != null && syncId in syncableTabIds) children += syncId
                continue
            }
            if (folder != null && groupId == folder) {
                if (syncId != null && syncId in syncableTabIds) children += syncId
                continue
            }
            if (groupId in splitIds || groupId in splitParents) {
                val parent = splitParents[groupId]
                val direct = if (folder != null) parent == folder else parent == null
                if (direct) {
                    if (groupId in splitIds && (space == null || splitWorkspaces[groupId] == space)) children += groupId
                } else if (parent != null) {
                    childFolderOf(space, folder, parent)?.let(children::add)
                }
                continue
            }
            if (groupId in folderById) childFolderOf(space, folder, groupId)?.let(children::add)
        }
        return children.toList()
    }

    /**
     * The folder among the parents of [folderId], itself included, that is a child of [folder], or of the top of
     * [space]'s pinned section, or `null` when its parents lead elsewhere.
     */
    private fun childFolderOf(space: String?, folder: String?, folderId: String): String? {
        val seen = HashSet<String>()
        var current = folderById[folderId]
        while (current != null) {
            val id = current.string("id").orEmpty()
            if (!seen.add(id)) return null
            val parentId = current.text("parentId")
            val isChild = if (folder != null) parentId == folder else parentId == null
            if (isChild) return if (space == null || current.text("workspaceId") == space) id else null
            current = parentId?.let { folderById[it] }
        }
        return null
    }

    private companion object {
        const val BLANK = "about:blank"
        const val DEFAULT_GRID = "grid"
        const val BUILTIN_CONTAINERS = 4
        const val BUILTIN_PREFIX = "builtin-"
        const val OWN_PREFIX = "zen-container-"
    }
}

/** A userContextId as Zen writes it, a number or sometimes a text, or 0 for none. */
private fun userContextIdOf(value: Any?): Int = when (value) {
    is Number -> value.toInt()
    is String -> value.toIntOrNull() ?: 0
    else -> 0
}

/** Whether JavaScript would take [value] as true, as Zen's flags are often missing rather than false. */
private fun isTruthy(value: Any?): Boolean = when (value) {
    null, JSONObject.NULL -> false
    is Boolean -> value
    is String -> value.isNotEmpty()
    is Number -> value.toDouble().let { it != 0.0 && !it.isNaN() }
    else -> true
}
