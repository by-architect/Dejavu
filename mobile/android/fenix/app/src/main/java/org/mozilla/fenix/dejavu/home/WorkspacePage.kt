/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import androidx.annotation.StringRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.actions.RowAction
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.actions.icon
import org.mozilla.fenix.dejavu.actions.label
import org.mozilla.fenix.dejavu.containers.ContainerIcon
import org.mozilla.fenix.dejavu.containers.ContainerPick
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.containers.NoContainerIcon
import org.mozilla.fenix.dejavu.containers.TemporaryContainerIcon
import org.mozilla.fenix.dejavu.containers.color
import org.mozilla.fenix.dejavu.sync.workspaceIconText
import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.Workspace
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState

private val RowShape = RoundedCornerShape(16.dp)
private val IndentPerLevel = 18.dp
private val AutoScrollEdge = 64.dp
private const val AUTO_SCROLL_STEP = 14f
private const val AUTO_SCROLL_FRAME_MS = 16L
private const val DIMMED_ALPHA = 0.55f
private const val RESET_SQUARE_ALPHA = 0.14f
private val IconSquareSize = 36.dp
private val MinTouchSize = 48.dp
private const val DRAGGED_ALPHA = 0.35f
private val INSIDE_FOLDER_RANGE = 0.25f..0.75f

private const val KEY_HEADER = "header"
private const val KEY_DIVIDER = "divider"
private const val KEY_NEW_TAB = "new_tab"
private const val PIN_PREFIX = "pin:"
private const val TAB_PREFIX = "tab:"

/** Callbacks of [WorkspacePage]. */
data class WorkspacePageCallbacks(
    val onTabClick: (TabSessionState) -> Unit,
    val onPinClick: (PinnedItem) -> Unit,
    /** A long press on a row adds [picked] to the selection; returns the whole selection a drag starting now moves. */
    val onStartDrag: (picked: Selection) -> Selection,
    /** Drops the dragged [Selection] on a [DropTarget]. */
    val onDrop: (selection: Selection, target: DropTarget) -> Unit,
    /** Tells whether the drag in progress can be dropped into the essentials, above the page. */
    val onEssentialsDrop: (EssentialsDrop) -> Unit,
    val onRowAction: (RowAction, ActionTargets) -> Unit,
    val onFolderClick: (PinnedItem) -> Unit,
    val onNewFolder: () -> Unit,
    val onNewWorkspace: () -> Unit,
    val onEditWorkspace: () -> Unit,
    val onDeleteWorkspace: () -> Unit,
    /** Moves the workspace [delta] places, negative to the left. */
    val onMoveWorkspace: (delta: Int) -> Unit,
    val onNewTabClick: () -> Unit,
    val onNewTabInContainer: (ContainerPick) -> Unit,
    val onNewPrivateTab: () -> Unit,
    val onManageContainers: () -> Unit,
    val onClearUnpinned: () -> Unit,
)

/**
 * What is being dragged and where it would land.
 *
 * @property selection Tabs, pinned tabs and folders being dragged.
 * @property label Text of the floating row that follows the finger.
 * @property isFolder Whether the long-pressed row is a folder.
 * @property pointerY Finger position, relative to the list.
 * @property startY Where the long press happened.
 * @property moved Whether the finger moved enough to be a drag rather than a long press.
 * @property target Where the items would be dropped, or `null` when that position is not allowed.
 */
