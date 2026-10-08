/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import mozilla.components.browser.state.state.ContainerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.workspaces.PinKind
import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.Workspace
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState

class EssentialsSeparationTest {
    private val containers = listOf(
        ContainerRecord(WORK, "Work", ContainerColor.ORANGE, ContainerState.Icon.BRIEFCASE, 1L, 1L),
        ContainerRecord(RESEARCH, "Research", ContainerColor.CYAN, ContainerState.Icon.CIRCLE, 1L, 1L),
        ContainerRecord(
            TEMPORARY,
            "Temporary",
            ContainerColor.WHITE,
            ContainerState.Icon.CIRCLE,
            createdAt = 1L,
            updatedAt = 1L,
            temporary = true,
        ),
    )

    /** What spaces with the default containers [spaces] and essentials in the containers [essentials] show. */
    private fun separates(spaces: List<String?>, essentials: List<String?>): Boolean? {
        val workspaces = spaces.mapIndexed { index, container ->
            Workspace("space-$index", "Space $index", container, createdAt = 1L, updatedAt = 1L)
        }
        val pins = essentials.mapIndexed { index, container ->
            PinnedItem(
                id = "essential-$index",
                workspaceId = null,
                parentId = null,
                kind = PinKind.TAB,
                title = "Essential $index",
                url = "https://$index.example/",
                containerId = container,
                essential = true,
                createdAt = 1L,
                updatedAt = 1L,
            )
        }
        return LocalSpaces(WorkspaceState(workspaces, pins, workspaces.first().id, emptyMap()), containers)
            .separatesEssentials()
    }

    @Test
    fun `essentials split between spaces of different containers are kept per container`() {
        assertEquals(true, separates(spaces = listOf(null, WORK), essentials = listOf(null, WORK, null)))
        assertEquals(true, separates(spaces = listOf(WORK, RESEARCH), essentials = listOf(RESEARCH, WORK)))
    }

    @Test
    fun `an essential in a container no space has as its default means essentials are shared`() {
        assertEquals(false, separates(spaces = listOf(null, WORK), essentials = listOf(null, WORK, RESEARCH)))
    }

    @Test
    fun `essentials that only one kind of space would show mean essentials are shared`() {
        assertEquals(false, separates(spaces = listOf(null, WORK), essentials = listOf(null, null)))
        assertEquals(false, separates(spaces = listOf(null, WORK), essentials = listOf(WORK)))
        assertEquals(false, separates(spaces = listOf(WORK), essentials = listOf(WORK, null)))
    }

    @Test
    fun `nothing is decided while the setting would not change which essentials a space shows`() {
        assertNull(separates(spaces = listOf(null, WORK), essentials = emptyList()))
        assertNull(separates(spaces = listOf(null, null), essentials = listOf(null, WORK)))
        assertNull(separates(spaces = listOf(WORK, WORK), essentials = listOf(WORK, WORK)))
    }

    @Test
    fun `temporary containers, which are not synced, count as no container`() {
        assertEquals(true, separates(spaces = listOf(null, WORK), essentials = listOf(TEMPORARY, WORK)))
        assertNull(separates(spaces = listOf(TEMPORARY, null), essentials = listOf(null, WORK)))
    }

    private companion object {
        const val WORK = "work"
        const val RESEARCH = "research"
        const val TEMPORARY = "temporary"
    }
}
