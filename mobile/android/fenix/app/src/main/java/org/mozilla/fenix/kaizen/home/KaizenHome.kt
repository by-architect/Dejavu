/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.home

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.state.TabSessionState
import org.mozilla.fenix.R
import org.mozilla.fenix.kaizen.actions.TabAction
import org.mozilla.fenix.kaizen.containers.ContainerRecord
import org.mozilla.fenix.kaizen.containers.color
import org.mozilla.fenix.kaizen.workspaces.PinnedItem
import org.mozilla.fenix.kaizen.workspaces.Workspace
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState
import mozilla.components.ui.icons.R as iconsR

/**
 * User actions on the Kaizen home screen that leave the screen's own UI state.
 */
@Suppress("TooManyFunctions")
interface KaizenHomeInteractor {
    fun onWorkspaceSelected(workspaceId: String)
    fun onSaveWorkspace(workspaceId: String?, name: String, containerId: String?)
    fun onDeleteWorkspace(workspaceId: String)
    fun onTabClick(tabId: String)
    fun onPinClick(pin: PinnedItem)

    /** Runs an action that needs no further input from the user. */
    fun onTabAction(action: TabAction, targets: ActionTargets)
    fun onCreateFolder(workspaceId: String, parentId: String?, name: String, targets: ActionTargets)
    fun onRenameFolder(folderId: String, name: String)
    fun onToggleFolder(folderId: String)
    fun onMoveToFolder(targets: ActionTargets, folderIds: Set<String>, destinationId: String?)
    fun onUnpackFolder(folderId: String)
    fun onDeleteFolder(folderId: String)
    fun onMoveToWorkspace(targets: ActionTargets, folderIds: Set<String>, workspaceId: String)
    fun onSearchClick()
    fun onAccountClick()
    fun onSettingsClick()
    fun onDownloadsClick()
}

