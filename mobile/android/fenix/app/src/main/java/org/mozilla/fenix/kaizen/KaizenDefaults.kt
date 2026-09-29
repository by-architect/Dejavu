/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen

import android.content.Context
import androidx.core.content.edit
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.settings.ShortcutType

/**
 * Fenix preferences Kaizen changes once, so the user can still change them back in Fenix's settings.
 */
internal object KaizenDefaults {
    private const val PREFS_NAME = "kaizen_settings"
    private const val KEY_VERSION = "defaults_version"
    private const val VERSION = 3

    /** Applies the defaults the first time this Kaizen version runs. Reads and writes preferences, so call off the main thread. */
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
        }
        prefs.edit { putInt(KEY_VERSION, VERSION) }
    }
}
