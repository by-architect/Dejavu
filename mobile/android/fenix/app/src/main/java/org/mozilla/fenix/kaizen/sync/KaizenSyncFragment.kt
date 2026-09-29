/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.sync

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
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.components.accounts.FenixFxAEntryPoint
import org.mozilla.fenix.compose.list.IconListItem
import org.mozilla.fenix.compose.list.SwitchListItem
import org.mozilla.fenix.kaizen.settings.KaizenComposeFragment
import org.mozilla.fenix.kaizen.settings.settingsCard
import mozilla.components.ui.icons.R as iconsR

/** Turns syncing workspaces with Zen on and off, shows how the last sync went, and syncs on demand. */
class KaizenSyncFragment : KaizenComposeFragment(R.string.kaizen_settings_sync) {
    @Composable
    override fun KaizenScreen() {
        val status by KaizenSync.status.collectAsState()

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Hint(stringResource(R.string.kaizen_sync_hint))
            if (status.signedIn) {
                SwitchListItem(
                    label = stringResource(R.string.kaizen_sync_enabled),
                    checked = status.enabled,
                    showSwitchAfter = true,
                    modifier = Modifier.settingsCard(index = 0, count = 2),
                    onClick = { KaizenSync.setEnabled(requireContext(), it) },
                )
                IconListItem(
                    label = stringResource(if (status.syncing) R.string.kaizen_sync_syncing else R.string.kaizen_sync_now),
                    description = statusText(status),
                    maxDescriptionLines = 4,
                    enabled = status.enabled && !status.syncing,
                    beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_sync_24),
                    modifier = Modifier.settingsCard(index = 1, count = 2),
                    onClick = KaizenSync::syncNow,
                )
                if (status.enabled && status.problem == KaizenSyncProblem.NOT_TURNED_ON) {
                    FilledButton(
                        text = stringResource(R.string.kaizen_sync_turn_on),
                        enabled = !status.syncing,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        onClick = KaizenSync::turnOn,
                    )
                }
            } else {
                Hint(stringResource(R.string.kaizen_sync_signed_out))
                FilledButton(
                    text = stringResource(R.string.kaizen_sync_sign_in),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    onClick = {
                        findNavController().navigate(
                            NavGraphDirections.actionGlobalTurnOnSync(entrypoint = FenixFxAEntryPoint.SettingsMenu),
                        )
                    },
                )
            }
            Hint(stringResource(R.string.kaizen_sync_zen_hint))
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
    private fun statusText(status: KaizenSyncStatus): String {
        val problem = status.problem
        return when {
            !status.enabled -> stringResource(R.string.kaizen_sync_off)
            problem != null -> stringResource(problemText(problem))
            status.lastSynced > 0 -> stringResource(
                R.string.kaizen_sync_last_synced,
                DateUtils.getRelativeTimeSpanString(
                    status.lastSynced,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString(),
            )
            else -> stringResource(R.string.kaizen_sync_never_synced)
        }
    }

    private fun problemText(problem: KaizenSyncProblem): Int = when (problem) {
        KaizenSyncProblem.NOT_SET_UP -> R.string.kaizen_sync_problem_not_set_up
        KaizenSyncProblem.NOT_TURNED_ON -> R.string.kaizen_sync_problem_not_turned_on
        KaizenSyncProblem.NEEDS_UPDATE -> R.string.kaizen_sync_problem_needs_update
        KaizenSyncProblem.SIGN_IN_AGAIN -> R.string.kaizen_sync_problem_sign_in
        KaizenSyncProblem.OFFLINE -> R.string.kaizen_sync_problem_offline
        KaizenSyncProblem.SERVER -> R.string.kaizen_sync_problem_server
    }
}
