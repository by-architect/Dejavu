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
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.browser.tabstrip.TabSidebarLayout
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
 * The colors the tab sidebar and the page corners beside it take on: [shownPopupTheme], or when it is `null` and
 * [withOwnGradient], the gradient of the shown tab's workspace, as the home screen shows it.
 */
@Composable
internal fun shownSidebarTheme(withOwnGradient: Boolean = true): WorkspaceTheme? {
    val popup = shownPopupTheme()
    val context = LocalContext.current
    val tabId by context.components.core.store.observeAsComposableState { state ->
        state.selectedTab?.takeUnless { it.content.private }?.id
    }
    val workspaces by remember(context) { WorkspaceRepository.get(context) }.state.collectAsState()
    if (popup != null || !withOwnGradient) return popup
    val workspaceId = tabId?.let(workspaces::workspaceOf)
    return workspaces.workspaces.firstOrNull { it.id == workspaceId }?.theme
}

/** [shownSidebarTheme] while the tab sidebar is docked, so the menus opened from it match, else [shownPopupTheme]. */
@Composable
internal fun shownMenuTheme(): WorkspaceTheme? =
    shownSidebarTheme(withOwnGradient = TabSidebarLayout.occupiedWidth > 0.dp)

/**
 * Draws [theme] over the background, the way the home screen shows the workspace: as one gradient across the whole
 * window, so each part showing it matches the others. The part becomes solid, since a see-through one would show
 * what is behind it through the theme, unless [baseAlpha] is below 1.
 */
@Composable
internal fun Modifier.workspaceTheme(theme: WorkspaceTheme?, baseAlpha: Float = 1f): Modifier {
    if (theme == null) return this
    val grain = rememberGrainBrush()
    val root = LocalView.current.rootView
    val base = MaterialTheme.colorScheme.surface.copy(alpha = baseAlpha)
    var origin by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin = it.positionInWindow() }
        .drawBehind {
            val window = Rect(-origin, Size(root.width.toFloat(), root.height.toFloat()))
            drawRect(base)
            drawWorkspaceTheme(theme, next = null, fraction = 0f, grain = grain, area = window)
        }
}
