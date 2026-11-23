/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession.SafeBrowsingPolicy
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime

/**
 * Google Safe Browsing, which warns about phishing and malware sites, on or off. Turning its blocking off is not
 * enough on Android, where GeckoView keeps downloading the lists of a blocking that is off, so off also leaves Google's
 * lists out of the ones pages are checked against: Dejavu then neither downloads them nor asks Google about pages.
 * Mozilla's list of harmful add-on sites stays on.
 */
object GoogleSafeBrowsing {
    private const val GOOGLE_LISTS = "goog"

    // GeckoView's own lists, which every runtime starts with.
    private val defaults by lazy { ContentBlocking.Settings.Builder().build() }

    /** What the engine blocks. */
    fun policy(enabled: Boolean): Array<SafeBrowsingPolicy> =
        arrayOf(if (enabled) SafeBrowsingPolicy.RECOMMENDED else SafeBrowsingPolicy.HARMFULADDON)

    /** Sets the lists [settings] checks pages against, before its runtime starts or while it runs. */
    @Suppress("SpreadOperator")
    fun applyLists(settings: ContentBlocking.Settings, enabled: Boolean) {
        settings.setSafeBrowsingMalwareTable(*lists(defaults.safeBrowsingMalwareTable, enabled))
        settings.setSafeBrowsingPhishingTable(*lists(defaults.safeBrowsingPhishingTable, enabled))
    }

    /** Turns Safe Browsing on or off in the running [engine] and its [runtime]. */
    fun apply(engine: Engine, runtime: GeckoRuntime, enabled: Boolean) {
        engine.settings.safeBrowsingPolicy = policy(enabled)
        applyLists(runtime.settings.contentBlocking, enabled)
    }

    private fun lists(all: Array<String>, enabled: Boolean): Array<String> =
        if (enabled) all else all.filterNot { it.startsWith(GOOGLE_LISTS) }.toTypedArray()
}
