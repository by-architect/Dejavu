/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.shortcuts

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyComboTest {
    private val ctrl = KeyEvent.META_CTRL_ON
    private val shift = KeyEvent.META_SHIFT_ON

    private fun press(keyCode: Int, metaState: Int) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)

    @Test
    fun `left and right modifier flags count as the plain ones`() {
        assertEquals(
            KeyCombo(KeyEvent.KEYCODE_W, ctrl),
            KeyCombo.of(press(KeyEvent.KEYCODE_W, KeyEvent.META_CTRL_LEFT_ON)),
        )
        assertEquals(
            KeyCombo(KeyEvent.KEYCODE_T, ctrl or shift),
            KeyCombo.of(press(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_RIGHT_ON or KeyEvent.META_SHIFT_LEFT_ON)),
        )
        assertEquals(
            KeyCombo(KeyEvent.KEYCODE_L, ctrl),
            KeyCombo.of(press(KeyEvent.KEYCODE_L, ctrl or KeyEvent.META_NUM_LOCK_ON or KeyEvent.META_META_ON)),
        )
    }

    @Test
    fun `lone modifiers are not combos`() {
        assertNull(KeyCombo.of(press(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_CTRL_LEFT_ON)))
        assertNull(KeyCombo.of(press(KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.META_SHIFT_RIGHT_ON)))
        assertNull(KeyCombo.of(press(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_ALT_LEFT_ON)))
    }

    @Test
    fun `encoded combos parse back and bad text does not`() {
        val combo = KeyCombo(KeyEvent.KEYCODE_TAB, ctrl or shift)
        assertEquals(combo, KeyCombo.parse(combo.encode()))
        assertNull(KeyCombo.parse(""))
        assertNull(KeyCombo.parse("ctrl:w"))
        assertNull(KeyCombo.parse("4096"))
    }

    @Test
    fun `saved combos replace defaults and clearing frees the key`() {
        val ctrlW = KeyCombo(KeyEvent.KEYCODE_W, ctrl)
        assertEquals(ShortcutAction.CLOSE_TAB, KeyboardShortcuts.actionFor(ctrlW, emptyMap()))
        assertEquals(ShortcutAction.CLOSE_TAB, KeyboardShortcuts.conflictOf(ShortcutAction.NEW_TAB, ctrlW, emptyMap()))
        assertNull(KeyboardShortcuts.conflictOf(ShortcutAction.CLOSE_TAB, ctrlW, emptyMap()))

        val cleared = mapOf(ShortcutAction.CLOSE_TAB.key to "")
        assertNull(KeyboardShortcuts.comboOf(ShortcutAction.CLOSE_TAB, cleared))
        assertNull(KeyboardShortcuts.conflictOf(ShortcutAction.NEW_TAB, ctrlW, cleared))

        val moved = mapOf(ShortcutAction.CLOSE_TAB.key to "", ShortcutAction.RELOAD.key to ctrlW.encode())
        assertEquals(ShortcutAction.RELOAD, KeyboardShortcuts.conflictOf(ShortcutAction.NEW_TAB, ctrlW, moved))
        val broken = mapOf(ShortcutAction.RELOAD.key to "x")
        assertEquals(ShortcutAction.RELOAD.default, KeyboardShortcuts.comboOf(ShortcutAction.RELOAD, broken))
    }

    @Test
    fun `text editing combos are told apart from app shortcuts`() {
        assertTrue(KeyCombo(KeyEvent.KEYCODE_C, ctrl).editsText)
        assertTrue(KeyCombo(KeyEvent.KEYCODE_Z, ctrl or shift).editsText)
        assertTrue(KeyCombo(KeyEvent.KEYCODE_DPAD_LEFT, ctrl or shift).editsText)
        assertTrue(KeyCombo(KeyEvent.KEYCODE_DEL, ctrl).editsText)
        assertFalse(KeyCombo(KeyEvent.KEYCODE_C, KeyEvent.META_ALT_ON).editsText)
        ShortcutAction.entries.forEach { assertFalse(it.name, it.default.editsText) }
    }
}
