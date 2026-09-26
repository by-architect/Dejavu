/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.compose.base.theme.success
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.Favicon
import org.mozilla.fenix.kaizen.actions.RowAction
import org.mozilla.fenix.kaizen.actions.icon
import org.mozilla.fenix.kaizen.actions.label
import org.mozilla.fenix.kaizen.containers.ContainerIcon
import org.mozilla.fenix.kaizen.containers.ContainerRecord
import org.mozilla.fenix.kaizen.containers.NoContainerIcon
import org.mozilla.fenix.kaizen.containers.color
import org.mozilla.fenix.kaizen.workspaces.MAX_FOLDER_DEPTH
import org.mozilla.fenix.kaizen.workspaces.PinnedItem
import org.mozilla.fenix.kaizen.workspaces.Workspace
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState
import kotlin.math.abs
import kotlin.math.roundToInt
import mozilla.components.ui.icons.R as iconsR

private val RowShape = RoundedCornerShape(16.dp)
private val IndentPerLevel = 18.dp
private val AutoScrollEdge = 64.dp
private const val AUTO_SCROLL_STEP = 14f
private const val AUTO_SCROLL_FRAME_MS = 16L
private const val DIMMED_ALPHA = 0.55f
private const val DRAGGED_ALPHA = 0.35f
private val INSIDE_FOLDER_RANGE = 0.25f..0.75f

private const val KEY_HEADER = "header"
private const val KEY_DIVIDER = "divider"
private const val KEY_NEW_TAB = "new_tab"
private const val PIN_PREFIX = "pin:"
private const val TAB_PREFIX = "tab:"

/** Entries of a folder's long-press menu. */
enum class FolderMenuItem(@param:StringRes val label: Int) {
    RENAME(R.string.kaizen_folder_rename),
    NEW_SUBFOLDER(R.string.kaizen_folder_new_subfolder),
    SLEEP_ALL(R.string.kaizen_folder_sleep_all),
    SHARE(R.string.kaizen_folder_share),
    MOVE_TO_FOLDER(R.string.kaizen_action_move_to_folder),
    MOVE_TO_WORKSPACE(R.string.kaizen_action_move_to_workspace),
    UNPACK(R.string.kaizen_folder_unpack),
    DELETE(R.string.kaizen_folder_delete),
}

/** Callbacks of [WorkspacePage]. */
data class WorkspacePageCallbacks(
    val onTabClick: (TabSessionState) -> Unit,
    val onPinClick: (PinnedItem) -> Unit,
    /** A long press on a tab or pinned tab selects it; returns the selection a drag starting now moves. */
    val onStartDrag: (tabId: String?, pinId: String?) -> Selection,
    /** Drops the dragged [Selection], or the dragged folder, on a [DropTarget]. */
    val onDrop: (selection: Selection, folderId: String?, target: DropTarget) -> Unit,
    val onRowAction: (RowAction, ActionTargets) -> Unit,
    val onFolderClick: (PinnedItem) -> Unit,
    val onFolderMenu: (PinnedItem, FolderMenuItem) -> Unit,
    val onNewFolder: () -> Unit,
    val onNewWorkspace: () -> Unit,
    val onEditWorkspace: () -> Unit,
    val onDeleteWorkspace: () -> Unit,
    val onNewTabClick: () -> Unit,
    val onNewTabInContainer: (String?) -> Unit,
    val onManageContainers: () -> Unit,
    val onClearUnpinned: () -> Unit,
)

/**
 * What is being dragged and where it would land.
 *
 * @property selection Tabs and pinned tabs being dragged.
 * @property folderId Folder being dragged, instead of [selection].
 * @property label Text of the floating row that follows the finger.
 * @property pointerY Finger position, relative to the list.
 * @property startY Where the long press happened.
 * @property moved Whether the finger moved enough to be a drag rather than a long press.
 * @property target Where the items would be dropped, or `null` when that position is not allowed.
 */
private data class DragState(
    val selection: Selection,
    val folderId: String?,
    val label: String,
    val pointerY: Float,
    val startY: Float,
    val moved: Boolean = false,
    val target: DropTarget? = null,
)

private enum class DropLine { TOP, BOTTOM, INSIDE }

/** Which row shows the drop position, and how. */
private data class DropLines(val key: String, val line: DropLine) {
    fun of(rowKey: String): DropLine? = line.takeIf { rowKey == key }
}

private data class PageContent(
    val state: WorkspaceState,
    val otherTabs: List<TabSessionState>,
)

@Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod")
@Composable
internal fun WorkspacePage(
    state: WorkspaceState,
    workspace: Workspace,
    tabs: List<TabSessionState>,
    selectedTabId: String?,
    containers: Map<String, ContainerRecord>,
    pinnedRowActions: List<RowAction>,
    unpinnedRowActions: List<RowAction>,
    selection: Selection?,
    callbacks: WorkspacePageCallbacks,
    canDeleteWorkspace: Boolean,
) {
    val tabsById = tabs.associateBy { it.id }
    val pinned = state.pinnedTree(workspace.id)
    val pinnedTabIds = state.pins.mapNotNull { it.tabId }.toSet()
    val otherTabs = tabs.filterNot { it.id in pinnedTabIds }

    val listState = rememberLazyListState()
    var drag by remember { mutableStateOf<DragState?>(null) }
    var menuFolderId by remember { mutableStateOf<String?>(null) }
    val content by rememberUpdatedState(PageContent(state, otherTabs))
    val latestCallbacks by rememberUpdatedState(callbacks)
    val haptics = LocalHapticFeedback.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val autoScrollEdge = with(LocalDensity.current) { AutoScrollEdge.toPx() }
    val isDragging = drag != null
    // A row also sees the finger lifting after a long press as a tap; while a long press or drag is in progress
    // (drag != null) row taps are ignored so the long press only selects.

    LaunchedEffect(isDragging) {
        while (isDragging) {
            val current = drag ?: break
            val height = listState.layoutInfo.viewportSize.height
            val step = when {
                current.pointerY < autoScrollEdge -> -AUTO_SCROLL_STEP
                current.pointerY > height - autoScrollEdge -> AUTO_SCROLL_STEP
                else -> 0f
            }
            if (step != 0f && current.moved && listState.scrollBy(step) != 0f) {
                drag = current.copy(target = targetAt(listState, current.pointerY, current, content.state))
            }
            delay(AUTO_SCROLL_FRAME_MS)
        }
    }

    val dropLines = drag?.takeIf { it.moved }?.target?.let { dropLinesFor(it) }
    val draggedKeys = drag?.let { current ->
        current.selection.tabIds.map { TAB_PREFIX + it } +
            current.selection.pinIds.map { PIN_PREFIX + it } +
            listOfNotNull(current.folderId?.let { PIN_PREFIX + it })
    }.orEmpty().toSet()
    val lineColor = MaterialTheme.colorScheme.primary
    val insideColor = MaterialTheme.colorScheme.primaryContainer

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            userScrollEnabled = !isDragging,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp)
                .pointerInput(workspace.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val key = itemKeyAt(listState, offset.y)
                            drag = key?.let { startDrag(it, offset.y, content, latestCallbacks) }
                            if (drag != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { change, amount ->
                            val current = drag ?: return@detectDragGesturesAfterLongPress
                            change.consume()
                            val y = current.pointerY + amount.y
                            val moved = current.moved || abs(y - current.startY) > touchSlop
                            val updated = current.copy(pointerY = y, moved = moved)
                            drag = updated.copy(target = if (moved) targetAt(listState, y, updated, content.state) else null)
                        },
                        onDragEnd = {
                            val current = drag
                            drag = null
                            val target = current?.target
                            when {
                                current == null -> Unit
                                current.moved && target != null ->
                                    latestCallbacks.onDrop(current.selection, current.folderId, target)
                                !current.moved && current.folderId != null -> menuFolderId = current.folderId
                            }
                        },
                        onDragCancel = { drag = null },
                    )
                },
        ) {
            item(key = KEY_HEADER) {
                WorkspaceHeader(
                    workspace = workspace,
                    container = workspace.containerId?.let { containers[it] },
                    canDelete = canDeleteWorkspace,
                    onNewFolder = callbacks.onNewFolder,
                    onNewWorkspace = callbacks.onNewWorkspace,
                    onEdit = callbacks.onEditWorkspace,
                    onDelete = callbacks.onDeleteWorkspace,
                    modifier = Modifier.dropLine(dropLines?.of(KEY_HEADER), lineColor, insideColor),
                )
            }

            items(pinned, key = { PIN_PREFIX + it.item.id }) { entry ->
                val item = entry.item
                val key = PIN_PREFIX + item.id
                val rowModifier = Modifier
                    .dropLine(dropLines?.of(key), lineColor, insideColor)
                    .alpha(if (key in draggedKeys) DRAGGED_ALPHA else 1f)
                if (item.isFolder) {
                    FolderRow(
                        folder = item,
                        depth = entry.depth,
                        childCount = state.pins.count { it.parentId == item.id },
                        canAddSubfolder = state.folderDepth(item.id) < MAX_FOLDER_DEPTH,
                        menuOpen = menuFolderId == item.id,
                        onDismissMenu = { menuFolderId = null },
                        onClick = { if (drag == null) callbacks.onFolderClick(item) },
                        onMenu = {
                            menuFolderId = null
                            callbacks.onFolderMenu(item, it)
                        },
                        modifier = rowModifier,
                    )
                } else {
                    val tab = item.tabId?.let { tabsById[it] }
                    val targets = ActionTargets(pins = listOf(item), pinnedTabs = listOfNotNull(tab))
                    TabRow(
                        title = tab?.displayTitle ?: item.title.ifBlank { item.url.orEmpty() },
                        url = tab?.content?.url ?: item.url.orEmpty(),
                        depth = entry.depth,
                        container = (tab?.contextId ?: item.containerId)?.let { containers[it] },
                        isOpen = tab != null,
                        isAwake = tab?.isAwake == true,
                        isCurrent = tab != null && tab.id == selectedTabId,
                        selection = selection?.let { item.id in it.pinIds },
                        actions = rowActions(pinnedRowActions, targets, isPinned = true),
                        onAction = { callbacks.onRowAction(it, targets) },
                        onClick = { if (drag == null) callbacks.onPinClick(item) },
                        modifier = rowModifier,
                    )
                }
            }

            item(key = KEY_DIVIDER) {
                SectionDivider(
                    showClear = otherTabs.isNotEmpty() && selection == null,
                    onClear = callbacks.onClearUnpinned,
                    modifier = Modifier.dropLine(dropLines?.of(KEY_DIVIDER), lineColor, insideColor),
                )
            }

            items(otherTabs, key = { TAB_PREFIX + it.id }) { tab ->
                val key = TAB_PREFIX + tab.id
                val targets = ActionTargets(tabs = listOf(tab))
                TabRow(
                    title = tab.displayTitle,
                    url = tab.content.url,
                    depth = 0,
                    container = tab.contextId?.let { containers[it] },
                    isOpen = true,
                    isAwake = tab.isAwake,
                    isCurrent = tab.id == selectedTabId,
                    selection = selection?.let { tab.id in it.tabIds },
                    actions = rowActions(unpinnedRowActions, targets, isPinned = false),
                    onAction = { callbacks.onRowAction(it, targets) },
                    onClick = { if (drag == null) callbacks.onTabClick(tab) },
                    modifier = Modifier
                        .dropLine(dropLines?.of(key), lineColor, insideColor)
                        .alpha(if (key in draggedKeys) DRAGGED_ALPHA else 1f),
                )
            }

            item(key = KEY_NEW_TAB) {
                NewTabRow(
                    enabled = selection == null,
                    containers = containers.values.toList(),
                    onClick = callbacks.onNewTabClick,
                    onNewTabInContainer = callbacks.onNewTabInContainer,
                    onManageContainers = callbacks.onManageContainers,
                    modifier = Modifier.dropLine(dropLines?.of(KEY_NEW_TAB), lineColor, insideColor),
                )
            }
        }

        drag?.takeIf { it.moved }?.let { current ->
            DragGhost(label = current.label, isFolder = current.folderId != null, pointerY = current.pointerY)
        }
    }
}

