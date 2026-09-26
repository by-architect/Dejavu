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
import org.mozilla.fenix.kaizen.NewTabContainerChoice
import org.mozilla.fenix.kaizen.actions.ActionContext
import org.mozilla.fenix.kaizen.actions.CustomAction
import org.mozilla.fenix.kaizen.actions.CustomActionRunner
import org.mozilla.fenix.kaizen.actions.TabAction
import org.mozilla.fenix.kaizen.browser.PendingTabLeave
import org.mozilla.fenix.kaizen.containers.KaizenContainerStorage
import org.mozilla.fenix.kaizen.settings.KaizenSettings
import org.mozilla.fenix.kaizen.settings.resolveRowActions
import org.mozilla.fenix.kaizen.workspaces.PinPlacement
import org.mozilla.fenix.kaizen.workspaces.PinSource
import org.mozilla.fenix.kaizen.workspaces.PinnedItem
import org.mozilla.fenix.kaizen.workspaces.WorkspaceRepository
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState
import org.mozilla.fenix.theme.FirefoxTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                DefaultKaizenHomeInteractor(
                    context = context,
                    components = fenix,
                    repository = repository,
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
            val containerRecords by containerStorage.records.collectAsState()
            val containers = remember(containerRecords) { containerRecords.orEmpty().associateBy { it.contextId } }
            val tabs by fenix.core.store.observeAsComposableState { it.normalTabs }
            val selectedTabId by fenix.core.store.observeAsComposableState { it.selectedTabId }
            val restoreComplete by fenix.core.store.observeAsComposableState { it.restoreComplete }
            val isSearchActive by fenix.appStore.observeAsComposableState { it.searchState.isSearchActive }

            LaunchedEffect(Unit) {
                containerStorage.load()
                PendingTabLeave.consume()?.let { interactor.onTabLeft(it) }
            }

            LaunchedEffect(isSearchActive) {
                if (!isSearchActive) {
                    delay(SEARCH_ABANDON_GRACE_MS)
                    NewTabContainerChoice.clear()
                }
            }

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
                    pinnedRowActions = resolveRowActions(pinnedKeys, pinned = true, customActions = customActions),
                    unpinnedRowActions = resolveRowActions(unpinnedKeys, pinned = false, customActions = customActions),
                    customActions = customActions,
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

    /** Unloads a pinned tab the user left with Back, or closes an unpinned one. */
    fun onTabLeft(leave: PendingTabLeave.Leave) {
        if (store.state.findTab(leave.tabId) == null) return
        if (leave.sleep) {
            store.dispatch(EngineAction.SuspendEngineSessionAction(leave.tabId))
        } else {
            tabsUseCases.removeTab(leave.tabId)
        }
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

    override fun onCustomAction(action: CustomAction, targets: ActionTargets) {
        val contexts = actionContexts(targets)
        if (contexts.isEmpty()) return
        scope.launch {
            val result = actionRunner.run(action, contexts)
            val message = if (result.failed == 0) {
                context.getString(R.string.kaizen_custom_action_sent, action.name)
            } else {
                context.resources.getQuantityString(
                    R.plurals.kaizen_custom_action_failed,
                    result.failed,
                    result.failed,
                    action.name,
                    result.lastStatus?.toString() ?: "-",
                )
            }
            snackbar.showSnackbar(message)
        }
    }

    @Suppress("LongMethod")
    override fun onDrop(workspaceId: String, selection: Selection, folderId: String?, target: DropTarget) {
        val state = repository.state.value
        val pins = state.pins.filter { it.id in selection.pinIds && !it.isFolder }
        val tabs = selection.tabIds.mapNotNull { store.state.findTab(it) }.filter { state.pinOf(it.id) == null }
        val placement = when (target) {
            is DropTarget.IntoFolder -> PinPlacement.Into(target.folderId)
            is DropTarget.NextToPin -> PinPlacement.Next(target.pinId, target.after)
            is DropTarget.PinnedEdge -> PinPlacement.Edge(target.atEnd)
            is DropTarget.NextToTab, is DropTarget.UnpinnedEdge -> null
        }
        if (placement != null) {
            repository.placePins(
                workspaceId = workspaceId,
                itemIds = pins.map { it.id }.toSet() + listOfNotNull(folderId),
                newPins = tabs.map { it.toPinSource() },
                placement = placement,
            )
            return
        }
        if (folderId != null) return

        // Unpinning: open pinned tabs become normal tabs, closed ones are reopened without loading.
        val livePinnedTabs = pins.mapNotNull { pin -> pin.tabId?.takeIf { store.state.findTab(it) != null } }
        val reopened = pins.filter { pin -> pin.tabId == null || store.state.findTab(pin.tabId) == null }.mapNotNull { pin ->
            pin.url?.let { url ->
                tabsUseCases.addTab(
                    url = url,
                    selectTab = false,
                    startLoading = false,
                    title = pin.title,
                    contextId = pin.containerId,
                    source = SessionState.Source.Internal.None,
                )
            }
        }
        repository.unpin(pins.map { it.id }.toSet())

        val moving = tabs.map { it.id } + livePinnedTabs + reopened
        val others = store.state.normalTabs.filter {
            it.id !in moving && state.workspaceOf(it.id) == workspaceId && state.pinOf(it.id) == null
        }
        val (anchor, after) = when (target) {
            is DropTarget.NextToTab -> target.tabId to target.after
            is DropTarget.UnpinnedEdge ->
                if (target.atEnd) others.lastOrNull()?.id to true else others.firstOrNull()?.id to false
            else -> null to false
        }
        if (anchor != null && moving.isNotEmpty()) {
            store.dispatch(TabListAction.MoveTabsAction(moving, anchor, after))
        }
    }

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
        NewTabContainerChoice.clear()
        components.appStore.dispatch(SearchStarted())
    }

    override fun onNewTabInContainer(containerId: String?) {
        NewTabContainerChoice.set(containerId)
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

    override fun onHistoryClick() {
        navController.navigate(NavGraphDirections.actionGlobalHistoryFragment())
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
        val pinContexts = targets.pins.map { pin ->
            val tab = targets.pinnedTabs.firstOrNull { it.id == pin.tabId }
            ActionContext(
                url = tab?.content?.url ?: pin.url.orEmpty(),
                title = tab?.content?.title?.ifBlank { null } ?: pin.title,
                container = (tab?.contextId ?: pin.containerId)?.let { containerNames[it] }.orEmpty(),
                workspace = workspaceName(pin.workspaceId),
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
private fun WorkspaceState.folderPathOf(item: PinnedItem): String {
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
