/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.feature.containers.ContainerMiddleware
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import org.mozilla.fenix.kaizen.containers.KaizenContainerStorage
import org.mozilla.fenix.kaizen.workspaces.WorkspaceRepository

/**
 * Middleware Kaizen adds to Fenix's browser store: android-components' container support backed by Kaizen's storage,
 * and [WorkspaceContainerMiddleware].
 */
fun kaizenBrowserMiddleware(context: Context): List<Middleware<BrowserState, BrowserAction>> {
    CoroutineScope(Dispatchers.IO).launch { WorkspaceRepository.get(context) }
    return listOf(
        ContainerMiddleware(context, containerStorage = KaizenContainerStorage.get(context)),
        WorkspaceContainerMiddleware(),
    )
}

/**
 * Opens new tabs in the container of the active workspace, like Zen does for workspaces with a default container. Only
 * tabs the user starts (typed, new tab, links from other apps) are changed; tabs that already have a container, private
 * tabs, and tabs opened by a page keep their own context.
 */
internal class WorkspaceContainerMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        if (action is TabListAction.AddTabAction && action.tab.canJoinWorkspaceContainer()) {
            val containerId = WorkspaceRepository.peek()?.state?.value?.activeWorkspace?.containerId
            if (containerId != null && store.state.containers.containsKey(containerId)) {
                next(action.copy(tab = action.tab.copy(contextId = containerId)))
                return
            }
        }
        next(action)
    }

    private fun TabSessionState.canJoinWorkspaceContainer(): Boolean =
        contextId == null &&
            !content.private &&
            parentId == null &&
            engineState.engineSession == null &&
            (source is SessionState.Source.External || source in userSources)

    private val userSources = setOf(
        SessionState.Source.Internal.UserEntered,
        SessionState.Source.Internal.NewTab,
        SessionState.Source.Internal.HomeScreen,
    )
}
