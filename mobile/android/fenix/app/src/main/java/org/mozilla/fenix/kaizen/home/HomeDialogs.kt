/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mozilla.fenix.R
import org.mozilla.fenix.kaizen.containers.ContainerIcon
import org.mozilla.fenix.kaizen.containers.ContainerRecord
import org.mozilla.fenix.kaizen.containers.NoContainerIcon
import org.mozilla.fenix.kaizen.workspaces.MAX_FOLDER_DEPTH
import org.mozilla.fenix.kaizen.workspaces.Workspace
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState
import org.mozilla.fenix.kaizen.workspaces.WorkspaceTheme
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
                workspace = workspace,
                containers = containers.values.toList(),
                onSave = { name, containerId, icon, theme ->
                    interactor.onSaveWorkspace(workspace?.id, name, containerId, icon, theme)
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

        is HomeDialog.ChangeContainer -> {
            val current = dialog.targets.containerIds.singleOrNull()
            val allInNoContainer = dialog.targets.containerIds == setOf(null)
            fun pick(contextId: String?) {
                interactor.onChangeContainer(dialog.targets, contextId)
                onDismiss()
            }
            PickerDialog(title = R.string.kaizen_action_change_container, onDismiss = onDismiss) {
                PickerRow(
                    label = stringResource(R.string.kaizen_workspace_no_container),
                    selected = allInNoContainer,
                    leading = { NoContainerIcon() },
                    onClick = { pick(null) },
                )
                if (containers.isNotEmpty()) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                containers.values.forEach { container ->
                    PickerRow(
                        label = container.name,
                        selected = current == container.contextId,
                        leading = { ContainerIcon(container) },
                        onClick = { pick(container.contextId) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                PickerRow(
                    label = stringResource(R.string.kaizen_container_add),
                    leading = { PickerIcon(iconsR.drawable.mozac_ic_plus_24) },
                    onClick = {
                        onDismiss()
                        interactor.onManageContainers()
                    },
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

/** Creates or edits a workspace: its icon, name, theme and default container. */
@Suppress("LongMethod")
@Composable
private fun WorkspaceDialog(
    workspace: Workspace?,
    containers: List<ContainerRecord>,
    onSave: (name: String, containerId: String?, icon: String?, theme: WorkspaceTheme?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(workspace?.name.orEmpty()) }
    var containerId by remember { mutableStateOf(workspace?.containerId) }
    var icon by remember { mutableStateOf(workspace?.icon) }
    var theme by remember { mutableStateOf(workspace?.theme) }
    val isNew = workspace == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.kaizen_add_workspace else R.string.kaizen_workspace_edit)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = icon.orEmpty(),
                        onValueChange = { icon = lastGrapheme(it) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.kaizen_workspace_icon)) },
                        textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
                        modifier = Modifier.width(76.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.kaizen_workspace_name)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.size(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item { IconChoice(text = null, selected = icon == null, onClick = { icon = null }) }
                    items(iconSuggestions) { suggestion ->
                        IconChoice(text = suggestion, selected = icon == suggestion, onClick = { icon = suggestion })
                    }
                }

                Spacer(Modifier.size(16.dp))
                Text(stringResource(R.string.kaizen_workspace_theme), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.size(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { ThemeChoice(colors = null, selected = theme == null, onClick = { theme = null }) }
                    items(themePresets) { colors ->
                        ThemeChoice(
                            colors = colors,
                            selected = theme?.colors == colors,
                            onClick = {
                                theme = WorkspaceTheme(
                                    colors = colors,
                                    opacity = theme?.opacity ?: WorkspaceTheme.DEFAULT_OPACITY,
                                    texture = theme?.texture ?: 0f,
                                )
                            },
                        )
                    }
                }
                theme?.let { current ->
                    SliderRow(R.string.kaizen_workspace_theme_intensity, current.opacity, MIN_THEME_OPACITY..1f) {
                        theme = current.copy(opacity = it)
                    }
                    SliderRow(R.string.kaizen_workspace_theme_grain, current.texture, 0f..1f) {
                        theme = current.copy(texture = it)
                    }
                }

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
            TextButton(onClick = { onSave(name, containerId, icon, theme) }) {
                Text(stringResource(if (isNew) R.string.kaizen_create else R.string.kaizen_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.kaizen_cancel)) }
        },
    )
}

private const val MIN_THEME_OPACITY = 0.1f

/** A choice of workspace icon: an emoji, or no icon when [text] is `null`. */
@Composable
private fun IconChoice(text: String?, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
    ) {
        if (text == null) {
            NoContainerIcon(size = 20.dp)
        } else {
            Text(text = text, fontSize = 20.sp)
        }
    }
}

/** A choice of workspace theme: a gradient of [colors], or no theme when it is `null`. */
@Composable
private fun ThemeChoice(colors: List<Int>?, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .then(
                if (colors == null) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
                } else {
                    Modifier.background(themeBrush(colors.map { Color(it) }, opacity = 1f))
                },
            )
            .clickable(onClick = onClick),
    ) {
        if (colors == null) NoContainerIcon(size = 18.dp)
    }
}

@Composable
private fun SliderRow(
    @StringRes label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(88.dp),
        )
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
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
    selected: Boolean = false,
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
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_checkmark_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
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
