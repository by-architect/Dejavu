/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import android.content.Context
import android.os.StrictMode
import java.util.UUID
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.selector.privateTabs
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.browser.browsingmode.BrowsingMode
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.components.accounts.FenixFxAEntryPoint
import org.mozilla.fenix.components.appstate.AppAction.SearchAction.SearchStarted
import org.mozilla.fenix.components.components
import org.mozilla.fenix.components.menu.MenuAccessPoint
import org.mozilla.fenix.dejavu.NewTabContainerChoice
import org.mozilla.fenix.dejavu.actions.ActionPlace
import org.mozilla.fenix.dejavu.actions.CustomAction
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.browser.DejavuSearchOverlay
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerPick
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.features.FeatureTour
import org.mozilla.fenix.dejavu.features.FeatureTourDialog
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.settings.resolveRowActions
import org.mozilla.fenix.dejavu.settings.resolveSelectionActions
import org.mozilla.fenix.dejavu.sync.DejavuSync
import org.mozilla.fenix.dejavu.sync.SyncSourceDialog
import org.mozilla.fenix.dejavu.ui.LocalPopupTheme
import org.mozilla.fenix.dejavu.workspaces.PinPlacement
import org.mozilla.fenix.dejavu.workspaces.PinSource
import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Whether the Dejavu workspace home replaces the upstream homepage. It always does: private tabs appear on it as a
 * private workspace, and it switches the app back to normal browsing when it shows.
 */
@Suppress("UNUSED_PARAMETER")
fun isDejavuHomeEnabled(isPrivate: Boolean): Boolean = true

/**
 * Sets the Dejavu workspace home as the content of the homepage [ComposeView].
 *
 * @param navController Used to open settings, account, downloads and the share sheet.
 * @param searchToolbar The upstream home toolbar, shown on top of the workspaces while a search is active.
 * @param onOpenTab Selects the given tab and navigates to the browser.
 * @param onFirstFrameDrawn Invoked once the first frame of the home screen is drawn.
 */
