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
    private const val VERSION = 1

    /** Applies the defaults the first time this Kaizen version runs. Reads and writes preferences, so call off the main thread. */
    fun applyOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= VERSION) return

        with(context.components.settings) {
            // New tabs start from the Kaizen home screen instead of an about:home tab.
            enableHomepageAsNewTab = false
            // The toolbar shortcut of the browser becomes a button back to the home screen.
            toolbarSimpleShortcutKey = ShortcutType.HOMEPAGE.value
            toolbarTabStripShortcutKey = ShortcutType.HOMEPAGE.value
            toolbarExpandedShortcutKey = ShortcutType.HOMEPAGE.value
        }
        prefs.edit { putInt(KEY_VERSION, VERSION) }
    }
}