/** Starts dragging the row with [key]: a folder on its own, or a tab together with the other selected ones. */
private fun startDrag(key: String, y: Float, content: PageContent, callbacks: WorkspacePageCallbacks): DragState? =
    when {
        key.startsWith(PIN_PREFIX) -> {
            val pin = content.state.pins.firstOrNull { it.id == key.removePrefix(PIN_PREFIX) }
            when {
                pin == null -> null
                pin.isFolder -> DragState(Selection(), pin.id, pin.title, y, y)
                else -> {
                    val dragged = callbacks.onStartDrag(null, pin.id)
                    DragState(dragged, null, dragLabel(pin.title, dragged), y, y)
                }
            }
        }
        key.startsWith(TAB_PREFIX) -> content.otherTabs.firstOrNull { it.id == key.removePrefix(TAB_PREFIX) }?.let {
            val dragged = callbacks.onStartDrag(it.id, null)
            DragState(dragged, null, dragLabel(it.displayTitle, dragged), y, y)
        }
        else -> null
    }

private fun dragLabel(title: String, selection: Selection): String =
    if (selection.size > 1) "$title  +${selection.size - 1}" else title

/** Key of the list item under [y], relative to the list. */
private fun itemKeyAt(listState: LazyListState, y: Float): String? =
    listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.key as? String

