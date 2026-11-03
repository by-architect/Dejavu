/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingColorsTest {
    private val red = 0xFFD32F2F.toInt()
    private val green = 0xFF2E7D32.toInt()
    private val blue = 0xFF1565C0.toInt()

    @Test
    fun `the rainbow and choices without colors show the rainbow`() {
        assertNull(loadingGradientOf(LoadingColorsChoice.RAINBOW, listOf(red), listOf(red), dark = true))
        // A private tab, or a workspace with neither a wallpaper nor a theme.
        assertNull(loadingGradientOf(LoadingColorsChoice.WORKSPACE, null, listOf(red), dark = true))
        assertNull(loadingGradientOf(LoadingColorsChoice.WORKSPACE, emptyList(), listOf(red), dark = true))
        // Custom colors before any were picked.
        assertNull(loadingGradientOf(LoadingColorsChoice.CUSTOM, listOf(red), emptyList(), dark = true))
    }

    @Test
    fun `each choice takes its own colors`() {
        val workspace = loadingGradientOf(LoadingColorsChoice.WORKSPACE, listOf(red, green), listOf(blue), dark = true)
        val custom = loadingGradientOf(LoadingColorsChoice.CUSTOM, listOf(red, green), listOf(blue), dark = false)

        assertEquals(readableOn(Color(red), dark = true), workspace!!.first())
        assertEquals(readableOn(Color(blue), dark = false), custom!!.first())
    }

    @Test
    fun `the gradient runs back to where it started so the band repeats without a seam`() {
        val colors = loadingGradientOf(LoadingColorsChoice.CUSTOM, null, listOf(red, green, blue), dark = false)!!

        assertEquals(5, colors.size)
        assertEquals(colors.first(), colors.last())
        assertEquals(colors[1], colors[3])
    }

    @Test
    fun `a single color runs between itself and the letters' own color`() {
        val colors = loadingGradientOf(LoadingColorsChoice.CUSTOM, null, listOf(blue), dark = false)!!

        assertEquals(3, colors.size)
        assertEquals(colors.first(), colors.last())
        assertEquals(1f, colors.first().alpha)
        assertTrue(colors[1].alpha < 1f)
    }

    @Test
    fun `pale colors darken over a light bar and dark colors lighten over a dark one`() {
        val pale = Color(0xFFFFEDC2)
        val navy = Color(0xFF0A1440)

        assertTrue(readableOn(pale, dark = false).luminance() <= 0.25f)
        assertTrue(readableOn(navy, dark = true).luminance() >= 0.25f)
        // Colors that already read well stay as they are.
        assertEquals(pale, readableOn(pale, dark = true))
        assertEquals(navy, readableOn(navy, dark = false))
    }

    @Test
    fun `an unknown saved choice is the rainbow`() {
        assertEquals(LoadingColorsChoice.RAINBOW, LoadingColorsChoice.fromKey(null))
        assertEquals(LoadingColorsChoice.RAINBOW, LoadingColorsChoice.fromKey("sparkles"))
        LoadingColorsChoice.entries.forEach { assertEquals(it, LoadingColorsChoice.fromKey(it.key)) }
    }
}
