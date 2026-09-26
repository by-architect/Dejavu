/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.containers

import mozilla.components.browser.state.action.ContainerAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.utils.ABOUT_HOME_URL
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.kaizen.workspaces.WorkspaceRepository

/** What happens to the tabs of a container that is deleted. */
sealed interface ContainerRemoval {
    /** Close its tabs, pinned tabs included. */
    data object CloseTabs : ContainerRemoval

    /** Reopen its tabs in [contextId], or without a container when it is `null`. */
    data class MoveTabs(val contextId: String?) : ContainerRemoval
}

/**
 * Deletes a container: handles its tabs as the user chose, points workspaces and pinned tabs away from it, clears its
 * cookies and site data, and removes it from storage.
 */
class ContainerRemover(
    private val components: Components,
    private val repository: WorkspaceRepository?,
) {
    private val store = components.core.store
    private val tabsUseCases = components.useCases.tabsUseCases

    fun remove(record: ContainerRecord, removal: ContainerRemoval) {
        val tabs = store.state.tabs.filter { it.contextId == record.contextId }
        when (removal) {
            ContainerRemoval.CloseTabs -> {
                repository?.removePinsOf(record.contextId, tabs.map { it.id }.toSet())
                repository?.replaceContainer(record.contextId, null)
                if (tabs.isNotEmpty()) {
                    // Selecting an about:home tab would leave the current screen, see AboutHomeBinding.
                    val aboutHomeTabIds = store.state.tabs.filter { it.content.url == ABOUT_HOME_URL }.map { it.id }.toSet()
                    tabsUseCases.removeTabs(tabs.map { it.id }, aboutHomeTabIds)
                }
            }
            is ContainerRemoval.MoveTabs -> {
                val selectedTabId = store.state.selectedTabId
                tabs.forEach { reopen(it, removal.contextId, selected = it.id == selectedTabId) }
                repository?.replaceContainer(record.contextId, removal.contextId)
            }
        }
        components.core.geckoRuntime.storageController.clearDataForSessionContext(record.contextId)
        store.dispatch(ContainerAction.RemoveContainerAction(record.contextId))
    }

    /** A tab cannot change its context, so it is reopened next to itself in [contextId] and the old one is closed. */
    private fun reopen(tab: TabSessionState, contextId: String?, selected: Boolean) {
        val newTabId = tabsUseCases.addTab(
            url = tab.content.url,
            selectTab = selected,
            startLoading = selected,
            title = tab.content.title,
            contextId = contextId,
            source = SessionState.Source.Internal.None,
        )
        store.dispatch(TabListAction.MoveTabsAction(listOf(newTabId), tab.id, placeAfter = true))
        repository?.replaceTab(tab.id, newTabId)
        tabsUseCases.removeTab(tab.id)
    }
}
