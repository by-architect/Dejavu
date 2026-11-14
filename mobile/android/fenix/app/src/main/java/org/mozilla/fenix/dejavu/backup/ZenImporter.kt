/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.state.createTab
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.sync.DejavuSync
import org.mozilla.fenix.dejavu.sync.LocalSpaces
import org.mozilla.fenix.dejavu.sync.LocalTab
import org.mozilla.fenix.dejavu.sync.TabOp
import org.mozilla.fenix.dejavu.sync.separatesEssentials
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpapers
import org.mozilla.fenix.ext.components

/**
 * Brings the workspaces of a Zen profile into Dejavu, as [ZenImport] plans them: the containers they need first, then
 * the workspaces with their tabs together on the main thread.
 */
internal class ZenImporter(context: Context) {
    private val appContext: Context = context.applicationContext

    /** Reads the files at [uris], or returns `null` when they hold no session of Zen, see [ZenFiles]. */
    suspend fun read(uris: List<Uri>): ZenProfile? = withContext(Dispatchers.IO) {
        val files = uris.mapNotNull { uri ->
            runCatching {
                checkNotNull(appContext.contentResolver.openInputStream(uri)).use { input ->
                    BackupZip.readLimited(input, ZenFiles.MAX_SIZE)?.let(ZenFiles::read)
                }
            }.getOrNull()
        }
        ZenFiles.profileOf(files)
    }

    /** What bringing [profile] in would bring, with Dejavu's containers as they are now. */
    suspend fun preview(profile: ZenProfile): ZenImport.Summary = plan(profile, includeUnpinned = false).summary

    /**
     * Brings [profile] in, next to the workspaces here or, with [replace], in place of the user's own, with Zen's tabs
     * that are not pinned when [includeUnpinned]. Replacing makes the next sync with Zen start over, see
     * [DejavuSync.replacingWorkspaces], so it does not delete on Zen what was replaced here, and the replaced
     * workspaces lose their wallpapers, like deleted ones. Essentials then go per container when Zen's look like it,
     * unless the user chose.
     */
    suspend fun import(profile: ZenProfile, includeUnpinned: Boolean, replace: Boolean) {
        val storage = DejavuContainerStorage.get(appContext)
        val plan = plan(profile, includeUnpinned)
        plan.containers.forEach { storage.saveContainer(it.contextId, it.name, it.color, it.icon) }
        val containerIds = storage.records.value.orEmpty().filterNot { it.temporary }.map { it.contextId }.toSet()
        val change: suspend () -> Unit = {
            withContext(Dispatchers.Main) { applyPlan(plan, containerIds, includeUnpinned, replace) }
        }
        if (replace) {
            // Starting the next sync over deletes what sync kept, so it is off the main thread.
            withContext(Dispatchers.IO) {
                DejavuSync.replacingWorkspaces(change)
                dropWallpapersOfGoneWorkspaces()
            }
        } else {
            change()
        }
        val local = LocalSpaces(WorkspaceRepository.get(appContext).state.value, storage.records.value.orEmpty())
        local.separatesEssentials()?.let { DejavuSettings.get(appContext).setEssentialsPerContainerFromSync(it) }
    }

    private fun dropWallpapersOfGoneWorkspaces() {
        val workspaceIds = WorkspaceRepository.get(appContext).state.value.workspaces.map { it.id }.toSet()
        val wallpapers = WorkspaceWallpapers.get(appContext)
        wallpapers.wallpapers.value.keys.filterNot(workspaceIds::contains).forEach { wallpapers.set(it, null) }
    }

    private suspend fun plan(profile: ZenProfile, includeUnpinned: Boolean): ZenImport.Plan =
        withContext(Dispatchers.IO) {
            val storage = DejavuContainerStorage.get(appContext)
            storage.load()
            ZenImport.plan(ZenRecords(profile, includeUnpinned), storage.records.value.orEmpty()) { container ->
                appContext.getString(container.label)
            }
        }

    /** Changes the workspaces as [plan] says and opens the tabs it brings, without loading them. */
    private fun applyPlan(plan: ZenImport.Plan, containerIds: Set<String>, includeUnpinned: Boolean, replace: Boolean) {
        val repository = WorkspaceRepository.get(appContext)
        val store = appContext.components.core.store
        val now = System.currentTimeMillis()
        val openTabs = store.state.normalTabs.map { tab ->
            LocalTab(
                id = tab.id,
                url = tab.content.url,
                title = tab.content.title,
                contextId = tab.contextId,
                awake = tab.engineState.engineSession != null,
            )
        }
        val defaultName = appContext.getString(R.string.dejavu_workspace_default_name)
        val result = repository.update { state ->
            val applied =
                ZenImport.applyPlan(plan, state, openTabs, containerIds, includeUnpinned, replace, defaultName, now)
            applied.state to applied
        }
        val opening = result.tabOps.filterIsInstance<TabOp.Open>()
            .filter { store.state.findTab(it.id) == null }
            .distinctBy { it.id }
        if (opening.isEmpty()) return
        opening.forEach { repository.expectTab(it.id) }
        store.dispatch(
            TabListAction.AddMultipleTabsAction(
                opening.map { op ->
                    createTab(
                        url = op.url,
                        id = op.id,
                        title = op.title,
                        contextId = op.contextId,
                        lastAccess = now,
                        createdAt = now,
                    )
                },
            ),
        )
    }
}