/** Where the dragged items would land at [y], or `null` when they cannot go there. */
@Suppress("CyclomaticComplexMethod", "ReturnCount")
private fun targetAt(listState: LazyListState, y: Float, drag: DragState, state: WorkspaceState): DropTarget? {
    val items = listState.layoutInfo.visibleItemsInfo
    if (items.isEmpty()) return null
    val info = items.firstOrNull { y >= it.offset && y < it.offset + it.size }
        ?: if (y < items.first().offset) items.first() else items.last()
    val fraction = ((y - info.offset) / info.size.toFloat()).coerceIn(0f, 1f)
    val key = info.key as? String ?: return null
    val movingFolder = drag.folderId
    val blockedPins = drag.selection.pinIds + listOfNotNull(movingFolder) +
        (movingFolder?.let { state.descendantIds(it) } ?: emptySet())

    val target = when {
        key == KEY_HEADER -> DropTarget.PinnedEdge(atEnd = false)
        key == KEY_DIVIDER ->
            if (fraction < 0.5f) DropTarget.PinnedEdge(atEnd = true) else DropTarget.UnpinnedEdge(atEnd = false)
        key == KEY_NEW_TAB -> DropTarget.UnpinnedEdge(atEnd = true)
        key.startsWith(PIN_PREFIX) -> {
            val id = key.removePrefix(PIN_PREFIX)
            val item = state.pins.firstOrNull { it.id == id } ?: return null
            if (id in blockedPins) return null
            if (item.isFolder && fraction in INSIDE_FOLDER_RANGE) {
                DropTarget.IntoFolder(id)
            } else {
                DropTarget.NextToPin(id, after = fraction >= 0.5f)
            }
        }
        key.startsWith(TAB_PREFIX) -> {
            val id = key.removePrefix(TAB_PREFIX)
            if (id in drag.selection.tabIds) return null
            DropTarget.NextToTab(id, after = fraction >= 0.5f)
        }
        else -> null
    }
    val unpinsFolder = movingFolder != null && (target is DropTarget.NextToTab || target is DropTarget.UnpinnedEdge)
    return target?.takeUnless { unpinsFolder }
}

private fun dropLinesFor(target: DropTarget): DropLines = when (target) {
    is DropTarget.IntoFolder -> DropLines(PIN_PREFIX + target.folderId, DropLine.INSIDE)
    is DropTarget.NextToPin -> DropLines(PIN_PREFIX + target.pinId, if (target.after) DropLine.BOTTOM else DropLine.TOP)
    is DropTarget.PinnedEdge ->
        if (target.atEnd) DropLines(KEY_DIVIDER, DropLine.TOP) else DropLines(KEY_HEADER, DropLine.BOTTOM)
    is DropTarget.NextToTab -> DropLines(TAB_PREFIX + target.tabId, if (target.after) DropLine.BOTTOM else DropLine.TOP)
    is DropTarget.UnpinnedEdge ->
        if (target.atEnd) DropLines(KEY_NEW_TAB, DropLine.TOP) else DropLines(KEY_DIVIDER, DropLine.BOTTOM)
}

