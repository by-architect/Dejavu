/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.state.TabSessionState
import org.mozilla.fenix.compose.Favicon
import org.mozilla.fenix.kaizen.containers.ContainerRecord
import org.mozilla.fenix.kaizen.containers.color
import org.mozilla.fenix.kaizen.workspaces.PinnedItem
import mozilla.components.ui.icons.R as iconsR

private val TileHeight = 52.dp
private val TileGap = 8.dp
private val MinTileWidth = 64.dp
private val TileShape = RoundedCornerShape(14.dp)
private const val MIN_COLUMNS = 3
private const val DIMMED_ALPHA = 0.55f

/**
 * An essential being reordered.
 *
 * @property pinId The long-pressed essential.
 * @property start Where the long press happened, relative to the grid.
 * @property pointer Finger position, relative to the grid.
 * @property moved Whether the finger moved enough to be a drag rather than a long press.
 * @property index Position the essential would be dropped at.
 */
private data class EssentialDrag(
    val pinId: String,
    val start: Offset,
    val pointer: Offset,
    val moved: Boolean,
    val index: Int,
)

/**
 * The essentials every workspace shares, as a grid of icons like in Zen. Long-pressing one selects it; keeping the
 * finger down and moving reorders it.
 *
 * @param isDropTarget Whether tabs dragged on the workspace page would be dropped here.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun EssentialsGrid(
    essentials: List<PinnedItem>,
    tabsById: Map<String, TabSessionState>,
    selectedTabId: String?,
    containers: Map<String, ContainerRecord>,
    selection: Selection?,
    isDropTarget: Boolean,
    onClick: (PinnedItem) -> Unit,
    onStartDrag: (PinnedItem) -> Unit,
    onMove: (pinId: String, index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var drag by remember { mutableStateOf<EssentialDrag?>(null) }
    val latestEssentials by rememberUpdatedState(essentials)
    val latestOnStartDrag by rememberUpdatedState(onStartDrag)
    val latestOnMove by rememberUpdatedState(onMove)
    val haptics = LocalHapticFeedback.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val density = LocalDensity.current
    val shape = RoundedCornerShape(18.dp)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(shape)
            .border(2.dp, if (isDropTarget) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .padding(4.dp),
    ) {
        val columns = ((maxWidth + TileGap) / (MinTileWidth + TileGap)).toInt().coerceAtLeast(MIN_COLUMNS)
        val tileWidth = (maxWidth - TileGap * (columns - 1)) / columns
        val cellWidth = with(density) { (tileWidth + TileGap).toPx() }
        val cellHeight = with(density) { (TileHeight + TileGap).toPx() }

        fun indexAt(position: Offset): Int {
            val column = (position.x / cellWidth).toInt().coerceIn(0, columns - 1)
            val row = (position.y / cellHeight).toInt().coerceAtLeast(0)
            return (row * columns + column).coerceIn(0, (latestEssentials.size - 1).coerceAtLeast(0))
        }

        val current = drag
        val ordered = if (current != null && current.moved) {
            val dragged = essentials.firstOrNull { it.id == current.pinId }
            if (dragged == null) {
                essentials
            } else {
                (essentials - dragged).toMutableList().apply { add(current.index.coerceIn(0, size), dragged) }
            }
        } else {
            essentials
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(TileGap),
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(columns, cellWidth, cellHeight) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val column = (offset.x / cellWidth).toInt()
                            val index = (offset.y / cellHeight).toInt() * columns + column
                            val item = latestEssentials.getOrNull(index)?.takeIf { column < columns }
                            if (item != null) {
                                drag = EssentialDrag(item.id, offset, offset, moved = false, index = index)
                                latestOnStartDrag(item)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { change, amount ->
                            val dragging = drag ?: return@detectDragGesturesAfterLongPress
                            change.consume()
                            val pointer = dragging.pointer + amount
                            val moved = dragging.moved || (pointer - dragging.start).getDistance() > touchSlop
                            drag = dragging.copy(
                                pointer = pointer,
                                moved = moved,
                                index = if (moved) indexAt(pointer) else dragging.index,
                            )
                        },
                        onDragEnd = {
                            val dragging = drag
                            drag = null
                            if (dragging != null && dragging.moved) latestOnMove(dragging.pinId, dragging.index)
                        },
                        onDragCancel = { drag = null },
                    )
                },
        ) {
            ordered.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                    row.forEach { item ->
                        val tab = item.tabId?.let { tabsById[it] }
                        EssentialTile(
                            item = item,
                            tab = tab,
                            container = (tab?.contextId ?: item.containerId)?.let { containers[it] },
                            isCurrent = tab != null && tab.id == selectedTabId,
                            selection = selection?.let { item.id in it.pinIds },
                            isDragged = current?.moved == true && current.pinId == item.id,
                            onClick = { if (drag == null) onClick(item) },
                            modifier = Modifier.width(tileWidth),
                        )
                    }
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun EssentialTile(
    item: PinnedItem,
    tab: TabSessionState?,
    container: ContainerRecord?,
    isCurrent: Boolean,
    selection: Boolean?,
    isDragged: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = item.label(tab)
    val isAwake = tab?.isAwake == true
    val background = when {
        selection == true -> MaterialTheme.colorScheme.secondaryContainer
        isCurrent && selection == null -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(TileHeight)
            .clip(TileShape)
            .background(background)
            .border(2.dp, if (isDragged) MaterialTheme.colorScheme.primary else Color.Transparent, TileShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = title },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .alpha(if (isAwake) 1f else DIMMED_ALPHA),
        ) {
            Favicon(url = tab?.content?.url ?: item.url.orEmpty(), size = 22.dp, shape = CircleShape)
        }
        if (container != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp)
                    .width(16.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(container.color.color),
            )
        }
        if (selection == true) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            ) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_checkmark_16),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
