/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.shortcuts

import android.view.KeyEvent
import mozilla.components.compose.browser.awesomebar.AwesomeBarSelection
import mozilla.components.concept.awesomebar.AwesomeBar

/**
 * The keys that pick a search suggestion while the address bar keeps the focus, like on desktop: Down and Tab pick
 * the next one, Up and Shift+Tab the previous one, Enter opens the picked one and Escape picks none again.
 * Tab never moves the focus out of the address bar, which would let the arrows walk the rest of the screen.
 */
class SuggestionKeys(
    private val selection: AwesomeBarSelection,
    private val open: (AwesomeBar.SuggestionItem) -> Unit,
) {
    /** Handles the press or repeat [event]. Returns whether it consumed [event]. */
    fun handle(event: KeyEvent): Boolean {
        val meta = KeyEvent.normalizeMetaState(event.metaState)
        if (meta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) != 0) return false
        val shift = meta and KeyEvent.META_SHIFT_ON != 0
        return when (event.keyCode) {
            KeyEvent.KEYCODE_TAB -> {
                selection.move(if (shift) -1 else 1)
                true
            }
            // Shift with an arrow selects text in the address bar.
            KeyEvent.KEYCODE_DPAD_DOWN -> !shift && selection.move(1)
            KeyEvent.KEYCODE_DPAD_UP -> !shift && selection.move(-1)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                event.repeatCount == 0 && selection.selected?.let { (_, suggestion) -> open(suggestion) } != null
            KeyEvent.KEYCODE_ESCAPE -> (selection.index >= 0).also { selection.clear() }
            else -> false
        }
    }

    companion object {
        /** Set while search suggestions are shown, to pick one with the keyboard. */
        var active: SuggestionKeys? = null
        private var consumedKey: Int? = null

        /** Handles [event] for the active suggestions, consuming the press, repeats, and matching release. */
        fun dispatch(event: KeyEvent): Boolean {
            val consumed = consumedKey
            return when {
                event.action == KeyEvent.ACTION_UP ->
                    (event.keyCode == consumed).also { if (it) consumedKey = null }
                event.action != KeyEvent.ACTION_DOWN -> false
                // Before the repeat check, so holding an arrow key keeps moving through the suggestions.
                active?.handle(event) == true -> {
                    consumedKey = event.keyCode
                    true
                }
                event.repeatCount > 0 -> event.keyCode == consumed
                else -> {
                    if (event.keyCode == consumed) consumedKey = null
                    false
                }
            }
        }
    }
}
