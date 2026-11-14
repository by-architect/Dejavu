/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.backup

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.compose.base.utils.toLocaleString
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.IconListItem
import org.mozilla.fenix.dejavu.settings.DejavuComposeFragment
import org.mozilla.fenix.dejavu.settings.SettingsHeader
import org.mozilla.fenix.dejavu.settings.SettingsHint
import org.mozilla.fenix.dejavu.settings.dejavuSettings
import org.mozilla.fenix.dejavu.settings.settingsCard
import org.mozilla.fenix.dejavu.sync.DejavuSync
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialog
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.ext.components

/**
 * Backs Dejavu up to a file and restores it, and brings the workspaces of a Zen profile in, see [DejavuBackups] and
 * [ZenImporter]. Saving, restoring and bringing in carry on when the screen closes meanwhile.
 */
class DejavuBackupFragment : DejavuComposeFragment(R.string.dejavu_settings_backup) {
    @Composable
    override fun DejavuScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val settings = remember { dejavuSettings() }
        val customActions by settings.customActions.collectAsState()
        val syncStatus by DejavuSync.status.collectAsState()
        var busy by remember { mutableStateOf(false) }
        var confirmingBackup by remember { mutableStateOf(false) }
        var restoring by remember { mutableStateOf<PendingRestore?>(null) }
        var importing by remember { mutableStateOf<PendingImport?>(null) }

        val createFile = remember { ActivityResultContracts.CreateDocument(BackupZip.MIME_TYPE) }
        val saver = rememberLauncherForActivityResult(createFile) { uri ->
            if (uri != null) {
                busy = true
                context.runInApp(onEnd = { busy = false }, failed = R.string.dejavu_backup_save_failed) { app ->
                    val saved = DejavuBackups(app).write(uri)
                    if (saved) R.string.dejavu_backup_saved else R.string.dejavu_backup_save_failed
                }
            }
        }
        val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                busy = true
                scope.launch {
                    when (val read = DejavuBackups(context).read(uri)) {
                        is BackupRead.Found -> restoring = PendingRestore(uri, read.backup)
                        BackupRead.NotBackup -> toast(context, R.string.dejavu_backup_bad_file)
                        BackupRead.TooNew -> toast(context, R.string.dejavu_backup_too_new)
                    }
                    busy = false
                }
            }
        }
        val zenPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                busy = true
                scope.launch {
                    val importer = ZenImporter(context)
                    val profile = importer.read(uris)
                    val summary = profile?.let { importer.preview(it) }
                    if (profile != null && summary != null && summary.workspaces > 0) {
                        importing = PendingImport(profile, summary)
                    } else {
                        toast(context, R.string.dejavu_backup_zen_bad_file)
                    }
                    busy = false
                }
            }
        }
        val backUp = { launchSafely { saver.launch(BackupZip.name(System.currentTimeMillis())) } }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsHint(stringResource(R.string.dejavu_backup_hint))
            IconListItem(
                label = stringResource(R.string.dejavu_backup_save),
                beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_save_file_24),
                enabled = !busy,
                modifier = Modifier.settingsCard(index = 0, count = 2),
                onClick = {
                    if (customActions.isEmpty()) {
                        backUp()
                    } else {
                        confirmingBackup = true
                    }
                },
            )
            IconListItem(
                label = stringResource(R.string.dejavu_backup_restore),
                beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_arrow_counter_clockwise_24),
                enabled = !busy,
                modifier = Modifier.settingsCard(index = 1, count = 2),
                onClick = { launchSafely { opener.launch(BackupZip.OPEN_TYPES) } },
            )
            SettingsHint(stringResource(R.string.dejavu_backup_private_note))
            SettingsHeader(stringResource(R.string.dejavu_sync_source_zen))
            IconListItem(
                label = stringResource(R.string.dejavu_backup_zen_restore),
                beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_import_data_24),
                enabled = !busy,
                modifier = Modifier.settingsCard(index = 0, count = 1),
                onClick = { launchSafely { zenPicker.launch(ZenFiles.OPEN_TYPES) } },
            )
            SettingsHint(stringResource(R.string.dejavu_backup_zen_hint))
            if (busy) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }

        if (confirmingBackup) {
            PrivateBackupDialog(
                onBackUp = {
                    confirmingBackup = false
                    backUp()
                },
                onDismiss = { confirmingBackup = false },
            )
        }
        restoring?.let { pending ->
            RestoreDialog(
                backup = pending.backup,
                syncsZen = syncStatus.syncsZen,
                onRestore = {
                    restoring = null
                    busy = true
                    context.runInApp(onEnd = { busy = false }, failed = R.string.dejavu_backup_restore_failed) { app ->
                        val complete = DejavuBackups(app).restore(pending.uri, pending.backup)
                        if (complete) R.string.dejavu_backup_restored else R.string.dejavu_backup_restore_failed
                    }
                },
                onDismiss = { restoring = null },
            )
        }
        importing?.let { pending ->
            ZenDialog(
                summary = pending.summary,
                syncsZen = syncStatus.syncsZen,
                onImport = { includeUnpinned, replace ->
                    importing = null
                    busy = true
                    context.runInApp(onEnd = { busy = false }, failed = R.string.dejavu_backup_zen_failed) { app ->
                        ZenImporter(app).import(pending.profile, includeUnpinned, replace)
                        R.string.dejavu_backup_zen_restored
                    }
                },
                onDismiss = { importing = null },
            )
        }
    }
}

/** A backup read from the file at [uri], waiting for the user to restore it. */
private class PendingRestore(val uri: Uri, val backup: DejavuBackup)

/** The files of a Zen profile and what they bring, waiting for the user to bring them in. */
private class PendingImport(val profile: ZenProfile, val summary: ZenImport.Summary)