fun ComposeView.setDejavuHomeContent(
    navController: NavController,
    searchToolbar: @Composable () -> Unit,
    onOpenTab: (String) -> Unit,
    onFirstFrameDrawn: () -> Unit,
) {
    setContent {
        FirefoxTheme {
            val fenix = components
            val repository = remember { fenix.readOnMainThread { WorkspaceRepository.get(context) } }
            val settings = remember { fenix.readOnMainThread { DejavuSettings.get(context) } }
            val containerStorage = remember { DejavuContainerStorage.get(context) }
            val scope = rememberCoroutineScope()
            val snackbarHostState = remember { SnackbarHostState() }
            val interactor = remember {
                DefaultDejavuHomeInteractor(
                    context = context,
                    components = fenix,
                    repository = repository,
                    settings = settings,
                    containerStorage = containerStorage,
                    navController = navController,
                    scope = scope,
                    snackbar = snackbarHostState,
                    openTab = onOpenTab,
                )
            }

            val workspaceState by repository.state.collectAsState()
            val customActions by settings.customActions.collectAsState()
            val pinnedKeys by settings.pinnedRowKeys.collectAsState()
            val unpinnedKeys by settings.unpinnedRowKeys.collectAsState()
            val folderKeys by settings.folderRowKeys.collectAsState()
            val hiddenSelectionKeys by settings.hiddenSelectionKeys.collectAsState()
            val essentialsPerContainer by settings.essentialsPerContainer.collectAsState()
            val containerRecords by containerStorage.records.collectAsState()
            val containers = remember(containerRecords) { containerRecords.orEmpty().associateBy { it.contextId } }
            val tabs by fenix.core.store.observeAsComposableState { it.normalTabs }
            val allPrivateTabs by fenix.core.store.observeAsComposableState { it.privateTabs }
            val isPrivateLocked by fenix.appStore.observeAsComposableState { it.isPrivateScreenLocked }
            val selectedTabId by fenix.core.store.observeAsComposableState { it.selectedTabId }
            val restoreComplete by fenix.core.store.observeAsComposableState { it.restoreComplete }
            val isSearchActive by fenix.appStore.observeAsComposableState { it.searchState.isSearchActive }

            LaunchedEffect(Unit) {
                containerStorage.load()
            }

            // Coming home from a private tab leaves the app in private browsing; the home itself is never private.
            LaunchedEffect(Unit) {
                val activity = context as? HomeActivity ?: return@LaunchedEffect
                if (activity.browsingModeManager.mode.isPrivate) activity.browsingModeManager.mode = BrowsingMode.Normal
            }

            LaunchedEffect(isSearchActive) {
                if (!isSearchActive) {
                    delay(SEARCH_ABANDON_GRACE_MS)
                    NewTabContainerChoice.clear()
                }
            }

            LaunchedEffect(tabs, restoreComplete) {
                repository.syncWithTabs(tabs.map { it.id }.toSet(), restoreComplete)
                repository.refreshPinTitles(tabs.associate { it.id to (it.content.url to it.content.title) })
            }

            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                DejavuHome(
                    state = workspaceState,
                    tabs = tabs,
                    privateTabs = if (isPrivateLocked) emptyList() else allPrivateTabs,
                    selectedTabId = selectedTabId,
                    containers = containers,
                    pinnedRowActions = resolveRowActions(pinnedKeys, ActionPlace.PINNED_ROWS, customActions),
                    unpinnedRowActions = resolveRowActions(unpinnedKeys, ActionPlace.UNPINNED_ROWS, customActions),
                    folderRowActions = resolveRowActions(folderKeys, ActionPlace.FOLDER_ROWS, customActions),
                    selectionActions = resolveSelectionActions(hiddenSelectionKeys, customActions),
                    essentialsPerContainer = essentialsPerContainer,
                    interactor = interactor,
                )

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .systemBarsPadding()
                        .imePadding()
                        .padding(bottom = 64.dp),
                )

                if (isSearchActive) {
                    Box(modifier = Modifier.systemBarsPadding().imePadding()) {
                        DejavuSearchOverlay(
                            fromTop = !fenix.settings.shouldUseBottomToolbar ||
                                fenix.appStore.state.searchState.sourceTabId == null,
                            content = searchToolbar,
                        )
                    }
                }
            }

            var showTour by remember { mutableStateOf(FeatureTour.isDue(settings)) }
            if (showTour) {
                CompositionLocalProvider(LocalPopupTheme provides workspaceState.activeWorkspace?.theme) {
                    FeatureTourDialog(
                        onDismiss = {
                            FeatureTour.markSeen(settings)
                            showTour = false
                        },
                    )
                }
            }

            // Once Dejavu found what the account syncs from Firefox and Zen, it asks which one workspaces follow.
            val syncStatus by DejavuSync.status.collectAsState()
            if (!showTour && syncStatus.askSource) {
                CompositionLocalProvider(LocalPopupTheme provides workspaceState.activeWorkspace?.theme) {
                    SyncSourceDialog(
                        status = syncStatus,
                        onChoose = { DejavuSync.chooseSource(context, it) },
                        onDismiss = { DejavuSync.putOffSourceChoice(context) },
                    )
                }
            }

            LaunchedEffect(Unit) {
                onFirstFrameDrawn()
            }
        }
    }
}

/**
 * How long a container picked for a new tab survives after the search closes. Long enough for the tab created by the
 * search to pick it up, short enough that an abandoned search does not leak it into a later one.
 */
private const val SEARCH_ABANDON_GRACE_MS = 1500L

private fun <T> Components.readOnMainThread(block: () -> T): T =
    strictMode.allowViolation(StrictMode::allowThreadDiskReads, block)