private fun Modifier.dropLine(line: DropLine?, lineColor: Color, insideColor: Color): Modifier =
    if (line == null) {
        this
    } else {
        drawWithContent {
            if (line == DropLine.INSIDE) {
                drawRoundRect(insideColor, cornerRadius = CornerRadius(16.dp.toPx()))
            }
            drawContent()
            val stroke = 3.dp.toPx()
            when (line) {
                DropLine.TOP -> drawLine(lineColor, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
                DropLine.BOTTOM -> drawLine(
                    lineColor,
                    Offset(0f, size.height - stroke / 2),
                    Offset(size.width, size.height - stroke / 2),
                    stroke,
                )
                DropLine.INSIDE -> Unit
            }
        }
    }

@Composable
private fun DragGhost(label: String, isFolder: Boolean, pointerY: Float) {
    val shape = RoundedCornerShape(14.dp)
    val halfHeight = with(LocalDensity.current) { 22.dp.toPx() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .offset { IntOffset(x = 32.dp.roundToPx(), y = (pointerY - halfHeight).roundToInt()) }
            .widthIn(max = 280.dp)
            .shadow(8.dp, shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(
            painter = painterResource(if (isFolder) iconsR.drawable.mozac_ic_folder_24 else iconsR.drawable.mozac_ic_tab_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The line between pinned and unpinned tabs, with Zen's "Clear" button for the unpinned ones. */
@Composable
private fun SectionDivider(
    showClear: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().height(44.dp).padding(start = 12.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        if (showClear) {
            TextButton(onClick = onClear) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_chevron_down_16),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.kaizen_clear_unpinned), style = MaterialTheme.typography.labelLarge)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/**
 * "New Tab" opens the search. Long-pressing it lists the containers to open the new tab in, and a shortcut to the
 * container settings.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun NewTabRow(
    enabled: Boolean,
    containers: List<ContainerRecord>,
    onClick: () -> Unit,
    onNewTabInContainer: (String?) -> Unit,
    onManageContainers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RowShape)
                .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_plus_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).padding(start = 3.dp),
            )
            Spacer(Modifier.width(19.dp))
            Text(
                text = stringResource(R.string.kaizen_new_tab),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Text(
                text = stringResource(R.string.kaizen_new_tab_in_container),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.kaizen_new_tab_no_container)) },
                leadingIcon = { NoContainerIcon() },
                onClick = {
                    menuOpen = false
                    onNewTabInContainer(null)
                },
            )
            if (containers.isNotEmpty()) HorizontalDivider()
            containers.forEach { container ->
                DropdownMenuItem(
                    text = { Text(container.name) },
                    leadingIcon = { ContainerIcon(container) },
                    onClick = {
                        menuOpen = false
                        onNewTabInContainer(container.contextId)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.kaizen_container_add)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(iconsR.drawable.mozac_ic_plus_24),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    menuOpen = false
                    onManageContainers()
                },
            )
        }
    }
}

/** Workspace name with its default container on the left; tapping the name edits the workspace. */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun WorkspaceHeader(
    workspace: Workspace,
    container: ContainerRecord?,
    canDelete: Boolean,
    onNewFolder: () -> Unit,
    onNewWorkspace: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onEdit)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (container != null) {
                ContainerIcon(container, size = 18.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = workspace.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = container?.color?.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SmallIconButton(iconsR.drawable.mozac_ic_folder_add_24, R.string.kaizen_action_new_folder, onNewFolder)
        Box {
            SmallIconButton(iconsR.drawable.mozac_ic_ellipsis_vertical_24, R.string.kaizen_workspace_menu) {
                menuOpen = true
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.kaizen_add_workspace)) },
                    onClick = {
                        menuOpen = false
                        onNewWorkspace()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.kaizen_workspace_edit)) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.kaizen_workspace_delete)) },
                    enabled = canDelete,
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun FolderRow(
    folder: PinnedItem,
    depth: Int,
    childCount: Int,
    canAddSubfolder: Boolean,
    menuOpen: Boolean,
    onDismissMenu: () -> Unit,
    onClick: () -> Unit,
    onMenu: (FolderMenuItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RowShape)
                .clickable(onClick = onClick)
                .padding(start = 12.dp + IndentPerLevel * depth, top = 12.dp, bottom = 12.dp, end = 12.dp),
        ) {
            Icon(
                painter = painterResource(
                    if (folder.collapsed) iconsR.drawable.mozac_ic_chevron_right_16 else iconsR.drawable.mozac_ic_chevron_down_16,
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_folder_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = folder.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (folder.collapsed && childCount > 0) {
                Text(
                    text = childCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
            FolderMenuItem.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(stringResource(item.label)) },
                    enabled = item != FolderMenuItem.NEW_SUBFOLDER || canAddSubfolder,
                    onClick = { onMenu(item) },
                )
            }
        }
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun TabRow(
    title: String,
    url: String,
    depth: Int,
    container: ContainerRecord?,
    isOpen: Boolean,
    isAwake: Boolean,
    isCurrent: Boolean,
    selection: Boolean?,
    actions: List<RowAction>,
    onAction: (RowAction) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = when {
        selection == true -> MaterialTheme.colorScheme.secondaryContainer
        isCurrent && selection == null -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> Color.Transparent
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(vertical = 2.dp)
            .clip(RowShape)
            .background(background)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .padding(start = 2.dp)
                .width(4.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(container?.color?.color ?: Color.Transparent),
        )
        Spacer(Modifier.width(6.dp + IndentPerLevel * depth))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .border(1.5.dp, if (isAwake) MaterialTheme.colorScheme.success else Color.Transparent, CircleShape)
                .alpha(if (isAwake) 1f else DIMMED_ALPHA),
        ) {
            Favicon(url = url, size = 20.dp, shape = CircleShape)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isOpen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selection == null) {
            actions.forEach { action ->
                IconButton(onClick = { onAction(action) }) {
                    Icon(
                        painter = painterResource(action.icon),
                        contentDescription = action.label,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        } else {
            SelectionMark(selected = selection)
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(end = 14.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .border(1.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
    ) {
        if (selected) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_checkmark_16),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun SmallIconButton(icon: Int, @StringRes description: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(description),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
