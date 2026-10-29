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
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialog
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.DejavuPopup
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.dejavu.workspaces.withoutStarters

/**
 * Asks which browser workspaces sync with, showing what the account has of each. When this device has workspaces or
 * tabs of its own, in [workspaces] and the open [tabs], it then asks whether to merge them with what is synced or to
 * start fresh. [onChoose] gets the choices, and [onDismiss] puts them off, or keeps the browser chosen before when
 * [status] has one.
 */
@Composable
fun SyncSourceDialog(
    status: DejavuSyncStatus,
    workspaces: WorkspaceState,
    tabs: List<TabSessionState>,
    onChoose: (DejavuSyncSource, SyncStart) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(status.source ?: status.found?.onlySource) }
    // The browser chosen, while asking what happens to this device's own workspaces and tabs.
    var starting by remember { mutableStateOf<DejavuSyncSource?>(null) }
    var start by remember { mutableStateOf(SyncStart.MERGE) }
    val defaultName = stringResource(R.string.dejavu_workspace_default_name)
    val own = remember(workspaces, tabs, defaultName) { OwnData.of(workspaces, tabs, defaultName) }
    val startingWith = starting
    val back = { starting = null }
    val askStart: (DejavuSyncSource) -> Unit = { starting = it }
    val startMerging: (DejavuSyncSource) -> Unit = { onChoose(it, SyncStart.MERGE) }
    val next = if (own != null) askStart else startMerging
    // One dialog for both steps, so that it stays in place when the step changes.
    DejavuDialog(
        title = stringResource(
            if (startingWith == null) R.string.dejavu_sync_choose_title else R.string.dejavu_sync_start_title,
        ),
        onDismissRequest = if (startingWith == null) onDismiss else back,
        icon = iconsR.drawable.mozac_ic_sync_24,
        buttons = {
            if (startingWith == null) {
                SourceButtons(status = status, selected = selected, onDismiss = onDismiss, onNext = next)
            } else {
                StartButtons(start = start, onBack = back, onStart = { onChoose(startingWith, start) })
            }
        },
    ) {
        if (startingWith == null) {
            SourceContent(status = status, selected = selected, onSelect = { selected = it })
        } else {
            SyncStartOptions(source = startingWith, own = own, start = start, onSelect = { start = it })
        }
    }
}

/** The buttons of the browser choice: put it off, or go on with [selected], which [onNext] gets. */
@Composable
private fun SourceButtons(
    status: DejavuSyncStatus,
    selected: DejavuSyncSource?,
    onDismiss: () -> Unit,
    onNext: (DejavuSyncSource) -> Unit,
) {
    val dismiss = if (status.source == null) R.string.dejavu_sync_choose_later else R.string.dejavu_cancel
    DejavuDialogButton(text = stringResource(dismiss), onClick = onDismiss)
    DejavuDialogButton(
        text = stringResource(chooseLabel(selected)),
        style = DejavuButtonStyle.Primary,
        enabled = selected != null && selected != status.source,
        onClick = { selected?.let(onNext) },
    )
}

/** The buttons of the merge or start fresh choice: back to the browsers, or start syncing as [start] says. */
@Composable
private fun StartButtons(start: SyncStart, onBack: () -> Unit, onStart: () -> Unit) {
    val fresh = start == SyncStart.FRESH
    DejavuDialogButton(text = stringResource(R.string.dejavu_back), onClick = onBack)
    DejavuDialogButton(
        text = stringResource(if (fresh) R.string.dejavu_sync_start_fresh else R.string.dejavu_sync_start_merge),
        style = if (fresh) DejavuButtonStyle.Danger else DejavuButtonStyle.Primary,
        onClick = onStart,
    )
}

/** The browser choice, with an introduction before the first choice. */
@Composable
private fun SourceContent(status: DejavuSyncStatus, selected: DejavuSyncSource?, onSelect: (DejavuSyncSource) -> Unit) {
    if (status.source == null) Text(stringResource(R.string.dejavu_sync_choose_intro))
    SyncSourceOptions(
        found = status.found,
        looking = status.syncing,
        current = status.source,
        selected = selected,
        onSelect = onSelect,
        modifier = Modifier.padding(top = if (status.source == null) 16.dp else 0.dp),
    )
}

