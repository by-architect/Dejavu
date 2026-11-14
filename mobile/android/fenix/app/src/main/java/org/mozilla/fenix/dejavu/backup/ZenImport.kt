/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.backup

import mozilla.components.browser.state.state.ContainerState
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.sync.ApplyResult
import org.mozilla.fenix.dejavu.sync.BuiltinContainer
import org.mozilla.fenix.dejavu.sync.LocalTab
import org.mozilla.fenix.dejavu.sync.RecordKind
import org.mozilla.fenix.dejavu.sync.SpacesApplier
import org.mozilla.fenix.dejavu.sync.SpacesProjector
import org.mozilla.fenix.dejavu.sync.SpacesRecord
import org.mozilla.fenix.dejavu.sync.SyncJson
import org.mozilla.fenix.dejavu.sync.containerColorOf
import org.mozilla.fenix.dejavu.sync.containerIconOf
import org.mozilla.fenix.dejavu.sync.pinnedSplitsOf
import org.mozilla.fenix.dejavu.sync.putNullable
import org.mozilla.fenix.dejavu.sync.string
import org.mozilla.fenix.dejavu.sync.strings
import org.mozilla.fenix.dejavu.workspaces.WorkspaceSerializer
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState

/**
 * Brings the records of a Zen profile, see [ZenRecords], into Dejavu's containers and workspaces, through the same
 * [SpacesApplier] that applies records synced from Zen. Everything here is without side effects, so it can run in
 * [org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository.update].
 */
internal object ZenImport {
    /** A container the import adds, as Dejavu has none with its id or its name. */
    data class NewContainer(
        val contextId: String,
        val name: String,
        val color: ContainerColor,
        val icon: ContainerState.Icon,
    )

    /**
     * What a Zen profile holds, for the user to confirm bringing it in.
     *
     * @property containers The containers its spaces and tabs use or it names, Dejavu's own ones of the same name
     *   included.
     * @property unpinnedTabs The tabs that are not pinned, which come along only when asked.
     * @property leftOutContainers Containers its tabs use that the picked files do not name, which those tabs lose.
     */
    data class Summary(
        val workspaces: Int,
        val pinnedTabs: Int,
        val essentials: Int,
        val folders: Int,
        val containers: Int,
        val unpinnedTabs: Int,
        val leftOutContainers: Int,
    )

    /**
     * @property records The records to apply, without the containers, which name Dejavu's containers.
     * @property containers The containers to add before the records are applied.
     * @property collapsedFolders Whether each folder is closed in Zen, by its id.
     */
    class Plan(
        val records: List<SpacesRecord>,
        val containers: List<NewContainer>,
        val collapsedFolders: Map<String, Boolean>,
        val summary: Summary,
    )

    /**
     * Plans bringing [zen] into Dejavu with the containers it has, [local]. A container of Zen becomes Dejavu's
     * container with the same id, as synced ones have Zen's ids, or with the same name, or else a new one. Firefox's
     * default containers that are used without being named get the names [builtinName] gives them.
     */
    fun plan(zen: ZenRecords, local: List<ContainerRecord>, builtinName: (BuiltinContainer) -> String): Plan {
        val permanent = local.filterNot { it.temporary }
        val byId = permanent.associateBy { it.contextId }
        val added = mutableListOf<NewContainer>()
        fun named(name: String): String? =
            permanent.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }?.contextId
                ?: added.firstOrNull { it.name.equals(name, ignoreCase = true) }?.contextId
        fun add(container: NewContainer): String = container.also { added += it }.contextId

