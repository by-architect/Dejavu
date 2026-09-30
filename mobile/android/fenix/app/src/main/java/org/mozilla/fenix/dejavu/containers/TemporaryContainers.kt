/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.containers

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContainerAction
import mozilla.components.browser.state.action.RestoreCompleteAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.action.UndoAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.ext.components

/**
 * Temporary containers, like Firefox's Temporary Containers add-on: made on demand for new tabs, named "tmp1", "tmp2"
 * and so on with the lowest free number, and destroyed with their cookies and site data once their last tab is
 * closed.
 */
object TemporaryContainers {
    private const val NAME_PREFIX = "tmp"

    /** Starts a new temporary container and returns its context ID. */
    fun create(store: Store<BrowserState, BrowserAction>, storage: DejavuContainerStorage): String {
        val taken = store.state.containers.values.filter { storage.isTemporary(it.contextId) }.map { it.name }.toSet()
        val number = generateSequence(1) { it + 1 }.first { "$NAME_PREFIX$it" !in taken }
        val contextId = UUID.randomUUID().toString()
        storage.markTemporary(contextId)
        store.dispatch(
            ContainerAction.AddContainerAction(
                ContainerState(
                    contextId = contextId,
                    name = "$NAME_PREFIX$number",
                    color = ContainerColor.WHITE.acColor,
                    icon = ContainerState.Icon.CIRCLE,
                ),
            ),
        )
        return contextId
    }
}

/**
 * Destroys temporary containers once no tab uses them: a while after their last tab is closed, so that the close can
 * still be undone, and at startup for the ones left without tabs.
 */
internal class TemporaryContainerMiddleware(private val context: Context) : Middleware<BrowserState, BrowserAction> {
    private val handler = Handler(Looper.getMainLooper())
    private var store: Store<BrowserState, BrowserAction>? = null
    private val check = Runnable { store?.let(::destroyUnused) }

    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        next(action)
        val removesTabs = action is TabListAction.RemoveTabAction ||
            action is TabListAction.RemoveTabsAction ||
            action is TabListAction.RemoveAllTabsAction ||
            action == TabListAction.RemoveAllNormalTabsAction ||
            action == TabListAction.RemoveAllPrivateTabsAction ||
            action is UndoAction.ClearRecoverableTabs ||
            action == RestoreCompleteAction
        if (removesTabs) {
            this.store = store
            handler.removeCallbacks(check)
            handler.postDelayed(check, GRACE_MS)
        }
    }

    private fun destroyUnused(store: Store<BrowserState, BrowserAction>) {
        val storage = DejavuContainerStorage.get(context)
        val state = store.state
        val used = (state.tabs + state.customTabs).mapNotNull { it.contextId }.toSet() +
            state.undoHistory.tabs.mapNotNull { it.state.contextId }
        state.containers.keys.filter { storage.isTemporary(it) && it !in used }.forEach { contextId ->
            context.components.core.geckoRuntime.storageController.clearDataForSessionContext(contextId)
            store.dispatch(ContainerAction.RemoveContainerAction(contextId))
            WorkspaceRepository.peek()?.replaceContainer(contextId, null)
            storage.forgetTemporary(contextId)
        }
    }

    private companion object {
        /** Longer than the time a closed tab can be brought back with undo. */
        const val GRACE_MS = 20_000L
    }
}