@Suppress("TooManyFunctions", "LongParameterList")
private class DefaultDejavuHomeInteractor(
    private val context: Context,
    private val components: Components,
    private val repository: WorkspaceRepository,
    private val settings: DejavuSettings,
    private val navController: NavController,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val openTab: (String) -> Unit,
    private val containerStorage: DejavuContainerStorage,
    private val runner: TabActionRunner = TabActionRunner(
        context = context,
        components = components,
        repository = repository,
        settings = settings,
        containerStorage = containerStorage,
        navController = navController,
        scope = scope,
        notify = { message -> scope.launch { snackbar.showSnackbar(message) } },
        openTab = openTab,
    ),
) : DejavuHomeInteractor {
    private val tabsUseCases = components.useCases.tabsUseCases
    private val store = components.core.store

    override fun onWorkspaceSelected(workspaceId: String) = repository.selectWorkspace(workspaceId)

    override fun onSaveWorkspace(
        workspaceId: String?,
        name: String,
        containerId: String?,
        icon: String?,
        theme: WorkspaceTheme?,
    ) {
        if (workspaceId == null) {
            repository.addWorkspace(name, containerId, icon, theme)
        } else {
            repository.updateWorkspace(workspaceId, name, containerId, icon, theme)
        }
    }

    override fun onCreateContainer(name: String, color: ContainerColor, icon: ContainerState.Icon): String {
        val contextId = UUID.randomUUID().toString()
        scope.launch { containerStorage.saveContainer(contextId, name, color, icon) }
        return contextId
    }

    override fun onDeleteWorkspace(workspaceId: String) = repository.deleteWorkspace(workspaceId)

    override fun onMoveWorkspace(workspaceId: String, index: Int) = repository.moveWorkspace(workspaceId, index)

    override fun onTabClick(tabId: String) = openTab(tabId)

    override fun onPinClick(pin: PinnedItem) {
        val liveTabId = pin.tabId?.takeIf { store.state.findTab(it) != null }
        if (liveTabId != null) {
            openTab(liveTabId)
            return
        }
        // A pin whose tab closed opens where that tab was, like a sleeping tab wakes up.
        val url = pin.pageUrl.takeIf { it.isNotEmpty() } ?: return
        val tabId = tabsUseCases.addTab(
            url = url,
            selectTab = true,
            title = pin.openTitle ?: pin.title,
            contextId = pin.containerId,
            source = SessionState.Source.Internal.None,
        )
        repository.attachPinned(pin.id, tabId)
        openTab(tabId)
    }

    override fun onTabAction(action: TabAction, targets: ActionTargets, workspaceId: String) =
        runner.run(action, targets, workspaceId)

    override fun onCustomAction(action: CustomAction, targets: ActionTargets) = runner.runCustom(action, targets)

    override fun onCreateFolder(workspaceId: String, parentId: String?, name: String, targets: ActionTargets) =
        runner.onCreateFolder(workspaceId, parentId, name, targets)

    override fun onRenameFolder(folderId: String, name: String) = runner.onRenameFolder(folderId, name)

    override fun onRenameTab(pinId: String?, tabId: String?, name: String) = runner.onRenameTab(pinId, tabId, name)

    override fun onMoveToFolder(workspaceId: String, targets: ActionTargets, folderId: String?) =
        runner.onMoveToFolder(workspaceId, targets, folderId)

    override fun onDeleteItems(targets: ActionTargets) = runner.onDeleteItems(targets)

    override fun onMoveToWorkspace(targets: ActionTargets, workspaceId: String) =
        runner.onMoveToWorkspace(targets, workspaceId)

    override fun onChangeContainer(targets: ActionTargets, pick: ContainerPick) = runner.onChangeContainer(targets, pick)

    override fun onManageContainers() = runner.onManageContainers()

    override fun onDrop(workspaceId: String, selection: Selection, target: DropTarget) {
        val state = repository.state.value
        val targets = ActionTargets.of(state, store.state.normalTabs, selection)
        val placement = when (target) {
            is DropTarget.IntoFolder -> PinPlacement.Into(target.folderId)
            is DropTarget.NextToPin -> PinPlacement.Next(target.pinId, target.after)
            is DropTarget.PinnedEdge -> PinPlacement.Edge(target.atEnd)
            DropTarget.Essentials -> {
                runner.addToEssentials(targets)
                return
            }
            is DropTarget.NextToTab, DropTarget.UnpinnedStart -> null
        }
        if (placement != null) {
            repository.placePins(workspaceId, targets.itemIds, targets.tabs.map { it.toPinSource() }, placement)
            return
        }
        if (targets.folders.isNotEmpty()) return

        // Unpinning: open pinned tabs become normal tabs, closed ones are reopened without loading.
        val moving = targets.tabs.map { it.id } + targets.pinnedTabs.map { it.id } + runner.reopenClosed(targets.pins)
        repository.unpin(targets.pins.map { it.id }.toSet())
        repository.moveToWorkspace(tabIds = moving.toSet(), itemIds = emptySet(), workspaceId = workspaceId)
        val others = store.state.normalTabs.filter {
            it.id !in moving && state.workspaceOf(it.id) == workspaceId && state.pinOf(it.id) == null
        }
        val (anchor, after) = when (target) {
            is DropTarget.NextToTab -> target.tabId to target.after
            else -> others.firstOrNull()?.id to false
        }
        if (anchor != null && moving.isNotEmpty()) {
            store.dispatch(TabListAction.MoveTabsAction(moving, anchor, after))
        }
    }

    override fun onMoveEssential(pinId: String, index: Int) =
        repository.moveEssential(pinId, index, settings.essentialsPerContainer.value)

    override fun onClearUnpinned(workspaceId: String) {
        val state = repository.state.value
        val tabIds = store.state.normalTabs
            .filter { state.workspaceOf(it.id) == workspaceId && state.pinOf(it.id) == null }
            .map { it.id }
        if (tabIds.isEmpty()) return
        tabsUseCases.removeTabs(tabIds)
        scope.launch {
            val result = snackbar.showSnackbar(
                message = context.resources.getQuantityString(R.plurals.dejavu_tabs_closed, tabIds.size, tabIds.size),
                actionLabel = context.getString(R.string.dejavu_undo),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) tabsUseCases.undo()
        }
    }

    override fun onToggleFolder(folderId: String) = repository.toggleFolder(folderId)

    override fun onSearchClick() {
        NewTabContainerChoice.clear()
        components.appStore.dispatch(SearchStarted())
    }

    override fun onNewPrivateTab() {
        NewTabContainerChoice.setPrivate()
        components.appStore.dispatch(SearchStarted())
    }

    override fun onClosePrivateTabs() = tabsUseCases.removePrivateTabs()

    override fun onCloseTab(tabId: String) = tabsUseCases.removeTab(tabId)

    override fun onNewTabInContainer(pick: ContainerPick) {
        NewTabContainerChoice.set(pick)
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

    override fun onExtensionsClick() {
        navController.navigate(NavGraphDirections.actionGlobalMenuDialogFragment(accesspoint = MenuAccessPoint.Home))
    }

    override fun onBookmarksClick() {
        navController.navigate(NavGraphDirections.actionGlobalBookmarkFragment(BookmarkRoot.Mobile.id))
    }

    override fun onHistoryClick() {
        navController.navigate(NavGraphDirections.actionGlobalHistoryFragment())
    }

    private fun TabSessionState.toPinSource() = PinSource(id, content.url, content.title, contextId)
}

/** Names of the folders around [item], outermost first, separated by "/". */
internal fun WorkspaceState.folderPathOf(item: PinnedItem): String {
    val names = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var parentId = item.parentId
    while (parentId != null && seen.add(parentId)) {
        val parent = pins.firstOrNull { it.id == parentId } ?: break
        names.add(0, parent.title)
        parentId = parent.parentId
    }
    return names.joinToString("/")
}
