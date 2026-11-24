/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.compose.browser.awesomebar

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mozilla.components.concept.awesomebar.AwesomeBar

/**
 * Dejavu: the suggestion picked with a hardware keyboard while the focus stays in the address bar. Give it to
 * [AwesomeBar], which highlights the picked suggestion and scrolls to it.
 */
@Stable
class AwesomeBarSelection {
    /** The shown suggestions, top to bottom, with their groups. */
    internal var shown by mutableStateOf(emptyList<Pair<AwesomeBar.SuggestionProviderGroup, AwesomeBar.SuggestionItem>>())
        private set

    /** The position of the picked suggestion in the shown ones, or -1 when none is picked. */
    var index by mutableIntStateOf(-1)
        private set

    /** The picked suggestion and its group, or `null` when none is picked. */
    val selected: Pair<AwesomeBar.SuggestionProviderGroup, AwesomeBar.SuggestionItem>?
        get() = shown.getOrNull(index)

    /**
     * Moves the pick [step] rows down, or up when negative. With none picked, down picks the first suggestion and up
     * the last one. Moving past either end picks none again, giving the address bar back its text.
     * Returns whether there was a suggestion to pick.
     */
    fun move(step: Int): Boolean {
        val size = shown.size
        if (size == 0) return false
        index = when {
            index < 0 -> if (step > 0) 0 else size - 1
            else -> (index + step).takeIf { it in 0 until size } ?: -1
        }
        return true
    }

    fun clear() {
        index = -1
    }

    /** Sets the suggestions shown now. The picked one stays picked while it is still shown. */
    internal fun update(suggestions: List<Pair<AwesomeBar.SuggestionProviderGroup, AwesomeBar.SuggestionItem>>) {
        val picked = selected?.second
        shown = suggestions
        index = picked?.let { suggestions.indexOfFirst { (_, suggestion) -> suggestion.isSameRow(it) } } ?: -1
    }

    /**
     * Whether this suggestion shows the same row as [other]. Reloaded suggestions, like after the soft keyboard
     * hides, get new ids, so a suggestion with a title or description also counts as the same by those.
     */
    private fun AwesomeBar.SuggestionItem.isSameRow(other: AwesomeBar.SuggestionItem): Boolean = when {
        provider.id == other.provider.id && id == other.id -> true
        this is AwesomeBar.Suggestion && other is AwesomeBar.Suggestion ->
            (title != null || description != null) && title == other.title && description == other.description
        else -> false
    }
}
