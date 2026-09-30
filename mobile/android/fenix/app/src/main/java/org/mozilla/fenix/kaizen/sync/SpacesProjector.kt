/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.sync

import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.fenix.kaizen.containers.ContainerRecord
import org.mozilla.fenix.kaizen.workspaces.PinnedItem
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState

/**
 * Kaizen's synced data: workspaces with their pinned tabs, folders and essentials, the containers, and the open tabs.
 *
 * @property normalTabs Whether tabs that are not pinned are synced too, like Zen's "Include unpinned tabs".
 * @property tabs The open tabs that are not private, or `null` while the browser has not restored them yet.
 */
internal data class LocalSpaces(
    val state: WorkspaceState,
    val containers: List<ContainerRecord>,
    val normalTabs: Boolean = false,
    val tabs: List<LocalTab>? = null,
)

/**
 * An open tab as spaces sync sees it.
 *
 * @property awake Whether its page is loaded. One that is not can be pointed at another address without losing anything.
 */
internal data class LocalTab(
    val id: String,
    val url: String,
    val title: String,
    val contextId: String?,
    val awake: Boolean,
) {
    /** What syncing the tab sends of it, to tell whether it changed here since the last sync. */
    val fingerprint: String
        get() = "$url\n$title"
}

/** Whether a tab with [url] is synced; Zen and Kaizen cannot share other pages. */
internal fun isSyncableUrl(url: String?): Boolean = url != null && (url.startsWith("https://") || url.startsWith("http://"))

/**
 * The records Kaizen's data maps to.
 *
 * @property records Records by id.
 * @property held Ids of local items that are left out on purpose, like a split view whose tabs have not arrived yet.
 *   Their server records are neither changed nor deleted.
 * @property closedTabs Ids of tabs that are not pinned and were closed here since the last sync.
 */
internal class Projection(
    val records: Map<String, SpacesRecord>,
    val held: Set<String>,
    val closedTabs: Set<String> = emptySet(),
) {
    /** What to upload so that [server] matches this projection: changed records, and tombstones for deleted ones. */
    fun changesAgainst(server: Map<String, SpacesRecord>): List<SpacesRecord> {
        val changed = records.values.filterNot { it.sameAs(server[it.id]) }
        val deleted = server.values
            .filter { (it.isSynced && it.id !in records && it.id !in held) || it.id in closedTabs }
            .map { SpacesRecord.tombstone(it.id) }
        return changed + deleted
    }
}

/**
 * Maps Kaizen's data to the records of Zen's spaces collection, like Zen's ZenSpacesSyncModel does for Zen's sidebar.
 *
 * Every record starts from the server's copy in [server] and only the fields that differ from what Kaizen shows for
 * that copy are rewritten. Fields Kaizen does not have, like tab icons or live folder settings, are kept, so data that
 * Kaizen received and did not change always maps back to exactly the same record and is never uploaded again.
 *
 * @param seen Tabs that are not pinned, by sync id, as they were here at the end of the last sync ([LocalTab.fingerprint]).
 */
