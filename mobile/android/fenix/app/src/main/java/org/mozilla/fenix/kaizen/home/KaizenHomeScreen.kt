/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.home

import android.content.Context
import android.os.StrictMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.prompt.ShareData
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.components.accounts.FenixFxAEntryPoint
import org.mozilla.fenix.components.appstate.AppAction.SearchAction.SearchStarted
import org.mozilla.fenix.components.components
import org.mozilla.fenix.kaizen.actions.TabAction
import org.mozilla.fenix.kaizen.containers.KaizenContainerStorage
import org.mozilla.fenix.kaizen.settings.KaizenSettings
import org.mozilla.fenix.kaizen.workspaces.PinSource
import org.mozilla.fenix.kaizen.workspaces.PinnedItem
import org.mozilla.fenix.kaizen.workspaces.WorkspaceRepository
import org.mozilla.fenix.theme.FirefoxTheme

/** Whether the Kaizen workspace home replaces the upstream homepage. Private browsing keeps the upstream one. */
fun isKaizenHomeEnabled(isPrivate: Boolean): Boolean = !isPrivate

/**
 * Sets the Kaizen workspace home as the content of the homepage [ComposeView].
 *
 * @param navController Used to open settings, account, downloads and the share sheet.
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
            val repository = remember { fenix.readOnMainThread { WorkspaceRepository.get(context) } }
            val settings = remember { fenix.readOnMainThread { KaizenSettings.get(context) } }
            val containerStorage = remember { KaizenContainerStorage.get(context) }
            val scope = rememberCoroutineScope()
            val snackbarHostState = remember { SnackbarHostState() }
            val interactor = remember {
                DefaultKaizenHomeInteractor(context, fenix, repository, navController, scope, snackbarHostState, onOpenTab)
            }

            val workspaceState by repository.state.collectAsState()
            val pinnedRowActions by settings.pinnedRowActions.collectAsState()
            val unpinnedRowActions by settings.unpinnedRowActions.collectAsState()
            val containerRecords by containerStorage.records.collectAsState()
            val containers = remember(containerRecords) { containerRecords.orEmpty().associateBy { it.contextId } }
            val tabs by fenix.core.store.observeAsComposableState { it.normalTabs }
            val selectedTabId by fenix.core.store.observeAsComposableState { it.selectedTabId }
            val restoreComplete by fenix.core.store.observeAsComposableState { it.restoreComplete }
            val isSearchActive by fenix.appStore.observeAsComposableState { it.searchState.isSearchActive }

            LaunchedEffect(Unit) { containerStorage.load() }

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
                    containers = containers,
                    pinnedRowActions = pinnedRowActions,
                    unpinnedRowActions = unpinnedRowActions,
                    interactor = interactor,
                )

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp),
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

private fun <T> Components.readOnMainThread(block: () -> T): T =
    strictMode.allowViolation(StrictMode::allowThreadDiskReads, block)

@Suppress("TooManyFunctions", "LongParameterList")
private class DefaultKaizenHomeInteractor(
    private val context: Context,
    private val components: Components,
    private val repository: WorkspaceRepository,
    private val navController: NavController,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val openTab: (String) -> Unit,
) : KaizenHomeInteractor {
    private val tabsUseCases = components.useCases.tabsUseCases
    private val store = components.core.store

    override fun onWorkspaceSelected(workspaceId: String) = repository.selectWorkspace(workspaceId)

    override fun onSaveWorkspace(workspaceId: String?, name: String, containerId: String?) {
        if (workspaceId == null) {
            repository.addWorkspace(name, containerId)
        } else {
            repository.updateWorkspace(workspaceId, name, containerId)
        }
    }

    override fun onDeleteWorkspace(workspaceId: String) = repository.deleteWorkspace(workspaceId)

    override fun onTabClick(tabId: String) = openTab(tabId)

    override fun onPinClick(pin: PinnedItem) {
        val liveTabId = pin.tabId?.takeIf { store.state.findTab(it) != null }
        if (liveTabId != null) {
            openTab(liveTabId)
            return
        }
        val url = pin.url ?: return
        val tabId = tabsUseCases.addTab(
            url = url,
            selectTab = true,
            title = pin.title,
            contextId = pin.containerId,
            source = SessionState.Source.Internal.None,
        )
        repository.attachPinned(pin.id, tabId)
        openTab(tabId)
    }

    override fun onTabAction(action: TabAction, targets: ActionTargets) {
        when (action) {
            TabAction.CLOSE -> closeTabs(targets.openTabs.map { it.id })
            TabAction.PIN -> repository.pinTabs(targets.tabs.map { it.toPinSource() })
            TabAction.UNPIN -> repository.unpin(targets.pins.map { it.id }.toSet())
            TabAction.SLEEP -> targets.awakeTabs.forEach { store.dispatch(EngineAction.SuspendEngineSessionAction(it.id)) }
            TabAction.BOOKMARK -> bookmark(targets.links)
            TabAction.SHARE -> share(targets.links)
            TabAction.COPY_LINK -> copyLinks(targets.links)
            TabAction.DUPLICATE -> duplicate(targets)
            TabAction.MOVE_TO_WORKSPACE, TabAction.MOVE_TO_FOLDER, TabAction.NEW_FOLDER -> Unit
        }
    }

    override fun onCreateFolder(workspaceId: String, parentId: String?, name: String, targets: ActionTargets) {
        repository.createFolder(
            workspaceId = workspaceId,
            parentId = parentId,
            name = name,
            itemIds = targets.pins.map { it.id }.toSet(),
            newPins = targets.tabs.map { it.toPinSource() },
        )
    }

    override fun onRenameFolder(folderId: String, name: String) = repository.renameFolder(folderId, name)

    override fun onToggleFolder(folderId: String) = repository.toggleFolder(folderId)

    override fun onMoveToFolder(targets: ActionTargets, folderIds: Set<String>, destinationId: String?) {
        if (targets.tabs.isNotEmpty()) repository.pinTabs(targets.tabs.map { it.toPinSource() }, destinationId)
        val itemIds = targets.pins.map { it.id }.toSet() + folderIds
        if (itemIds.isNotEmpty()) repository.moveToFolder(itemIds, destinationId)
    }

    override fun onUnpackFolder(folderId: String) = repository.unpackFolder(folderId)

    override fun onDeleteFolder(folderId: String) {
        val state = repository.state.value
        val removed = state.descendantIds(folderId)
        val tabIds = state.pins.filter { it.id in removed }.mapNotNull { it.tabId }
        repository.deleteFolder(folderId)
        closeTabs(tabIds)
    }

    override fun onMoveToWorkspace(targets: ActionTargets, folderIds: Set<String>, workspaceId: String) {
        repository.moveToWorkspace(
            tabIds = targets.tabs.map { it.id }.toSet(),
            itemIds = targets.pins.map { it.id }.toSet() + folderIds,
            workspaceId = workspaceId,
        )
    }

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

    private fun closeTabs(tabIds: List<String>) {
        val open = tabIds.filter { store.state.findTab(it) != null }
        if (open.isNotEmpty()) tabsUseCases.removeTabs(open)
    }

    private fun bookmark(links: List<Pair<String, String>>) {
        val valid = links.filter { it.first.isNotBlank() }
        if (valid.isEmpty()) return
        scope.launch {
            valid.forEach { (url, title) -> components.useCases.bookmarksUseCases.addBookmark(url, title.ifBlank { url }) }
            snackbar.showSnackbar(
                context.resources.getQuantityString(R.plurals.kaizen_bookmarked, valid.size, valid.size),
            )
        }
    }

    private fun share(links: List<Pair<String, String>>) {
        val data = links.filter { it.first.isNotBlank() }.map { (url, title) -> ShareData(title = title, url = url, private = false) }
        if (data.isEmpty()) return
        navController.navigate(NavGraphDirections.actionGlobalShareFragment(data = data.toTypedArray()))
    }

    private fun copyLinks(links: List<Pair<String, String>>) {
        val urls = links.map { it.first }.filter { it.isNotBlank() }
        if (urls.isEmpty()) return
        components.clipboardHandler.text = urls.joinToString("\n")
        scope.launch {
            snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.kaizen_links_copied, urls.size, urls.size))
        }
    }

    private fun duplicate(targets: ActionTargets) {
        targets.openTabs.forEach { tabsUseCases.duplicateTab(it, selectNewTab = false) }
        targets.pins.filter { pin -> targets.pinnedTabs.none { it.id == pin.tabId } }.forEach { pin ->
            val url = pin.url ?: return@forEach
            tabsUseCases.addTab(
                url = url,
                selectTab = false,
                title = pin.title,
                contextId = pin.containerId,
                source = SessionState.Source.Internal.None,
            )
        }
    }

    private fun TabSessionState.toPinSource() = PinSource(id, content.url, content.title, contextId)
}
