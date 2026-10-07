/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import org.json.JSONObject
import org.mozilla.fenix.dejavu.workspaces.PinKind
import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.Workspace
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme

/**
 * The outcome of applying incoming records.
 *
 * @property state Dejavu's workspaces with the records applied.
 * @property applied Records that are now reflected locally, or deliberately left alone. The server holds them as they
 *   are, so they become the server's known copy.
 * @property failed Records that could not be applied yet, usually because a space, folder or container they need has
 *   not arrived. They are applied again on the next sync.
 * @property tabOps What to do with open tabs so that they match the records.
 */
internal class ApplyResult(
    val state: WorkspaceState,
    val applied: List<SpacesRecord>,
    val failed: List<SpacesRecord>,
    val tabOps: List<TabOp> = emptyList(),
)

/** A change to the open tabs that applying records asks for. */
internal sealed interface TabOp {
    /** Opens [url] as tab [id] of workspace [workspaceId] in container [contextId], without loading it. */
    data class Open(val id: String, val url: String, val title: String, val contextId: String?, val workspaceId: String) :
        TabOp

    /** Points tab [id], whose page is not loaded, at [url]. */
    data class Retarget(val id: String, val url: String, val title: String) : TabOp

    /** Closes tab [id]. */
    data class Close(val id: String) : TabOp
}

/**
 * Applies incoming records, except containers, to Dejavu's workspaces, like Zen's ZenSpacesSyncApplier does to its
 * sidebar: removed tabs first, then spaces, folders (parents first) and tabs, then removed folders and spaces, then the
 * order of everything.
 *
 * Incoming records always win over local changes. Anything that then differs locally is uploaded afterwards.
 *
 * @param server The records known to be on the server before this batch.
 * @param containerIds The permanent containers that exist locally, already including the incoming ones.
 * @param firstSync Whether nothing was synced before, so a pristine default workspace can make way for synced ones.
 * @param defaultName Name of a new default workspace.
 * @param now Time used for the changes, in milliseconds.
 * @param normalTabs Whether tabs that are not pinned are synced too. Otherwise their records only unpin tabs.
 * @param tabs The open tabs that are not private by id, or `null` while the browser has not restored them. Records of
 *   tabs that are not pinned then wait.
 */