internal class SpacesProjector(
    private val local: LocalSpaces,
    private val server: Map<String, SpacesRecord>,
    private val seen: Map<String, String> = emptyMap(),
) {
    private val state = local.state
    private val workspaceIds = state.workspaces.map { it.id }.toSet()
    private val containerIds = local.containers.filterNot { it.temporary }.map { it.contextId }.toSet()
    private val pinsById = state.pins.associateBy { it.id }
    private val records = LinkedHashMap<String, SpacesRecord>()
    private val held = HashSet<String>()
    private val splitMembers = LinkedHashMap<String, List<String>>()
    private val projectedPins = HashSet<String>()
    private val normalTabsBySpace = LinkedHashMap<String, MutableList<String>>()
    private val closedTabs = HashSet<String>()

    /** Tabs of Zen's split views that are not pinned; Kaizen keeps those splits as they are, in place of their tabs. */
    private val normalSplitMembers: Set<String> = server.values
        .filter { !it.deleted && it.kind == RecordKind.SPLIT && it.data?.opt("pinned") == false }
        .flatMap { it.data?.strings("tabs").orEmpty() }
        .toSet()

    fun project(): Projection {
        projectContainers()
        findProjectedPins()
        projectTabs()
        projectNormalTabs()
        projectSplits()
        projectFolders()
        projectSpaces()
        projectLayout()
        return Projection(records, held, closedTabs)
    }

    private fun projectContainers() {
        for (container in local.containers) {
            if (container.temporary) continue
            val id = container.contextId
            val previous = previous(id, RecordKind.CONTAINER)
            // Zen leaves out its default containers until they are renamed; Kaizen's copies of them follow that.
            if (previous == null && BuiltinContainer.of(id) != null && container.updatedAt == container.createdAt) {
                held += id
                continue
            }
            val data = start(previous).put("guid", id).put("name", container.name)
            if (previous == null || containerIconOf(previous.opt("icon")) != container.icon) {
                data.put("icon", container.icon.icon)
            }
            if (previous == null || containerColorOf(previous.opt("color")) != syncedColor(container.color)) {
                data.put("color", syncedColor(container.color).key)
            }
            records[id] = SpacesRecord.of(id, RecordKind.CONTAINER, data)
        }
    }

    private fun findProjectedPins() {
        for (pin in state.pins) {
            val inWorkspace = pin.workspaceId in workspaceIds
            val projected = if (pin.isFolder) {
                inWorkspace
            } else {
                !pin.url.isNullOrBlank() && pin.url != BLANK_URL && (pin.essential || inWorkspace)
            }
            if (projected) projectedPins += pin.id else held += pin.id
        }
    }

    private fun projectTabs() {
        for (pin in state.pins) {
            if (pin.isFolder || pin.id !in projectedPins) continue
            val previous = previous(pin.id, RecordKind.TAB)?.takeIf { it.opt("pinned") != false }
            val data = start(previous).put("tabId", pin.id).put("url", pin.url)
            if (previous == null || labelOf(previous) != (pin.title to pin.staticLabel)) putLabel(data, pin, previous)
            if (previous == null) {
                data.put("icon", "").put("hasStaticIcon", false).put("defaultContainer", false)
            }
            data.putNullable("containerGuid", guidOf(pin.containerId))
                .put("essential", pin.essential)
                .put("pinned", true)
                .putNullable("workspaceUuid", if (pin.essential) null else pin.workspaceId)
                .putNullable("folderId", if (pin.essential) null else parentOf(pin))
            records[pin.id] = SpacesRecord.of(pin.id, RecordKind.TAB, data)
        }
    }

    /**
     * Open tabs that are not pinned, like Zen's "Include unpinned tabs": each is a tab record that is not pinned, in its
     * workspace's children after the pinned items. A tab's address and title go out only when they changed here since
     * the last sync, so a page loaded on two devices is not sent back and forth. Without them, and before the browser
     * restored its tabs, the server's unpinned tabs are held. Those that never were open here are held too; the engine
     * opens them.
     */
    private fun projectNormalTabs() {
        val tabs = local.tabs?.takeIf { local.normalTabs }
        if (tabs == null) {
            server.values.filter { it.isNormalTab }.forEach { held += it.id }
            return
        }
        val pinnedTabs = state.pins.mapNotNull { it.tabId }.toSet()
        for (tab in tabs) {
            if (tab.id in pinnedTabs) continue
            val syncId = state.syncIdOf(tab.id)
            val workspaceId = state.workspaceOf(tab.id)
            val inSyncedContainer = tab.contextId == null || tab.contextId in containerIds
            if (!isSyncableUrl(tab.url) || workspaceId !in workspaceIds || !inSyncedContainer) {
                held += syncId
                continue
            }
            val previous = previous(syncId, RecordKind.TAB)
            val data = start(previous).put("tabId", syncId)
            if (previous?.opt("pinned") != false || seen[syncId] != tab.fingerprint) {
                data.put("url", tab.url).put("title", tab.title)
            }
            if (previous == null) data.put("icon", "").put("hasStaticIcon", false).put("defaultContainer", false)
            data.putNullable("containerGuid", guidOf(tab.contextId))
                .put("essential", false)
                .put("pinned", false)
                .put("workspaceUuid", workspaceId)
                .put("folderId", JSONObject.NULL)
                .putNullable("staticLabel", state.tabTitles[tab.id])
            records[syncId] = SpacesRecord.of(syncId, RecordKind.TAB, data)
            normalTabsBySpace.getOrPut(workspaceId) { mutableListOf() } += syncId
        }
        for (record in server.values) {
            if (!record.isNormalTab || record.id in records || record.id in held) continue
            if (record.id in seen) closedTabs += record.id else held += record.id
        }
    }

    /**
     * Zen syncs the title a tab had when it was pinned, so the server's title stays while the pinned address does,
     * even though Kaizen shows the title of the page open in the pin. A renamed tab keeps its page title too.
     */
    private fun putLabel(data: JSONObject, pin: PinnedItem, previous: JSONObject?) {
        val pinnedTitle = previous?.string("title")?.takeIf { previous.string("url") == pin.url }
        if (pin.staticLabel) {
            data.put("staticLabel", pin.title).put("title", previous?.string("title") ?: pin.title)
        } else {
            data.put("staticLabel", JSONObject.NULL).put("title", pinnedTitle ?: pin.title)
        }
    }

    /**
     * Kaizen has no pinned split views, so the server's are kept as long as their tabs stay together, and follow them
     * when they move. A split whose tabs were separated or unpinned here is deleted, like Zen does.
     */
    private fun projectSplits() {
        for (split in server.values) {
            val data = split.data
            if (split.kind != RecordKind.SPLIT || data == null || !split.isSynced) continue
            val members = data.strings("tabs").orEmpty()
            val pins = members.map { pinsById[it] }
            when {
                members.size < 2 -> held += split.id
                pins.any { it == null } -> {
                    if (members.any { pinsById[it] == null && !isGone(it) }) held += split.id
                }
                pins.any { it!!.isFolder || it.id !in projectedPins } -> held += split.id
                pins.map { Triple(it!!.essential, it.workspaceId, parentOf(it)) }.distinct().size == 1 -> {
                    val first = pins.first()!!
                    val out = SyncJson.copy(data).put("pinned", true)
                    if (!first.essential) {
                        out.putNullable("workspaceUuid", first.workspaceId).putNullable("folderId", parentOf(first))
                    }
                    records[split.id] = SpacesRecord.of(split.id, RecordKind.SPLIT, out)
                    splitMembers[split.id] = members
                }
            }
        }
    }

    private fun projectFolders() {
        for (pin in state.pins) {
            if (!pin.isFolder || pin.id !in projectedPins) continue
            val workspaceId = pin.workspaceId ?: continue
            val previous = previous(pin.id, RecordKind.FOLDER)
            val data = start(previous).put("folderId", pin.id).put("name", pin.title)
            if (previous == null || iconOf(previous.opt("icon")) != pin.icon) data.putNullable("icon", pin.icon)
            if (previous == null) data.put("live", JSONObject.NULL)
            data.put("workspaceUuid", workspaceId)
                .putNullable("parentFolderId", parentOf(pin))
                .put("children", JSONArray(children(workspaceId, pin.id, previous?.strings("children"))))
            records[pin.id] = SpacesRecord.of(pin.id, RecordKind.FOLDER, data)
        }
    }

    private fun projectSpaces() {
        for (workspace in state.workspaces) {
            val previous = previous(workspace.id, RecordKind.SPACE)
            val data = start(previous).put("uuid", workspace.id).put("name", workspace.name)
            if (previous == null || iconOf(previous.opt("icon")) != workspace.icon) {
                data.putNullable("icon", workspace.icon)
            }
            if (previous == null || ZenThemes.toKaizen(previous.opt("theme")) != workspace.theme) {
                data.put("theme", ZenThemes.fromKaizen(workspace.theme, previous?.opt("theme")))
            }
            data.putNullable("containerGuid", guidOf(workspace.containerId))
                .put("children", JSONArray(children(workspace.id, null, previous?.strings("children"))))
            records[workspace.id] = SpacesRecord.of(workspace.id, RecordKind.SPACE, data)
        }
    }

    private fun projectLayout() {
        val previous = previous(LAYOUT_RECORD_ID, RecordKind.LAYOUT)
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (pin in state.pins) {
            if (pin.essential && pin.id in records) {
                groups.getOrPut(guidOf(pin.containerId) ?: DEFAULT_ESSENTIALS) { mutableListOf() } += pin.id
            }
        }
        val previousGroups = previous?.optJSONObject("essentials")
        val essentials = JSONObject()
        val keys = groups.keys + previousGroups?.keys()?.asSequence()?.toList().orEmpty()
        for (key in keys.distinct()) {
            val order = mergeOrder(groups[key].orEmpty(), previousGroups?.strings(key), ::isOpaque)
            if (order.isNotEmpty()) essentials.put(key, JSONArray(order))
        }
        val spaces = mergeOrder(state.workspaces.map { it.id }, previous?.strings("spaces"), ::isOpaque)
        val data = start(previous).put("spaces", JSONArray(spaces)).put("essentials", essentials)
        records[LAYOUT_RECORD_ID] = SpacesRecord.of(LAYOUT_RECORD_ID, RecordKind.LAYOUT, data)
    }

    /** The children of a space's pinned section or of a folder, with split views in place of their tabs. */
    private fun children(workspaceId: String, parentId: String?, previous: List<String>?): List<String> {
        val siblings = state.pins
            .filter { !it.essential && it.workspaceId == workspaceId && parentOf(it) == parentId && it.id in projectedPins }
            .map { it.id }
            .toMutableList()
        if (parentId == null) siblings += normalTabsBySpace[workspaceId].orEmpty().filterNot { it in normalSplitMembers }
        for ((splitId, members) in splitMembers) {
            if (members.all { it in siblings }) {
                val first = members.minOf { siblings.indexOf(it) }
                siblings.removeAll(members.toSet())
                siblings.add(first.coerceAtMost(siblings.size), splitId)
            }
        }
        // A split that no longer holds here leaves its tabs where it was.
        val expanded = previous?.flatMap { id ->
            val split = server[id]?.takeIf { it.kind == RecordKind.SPLIT && it.isSynced && id !in records && id !in held }
            split?.data?.strings("tabs") ?: listOf(id)
        }
        return mergeOrder(siblings, expanded, ::isOpaque)
    }

    /**
     * Whether an id in the server's order of some children stays there although it is not a local child: ids Kaizen
     * does not know, like normal tabs that Zen syncs, keep their place. Items deleted or moved here do not.
     */
    private fun isOpaque(id: String): Boolean {
        if (id in records) return false
        if (id in held) return true
        if (id in closedTabs) return false
        val known = server[id] ?: return true
        return !known.deleted && !known.isSynced
    }

    /** Whether [id] is known to be gone: deleted on the server, or synced before and no longer here. */
    private fun isGone(id: String): Boolean = server[id]?.let { it.deleted || it.isSynced } == true

    private fun parentOf(pin: PinnedItem): String? =
        pin.parentId?.takeIf { id -> pinsById[id]?.let { it.isFolder && it.workspaceId == pin.workspaceId } == true }

    private fun guidOf(contextId: String?): String? = contextId?.takeIf { it in containerIds }

    private fun previous(id: String, kind: String): JSONObject? =
        server[id]?.takeIf { !it.deleted && it.kind == kind }?.data

    private fun start(previous: JSONObject?): JSONObject = previous?.let(SyncJson::copy) ?: JSONObject()

    companion object {
        /** Key of the essentials without a container in the layout record. */
        const val DEFAULT_ESSENTIALS = "default"
        const val BLANK_URL = "about:blank"
    }
}

