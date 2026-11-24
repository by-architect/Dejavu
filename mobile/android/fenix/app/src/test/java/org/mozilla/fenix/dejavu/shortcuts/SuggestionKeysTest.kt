/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.shortcuts

import android.view.KeyEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mozilla.components.compose.browser.awesomebar.AwesomeBarSelection
import mozilla.components.concept.awesomebar.AwesomeBar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SuggestionKeysTest {
    private val selection: AwesomeBarSelection = mockk(relaxed = true)
    private val opened = mutableListOf<AwesomeBar.SuggestionItem>()
    private val keys = SuggestionKeys(selection) { opened += it }

    @After
    fun tearDown() {
        SuggestionKeys.active = null
    }

    private fun press(keyCode: Int, metaState: Int = 0, repeat: Int = 0) =
        keys.handle(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, repeat, metaState))

    @Test
    fun `down and tab pick the next suggestion, up and shift tab the previous one`() {
        every { selection.move(any()) } returns true

        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(press(KeyEvent.KEYCODE_TAB))
        assertTrue(press(KeyEvent.KEYCODE_DPAD_UP))
        assertTrue(press(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_LEFT_ON))

        verify(exactly = 2) { selection.move(1) }
        verify(exactly = 2) { selection.move(-1) }
    }

    @Test
    fun `tab stays in the address bar even without suggestions, the arrows do not`() {
        every { selection.move(any()) } returns false

        assertTrue(press(KeyEvent.KEYCODE_TAB))
        assertFalse(press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(press(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun `ctrl and alt combos and shift arrows are left alone`() {
        every { selection.move(any()) } returns true

        assertFalse(press(KeyEvent.KEYCODE_TAB, KeyEvent.META_CTRL_ON))
        assertFalse(press(KeyEvent.KEYCODE_TAB, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON))
        assertFalse(press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.META_ALT_ON))
        assertFalse(press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.META_SHIFT_ON))
        verify(exactly = 0) { selection.move(any()) }
    }

    @Test
    fun `enter opens the picked suggestion once`() {
        val suggestion: AwesomeBar.SuggestionItem = mockk()
        val group: AwesomeBar.SuggestionProviderGroup = mockk()
        every { selection.selected } returns (group to suggestion)

        assertTrue(press(KeyEvent.KEYCODE_ENTER))
        assertFalse(press(KeyEvent.KEYCODE_ENTER, repeat = 1))

        assertEquals(listOf(suggestion), opened)
    }

    @Test
    fun `enter and escape are left to the address bar with nothing picked`() {
        every { selection.selected } returns null
        every { selection.index } returns -1

        assertFalse(press(KeyEvent.KEYCODE_ENTER))
        assertFalse(press(KeyEvent.KEYCODE_ESCAPE))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `escape picks none`() {
        every { selection.index } returns 1

        assertTrue(press(KeyEvent.KEYCODE_ESCAPE))
        verify { selection.clear() }
    }

    @Test
    fun `dispatch consumes repeats and the matching release even after suggestions close`() {
        val suggestion: AwesomeBar.SuggestionItem = mockk()
        val group: AwesomeBar.SuggestionProviderGroup = mockk()
        every { selection.selected } returns (group to suggestion)
        every { selection.move(any()) } returns true
        SuggestionKeys.active = keys

        assertTrue(SuggestionKeys.dispatch(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0)))
        assertTrue(SuggestionKeys.dispatch(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 1)))
        assertTrue(SuggestionKeys.dispatch(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0)))
        assertFalse(SuggestionKeys.dispatch(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0)))
        verify(exactly = 2) { selection.move(1) }

        assertTrue(SuggestionKeys.dispatch(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)))
        assertTrue(SuggestionKeys.dispatch(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 1)))
        SuggestionKeys.active = null
        assertTrue(SuggestionKeys.dispatch(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)))
        assertFalse(SuggestionKeys.dispatch(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)))
        assertEquals(listOf(suggestion), opened)
    }
}
