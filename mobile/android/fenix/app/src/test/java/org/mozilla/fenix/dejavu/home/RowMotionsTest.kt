/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowMotionsTest {
    private val rows = mapOf("pin:p" to setOf("a"), "tab:b" to setOf("b"), "tab:c" to setOf("c"))
    private val open = setOf("a", "b", "c")

    private fun plan(
        now: Map<String, Set<String>>,
        openTabIds: Set<String>,
        returning: Set<String> = emptySet(),
        clearing: Boolean = false,
    ) = planRows(rows, now, openTabIds, returning, clearing)

    @Test
    fun `nothing moves the first time the rows are seen`() {
        assertTrue(planRows(null, rows, open, returning = setOf("a"), clearing = true).isEmpty)
    }

    @Test
    fun `nothing moves while the rows stay`() {
        assertTrue(plan(rows, open).isEmpty)
    }

    @Test
    fun `the row of a tab that closed closes`() {
        val plan = plan(rows - "tab:b", open - "b")

        assertEquals(listOf("tab:b"), plan.closed)
        assertFalse(plan.clear)
    }

    @Test
    fun `the rows going away after a tap on Clear are cleared together`() {
        val plan = plan(rows - "tab:b" - "tab:c", setOf("a"), clearing = true)

        assertEquals(listOf("tab:b", "tab:c"), plan.closed)
        assertTrue(plan.clear)
    }

    @Test
    fun `the row of a tab that went to another workspace just goes`() {
        assertTrue(plan(rows - "tab:b", open).isEmpty)
    }

    @Test
    fun `a pinned tab whose row goes away without an open tab does not close`() {
        val closedPin = mapOf("pin:q" to emptySet<String>())

        assertTrue(planRows(rows + closedPin, rows, open, emptySet(), clearing = false).isEmpty)
    }

    @Test
    fun `a tab that is pinned moves from its row to the pinned one`() {
        val plan = plan(rows - "tab:b" + ("pin:r" to setOf("b")), open)

        assertEquals(mapOf("pin:r" to "tab:b"), plan.moved)
        assertTrue(plan.closed.isEmpty())
    }

    @Test
    fun `a tab that is unpinned moves from its pinned row to a row of its own`() {
        val plan = plan(rows - "pin:p" + ("tab:a" to setOf("a")), open)

        assertEquals(mapOf("tab:a" to "pin:p"), plan.moved)
    }

    @Test
    fun `a tab coming back is reopened, a new one just shows`() {
        val now = rows + ("tab:d" to setOf("d")) + ("tab:e" to setOf("e"))
        val plan = plan(now, open + "d" + "e", returning = setOf("d"))

        assertEquals(listOf("tab:d"), plan.reopened)
    }
}
