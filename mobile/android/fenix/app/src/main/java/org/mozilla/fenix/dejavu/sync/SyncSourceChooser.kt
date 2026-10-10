/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialog
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.DejavuPopup

/**
 * Asks which browser workspaces sync with, showing what the account has of each. [onChoose] gets the choice, and
 * [onDismiss] puts it off, or keeps the browser chosen before when [status] has one.
 */
@Composable
fun SyncSourceDialog(status: DejavuSyncStatus, onChoose: (DejavuSyncSource) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(status.source ?: status.found?.onlySource) }
    DejavuDialog(
        title = stringResource(R.string.dejavu_sync_choose_title),
        onDismissRequest = onDismiss,
        icon = iconsR.drawable.mozac_ic_sync_24,
        buttons = {
            val dismiss = if (status.source == null) R.string.dejavu_sync_choose_later else R.string.dejavu_cancel
            DejavuDialogButton(text = stringResource(dismiss), onClick = onDismiss)
            DejavuDialogButton(
                text = stringResource(chooseLabel(selected)),
                style = DejavuButtonStyle.Primary,
                enabled = selected != null && selected != status.source,
                onClick = { selected?.let(onChoose) },
            )
        },
    ) {
        if (status.source == null) Text(stringResource(R.string.dejavu_sync_choose_intro))
        SyncSourceOptions(
            found = status.found,
            looking = status.syncing,
            current = status.source,
            selected = selected,
            onSelect = { selected = it },
            modifier = Modifier.padding(top = if (status.source == null) 16.dp else 0.dp),
        )
    }
}

/** The text of the button that starts syncing with [source]. */
@StringRes
internal fun chooseLabel(source: DejavuSyncSource?): Int = when (source) {
    DejavuSyncSource.FIREFOX -> R.string.dejavu_sync_choose_firefox
    DejavuSyncSource.ZEN -> R.string.dejavu_sync_choose_zen
    null -> R.string.dejavu_sync_choose_start
}

/**
 * Firefox and Zen to choose from, each with what was found of it on the account, or that Dejavu is [looking]. When
 * [selected] is not [current], the browser chosen before, what switching does is explained below.
 */
@Composable
internal fun SyncSourceOptions(
    found: FoundSyncData?,
    looking: Boolean,
    current: DejavuSyncSource?,
    selected: DejavuSyncSource?,
    onSelect: (DejavuSyncSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (looking) {
            Text(
                text = stringResource(R.string.dejavu_sync_looking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SourceOption(
            title = stringResource(R.string.dejavu_sync_source_firefox),
            findings = found?.let { firefoxFindings(it.firefox) },
            summary = stringResource(R.string.dejavu_sync_firefox_summary),
            selected = selected == DejavuSyncSource.FIREFOX,
            onClick = { onSelect(DejavuSyncSource.FIREFOX) },
        )
        SourceOption(
            title = stringResource(R.string.dejavu_sync_source_zen),
            findings = found?.let { zenFindings(it.zen) },
            summary = stringResource(R.string.dejavu_sync_zen_summary),
            selected = selected == DejavuSyncSource.ZEN,
            onClick = { onSelect(DejavuSyncSource.ZEN) },
        )
        if (current != null && selected != null && selected != current) {
            Text(
                text = stringResource(
                    if (current == DejavuSyncSource.FIREFOX) {
                        R.string.dejavu_sync_switch_from_firefox
                    } else {
                        R.string.dejavu_sync_switch_from_zen
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * What was found of one browser.
 *
 * @property heading Whether and what was found.
 * @property found Whether anything was found.
 * @property items Each finding: what it is from, like a computer's name, and what it has.
 */
private class Findings(val heading: String, val found: Boolean, val items: List<Pair<String?, String>> = emptyList())

@Composable
private fun firefoxFindings(computers: List<FoundFirefox>): Findings {
    if (computers.isEmpty()) return Findings(stringResource(R.string.dejavu_sync_found_no_firefox), found = false)
    return Findings(
        heading = stringResource(R.string.dejavu_sync_found_firefox),
        found = true,
        items = computers.map { computer ->
            computer.name to counts(
                stringResource(R.string.dejavu_sync_found_tabs, computer.tabs),
                stringResource(R.string.dejavu_sync_found_pinned, computer.pinned).takeIf { computer.pinned > 0 },
                stringResource(R.string.dejavu_sync_found_groups, computer.groups).takeIf { computer.groups > 0 },
            )
        },
    )
}

@Composable
private fun zenFindings(zen: FoundZen?): Findings {
    if (zen == null) return Findings(stringResource(R.string.dejavu_sync_found_no_zen), found = false)
    val devices = zen.devices.joinToString(", ").ifEmpty { null }
    if (!zen.spacesSyncOn) {
        return Findings(
            heading = stringResource(R.string.dejavu_sync_found_zen_off),
            found = true,
            items = listOfNotNull(devices?.let { null to it }),
        )
    }
    return Findings(
        heading = stringResource(R.string.dejavu_sync_found_zen),
        found = true,
        items = listOf(
            devices to counts(
                stringResource(R.string.dejavu_sync_found_workspaces, zen.spaces),
                stringResource(R.string.dejavu_sync_found_pinned, zen.pinned).takeIf { zen.pinned > 0 },
                stringResource(R.string.dejavu_sync_found_essentials, zen.essentials).takeIf { zen.essentials > 0 },
                stringResource(R.string.dejavu_sync_found_folders, zen.folders).takeIf { zen.folders > 0 },
            ),
        ),
    )
}

/** [parts] on one line, each kept whole so that lines only break between them. */
private fun counts(vararg parts: String?): String =
    parts.filterNotNull().joinToString(" · ") { it.replace(' ', ' ') }

@Composable
private fun SourceOption(title: String, findings: Findings?, summary: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DejavuPopup.ItemShape)
            .border(1.dp, if (selected) scheme.primary else DejavuPopup.edgeColor, DejavuPopup.ItemShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 4.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
            if (findings != null) {
                Text(
                    text = findings.heading,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (findings.found) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                findings.items.forEach { (name, details) ->
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        if (name != null) {
                            Text(text = name, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                        }
                        Text(text = details, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
