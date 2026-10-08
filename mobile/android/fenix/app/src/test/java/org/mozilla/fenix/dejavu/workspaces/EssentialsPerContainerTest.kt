/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.workspaces

import android.content.Context
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EssentialsPerContainerTest {
    private fun newRepository(): WorkspaceRepository =
        WorkspaceRepository::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(testContext)

    private fun source(id: String, containerId: String?) = PinSource(id, "https://$id.example/", id, containerId)

    /** A workspace without a container and a work workspace, with essentials in no, another and the work container. */
    private fun repositoryWithEssentials(): WorkspaceRepository {
        val repository = newRepository()
        repository.addWorkspace("Work", containerId = WORK, icon = null, theme = null)
        repository.addToEssentials(
            sources = listOf(source(MAIL, null), source(DOCS, RESEARCH), source(CHAT, WORK)),
            pinIds = emptySet(),
            perContainer = true,
        )
        return repository
    }

    @Test
    fun `a workspace without a container also shows the essentials of containers no workspace has`() {
        val state = repositoryWithEssentials().state.value
        val (home, work) = state.workspaces

        assertEquals(listOf(MAIL, DOCS), state.essentialsFor(home.containerId, perContainer = true).map { it.id })
        assertEquals(listOf(CHAT), state.essentialsFor(work.containerId, perContainer = true).map { it.id })
        assertEquals(listOf(MAIL, DOCS, CHAT), state.essentials.map { it.id })
        assertEquals(state.essentials, state.essentialsFor(work.containerId, perContainer = false))
    }

    @Test
    fun `essentials move among the ones shown with them`() {
        val repository = repositoryWithEssentials()

        repository.moveEssential(DOCS, index = 0, perContainer = true)

        assertEquals(listOf(DOCS, MAIL, CHAT), repository.state.value.essentials.map { it.id })
    }

    @Test
    fun `essentials shown together share their limit`() {
        val repository = repositoryWithEssentials()

        repository.addToEssentials(
            sources = (1..MAX_ESSENTIALS).map { source("more$it", RESEARCH) },
            pinIds = emptySet(),
            perContainer = true,
        )

        val state = repository.state.value
        assertEquals(MAX_ESSENTIALS, state.essentialsFor(null, perContainer = true).size)
        assertEquals(listOf(CHAT), state.essentialsFor(WORK, perContainer = true).map { it.id })
    }

    private companion object {
        const val WORK = "work"
        const val RESEARCH = "research"
        const val MAIL = "mail"
        const val DOCS = "docs"
        const val CHAT = "chat"
    }
}
