/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.dejavu.home.drawWorkspaceTheme
import org.mozilla.fenix.dejavu.home.rememberGrainBrush
import org.mozilla.fenix.dejavu.ui.popupThemeOf
import org.mozilla.fenix.dejavu.ui.workspacePopupTheme
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme
import org.mozilla.fenix.ext.components

/**
 * The colors that the bars around the tab with [tabId], or the shown tab when `null`, and the menus over it take on:
 * those of its workspace's wallpaper, see [popupThemeOf]; `null` for a private tab or a workspace without a wallpaper.
 */
@Composable
internal fun shownPopupTheme(tabId: String? = null): WorkspaceTheme? {
    val context = LocalContext.current
    val themedTabId by context.components.core.store.observeAsComposableState { state ->
        state.tabOrShown(tabId)?.takeUnless { it.content.private }?.id
    }
    val repository = remember(context) { WorkspaceRepository.get(context) }
    val workspaces by repository.state.collectAsState()
    return workspacePopupTheme(themedTabId?.let(workspaces::workspaceOf))
}

/**
 * Draws [theme] over the background, the way the home screen shows the workspace: as one gradient across the whole
 * window, so each part showing it matches the others. The part becomes solid, since a see-through one would show
 * what is behind it through the theme.
 */
@Composable
internal fun Modifier.workspaceTheme(theme: WorkspaceTheme?): Modifier {
    if (theme == null) return this
    val grain = rememberGrainBrush()
    val root = LocalView.current.rootView
    val base = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
    var origin by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin = it.positionInWindow() }
        .drawBehind {
            val window = Rect(-origin, Size(root.width.toFloat(), root.height.toFloat()))
            drawRect(base)
            drawWorkspaceTheme(theme, next = null, fraction = 0f, grain = grain, area = window)
        }
}
