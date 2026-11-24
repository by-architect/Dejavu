/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.compose.browser.awesomebar

import mozilla.components.concept.awesomebar.AwesomeBar
import mozilla.components.concept.awesomebar.AwesomeBar.SuggestionProviderGroup
import mozilla.components.support.test.mock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AwesomeBarSelectionTest {
    private val provider: AwesomeBar.SuggestionProvider = mock()
    private val group = SuggestionProviderGroup(listOf(provider), id = "group")

    private fun suggestions(vararg ids: String) = ids.map { group to AwesomeBar.Suggestion(provider = provider, id = it) }

    private fun reloaded(vararg titles: String): List<Pair<SuggestionProviderGroup, AwesomeBar.Suggestion>> {
        val newGroup = SuggestionProviderGroup(listOf(provider))
        return titles.map { newGroup to AwesomeBar.Suggestion(provider = provider, title = it) }
    }

    private val AwesomeBarSelection.pickedId get() = selected?.second?.id

    @Test
    fun `nothing is picked without suggestions`() {
        val selection = AwesomeBarSelection()

        assertFalse(selection.move(1))
        assertFalse(selection.move(-1))
        assertEquals(-1, selection.index)
        assertNull(selection.selected)
    }

    @Test
    fun `down goes from the first suggestion to the last one, then back to none`() {
        val selection = AwesomeBarSelection().apply { update(suggestions("a", "b", "c")) }

        assertTrue(selection.move(1))
        assertEquals("a", selection.pickedId)
        selection.move(1)
        selection.move(1)
        assertEquals("c", selection.pickedId)
        selection.move(1)
        assertNull(selection.selected)
        selection.move(1)
        assertEquals("a", selection.pickedId)
    }

    @Test
    fun `up from none picks the last suggestion`() {
        val selection = AwesomeBarSelection().apply { update(suggestions("a", "b", "c")) }

        selection.move(-1)
        assertEquals("c", selection.pickedId)
        selection.move(-1)
        selection.move(-1)
        assertEquals("a", selection.pickedId)
        selection.move(-1)
        assertNull(selection.selected)
    }

    @Test
    fun `the picked suggestion stays picked when more suggestions arrive`() {
        val selection = AwesomeBarSelection().apply { update(suggestions("a", "b")) }
        selection.move(1)
        selection.move(1)

        selection.update(suggestions("x", "a", "b"))

        assertEquals(2, selection.index)
        assertEquals("b", selection.pickedId)
    }

    @Test
    fun `nothing is picked once the picked suggestion is gone`() {
        val selection = AwesomeBarSelection().apply { update(suggestions("a", "b")) }
        selection.move(1)

        selection.update(suggestions("b", "c"))
        assertNull(selection.selected)

        selection.move(1)
        selection.update(emptyList())
        assertEquals(-1, selection.index)
    }

    @Test
    fun `the picked suggestion stays picked when the suggestions reload with new ids`() {
        val selection = AwesomeBarSelection().apply { update(reloaded("a", "b", "c")) }
        selection.move(1)
        selection.move(1)

        selection.update(reloaded("c", "b"))

        assertEquals(1, selection.index)
        assertEquals("b", (selection.selected?.second as AwesomeBar.Suggestion).title)
    }

    @Test
    fun `clear picks none`() {
        val selection = AwesomeBarSelection().apply { update(suggestions("a")) }
        selection.move(1)

        selection.clear()

        assertNull(selection.selected)
    }
}
