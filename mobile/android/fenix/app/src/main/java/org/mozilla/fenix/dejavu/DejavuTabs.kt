/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu

import android.content.Context
import mozilla.components.browser.state.state.recover.RecoverableTab
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository

/**
 * Tells which tabs to restore even when Firefox would close them for being unused for too long: Dejavu only closes
 * unpinned tabs, so pinned tabs and essentials stay. Reads the workspaces on first use, so call it off the main thread.
 */
fun dejavuKeepsTab(context: Context): (RecoverableTab) -> Boolean {
    val pinnedTabIds by lazy { WorkspaceRepository.get(context).state.value.pins.mapNotNullTo(HashSet()) { it.tabId } }
    return { tab -> tab.state.id in pinnedTabIds }
}