/** A tab record's title as Kaizen shows it, and whether it is a name the user gave the tab. */
internal fun labelOf(data: JSONObject): Pair<String, Boolean> =
    data.string("staticLabel")?.takeIf { it.isNotEmpty() }?.let { it to true } ?: (data.string("title").orEmpty() to false)

/**
 * Puts the ids of [previous], an order from the server, in the order of [local], keeping the ids of [previous] that
 * are not local and [isOpaque] right after the local id they followed. The result is [previous] itself when [local]
 * lists its local ids in the same order, so an unchanged order is never uploaded again.
 */
internal fun mergeOrder(local: List<String>, previous: List<String>?, isOpaque: (String) -> Boolean): List<String> {
    if (previous.isNullOrEmpty()) return local
    val localIds = local.toSet()
    val anchored = HashMap<String?, MutableList<String>>()
    var anchor: String? = null
    val seen = HashSet<String>()
    for (id in previous) {
        if (!seen.add(id)) continue
        if (id in localIds) {
            anchor = id
        } else if (isOpaque(id)) {
            anchored.getOrPut(anchor) { mutableListOf() } += id
        }
    }
    return buildList {
        anchored[null]?.let(::addAll)
        for (id in local) {
            add(id)
            anchored[id]?.let(::addAll)
        }
    }
}

/**
 * Orders [current] like [desired]. Ids of [current] missing from [desired] stay right after the id they followed.
 */
internal fun reorder(current: List<String>, desired: List<String>): List<String> {
    val currentIds = current.toSet()
    val result = desired.filter { it in currentIds }.distinct().toMutableList()
    val placed = result.toHashSet()
    current.forEachIndexed { index, id ->
        if (id in placed) return@forEachIndexed
        val before = (index - 1 downTo 0).map { current[it] }.firstOrNull { it in placed }
        result.add(if (before == null) 0 else result.indexOf(before) + 1, id)
        placed += id
    }
    return result
}
