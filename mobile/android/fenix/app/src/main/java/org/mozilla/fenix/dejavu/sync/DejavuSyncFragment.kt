/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.fragment.findNavController
import mozilla.components.compose.base.button.FilledButton
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.components.accounts.FenixFxAEntryPoint
import org.mozilla.fenix.compose.list.IconListItem
import org.mozilla.fenix.compose.list.SwitchListItem
import org.mozilla.fenix.dejavu.settings.DejavuComposeFragment
import org.mozilla.fenix.dejavu.settings.settingsCard

/** Turns syncing workspaces with Zen on and off, shows how the last sync went, and syncs on demand. */
class DejavuSyncFragment : DejavuComposeFragment(R.string.dejavu_settings_sync) {
    @Composable
    override fun DejavuScreen() {
        val status by DejavuSync.status.collectAsState()

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Hint(stringResource(R.string.dejavu_sync_hint))
            if (status.signedIn) {
                SwitchListItem(
                    label = stringResource(R.string.dejavu_sync_enabled),
                    checked = status.enabled,
                    showSwitchAfter = true,
                    modifier = Modifier.settingsCard(index = 0, count = 3),
                    onClick = { DejavuSync.setEnabled(requireContext(), it) },
                )
                SwitchListItem(
                    label = stringResource(R.string.dejavu_sync_normal_tabs),
                    description = stringResource(R.string.dejavu_sync_normal_tabs_summary),
                    maxDescriptionLines = 4,
                    checked = status.normalTabs,
                    enabled = status.enabled,
                    showSwitchAfter = true,
                    modifier = Modifier.settingsCard(index = 1, count = 3),
                    onClick = { DejavuSync.setNormalTabs(requireContext(), it) },
                )
                IconListItem(
                    label = stringResource(if (status.syncing) R.string.dejavu_sync_syncing else R.string.dejavu_sync_now),
                    description = statusText(status),
                    maxDescriptionLines = 4,
                    enabled = status.enabled && !status.syncing,
                    beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_sync_24),
                    modifier = Modifier.settingsCard(index = 2, count = 3),
                    onClick = DejavuSync::syncNow,
                )
                if (status.enabled && status.problem == DejavuSyncProblem.NOT_TURNED_ON) {
                    FilledButton(
                        text = stringResource(R.string.dejavu_sync_turn_on),
                        enabled = !status.syncing,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        onClick = DejavuSync::turnOn,
                    )
                }
            } else {
                Hint(stringResource(R.string.dejavu_sync_signed_out))
                FilledButton(
                    text = stringResource(R.string.dejavu_sync_sign_in),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    onClick = {
                        findNavController().navigate(
                            NavGraphDirections.actionGlobalTurnOnSync(entrypoint = FenixFxAEntryPoint.SettingsMenu),
                        )
                    },
                )
            }
            Hint(stringResource(R.string.dejavu_sync_zen_hint))
        }
    }

    @Composable
    private fun Hint(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }

    @Composable
    private fun statusText(status: DejavuSyncStatus): String {
        val problem = status.problem
        return when {
            !status.enabled -> stringResource(R.string.dejavu_sync_off)
            problem != null -> stringResource(problemText(problem))
            status.lastSynced > 0 -> stringResource(
                R.string.dejavu_sync_last_synced,
                DateUtils.getRelativeTimeSpanString(
                    status.lastSynced,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString(),
            )
            else -> stringResource(R.string.dejavu_sync_never_synced)
        }
    }

    private fun problemText(problem: DejavuSyncProblem): Int = when (problem) {
        DejavuSyncProblem.NOT_SET_UP -> R.string.dejavu_sync_problem_not_set_up
        DejavuSyncProblem.NOT_TURNED_ON -> R.string.dejavu_sync_problem_not_turned_on
        DejavuSyncProblem.NEEDS_UPDATE -> R.string.dejavu_sync_problem_needs_update
        DejavuSyncProblem.SIGN_IN_AGAIN -> R.string.dejavu_sync_problem_sign_in
        DejavuSyncProblem.OFFLINE -> R.string.dejavu_sync_problem_offline
        DejavuSyncProblem.SERVER -> R.string.dejavu_sync_problem_server
    }
}
