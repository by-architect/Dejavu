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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.selector.privateTabs
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.prompt.ShareData
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.appservices.places.BookmarkRoot
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.browser.browsingmode.BrowsingMode
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.components.accounts.FenixFxAEntryPoint
import org.mozilla.fenix.components.appstate.AppAction.SearchAction.SearchStarted
import org.mozilla.fenix.components.components
import org.mozilla.fenix.components.menu.MenuAccessPoint
import org.mozilla.fenix.kaizen.NewTabContainerChoice
import org.mozilla.fenix.kaizen.actions.ActionContext
import org.mozilla.fenix.kaizen.actions.CustomAction
import org.mozilla.fenix.kaizen.actions.CustomActionRunner
import org.mozilla.fenix.kaizen.actions.TabAction
import org.mozilla.fenix.kaizen.browser.KaizenSearchOverlay
import org.mozilla.fenix.kaizen.containers.ContainerPick
import org.mozilla.fenix.kaizen.containers.KaizenContainerStorage
import org.mozilla.fenix.kaizen.containers.TemporaryContainers
import org.mozilla.fenix.kaizen.containers.reopenInContainer
import org.mozilla.fenix.kaizen.settings.KaizenSettings
import org.mozilla.fenix.kaizen.settings.resolveRowActions
import org.mozilla.fenix.kaizen.settings.resolveSelectionActions
import org.mozilla.fenix.kaizen.workspaces.MAX_ESSENTIALS
import org.mozilla.fenix.kaizen.workspaces.PinPlacement
import org.mozilla.fenix.kaizen.workspaces.PinSource
import org.mozilla.fenix.kaizen.workspaces.PinnedItem
import org.mozilla.fenix.kaizen.workspaces.WorkspaceRepository
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState
import org.mozilla.fenix.kaizen.workspaces.WorkspaceTheme
import org.mozilla.fenix.theme.FirefoxTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Whether the Kaizen workspace home replaces the upstream homepage. It always does: private tabs appear on it as a
 * private workspace, and it switches the app back to normal browsing when it shows.
 */
@Suppress("UNUSED_PARAMETER")
fun isKaizenHomeEnabled(isPrivate: Boolean): Boolean = true

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
                DefaultKaizenHomeInteractor(
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
                KaizenHome(
                    state = workspaceState,
                    tabs = tabs,
                    privateTabs = if (isPrivateLocked) emptyList() else allPrivateTabs,
                    selectedTabId = selectedTabId,
                    containers = containers,
                    pinnedRowActions = resolveRowActions(pinnedKeys, pinned = true, customActions = customActions),
                    unpinnedRowActions = resolveRowActions(unpinnedKeys, pinned = false, customActions = customActions),
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
                        KaizenSearchOverlay(
                            fromTop = !fenix.settings.shouldUseBottomToolbar ||
                                fenix.appStore.state.searchState.sourceTabId == null,
                            content = searchToolbar,
                        )
                    }
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
private class DefaultKaizenHomeInteractor(
    private val context: Context,
    private val components: Components,
    private val repository: WorkspaceRepository,
    private val settings: KaizenSettings,
    private val containerStorage: KaizenContainerStorage,
    private val navController: NavController,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val openTab: (String) -> Unit,
) : KaizenHomeInteractor {
    private val tabsUseCases = components.useCases.tabsUseCases
    private val store = components.core.store
    private val actionRunner by lazy { CustomActionRunner(components.core.client) }

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

    override fun onDeleteWorkspace(workspaceId: String) = repository.deleteWorkspace(workspaceId)

    override fun onMoveWorkspace(workspaceId: String, index: Int) = repository.moveWorkspace(workspaceId, index)

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

    @Suppress("CyclomaticComplexMethod")
    override fun onTabAction(action: TabAction, targets: ActionTargets, workspaceId: String) {
        when (action) {
            TabAction.CLOSE -> closeTabs(targets.openTabs.map { it.id })
            TabAction.PIN -> repository.pinTabs(targets.tabs.map { it.toPinSource() })
            TabAction.UNPIN -> {
                // Like dragging a pin out: unpinned tabs stay as tabs, the closed ones reopened without loading.
                val pins = targets.pins.filterNot { it.essential }
                reopenClosed(pins)
                repository.unpin(pins.map { it.id }.toSet())
            }
            TabAction.SLEEP -> targets.awakeTabs.forEach { store.dispatch(EngineAction.SuspendEngineSessionAction(it.id)) }
            TabAction.BOOKMARK -> bookmark(targets.links)
            TabAction.SHARE -> share(targets.links)
            TabAction.COPY_LINK -> copyLinks(targets.links)
            TabAction.DUPLICATE -> duplicate(targets)
            TabAction.RESET_PIN -> targets.changedPins.forEach { (pin, tab) ->
                pin.url?.let { components.useCases.sessionUseCases.loadUrl(it, tab.id) }
            }
            TabAction.ADD_TO_ESSENTIALS -> addToEssentials(targets)
            TabAction.REMOVE_FROM_ESSENTIALS -> removeFromEssentials(targets.pins.filter { it.essential }, workspaceId)
            TabAction.UNPACK_FOLDER -> targets.folders.forEach { repository.unpackFolder(it.id) }
            TabAction.DELETE -> onDeleteItems(targets)
            TabAction.SPLIT_VIEW -> split(targets)
            TabAction.UNSPLIT -> repository.unsplit(targets.splitTabIds)
            TabAction.MOVE_TO_WORKSPACE, TabAction.MOVE_TO_FOLDER, TabAction.NEW_FOLDER, TabAction.NEW_SUBFOLDER,
            TabAction.RENAME_FOLDER, TabAction.RENAME_TAB, TabAction.CHANGE_CONTAINER,
            -> Unit
        }
    }

    override fun onCustomAction(action: CustomAction, targets: ActionTargets) {
        val contexts = actionContexts(targets)
        if (contexts.isEmpty()) return
        scope.launch {
            val result = actionRunner.run(action, contexts)
            snackbar.showSnackbar(result.message(context, action, contexts.size))
        }
    }

    override fun onDrop(workspaceId: String, selection: Selection, target: DropTarget) {
        val state = repository.state.value
        val targets = ActionTargets.of(state, store.state.normalTabs, selection)
        val placement = when (target) {
            is DropTarget.IntoFolder -> PinPlacement.Into(target.folderId)
            is DropTarget.NextToPin -> PinPlacement.Next(target.pinId, target.after)
            is DropTarget.PinnedEdge -> PinPlacement.Edge(target.atEnd)
            DropTarget.Essentials -> {
                addToEssentials(targets)
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
        val moving = targets.tabs.map { it.id } + targets.pinnedTabs.map { it.id } + reopenClosed(targets.pins)
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
                message = context.resources.getQuantityString(R.plurals.kaizen_tabs_closed, tabIds.size, tabIds.size),
                actionLabel = context.getString(R.string.kaizen_undo),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) tabsUseCases.undo()
        }
    }

    override fun onCreateFolder(workspaceId: String, parentId: String?, name: String, targets: ActionTargets) {
        repository.createFolder(
            workspaceId = workspaceId,
            parentId = parentId,
            name = name,
            itemIds = targets.itemIds,
            newPins = targets.tabs.map { it.toPinSource() },
        )
    }

    override fun onRenameFolder(folderId: String, name: String) = repository.renameFolder(folderId, name)

    override fun onRenameTab(pinId: String?, tabId: String?, name: String) {
        when {
            pinId != null -> repository.renamePin(pinId, name)
            tabId != null -> repository.renameTab(tabId, name)
        }
    }

    override fun onToggleFolder(folderId: String) = repository.toggleFolder(folderId)

    override fun onMoveToFolder(workspaceId: String, targets: ActionTargets, folderId: String?) {
        repository.placePins(
            workspaceId = workspaceId,
            itemIds = targets.itemIds,
            newPins = targets.tabs.map { it.toPinSource() },
            placement = folderId?.let { PinPlacement.Into(it) } ?: PinPlacement.Edge(atEnd = true),
        )
    }

    override fun onDeleteItems(targets: ActionTargets) {
        val tabIds = targets.openTabs.map { it.id }
        repository.deleteItems(targets.itemIds)
        closeTabs(tabIds)
    }

    override fun onMoveToWorkspace(targets: ActionTargets, workspaceId: String) {
        repository.moveToWorkspace(
            tabIds = targets.tabs.map { it.id }.toSet(),
            itemIds = targets.itemIds,
            workspaceId = workspaceId,
        )
    }

    override fun onChangeContainer(targets: ActionTargets, pick: ContainerPick) {
        val contextId = when (pick) {
            ContainerPick.NoContainer -> null
            ContainerPick.Temporary -> TemporaryContainers.create(store, containerStorage)
            is ContainerPick.Container -> pick.contextId
        }
        repository.setPinContainer(targets.allPins.map { it.id }.toSet(), contextId)
        val selectedTabId = store.state.selectedTabId
        targets.openTabs.filter { it.contextId != contextId }.forEach { tab ->
            val selected = tab.id == selectedTabId
            components.reopenInContainer(tab, contextId, repository, selected = selected, load = selected || tab.isAwake)
        }
    }

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

    override fun onManageContainers() {
        navController.navigate(R.id.kaizen_containers_graph)
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

    private fun closeTabs(tabIds: List<String>) {
        val open = tabIds.filter { store.state.findTab(it) != null }
        if (open.isNotEmpty()) tabsUseCases.removeTabs(open)
    }

    /** Shows the two open tabs of [targets] together, next to each other when they are both unpinned. */
    private fun split(targets: ActionTargets) {
        val (first, second) = (targets.tabs + targets.pinnedTabs).takeIf { it.size == 2 } ?: return
        repository.createSplit(first.id, second.id)
        if (targets.pinnedTabs.isEmpty()) {
            store.dispatch(TabListAction.MoveTabsAction(listOf(second.id), first.id, placeAfter = true))
        }
        openTab(first.id)
    }

    /** Opens the closed ones of [pins] again without loading them, and returns the new tabs. */
    private fun reopenClosed(pins: List<PinnedItem>): List<String> =
        pins.filter { pin -> pin.tabId == null || store.state.findTab(pin.tabId) == null }.mapNotNull { pin ->
            pin.url?.let { url ->
                tabsUseCases.addTab(
                    url = url,
                    selectTab = false,
                    startLoading = false,
                    title = pin.title,
                    contextId = pin.containerId,
                    source = SessionState.Source.Internal.None,
                ).also { repository.attachPinned(pin.id, it) }
            }
        }

    private fun addToEssentials(targets: ActionTargets) {
        val candidates = targets.tabs.size + targets.pins.count { !it.essential }
        val before = repository.state.value.essentials.size
        repository.addToEssentials(
            sources = targets.tabs.map { it.toPinSource() },
            pinIds = targets.pins.map { it.id }.toSet(),
            perContainer = settings.essentialsPerContainer.value,
        )
        if (repository.state.value.essentials.size - before < candidates) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.kaizen_essentials_full, MAX_ESSENTIALS)) }
        }
    }

    /** Turns essentials back into normal tabs of [workspaceId]; closed ones are opened again without loading. */
    private fun removeFromEssentials(essentials: List<PinnedItem>, workspaceId: String) {
        reopenClosed(essentials)
        repository.removeFromEssentials(essentials.map { it.id }.toSet(), workspaceId)
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
        (targets.tabs + targets.pinnedTabs).forEach { tabsUseCases.duplicateTab(it, selectNewTab = false) }
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

    /** The values of the custom action variables for every target. */
    private fun actionContexts(targets: ActionTargets): List<ActionContext> {
        val state = repository.state.value
        val containerNames = containerStorage.records.value.orEmpty().associate { it.contextId to it.name }
        val date = SimpleDateFormat(ISO_DATE_PATTERN, Locale.US).format(Date())
        fun workspaceName(id: String) = state.workspaces.firstOrNull { it.id == id }?.name.orEmpty()

        val tabContexts = targets.tabs.map { tab ->
            ActionContext(
                url = tab.content.url,
                title = tab.content.title,
                container = tab.contextId?.let { containerNames[it] }.orEmpty(),
                workspace = workspaceName(state.workspaceOf(tab.id)),
                folderPath = "",
                date = date,
            )
        }
        val pinnedTabs = targets.pinnedTabs + targets.folderTabs
        val pinContexts = targets.allPins.map { pin ->
            val tab = pinnedTabs.firstOrNull { it.id == pin.tabId }
            ActionContext(
                url = tab?.content?.url ?: pin.url.orEmpty(),
                title = tab?.content?.title?.ifBlank { null } ?: pin.title,
                container = (tab?.contextId ?: pin.containerId)?.let { containerNames[it] }.orEmpty(),
                workspace = workspaceName(pin.workspaceId ?: state.activeWorkspaceId),
                folderPath = state.folderPathOf(pin),
                date = date,
            )
        }
        return tabContexts + pinContexts
    }

    private fun TabSessionState.toPinSource() = PinSource(id, content.url, content.title, contextId)

    private companion object {
        const val ISO_DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ssXXX"
    }
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
