/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.sync

import org.json.JSONObject
import org.mozilla.fenix.kaizen.workspaces.PinKind
import org.mozilla.fenix.kaizen.workspaces.PinnedItem
import org.mozilla.fenix.kaizen.workspaces.Workspace
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState

/**
 * The outcome of applying incoming records.
 *
 * @property state Kaizen's workspaces with the records applied.
 * @property applied Records that are now reflected locally, or deliberately left alone. The server holds them as they
 *   are, so they become the server's known copy.
 * @property failed Records that could not be applied yet, usually because a space, folder or container they need has
 *   not arrived. They are applied again on the next sync.
 */
internal class ApplyResult(
    val state: WorkspaceState,
    val applied: List<SpacesRecord>,
    val failed: List<SpacesRecord>,
)

/**
 * Applies incoming records, except containers, to Kaizen's workspaces, like Zen's ZenSpacesSyncApplier does to its
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
 */
internal class SpacesApplier(
    private val server: Map<String, SpacesRecord>,
    private val containerIds: Set<String>,
    private val firstSync: Boolean,
    private val defaultName: String,
    private val now: Long,
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
        private var activeId = original.activeWorkspaceId
        private val applied = mutableListOf<SpacesRecord>()
        private val failed = mutableListOf<SpacesRecord>()
        private val incomingIds = incoming.map { it.id }.toSet()

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

            removals.forEach { record -> pins.firstOrNull { it.id == record.id && !it.isFolder }?.let { unpin(it.id) } }
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
            )
            return ApplyResult(state, applied, failed)
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
            val theme = ZenThemes.toKaizen(data.opt("theme"))
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
         * On the first sync, the untouched workspace a fresh Kaizen starts with is replaced by the synced ones instead
         * of being uploaded as an empty space.
         */
        private fun dropPristineWorkspace(created: List<String>, layout: SpacesRecord?) {
            if (!firstSync || created.isEmpty() || original.workspaces.size != 1) return
            val initial = original.workspaces.single()
            val pristine = initial.name == defaultName && initial.icon == null && initial.theme == null &&
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
                return Outcome.APPLIED
            }
            val current = pins[index]
            if (!current.isFolder) return Outcome.FAILED
            val updated = current.copy(title = name, icon = icon, workspaceId = workspaceId, parentId = parent?.id)
            if (updated != current) {
                pins[index] = updated.copy(updatedAt = now)
                if (current.workspaceId != workspaceId) moveNested(record.id, workspaceId)
            }
            return Outcome.APPLIED
        }

        private fun upsertTab(record: SpacesRecord): Outcome {
            val data = record.data ?: return Outcome.IGNORED
            if (data.opt("pinned") == false) {
                // A normal tab: unpinned elsewhere, or synced by Zen's optional normal tab sync, which Kaizen skips.
                unpin(record.id)
                return Outcome.APPLIED
            }
            val url = data.string("url")
            if (url.isNullOrEmpty() || url == SpacesProjector.BLANK_URL) return Outcome.IGNORED
            val container = containerOf(data) ?: return Outcome.FAILED
            val essential = data.opt("essential") == true
            val existing = pins.firstOrNull { it.id == record.id }
            if (existing?.isFolder == true) return Outcome.FAILED
            val (workspaceId, parentId) = placementOf(data, essential, existing) ?: return Outcome.FAILED

            val (title, staticLabel) = labelOf(data)
            if (existing == null) {
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
                    createdAt = now,
                    updatedAt = now,
                )
                return Outcome.APPLIED
            }
            val updated = existing.copy(
                title = title,
                staticLabel = staticLabel,
                url = url,
                containerId = keepLocalContainer(existing.containerId, container.contextId),
                essential = essential,
                workspaceId = workspaceId,
                parentId = parentId,
            )
            if (updated != existing) {
                pins[pins.indexOf(existing)] = updated.copy(updatedAt = now)
                val tabId = existing.tabId
                if (tabId != null && workspaceId != null && workspaceId != existing.workspaceId) {
                    assignments[tabId] = workspaceId
                }
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

        /** Removes a pinned tab; its open tab stays open as a normal tab, keeping a name the user gave it. */
        private fun unpin(pinId: String) {
            val pin = pins.firstOrNull { it.id == pinId && !it.isFolder } ?: return
            pins.remove(pin)
            pin.tabId?.let { tabId ->
                assignments[tabId] = pin.workspaceId ?: activeId
                if (pin.staticLabel) tabTitles[tabId] = pin.title
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
            removed.forEach { id -> pins.firstOrNull { it.id == id }?.let { if (it.isFolder) pins.remove(it) else unpin(id) } }
            val gone = removed + folder.id
            pins.replaceAll { if (it.parentId in gone) it.copy(parentId = folder.parentId, updatedAt = now) else it }
            pins.removeAll { it.id == folder.id }
        }

        /**
         * Removes a space deleted elsewhere. What is left in it moves to a neighbouring workspace, like when deleting
         * a workspace in Kaizen. The last workspace is kept, which uploads it again.
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

        /** Orders the children of the applied spaces and folders, then the spaces and essentials of the layout. */
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
        }

        private fun reorderChildren(workspaceId: String, parentId: String?, children: List<String>?) {
            if (children.isNullOrEmpty()) return
            val desired = children.flatMap { splitMembers[it] ?: listOf(it) }
            val slots = pins.indices.filter { index ->
                val pin = pins[index]
                !pin.essential && pin.workspaceId == workspaceId && parentOf(pin) == parentId
            }
            reorderSlots(slots, desired)
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
                val slots = pins.indices.filter { index ->
                    pins[index].essential && essentialsKey(pins[index].containerId) == key
                }
                reorderSlots(slots, order)
            }
        }

        private fun reorderSlots(slots: List<Int>, desired: List<String>) {
            val current = slots.map { pins[it] }
            val ids = reorder(current.map { it.id }, desired)
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
