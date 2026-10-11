/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import java.util.UUID
import org.mozilla.fenix.dejavu.workspaces.PinKind
import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.SyncedDevice
import org.mozilla.fenix.dejavu.workspaces.Workspace
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState

/**
 * A tab of a Firefox computer as Dejavu shows it: a pinned tab, a pinned tab in the folder of its tab group, or an open
 * tab of the computer's workspace.
 *
 * @property id The pinned tab's id, or the id the open tab syncs under.
 * @property url The page the tab shows in Firefox. Closing the tab here asks Firefox to close this page.
 * @property title The page's title in Firefox.
 * @property pinned Whether the tab is pinned in Firefox.
 * @property groupId The tab's group in Firefox, or `null`.
 * @property closedHere Whether the tab was closed or removed here. Firefox was asked to close it, and it does not come
 *   back while Firefox still has it.
 * @property confirmed Whether the tab was seen here since Dejavu showed it. Only then can it be closed here: a tab Dejavu
 *   stopped before keeping, like when the app was closed right after opening it, is shown again instead.
 */
internal data class MirroredTab(
    val id: String,
    val url: String,
    val title: String,
    val pinned: Boolean = false,
    val groupId: String? = null,
    val closedHere: Boolean = false,
    val confirmed: Boolean = false,
) {
    /** Whether Dejavu shows the tab as a pinned tab: pinned in Firefox, or in a tab group. */
    val isPin: Boolean
        get() = pinned || groupId != null

    /** Where the tab is in Firefox: pinned, in a group, or neither. */
    val place: Pair<Boolean, String?>
        get() = pinned to groupId

    /** Whether Firefox shows [other] the same way: the same page, title and place. */
    fun sameAs(other: MirroredTab?): Boolean =
        other != null && url == other.url && title == other.title && place == other.place
}

/** A tab group of a Firefox computer, shown as the folder [folderId] in the group's [color]. */
internal data class MirroredGroup(
    val id: String,
    val folderId: String,
    val name: String,
    val collapsed: Boolean,
    val color: String? = null,
)

/**
 * A Firefox computer as Dejavu last showed it.
 *
 * @property clientId Id of the computer's clients and tabs records.
 * @property fxaDeviceId The computer's id in the Mozilla account, which close commands go to.
 * @property name The computer's name, which its workspace is named after.
 * @property workspaceId The workspace showing the computer's tabs.
 * @property positioned Whether [tabs] are in Firefox's order, see [TabsRecord.positioned].
 * @property groups The computer's tab groups, in the order of their first tab.
 * @property tabs The computer's tabs in Firefox's order.
 * @property kind Whether the device is a computer or a phone.
 */
internal data class MirroredDevice(
    val clientId: String,
    val fxaDeviceId: String?,
    val name: String,
    val workspaceId: String,
    val positioned: Boolean = true,
    val groups: List<MirroredGroup> = emptyList(),
    val tabs: List<MirroredTab> = emptyList(),
    val kind: SyncedDevice = SyncedDevice.DESKTOP,
) {
    /** The pinned tabs and folders of the workspace in Firefox's order: pinned tabs first, like in Firefox. */
    fun topLevel(): List<String> = tabs.filter { !it.closedHere && it.pinned }.map { it.id } + groups.map { it.folderId }

    /** The pinned tabs of the folder of group [groupId] in Firefox's order. */
    fun inGroup(groupId: String): List<String> = tabs.filter { !it.closedHere && it.groupId == groupId }.map { it.id }
}

/**
 * The outcome of [FirefoxMirror.findClosedHere].
 *
 * @property devices The devices with the tabs closed here marked.
 * @property commands Pages to close, by the account's device id of the computer that has them open.
 * @property marked How many tabs were newly found closed here.
 * @property changed Whether [devices] differ from the ones looked at.
 */
internal class ClosedHere(
    val devices: List<MirroredDevice>,
    val commands: Map<String, List<String>>,
    val marked: Int,
    val changed: Boolean,
)

/** The outcome of [FirefoxMirror.apply]: Dejavu's workspaces, and what to do with open tabs. */
internal class MirrorResult(val state: WorkspaceState, val tabOps: List<TabOp>)