internal class SpacesApplier(
    private val server: Map<String, SpacesRecord>,
    private val containerIds: Set<String>,
    private val firstSync: Boolean,
    private val defaultName: String,
    private val now: Long,
    private val normalTabs: Boolean = false,
    private val tabs: Map<String, LocalTab>? = null,
) {
    /** Applies [incoming] to [state]. Pure: the same input always gives the same result. */
    fun apply(state: WorkspaceState, incoming: List<SpacesRecord>): ApplyResult = Pass(state, incoming).run()

    private enum class Outcome { APPLIED, FAILED, IGNORED }

    /** A container a record refers to; [contextId] is `null` for no container. */
    private class ContainerRef(val contextId: String?)

    @Suppress("TooManyFunctions")
    private inner class Pass(private val original: WorkspaceState, private val incoming: List<SpacesRecord>) {
        private val workspaces = original.workspaces.toMutableList()
        private val pins = original.pins.toMutableList()
        private val assignments = original.assignments.toMutableMap()
        private val tabTitles = original.tabTitles.toMutableMap()
        private val tabSyncIds = original.tabSyncIds.toMutableMap()
        private val tabOps = mutableListOf<TabOp>()
        private val openTabs = tabs.orEmpty()

        /** Open tabs by the id they sync under. */
        private val tabIdsBySyncId: Map<String, String> = openTabs.keys.associateBy { original.syncIdOf(it) }
        private var activeId = original.activeWorkspaceId
        private val applied = mutableListOf<SpacesRecord>()
        private val failed = mutableListOf<SpacesRecord>()
        private val incomingIds = incoming.map { it.id }.toSet()

        /** Pinned tabs and folders that this batch added, or moved to another place. */
        private val arrivals = mutableSetOf<String>()

        private val splitMembers: Map<String, List<String>> =
            (server.values + incoming)
                .filter { !it.deleted && it.kind == RecordKind.SPLIT }
                .mapNotNull { split -> split.data?.strings("tabs")?.let { split.id to it } }
                .toMap()

        fun run(): ApplyResult {
            val upserts = incoming.filterNot { it.deleted }
            val removals = incoming.filter { it.deleted }
            val spaces = upserts.filter { it.kind == RecordKind.SPACE }
            val folders = upserts.filter { it.kind == RecordKind.FOLDER }
            val layout = upserts.lastOrNull { it.kind == RecordKind.LAYOUT && it.id == LAYOUT_RECORD_ID }

            removals.forEach { removeTab(it.id) }
            val createdSpaces = spaces.mapNotNull { record ->
                val isNew = workspaces.none { it.id == record.id }
                record(record, upsertSpace(record))
                record.id.takeIf { isNew && workspaces.any { it.id == record.id } }
            }
            dropPristineWorkspace(createdSpaces, layout)
            sortParentsFirst(folders).forEach { record(it, upsertFolder(it)) }
            upserts.filter { it.kind == RecordKind.TAB }.forEach { record(it, upsertTab(it)) }
            upserts.filter { it.kind == RecordKind.SPLIT }.forEach {
                record(it, if (it.data?.strings("tabs") != null) Outcome.APPLIED else Outcome.IGNORED)
            }
            removals.forEach { record ->
                pins.firstOrNull { it.id == record.id && it.isFolder }?.let { deleteFolder(it) }
            }
            removals.forEach { record -> deleteWorkspace(record.id) }
            applied += removals
            applyOrder(spaces, folders, layout)

            val state = original.copy(
                workspaces = workspaces,
                pins = pins,
                activeWorkspaceId = activeId.takeIf { id -> workspaces.any { it.id == id } } ?: workspaces.first().id,
                assignments = assignments,
                tabTitles = tabTitles,
                tabSyncIds = tabSyncIds,
            )
            return ApplyResult(state, applied, failed, tabOps)
        }

        private fun record(record: SpacesRecord, outcome: Outcome) {
            when (outcome) {
                Outcome.APPLIED -> applied += record
                Outcome.FAILED -> failed += record
                Outcome.IGNORED -> Unit
            }
        }

        private fun upsertSpace(record: SpacesRecord): Outcome {
            val data = record.data ?: return Outcome.IGNORED
            val container = containerOf(data) ?: return Outcome.FAILED
            val name = data.string("name").orEmpty()
            val icon = iconOf(data.opt("icon"))
            val theme = ZenThemes.toDejavu(data.opt("theme"))
            val index = workspaces.indexOfFirst { it.id == record.id }
            if (index < 0) {
                workspaces += Workspace(record.id, name, container.contextId, icon, theme, createdAt = now, updatedAt = now)
                return Outcome.APPLIED
            }
            val current = workspaces[index]
            val updated = current.copy(
                name = name,
                icon = icon,
                theme = theme,
                containerId = keepLocalContainer(current.containerId, container.contextId),
            )
            if (updated != current) workspaces[index] = updated.copy(updatedAt = now)
            return Outcome.APPLIED
        }

        /**
         * On the first sync, the untouched workspace a fresh Dejavu starts with is replaced by the synced ones instead
         * of being uploaded as an empty space.
         */
        private fun dropPristineWorkspace(created: List<String>, layout: SpacesRecord?) {
            if (!firstSync || created.isEmpty() || original.workspaces.size != 1) return
            val initial = original.workspaces.single()
            val pristine = initial.name == defaultName && initial.icon == null &&
                (initial.theme == null || initial.theme == WorkspaceTheme.Gold) &&
                initial.containerId == null && pins.none { it.workspaceId == initial.id }
            if (!pristine || initial.id in created) return
            val target = layout?.data?.strings("spaces")?.firstOrNull { it in created } ?: created.first()
            workspaces.removeAll { it.id == initial.id }
            assignments.replaceAll { _, workspaceId -> if (workspaceId == initial.id) target else workspaceId }
            if (activeId == initial.id) activeId = target
        }

        private fun upsertFolder(record: SpacesRecord): Outcome {
            val data = record.data ?: return Outcome.IGNORED
            val parentId = data.string("parentFolderId")?.takeIf { it != record.id }
            val parent = parentId?.let { id -> pins.firstOrNull { it.id == id && it.isFolder } }
            if (parentId != null && (parent == null || isInside(parent.id, record.id))) return Outcome.FAILED
            val workspaceId = parent?.workspaceId ?: data.string("workspaceUuid")
            if (workspaceId == null || workspaces.none { it.id == workspaceId }) return Outcome.FAILED
            val name = data.string("name").orEmpty()
            val icon = iconOf(data.opt("icon"))
            val index = pins.indexOfFirst { it.id == record.id }
            if (index < 0) {
                pins += PinnedItem(
                    id = record.id,
                    workspaceId = workspaceId,
                    parentId = parent?.id,
                    kind = PinKind.FOLDER,
                    title = name,
                    collapsed = true,
                    icon = icon,
                    createdAt = now,
                    updatedAt = now,
                )
                arrivals += record.id
                return Outcome.APPLIED
            }
            val current = pins[index]
            if (!current.isFolder) return Outcome.FAILED
            val updated = current.copy(title = name, icon = icon, workspaceId = workspaceId, parentId = parent?.id)
            if (updated != current) {
                pins[index] = updated.copy(updatedAt = now)
                if (current.workspaceId != workspaceId) moveNested(record.id, workspaceId)
                if (current.workspaceId != workspaceId || current.parentId != parent?.id) arrivals += record.id
            }
            return Outcome.APPLIED
        }

        private fun upsertTab(record: SpacesRecord): Outcome {
            val data = record.data ?: return Outcome.IGNORED
            if (record.isNormalTab) return upsertNormalTab(record, data)
            val url = data.string("url")
            if (url.isNullOrEmpty() || url == SpacesProjector.BLANK_URL) return Outcome.IGNORED
            val container = containerOf(data) ?: return Outcome.FAILED
            val essential = data.opt("essential") == true
            val existing = pins.firstOrNull { it.id == record.id }
            if (existing?.isFolder == true) return Outcome.FAILED
            val (workspaceId, parentId) = placementOf(data, essential, existing) ?: return Outcome.FAILED

            val (title, staticLabel) = labelOf(data)
            if (existing == null) {
                // A tab open here without a pin, pinned elsewhere, becomes the pinned tab.
                val openTab = tabIdsBySyncId[record.id]?.takeIf { tabId -> pins.none { it.tabId == tabId } }
                pins += PinnedItem(
                    id = record.id,
                    workspaceId = workspaceId,
                    parentId = parentId,
                    kind = PinKind.TAB,
                    title = title,
                    url = url,
                    containerId = container.contextId,
                    essential = essential,
                    staticLabel = staticLabel,
                    tabId = openTab,
                    createdAt = now,
                    updatedAt = now,
                )
                openTab?.let { tabId ->
                    tabSyncIds.remove(tabId)
                    tabTitles.remove(tabId)
                    workspaceId?.let { assignments[tabId] = it }
                }
                arrivals += record.id
                return Outcome.APPLIED
            }
            val pinnedPageChanged = url != existing.url
            val updated = existing.copy(
                title = title,
                staticLabel = staticLabel,
                url = url,
                containerId = keepLocalContainer(existing.containerId, container.contextId),
                essential = essential,
                workspaceId = workspaceId,
                parentId = parentId,
                // A pin given a new address on another device opens there, not on the page it was last on here.
                openUrl = if (pinnedPageChanged) null else existing.openUrl,
                openTitle = if (pinnedPageChanged) null else existing.openTitle,
            )
            if (updated != existing) {
                pins[pins.indexOf(existing)] = updated.copy(updatedAt = now)
                val tabId = existing.tabId
                if (tabId != null && workspaceId != null && workspaceId != existing.workspaceId) {
                    assignments[tabId] = workspaceId
                }
                val moved = essential != existing.essential || workspaceId != existing.workspaceId ||
                    parentId != existing.parentId
                if (moved) arrivals += record.id
            }
            return Outcome.APPLIED
        }

        /**
         * The workspace and folder a tab record puts its pin in: none for essentials, the folder's workspace for a tab
         * in a folder. `null` when the space or folder has not arrived yet.
         */
        private fun placementOf(data: JSONObject, essential: Boolean, existing: PinnedItem?): Pair<String?, String?>? {
            if (essential) return null to null
            val folderId = data.string("folderId")
            if (folderId != null) {
                val folder = pins.firstOrNull { it.id == folderId && it.isFolder } ?: return null
                return folder.workspaceId to folder.id
            }
            val workspaceId = data.string("workspaceUuid") ?: existing?.workspaceId ?: activeId
            return (workspaceId to null).takeIf { workspaces.any { it.id == workspaceId } }
        }

        /**
         * A tab that is not pinned: a pinned tab unpinned elsewhere, or one of Zen's unpinned tabs. Without unpinned
         * tabs synced it only unpins. With them, like Zen, the tab is opened here without loading it, or moved to its
         * workspace and, when its page is not loaded, pointed at its new address.
         */
        private fun upsertNormalTab(record: SpacesRecord, data: JSONObject): Outcome {
            val pin = pins.firstOrNull { it.id == record.id && !it.isFolder }
            if (!normalTabs) {
                pin?.let { unpin(it.id) }
                return Outcome.APPLIED
            }
            if (tabs == null) return Outcome.FAILED
            val url = data.string("url")
            if (url == null || !isSyncableUrl(url)) {
                pin?.let { unpin(it.id) }
                return Outcome.IGNORED
            }
            val container = containerOf(data) ?: return Outcome.FAILED
            val workspaceId = data.string("workspaceUuid")?.takeIf { id -> workspaces.any { it.id == id } }
                ?: return Outcome.FAILED
            val title = data.string("title").orEmpty()
            pin?.let { unpin(it.id) }
            val tabId = pin?.tabId?.takeIf { it in openTabs }
                ?: tabIdsBySyncId[record.id]?.takeIf { id -> pins.none { it.tabId == id } }
            if (tabId == null) {
                tabOps += TabOp.Open(record.id, url, title, container.contextId, workspaceId)
                assignments[record.id] = workspaceId
                applyLabel(record.id, data)
                return Outcome.APPLIED
            }
            assignments[tabId] = workspaceId
            applyLabel(tabId, data)
            val tab = openTabs.getValue(tabId)
            if (!tab.awake && tab.url != url) tabOps += TabOp.Retarget(tabId, url, title)
            return Outcome.APPLIED
        }

        /** Gives tab [tabId] the name of the record's static label, or its page title when it has none. */
        private fun applyLabel(tabId: String, data: JSONObject) {
            val label = data.string("staticLabel")?.takeIf { it.isNotEmpty() }
            if (label != null) tabTitles[tabId] = label else tabTitles.remove(tabId)
        }

        /**
         * A tab removed elsewhere. A pinned tab loses its pin; with unpinned tabs synced its open tab closes too, like
         * in Zen, and so does an unpinned tab.
         */
        private fun removeTab(id: String) {
            val pin = pins.firstOrNull { it.id == id && !it.isFolder }
            val syncsTabs = normalTabs && tabs != null
            if (pin != null) {
                val tabId = pin.tabId
                if (syncsTabs && tabId != null && tabId in openTabs) {
                    pins.remove(pin)
                    tabOps += TabOp.Close(tabId)
                } else {
                    unpin(pin.id)
                }
                return
            }
            if (!syncsTabs) return
            tabIdsBySyncId[id]?.takeIf { tabId -> pins.none { it.tabId == tabId } }?.let { tabOps += TabOp.Close(it) }
        }

        /**
         * Removes a pinned tab; its open tab stays open as a normal tab, keeping a name the user gave it and syncing
         * under the pin's id.
         */
        private fun unpin(pinId: String) {
            val pin = pins.firstOrNull { it.id == pinId && !it.isFolder } ?: return
            pins.remove(pin)
            pin.tabId?.let { tabId ->
                assignments[tabId] = pin.workspaceId ?: activeId
                if (pin.staticLabel) tabTitles[tabId] = pin.title
                if (tabId != pin.id) tabSyncIds[tabId] = pin.id
            }
        }

        /**
         * Removes a folder deleted elsewhere with the synced items inside it, like Zen does. Items that never reached
         * the server move up to where the folder was instead of being lost.
         */
        private fun deleteFolder(folder: PinnedItem) {
            val nested = descendants(folder.id)
            val removed = nested.filter { id ->
                id in incomingIds || server[id]?.let { it.isSynced || it.deleted } == true
            }.toSet()
            removed.forEach { id -> pins.firstOrNull { it.id == id }?.let { if (it.isFolder) pins.remove(it) else removeTab(id) } }
            val gone = removed + folder.id
            pins.replaceAll { if (it.parentId in gone) it.copy(parentId = folder.parentId, updatedAt = now) else it }
            pins.removeAll { it.id == folder.id }
        }

        /**
         * Removes a space deleted elsewhere. What is left in it moves to a neighbouring workspace, like when deleting
         * a workspace in Dejavu. The last workspace is kept, which uploads it again.
         */
        private fun deleteWorkspace(id: String) {
            val index = workspaces.indexOfFirst { it.id == id }
            if (index < 0 || workspaces.size == 1) return
            val target = workspaces[if (index > 0) index - 1 else 1].id
            workspaces.removeAt(index)
            pins.replaceAll { if (it.workspaceId == id) it.copy(workspaceId = target, updatedAt = now) else it }
            assignments.replaceAll { _, workspaceId -> if (workspaceId == id) target else workspaceId }
            if (activeId == id) activeId = target
        }

        /**
         * Orders the children of the applied spaces and folders, then the spaces and essentials of the layout. Pinned
         * tabs and folders that arrived without the record ordering their place, like a tab Zen already listed in the
         * layout while its page had not loaded and its own record came later, go where the last known order of that
         * place puts them, instead of at its end.
         */
        private fun applyOrder(spaces: List<SpacesRecord>, folders: List<SpacesRecord>, layout: SpacesRecord?) {
            spaces.filter { it in applied }.forEach { reorderChildren(it.id, null, it.data?.strings("children")) }
            folders.filter { it in applied }.forEach { record ->
                pins.firstOrNull { it.id == record.id }?.workspaceId?.let { workspaceId ->
                    reorderChildren(workspaceId, record.id, record.data?.strings("children"))
                }
            }
            layout?.let {
                applyLayout(it.data)
                applied += it
            }

            val ordered = (spaces + folders).filter { it in applied }.map { it.id }.toSet()
            val moved = pins.filter { it.id in arrivals }
            val places = moved.filterNot { it.essential }.mapNotNull { pin -> pin.workspaceId?.let { it to parentOf(pin) } }
            places.distinct().forEach { (workspaceId, parentId) ->
                val placeId = parentId ?: workspaceId
                val children = server[placeId]?.data?.strings("children")?.takeIf { placeId !in ordered }
                    ?: return@forEach
                placeArrivalsIn(childSlots(workspaceId, parentId), children.flatMap { splitMembers[it] ?: listOf(it) })
            }
            if (layout == null && moved.any { it.essential }) {
                val groups = server[LAYOUT_RECORD_ID]?.data?.optJSONObject("essentials") ?: return
                for (key in groups.keys()) {
                    groups.strings(key)?.let { order -> placeArrivalsIn(essentialSlots(key), order) }
                }
            }
        }

        private fun reorderChildren(workspaceId: String, parentId: String?, children: List<String>?) {
            if (children.isNullOrEmpty()) return
            val desired = children.flatMap { splitMembers[it] ?: listOf(it) }
            reorderSlots(childSlots(workspaceId, parentId), desired)
        }

        private fun applyLayout(data: JSONObject?) {
            data ?: return
            data.strings("spaces")?.let { order ->
                val ids = reorder(workspaces.map { it.id }, order)
                val byId = workspaces.associateBy { it.id }
                workspaces.clear()
                ids.mapTo(workspaces) { byId.getValue(it) }
            }
            val groups = data.optJSONObject("essentials") ?: return
            for (key in groups.keys()) {
                val order = groups.strings(key) ?: continue
                reorderSlots(essentialSlots(key), order)
            }
        }

        /** Places in [pins] of the children of [parentId] in [workspaceId], or of its top level for `null`. */
        private fun childSlots(workspaceId: String, parentId: String?): List<Int> = pins.indices.filter { index ->
            val pin = pins[index]
            !pin.essential && pin.workspaceId == workspaceId && parentOf(pin) == parentId
        }

        /** Places in [pins] of the essentials of the layout's group [key]. */
        private fun essentialSlots(key: String): List<Int> = pins.indices.filter { index ->
            pins[index].essential && essentialsKey(pins[index].containerId) == key
        }

        private fun reorderSlots(slots: List<Int>, desired: List<String>) = rearrange(slots) { reorder(it, desired) }

        private fun placeArrivalsIn(slots: List<Int>, desired: List<String>) =
            rearrange(slots) { placeArrivals(it, desired, arrivals) }

        /** Puts the items at [slots] of [pins] in the order [order] gives their ids. */
        private fun rearrange(slots: List<Int>, order: (List<String>) -> List<String>) {
            val current = slots.map { pins[it] }
            val ids = order(current.map { it.id })
            if (ids == current.map { it.id }) return
            val byId = current.associateBy { it.id }
            slots.forEachIndexed { position, slot -> pins[slot] = byId.getValue(ids[position]) }
        }

        private fun moveNested(folderId: String, workspaceId: String) {
            val nested = descendants(folderId)
            pins.replaceAll { if (it.id in nested) it.copy(workspaceId = workspaceId) else it }
        }

        private fun descendants(folderId: String): Set<String> {
            val result = mutableSetOf<String>()
            var level = setOf(folderId)
            while (level.isNotEmpty()) {
                level = pins.filter { it.parentId in level && it.id !in result }.map { it.id }.toSet()
                result += level
            }
            return result
        }

        private fun isInside(folderId: String, ancestorId: String): Boolean = folderId in descendants(ancestorId)

        private fun parentOf(pin: PinnedItem): String? =
            pin.parentId?.takeIf { id -> pins.any { it.id == id && it.isFolder && it.workspaceId == pin.workspaceId } }

        private fun containerOf(data: JSONObject): ContainerRef? {
            val guid = data.string("containerGuid")?.takeIf { it.isNotEmpty() } ?: return ContainerRef(null)
            return if (guid in containerIds) ContainerRef(guid) else null
        }

        /** Keeps a local container the server cannot see, like a temporary one, while the synced one is unchanged. */
        private fun keepLocalContainer(local: String?, synced: String?): String? =
            if (local?.takeIf { it in containerIds } == synced) local else synced

        private fun essentialsKey(containerId: String?): String =
            containerId?.takeIf { it in containerIds } ?: SpacesProjector.DEFAULT_ESSENTIALS

        private fun sortParentsFirst(folders: List<SpacesRecord>): List<SpacesRecord> {
            val parents = folders.associate { it.id to it.data?.string("parentFolderId") }
            fun depth(id: String): Int {
                val seen = mutableSetOf(id)
                var depth = 0
                var parent = parents[id]
                while (parent != null && parent in parents && seen.add(parent)) {
                    depth++
                    parent = parents[parent]
                }
                return depth
            }
            return folders.sortedBy { depth(it.id) }
        }
    }
}
