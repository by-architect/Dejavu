/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.browser.BrowserFragment
import org.mozilla.fenix.dejavu.welcome.DejavuWelcomeFragment
import org.mozilla.fenix.home.HomeFragment
import org.mozilla.fenix.library.history.HistoryFragment
import org.mozilla.fenix.settings.SettingsFragment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PageBackdropTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `a picture of one color stays that color`() {
        val pixels = IntArray(12 * 7) { 0xFF336699.toInt() }

        pixels.boxBlur(12, 7, radius = 2)

        assertTrue(pixels.all { it == 0xFF336699.toInt() })
    }

    @Test
    fun `a bright dot spreads evenly around itself`() {
        val size = 21
        val pixels = IntArray(size * size) { black }
        pixels[10 * size + 10] = white

        pixels.boxBlur(size, size, radius = 2)

        val center = blue(pixels[10 * size + 10])
        assertTrue(center > 0)
        assertEquals(blue(pixels[10 * size + 7]), blue(pixels[10 * size + 13]))
        assertEquals(blue(pixels[7 * size + 10]), blue(pixels[13 * size + 10]))
        assertEquals(blue(pixels[10 * size + 7]), blue(pixels[7 * size + 10]))
        assertTrue(blue(pixels[10 * size + 7]) < center)
        assertEquals(black, pixels[0])
        assertTrue(pixels.all { it ushr 24 == 0xFF })
    }

    @Test
    fun `an edge blurs into a slope`() {
        val width = 16
        val pixels = IntArray(width) { if (it < width / 2) black else white }

        pixels.boxBlur(width, 1, radius = 2)

        val slope = pixels.map(::blue)
        assertEquals(0, slope.first())
        assertEquals(0xFF, slope.last())
        assertTrue(slope.zipWithNext().all { (left, right) -> left <= right })
        assertTrue(slope[width / 2 - 1] in 1 until 0xFF)
    }

    @Test
    fun `the boxes blur about as much as Android's blur`() {
        assertEquals(1, boxRadiusFor(0.1f))
        assertEquals(3, boxRadiusFor(3.96f))
        assertEquals(4, boxRadiusFor(4.61f))
    }

    @Test
    fun `the screens of the navigation host are pages, apart from the home screen, web pages and the welcome`() {
        assertTrue(PageBackdrop.isPage(inHost<SettingsFragment>()))
        assertTrue(PageBackdrop.isPage(inHost<HistoryFragment>()))
        assertTrue(PageBackdrop.isPage(inHost<Fragment>()))

        assertFalse(PageBackdrop.isPage(inHost<HomeFragment>()))
        assertFalse(PageBackdrop.isPage(inHost<BrowserFragment>()))
        assertFalse(PageBackdrop.isPage(inHost<DejavuWelcomeFragment>()))
    }

    @Test
    fun `dialogs and screens outside the navigation host are no pages`() {
        assertFalse(PageBackdrop.isPage(inHost<DialogFragment>()))
        assertFalse(PageBackdrop.isPage(mockk<Fragment>(relaxed = true) { every { parentFragment } returns null }))
    }

    private inline fun <reified F : Fragment> inHost(): F = mockk(relaxed = true) {
        every { parentFragment } returns NavHostFragment()
    }

    private fun blue(color: Int) = color and 0xFF
}
