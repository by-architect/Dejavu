/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.state.ContainerState
import org.mozilla.fenix.R
import org.mozilla.fenix.kaizen.containers.ContainerColor
import org.mozilla.fenix.kaizen.containers.ContainerRecord
import org.mozilla.fenix.kaizen.containers.color
import org.mozilla.fenix.kaizen.containers.drawable

private const val ITEMS_PER_ROW = 5

/**
 * Creates a container, or edits [record] when it is not `null`.
 */
@Composable
fun ContainerEditorDialog(
    record: ContainerRecord?,
    onSave: (name: String, color: ContainerColor, icon: ContainerState.Icon) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(record?.name.orEmpty()) }
    var color by remember { mutableStateOf(record?.color ?: ContainerColor.BLUE) }
    var icon by remember { mutableStateOf(record?.icon ?: ContainerState.Icon.FINGERPRINT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (record == null) R.string.kaizen_container_add else R.string.kaizen_container_edit))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.kaizen_container_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.kaizen_container_color), style = MaterialTheme.typography.labelLarge)
                ContainerColor.entries.chunked(ITEMS_PER_ROW).forEach { row ->
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        row.forEach { option ->
                            ChoiceCircle(selected = option == color, onClick = { color = option }) {
                                Box(Modifier.size(22.dp).clip(CircleShape).background(option.color))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.kaizen_container_icon), style = MaterialTheme.typography.labelLarge)
                ContainerState.Icon.entries.chunked(ITEMS_PER_ROW).forEach { row ->
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        row.forEach { option ->
                            ChoiceCircle(selected = option == icon, onClick = { icon = option }) {
                                Icon(
                                    painter = painterResource(option.drawable),
                                    contentDescription = option.icon,
                                    tint = color.color,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        repeat(ITEMS_PER_ROW - row.size) { Spacer(Modifier.size(44.dp)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), color, icon) }) {
                Text(stringResource(R.string.kaizen_save))
            }
        },
        dismissButton = {
            Row {
                if (record != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.kaizen_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.kaizen_cancel)) }
            }
        },
    )
}

/** Asks before a container is deleted together with its tabs and site data. */
@Composable
fun DeleteContainerDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kaizen_container_delete_title, name)) },
        text = { Text(stringResource(R.string.kaizen_container_delete_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.kaizen_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.kaizen_cancel)) }
        },
    )
}

@Composable
private fun ChoiceCircle(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .border(2.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
    ) {
        content()
    }
}
