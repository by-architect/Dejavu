/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.home

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mozilla.components.browser.state.state.TabSessionState
import org.mozilla.fenix.R
import org.mozilla.fenix.kaizen.actions.CustomAction
import org.mozilla.fenix.kaizen.actions.RowAction
import org.mozilla.fenix.kaizen.actions.TabAction
import org.mozilla.fenix.kaizen.actions.icon
import org.mozilla.fenix.kaizen.actions.label
import org.mozilla.fenix.kaizen.containers.ContainerRecord
import org.mozilla.fenix.kaizen.containers.color
import org.mozilla.fenix.kaizen.workspaces.PinnedItem
import org.mozilla.fenix.kaizen.workspaces.Workspace
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState
import org.mozilla.fenix.kaizen.workspaces.WorkspaceTheme
import kotlin.math.floor
import mozilla.components.ui.icons.R as iconsR

/**
 * User actions on the Kaizen home screen that leave the screen's own UI state.
 */
@Suppress("TooManyFunctions")
interface KaizenHomeInteractor {
    fun onWorkspaceSelected(workspaceId: String)
    fun onSaveWorkspace(workspaceId: String?, name: String, containerId: String?, icon: String?, theme: WorkspaceTheme?)
    fun onDeleteWorkspace(workspaceId: String)

    /** Moves workspace [workspaceId] to position [index] among the workspaces. */
    fun onMoveWorkspace(workspaceId: String, index: Int)
    fun onTabClick(tabId: String)
    fun onPinClick(pin: PinnedItem)

    /** Runs a built-in action that needs no further input from the user, from the page of [workspaceId]. */
    fun onTabAction(action: TabAction, targets: ActionTargets, workspaceId: String)

    /** Sends the requests of a custom action for [targets]. */
    fun onCustomAction(action: CustomAction, targets: ActionTargets)

    /** Moves dragged tabs, pinned items and folders to [target] in [workspaceId]. */
    fun onDrop(workspaceId: String, selection: Selection, target: DropTarget)

    /** Moves essential [pinId] to position [index] among the essentials shown with it. */
    fun onMoveEssential(pinId: String, index: Int)

    /** Closes the unpinned tabs of [workspaceId], with a way to undo. */
    fun onClearUnpinned(workspaceId: String)
    fun onCreateFolder(workspaceId: String, parentId: String?, name: String, targets: ActionTargets)
    fun onRenameFolder(folderId: String, name: String)
    fun onToggleFolder(folderId: String)

    /** Pins [targets] in [workspaceId], inside [folderId] or at the end of the top level when it is `null`. */
    fun onMoveToFolder(workspaceId: String, targets: ActionTargets, folderId: String?)

    /** Removes pinned tabs, essentials and folders with everything inside them, and closes every tab of [targets]. */
    fun onDeleteItems(targets: ActionTargets)
    fun onMoveToWorkspace(targets: ActionTargets, workspaceId: String)

    /** Moves the tabs of [targets] to container [contextId], or out of any container when it is `null`. */
    fun onChangeContainer(targets: ActionTargets, contextId: String?)
    fun onSearchClick()

    /** Starts a new tab in [containerId], or without a container when it is `null`. */
    fun onNewTabInContainer(containerId: String?)
    fun onManageContainers()
    fun onAccountClick()
    fun onSettingsClick()
    fun onDownloadsClick()
    fun onHistoryClick()
}

/**
 * Workspace based home screen. The essentials sit on top of every workspace. Each workspace is a page with its pinned
 * tabs and folders, then its other tabs; swiping horizontally switches workspace. Long-pressing a tab or folder starts
 * selection mode; keeping the finger down and moving drags the selection.
 */
