/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.backup

import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.SplitView
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.dejavu.workspaces.isSamePage

/**
 * How restoring a backup changes the workspaces. Everything here is without side effects, so it can run in
 * [org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository.update].
 */
internal object BackupRestore {
    /**
     * The tabs of [backup] to open again: the ones that are not open, by their id, and that no pinned tab of the backup
     * had, which opens on its own when tapped. A tab goes to its workspace, or to the one the backup showed when the
     * backup has no such workspace. The tabs of the workspaces of devices on the account are left to sync.
     */
    fun tabsToOpen(backup: DejavuBackup, openTabIds: Set<String>): List<BackupTab> {
        val own = backup.ownWorkspaceIds
        val devices = backup.state.workspaces.filter { it.device != null }.map { it.id }.toSet()
        val shown = shownWorkspaceOf(backup)
        val pinnedTabs = backup.state.pins.mapNotNull { it.tabId }.toSet()
        return backup.tabs
            .filter { tab -> tab.id !in openTabIds && tab.id !in pinnedTabs && tab.workspaceId !in devices }
            .distinctBy { it.id }
            .map { tab -> if (tab.workspaceId in own) tab else tab.copy(workspaceId = shown) }
    }

    /**
     * The workspaces once [backup] is restored over [current]: the backup's own workspaces with their pinned tabs,
     * folders, essentials and split views, next to the workspaces of devices on the account as they are here, since
     * sync adds and removes those. A pinned tab whose tab is not open here opens again on the page it was on. The open
     * tabs [openTabIds] stay open: in the workspace the backup had them in, or in theirs when the backup has it too, or
     * else in the workspace the backup showed. The tabs [reopened], opened again from the backup, go to their workspace
     * by their id.
     */
    fun restoredState(
        current: WorkspaceState,
        backup: DejavuBackup,
        openTabIds: Set<String>,
        reopened: Map<String, String>,
    ): WorkspaceState {
        val saved = backup.state
        val own = saved.workspaces.filter { it.device == null }
        val ownIds = own.map { it.id }.toSet()
        val devices = current.workspaces.filter { it.device != null }
        val deviceIds = devices.map { it.id }.toSet()
        val shown = shownWorkspaceOf(backup)
        val pages = backup.tabs.associateBy { it.id }
        val pins = saved.pins
            .filter { it.essential || it.workspaceId in ownIds }
            .map { pin -> pin.tabId?.takeUnless(openTabIds::contains)?.let { closed(pin, pages[it]) } ?: pin } +
            current.pins.filter { it.workspaceId in deviceIds }

        val assignments = HashMap<String, String>()
        for (tabId in openTabIds) {
            val here = current.assignments[tabId]
            assignments[tabId] = when {
                here != null && here in deviceIds -> here
                else -> saved.assignments[tabId]?.takeIf(ownIds::contains) ?: here?.takeIf(ownIds::contains) ?: shown
            }
        }
        for (pin in pins) {
            val tabId = pin.tabId ?: continue
            val workspaceId = pin.workspaceId ?: continue
            if (workspaceId in ownIds || workspaceId in deviceIds) assignments[tabId] = workspaceId
        }
        assignments += reopened

        val present = openTabIds + reopened.keys
        val splits = mutableListOf<SplitView>()
        for (split in saved.splits + current.splits) {
            val together = split.tabIds.all(present::contains)
            if (together && splits.none { kept -> kept.tabIds.any(split.tabIds::contains) }) splits += split
        }
        val pinIds = pins.map { it.id }.toSet()
        return WorkspaceState(
            workspaces = own + devices,
            pins = pins,
            activeWorkspaceId = shown,
            assignments = assignments,
            splits = splits,
            tabTitles = (current.tabTitles + saved.tabTitles).filterKeys(present::contains),
            tabSyncIds = (current.tabSyncIds + saved.tabSyncIds).filterKeys(present::contains),
            pinnedSplits = saved.pinnedSplits.filter { split -> split.pinIds.all(pinIds::contains) },
            openedByPages = (current.openedByPages + saved.openedByPages).intersect(present),
            starters = saved.starters.filter { it in ownIds || it in pinIds }.toSet(),
        )
    }

    /**
     * [pin] without its tab, which is not open here, opening on [page], the page its tab was on, like a pinned tab
     * whose tab closed. Without the page, it opens where it opened before.
     */
    private fun closed(pin: PinnedItem, page: BackupTab?): PinnedItem {
        val withoutTab = pin.copy(tabId = null)
        return when {
            page == null -> withoutTab
            pin.url == null || isSamePage(page.url, pin.url) -> withoutTab.copy(openUrl = null, openTitle = null)
            else -> withoutTab.copy(openUrl = page.url, openTitle = page.title)
        }
    }

    /** The workspace [backup] showed, or its first own one. */
    private fun shownWorkspaceOf(backup: DejavuBackup): String {
        val own = backup.ownWorkspaceIds
        return backup.state.activeWorkspaceId.takeIf { it in own }
            ?: backup.state.workspaces.first { it.device == null }.id
    }
}
