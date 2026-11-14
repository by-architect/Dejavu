/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.net.Uri
import java.util.Collections
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TranslationsBrowserState

/**
 * Offers to translate a site in a language the user does not read, once for each site while Dejavu runs. Firefox only
 * offers it the first time ever a site is visited, so later visits never asked again (#279). The user's choices still
 * count: no offer when offering is turned off, or for a site or language they never want translated, or one translated
 * by itself.
 */
object TranslationOffers {
    /** What to do for the shown tab. */
    enum class Step { NONE, FETCH_SETTINGS, OFFER }

    private val offeredSites: MutableSet<String> = Collections.synchronizedSet(HashSet())

    // Sites whose translation settings were asked for, once, so a failing fetch is not asked for again and again.
    private val askedSites: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** What to do for [tab], given the browser's translations state [engine]. */
    fun stepFor(tab: SessionState, engine: TranslationsBrowserState): Step {
        val translations = tab.translationsState
        val site = siteOf(tab) ?: return Step.NONE
        val waiting = !translations.isExpectedTranslate || translations.isTranslated ||
            translations.isTranslateProcessing || translations.translationError != null
        if (waiting || engine.offerTranslation == false || site in offeredSites) return Step.NONE
        val settings = translations.pageSettings
            ?: return if (askedSites.add(site)) Step.FETCH_SETTINGS else Step.NONE
        val refused = settings.alwaysOfferPopup == false || settings.neverTranslateSite == true ||
            settings.neverTranslateLanguage == true || settings.alwaysTranslateLanguage == true
        if (refused) return Step.NONE
        offeredSites.add(site)
        return Step.OFFER
    }

    /** Firefox offered to translate [tab] itself, which counts as the offer for its site. */
    fun offered(tab: SessionState) {
        siteOf(tab)?.let(offeredSites::add)
    }

    private fun siteOf(tab: SessionState): String? = runCatching { Uri.parse(tab.content.url).host }.getOrNull()
}