        val (containerRecords, records) = zen.records.partition { it.kind == RecordKind.CONTAINER }
        val contextIds = HashMap<String, String?>()
        for (record in containerRecords) {
            val data = record.data ?: continue
            val name = data.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val color = containerColorOf(data.opt("color"))
            val icon = containerIconOf(data.opt("icon"))
            contextIds[record.id] =
                byId[record.id]?.contextId ?: named(name) ?: add(NewContainer(record.id, name, color, icon))
        }
        val used = records.flatMap(::containersOf).toSet()
        for (guid in used - contextIds.keys) {
            val builtin = BuiltinContainer.of(guid)
            contextIds[guid] = byId[guid]?.contextId ?: builtin?.let { container ->
                val name = builtinName(container)
                named(name) ?: add(NewContainer(guid, name, container.color, container.icon))
            }
        }
        val rewritten = records.map { rewrite(it, contextIds) }
        val tabs = rewritten.filter { it.kind == RecordKind.TAB }.mapNotNull { it.data }
        return Plan(
            records = rewritten,
            containers = added,
            collapsedFolders = zen.collapsedFolders,
            summary = Summary(
                workspaces = rewritten.count { it.kind == RecordKind.SPACE },
                pinnedTabs = tabs.count { it.opt("pinned") == true && it.opt("essential") != true },
                essentials = tabs.count { it.opt("essential") == true },
                folders = rewritten.count { it.kind == RecordKind.FOLDER },
                containers = contextIds.values.filterNotNull().distinct().size,
                unpinnedTabs = zen.unpinnedTabs,
                leftOutContainers = zen.leftOutContainers.size + used.count { contextIds[it] == null },
            ),
        )
    }

    /**
     * Applies [plan] to [state], adding Zen's workspaces next to the ones there, or with [replace] in their place.
     * What is there already, by its id, stays as it is when adding, so bringing in the same profile twice adds nothing
     * the second time and changes nothing the user changed since. The one workspace a fresh Dejavu starts with makes
     * way for Zen's ones while it is as it came, without tabs.
     *
     * @param openTabs The open tabs that are not private, which stay open.
     * @param containerIds Dejavu's permanent containers, the ones [plan] adds included.
     * @param includeUnpinned Whether Zen's tabs that are not pinned come along; the result asks to open them.
     */
    @Suppress("LongParameterList")
    fun applyPlan(
        plan: Plan,
        state: WorkspaceState,
        openTabs: List<LocalTab>,
        containerIds: Set<String>,
        includeUnpinned: Boolean,
        replace: Boolean,
        defaultName: String,
        now: Long,
    ): ApplyResult {
        val base = if (replace) WorkspaceSerializer.read(null, defaultName, now) else state
        val openSyncIds = openTabs.map { state.syncIdOf(it.id) }.toSet()
        val records = newIn(plan.records, base, openSyncIds)
        val applier = SpacesApplier(
            server = emptyMap(),
            containerIds = containerIds,
            firstSync = replace || openTabs.isEmpty(),
            defaultName = defaultName,
            now = now,
            normalTabs = includeUnpinned,
            tabs = openTabs.associateBy { it.id },
        )
        val result = applier.apply(base, records)
        val imported = withZenLooks(result.state, records, plan.collapsedFolders)
        val outcome = if (replace) replacing(state, imported, openTabs.map { it.id }.toSet()) else imported
        return ApplyResult(outcome, result.applied, result.failed, result.tabOps)
    }

    /** The containers [record] uses, by the ids Zen syncs them under. */
    private fun containersOf(record: SpacesRecord): List<String> {
        val data = record.data ?: return emptyList()
        return when (record.kind) {
            RecordKind.SPACE, RecordKind.TAB -> listOfNotNull(data.string("containerGuid"))
            RecordKind.LAYOUT -> data.optJSONObject("essentials")?.keys()?.asSequence()
                ?.filter { it != SpacesProjector.DEFAULT_ESSENTIALS }?.toList().orEmpty()
            else -> emptyList()
        }
    }

    /** [record] naming Dejavu's containers, by [contextIds], in place of Zen's ids, or no container for none. */
    private fun rewrite(record: SpacesRecord, contextIds: Map<String, String?>): SpacesRecord {
        val data = record.data ?: return record
        val kind = record.kind ?: return record
        return when (kind) {
            RecordKind.SPACE, RecordKind.TAB -> {
                val guid = data.string("containerGuid") ?: return record
                val contextId = contextIds[guid]
                if (contextId == guid) {
                    record
                } else {
                    SpacesRecord.of(record.id, kind, SyncJson.copy(data).putNullable("containerGuid", contextId))
                }
            }
            RecordKind.LAYOUT -> {
                val groups = data.optJSONObject("essentials") ?: return record
                val essentials = JSONObject()
                groups.keys().forEach { key ->
                    val target = if (key == SpacesProjector.DEFAULT_ESSENTIALS) {
                        key
                    } else {
                        contextIds[key] ?: SpacesProjector.DEFAULT_ESSENTIALS
                    }
                    val ids = essentials.optJSONArray(target) ?: JSONArray().also { essentials.put(target, it) }
                    groups.strings(key).orEmpty().forEach { ids.put(it) }
                }
                SpacesRecord.of(record.id, kind, SyncJson.copy(data).put("essentials", essentials))
            }
            else -> record
        }
    }

    /**
     * The records of [records] whose items [base] does not have yet, and the open tabs of [openSyncIds] do not show,
     * with the layout ordering only those: the order of the workspaces and essentials there stays as it is.
     */
    private fun newIn(records: List<SpacesRecord>, base: WorkspaceState, openSyncIds: Set<String>): List<SpacesRecord> {
        val existing = base.workspaces.map { it.id }.toSet() + base.pins.map { it.id }
        val kept = records.filter { record ->
            record.kind == RecordKind.LAYOUT ||
                (record.id !in existing && !(record.isNormalTab && record.id in openSyncIds))
        }
        val keptIds = kept.map { it.id }.toSet()
        return kept.map { record ->
            val data = record.data
            if (record.kind != RecordKind.LAYOUT || data == null) return@map record
            val essentials = JSONObject()
            data.optJSONObject("essentials")?.let { groups ->
                groups.keys().forEach { key ->
                    essentials.put(key, JSONArray(groups.strings(key).orEmpty().filter(keptIds::contains)))
                }
            }
            val spaces = data.strings("spaces").orEmpty().filter(keptIds::contains)
            val layout = SyncJson.copy(data).put("spaces", JSONArray(spaces)).put("essentials", essentials)
            SpacesRecord.of(record.id, RecordKind.LAYOUT, layout)
        }
    }

    /**
     * [state] with what Zen shows of [records] that synced records leave out: the folders open or closed as in Zen, and
     * Zen's split views of two pinned tabs.
     */
    private fun withZenLooks(
        state: WorkspaceState,
        records: List<SpacesRecord>,
        collapsed: Map<String, Boolean>,
    ): WorkspaceState {
        val folderIds = records.filter { it.kind == RecordKind.FOLDER }.map { it.id }.toSet()
        val looks = state.pins.map { pin ->
            val closed = collapsed[pin.id]
            if (pin.isFolder && pin.id in folderIds && closed != null) pin.copy(collapsed = closed) else pin
        }
        val pinIds = looks.map { it.id }.toSet()
        val splits = (state.pinnedSplits + pinnedSplitsOf(records, looks))
            .distinctBy { it.id }
            .filter { split -> split.pinIds.all(pinIds::contains) }
        return state.copy(pins = looks, pinnedSplits = splits)
    }

    /**
     * [imported], Zen's workspaces, in place of the user's own workspaces of [current]. The workspaces of devices on
     * the account stay, since sync adds and removes them, and so do the open tabs [openTabIds]: in a device's workspace
     * when they were in one, or else in the first of Zen's workspaces, with the names the user gave them.
     */
    private fun replacing(current: WorkspaceState, imported: WorkspaceState, openTabIds: Set<String>): WorkspaceState {
        val devices = current.workspaces.filter { it.device != null }
        val deviceIds = devices.map { it.id }.toSet()
        val assignments = imported.assignments.toMutableMap()
        for (tabId in openTabIds) {
            if (tabId in assignments) continue
            assignments[tabId] = current.assignments[tabId]?.takeIf(deviceIds::contains) ?: imported.activeWorkspaceId
        }
        val present = assignments.keys
        return imported.copy(
            workspaces = imported.workspaces + devices,
            pins = imported.pins + current.pins.filter { it.workspaceId in deviceIds },
            assignments = assignments,
            splits = current.splits.filter { split -> split.tabIds.all(present::contains) },
            tabTitles = current.tabTitles.filterKeys(openTabIds::contains) + imported.tabTitles,
            tabSyncIds = current.tabSyncIds.filterKeys(openTabIds::contains) + imported.tabSyncIds,
            openedByPages = current.openedByPages.intersect(openTabIds),
        )
    }
}
