/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.workspaces

import android.content.Context
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.sync.BuiltinContainer
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StarterWorkspacesTest {
    private fun newRepository(): WorkspaceRepository =
        WorkspaceRepository::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(testContext)

    private fun WorkspaceRepository.withStarters() = apply { giveStarters(workName = "Work", folderName = "Tools") }

    @Test
    fun `a fresh install gets a folder of pinned tabs and essentials in Home, and Work in the Work container`() {
        val repository = newRepository()
        val home = repository.state.value.workspaces.single()

        val state = repository.withStarters().state.value

        val work = state.workspaces.last()
        assertEquals(listOf(home.id, work.id), state.workspaces.map { it.id })
        assertEquals("Work", work.name)
        assertEquals(BuiltinContainer.WORK.guid, work.containerId)
        val folder = state.pins.single { it.isFolder }
        assertEquals("Tools", folder.title)
        assertEquals(home.id, folder.workspaceId)
        assertEquals(StarterWorkspaces.Tools.map { it.url }, state.children(home.id, folder.id).map { it.url })
        assertEquals(StarterWorkspaces.Essentials.map { it.url }, state.essentials.map { it.url })
        assertEquals((state.pins.map { it.id } + work.id).toSet(), state.starters)
        assertEquals(home.id, state.activeWorkspaceId)
    }

    @Test
    fun `Home starts with a house and Work with a briefcase, and Home still counts as new`() {
        val workspaces = newRepository().withStarters().state.value.workspaces

        assertEquals(Workspace.HomeIcon, workspaces.first().icon)
        assertEquals(StarterWorkspaces.WorkIcon, workspaces.last().icon)
        assertTrue(workspaces.first().isLikeNew(testContext.getString(R.string.dejavu_workspace_default_name)))
    }

    @Test
    fun `the examples come only once`() {
        val repository = newRepository().withStarters()
        val given = repository.state.value

        repository.withStarters()

        assertEquals(given, repository.state.value)
    }

    @Test
    fun `the examples come only to an untouched Home`() {
        val repository = newRepository()
        val home = repository.state.value.activeWorkspaceId
        repository.updateWorkspace(home, "Main", containerId = null, icon = null, theme = null)

        repository.withStarters()

        assertEquals(1, repository.state.value.workspaces.size)
        assertTrue(repository.state.value.pins.isEmpty())
    }

    @Test
    fun `the examples are kept with the workspaces`() {
        val starters = newRepository().withStarters().state.value.starters

        assertEquals(starters, newRepository().state.value.starters)
    }

    @Test
    fun `untouched examples are not the user's own, and go before syncing with Zen`() {
        val repository = newRepository().withStarters()
        val home = repository.state.value.workspaces.first()

        val own = repository.state.value.withoutStarters()
        assertEquals(listOf(home), own.workspaces)
        assertTrue(own.pins.isEmpty())
        assertTrue(own.starters.isEmpty())

        repository.dropStarters()
        assertEquals(own.workspaces, repository.state.value.workspaces)
        assertTrue(repository.state.value.pins.isEmpty())
    }

    @Test
    fun `examples the user changed or filled stay, with what is in them`() {
        val repository = newRepository().withStarters()
        val state = repository.state.value
        val home = state.workspaces.first()
        val work = state.workspaces.last()
        val folder = state.pins.single { it.isFolder }
        val youTube = state.essentials.first()
        Thread.sleep(LATER_MS)
        repository.renameFolder(folder.id, "Daily")
        repository.renamePin(youTube.id, "Videos")
        repository.assignTab("open", work.id)

        val own = repository.state.value.withoutStarters()

        assertEquals(listOf(home.id, work.id), own.workspaces.map { it.id })
        assertEquals(StarterWorkspaces.Tools.size, own.children(home.id, folder.id).size)
        assertEquals(listOf(youTube.id), own.essentials.map { it.id })
    }

    @Test
    fun `an example whose page told its title stays as it came`() {
        val repository = newRepository().withStarters()
        val reddit = repository.state.value.essentials.last()
        repository.attachPinned(reddit.id, "reddit")
        Thread.sleep(LATER_MS)

        repository.refreshPinTitles(mapOf("reddit" to (reddit.url!! to "Reddit - Dive into anything")))

        assertEquals("Reddit - Dive into anything", repository.state.value.pinOf("reddit")?.title)
        assertTrue(repository.state.value.withoutStarters().essentials.isEmpty())
    }

    private companion object {
        // Long enough for a change to come a millisecond after the examples.
        const val LATER_MS = 5L
    }
}
