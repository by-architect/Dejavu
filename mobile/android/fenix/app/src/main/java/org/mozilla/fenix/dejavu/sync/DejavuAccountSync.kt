/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import android.content.Context
import android.text.format.DateUtils
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference
import com.google.android.material.R as materialR
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.support.ktx.android.content.getColorFromAttr
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.settings.workspaceRepository
import org.mozilla.fenix.dejavu.ui.LocalPopupTheme
import org.mozilla.fenix.dejavu.ui.workspacePopupTheme
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.settings.requirePreference
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * The parts of the account page that sync workspaces with Firefox or Zen. Under Sync: which browser workspaces sync
 * with and how the last sync went. Among what to sync: whether workspaces sync with Zen, and their unpinned tabs too,
 * both marked Zen and off while workspaces sync with Firefox, whose tabs always show. Choosing the browser opens
 * [SyncSourceDialog]. [onChanged] runs whenever the state of workspace sync shows anew.
 */
class DejavuAccountSync(private val fragment: PreferenceFragmentCompat, private val onChanged: () -> Unit = {}) {
    private val context
        get() = fragment.requireContext()

    private val enabled = fragment.requirePreference<TwoStatePreference>(R.string.pref_key_dejavu_sync_enabled)
    private val source = fragment.requirePreference<Preference>(R.string.pref_key_dejavu_sync_source)
    private val normalTabs = fragment.requirePreference<TwoStatePreference>(R.string.pref_key_dejavu_sync_normal_tabs)
    private val turnOn = fragment.requirePreference<Preference>(R.string.pref_key_dejavu_sync_turn_on)
    private val hint = fragment.requirePreference<Preference>(R.string.pref_key_dejavu_sync_hint)

    fun bind() {
        val zenSettings = mapOf(enabled to R.string.dejavu_sync_enabled, normalTabs to R.string.dejavu_sync_normal_tabs)
        zenSettings.forEach { (preference, title) ->
            preference.title = zenSetting(context.getString(title))
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
        val withFirefox = status.source == DejavuSyncSource.FIREFOX
        enabled.isChecked = status.enabled
        enabled.isEnabled = !withFirefox
        normalTabs.isChecked = status.normalTabs
        normalTabs.isEnabled = status.enabled && !withFirefox
        source.summary = syncSummary(context, status)
        turnOn.isVisible = status.syncsZen && status.problem == DejavuSyncProblem.NOT_TURNED_ON
        turnOn.isEnabled = !status.syncing
        hint.isVisible = status.source != null
        status.source?.let {
            hint.summary = context.getString(
                when {
                    it == DejavuSyncSource.FIREFOX -> R.string.dejavu_sync_firefox_hint
                    status.normalTabs -> R.string.dejavu_sync_zen_hint_unpinned
                    else -> R.string.dejavu_sync_zen_hint
                },
            )
        }
        onChanged()
    }

    /** [title] of a setting of syncing with Zen, followed by "Zen" in the color of secondary text. */
    private fun zenSetting(title: String): CharSequence = buildSpannedString {
        append(title).append("  ")
        color(context.getColorFromAttr(materialR.attr.colorOnSurfaceVariant)) {
            append(context.getString(R.string.dejavu_sync_source_zen))
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
                val tabs by context.components.core.store.observeAsComposableState { it.normalTabs }
                val status by DejavuSync.status.collectAsState()
                CompositionLocalProvider(LocalPopupTheme provides workspacePopupTheme(workspaces.activeWorkspaceId)) {
                    SyncSourceDialog(
                        status = status,
                        workspaces = workspaces,
                        tabs = tabs,
                        onChoose = { source, start ->
                            DejavuSync.chooseSource(context, source, start)
                            close()
                        },
                        onDismiss = ::close,
                    )
                }
            }
        }
    }
}

/** The browser workspaces sync with, and how syncing goes, on two lines. */
private fun syncSummary(context: Context, status: DejavuSyncStatus): String =
    context.getString(sourceName(status.source)) + "\n" + statusText(context, status)

private fun statusText(context: Context, status: DejavuSyncStatus): String {
    val problem = status.problem
    return when {
        !status.isOn -> context.getString(R.string.dejavu_sync_off)
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

@StringRes
private fun sourceName(source: DejavuSyncSource?): Int = when (source) {
    DejavuSyncSource.ZEN -> R.string.dejavu_sync_source_zen
    DejavuSyncSource.FIREFOX -> R.string.dejavu_sync_source_firefox
    null -> R.string.dejavu_sync_source_none
}

@StringRes
private fun problemText(problem: DejavuSyncProblem): Int = when (problem) {
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
