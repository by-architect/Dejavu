/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.preference.PreferenceFragmentCompat
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.browser.LoadingColorsChoice
import org.mozilla.fenix.dejavu.home.ThemeColorDialog
import org.mozilla.fenix.dejavu.ui.LocalPopupTheme
import org.mozilla.fenix.dejavu.ui.workspacePopupTheme
import org.mozilla.fenix.settings.RadioButtonPreference
import org.mozilla.fenix.settings.requirePreference
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.fenix.utils.view.addToRadioGroup

/**
 * Chooses the colors that run through the address bar's letters while a page loads, on the Bars screen: the rainbow,
 * the workspace's colors, or the user's own. Choosing their own, or choosing it again, picks the colors in the
 * workspace editor's [ThemeColorDialog]; closing it without picking any keeps the choice from before.
 */
internal fun PreferenceFragmentCompat.setupLoadingColorsCategory() {
    val dejavuSettings = dejavuSettings()
    val choices = LoadingColorsChoice.entries.map { choice ->
        requirePreference<RadioButtonPreference>(choice.preferenceKey).apply {
            onClickListener {
                if (choice == LoadingColorsChoice.CUSTOM) {
                    showLoadingColorsDialog()
                } else {
                    dejavuSettings.setLoadingColors(choice)
                }
            }
            setCheckedWithoutClickListener(dejavuSettings.loadingColors.value == choice)
        }
    }
    addToRadioGroup(*choices.toTypedArray())
}

/** Shows [ThemeColorDialog] over the Bars screen, which is made of views, in a composition of its own. */
private fun PreferenceFragmentCompat.showLoadingColorsDialog() {
    val root = view as? ViewGroup ?: return
    val dejavuSettings = dejavuSettings()
    val dialog = ComposeView(requireContext())
    fun close() {
        root.post { root.removeView(dialog) }
        // The tap checked the user's own colors; without colors picked, the choice from before stays checked.
        requirePreference<RadioButtonPreference>(dejavuSettings.loadingColors.value.preferenceKey)
            .setCheckedWithoutClickListener(true)
    }
    root.addView(dialog, ViewGroup.LayoutParams(0, 0))
    dialog.setContent {
        FirefoxTheme {
            val repository = remember { workspaceRepository() }
            val workspaces by repository.state.collectAsState()
            CompositionLocalProvider(LocalPopupTheme provides workspacePopupTheme(workspaces.activeWorkspaceId)) {
                ThemeColorDialog(
                    initial = dejavuSettings.loadingCustomColors.value,
                    onPick = { colors ->
                        dejavuSettings.setLoadingCustomColors(colors)
                        dejavuSettings.setLoadingColors(LoadingColorsChoice.CUSTOM)
                        close()
                    },
                    onDismiss = ::close,
                )
            }
        }
    }
}

/** The radio button of this choice on the Bars screen. */
@get:StringRes
private val LoadingColorsChoice.preferenceKey: Int
    get() = when (this) {
        LoadingColorsChoice.RAINBOW -> R.string.pref_key_dejavu_loading_colors_rainbow
        LoadingColorsChoice.WORKSPACE -> R.string.pref_key_dejavu_loading_colors_workspace
        LoadingColorsChoice.CUSTOM -> R.string.pref_key_dejavu_loading_colors_custom
    }
