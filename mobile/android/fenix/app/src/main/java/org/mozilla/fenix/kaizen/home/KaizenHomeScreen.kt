/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.components.appstate.AppAction.SearchAction.SearchStarted
import org.mozilla.fenix.components.components
import org.mozilla.fenix.kaizen.workspaces.WorkspaceRepository
import org.mozilla.fenix.theme.FirefoxTheme

/** Whether the Kaizen workspace home replaces the upstream homepage. Private browsing keeps the upstream one. */
fun isKaizenHomeEnabled(isPrivate: Boolean): Boolean = !isPrivate

/**
 * Sets the Kaizen workspace home as the content of the homepage [ComposeView].
 *
 * @param searchToolbar The upstream home toolbar, shown on top of the workspaces while a search is active.
 * @param onOpenTab Selects the given tab and navigates to the browser.
 * @param onDownloadsClick Opens the downloads screen.
 * @param onFirstFrameDrawn Invoked once the first frame of the home screen is drawn.
 */
fun ComposeView.setKaizenHomeContent(
    searchToolbar: @Composable () -> Unit,
    onOpenTab: (String) -> Unit,
    onDownloadsClick: () -> Unit,
    onFirstFrameDrawn: () -> Unit,
) {
    setContent {
        FirefoxTheme {
            val appStore = components.appStore
            val browserStore = components.core.store
            val repository = remember { WorkspaceRepository.get(context) }
            val workspaceState by repository.state.collectAsState()
            val tabs by browserStore.observeAsComposableState { it.normalTabs }
            val selectedTabId by browserStore.observeAsComposableState { it.selectedTabId }
            val restoreComplete by browserStore.observeAsComposableState { it.restoreComplete }
            val isSearchActive by appStore.observeAsComposableState { it.searchState.isSearchActive }

            LaunchedEffect(tabs, restoreComplete) {
                repository.syncWithTabs(tabs.map { it.id }.toSet(), restoreComplete)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .systemBarsPadding()
                    .imePadding(),
            ) {
                KaizenHome(
                    state = workspaceState,
                    tabs = tabs,
                    selectedTabId = selectedTabId,
                    onWorkspaceSelected = repository::selectWorkspace,
                    onAddWorkspace = repository::addWorkspace,
                    onTabClick = onOpenTab,
                    onNewTabClick = { appStore.dispatch(SearchStarted()) },
                    onDownloadsClick = onDownloadsClick,
                )

                if (isSearchActive) {
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        searchToolbar()
                    }
                }
            }

            LaunchedEffect(Unit) {
                onFirstFrameDrawn()
            }
        }
    }
}