/**
 * What this device has of its own, which starting to sync merges with what is synced, or removes for a fresh start:
 * its workspaces with their pinned tabs and folders, the essentials and the open tabs. The workspaces of devices on the
 * account and their tabs are left out, as they come from syncing with Firefox, and so are private tabs.
 */
internal data class OwnData(
    val workspaces: Int,
    val pinned: Int,
    val essentials: Int,
    val folders: Int,
    val tabs: Int,
) {
    companion object {
        /**
         * What [state] and the open [tabs] that are not private hold, or `null` when that is only the workspaces a
         * fresh Dejavu starts with, the one named [defaultName] and the examples as they came, so there is nothing to
         * merge.
         */
        fun of(state: WorkspaceState, tabs: List<TabSessionState>, defaultName: String): OwnData? {
            val own = state.withoutStarters()
            val workspaces = own.workspaces.filter { it.device == null }
            val workspaceIds = workspaces.map { it.id }.toSet()
            val pins = own.pins.filter { it.essential || it.workspaceId in workspaceIds }
            val open = tabs.filterNot { own.isDeviceWorkspace(own.workspaceOf(it.id)) }
            val untouched = workspaces.size <= 1 && workspaces.all { it.isLikeNew(defaultName) }
            if (untouched && pins.isEmpty() && open.isEmpty()) return null
            return OwnData(
                workspaces = workspaces.size,
                pinned = pins.count { !it.isFolder && !it.essential },
                essentials = pins.count { it.essential },
                folders = pins.count { it.isFolder },
                tabs = open.size,
            )
        }
    }
}

/** Merging or starting fresh with [source], with what this device has of its own, [own], when anything. */
@Composable
private fun SyncStartOptions(source: DejavuSyncSource, own: OwnData?, start: SyncStart, onSelect: (SyncStart) -> Unit) {
    val zen = source == DejavuSyncSource.ZEN
    val merge = if (zen) R.string.dejavu_sync_start_merge_zen else R.string.dejavu_sync_start_merge_firefox
    val fresh = if (zen) R.string.dejavu_sync_start_fresh_zen else R.string.dejavu_sync_start_fresh_firefox
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.dejavu_sync_start_intro))
        if (own != null) {
            Text(
                text = counts(
                    stringResource(R.string.dejavu_sync_found_workspaces, own.workspaces),
                    stringResource(R.string.dejavu_sync_found_pinned, own.pinned).takeIf { own.pinned > 0 },
                    stringResource(R.string.dejavu_sync_found_essentials, own.essentials).takeIf { own.essentials > 0 },
                    stringResource(R.string.dejavu_sync_found_folders, own.folders).takeIf { own.folders > 0 },
                    stringResource(R.string.dejavu_sync_found_tabs, own.tabs).takeIf { own.tabs > 0 },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        ChoiceOption(
            title = stringResource(R.string.dejavu_sync_start_merge),
            summary = stringResource(merge),
            selected = start == SyncStart.MERGE,
            onClick = { onSelect(SyncStart.MERGE) },
        )
        ChoiceOption(
            title = stringResource(R.string.dejavu_sync_start_fresh),
            summary = stringResource(fresh),
            selected = start == SyncStart.FRESH,
            onClick = { onSelect(SyncStart.FRESH) },
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
        ChoiceOption(
            title = stringResource(R.string.dejavu_sync_source_firefox),
            findings = found?.let { firefoxFindings(it.firefox) },
            summary = stringResource(R.string.dejavu_sync_firefox_summary),
            selected = selected == DejavuSyncSource.FIREFOX,
            onClick = { onSelect(DejavuSyncSource.FIREFOX) },
        )
        ChoiceOption(
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

/** One of the choices of the dialog, with a radio button: its [title], what was found of it, and what it does. */
@Composable
private fun ChoiceOption(
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    findings: Findings? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
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
