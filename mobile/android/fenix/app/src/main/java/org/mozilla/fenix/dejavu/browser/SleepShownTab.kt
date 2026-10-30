/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState

/**
 * The tab to show before the tabs in [sleeping] go to sleep, or `null` when the shown tab is not one of them or no other
 * tab can take its place. Firefox opens the page of the shown tab again as soon as it sleeps, so it has to be left first.
 *
 * It is the tab used last that is not in [sleeping] and is private exactly when the shown tab is, so a private tab never
 * leads to a normal one and the other way round. A tab of the workspace of the shown tab comes first, [workspaceOf] says
 * which workspace a tab is in.
 */
internal fun tabToShowInstead(state: BrowserState, sleeping: Set<String>, workspaceOf: (String) -> String?): String? {
    val shown = state.selectedTab?.takeIf { it.id in sleeping } ?: return null
    val candidates = state.tabs
        .filter { it.id !in sleeping && it.content.private == shown.content.private }
        .sortedByDescending { it.lastAccess }
    val workspace = workspaceOf(shown.id)
    return (candidates.firstOrNull { workspaceOf(it.id) == workspace } ?: candidates.firstOrNull())?.id
}

/**
 * Puts the tabs [tabIds] to sleep on the user's request. When the shown tab is one of them, another tab is shown first,
 * see [tabToShowInstead]. Without one, a pinned shown tab is closed, its pin stays and opens the page again, and any
 * other is put to sleep as it is.
 */
internal fun Components.sleepTabs(workspaces: WorkspaceState, tabIds: Set<String>) {
    val store = core.store
    var sleeping = tabIds
    val shown = store.state.selectedTabId
    if (shown != null && shown in tabIds) {
        val instead = tabToShowInstead(store.state, tabIds, workspaces::workspaceOf)
        if (instead != null) {
            useCases.tabsUseCases.selectTab(instead)
        } else if (workspaces.pinOf(shown) != null) {
            useCases.tabsUseCases.removeTab(shown)
            sleeping = tabIds - shown
        }
    }
    sleeping.forEach { store.dispatch(EngineAction.SuspendEngineSessionAction(it)) }
}
