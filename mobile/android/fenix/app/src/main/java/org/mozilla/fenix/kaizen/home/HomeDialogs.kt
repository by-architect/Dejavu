/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.home

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mozilla.fenix.R
import org.mozilla.fenix.kaizen.containers.ContainerIcon
import org.mozilla.fenix.kaizen.containers.ContainerRecord
import org.mozilla.fenix.kaizen.containers.NoContainerIcon
import org.mozilla.fenix.kaizen.workspaces.MAX_FOLDER_DEPTH
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState
import mozilla.components.ui.icons.R as iconsR

/** Shows [dialog] and forwards the user's choice to [interactor]. */
@Suppress("LongMethod")
@Composable
internal fun HomeDialogs(
    dialog: HomeDialog,
    state: WorkspaceState,
    containers: Map<String, ContainerRecord>,
    interactor: KaizenHomeInteractor,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        is HomeDialog.EditWorkspace -> {
            val workspace = state.workspaces.firstOrNull { it.id == dialog.workspaceId }
            WorkspaceDialog(
                initialName = workspace?.name.orEmpty(),
                initialContainerId = workspace?.containerId,
                isNew = workspace == null,
                containers = containers.values.toList(),
                onSave = { name, containerId ->
                    interactor.onSaveWorkspace(workspace?.id, name, containerId)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        is HomeDialog.DeleteWorkspace -> {
            val workspace = state.workspaces.firstOrNull { it.id == dialog.workspaceId }
            ConfirmDialog(
                title = stringResource(R.string.kaizen_workspace_delete_title, workspace?.name.orEmpty()),
                message = stringResource(R.string.kaizen_workspace_delete_message),
                confirm = stringResource(R.string.kaizen_delete),
                onConfirm = {
                    workspace?.let { interactor.onDeleteWorkspace(it.id) }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        is HomeDialog.NewFolder -> NameDialog(
            title = R.string.kaizen_action_new_folder,
            initial = "",
            onConfirm = { name ->
                interactor.onCreateFolder(dialog.workspaceId, dialog.parentId, name, dialog.targets)
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        is HomeDialog.RenameFolder -> NameDialog(
            title = R.string.kaizen_folder_rename,
            initial = dialog.folder.title,
            onConfirm = { name ->
                interactor.onRenameFolder(dialog.folder.id, name)
                onDismiss()
            },
            onDismiss = onDismiss,
        )

        is HomeDialog.DeleteItems -> {
            val targets = dialog.targets
            val single = (targets.pins + targets.folders).singleOrNull()?.takeIf { targets.tabs.isEmpty() }
            val count = targets.pins.size + targets.folders.size + targets.tabs.size
            ConfirmDialog(
                title = if (single != null) {
                    stringResource(R.string.kaizen_delete_item_title, single.title)
                } else {
                    pluralStringResource(R.plurals.kaizen_delete_items_title, count, count)
                },
                message = stringResource(R.string.kaizen_delete_items_message),
                confirm = stringResource(R.string.kaizen_delete),
                onConfirm = {
                    interactor.onDeleteItems(targets)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }

        is HomeDialog.MoveToFolder -> {
            var naming by remember { mutableStateOf(false) }
            if (naming) {
                NameDialog(
                    title = R.string.kaizen_action_new_folder,
                    initial = "",
                    onConfirm = { name ->
                        interactor.onCreateFolder(dialog.workspaceId, null, name, dialog.targets)
                        onDismiss()
                    },
                    onDismiss = onDismiss,
                )
            } else {
                FolderPickerDialog(
                    state = state,
                    workspaceId = dialog.workspaceId,
                    movingFolderIds = dialog.targets.folders.map { it.id }.toSet(),
                    onPick = { folderId ->
                        interactor.onMoveToFolder(dialog.workspaceId, dialog.targets, folderId)
                        onDismiss()
                    },
                    onNewFolder = { naming = true },
                    onDismiss = onDismiss,
                )
            }
        }

        is HomeDialog.MoveToWorkspace -> PickerDialog(
            title = R.string.kaizen_action_move_to_workspace,
            onDismiss = onDismiss,
        ) {
            // Essentials can also go to the workspace they are looked at from, as its pinned tabs.
            val hasEssentials = dialog.targets.pins.any { it.essential }
            state.workspaces.filter { hasEssentials || it.id != dialog.workspaceId }.forEach { workspace ->
                PickerRow(
                    label = workspace.name,
                    leading = {
                        val container = workspace.containerId?.let { containers[it] }
                        if (container != null) ContainerIcon(container) else NoContainerIcon()
                    },
                    onClick = {
                        interactor.onMoveToWorkspace(dialog.targets, workspace.id)
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun WorkspaceDialog(
    initialName: String,
    initialContainerId: String?,
    isNew: Boolean,
    containers: List<ContainerRecord>,
    onSave: (name: String, containerId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var containerId by remember { mutableStateOf(initialContainerId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.kaizen_add_workspace else R.string.kaizen_workspace_edit)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.kaizen_workspace_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(16.dp))
                Text(stringResource(R.string.kaizen_workspace_container), style = MaterialTheme.typography.labelLarge)
                Text(
                    text = stringResource(R.string.kaizen_workspace_container_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RadioRow(
                    label = stringResource(R.string.kaizen_workspace_no_container),
                    selected = containerId == null,
                    onClick = { containerId = null },
                    leading = { NoContainerIcon() },
                )
                if (containers.isNotEmpty()) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                containers.forEach { container ->
                    RadioRow(
                        label = container.name,
                        selected = containerId == container.contextId,
                        onClick = { containerId = container.contextId },
                        leading = { ContainerIcon(container) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, containerId) }) {
                Text(stringResource(if (isNew) R.string.kaizen_create else R.string.kaizen_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.kaizen_cancel)) }
        },
    )
}

@Suppress("LongParameterList")
@Composable
private fun FolderPickerDialog(
    state: WorkspaceState,
    workspaceId: String,
    movingFolderIds: Set<String>,
    onPick: (String?) -> Unit,
    onNewFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val blocked = movingFolderIds + movingFolderIds.flatMap { state.descendantIds(it) }
    val tallest = movingFolderIds.maxOfOrNull { state.folderHeight(it) } ?: 0
    val folders = state.pinnedTree(workspaceId, expandAll = true).filter { entry ->
        entry.item.isFolder && entry.item.id !in blocked && state.folderDepth(entry.item.id) + tallest <= MAX_FOLDER_DEPTH
    }

    PickerDialog(title = R.string.kaizen_action_move_to_folder, onDismiss = onDismiss) {
        PickerRow(
            label = stringResource(R.string.kaizen_folder_top_level),
            leading = { PickerIcon(iconsR.drawable.mozac_ic_pin_24) },
            onClick = { onPick(null) },
        )
        folders.forEach { entry ->
            PickerRow(
                label = entry.item.title,
                depth = entry.depth,
                leading = { PickerIcon(iconsR.drawable.mozac_ic_folder_24) },
                onClick = { onPick(entry.item.id) },
            )
        }
        PickerRow(
            label = stringResource(R.string.kaizen_action_new_folder),
            leading = { PickerIcon(iconsR.drawable.mozac_ic_folder_add_24) },
            onClick = onNewFolder,
        )
    }
}

@Composable
private fun NameDialog(
    @StringRes title: Int,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.kaizen_workspace_name)) },
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) {
                Text(stringResource(R.string.kaizen_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.kaizen_cancel)) }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.kaizen_cancel)) }
        },
    )
}

@Composable
private fun PickerDialog(
    @StringRes title: Int,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                content()
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.kaizen_cancel)) }
        },
    )
}

@Composable
private fun PickerRow(
    label: String,
    onClick: () -> Unit,
    depth: Int = 0,
    leading: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp + 16.dp * depth, top = 12.dp, bottom = 12.dp, end = 8.dp),
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PickerIcon(icon: Int) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        leading()
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
