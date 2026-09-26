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

/** Kaizen's changes to the browser toolbar. */
object KaizenToolbar {
    /** The home button replaces the tab counter; tabs are managed from the home screen. */
    val showTabCounter: Boolean = false
}

/**
 * Handles Back on a tab without history: goes to the home screen and leaves the tab as it is. Custom tabs, private
 * tabs and tabs opened by other apps keep Fenix's behavior.
 *
 * @return Whether Back was handled.
 */
fun Fragment.handleKaizenBackPressed(customTabSessionId: String?): Boolean {
    if (customTabSessionId != null) return false
    val tab = requireComponents.core.store.state.selectedTab ?: return false
    if (tab.content.private || tab.source is SessionState.Source.External) return false

    findNavController().navigate(NavGraphDirections.actionGlobalHome())
    return true
}