private data class DragState(
    val selection: Selection,
    val label: String,
    val isFolder: Boolean,
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
    folderRowActions: List<RowAction>,
    selection: Selection?,
    callbacks: WorkspacePageCallbacks,
    canDeleteWorkspace: Boolean,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
) {
    val tabsById = tabs.associateBy { it.id }
    val awakeTabIds = tabs.filter { it.isAwake }.map { it.id }.toSet()
    val pinned = state.pinnedTree(workspace.id)
    val pinnedTabIds = state.pins.mapNotNull { it.tabId }.toSet()
    val otherTabs = tabs.filterNot { it.id in pinnedTabIds }
    val splitTabIds = state.splits.flatMap { it.tabIds }.toSet()

    val listState = rememberLazyListState()
    var drag by remember { mutableStateOf<DragState?>(null) }
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
                current.pointerY < 0f -> 0f
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
        val folderIds = current.selection.folderIds
        current.selection.tabIds.map { TAB_PREFIX + it } +
            (current.selection.pinIds + folderIds + folderIds.flatMap { state.descendantIds(it) }).map { PIN_PREFIX + it }
    }.orEmpty().toSet()
    val essentialsDrop = drag.let { current ->
        when {
            current == null || !current.moved || current.selection.folderIds.isNotEmpty() -> EssentialsDrop.NONE
            current.target == DropTarget.Essentials -> EssentialsDrop.ACTIVE
            else -> EssentialsDrop.AVAILABLE
        }
    }
    LaunchedEffect(essentialsDrop) { latestCallbacks.onEssentialsDrop(essentialsDrop) }
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
                            if (current != null && current.moved && target != null) {
                                latestCallbacks.onDrop(current.selection, target)
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
                    canMoveLeft = canMoveLeft,
                    canMoveRight = canMoveRight,
                    onNewFolder = callbacks.onNewFolder,
                    onNewWorkspace = callbacks.onNewWorkspace,
                    onEdit = callbacks.onEditWorkspace,
                    onDelete = callbacks.onDeleteWorkspace,
                    onMove = callbacks.onMoveWorkspace,
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
                    val targets = ActionTargets.of(state, tabs, Selection(folderIds = setOf(item.id)))
                    FolderRow(
                        folder = item,
                        depth = entry.depth,
                        childCount = state.pins.count { it.parentId == item.id },
                        openTabCount = if (item.collapsed) state.openTabsIn(item.id, awakeTabIds) else 0,
                        selection = selection?.let { item.id in it.folderIds },
                        actions = folderRowActions.filter { it.appliesTo(targets) },
                        onAction = { callbacks.onRowAction(it, targets) },
                        onClick = { if (drag == null) callbacks.onFolderClick(item) },
                        modifier = rowModifier,
                    )
                } else {
                    val tab = item.tabId?.let { tabsById[it] }
                    val targets = ActionTargets.ofPin(item, tab)
                        .copy(splitTabIds = setOfNotNull(tab?.id).intersect(splitTabIds))
                    val resetPin = RowAction.BuiltIn(TabAction.RESET_PIN).takeIf { it.appliesTo(targets) }
                    TabRow(
                        title = item.label(tab),
                        url = tab?.content?.url ?: item.pageUrl,
                        depth = entry.depth,
                        container = (tab?.contextId ?: item.containerId)?.let { containers[it] },
                        isAwake = tab?.isAwake == true,
                        isCurrent = tab != null && tab.id == selectedTabId,
                        isSplit = tab != null && tab.id in splitTabIds,
                        selection = selection?.let { item.id in it.pinIds },
                        actions = rowActions(pinnedRowActions, targets, isPinned = true),
                        onAction = { callbacks.onRowAction(it, targets) },
                        onClick = { if (drag == null) callbacks.onPinClick(item) },
                        onIconClick = resetPin?.let { action -> { if (drag == null) callbacks.onRowAction(action, targets) } },
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

            item(key = KEY_NEW_TAB) {
                NewTabRow(
                    enabled = selection == null,
                    containers = containers.values.toList(),
                    onClick = callbacks.onNewTabClick,
                    onNewTabInContainer = callbacks.onNewTabInContainer,
                    onNewPrivateTab = callbacks.onNewPrivateTab,
                    onManageContainers = callbacks.onManageContainers,
                    modifier = Modifier.dropLine(dropLines?.of(KEY_NEW_TAB), lineColor, insideColor),
                )
            }

            items(otherTabs, key = { TAB_PREFIX + it.id }) { tab ->
                val key = TAB_PREFIX + tab.id
                val targets = ActionTargets(tabs = listOf(tab), splitTabIds = setOf(tab.id).intersect(splitTabIds))
                TabRow(
                    title = state.titleOf(tab),
                    url = tab.content.url,
                    depth = 0,
                    container = tab.contextId?.let { containers[it] },
                    isAwake = tab.isAwake,
                    isCurrent = tab.id == selectedTabId,
                    isSplit = tab.id in splitTabIds,
                    selection = selection?.let { tab.id in it.tabIds },
                    actions = rowActions(unpinnedRowActions, targets, isPinned = false),
                    onAction = { callbacks.onRowAction(it, targets) },
                    onClick = { if (drag == null) callbacks.onTabClick(tab) },
                    modifier = Modifier
                        .dropLine(dropLines?.of(key), lineColor, insideColor)
                        .alpha(if (key in draggedKeys) DRAGGED_ALPHA else 1f),
                )
            }
        }

        drag?.takeIf { it.moved }?.let { current ->
            DragGhost(label = current.label, isFolder = current.isFolder, pointerY = current.pointerY)
        }
    }
}

/** Selects the row with [key] and starts dragging it together with the rest of the selection. */
private fun startDrag(key: String, y: Float, content: PageContent, callbacks: WorkspacePageCallbacks): DragState? {
    val picked: Selection
    val title: String
    val isFolder: Boolean
    when {
        key.startsWith(PIN_PREFIX) -> {
            val pin = content.state.pins.firstOrNull { it.id == key.removePrefix(PIN_PREFIX) } ?: return null
            picked = if (pin.isFolder) Selection(folderIds = setOf(pin.id)) else Selection(pinIds = setOf(pin.id))
            title = pin.title
            isFolder = pin.isFolder
        }
        key.startsWith(TAB_PREFIX) -> {
            val tab = content.otherTabs.firstOrNull { it.id == key.removePrefix(TAB_PREFIX) } ?: return null
            picked = Selection(tabIds = setOf(tab.id))
            title = tab.displayTitle
            isFolder = false
        }
        else -> return null
    }
    val dragged = callbacks.onStartDrag(picked)
    return DragState(dragged, dragLabel(title, dragged), isFolder, y, y)
}

