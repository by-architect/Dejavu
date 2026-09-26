/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.action.UndoAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.feature.containers.ContainerMiddleware
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import org.mozilla.fenix.kaizen.containers.KaizenContainerStorage
import org.mozilla.fenix.kaizen.workspaces.WorkspaceRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Middleware Kaizen adds to Fenix's browser store: android-components' container support backed by Kaizen's storage,
 * and [WorkspaceContainerMiddleware]. Also loads Kaizen's data and applies its defaults off the main thread.
 */
fun kaizenBrowserMiddleware(context: Context): List<Middleware<BrowserState, BrowserAction>> {
    CoroutineScope(Dispatchers.IO).launch {
        WorkspaceRepository.get(context)
        KaizenDefaults.applyOnce(context)
    }
    return listOf(
        ContainerMiddleware(context, containerStorage = KaizenContainerStorage.get(context)),
        WorkspaceContainerMiddleware(),
        SilentlyClosedTabsMiddleware(),
    )
}

/**
 * Tabs Kaizen closes on its own, like the old copy of a tab reopened in another container. They are kept out of undo
 * and so never show up in the recently closed tabs.
 */
object SilentlyClosedTabs {
    private val tabIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Marks [tabId], which is about to be closed, as closed by Kaizen. */
    fun add(tabId: String) {
        tabIds.add(tabId)
    }

    internal fun contains(tabId: String) = tabId in tabIds

    internal fun consume(tabId: String) = tabIds.remove(tabId)
}

/**
 * Removes [SilentlyClosedTabs] from the tabs remembered for undo. Tabs left in undo end up in the recently closed tabs,
 * so this has to happen before that list and its storage see them.
 */
internal class SilentlyClosedTabsMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        if (action is UndoAction.AddRecoverableTabs && action.tabs.any { SilentlyClosedTabs.contains(it.state.id) }) {
            next(action.copy(tabs = action.tabs.filterNot { SilentlyClosedTabs.consume(it.state.id) }))
        } else {
            next(action)
        }
    }
}

/**
 * The container the next new tab started by the user opens in, chosen by long-pressing "New Tab" on the home screen.
 * It overrides the workspace's container once, and is dropped when it expires or the search is abandoned.
 */
object NewTabContainerChoice {
    private const val LIFETIME_MS = 10 * 60 * 1000L

    private class Choice(val contextId: String?, val expiresAt: Long)

    @Volatile
    private var choice: Choice? = null

    /** Opens the next new tab in [contextId], or without a container when it is `null`. */
    fun set(contextId: String?) {
        choice = Choice(contextId, SystemClock.elapsedRealtime() + LIFETIME_MS)
    }

    fun clear() {
        choice = null
    }

    /** Returns and forgets the pending choice. The outer `null` means there is no choice. */
    internal fun consume(): Result<String?>? {
        val current = choice ?: return null
        choice = null
        return if (SystemClock.elapsedRealtime() < current.expiresAt) Result.success(current.contextId) else null
    }
}

/**
 * Opens new tabs in the container of the active workspace, like Zen does for workspaces with a default container, or
 * in the container picked with [NewTabContainerChoice]. Only tabs the user starts (typed, new tab, links from other
 * apps) are changed; tabs that already have a container, private tabs, and tabs opened by a page keep their context.
 */
internal class WorkspaceContainerMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        if (action is TabListAction.AddTabAction && action.tab.canJoinContainer()) {
            val picked = if (action.tab.source in userSources) NewTabContainerChoice.consume() else null
            val containerId = if (picked != null) {
                picked.getOrNull()
            } else {
                WorkspaceRepository.peek()?.state?.value?.activeWorkspace?.containerId
            }
            if (containerId != null && store.state.containers.containsKey(containerId)) {
                next(action.copy(tab = action.tab.copy(contextId = containerId)))
                return
            }
        }
        next(action)
    }

    private fun TabSessionState.canJoinContainer(): Boolean =
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
