/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen

import android.content.Context
import androidx.core.content.edit
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.getPreferenceKey
import org.mozilla.fenix.settings.ShortcutType

/**
 * Fenix preferences Kaizen changes once, so the user can still change them back in Fenix's settings.
 */
internal object KaizenDefaults {
    private const val PREFS_NAME = "kaizen_settings"
    private const val KEY_VERSION = "defaults_version"
    private const val VERSION = 5

    /**
     * Applies the defaults the first time this Kaizen version runs. Runs before telemetry and experiments start, so
     * their defaults hold from the first launch.
     */
    fun applyOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val applied = prefs.getInt(KEY_VERSION, 0)
        if (applied >= VERSION) return

        with(context.components.settings) {
            if (applied < 1) {
                // New tabs start from the Kaizen home screen instead of an about:home tab.
                enableHomepageAsNewTab = false
                toolbarExpandedShortcutKey = ShortcutType.HOMEPAGE.value
            }
            if (applied < 2) {
                // One toolbar row: the shortcut left of the address bar is Back, and home is always on its right.
                shouldUseExpandedToolbar = false
                toolbarSimpleShortcutKey = ShortcutType.BACK.value
                toolbarTabStripShortcutKey = ShortcutType.BACK.value
            }
            if (applied < 3) {
                // Kaizen has no shortcuts, collections, inactive tabs or tab groups; workspaces and pins replace them.
                showTopSitesFeature = false
                showContileFeature = false
                hideCollectionsUi = true
                inactiveTabsAreEnabled = false
                tabGroupsEnabled = false
            }
            if (applied < 4) {
                // Private by default: no suggestions from the search engine, Firefox Suggest or sponsors, and no
                // telemetry, studies, daily usage ping or links inviting others to Firefox.
                preferences.edit {
                    putBoolean(context.getPreferenceKey(R.string.pref_key_show_search_suggestions), false)
                    putBoolean(context.getPreferenceKey(R.string.pref_key_show_nonsponsored_suggestions), false)
                    putBoolean(context.getPreferenceKey(R.string.pref_key_show_sponsored_suggestions), false)
                    // Tabs from other devices come from Zen's sync instead.
                    putBoolean(context.getPreferenceKey(R.string.pref_key_search_synced_tabs), false)
                }
                isTelemetryEnabled = false
                isExperimentationEnabled = false
                isDailyUsagePingEnabled = false
                whatsappLinkSharingEnabled = false
                // The address bar is always at the top, with Kaizen's actions bar at the bottom.
                shouldUseBottomToolbar = false
                shouldUseExpandedToolbar = true
                shouldFollowDeviceTheme = true
                shouldUseLightTheme = false
                shouldUseDarkTheme = false
                shouldUseOledTheme = false
            }
            if (applied < 5) {
                // The options under the suggestion switches turned off above.
                preferences.edit {
                    putBoolean(context.getPreferenceKey(R.string.pref_key_show_trending_search_suggestions), false)
                    putBoolean(context.getPreferenceKey(R.string.pref_key_search_optimization_cards), false)
                }
            }
        }
        prefs.edit { putInt(KEY_VERSION, VERSION) }
    }
}
