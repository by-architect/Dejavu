/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.SwitchListItem
import org.mozilla.fenix.compose.settings.SettingsSectionHeader
import org.mozilla.fenix.dejavu.menu.MoreMenu
import org.mozilla.fenix.dejavu.menu.MoreMenuEntry
import org.mozilla.fenix.dejavu.menu.MoreMenuLayout
import org.mozilla.fenix.dejavu.menu.MoreMenuState

/** Arranges the buttons of the browser's "More" menu in rows. */
class DejavuMoreMenuFragment : DejavuComposeFragment(R.string.dejavu_settings_more_menu) {
    @Composable
    override fun DejavuScreen() {
        val settings = remember { dejavuSettings() }
        val savedRows by settings.moreMenuRows.collectAsState()
        val customActions by settings.customActions.collectAsState()
        val editHidden by settings.moreMenuEditHidden.collectAsState()
        val rows = remember(savedRows, customActions) {
            savedRows.map { row -> row.filter { MoreMenuLayout.entryOf(it, customActions) != null } }.filter { it.isNotEmpty() }
        }
        var adding by remember { mutableStateOf<AddTarget?>(null) }
        val editor = RowsEditor(rows, settings::setMoreMenuRows)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = stringResource(R.string.dejavu_more_menu_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                SwitchListItem(
                    label = stringResource(R.string.dejavu_more_menu_hide_edit),
                    description = stringResource(R.string.dejavu_more_menu_hide_edit_summary),
                    maxDescriptionLines = 2,
                    checked = editHidden,
                    showSwitchAfter = true,
                    modifier = Modifier.settingsCard(index = 0, count = 1),
                    onClick = settings::setMoreMenuEditHidden,
                )
            }
            item {
                SettingsSectionHeader(
                    text = stringResource(R.string.dejavu_more_menu_preview),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Box(
                    modifier = Modifier
                        .padding(16.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(8.dp),
                ) {
                    MoreMenu(
                        rows = MoreMenuLayout.resolve(rows, customActions),
                        state = PREVIEW_STATE,
                        extensions = emptyList(),
                        extensionsExpanded = false,
                        onToggleExtensions = {},
                        onEntryClick = {},
                        onManageExtensions = {},
                        onEdit = {}.takeUnless { editHidden },
                    )
                }
            }
            items(rows.indices.toList(), key = { index -> rows[index].joinToString() + index }) { index ->
                MenuRowEditor(
                    index = index,
                    entries = rows[index].mapNotNull { MoreMenuLayout.entryOf(it, customActions) },
                    editor = editor,
                    onAdd = { adding = AddTarget.IntoRow(index) },
                )
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    OutlinedButton(onClick = { adding = AddTarget.NewRow }) {
                        Text(stringResource(R.string.dejavu_more_menu_add_row))
                    }
                    TextButton(onClick = settings::resetMoreMenu) {
                        Text(stringResource(R.string.dejavu_more_menu_reset))
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        adding?.let { target ->
            val used = rows.flatten().toSet()
            EntryPicker(
                entries = MoreMenuLayout.available(customActions).filter { it.key !in used },
                onPick = { entry ->
                    when (target) {
                        is AddTarget.IntoRow -> editor.add(target.row, entry.key)
                        AddTarget.NewRow -> editor.addRow(entry.key)
                    }
                    adding = null
                },
                onDismiss = { adding = null },
            )
        }
    }

    private sealed interface AddTarget {
        data class IntoRow(val row: Int) : AddTarget
        data object NewRow : AddTarget
    }

    private companion object {
        val PREVIEW_STATE = MoreMenuState(
            canGoBack = true,
            canGoForward = true,
            hasExternalApp = true,
            canTranslate = true,
            canSummarize = true,
            isWebPage = true,
        )
    }
}

/** Changes to the rows of the "More" menu, saved with [save]. */
private class RowsEditor(private val rows: List<List<String>>, private val save: (List<List<String>>) -> Unit) {
    fun hasRoom(row: Int): Boolean {
        val keys = rows.getOrNull(row) ?: return false
        return keys.size < MoreMenuLayout.MAX_PER_ROW && keys.none(MoreMenuLayout::isFullRow)
    }

    fun add(row: Int, key: String) {
        val updated = rows.toMutableList()
        if (MoreMenuLayout.isFullRow(key) || !hasRoom(row)) {
            updated.add(row + 1, listOf(key))
        } else {
            updated[row] = rows[row] + key
        }
        save(updated)
    }

    fun addRow(key: String) = save(rows + listOf(listOf(key)))

    fun remove(row: Int, item: Int) = save(rows.mapIndexed { index, keys -> if (index == row) keys - keys[item] else keys })

    fun moveItem(row: Int, item: Int, delta: Int) {
        val keys = rows[row].toMutableList()
        val target = item + delta
        if (target !in keys.indices) return
        keys.add(target, keys.removeAt(item))
        save(rows.mapIndexed { index, old -> if (index == row) keys else old })
    }

    /** Moves an item to the end of the row [delta] rows away, if that row has room for it. */
    fun moveToRow(row: Int, item: Int, delta: Int) {
        val target = row + delta
        if (!hasRoom(target) || MoreMenuLayout.isFullRow(rows[row][item])) return
        val key = rows[row][item]
        save(
            rows.mapIndexed { index, keys ->
                when (index) {
                    row -> keys - key
                    target -> keys + key
                    else -> keys
                }
            },
        )
    }

    fun moveRow(row: Int, delta: Int) {
        val target = row + delta
        if (target !in rows.indices) return
        val updated = rows.toMutableList()
        updated.add(target, updated.removeAt(row))
        save(updated)
    }

    fun deleteRow(row: Int) = save(rows.filterIndexed { index, _ -> index != row })

    val size: Int get() = rows.size
}

@Composable
private fun MenuRowEditor(
    index: Int,
    entries: List<MoreMenuEntry>,
    editor: RowsEditor,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.dejavu_more_menu_row, index + 1),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { editor.moveRow(index, -1) }, enabled = index > 0) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_chevron_up_24),
                    contentDescription = stringResource(R.string.dejavu_more_menu_move_up),
                )
            }
            IconButton(onClick = { editor.moveRow(index, 1) }, enabled = index < editor.size - 1) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_chevron_down_24),
                    contentDescription = stringResource(R.string.dejavu_more_menu_move_down),
                )
            }
            IconButton(onClick = { editor.deleteRow(index) }) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_delete_24),
                    contentDescription = stringResource(R.string.dejavu_more_menu_delete_row),
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(end = 12.dp),
        ) {
            entries.forEachIndexed { item, entry ->
                EntryChip(
                    entry = entry,
                    canMoveLeft = item > 0,
                    canMoveRight = item < entries.size - 1,
                    canMoveUp = editor.hasRoom(index - 1) && !MoreMenuLayout.isFullRow(entry.key),
                    canMoveDown = editor.hasRoom(index + 1) && !MoreMenuLayout.isFullRow(entry.key),
                    onMove = { delta -> editor.moveItem(index, item, delta) },
                    onMoveToRow = { delta -> editor.moveToRow(index, item, delta) },
                    onRemove = { editor.remove(index, item) },
                )
            }
            if (editor.hasRoom(index)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onAdd)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(
                        painter = painterResource(iconsR.drawable.mozac_ic_plus_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.dejavu_more_menu_add_item),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun EntryChip(
    entry: MoreMenuEntry,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onMoveToRow: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable { menuOpen = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(
                painter = painterResource(entry.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val close = { menuOpen = false }
            ChipOption(R.string.dejavu_more_menu_move_left, canMoveLeft, close) { onMove(-1) }
            ChipOption(R.string.dejavu_more_menu_move_right, canMoveRight, close) { onMove(1) }
            ChipOption(R.string.dejavu_more_menu_move_up, canMoveUp, close) { onMoveToRow(-1) }
            ChipOption(R.string.dejavu_more_menu_move_down, canMoveDown, close) { onMoveToRow(1) }
            ChipOption(R.string.dejavu_more_menu_remove, enabled = true, close = close, onClick = onRemove)
        }
    }
}

@Composable
private fun ChipOption(@StringRes label: Int, enabled: Boolean, close: () -> Unit, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        enabled = enabled,
        onClick = {
            close()
            onClick()
        },
    )
}

@Composable
private fun EntryPicker(
    entries: List<MoreMenuEntry>,
    onPick: (MoreMenuEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dejavu_more_menu_add_item)) },
        text = {
            if (entries.isEmpty()) {
                Text(stringResource(R.string.dejavu_more_menu_all_added))
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    entries.forEach { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(entry) }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                        ) {
                            Icon(
                                painter = painterResource(entry.icon),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = entry.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private val MoreMenuEntry.icon: Int
    get() = when (this) {
        is MoreMenuEntry.BuiltIn -> item.icon
        is MoreMenuEntry.Custom -> iconsR.drawable.mozac_ic_lightning_24
    }

private val MoreMenuEntry.label: String
    @Composable get() = when (this) {
        is MoreMenuEntry.BuiltIn -> stringResource(item.label)
        is MoreMenuEntry.Custom -> action.name
    }
