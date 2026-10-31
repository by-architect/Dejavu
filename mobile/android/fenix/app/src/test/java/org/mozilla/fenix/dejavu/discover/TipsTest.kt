/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.discover

import androidx.compose.ui.geometry.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TipsTest {
    private val owners = mutableListOf<Any>()

    @After
    fun tearDown() {
        owners.forEach(Tips::remove)
        Tips.show(null)
    }

    private fun place(tip: Tip, bounds: Rect = Rect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())) {
        val owner = Any().also(owners::add)
        Tips.place(owner, TipTarget(tip, TipGesture.Tap, tip.text, 0.5f, bounds, WIDTH, HEIGHT, drawsItself = false))
    }

    @Test
    fun `the first tip due whose place is in view shows, in the order of the tips`() {
        place(Tip.FOLDER)
        place(Tip.NEW_TAB)

        assertEquals(Tip.NEW_TAB, Tips.pick(TipScreen.HOME, emptySet()))
    }

    @Test
    fun `seen tips and the tips of other screens are left out`() {
        place(Tip.NEW_TAB)
        place(Tip.PAGE_MENU)

        assertNull(Tips.pick(TipScreen.HOME, setOf(Tip.NEW_TAB.key)))
        assertEquals(Tip.PAGE_MENU, Tips.pick(TipScreen.PAGE, emptySet()))
    }

    @Test
    fun `a place cut off by the edge of a list is not in view`() {
        place(Tip.WORKSPACE_NAME, bounds = Rect(0f, 0f, WIDTH.toFloat(), HEIGHT / 2f))

        assertNull(Tips.pick(TipScreen.HOME, emptySet()))
    }

    @Test
    fun `the tip showing stays while its place is in view, also when an earlier one comes due`() {
        place(Tip.ESSENTIAL_HOLD)
        Tips.show(Tips.pick(TipScreen.HOME, emptySet()))
        place(Tip.WORKSPACE_NAME)

        assertEquals(Tip.ESSENTIAL_HOLD, Tips.pick(TipScreen.HOME, emptySet()))
    }

    @Test
    fun `a tip done closes and does not come back`() {
        place(Tip.SPLIT)
        Tips.show(Tip.SPLIT)

        Tips.done(Tip.SPLIT)

        assertNull(Tips.shown)
        assertNull(Tips.pick(TipScreen.HOME, emptySet()))
    }

    @Test
    fun `a tip put off before it could be read comes back`() {
        place(Tip.WORKSPACES)
        Tips.show(Tip.WORKSPACES)

        Tips.putOff(Tip.WORKSPACES)

        assertNull(Tips.shown)
        assertEquals(Tip.WORKSPACES, Tips.pick(TipScreen.HOME, emptySet()))
    }

    private companion object {
        const val WIDTH = 300
        const val HEIGHT = 60
    }
}
