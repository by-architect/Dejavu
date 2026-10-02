/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.action.UndoAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.feature.containers.ContainerMiddleware
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import org.mozilla.fenix.dejavu.browser.SplitViewMiddleware
import org.mozilla.fenix.dejavu.containers.ContainerPick
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.containers.TemporaryContainerMiddleware
import org.mozilla.fenix.dejavu.containers.TemporaryContainers
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.sync.DejavuSync
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository

/**
 * Middleware Dejavu adds to Fenix's browser store: android-components' container support backed by Dejavu's storage,
 * and [WorkspaceContainerMiddleware]. Also loads Dejavu's data and starts syncing workspaces with Zen, off the main
 * thread.
 */
fun dejavuBrowserMiddleware(context: Context): List<Middleware<BrowserState, BrowserAction>> {
    CoroutineScope(Dispatchers.IO).launch {
        WorkspaceRepository.get(context)
        DejavuSettings.get(context)
        DejavuContainerStorage.get(context).load()
        DejavuSync.install(context)
    }
    return listOf(
        ContainerMiddleware(context, containerStorage = DejavuContainerStorage.get(context)),
        WorkspaceContainerMiddleware(context),
        TemporaryContainerMiddleware(context),
        SilentlyClosedTabsMiddleware(),
        ClosingPinnedTabsMiddleware(),
        SplitViewMiddleware(),
    )
}

/**
 * Remembers the page a pinned tab is on as its tab closes, however it closes, so the pin opens there again instead of
 * on its pinned address, the way a sleeping tab wakes up where it was.
 */
internal class ClosingPinnedTabsMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        val closing = when (action) {
            is TabListAction.RemoveTabAction -> listOf(action.tabId)
            is TabListAction.RemoveTabsAction -> action.tabIds
            is TabListAction.RemoveAllNormalTabsAction -> store.state.tabs.filterNot { it.content.private }.map { it.id }
            is TabListAction.RemoveAllTabsAction -> store.state.tabs.map { it.id }
            else -> emptyList()
        }
        val repository = WorkspaceRepository.peek()
        if (closing.isNotEmpty() && repository != null) {
            val pinnedTabIds = repository.state.value.pins.mapNotNullTo(HashSet()) { it.tabId }
            val pages = closing.filter { it in pinnedTabIds }.mapNotNull { id ->
                store.state.findTab(id)?.let { tab -> id to (tab.content.url to tab.content.title) }
            }.toMap()
            if (pages.isNotEmpty()) repository.rememberClosingPages(pages)
        }
        next(action)
    }
}

/**
 * Tabs Dejavu closes on its own, like the old copy of a tab reopened in another container. They are kept out of undo
 * and so never show up in the recently closed tabs.
 */
object SilentlyClosedTabs {
    private val tabIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Marks [tabId], which is about to be closed, as closed by Dejavu. */
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

    /** A container for the next new tab, or `null` with [private] for a private tab outside any container. */
    internal class Choice(val pick: ContainerPick?, val private: Boolean, val expiresAt: Long)

    @Volatile
    private var choice: Choice? = null

    fun set(pick: ContainerPick) {
        choice = Choice(pick, private = false, expiresAt = SystemClock.elapsedRealtime() + LIFETIME_MS)
    }

    /** Opens the next new tab as a private tab. */
    fun setPrivate() {
        choice = Choice(null, private = true, expiresAt = SystemClock.elapsedRealtime() + LIFETIME_MS)
    }

    fun clear() {
        choice = null
    }

    /** Returns and forgets the pending choice, if it has not expired. */
    internal fun consume(): Choice? {
        val current = choice ?: return null
        choice = null
        return current.takeIf { SystemClock.elapsedRealtime() < current.expiresAt }
    }
}

/**
 * Opens new tabs in the container of their workspace, like Zen does for workspaces with a default container, in the
 * container picked with [NewTabContainerChoice], or in a new temporary container. Links from other apps open in the
 * workspace and container chosen in settings. Only tabs the user starts (typed, new tab, links from other apps) are
 * changed; tabs that already have a container, private tabs, and tabs opened by a page keep their context.
 */
internal class WorkspaceContainerMiddleware(private val context: Context) : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        if (action is TabListAction.AddTabAction) {
            val tab = action.tab
            val parentContextId = tab.parentId?.let { store.state.findTab(it)?.contextId }
            when {
                // Tabs opened by a page load in the page's container; record it so the UI shows it.
                parentContextId != null && tab.contextId == null && !tab.content.private -> {
                    next(action.copy(tab = tab.copy(contextId = parentContextId)))
                    return
                }
                tab.canJoinContainer() -> {
                    val choice = if (tab.source in userSources) NewTabContainerChoice.consume() else null
                    if (choice?.private == true) {
                        next(action.copy(tab = tab.copy(content = tab.content.copy(private = true))))
                        return
                    }
                    val containerId = containerFor(store, tab, choice?.pick)
                    if (containerId != null && store.state.containers.containsKey(containerId)) {
                        next(action.copy(tab = tab.copy(contextId = containerId)))
                        return
                    }
                }
            }
        }
        next(action)
    }

    private fun containerFor(
        store: Store<BrowserState, BrowserAction>,
        tab: TabSessionState,
        chosen: ContainerPick?,
    ): String? {
        val repository = WorkspaceRepository.peek()
        val settings = DejavuSettings.peek()
        val workspaces = repository?.state?.value
        var workspace = workspaces?.activeWorkspace
        val pick = if (tab.source is SessionState.Source.External) {
            val target = settings?.externalLinkWorkspaceId?.value?.let { id -> workspaces?.workspaces?.firstOrNull { it.id == id } }
            if (target != null) {
                workspace = target
                repository?.assignTab(tab.id, target.id)
                repository?.selectWorkspace(target.id)
            }
            settings?.externalLinkContainer?.value
        } else {
            chosen
        }
        return when (pick) {
            ContainerPick.NoContainer -> null
            ContainerPick.Temporary -> TemporaryContainers.create(store, DejavuContainerStorage.get(context))
            is ContainerPick.Container -> pick.contextId
            null -> workspace?.containerId ?: if (settings?.temporaryContainersByDefault?.value == true) {
                TemporaryContainers.create(store, DejavuContainerStorage.get(context))
            } else {
                null
            }
        }
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
