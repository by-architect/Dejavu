/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import android.content.Context
import mozilla.components.browser.state.state.createTab
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.workspaces.PinSource
import org.mozilla.fenix.dejavu.workspaces.SyncedDevice
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncStartTest {
    private fun newRepository(): WorkspaceRepository =
        WorkspaceRepository::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(testContext)

    private fun tab(id: String) = createTab(url = "https://$id.example/", id = id, title = id)

    private fun source(tabId: String) = PinSource(tabId, "https://$tabId.example/", tabId, containerId = null)

    @Test
    fun `a fresh Dejavu has nothing of its own to merge`() {
        val state = newRepository().state.value

        assertNull(OwnData.of(state, emptyList(), DEFAULT_NAME))
    }

    @Test
    fun `the examples a fresh Dejavu starts with are nothing to merge, until changed`() {
        val repository = newRepository()
        repository.giveStarters(workName = "Work", folderName = "Tools")

        assertNull(OwnData.of(repository.state.value, emptyList(), DEFAULT_NAME))

        Thread.sleep(LATER_MS)
        val work = repository.state.value.workspaces.last()
        repository.updateWorkspace(work.id, "Office", work.containerId, icon = null, theme = work.theme)
        assertEquals(
            OwnData(workspaces = 2, pinned = 0, essentials = 0, folders = 0, tabs = 0),
            OwnData.of(repository.state.value, emptyList(), DEFAULT_NAME),
        )
    }

    @Test
    fun `open tabs, pinned tabs, folders, essentials and workspaces are counted`() {
        val repository = newRepository()
        val home = repository.state.value.activeWorkspaceId
        repository.pinTabs(listOf(source("pinned"), source("inside")))
        val inside = repository.state.value.pinOf("inside")!!.id
        repository.createFolder(home, parentId = null, name = "Folder", itemIds = setOf(inside))
        repository.addToEssentials(listOf(source("mail")), emptySet(), perContainer = false)
        repository.addWorkspace("Work", containerId = null, icon = null, theme = null)
        val tabs = listOf(tab("pinned"), tab("inside"), tab("mail"), tab("loose"))

        assertEquals(
            OwnData(workspaces = 2, pinned = 2, essentials = 1, folders = 1, tabs = 4),
            OwnData.of(repository.state.value, tabs, DEFAULT_NAME),
        )
    }

    @Test
    fun `a renamed workspace is something to merge, even without tabs`() {
        val repository = newRepository()
        val home = repository.state.value.activeWorkspaceId
        repository.updateWorkspace(home, "Main", containerId = null, icon = null, theme = null)

        assertEquals(
            OwnData(workspaces = 1, pinned = 0, essentials = 0, folders = 0, tabs = 0),
            OwnData.of(repository.state.value, emptyList(), DEFAULT_NAME),
        )
    }

    @Test
    fun `the workspaces of devices and their tabs come from sync, so they are not counted`() {
        val repository = newRepository()
        val fresh = repository.state.value
        val device = fresh.workspaces.single().copy(id = "laptop", name = "Laptop", device = SyncedDevice.DESKTOP)
        val state = fresh.copy(workspaces = fresh.workspaces + device, assignments = mapOf("remote" to "laptop"))

        assertNull(OwnData.of(state, listOf(tab("remote")), DEFAULT_NAME))
    }

    @Test
    fun `starting fresh leaves the empty workspace of a fresh Dejavu, and the workspaces of devices`() {
        val repository = newRepository()
        repository.pinTabs(listOf(source("pinned")))
        repository.addToEssentials(listOf(source("mail")), emptySet(), perContainer = false)
        repository.addWorkspace("Work", containerId = null, icon = null, theme = null)
        repository.assignTab("loose", repository.state.value.activeWorkspaceId)
        repository.update { state ->
            val device = state.workspaces.first().copy(id = "laptop", name = "Laptop", device = SyncedDevice.DESKTOP)
            state.copy(workspaces = state.workspaces + device, assignments = state.assignments + ("remote" to "laptop")) to Unit
        }

        repository.startFresh()

        val state = repository.state.value
        assertEquals(listOf(DEFAULT_NAME, "Laptop"), state.workspaces.map { it.name })
        assertTrue(state.workspaces.first().isLikeNew(DEFAULT_NAME))
        assertEquals(state.workspaces.first().id, state.activeWorkspaceId)
        assertTrue(state.pins.isEmpty())
        assertEquals(mapOf("remote" to "laptop"), state.assignments)
        assertNull(OwnData.of(state, listOf(tab("remote")), DEFAULT_NAME))
    }

    private companion object {
        const val DEFAULT_NAME = "Home"

        // Long enough for a change to come a millisecond after the examples.
        const val LATER_MS = 5L
    }
}