/**
 * A tab opened here in the workspace of a computer and sent to it, which opens it too. Once the computer shows it,
 * [FirefoxMirror.match] takes the tab here for it, and from then on it is one of the computer's tabs.
 *
 * @property id The id the tab here syncs under.
 * @property clientId The computer it was sent to.
 * @property url The page that was sent.
 */
internal data class SentTab(val id: String, val clientId: String, val url: String)

/**
 * The outcome of [FirefoxMirror.findOpenedHere].
 *
 * @property toSend Tabs opened here in the workspace of a computer, with their titles, to send to it.
 * @property gone Tabs sent before that are not here anymore: they were closed before their computer showed them.
 */
internal class OpenedHere(val toSend: List<Pair<SentTab, String>>, val gone: List<SentTab>)

/**
 * Shows the open tabs of Firefox computers in Dejavu, each computer as a workspace: its pinned tabs as pinned tabs, each
 * tab group as a folder of pinned tabs, and its other tabs as open tabs of the workspace, opened without loading.
 *
 * Firefox gives its tabs no ids, so [match] finds which tab of a new record is which one shown before: by address, then
 * by place between the same neighbours for a tab that went to another page. Like spaces sync, [apply] only changes what
 * changed in Firefox since the last record, so changes made here stay until Firefox changes the same tab. Tabs closed
 * or removed here are found by [findClosedHere], and Firefox is asked to close them. Tabs opened here in the workspace
 * of a computer are found by [findOpenedHere] and sent to it, and become its tabs once it shows them.
 *
 * @param now Time used for the changes, in milliseconds.
 * @param normalTabs Whether the tabs that are neither pinned nor in a group are shown. While they are not, or while the
 *   browser has not restored its tabs, Dejavu leaves those tabs as they are.
 * @param tabs The open tabs that are not private by id, or `null` while the browser has not restored them.
 * @param unnamedGroup Name of the folder of a tab group without a name.
 * @param newId Makes ids for new pinned and open tabs.
 */
