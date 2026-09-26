/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.browser

import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.SessionState
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.kaizen.workspaces.WorkspaceRepository

/** Kaizen's changes to the browser toolbar. */
object KaizenToolbar {
    /** The home button replaces the tab counter; tabs are managed from the home screen. */
    val showTabCounter: Boolean = false
}

/**
 * What happens to a tab the user leaves with Back from its first page. The home screen applies it once it is shown,
 * so the browser screen is gone by then.
 */
object PendingTabLeave {
    /**
     * @property tabId The tab that was left.
     * @property sleep Whether to unload the tab instead of closing it.
     */
    data class Leave(val tabId: String, val sleep: Boolean)

    @Volatile
    private var pending: Leave? = null

    internal fun set(leave: Leave) {
        pending = leave
    }

    /** Returns and forgets the pending leave. */
    fun consume(): Leave? = pending.also { pending = null }
}

/**
 * Handles Back on a tab without history: goes to the home screen and, like Zen, unloads the tab when it is pinned or
 * closes it otherwise. Custom tabs, private tabs and tabs opened by other apps keep Fenix's behavior.
 *
 * @return Whether Back was handled.
 */
fun Fragment.handleKaizenBackPressed(customTabSessionId: String?): Boolean {
    if (customTabSessionId != null) return false
    val tab = requireComponents.core.store.state.selectedTab ?: return false
    if (tab.content.private || tab.source is SessionState.Source.External) return false
    val repository = WorkspaceRepository.peek() ?: return false

    PendingTabLeave.set(PendingTabLeave.Leave(tab.id, sleep = repository.state.value.pinOf(tab.id) != null))
    findNavController().navigate(NavGraphDirections.actionGlobalHome())
    return true
}
