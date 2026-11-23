/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

/**
 * The sizes the settings share on wide windows, in dp: the width from which Settings shows a list of categories next
 * to the settings, the width of that list, and the width the cards of the settings stop growing at.
 */
object SettingsLayout {
    /** From this window width on, Settings shows the categories beside the settings. */
    const val WIDE_MIN_WIDTH_DP = 600

    /** Width of the list of categories. */
    const val PANE_WIDTH_DP = 320

    /** The widest a settings card gets. On a wide window, a label would otherwise sit far from its switch. */
    const val MAX_CONTENT_WIDTH_DP = 680

    /** Whether a window [widthDp] wide is wide enough for the categories to show beside the settings. */
    fun isWide(widthDp: Int): Boolean = widthDp >= WIDE_MIN_WIDTH_DP

    /**
     * The space to leave on each side of content that is at most [maxWidth] wide so it stays centered in a space
     * [available] wide, never less than [minimum]. All sizes are in the same unit.
     */
    fun sideInset(available: Int, maxWidth: Int, minimum: Int): Int = maxOf(minimum, (available - maxWidth) / 2)
}
