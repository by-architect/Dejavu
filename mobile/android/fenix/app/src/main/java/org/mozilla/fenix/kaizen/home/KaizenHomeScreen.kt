/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.home

import android.os.StrictMode
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
import androidx.navigation.NavController
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.components.accounts.FenixFxAEntryPoint
import org.mozilla.fenix.components.appstate.AppAction.SearchAction.SearchStarted
import org.mozilla.fenix.components.components
import org.mozilla.fenix.kaizen.workspaces.PinnedTab
import org.mozilla.fenix.kaizen.workspaces.WorkspaceRepository
import org.mozilla.fenix.theme.FirefoxTheme

/** Whether the Kaizen workspace home replaces the upstream homepage. Private browsing keeps the upstream one. */
fun isKaizenHomeEnabled(isPrivate: Boolean): Boolean = !isPrivate

/**
 * Sets the Kaizen workspace home as the content of the homepage [ComposeView].
 *
 * @param navController Used to open settings, account and downloads.
 * @param searchToolbar The upstream home toolbar, shown on top of the workspaces while a search is active.
 * @param onOpenTab Selects the given tab and navigates to the browser.
 * @param onFirstFrameDrawn Invoked once the first frame of the home screen is drawn.
 */
fun ComposeView.setKaizenHomeContent(
    navController: NavController,
    searchToolbar: @Composable () -> Unit,
    onOpenTab: (String) -> Unit,
    onFirstFrameDrawn: () -> Unit,
) {
    setContent {
        FirefoxTheme {
            val fenix = components
            val repository = remember {
                fenix.strictMode.allowViolation(StrictMode::allowThreadDiskReads) { WorkspaceRepository.get(context) }
            }
            val interactor = remember { DefaultKaizenHomeInteractor(fenix, repository, navController, onOpenTab) }
            val workspaceState by repository.state.collectAsState()
            val tabs by fenix.core.store.observeAsComposableState { it.normalTabs }
            val selectedTabId by fenix.core.store.observeAsComposableState { it.selectedTabId }
            val restoreComplete by fenix.core.store.observeAsComposableState { it.restoreComplete }
            val isSearchActive by fenix.appStore.observeAsComposableState { it.searchState.isSearchActive }

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
                    interactor = interactor,
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

private class DefaultKaizenHomeInteractor(
    private val components: Components,
    private val repository: WorkspaceRepository,
    private val navController: NavController,
    private val openTab: (String) -> Unit,
) : KaizenHomeInteractor {
    private val tabsUseCases = components.useCases.tabsUseCases

    override fun onWorkspaceSelected(workspaceId: String) = repository.selectWorkspace(workspaceId)

    override fun onAddWorkspace(name: String) = repository.addWorkspace(name)

    override fun onTabClick(tabId: String) = openTab(tabId)

    override fun onTabClose(tabId: String) {
        tabsUseCases.removeTab(tabId)
    }

    override fun onTabPin(tab: TabSessionState) = repository.pinTab(tab.id, tab.content.url, tab.content.title)

    override fun onPinnedClick(pinned: PinnedTab) {
        val tabId = tabsUseCases.addTab(url = pinned.url, selectTab = true, title = pinned.title)
        repository.attachPinned(pinned.id, tabId)
        openTab(tabId)
    }

    override fun onUnpin(pinnedId: String) = repository.unpin(pinnedId)

    override fun onSearchClick() {
        components.appStore.dispatch(SearchStarted())
    }

    override fun onAccountClick() {
        val directions = if (components.backgroundServices.accountManager.authenticatedAccount() != null) {
            NavGraphDirections.actionGlobalAccountSettingsFragment()
        } else {
            NavGraphDirections.actionGlobalTurnOnSync(entrypoint = FenixFxAEntryPoint.HomeMenu)
        }
        navController.navigate(directions)
    }

    override fun onSettingsClick() {
        navController.navigate(NavGraphDirections.actionGlobalSettingsFragment())
    }

    override fun onDownloadsClick() {
        navController.navigate(NavGraphDirections.actionGlobalDownloadsFragment())
    }
}
