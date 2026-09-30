/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.kaizen.home.drawWorkspaceTheme
import org.mozilla.fenix.kaizen.home.rememberGrainBrush
import org.mozilla.fenix.kaizen.workspaces.WorkspaceRepository
import org.mozilla.fenix.kaizen.workspaces.WorkspaceTheme

/** The theme of the workspace of the shown tab, or `null` for a private tab or a workspace without one. */
@Composable
internal fun shownWorkspaceTheme(): WorkspaceTheme? {
    val context = LocalContext.current
    val tabId by context.components.core.store.observeAsComposableState { state ->
        state.selectedTab?.takeUnless { it.content.private }?.id
    }
    val repository = remember(context) { WorkspaceRepository.get(context) }
    val workspaces by repository.state.collectAsState()
    val workspaceId = tabId?.let { workspaces.workspaceOf(it) } ?: return null
    return workspaces.workspaces.firstOrNull { it.id == workspaceId }?.theme
}

/** Draws [theme] over the background, the way the home screen shows the workspace. */
@Composable
internal fun Modifier.workspaceTheme(theme: WorkspaceTheme?): Modifier {
    if (theme == null) return this
    val grain = rememberGrainBrush()
    return drawBehind { drawWorkspaceTheme(theme, next = null, fraction = 0f, grain = grain) }
}
