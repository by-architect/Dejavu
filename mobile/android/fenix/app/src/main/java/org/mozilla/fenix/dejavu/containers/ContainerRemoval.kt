/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.containers

import mozilla.components.browser.state.action.ContainerAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.utils.ABOUT_HOME_URL
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.dejavu.SilentlyClosedTabs
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository

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
                tabs.forEach {
                    val selected = it.id == selectedTabId
                    components.reopenInContainer(it, removal.contextId, repository, selected = selected, load = selected)
                }
                repository?.replaceContainer(record.contextId, removal.contextId)
            }
        }
        components.core.geckoRuntime.storageController.clearDataForSessionContext(record.contextId)
        store.dispatch(ContainerAction.RemoveContainerAction(record.contextId))
    }
}

/**
 * Moves [tab] to container [contextId], or out of any container when it is `null`. A tab cannot change its context,
 * so it is reopened next to itself in [contextId] and the old one is closed; its back and forward history is lost.
 *
 * @param selected Whether [tab] is the selected tab; the new tab then takes its place.
 * @param load Whether the new tab loads its page right away rather than when it is first shown.
 * @return The id of the new tab.
 */
fun Components.reopenInContainer(
    tab: TabSessionState,
    contextId: String?,
    repository: WorkspaceRepository?,
    selected: Boolean,
    load: Boolean,
): String {
    val tabsUseCases = useCases.tabsUseCases
    val newTabId = tabsUseCases.addTab(
        url = tab.content.url,
        selectTab = selected,
        startLoading = load,
        title = tab.content.title,
        contextId = contextId,
        source = SessionState.Source.Internal.None,
    )
    core.store.dispatch(TabListAction.MoveTabsAction(listOf(newTabId), tab.id, placeAfter = true))
    repository?.replaceTab(tab.id, newTabId)
    SilentlyClosedTabs.add(tab.id)
    tabsUseCases.removeTab(tab.id)
    return newTabId
}
