/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.work.WorkManager
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.getPreferenceKey
import org.mozilla.fenix.onboarding.FenixOnboarding
import org.mozilla.fenix.settings.ShortcutType

/**
 * Fenix preferences Dejavu changes once, so the user can still change them back in Fenix's settings.
 */
internal object DejavuDefaults {
    // Kept from before the rename to Dejavu, so saved data still loads.
    private const val PREFS_NAME = "kaizen_settings"
    private const val KEY_VERSION = "defaults_version"
    private const val VERSION = 7
    private const val POCKET_STORIES_WORK_TAG = "mozilla.components.service.pocket.recommendations.refresh.work.tag"
    private const val POCKET_SPONSORED_WORK_TAG = "mozilla.components.service.pocket.sponsored.content.refresh.work.tag"

    /**
     * Applies the defaults the first time this Dejavu version runs. Runs before telemetry and experiments start, so
     * their defaults hold from the first launch.
     */
    fun applyOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val applied = prefs.getInt(KEY_VERSION, 0)
        if (applied >= VERSION) return

        var stopStoryDownloads = false
        with(context.components.settings) {
            if (applied < 1) {
                // New tabs start from the Dejavu home screen instead of an about:home tab.
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
                // Dejavu has no shortcuts, collections, inactive tabs or tab groups; workspaces and pins replace them.
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
                // The address bar is always at the top, with Dejavu's actions bar at the bottom.
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
            if (applied < 6) {
                // Dejavu starts on its own home screen, without Firefox's onboarding or its Terms of Use prompt.
                FenixOnboarding(context).finish()
                isTermsOfUsePromptEnabled = false
            }
            if (applied < 7) {
                // Dejavu's home screen shows no Pocket stories, so neither they nor the sponsored stories, which are
                // ads from Mozilla's ad service, are downloaded. Page summaries and Relay email masks are Mozilla's
                // online services for Firefox, so they are off too.
                showPocketRecommendationsFeature = false
                preferences.edit {
                    putBoolean(context.getPreferenceKey(R.string.pref_key_pocket_sponsored_stories), false)
                }
                shakeToSummarizeFeatureFlagEnabled = false
                isEmailMaskFeatureEnabled = false
                isEmailMaskSuggestionEnabled = false
                stopStoryDownloads = true
            }
        }
        prefs.edit { putInt(KEY_VERSION, VERSION) }
        if (stopStoryDownloads) {
            // Downloads scheduled by an earlier version keep running until they are cancelled. The first use of
            // WorkManager starts the browser engine, so this waits on the main thread until the app has started.
            Handler(Looper.getMainLooper()).post {
                WorkManager.getInstance(context).run {
                    cancelAllWorkByTag(POCKET_STORIES_WORK_TAG)
                    cancelAllWorkByTag(POCKET_SPONSORED_WORK_TAG)
                }
            }
        }
    }
}