private fun dragLabel(title: String, selection: Selection): String =
    if (selection.size > 1) "$title  +${selection.size - 1}" else title

/** Key of the list item under [y], relative to the list. */
private fun itemKeyAt(listState: LazyListState, y: Float): String? =
    listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.key as? String

/**
 * Where the dragged items would land at [y], or `null` when they cannot go there. Above the list lie the essentials,
 * which take tabs but no folders.
 */
@Suppress("CyclomaticComplexMethod", "ReturnCount")
private fun targetAt(listState: LazyListState, y: Float, drag: DragState, state: WorkspaceState): DropTarget? {
    val movingFolders = drag.selection.folderIds
    if (y < 0f) return DropTarget.Essentials.takeIf { movingFolders.isEmpty() }
    val items = listState.layoutInfo.visibleItemsInfo
    if (items.isEmpty()) return null
    val info = items.firstOrNull { y >= it.offset && y < it.offset + it.size }
        ?: if (y < items.first().offset) items.first() else items.last()
    val fraction = ((y - info.offset) / info.size.toFloat()).coerceIn(0f, 1f)
    val key = info.key as? String ?: return null
    val blockedPins = drag.selection.pinIds + movingFolders + movingFolders.flatMap { state.descendantIds(it) }

    val target = when {
        key == KEY_HEADER -> DropTarget.PinnedEdge(atEnd = false)
        key == KEY_DIVIDER -> DropTarget.PinnedEdge(atEnd = true)
        key == KEY_NEW_TAB -> DropTarget.UnpinnedStart
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
    val unpinsFolder = movingFolders.isNotEmpty() &&
        (target is DropTarget.NextToTab || target == DropTarget.UnpinnedStart)
    return target?.takeUnless { unpinsFolder }
}

private fun dropLinesFor(target: DropTarget): DropLines? = when (target) {
    is DropTarget.IntoFolder -> DropLines(PIN_PREFIX + target.folderId, DropLine.INSIDE)
    is DropTarget.NextToPin -> DropLines(PIN_PREFIX + target.pinId, if (target.after) DropLine.BOTTOM else DropLine.TOP)
    is DropTarget.PinnedEdge ->
        if (target.atEnd) DropLines(KEY_DIVIDER, DropLine.TOP) else DropLines(KEY_HEADER, DropLine.BOTTOM)
    is DropTarget.NextToTab -> DropLines(TAB_PREFIX + target.tabId, if (target.after) DropLine.BOTTOM else DropLine.TOP)
    DropTarget.UnpinnedStart -> DropLines(KEY_NEW_TAB, DropLine.BOTTOM)
    DropTarget.Essentials -> null
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
            TextButton(
                onClick = onClear,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_chevron_down_16),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.dejavu_clear_unpinned), style = MaterialTheme.typography.labelLarge)
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
    onNewTabInContainer: (ContainerPick) -> Unit,
    onNewPrivateTab: () -> Unit,
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
                text = stringResource(R.string.dejavu_new_tab),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            fun pick(choice: ContainerPick) {
                menuOpen = false
                onNewTabInContainer(choice)
            }
            val (temporary, permanent) = containers.partition { it.temporary }
            Text(
                text = stringResource(R.string.dejavu_new_tab_in_container),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.dejavu_new_tab_no_container)) },
                leadingIcon = { NoContainerIcon() },
                onClick = { pick(ContainerPick.NoContainer) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.dejavu_new_tab_temporary_container)) },
                leadingIcon = { TemporaryContainerIcon() },
                onClick = { pick(ContainerPick.Temporary) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.dejavu_new_private_tab)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(iconsR.drawable.mozac_ic_private_mode_24),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    menuOpen = false
                    onNewPrivateTab()
                },
            )
            if (permanent.isNotEmpty()) HorizontalDivider()
            permanent.forEach { container ->
                DropdownMenuItem(
                    text = { Text(container.name) },
                    leadingIcon = { ContainerIcon(container) },
                    onClick = { pick(ContainerPick.Container(container.contextId)) },
                )
            }
            if (temporary.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.dejavu_open_temporary_containers),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                temporary.sortedWith(compareBy({ it.name.length }, { it.name })).forEach { container ->
                    DropdownMenuItem(
                        text = { Text(container.name) },
                        leadingIcon = { ContainerIcon(container) },
                        onClick = { pick(ContainerPick.Container(container.contextId)) },
                    )
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.dejavu_container_add)) },
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

