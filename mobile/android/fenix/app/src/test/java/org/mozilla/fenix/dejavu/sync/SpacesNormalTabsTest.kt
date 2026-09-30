/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.sync.ZenRecords.SPACE_PERSONAL
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_NORMAL
import org.mozilla.fenix.dejavu.workspaces.PinKind
import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpacesNormalTabsTest {
    private val containers = ZenRecords.dejavuContainers()
    private val containerIds = containers.map { it.contextId }.toSet()
    private val news = "https://news.example/"
    private val newsTitle = "Title of $news"

    private fun apply(
        state: WorkspaceState,
        records: List<SpacesRecord>,
        tabs: List<LocalTab>?,
        normalTabs: Boolean = true,
        server: Map<String, SpacesRecord> = emptyMap(),
    ): ApplyResult = SpacesApplier(
        server = server,
        containerIds = containerIds,
        firstSync = server.isEmpty(),
        defaultName = "Home",
        now = 10L,
        normalTabs = normalTabs,
        tabs = tabs?.associateBy { it.id },
    ).apply(state, records.filter { it.kind != RecordKind.CONTAINER })

    private fun desktop(tabs: List<LocalTab>? = emptyList(), normalTabs: Boolean = true) =
        apply(ZenRecords.freshDejavu(), ZenRecords.desktop(), tabs, normalTabs)

    private fun changes(
        state: WorkspaceState,
        tabs: List<LocalTab>?,
        seen: Map<String, String> = emptyMap(),
        server: Map<String, SpacesRecord> = ZenRecords.desktop().associateBy { it.id },
    ): Map<String, SpacesRecord> =
        SpacesProjector(LocalSpaces(state, containers, normalTabs = true, tabs = tabs), server, seen)
            .project()
            .changesAgainst(server)
            .associateBy { it.id }

    private fun tab(id: String, url: String = news, title: String = newsTitle, awake: Boolean = false) =
        LocalTab(id, url, title, contextId = null, awake = awake)

    @Test
    fun `without unpinned tabs, Zen's unpinned tabs are neither opened nor deleted`() {
        val result = desktop(normalTabs = false)

        assertTrue(result.tabOps.isEmpty())
        val server = ZenRecords.desktop().associateBy { it.id }
        val changes = SpacesProjector(LocalSpaces(result.state, containers), server).project().changesAgainst(server)
        assertTrue(changes.none { it.id == TAB_NORMAL })
    }

    @Test
    fun `Zen's unpinned tabs open without loading in their workspace`() {
        val result = desktop()

        assertEquals(listOf(TabOp.Open(TAB_NORMAL, news, newsTitle, null, SPACE_PERSONAL)), result.tabOps)
        assertEquals(SPACE_PERSONAL, result.state.assignments[TAB_NORMAL])
        assertTrue(result.state.pins.none { it.id == TAB_NORMAL })
    }

    @Test
    fun `unpinned tabs wait until the browser restored its tabs`() {
        val result = desktop(tabs = null)

        assertTrue(result.tabOps.isEmpty())
        assertEquals(listOf(TAB_NORMAL), result.failed.map { it.id })
    }

    @Test
    fun `an unpinned tab goes out in its workspace, after the pinned items`() {
        val state = desktop().state.let { it.copy(assignments = it.assignments + ("local-1" to SPACE_PERSONAL)) }
        val tabs = listOf(tab(TAB_NORMAL), tab("local-1", "https://local.example/", "Local"))

        val changes = changes(state, tabs)

        val record = changes.getValue("local-1").data!!
        assertEquals(false, record.opt("pinned"))
        assertEquals(SPACE_PERSONAL, record.string("workspaceUuid"))
        assertEquals("https://local.example/", record.string("url"))
        assertFalse(TAB_NORMAL in changes)
        assertEquals(TAB_NORMAL to "local-1", changes.getValue(SPACE_PERSONAL).data!!.strings("children")!!.takeLast(2).let { it[0] to it[1] })
    }

    @Test
    fun `a tab closed here is deleted on the server, one never open here is kept`() {
        val state = desktop().state

        assertTrue(changes(state, tabs = emptyList(), seen = mapOf(TAB_NORMAL to tab(TAB_NORMAL).fingerprint)).getValue(TAB_NORMAL).deleted)
        assertFalse(TAB_NORMAL in changes(state, tabs = emptyList()))
        assertFalse(TAB_NORMAL in changes(state, tabs = null, seen = mapOf(TAB_NORMAL to tab(TAB_NORMAL).fingerprint)))
    }

    @Test
    fun `a page loaded here is not sent back after another device moved on, only a page opened here since`() {
        val state = desktop().state
        val older = tab(TAB_NORMAL, "https://older.example/", "Older", awake = true)
        val seen = mapOf(TAB_NORMAL to older.fingerprint)

        assertFalse(TAB_NORMAL in changes(state, listOf(older), seen))
        val newer = tab(TAB_NORMAL, "https://newer.example/", "Newer", awake = true)
        assertEquals("https://newer.example/", changes(state, listOf(newer), seen).getValue(TAB_NORMAL).data!!.string("url"))
    }

    @Test
    fun `an unloaded tab moves to the address given elsewhere, and closes when deleted elsewhere`() {
        val state = desktop().state
        val server = ZenRecords.desktop().associateBy { it.id }
        val unloaded = tab(TAB_NORMAL, "https://older.example/", "Older")
        val record = ZenRecords.tab(TAB_NORMAL, news, SPACE_PERSONAL, pinned = false)

        assertEquals(
            listOf(TabOp.Retarget(TAB_NORMAL, news, newsTitle)),
            apply(state, listOf(record), listOf(unloaded), server = server).tabOps,
        )
        assertTrue(apply(state, listOf(record), listOf(unloaded.copy(awake = true)), server = server).tabOps.isEmpty())
        assertEquals(
            listOf(TabOp.Close(TAB_NORMAL)),
            apply(state, listOf(SpacesRecord.tombstone(TAB_NORMAL)), listOf(unloaded), server = server).tabOps,
        )
    }

    @Test
    fun `a tab pinned elsewhere pins the tab open here`() {
        val state = desktop().state
        val server = ZenRecords.desktop().associateBy { it.id }
        val record = ZenRecords.tab(TAB_NORMAL, news, SPACE_PERSONAL, pinned = true)

        val result = apply(state, listOf(record), listOf(tab(TAB_NORMAL)), server = server)

        assertEquals(TAB_NORMAL, result.state.pins.single { it.id == TAB_NORMAL }.tabId)
        assertTrue(result.tabOps.isEmpty())
    }

    @Test
    fun `a tab unpinned elsewhere stays open here and syncs under the pin's id`() {
        val pin = PinnedItem(
            id = "pin-1",
            workspaceId = SPACE_PERSONAL,
            parentId = null,
            kind = PinKind.TAB,
            title = "Pinned",
            url = news,
            tabId = "tab-9",
            createdAt = 1L,
            updatedAt = 1L,
        )
        val state = desktop().state.let { it.copy(pins = it.pins + pin) }
        val record = ZenRecords.tab("pin-1", news, SPACE_PERSONAL, pinned = false)

        val result = apply(state, listOf(record), listOf(tab("tab-9")), server = mapOf("x" to record))

        assertNull(result.state.pins.firstOrNull { it.id == "pin-1" })
        assertEquals("pin-1", result.state.syncIdOf("tab-9"))
        assertEquals(SPACE_PERSONAL, result.state.assignments["tab-9"])
        assertTrue(result.tabOps.isEmpty())
    }
}