/** Tells that a backup holds the custom actions with their tokens, before saving one. */
@Composable
private fun PrivateBackupDialog(onBackUp: () -> Unit, onDismiss: () -> Unit) {
    DejavuDialog(
        title = stringResource(R.string.dejavu_backup_save),
        onDismissRequest = onDismiss,
        icon = iconsR.drawable.mozac_ic_lock_24,
        buttons = {
            DejavuDialogButton(text = stringResource(R.string.dejavu_cancel), onClick = onDismiss)
            DejavuDialogButton(
                text = stringResource(R.string.dejavu_backup_save_button),
                onClick = onBackUp,
                style = DejavuButtonStyle.Primary,
            )
        },
    ) {
        Text(stringResource(R.string.dejavu_backup_private_confirm))
    }
}

/** What restoring [backup] brings back and replaces, for the user to confirm it. */
@Composable
private fun RestoreDialog(backup: DejavuBackup, syncsZen: Boolean, onRestore: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val summary = remember(backup) { backup.summary }
    val date = remember(backup) { DateUtils.formatDateTime(context, backup.createdAt, DATE_FLAGS) }
    DejavuDialog(
        title = stringResource(R.string.dejavu_backup_restore),
        onDismissRequest = onDismiss,
        icon = iconsR.drawable.mozac_ic_arrow_counter_clockwise_24,
        buttons = {
            DejavuDialogButton(text = stringResource(R.string.dejavu_cancel), onClick = onDismiss)
            DejavuDialogButton(
                text = stringResource(R.string.dejavu_backup_restore_button),
                onClick = onRestore,
                style = DejavuButtonStyle.Primary,
            )
        },
    ) {
        Text(stringResource(R.string.dejavu_backup_from, date), modifier = Modifier.padding(bottom = 8.dp))
        CountRows(
            listOf(
                R.string.dejavu_backup_workspaces to summary.workspaces,
                R.string.dejavu_settings_pinned_tabs to summary.pinnedTabs,
                R.string.dejavu_sidebery_essentials to summary.essentials,
                R.string.dejavu_settings_folders to summary.folders,
                R.string.dejavu_settings_containers to summary.containers,
                R.string.dejavu_backup_open_tabs to summary.tabs,
            ),
        )
        Note(stringResource(R.string.dejavu_backup_restore_replaces))
        if (syncsZen) Note(stringResource(R.string.dejavu_backup_sync_note))
    }
}

/**
 * What restoring from Zen brings, for the user to add it next to the workspaces here or to put it in their place, with
 * Zen's unpinned tabs or without them.
 */
@Composable
private fun ZenDialog(
    summary: ZenImport.Summary,
    syncsZen: Boolean,
    onImport: (includeUnpinned: Boolean, replace: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var unpinned by remember { mutableStateOf(false) }
    DejavuDialog(
        title = stringResource(R.string.dejavu_backup_zen_restore),
        onDismissRequest = onDismiss,
        icon = iconsR.drawable.mozac_ic_import_data_24,
        buttons = {
            DejavuDialogButton(text = stringResource(R.string.dejavu_cancel), onClick = onDismiss)
            DejavuDialogButton(
                text = stringResource(R.string.dejavu_backup_zen_replace),
                onClick = { onImport(unpinned, true) },
            )
            DejavuDialogButton(
                text = stringResource(R.string.dejavu_backup_zen_add),
                onClick = { onImport(unpinned, false) },
                style = DejavuButtonStyle.Primary,
            )
        },
    ) {
        CountRows(
            listOf(
                R.string.dejavu_backup_workspaces to summary.workspaces,
                R.string.dejavu_settings_pinned_tabs to summary.pinnedTabs,
                R.string.dejavu_sidebery_essentials to summary.essentials,
                R.string.dejavu_settings_folders to summary.folders,
                R.string.dejavu_settings_containers to summary.containers,
            ),
        )
        if (summary.unpinnedTabs > 0) {
            UnpinnedSwitch(count = summary.unpinnedTabs, checked = unpinned, onChange = { unpinned = it })
        }
        Note(stringResource(R.string.dejavu_backup_zen_choice))
        if (summary.leftOutContainers > 0) Note(stringResource(R.string.dejavu_backup_zen_left_out))
        if (syncsZen) Note(stringResource(R.string.dejavu_backup_sync_note))
    }
}

/** Whether Zen's [count] unpinned tabs come along too. */
@Composable
private fun UnpinnedSwitch(count: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.dejavu_backup_zen_unpinned),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.dejavu_backup_zen_unpinned_count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A row for each kind of item there is some of: its label, a string, and how many there are. */
@Composable
private fun CountRows(counts: List<Pair<Int, Int>>) {
    counts.filter { (_, count) -> count > 0 }.forEach { (label, count) ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(count.toLocaleString(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/** A line of smaller text below the rows of a dialog. */
@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * Runs [work] in the app's scope rather than the screen's, so closing the screen does not cut it short, then shows the
 * message [work] gives, or [failed] when it fails, and calls [onEnd].
 */
private fun Context.runInApp(onEnd: () -> Unit, @StringRes failed: Int, work: suspend (Context) -> Int) {
    val app = applicationContext
    app.components.applicationScope.launch {
        val message = runCatching { work(app) }.getOrElse { error ->
            Logger.warn("Backing up or restoring failed", error)
            failed
        }
        withContext(Dispatchers.Main) {
            onEnd()
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
    }
}

/** Runs [launch], which opens the system's file picker, unless the phone has none. */
private inline fun launchSafely(launch: () -> Unit) {
    try {
        launch()
    } catch (e: ActivityNotFoundException) {
        Logger.warn("No file picker to save to or open from", e)
    }
}

private fun toast(context: Context, @StringRes text: Int) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

private val DATE_FLAGS = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR
