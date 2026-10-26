/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageGenieTest {
    private val page = Rect(0f, 0f, 1000f, 2000f)
    private val columns = 4
    private val rows = 10

    private fun mesh(button: Rect, tau: Float) =
        FloatArray((columns + 1) * (rows + 1) * 2).also { genieMesh(page, button, tau, columns, rows, it) }

    /** The left and right ends of [row] of [verts]. */
    private fun ends(verts: FloatArray, row: Int): Pair<Float, Float> {
        val first = row * (columns + 1) * 2
        return verts[first] to verts[first + columns * 2]
    }

    private fun yOf(verts: FloatArray, row: Int) = verts[row * (columns + 1) * 2 + 1]

    @Test
    fun `at the start the page is in its place`() {
        val verts = mesh(Rect(800f, 1900f, 900f, 2000f), tau = 0f)

        for (row in 0..rows) {
            assertEquals(0f, ends(verts, row).first, 0.01f)
            assertEquals(1000f, ends(verts, row).second, 0.01f)
            assertEquals(row * 200f, yOf(verts, row), 0.01f)
        }
    }

    @Test
    fun `at the end the whole page is inside its button`() {
        val button = Rect(800f, 1900f, 900f, 2000f)
        val verts = mesh(button, tau = 1f)

        for (i in verts.indices step 2) {
            assertTrue(verts[i] in button.left - 0.01f..button.right + 0.01f)
            assertTrue(verts[i + 1] in button.top - 0.01f..button.bottom + 0.01f)
        }
    }

    @Test
    fun `a button below the page narrows its bottom into a funnel first`() {
        val button = Rect(800f, 1900f, 900f, 2000f)
        val verts = mesh(button, tau = 0.4f)

        val (topLeft, topRight) = ends(verts, 0)
        val (bottomLeft, bottomRight) = ends(verts, rows)
        assertTrue(topRight - topLeft > 800f)
        assertEquals(button.left, bottomLeft, 0.01f)
        assertEquals(button.right, bottomRight, 0.01f)
    }

    @Test
    fun `a row going into a button at its end narrows towards the button along the row`() {
        val row = Rect(0f, 100f, 1000f, 200f)
        val button = Rect(900f, 140f, 920f, 160f)

        val placed = genieLine(row, button, tau = 0f, fraction = 0f, downThePage = false)
        assertEquals(0f, placed.along, 0.01f)
        assertEquals(100f, placed.start, 0.01f)
        assertEquals(200f, placed.end, 0.01f)

        val start = genieLine(row, button, tau = 0.4f, fraction = 0f, downThePage = false)
        val end = genieLine(row, button, tau = 0.4f, fraction = 1f, downThePage = false)
        assertTrue(start.end - start.start > 80f)
        assertTrue(start.along < end.along)
        assertEquals(button.top, end.start, 0.01f)
        assertEquals(button.bottom, end.end, 0.01f)
    }

    @Test
    fun `a row is all inside the button at its end at the end`() {
        val row = Rect(0f, 100f, 1000f, 200f)
        val button = Rect(900f, 140f, 920f, 160f)

        for (fraction in listOf(0f, 0.5f, 1f)) {
            val line = genieLine(row, button, tau = 1f, fraction = fraction, downThePage = false)
            assertTrue(line.along in button.left - 0.01f..button.right + 0.01f)
            assertEquals(button.top, line.start, 0.01f)
            assertEquals(button.bottom, line.end, 0.01f)
        }
    }

    @Test
    fun `a genie fades only as it goes into its button`() {
        assertEquals(1f, genieAlpha(0f), 0.001f)
        assertEquals(1f, genieAlpha(0.8f), 0.001f)
        assertEquals(0f, genieAlpha(1f), 0.001f)
    }

    @Test
    fun `a button above the page turns the funnel upwards`() {
        val button = Rect(0f, 0f, 100f, 100f)
        val verts = mesh(button, tau = 0.4f)

        val (topLeft, topRight) = ends(verts, 0)
        val (bottomLeft, bottomRight) = ends(verts, rows)
        assertEquals(button.left, topLeft, 0.01f)
        assertEquals(button.right, topRight, 0.01f)
        assertTrue(bottomRight - bottomLeft > 800f)
    }
}