/** Workspace name with its default container and icon on the left; tapping the name edits the workspace. */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun WorkspaceHeader(
    workspace: Workspace,
    container: ContainerRecord?,
    canDelete: Boolean,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onNewFolder: () -> Unit,
    onNewWorkspace: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMove: (delta: Int) -> Unit,
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
            workspaceIconText(workspace.icon)?.let { icon ->
                Text(text = icon, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(6.dp))
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
        SmallIconButton(iconsR.drawable.mozac_ic_folder_add_24, R.string.dejavu_action_new_folder, onNewFolder)
        Box {
            SmallIconButton(iconsR.drawable.mozac_ic_ellipsis_vertical_24, R.string.dejavu_workspace_menu) {
                menuOpen = true
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.dejavu_add_workspace)) },
                    onClick = {
                        menuOpen = false
                        onNewWorkspace()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.dejavu_workspace_edit)) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.dejavu_workspace_move_left)) },
                    enabled = canMoveLeft,
                    onClick = {
                        menuOpen = false
                        onMove(-1)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.dejavu_workspace_move_right)) },
                    enabled = canMoveRight,
                    onClick = {
                        menuOpen = false
                        onMove(1)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.dejavu_workspace_delete)) },
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

/**
 * How many pinned tabs inside folder [folderId], subfolders included, are open and awake, their tab among
 * [awakeTabIds]. Sleeping tabs are not counted, the same as closed ones.
 */
private fun WorkspaceState.openTabsIn(folderId: String, awakeTabIds: Set<String>): Int {
    val inside = descendantIds(folderId)
    return pins.count { it.id in inside && it.tabId != null && it.tabId in awakeTabIds }
}

/**
 * A folder of the pinned section. Tapping it folds it, or selects it in selection mode. A closed folder shows
 * [openTabCount], the number of awake tabs hidden inside it, next to its icon. The buttons of [actions] act on
 * everything inside the folder; without any, a closed folder shows how many items it holds instead.
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
private fun FolderRow(
    folder: PinnedItem,
    depth: Int,
    childCount: Int,
    openTabCount: Int,
    selection: Boolean?,
    actions: List<RowAction>,
    onAction: (RowAction) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(vertical = 2.dp)
            .clip(RowShape)
            .background(if (selection == true) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 12.dp + IndentPerLevel * depth),
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
        if (openTabCount > 0) {
            val description = pluralStringResource(R.plurals.dejavu_folder_open_tabs, openTabCount, openTabCount)
            Spacer(Modifier.width(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .semantics { contentDescription = description }
                    .heightIn(min = 18.dp)
                    .widthIn(min = 18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 5.dp),
            ) {
                Text(
                    text = openTabCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = folder.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selection != null) {
            SelectionMark(selected = selection)
        } else if (actions.isNotEmpty()) {
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
        } else if (folder.collapsed && childCount > 0) {
            Text(
                text = childCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 14.dp),
            )
        }
    }
}

@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun TabRow(
    title: String,
    url: String,
    depth: Int,
    container: ContainerRecord?,
    isAwake: Boolean,
    isCurrent: Boolean,
    isSplit: Boolean,
    selection: Boolean?,
    actions: List<RowAction>,
    onAction: (RowAction) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onIconClick: (() -> Unit)? = null,
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
        Spacer(Modifier.width(2.dp + IndentPerLevel * depth))
        // Like Zen, a pinned tab that left its pinned page shows its icon on a brighter square; tapping it goes back.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(IconSquareSize)) {
            val interactions = remember { MutableInteractionSource() }
            val resetLabel = stringResource(R.string.dejavu_action_reset_pin)
            if (onIconClick != null && selection == null) {
                // The tap area gets the 48dp minimum without moving the square or the title.
                Box(
                    modifier = Modifier
                        .requiredSize(MinTouchSize)
                        .clickable(interactionSource = interactions, indication = null, onClick = onIconClick)
                        .semantics { contentDescription = resetLabel },
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(IconSquareSize)
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (onIconClick != null) {
                            Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = RESET_SQUARE_ALPHA))
                        } else {
                            Modifier
                        },
                    )
                    .indication(interactions, LocalIndication.current),
            ) {
                // Tabs loaded in the browser are shown bright, the others dimmed.
                Box(modifier = Modifier.alpha(if (isAwake) 1f else DIMMED_ALPHA)) {
                    SiteIcon(url = url, size = 20.dp)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isAwake) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isSplit) {
            Icon(
                painter = painterResource(R.drawable.dejavu_ic_split_24),
                contentDescription = stringResource(R.string.dejavu_split_view),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp).size(16.dp),
            )
        }
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
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(description),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
