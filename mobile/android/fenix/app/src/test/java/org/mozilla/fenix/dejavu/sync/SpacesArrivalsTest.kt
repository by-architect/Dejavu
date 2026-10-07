/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.sync.ZenRecords.CONTAINER
import org.mozilla.fenix.dejavu.sync.ZenRecords.FOLDER
import org.mozilla.fenix.dejavu.sync.ZenRecords.SPACE_PERSONAL
import org.mozilla.fenix.dejavu.sync.ZenRecords.SPACE_WORK
import org.mozilla.fenix.dejavu.sync.ZenRecords.SPLIT
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_ESSENTIAL
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_ESSENTIAL_CONTAINER
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_NORMAL
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_PINNED
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_SPLIT_LEFT
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_SPLIT_RIGHT
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.robolectric.RobolectricTestRunner

/**
 * Zen lists a new tab in the order of its place, the layout's essentials or its space's children, as soon as it is
 * added, but uploads the tab's own record only once its page has an address. The tab then arrives on its own, after
 * the order that places it.
 */
@RunWith(RobolectricTestRunner::class)
class SpacesArrivalsTest {
    private val containerIds = ZenRecords.dejavuContainers().map { it.contextId }.toSet()

    /** Applies Zen's records but [held], as a first sync, and returns the state with the server's records. */
    private fun firstSync(records: List<SpacesRecord>, held: String): Pair<WorkspaceState, Map<String, SpacesRecord>> {
        val applier = SpacesApplier(emptyMap(), containerIds, firstSync = true, defaultName = "Home", now = 10L)
        val sent = records.filter { it.kind != RecordKind.CONTAINER && it.id != held }
        val result = applier.apply(ZenRecords.freshDejavu(), sent)
        assertEquals(emptyList<String>(), result.failed.map { it.id })
        return result.state to records.filter { it.id != held }.associateBy { it.id }
    }

    private fun later(state: WorkspaceState, server: Map<String, SpacesRecord>, record: SpacesRecord): WorkspaceState {
        val result = SpacesApplier(server, containerIds, firstSync = false, defaultName = "Home", now = 20L)
            .apply(state, listOf(record))
        assertEquals(emptyList<String>(), result.failed.map { it.id })
        return result.state
    }

    @Test
    fun `an essential whose record comes after the layout goes where the layout lists it`() {
        val records = ZenRecords.desktop().filter { it.id != LAYOUT_RECORD_ID } + listOf(
            ZenRecords.tab(OTHER_ESSENTIAL, "https://calendar.example/", null, essential = true),
            ZenRecords.layout(
                spaces = listOf(SPACE_PERSONAL, SPACE_WORK),
                essentials = mapOf(
                    "default" to listOf(TAB_ESSENTIAL, NEW_ESSENTIAL, OTHER_ESSENTIAL),
                    CONTAINER to listOf(TAB_ESSENTIAL_CONTAINER),
                ),
            ),
        )
        val newEssential = ZenRecords.tab(NEW_ESSENTIAL, "https://notes.example/", null, essential = true)
        val (state, server) = firstSync(records + newEssential, held = NEW_ESSENTIAL)
        assertEquals(listOf(TAB_ESSENTIAL, TAB_ESSENTIAL_CONTAINER, OTHER_ESSENTIAL), state.essentials.map { it.id })

        val synced = later(state, server, newEssential)

        assertEquals(
            listOf(TAB_ESSENTIAL, TAB_ESSENTIAL_CONTAINER, NEW_ESSENTIAL, OTHER_ESSENTIAL),
            synced.essentials.map { it.id },
        )
    }

    @Test
    fun `a pinned tab whose record comes after its space goes where the space lists it`() {
        val records = ZenRecords.desktop().map { record ->
            if (record.id != SPACE_PERSONAL) return@map record
            ZenRecords.space(
                SPACE_PERSONAL,
                "Personal",
                children = listOf(TAB_PINNED, NEW_PIN, FOLDER, SPLIT, TAB_NORMAL),
                icon = ZenRecords.STAR_ICON,
                theme = ZenRecords.theme,
            )
        }
        val newPin = ZenRecords.tab(NEW_PIN, "https://new.example/", SPACE_PERSONAL)
        val (state, server) = firstSync(records + newPin, held = NEW_PIN)

        val synced = later(state, server, newPin)

        assertEquals(
            listOf(TAB_PINNED, NEW_PIN, FOLDER, TAB_SPLIT_LEFT, TAB_SPLIT_RIGHT),
            synced.children(SPACE_PERSONAL, null).map { it.id },
        )
    }

    @Test
    fun `arrivals move next to their neighbours while everything else keeps its place`() {
        assertEquals(listOf("a", "x", "b", "c"), placeArrivals(listOf("a", "b", "c", "x"), listOf("a", "x", "b", "c"), setOf("x")))
        assertEquals(listOf("x", "a", "b"), placeArrivals(listOf("a", "b", "x"), listOf("x", "a", "b"), setOf("x")))
        // Only the arrival moves, even where the rest is in another order than the server's.
        assertEquals(listOf("b", "a", "x", "c"), placeArrivals(listOf("b", "a", "c", "x"), listOf("a", "x", "b", "c"), setOf("x")))
        // An arrival the order does not list stays where it is.
        assertEquals(listOf("a", "b", "x"), placeArrivals(listOf("a", "b", "x"), listOf("b", "a"), setOf("x")))
    }

    private companion object {
        const val NEW_ESSENTIAL = "1727000000300-0a0b0c0d-0e0f-4a1b-8c2d-3e4f5a6b7c8d"
        const val OTHER_ESSENTIAL = "1727000000301-1a1b1c1d-1e1f-4a2b-8c3d-4e5f6a7b8c9d"
        const val NEW_PIN = "1727000000302-2a2b2c2d-2e2f-4a3b-8c4d-5e6f7a8b9cad"
    }
}