/**
 * Workspace based home screen. Each workspace is a page with its pinned tabs and folders, then its other tabs;
 * swiping horizontally switches workspace. Long-pressing a tab starts selection mode.
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
fun KaizenHome(
    state: WorkspaceState,
    tabs: List<TabSessionState>,
    selectedTabId: String?,
    containers: Map<String, ContainerRecord>,
    pinnedRowActions: List<TabAction>,
    unpinnedRowActions: List<TabAction>,
    interactor: KaizenHomeInteractor,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(initialPage = state.activeIndex) { state.workspaces.size }
    var selection by remember { mutableStateOf<Selection?>(null) }
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    val currentWorkspace = state.workspaces.getOrNull(pagerState.currentPage) ?: state.workspaces.first()

    LaunchedEffect(pagerState.settledPage) {
        state.workspaces.getOrNull(pagerState.settledPage)?.let { interactor.onWorkspaceSelected(it.id) }
    }

    LaunchedEffect(state.activeIndex, state.workspaces.size) {
        if (pagerState.settledPage != state.activeIndex) {
            pagerState.animateScrollToPage(state.activeIndex)
        }
    }

    LaunchedEffect(tabs, state.pins) {
        val current = selection ?: return@LaunchedEffect
        val tabIds = tabs.map { it.id }.toSet()
        val pinIds = state.pins.map { it.id }.toSet()
        val cleaned = Selection(current.tabIds.filter { it in tabIds }.toSet(), current.pinIds.filter { it in pinIds }.toSet())
        selection = cleaned.takeIf { it.size > 0 }
    }

    BackHandler(enabled = selection != null) { selection = null }

    fun runAction(action: TabAction, targets: ActionTargets, workspaceId: String) {
        when (action) {
            TabAction.MOVE_TO_WORKSPACE -> dialog = HomeDialog.MoveToWorkspace(workspaceId, targets)
            TabAction.MOVE_TO_FOLDER -> dialog = HomeDialog.MoveToFolder(workspaceId, targets)
            TabAction.NEW_FOLDER -> dialog = HomeDialog.NewFolder(workspaceId, parentId = null, targets = targets)
            else -> interactor.onTabAction(action, targets)
        }
        selection = null
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val activeSelection = selection
        if (activeSelection == null) {
            TopBar(interactor)
        } else {
            SelectionTopBar(
                count = activeSelection.size,
                onClose = { selection = null },
                onSelectAll = {
                    selection = Selection(
                        tabIds = tabs.filter { state.workspaceOf(it.id) == currentWorkspace.id && state.pinOf(it.id) == null }
                            .map { it.id }.toSet(),
                        pinIds = state.pins.filter { it.workspaceId == currentWorkspace.id && !it.isFolder }
                            .map { it.id }.toSet(),
                    )
                },
            )
        }

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = activeSelection == null,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            key = { state.workspaces[it].id },
        ) { page ->
            val workspace = state.workspaces[page]
            WorkspacePage(
                state = state,
                workspace = workspace,
                tabs = tabs.filter { state.workspaceOf(it.id) == workspace.id },
                selectedTabId = selectedTabId,
                containers = containers,
                pinnedRowActions = pinnedRowActions,
                unpinnedRowActions = unpinnedRowActions,
                selection = activeSelection,
                callbacks = WorkspacePageCallbacks(
                    onTabClick = { tab ->
                        if (activeSelection != null) selection = activeSelection.toggleTab(tab.id) else interactor.onTabClick(tab.id)
                    },
                    onTabLongClick = { tab -> selection = (activeSelection ?: Selection()).toggleTab(tab.id) },
                    onPinClick = { pin ->
                        if (activeSelection != null) selection = activeSelection.togglePin(pin.id) else interactor.onPinClick(pin)
                    },
                    onPinLongClick = { pin -> selection = (activeSelection ?: Selection()).togglePin(pin.id) },
                    onRowAction = { action, targets -> runAction(action, targets, workspace.id) },
                    onFolderClick = { interactor.onToggleFolder(it.id) },
                    onFolderMenu = { folder, item ->
                        when (item) {
                            FolderMenuItem.NEW_SUBFOLDER -> dialog = HomeDialog.NewFolder(workspace.id, parentId = folder.id)
                            FolderMenuItem.RENAME -> dialog = HomeDialog.RenameFolder(folder)
                            FolderMenuItem.SLEEP_ALL ->
                                interactor.onTabAction(TabAction.SLEEP, ActionTargets.ofFolder(state, tabs, folder.id))
                            FolderMenuItem.SHARE ->
                                interactor.onTabAction(TabAction.SHARE, ActionTargets.ofFolder(state, tabs, folder.id))
                            FolderMenuItem.MOVE_TO_FOLDER ->
                                dialog = HomeDialog.MoveToFolder(workspace.id, ActionTargets(), setOf(folder.id))
                            FolderMenuItem.MOVE_TO_WORKSPACE ->
                                dialog = HomeDialog.MoveToWorkspace(workspace.id, ActionTargets(), setOf(folder.id))
                            FolderMenuItem.UNPACK -> interactor.onUnpackFolder(folder.id)
                            FolderMenuItem.DELETE -> dialog = HomeDialog.DeleteFolder(folder)
                        }
                    },
                    onNewFolder = { dialog = HomeDialog.NewFolder(workspace.id, parentId = null) },
                    onEditWorkspace = { dialog = HomeDialog.EditWorkspace(workspace.id) },
                    onDeleteWorkspace = { dialog = HomeDialog.DeleteWorkspace(workspace.id) },
                    onNewTabClick = interactor::onSearchClick,
                ),
                canDeleteWorkspace = state.workspaces.size > 1,
            )
        }

        if (activeSelection == null) {
            WorkspaceBar(
                workspaces = state.workspaces,
                containers = containers,
                activeIndex = pagerState.currentPage,
                onDotClick = { index -> interactor.onWorkspaceSelected(state.workspaces[index].id) },
                onAddClick = { dialog = HomeDialog.EditWorkspace(null) },
                onDownloadsClick = interactor::onDownloadsClick,
            )
        } else {
            val targets = ActionTargets.of(state, tabs, activeSelection)
            SelectionBar(
                actions = TabAction.forSelection.filter { it.appliesTo(targets) },
                onAction = { runAction(it, targets, currentWorkspace.id) },
            )
        }
    }

    dialog?.let { current ->
        HomeDialogs(
            dialog = current,
            state = state,
            containers = containers,
            interactor = interactor,
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun TopBar(interactor: KaizenHomeInteractor) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
    ) {
        BarIconButton(
            icon = iconsR.drawable.mozac_ic_avatar_circle_24,
            contentDescription = stringResource(R.string.kaizen_account),
            onClick = interactor::onAccountClick,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = interactor::onSearchClick)
                .padding(horizontal = 14.dp),
        ) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_search_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.kaizen_search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        BarIconButton(
            icon = iconsR.drawable.mozac_ic_settings_24,
            contentDescription = stringResource(R.string.kaizen_settings),
            onClick = interactor::onSettingsClick,
        )
    }
}

@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
    ) {
        BarIconButton(
            icon = iconsR.drawable.mozac_ic_cross_24,
            contentDescription = stringResource(R.string.kaizen_selection_exit),
            onClick = onClose,
        )
        Text(
            text = pluralStringResource(R.plurals.kaizen_selection_count, count, count),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        BarIconButton(
            icon = iconsR.drawable.mozac_ic_select_all_24,
            contentDescription = stringResource(R.string.kaizen_selection_select_all),
            onClick = onSelectAll,
        )
    }
}

@Composable
private fun SelectionBar(
    actions: List<TabAction>,
    onAction: (TabAction) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        actions.forEach { action ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onAction(action) }
                    .padding(vertical = 8.dp),
            ) {
                Icon(
                    painter = painterResource(action.icon),
                    contentDescription = null,
                    tint = if (action == TabAction.CLOSE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(action.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun BarIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun WorkspaceBar(
    workspaces: List<Workspace>,
    containers: Map<String, ContainerRecord>,
    activeIndex: Int,
    onDotClick: (Int) -> Unit,
    onAddClick: () -> Unit,
    onDownloadsClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
    ) {
        BarIconButton(
            icon = iconsR.drawable.mozac_ic_download_24,
            contentDescription = stringResource(R.string.kaizen_downloads),
            onClick = onDownloadsClick,
        )

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        ) {
            workspaces.forEachIndexed { index, workspace ->
                val isActive = index == activeIndex
                val containerColor = workspace.containerId?.let { containers[it] }?.color?.color
                val base = containerColor ?: MaterialTheme.colorScheme.onSurface
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = workspace.name) { onDotClick(index) },
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (isActive) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (isActive) base else base.copy(alpha = 0.45f)),
                    )
                }
            }
        }

        BarIconButton(
            icon = iconsR.drawable.mozac_ic_plus_24,
            contentDescription = stringResource(R.string.kaizen_add_workspace),
            onClick = onAddClick,
        )
    }
}