internal class FirefoxMirror(
    private val now: Long,
    private val normalTabs: Boolean,
    private val tabs: Map<String, LocalTab>?,
    private val unnamedGroup: String,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val showsLooseTabs = normalTabs && tabs != null

    /**
     * Finds the tabs of [devices] that are gone here, closed or removed by the user, and marks them. Their computers are
     * asked to close them. Tabs never seen here since Dejavu showed them are forgotten instead, so they are shown
     * again, unless [shownHere], the tabs shown during this run of the app, has them: those can only be gone because
     * the user closed them. The ones seen for the first time are confirmed. Nothing is looked at while the browser has
     * not restored its tabs.
     */
    fun findClosedHere(
        state: WorkspaceState,
        devices: List<MirroredDevice>,
        shownHere: Set<String> = emptySet(),
    ): ClosedHere {
        if (tabs == null) return ClosedHere(devices, emptyMap(), marked = 0, changed = false)
        val present = shownIds(state)
        val commands = LinkedHashMap<String, MutableList<String>>()
        var marked = 0
        var changed = false
        val updated = devices.map { device ->
            device.copy(
                tabs = device.tabs.mapNotNull { tab ->
                    when {
                        tab.closedHere || (!tab.isPin && !normalTabs) -> tab
                        tab.id in present -> if (tab.confirmed) tab else tab.copy(confirmed = true).also { changed = true }
                        !tab.confirmed && tab.id !in shownHere -> null.also { changed = true }
                        else -> {
                            marked++
                            changed = true
                            device.fxaDeviceId?.let { commands.getOrPut(it) { mutableListOf() } += tab.url }
                            tab.copy(closedHere = true)
                        }
                    }
                },
            )
        }
        return ClosedHere(updated, commands, marked, changed)
    }

    /**
     * Finds the tabs opened here since [since] in the workspace of a computer that are not its tabs yet, to send to
     * it once their page loaded, and the tabs of [sent] that are not here anymore. Pinned tabs stay here, and nothing
     * is sent while tabs that are neither pinned nor in a group are not shown, or before the browser restored its tabs.
     */
    fun findOpenedHere(
        state: WorkspaceState,
        devices: List<MirroredDevice>,
        sent: List<SentTab>,
        since: Long,
    ): OpenedHere {
        val openTabs = tabs ?: return OpenedHere(emptyList(), emptyList())
        val here = openTabs.keys.mapTo(HashSet()) { state.syncIdOf(it) }
        val gone = sent.filter { it.id !in here }
        if (!showsLooseTabs) return OpenedHere(emptyList(), gone)
        val pinnedTabs = state.pins.mapNotNullTo(HashSet()) { it.tabId }
        val waiting = sent.mapTo(HashSet()) { it.id }
        val toSend = devices.filter { it.fxaDeviceId != null }.flatMap { device ->
            val theirs = device.tabs.mapTo(HashSet()) { it.id }
            openTabs.values
                .filter { tab ->
                    state.workspaceOf(tab.id) == device.workspaceId && tab.id !in pinnedTabs && !tab.loading &&
                        tab.createdAt >= since && isSyncableUrl(tab.url)
                }
                .mapNotNull { tab ->
                    val id = state.syncIdOf(tab.id)
                    if (id in theirs || id in waiting) null else SentTab(id, device.clientId, tab.url) to tab.title
                }
        }
        return OpenedHere(toSend, gone)
    }

    /**
     * The ids that tabs of computers can be shown under here: those of the pinned tabs, and the ids the open tabs sync
     * under. Empty while the browser has not restored its tabs.
     */
    fun shownIds(state: WorkspaceState): Set<String> {
        val openTabs = tabs ?: return emptySet()
        val present = state.pins.filterNot { it.isFolder }.map { it.id }.toHashSet()
        openTabs.keys.mapTo(present) { state.syncIdOf(it) }
        return present
    }

    /**
     * The tabs and groups of [computer] with the ids they are shown under: those of [previous], the computer as last
     * shown, for the tabs found again, those of the tabs here of [sent] for the ones they became, and new ones for the
     * others. [state] is also looked at for a computer not shown before, whose pinned and open tabs already in its
     * workspace are taken over, like after Dejavu forgot what it showed.
     */
    fun match(
        previous: MirroredDevice?,
        computer: FirefoxComputer,
        state: WorkspaceState,
        sent: List<SentTab> = emptyList(),
    ): MirroredDevice {
        val clientId = computer.client.id
        val workspaceId = previous?.workspaceId ?: workspaceIdOf(clientId)
        val incoming = computer.record.tabs.filter { showsLooseTabs || it.isPin }
        val known = previous?.tabs.orEmpty().filter { showsLooseTabs || it.isPin }
        val frozen = previous?.tabs.orEmpty().filter { !showsLooseTabs && !it.isPin }
        val matched = arrayOfNulls<MirroredTab>(incoming.size)
        val used = HashSet<String>()

        fun take(index: Int, candidate: MirroredTab?) {
            if (candidate == null) return
            matched[index] = candidate
            used += candidate.id
        }
        // The same page in the same place, then the same page moved to another place.
        incoming.forEachIndexed { i, tab ->
            take(i, known.firstOrNull { it.id !in used && it.url == tab.url && it.place == tab.place })
        }
        incoming.forEachIndexed { i, tab ->
            if (matched[i] == null) take(i, known.firstOrNull { it.id !in used && it.url == tab.url })
        }
        // Tabs opened here and sent to the computer, which shows them now: with the page sent, or with the one the tab
        // here went on to, like after a redirect.
        val arriving = sent.filterTo(mutableListOf()) { it.clientId == clientId && it.id !in used }
        if (arriving.isNotEmpty()) {
            val pages = this.tabs?.values?.associate { state.syncIdOf(it.id) to it.url }.orEmpty()
            incoming.forEachIndexed { i, tab ->
                if (matched[i] != null) return@forEachIndexed
                val arrived = arriving.firstOrNull { it.url == tab.url || pages[it.id] == tab.url }
                    ?: return@forEachIndexed
                arriving -= arrived
                take(i, MirroredTab(arrived.id, tab.url, tab.title, tab.pinned, tab.groupId, confirmed = true))
            }
        }
        val order = stableOrder(incoming, matched, known)
        pairNavigated(incoming, order, matched, known, used)

        val adoptable = if (previous == null) adoptable(state, workspaceId) else HashMap()
        val tabs = order.map { i ->
            val tab = incoming[i]
            val shown = matched[i]
            val adopted = if (shown == null) adoptable[tab.url to tab.isPin]?.removeFirstOrNull() else null
            MirroredTab(
                id = shown?.id ?: adopted ?: newId(),
                url = tab.url,
                title = tab.title,
                pinned = tab.pinned,
                groupId = tab.groupId,
                closedHere = shown?.closedHere == true,
                confirmed = shown?.confirmed == true || adopted != null,
            )
        }
        val previousGroups = previous?.groups.orEmpty().associateBy { it.id }
        val groups = tabs.mapNotNull { it.groupId }.distinct().map { groupId ->
            val group = computer.record.groups.getValue(groupId)
            val folderId = previousGroups[groupId]?.folderId ?: folderIdOf(clientId, groupId)
            MirroredGroup(groupId, folderId, group.name, group.collapsed, group.color)
        }
        return MirroredDevice(
            clientId = clientId,
            fxaDeviceId = computer.client.fxaDeviceId,
            name = computer.name,
            workspaceId = workspaceId,
            positioned = computer.record.positioned,
            groups = groups,
            tabs = tabs + frozen,
            kind = computer.kind,
        )
    }

    /**
     * The order of [incoming], as indices into it: Firefox's, except that windows keep the order they had before, since
     * Firefox names its windows after the order they were last used in. Windows without a tab seen before go last.
     */
    private fun stableOrder(incoming: List<FirefoxTab>, matched: Array<MirroredTab?>, known: List<MirroredTab>): List<Int> {
        val positions = known.withIndex().associate { it.value.id to it.index }
        val rank = HashMap<String, Int>()
        incoming.forEachIndexed { i, tab ->
            val position = matched[i]?.let { positions[it.id] } ?: return@forEachIndexed
            rank[tab.windowId] = minOf(rank[tab.windowId] ?: Int.MAX_VALUE, position)
        }
        return incoming.indices.sortedWith(compareBy({ rank[incoming[it].windowId] ?: Int.MAX_VALUE }, { it }))
    }

    /**
     * Matches the tabs of [incoming] still without a match with tabs of [known] that were not found again, when they are
     * in the same place between the same neighbours: the tab went to another page. Tabs closed here are not matched
     * this way, so a tab Firefox opened in their place shows.
     */
    private fun pairNavigated(
        incoming: List<FirefoxTab>,
        order: List<Int>,
        matched: Array<MirroredTab?>,
        known: List<MirroredTab>,
        used: MutableSet<String>,
    ) {
        for (place in order.map { incoming[it].place }.distinct()) {
            val here = order.filter { incoming[it].place == place }
            val before = known.filter { it.place == place }
            val positions = before.withIndex().associate { it.value.id to it.index }
            fun anchor(range: IntProgression) = range.firstNotNullOfOrNull { j -> matched[here[j]]?.let { positions[it.id] } }
            here.forEachIndexed { k, i ->
                if (matched[i] != null) return@forEachIndexed
                val from = anchor(k - 1 downTo 0)?.plus(1) ?: 0
                val to = anchor(k + 1 until here.size) ?: before.size
                val candidate = (from until to).map { before[it] }.firstOrNull { it.id !in used && !it.closedHere }
                if (candidate != null) {
                    matched[i] = candidate
                    used += candidate.id
                }
            }
        }
    }

    /** Ids of the pinned tabs (`true`) and open tabs (`false`) of workspace [workspaceId], by page. */
    private fun adoptable(state: WorkspaceState, workspaceId: String): HashMap<Pair<String, Boolean>, MutableList<String>> {
        val result = HashMap<Pair<String, Boolean>, MutableList<String>>()
        state.pins
            .filter { !it.isFolder && !it.essential && it.workspaceId == workspaceId }
            .forEach { pin -> pin.url?.let { result.getOrPut(it to true) { mutableListOf() } += pin.id } }
        val pinnedTabs = state.pins.mapNotNull { it.tabId }.toSet()
        tabs?.values
            ?.filter { it.id !in pinnedTabs && state.assignments[it.id] == workspaceId }
            ?.forEach { result.getOrPut(it.url to false) { mutableListOf() } += state.syncIdOf(it.id) }
        return result
    }

    /**
     * Applies what changed from [previous], the computers as last shown, to [current], as [match] found them: new and
     * changed tabs and groups, and the ones Firefox closed. Pure: the same input always gives the same result.
     */
    fun apply(state: WorkspaceState, previous: List<MirroredDevice>, current: List<MirroredDevice>): MirrorResult =
        Pass(state, removing = false).run(previous, current)

    /** Removes everything [devices] show, for when Dejavu stops following Firefox. */
    fun removeAll(state: WorkspaceState, devices: List<MirroredDevice>): MirrorResult =
        Pass(state, removing = true).run(devices, emptyList())

    @Suppress("TooManyFunctions")
    private inner class Pass(private val original: WorkspaceState, private val removing: Boolean) {
        private val workspaces = original.workspaces.toMutableList()
        private val pins = original.pins.toMutableList()
        private val assignments = original.assignments.toMutableMap()
        private val tabTitles = original.tabTitles.toMutableMap()
        private val tabSyncIds = original.tabSyncIds.toMutableMap()
        private var activeId = original.activeWorkspaceId
        private val tabOps = mutableListOf<TabOp>()
        private val openTabs = tabs.orEmpty()
        private val openBySyncId: Map<String, String> = openTabs.keys.associateBy { original.syncIdOf(it) }

        /** Pinned tabs and folders that this pass added, or moved to another place. */
        private val arrivals = mutableSetOf<String>()
        private val created = mutableListOf<String>()
        private val closing = mutableSetOf<String>()

        fun run(previous: List<MirroredDevice>, current: List<MirroredDevice>): MirrorResult {
            val currentIds = current.map { it.clientId }.toSet()
            previous.filter { it.clientId !in currentIds }.forEach(::removeDevice)
            val before = previous.associateBy { it.clientId }
            current.forEach { applyDevice(before[it.clientId], it) }
            if (previous.isEmpty()) showFirstCreated()
            val state = original.copy(
                workspaces = workspaces,
                pins = pins,
                activeWorkspaceId = activeId.takeIf { id -> workspaces.any { it.id == id } } ?: workspaces.first().id,
                assignments = assignments,
                tabTitles = tabTitles,
                tabSyncIds = tabSyncIds,
            )
            return MirrorResult(state, tabOps)
        }

        private fun applyDevice(old: MirroredDevice?, device: MirroredDevice) {
            if (old == null) ensureWorkspace(device)
            keepDeviceLook(device, renamed = old != null && old.name != device.name)
            val oldGroups = old?.groups.orEmpty().associateBy { it.id }
            for (group in device.groups) {
                val before = oldGroups[group.id] ?: continue
                if (before.name != group.name || before.collapsed != group.collapsed || before.color != group.color) {
                    updateFolder(group)
                }
            }
            val oldTabs = old?.tabs.orEmpty().associateBy { it.id }
            for (tab in device.tabs) {
                val before = oldTabs[tab.id]
                if (!tab.closedHere && !tab.sameAs(before)) upsertTab(device, tab, before)
            }
            val tabIds = device.tabs.map { it.id }.toSet()
            old?.tabs.orEmpty().filter { it.id !in tabIds && !it.closedHere }.forEach(::removeTab)
            val groupIds = device.groups.map { it.id }.toSet()
            old?.groups.orEmpty().filter { it.id !in groupIds }.forEach { deleteFolder(it.folderId) }
            applyOrder(old, device)
        }

        private fun removeDevice(device: MirroredDevice) {
            device.tabs.filter { !it.closedHere && (removing || showsLooseTabs || it.isPin) }.forEach(::removeTab)
            device.groups.forEach { deleteFolder(it.folderId) }
            removeWorkspaceIfEmpty(device.workspaceId)
        }

        private fun upsertTab(device: MirroredDevice, tab: MirroredTab, before: MirroredTab?) {
            val moved = before == null || before.place != tab.place
            if (tab.isPin) upsertPin(device, tab, before, moved) else upsertLooseTab(device, tab, before, moved)
        }

        /**
         * Shows a tab that is pinned or in a group as a pinned tab. One already here takes the new page, and goes to its
         * new place only when it moved in Firefox. An open tab that syncs under the same id becomes the pin's tab.
         */
        private fun upsertPin(device: MirroredDevice, tab: MirroredTab, before: MirroredTab?, moved: Boolean) {
            val index = pins.indexOfFirst { it.id == tab.id && !it.isFolder }
            if (index >= 0) {
                followPage(index, tab)
                if (moved) {
                    val pin = pins[index]
                    val workspaceId = ensureWorkspace(device)
                    val parentId = tab.groupId?.let { ensureFolder(device, it) }
                    pins[pins.indexOf(pin)] = pin.copy(
                        workspaceId = workspaceId,
                        parentId = parentId,
                        essential = false,
                        updatedAt = now,
                    )
                    pin.tabId?.let { assignments[it] = workspaceId }
                    arrivals += tab.id
                }
                return
            }
            val openTab = openBySyncId[tab.id]?.takeIf { id -> pins.none { it.tabId == id } }
            // A tab shown before that is gone now was closed by the user, after Firefox was last asked to close it.
            if (before != null && openTab == null) return
            val workspaceId = ensureWorkspace(device)
            val parentId = tab.groupId?.let { ensureFolder(device, it) }
            pins += PinnedItem(
                id = tab.id,
                workspaceId = workspaceId,
                parentId = parentId,
                kind = PinKind.TAB,
                title = tab.title.ifBlank { tab.url },
                url = tab.url,
                tabId = openTab,
                createdAt = now,
                updatedAt = now,
            )
            openTab?.let {
                tabSyncIds.remove(it)
                tabTitles.remove(it)
                assignments[it] = workspaceId
            }
            arrivals += tab.id
        }

        /**
         * Shows a tab that is neither pinned nor in a group as an open tab of the computer's workspace. A pinned tab
         * Firefox unpinned stays open as a normal tab, and an open tab whose page is not loaded follows Firefox's page.
         */
        private fun upsertLooseTab(device: MirroredDevice, tab: MirroredTab, before: MirroredTab?, moved: Boolean) {
            val pinIndex = pins.indexOfFirst { it.id == tab.id && !it.isFolder }
            if (pinIndex >= 0 && !moved) {
                // Pinned here by the user: it only follows the page.
                followPage(pinIndex, tab)
                return
            }
            val workspaceId = ensureWorkspace(device)
            if (pinIndex >= 0) {
                val pin = pins.removeAt(pinIndex)
                val tabId = pin.tabId?.takeIf { it in openTabs }
                if (tabId == null) {
                    open(tab, workspaceId)
                    return
                }
                assignments[tabId] = workspaceId
                if (tabId != pin.id) tabSyncIds[tabId] = pin.id
                if (pin.staticLabel) tabTitles[tabId] = pin.title
                retarget(tabId, tab)
                return
            }
            val tabId = openBySyncId[tab.id]?.takeIf { id -> pins.none { it.tabId == id } }
            if (tabId == null) {
                if (before == null) open(tab, workspaceId)
                return
            }
            if (moved) assignments[tabId] = workspaceId
            retarget(tabId, tab)
        }

        /** Gives the pinned tab at [index] the page and title Firefox shows, keeping a name the user gave it. */
        private fun followPage(index: Int, tab: MirroredTab) {
            val pin = pins[index]
            val pageChanged = pin.url != tab.url
            val updated = pin.copy(
                url = tab.url,
                title = if (pin.staticLabel) pin.title else tab.title.ifBlank { tab.url },
                openUrl = if (pageChanged) null else pin.openUrl,
                openTitle = if (pageChanged) null else pin.openTitle,
            )
            if (updated != pin) pins[index] = updated.copy(updatedAt = now)
        }

        private fun open(tab: MirroredTab, workspaceId: String) {
            tabOps += TabOp.Open(tab.id, tab.url, tab.title, contextId = null, workspaceId = workspaceId)
            assignments[tab.id] = workspaceId
        }

        /** Points open tab [tabId] at the tab's page in Firefox, when its own page is not loaded. */
        private fun retarget(tabId: String, tab: MirroredTab) {
            val local = openTabs[tabId] ?: return
            if (!local.awake && local.url != tab.url) tabOps += TabOp.Retarget(tabId, tab.url, tab.title)
        }

        /**
         * Removes a tab Firefox closed: its pinned tab, and its open tab while tabs that are not pinned are shown. When
         * they are not, an open pinned tab stays open as a normal tab.
         */
        private fun removeTab(tab: MirroredTab) {
            val pin = pins.firstOrNull { it.id == tab.id && !it.isFolder }
            if (pin != null) {
                pins.remove(pin)
                val tabId = pin.tabId?.takeIf { it in openTabs } ?: return
                if (showsLooseTabs || removing) {
                    close(tabId)
                } else {
                    assignments[tabId] = pin.workspaceId ?: activeId
                    if (pin.staticLabel) tabTitles[tabId] = pin.title
                }
                return
            }
            val tabId = openBySyncId[tab.id]?.takeIf { id -> pins.none { it.tabId == id } }
            when {
                tabId != null && (showsLooseTabs || removing) -> close(tabId)
                // Before the browser restored its tabs, the tab Dejavu opened for it is closed by its id.
                tabId == null && tabs == null && removing && !tab.isPin -> close(tab.id)
            }
        }

        private fun close(tabId: String) {
            tabOps += TabOp.Close(tabId)
            closing += tabId
        }

        private fun ensureWorkspace(device: MirroredDevice): String {
            if (workspaces.none { it.id == device.workspaceId }) {
                workspaces += Workspace(
                    id = device.workspaceId,
                    name = device.name,
                    icon = iconOf(device.kind),
                    createdAt = now,
                    updatedAt = now,
                    device = device.kind,
                )
                created += device.workspaceId
            }
            return device.workspaceId
        }

        /**
         * Gives the device's workspace what comes from the device: its name when it was [renamed] there, its icon, its
         * mark as a device's workspace, and no container, since the device would not keep one.
         */
        private fun keepDeviceLook(device: MirroredDevice, renamed: Boolean) {
            val index = workspaces.indexOfFirst { it.id == device.workspaceId }
            if (index < 0) return
            val workspace = workspaces[index]
            val updated = workspace.copy(
                name = if (renamed) device.name else workspace.name,
                icon = iconOf(device.kind),
                device = device.kind,
                containerId = null,
            )
            if (updated != workspace) workspaces[index] = updated.copy(updatedAt = now)
        }

        /**
         * Removes the workspace of a device no longer followed, unless pinned or open tabs were added to it here. Then
         * it stays as one of the user's own workspaces.
         */
        private fun removeWorkspaceIfEmpty(workspaceId: String) {
            val index = workspaces.indexOfFirst { it.id == workspaceId }
            if (index < 0) return
            val hasTabs = if (tabs == null) {
                assignments.any { (tabId, assigned) -> assigned == workspaceId && tabId !in closing }
            } else {
                openTabs.keys.any { it !in closing && assignments[it] == workspaceId }
            }
            if (workspaces.size == 1 || hasTabs || pins.any { it.workspaceId == workspaceId }) {
                val workspace = workspaces[index]
                if (workspace.device != null) workspaces[index] = workspace.copy(device = null, updatedAt = now)
                return
            }
            val target = workspaces[if (index > 0) index - 1 else 1].id
            workspaces.removeAt(index)
            assignments.replaceAll { _, assigned -> if (assigned == workspaceId) target else assigned }
            if (activeId == workspaceId) activeId = target
        }

        /** The folder of group [groupId], created at the top of the computer's workspace when it is not here. */
        private fun ensureFolder(device: MirroredDevice, groupId: String): String? {
            val group = device.groups.firstOrNull { it.id == groupId } ?: return null
            if (pins.none { it.id == group.folderId && it.isFolder }) {
                pins += PinnedItem(
                    id = group.folderId,
                    workspaceId = ensureWorkspace(device),
                    parentId = null,
                    kind = PinKind.FOLDER,
                    title = group.name.ifBlank { unnamedGroup },
                    collapsed = group.collapsed,
                    createdAt = now,
                    updatedAt = now,
                    color = group.color,
                )
                arrivals += group.folderId
            }
            return group.folderId
        }

        private fun updateFolder(group: MirroredGroup) {
            val index = pins.indexOfFirst { it.id == group.folderId && it.isFolder }
            if (index < 0) return
            pins[index] = pins[index].copy(
                title = group.name.ifBlank { unnamedGroup },
                collapsed = group.collapsed,
                color = group.color,
                updatedAt = now,
            )
        }

        /** Removes the folder of a group Firefox closed. What is still inside, added here, moves up to where it was. */
        private fun deleteFolder(folderId: String) {
            val folder = pins.firstOrNull { it.id == folderId && it.isFolder } ?: return
            pins.replaceAll { if (it.parentId == folderId) it.copy(parentId = folder.parentId, updatedAt = now) else it }
            pins.remove(folder)
        }

        /**
         * Puts the pinned tabs and folders of the computer's workspace, and the pinned tabs of each folder, in Firefox's
         * order when it changed there. Otherwise only the ones that arrived go where Firefox has them. Items added here
         * stay after the one they followed.
         */
        private fun applyOrder(old: MirroredDevice?, device: MirroredDevice) {
            val workspaceId = device.workspaceId
            arrange(childSlots(workspaceId, null), device.topLevel(), old?.topLevel(), device.positioned)
            for (group in device.groups) {
                arrange(
                    childSlots(workspaceId, group.folderId),
                    device.inGroup(group.id),
                    old?.inGroup(group.id),
                    device.positioned,
                )
            }
        }

        private fun arrange(slots: List<Int>, desired: List<String>, before: List<String>?, positioned: Boolean) {
            when {
                positioned && desired != before -> rearrange(slots) { reorder(it, desired) }
                desired.any { it in arrivals } -> rearrange(slots) { placeArrivals(it, desired, arrivals) }
            }
        }

        /** Places in [pins] of the children of [parentId] in [workspaceId], or of its top level for `null`. */
        private fun childSlots(workspaceId: String, parentId: String?): List<Int> = pins.indices.filter { index ->
            val pin = pins[index]
            !pin.essential && pin.workspaceId == workspaceId && pin.parentId == parentId
        }

        /** Puts the items at [slots] of [pins] in the order [order] gives their ids. */
        private fun rearrange(slots: List<Int>, order: (List<String>) -> List<String>) {
            val current = slots.map { pins[it] }
            val ids = order(current.map { it.id })
            if (ids == current.map { it.id }) return
            val byId = current.associateBy { it.id }
            slots.forEachIndexed { position, slot -> pins[slot] = byId.getValue(ids[position]) }
        }

        /** On the first sync, shows the first new computer's workspace when the one shown has nothing in it yet. */
        private fun showFirstCreated() {
            val first = created.firstOrNull() ?: return
            if (tabs == null || pins.any { it.workspaceId == activeId }) return
            if (openTabs.keys.none { (assignments[it] ?: activeId) == activeId }) activeId = first
        }
    }

    companion object {
        /** The desktop computer emoji, the icon of the computers' workspaces. */
        @Suppress("MagicNumber")
        private val COMPUTER_ICON = buildString {
            appendCodePoint(0x1F5A5)
            appendCodePoint(0xFE0F)
        }

        /** The mobile phone emoji, the icon of the phones' workspaces. */
        @Suppress("MagicNumber")
        private val PHONE_ICON = buildString { appendCodePoint(0x1F4F1) }

        /** The icon of the workspace of a device of kind [kind]. */
        fun iconOf(kind: SyncedDevice): String = when (kind) {
            SyncedDevice.DESKTOP -> COMPUTER_ICON
            SyncedDevice.PHONE -> PHONE_ICON
        }

        /** The id of the workspace of computer [clientId], the same every time so that it is found again. */
        fun workspaceIdOf(clientId: String): String =
            "{${UUID.nameUUIDFromBytes("firefox-computer:$clientId".toByteArray())}}"

        /** The id of the folder of group [groupId] of computer [clientId], the same every time. */
        fun folderIdOf(clientId: String, groupId: String): String =
            UUID.nameUUIDFromBytes("firefox-group:$clientId:$groupId".toByteArray()).toString()
    }
}

private val FirefoxTab.isPin: Boolean
    get() = pinned || groupId != null

private val FirefoxTab.place: Pair<Boolean, String?>
    get() = pinned to groupId