@Suppress("LongMethod", "LongParameterList", "CognitiveComplexMethod")
@Composable
fun KaizenHome(
    state: WorkspaceState,
    tabs: List<TabSessionState>,
    selectedTabId: String?,
    containers: Map<String, ContainerRecord>,
    pinnedRowActions: List<RowAction>,
    unpinnedRowActions: List<RowAction>,
    selectionActions: List<RowAction>,
    essentialsPerContainer: Boolean,
    interactor: KaizenHomeInteractor,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(initialPage = state.activeIndex) { state.workspaces.size }
    var selection by remember { mutableStateOf<Selection?>(null) }
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    var essentialsDrop by remember { mutableStateOf(EssentialsDrop.NONE) }
    val currentWorkspace = state.workspaces.getOrNull(pagerState.currentPage) ?: state.workspaces.first()
    val tabsById = remember(tabs) { tabs.associateBy { it.id } }
    val essentials = state.essentialsFor(currentWorkspace.containerId, essentialsPerContainer)
    val grain = rememberGrainBrush()

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
        val (folders, pins) = state.pins.partition { it.isFolder }
        val pinIds = pins.map { it.id }.toSet()
        val folderIds = folders.map { it.id }.toSet()
        val cleaned = Selection(
            tabIds = current.tabIds.filter { it in tabIds }.toSet(),
            pinIds = current.pinIds.filter { it in pinIds }.toSet(),
            folderIds = current.folderIds.filter { it in folderIds }.toSet(),
        )
        selection = cleaned.takeIf { it.size > 0 }
    }

    BackHandler(enabled = selection != null) { selection = null }

    fun runAction(action: RowAction, targets: ActionTargets, workspaceId: String) {
        when (action) {
            is RowAction.Custom -> interactor.onCustomAction(action.action, targets)
            is RowAction.BuiltIn -> when (action.action) {
                TabAction.MOVE_TO_WORKSPACE -> dialog = HomeDialog.MoveToWorkspace(workspaceId, targets)
                TabAction.MOVE_TO_FOLDER -> dialog = HomeDialog.MoveToFolder(workspaceId, targets)
                TabAction.NEW_FOLDER -> dialog = HomeDialog.NewFolder(workspaceId, state.newFolderParent(targets), targets)
                TabAction.NEW_SUBFOLDER ->
                    targets.folders.singleOrNull()?.let { dialog = HomeDialog.NewFolder(workspaceId, parentId = it.id) }
                TabAction.RENAME_FOLDER -> targets.folders.singleOrNull()?.let { dialog = HomeDialog.RenameFolder(it) }
                TabAction.DELETE -> dialog = HomeDialog.DeleteItems(targets)
                TabAction.CHANGE_CONTAINER -> dialog = HomeDialog.ChangeContainer(targets)
                else -> interactor.onTabAction(action.action, targets, workspaceId)
            }
        }
        selection = null
    }

    fun onPinClick(pin: PinnedItem) {
        val current = selection
        if (current != null) selection = current.togglePin(pin.id).takeIf { it.size > 0 } else interactor.onPinClick(pin)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .drawBehind {
                val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
                val page = floor(position).toInt()
                drawWorkspaceTheme(
                    theme = state.workspaces.getOrNull(page)?.theme,
                    next = state.workspaces.getOrNull(page + 1)?.theme,
                    fraction = position - page,
                    grain = grain,
                )
            },
    ) {
        val activeSelection = selection
        if (activeSelection == null) {
            TopBar(interactor)
        } else if (essentialsDrop != EssentialsDrop.NONE) {
            EssentialsDropBar(active = essentialsDrop == EssentialsDrop.ACTIVE)
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

        if (essentials.isNotEmpty()) {
            EssentialsGrid(
                essentials = essentials,
                tabsById = tabsById,
                selectedTabId = selectedTabId,
                containers = containers,
                selection = activeSelection,
                isDropTarget = essentialsDrop == EssentialsDrop.ACTIVE,
                onClick = ::onPinClick,
                onStartDrag = { pin -> selection = (selection ?: Selection()) + Selection(pinIds = setOf(pin.id)) },
                onMove = { pinId, index ->
                    interactor.onMoveEssential(pinId, index)
                    selection = null
                },
                modifier = Modifier.padding(bottom = 4.dp),
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
                        val current = selection
                        if (current != null) selection = current.toggleTab(tab.id).takeIf { it.size > 0 } else interactor.onTabClick(tab.id)
                    },
                    onPinClick = ::onPinClick,
                    onStartDrag = { picked ->
                        val started = (selection ?: Selection()) + picked
                        selection = started
                        started
                    },
                    onDrop = { dragged, target ->
                        interactor.onDrop(workspace.id, dragged, target)
                        selection = null
                    },
                    onEssentialsDrop = { essentialsDrop = it },
                    onRowAction = { action, targets -> runAction(action, targets, workspace.id) },
                    onFolderClick = { folder ->
                        val current = selection
                        if (current != null) {
                            selection = current.toggleFolder(folder.id).takeIf { it.size > 0 }
                        } else {
                            interactor.onToggleFolder(folder.id)
                        }
                    },
                    onNewFolder = { dialog = HomeDialog.NewFolder(workspace.id, parentId = null) },
                    onNewWorkspace = { dialog = HomeDialog.EditWorkspace(null) },
                    onEditWorkspace = { dialog = HomeDialog.EditWorkspace(workspace.id) },
                    onDeleteWorkspace = { dialog = HomeDialog.DeleteWorkspace(workspace.id) },
                    onMoveWorkspace = { delta -> interactor.onMoveWorkspace(workspace.id, page + delta) },
                    onNewTabClick = interactor::onSearchClick,
                    onNewTabInContainer = interactor::onNewTabInContainer,
                    onManageContainers = interactor::onManageContainers,
                    onClearUnpinned = { interactor.onClearUnpinned(workspace.id) },
                ),
                canDeleteWorkspace = state.workspaces.size > 1,
                canMoveLeft = page > 0,
                canMoveRight = page < state.workspaces.size - 1,
            )
        }

        if (activeSelection == null) {
            WorkspaceBar(
                workspaces = state.workspaces,
                containers = containers,
                activeIndex = pagerState.currentPage,
                onDotClick = { index -> interactor.onWorkspaceSelected(state.workspaces[index].id) },
                onMove = interactor::onMoveWorkspace,
                onDownloadsClick = interactor::onDownloadsClick,
                onHistoryClick = interactor::onHistoryClick,
            )
        } else {
            val targets = ActionTargets.of(state, tabs, activeSelection)
            SelectionBar(
                actions = selectionActions.filter { it.appliesTo(targets) },
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

/** Replaces the selection bar on top while dragged tabs can be dropped into the essentials. */
@Composable
private fun EssentialsDropBar(active: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
        ) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_grid_add_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.kaizen_essentials_drop),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
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
    actions: List<RowAction>,
    onAction: (RowAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        actions.chunked(SELECTION_ACTIONS_PER_ROW).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { action ->
                    SelectionAction(action, onAction, Modifier.weight(1f))
                }
                repeat(SELECTION_ACTIONS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SelectionAction(
    action: RowAction,
    onAction: (RowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDestructive = action is RowAction.BuiltIn && (action.action == TabAction.CLOSE || action.action == TabAction.DELETE)
    val color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onAction(action) }
            .padding(vertical = 8.dp, horizontal = 2.dp),
    ) {
        Icon(
            painter = painterResource(action.icon),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val SELECTION_ACTIONS_PER_ROW = 6

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

/**
 * A workspace being dragged along the workspace bar.
 *
 * @property workspaceId The long-pressed workspace.
 * @property x Finger position along the row of workspaces.
 * @property index Position the workspace would be dropped at.
 */
private data class WorkspaceDrag(val workspaceId: String, val x: Float, val index: Int)

/**
 * Downloads, one dot or icon per workspace, and history. Tapping a workspace shows it; long-pressing and dragging one
 * sideways moves it.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun WorkspaceBar(
    workspaces: List<Workspace>,
    containers: Map<String, ContainerRecord>,
    activeIndex: Int,
    onDotClick: (Int) -> Unit,
    onMove: (workspaceId: String, index: Int) -> Unit,
    onDownloadsClick: () -> Unit,
    onHistoryClick: () -> Unit,
) {
    var drag by remember { mutableStateOf<WorkspaceDrag?>(null) }
    val lefts = remember { mutableStateMapOf<String, Float>() }
    val latestWorkspaces by rememberUpdatedState(workspaces)
    val latestOnMove by rememberUpdatedState(onMove)
    val haptics = LocalHapticFeedback.current
    val cellWidth = with(LocalDensity.current) { WorkspaceCellSize.toPx() }
    val activeId = workspaces.getOrNull(activeIndex)?.id

    fun indexAt(x: Float): Int {
        val start = lefts.values.minOrNull() ?: 0f
        return ((x - start) / cellWidth).toInt().coerceIn(0, (latestWorkspaces.size - 1).coerceAtLeast(0))
    }

    val current = drag
    val ordered = if (current == null) {
        workspaces
    } else {
        val dragged = workspaces.firstOrNull { it.id == current.workspaceId }
        if (dragged == null) {
            workspaces
        } else {
            (workspaces - dragged).toMutableList().apply { add(current.index.coerceIn(0, size), dragged) }
        }
    }

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
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .pointerInput(cellWidth) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val index = indexAt(offset.x)
                            val workspace = latestWorkspaces.getOrNull(index)
                            if (workspace != null) {
                                drag = WorkspaceDrag(workspace.id, offset.x, index)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { change, amount ->
                            val dragging = drag ?: return@detectDragGesturesAfterLongPress
                            change.consume()
                            val x = dragging.x + amount.x
                            drag = dragging.copy(x = x, index = indexAt(x))
                        },
                        onDragEnd = {
                            val dragging = drag
                            drag = null
                            val from = latestWorkspaces.indexOfFirst { it.id == dragging?.workspaceId }
                            if (dragging != null && from >= 0 && from != dragging.index) {
                                latestOnMove(dragging.workspaceId, dragging.index)
                            }
                        },
                        onDragCancel = { drag = null },
                    )
                },
        ) {
            ordered.forEach { workspace ->
                WorkspaceDot(
                    workspace = workspace,
                    container = workspace.containerId?.let { containers[it] },
                    isActive = workspace.id == activeId,
                    isDragged = current?.workspaceId == workspace.id,
                    onClick = {
                        val index = workspaces.indexOfFirst { it.id == workspace.id }
                        if (drag == null && index >= 0) onDotClick(index)
                    },
                    modifier = Modifier.onPlaced { lefts[workspace.id] = it.positionInParent().x },
                )
            }
        }

        BarIconButton(
            icon = iconsR.drawable.mozac_ic_history_24,
            contentDescription = stringResource(R.string.kaizen_history),
            onClick = onHistoryClick,
        )
    }
}

private val WorkspaceCellSize = 36.dp
private const val INACTIVE_WORKSPACE_ALPHA = 0.45f
private const val INACTIVE_ICON_ALPHA = 0.55f

/** A workspace in the workspace bar: its icon, or a dot in its container's color. */
@Suppress("LongParameterList")
@Composable
private fun WorkspaceDot(
    workspace: Workspace,
    container: ContainerRecord?,
    isActive: Boolean,
    isDragged: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val base = container?.color?.color ?: MaterialTheme.colorScheme.onSurface
    val icon = workspace.icon
    val background = when {
        isDragged -> MaterialTheme.colorScheme.primaryContainer
        isActive && icon != null -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> Color.Transparent
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(WorkspaceCellSize)
            .clip(CircleShape)
            .background(background)
            .clickable(onClickLabel = workspace.name, onClick = onClick),
    ) {
        if (icon != null) {
            Text(
                text = icon,
                fontSize = 17.sp,
                modifier = Modifier.alpha(if (isActive || isDragged) 1f else INACTIVE_ICON_ALPHA),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(if (isActive) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (isActive) base else base.copy(alpha = INACTIVE_WORKSPACE_ALPHA)),
            )
        }
    }
}
