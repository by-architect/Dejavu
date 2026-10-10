/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import android.text.format.DateUtils
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.launch
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.settings.workspaceRepository
import org.mozilla.fenix.dejavu.ui.LocalPopupTheme
import org.mozilla.fenix.settings.requirePreference
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * The section of the account page that syncs workspaces with Firefox or Zen: whether they sync, with which browser and
 * how the last sync went, and whether unpinned tabs sync too. Choosing the browser opens [SyncSourceDialog].
 */
class DejavuAccountSync(private val fragment: PreferenceFragmentCompat) {
    private val context
        get() = fragment.requireContext()

    private val category = fragment.requirePreference<PreferenceCategory>(R.string.pref_key_dejavu_sync_category)
    private val enabled = fragment.requirePreference<TwoStatePreference>(R.string.pref_key_dejavu_sync_enabled)
    private val source = fragment.requirePreference<Preference>(R.string.pref_key_dejavu_sync_source)
    private val normalTabs = fragment.requirePreference<TwoStatePreference>(R.string.pref_key_dejavu_sync_normal_tabs)
    private val turnOn = fragment.requirePreference<Preference>(R.string.pref_key_dejavu_sync_turn_on)
    private val hint = fragment.requirePreference<Preference>(R.string.pref_key_dejavu_sync_hint)

    fun bind() {
        listOf(enabled, source, normalTabs, turnOn).forEach { preference ->
            preference.icon = preference.icon?.mutate()?.apply {
                setTintList(AppCompatResources.getColorStateList(context, R.color.state_list_text_color))
            }
        }
        enabled.setOnPreferenceChangeListener { _, value ->
            DejavuSync.setEnabled(context, value as Boolean)
            true
        }
        normalTabs.setOnPreferenceChangeListener { _, value ->
            DejavuSync.setNormalTabs(context, value as Boolean)
            true
        }
        source.setOnPreferenceClickListener {
            DejavuSync.findSources()
            showSourceDialog()
            true
        }
        turnOn.setOnPreferenceClickListener {
            DejavuSync.turnOn()
            true
        }
        val owner = fragment.viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DejavuSync.status.collect(::show)
            }
        }
    }

    private fun show(status: DejavuSyncStatus) {
        category.summary = context.getString(
            when (status.source) {
                DejavuSyncSource.ZEN -> R.string.dejavu_sync_hint
                DejavuSyncSource.FIREFOX -> R.string.dejavu_sync_firefox_about
                null -> R.string.dejavu_sync_choose_about
            },
        )
        enabled.isChecked = status.enabled
        source.summary = context.getString(sourceName(status.source)) + "\n" + statusText(status)
        source.isEnabled = status.enabled
        normalTabs.isChecked = status.normalTabs
        normalTabs.isEnabled = status.enabled
        normalTabs.summary = context.getString(
            if (status.source == DejavuSyncSource.FIREFOX) {
                R.string.dejavu_sync_normal_tabs_firefox_summary
            } else {
                R.string.dejavu_sync_normal_tabs_summary
            },
        )
        turnOn.isVisible = status.enabled && status.source == DejavuSyncSource.ZEN &&
            status.problem == DejavuSyncProblem.NOT_TURNED_ON
        turnOn.isEnabled = !status.syncing
        hint.isVisible = status.source != null
        status.source?.let {
            hint.summary = context.getString(
                if (it == DejavuSyncSource.FIREFOX) R.string.dejavu_sync_firefox_hint else R.string.dejavu_sync_zen_hint,
            )
        }
    }

    private fun statusText(status: DejavuSyncStatus): String {
        val problem = status.problem
        return when {
            !status.enabled -> context.getString(R.string.dejavu_sync_off)
            status.syncing -> context.getString(R.string.dejavu_sync_syncing)
            problem != null -> context.getString(problemText(problem))
            status.source == null -> context.getString(R.string.dejavu_sync_problem_choose)
            status.lastSynced > 0 -> context.getString(
                R.string.dejavu_sync_last_synced,
                DateUtils.getRelativeTimeSpanString(
                    status.lastSynced,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString(),
            )
            else -> context.getString(R.string.dejavu_sync_never_synced)
        }
    }

    /** Shows [SyncSourceDialog] over the account page, which is made of views, in a composition of its own. */
    private fun showSourceDialog() {
        val root = fragment.view as? ViewGroup ?: return
        val dialog = ComposeView(context)
        fun close() {
            root.post { root.removeView(dialog) }
        }
        root.addView(dialog, ViewGroup.LayoutParams(0, 0))
        dialog.setContent {
            FirefoxTheme {
                val repository = remember { fragment.workspaceRepository() }
                val workspaces by repository.state.collectAsState()
                val status by DejavuSync.status.collectAsState()
                CompositionLocalProvider(LocalPopupTheme provides workspaces.activeWorkspace?.theme) {
                    SyncSourceDialog(
                        status = status,
                        onChoose = {
                            DejavuSync.chooseSource(context, it)
                            close()
                        },
                        onDismiss = ::close,
                    )
                }
            }
        }
    }

    private companion object {
        @StringRes
        fun sourceName(source: DejavuSyncSource?): Int = when (source) {
            DejavuSyncSource.ZEN -> R.string.dejavu_sync_source_zen
            DejavuSyncSource.FIREFOX -> R.string.dejavu_sync_source_firefox
            null -> R.string.dejavu_sync_source_none
        }

        @StringRes
        fun problemText(problem: DejavuSyncProblem): Int = when (problem) {
            DejavuSyncProblem.NOT_SET_UP -> R.string.dejavu_sync_problem_not_set_up
            DejavuSyncProblem.NOT_TURNED_ON -> R.string.dejavu_sync_problem_not_turned_on
            DejavuSyncProblem.NEEDS_UPDATE -> R.string.dejavu_sync_problem_needs_update
            DejavuSyncProblem.SIGN_IN_AGAIN -> R.string.dejavu_sync_problem_sign_in
            DejavuSyncProblem.OFFLINE -> R.string.dejavu_sync_problem_offline
            DejavuSyncProblem.SERVER -> R.string.dejavu_sync_problem_server
            DejavuSyncProblem.CHOOSE_SOURCE -> R.string.dejavu_sync_problem_choose
            DejavuSyncProblem.TABS_OFF -> R.string.dejavu_sync_problem_tabs_off
            DejavuSyncProblem.NO_FIREFOX -> R.string.dejavu_sync_problem_no_firefox
        }
    }
}
