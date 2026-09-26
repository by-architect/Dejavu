/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.compose.base.theme.success
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.Favicon
import org.mozilla.fenix.kaizen.actions.TabAction
import org.mozilla.fenix.kaizen.containers.ContainerIcon
import org.mozilla.fenix.kaizen.containers.ContainerRecord
import org.mozilla.fenix.kaizen.containers.color
import org.mozilla.fenix.kaizen.workspaces.MAX_FOLDER_DEPTH
import org.mozilla.fenix.kaizen.workspaces.PinnedItem
import org.mozilla.fenix.kaizen.workspaces.Workspace
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState
import mozilla.components.ui.icons.R as iconsR

private val RowShape = RoundedCornerShape(16.dp)
private val IndentPerLevel = 18.dp

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
    val onTabLongClick: (TabSessionState) -> Unit,
    val onPinClick: (PinnedItem) -> Unit,
    val onPinLongClick: (PinnedItem) -> Unit,
    val onRowAction: (TabAction, ActionTargets) -> Unit,
    val onFolderClick: (PinnedItem) -> Unit,
    val onFolderMenu: (PinnedItem, FolderMenuItem) -> Unit,
    val onNewFolder: () -> Unit,
    val onEditWorkspace: () -> Unit,
    val onDeleteWorkspace: () -> Unit,
    val onNewTabClick: () -> Unit,
)

@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun WorkspacePage(
    state: WorkspaceState,
    workspace: Workspace,
    tabs: List<TabSessionState>,
    selectedTabId: String?,
    containers: Map<String, ContainerRecord>,
    pinnedRowActions: List<TabAction>,
    unpinnedRowActions: List<TabAction>,
    selection: Selection?,
    callbacks: WorkspacePageCallbacks,
    canDeleteWorkspace: Boolean,
) {
    val tabsById = tabs.associateBy { it.id }
    val pinned = state.pinnedTree(workspace.id)
    val pinnedTabIds = state.pins.mapNotNull { it.tabId }.toSet()
    val otherTabs = tabs.filterNot { it.id in pinnedTabIds }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        item(key = "header") {
            WorkspaceHeader(
                workspace = workspace,
                container = workspace.containerId?.let { containers[it] },
                canDelete = canDeleteWorkspace,
                onNewFolder = callbacks.onNewFolder,
                onEdit = callbacks.onEditWorkspace,
                onDelete = callbacks.onDeleteWorkspace,
            )
        }

        items(pinned, key = { "pin-${it.item.id}" }) { entry ->
            val item = entry.item
            if (item.isFolder) {
                FolderRow(
                    folder = item,
                    depth = entry.depth,
                    childCount = state.pins.count { it.parentId == item.id },
                    canAddSubfolder = state.folderDepth(item.id) < MAX_FOLDER_DEPTH,
                    onClick = { callbacks.onFolderClick(item) },
                    onMenu = { callbacks.onFolderMenu(item, it) },
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
                    onClick = { callbacks.onPinClick(item) },
                    onLongClick = { callbacks.onPinLongClick(item) },
                )
            }
        }

        if (pinned.isNotEmpty()) {
            item(key = "pinned-divider") {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }

        items(otherTabs, key = { it.id }) { tab ->
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
                onClick = { callbacks.onTabClick(tab) },
                onLongClick = { callbacks.onTabLongClick(tab) },
            )
        }

        item(key = "new_tab") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RowShape)
                    .clickable(enabled = selection == null, onClick = callbacks.onNewTabClick)
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
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun WorkspaceHeader(
    workspace: Workspace,
    container: ContainerRecord?,
    canDelete: Boolean,
    onNewFolder: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
    ) {
        if (container != null) {
            ContainerIcon(container, size = 16.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = workspace.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        SmallIconButton(iconsR.drawable.mozac_ic_folder_add_24, R.string.kaizen_action_new_folder, onNewFolder)
        Box {
            SmallIconButton(iconsR.drawable.mozac_ic_ellipsis_vertical_24, R.string.kaizen_workspace_menu) {
                menuOpen = true
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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

@Composable
private fun FolderRow(
    folder: PinnedItem,
    depth: Int,
    childCount: Int,
    canAddSubfolder: Boolean,
    onClick: () -> Unit,
    onMenu: (FolderMenuItem) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RowShape)
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
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
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            FolderMenuItem.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(stringResource(item.label)) },
                    enabled = item != FolderMenuItem.NEW_SUBFOLDER || canAddSubfolder,
                    onClick = {
                        menuOpen = false
                        onMenu(item)
                    },
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
    actions: List<TabAction>,
    onAction: (TabAction) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val background = when {
        selection == true -> MaterialTheme.colorScheme.secondaryContainer
        isCurrent && selection == null -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> Color.Transparent
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(vertical = 2.dp)
            .clip(RowShape)
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 4.dp + IndentPerLevel * depth),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(container?.color?.color ?: Color.Transparent),
        )
        Spacer(Modifier.width(5.dp))
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
                        contentDescription = stringResource(action.label),
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

private const val DIMMED_ALPHA = 0.55f
