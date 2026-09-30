/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import mozilla.components.browser.state.state.ContainerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.sync.ZenRecords.CONTAINER
import org.mozilla.fenix.dejavu.sync.ZenRecords.FOLDER
import org.mozilla.fenix.dejavu.sync.ZenRecords.SPACE_PERSONAL
import org.mozilla.fenix.dejavu.sync.ZenRecords.SPACE_WORK
import org.mozilla.fenix.dejavu.sync.ZenRecords.SPLIT
import org.mozilla.fenix.dejavu.sync.ZenRecords.SUBFOLDER
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_ESSENTIAL
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_ESSENTIAL_CONTAINER
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_IN_FOLDER
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_IN_SUBFOLDER
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_NORMAL
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_PINNED
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_RENAMED
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_SPLIT_LEFT
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_SPLIT_RIGHT
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpacesModelTest {
    private val containers = ZenRecords.dejavuContainers()
    private val containerIds = containers.map { it.contextId }.toSet()

    private fun applyDesktop(): Pair<WorkspaceState, Map<String, SpacesRecord>> {
        val records = ZenRecords.desktop()
        val applier = SpacesApplier(emptyMap(), containerIds, firstSync = true, defaultName = "Home", now = 10L)
        val result = applier.apply(ZenRecords.freshDejavu(), records.filter { it.kind != RecordKind.CONTAINER })
        assertEquals(emptyList<String>(), result.failed.map { it.id })
        val server = records.associateBy { it.id }
        return result.state to server
    }

    private fun changes(state: WorkspaceState, server: Map<String, SpacesRecord>, local: List<ContainerRecord> = containers) =
        SpacesProjector(LocalSpaces(state, local), server).project().changesAgainst(server)

    @Test
    fun `Zen's spaces become Dejavu workspaces`() {
        val (state, _) = applyDesktop()

        assertEquals(listOf(SPACE_PERSONAL, SPACE_WORK), state.workspaces.map { it.id })
        assertEquals(SPACE_PERSONAL, state.activeWorkspaceId)
        val personal = state.workspaces.first()
        assertEquals(ZenRecords.STAR_ICON, personal.icon)
        assertEquals(
            WorkspaceTheme(colors = listOf(0xFF7C85FF.toInt(), 0xFFFFCB7C.toInt()), opacity = 0.65f, texture = 0.2f),
            personal.theme,
        )
        assertEquals("builtin-2", state.workspaces[1].containerId)

        assertEquals(
            listOf(TAB_PINNED, FOLDER, TAB_SPLIT_LEFT, TAB_SPLIT_RIGHT),
            state.children(SPACE_PERSONAL, null).map { it.id },
        )
        assertEquals(listOf(TAB_IN_FOLDER, SUBFOLDER), state.children(SPACE_PERSONAL, FOLDER).map { it.id })
        assertEquals(listOf(TAB_IN_SUBFOLDER), state.children(SPACE_PERSONAL, SUBFOLDER).map { it.id })
        assertEquals(listOf(TAB_ESSENTIAL, TAB_ESSENTIAL_CONTAINER), state.essentials.map { it.id })
        assertEquals(CONTAINER, state.essentials[1].containerId)

        val renamed = state.pins.single { it.id == TAB_RENAMED }
        assertEquals("Inbox", renamed.title)
        assertTrue(renamed.staticLabel)
        assertTrue(state.pins.none { it.id == TAB_NORMAL })
    }

    @Test
    fun `applied records map back to exactly the same records`() {
        val (state, server) = applyDesktop()

        assertEquals(emptyList<String>(), changes(state, server).map { it.id })
    }

    @Test
    fun `renaming a workspace only changes its record and keeps Zen's theme`() {
        val (state, server) = applyDesktop()
        val renamed = state.copy(
            workspaces = state.workspaces.map { if (it.id == SPACE_PERSONAL) it.copy(name = "Home life") else it },
        )

        val changes = changes(renamed, server)

        assertEquals(listOf(SPACE_PERSONAL), changes.map { it.id })
        val data = changes.single().data!!
        assertEquals("Home life", data.string("name"))
        assertEquals(SyncJson.canonical(ZenRecords.theme), SyncJson.canonical(data.opt("theme")))
        assertEquals(ZenRecords.STAR_ICON, data.string("icon"))
    }

    @Test
    fun `page titles of open pinned tabs are not uploaded while the pinned address stays`() {
        val (state, server) = applyDesktop()
        val retitled = state.copy(
            pins = state.pins.map { if (it.id == TAB_PINNED) it.copy(title = "(4) Inbox") else it },
        )

        assertEquals(emptyList<String>(), changes(retitled, server).map { it.id })

        val repinned = state.copy(
            pins = state.pins.map {
                if (it.id == TAB_PINNED) it.copy(url = "https://example.com/other", title = "Other") else it
            },
        )
        val data = changes(repinned, server).single().data!!
        assertEquals("https://example.com/other", data.string("url"))
        assertEquals("Other", data.string("title"))
    }

    @Test
    fun `the color of temporary containers is never synced`() {
        val white = ContainerRecord("white-one", "Odd", ContainerColor.WHITE, ContainerState.Icon.CIRCLE, 1L, 1L)

        val records = SpacesProjector(LocalSpaces(ZenRecords.freshDejavu(), listOf(white)), emptyMap()).project().records

        assertEquals("gray", records.getValue("white-one").data!!.string("color"))
        assertEquals(ContainerColor.GRAY, containerColorOf("white"))
    }

    @Test
    fun `a new theme keeps the dots whose color did not change`() {
        val (state, server) = applyDesktop()
        val colors = listOf(0xFF7C85FF.toInt(), 0xFF00FF00.toInt())
        val themed = state.copy(
            workspaces = state.workspaces.map {
                if (it.id == SPACE_PERSONAL) it.copy(theme = WorkspaceTheme(colors, opacity = 0.35f)) else it
            },
        )

        val theme = changes(themed, server).single().data!!.getJSONObject("theme")

        val dots = theme.getJSONArray("gradientColors")
        assertEquals(SyncJson.canonical(ZenRecords.theme.getJSONArray("gradientColors").get(0)), SyncJson.canonical(dots.get(0)))
        assertEquals("[0,255,0]", SyncJson.canonical(dots.getJSONObject(1).get("c")))
        assertEquals(0.35, theme.getDouble("opacity"), 0.0)
        assertEquals(0.0, theme.getDouble("texture"), 0.0)
    }

    @Test
    fun `unpinning a split view tab deletes the tab and the split and keeps the other tab in its place`() {
        val (state, server) = applyDesktop()
        val unpinned = state.copy(pins = state.pins.filterNot { it.id == TAB_SPLIT_LEFT })

        val changes = changes(unpinned, server).associateBy { it.id }

        assertEquals(setOf(TAB_SPLIT_LEFT, SPLIT, SPACE_PERSONAL), changes.keys)
        assertTrue(changes.getValue(TAB_SPLIT_LEFT).deleted)
        assertTrue(changes.getValue(SPLIT).deleted)
        assertEquals(
            listOf(TAB_PINNED, FOLDER, TAB_SPLIT_RIGHT, TAB_NORMAL),
            changes.getValue(SPACE_PERSONAL).data!!.strings("children"),
        )
    }

    @Test
    fun `moving a pinned tab into a folder updates the tab and both parents`() {
        val (state, server) = applyDesktop()
        val pinned = state.pins.single { it.id == TAB_PINNED }
        // Like dropping it into the folder, which puts it last.
        val moved = state.copy(pins = state.pins - pinned + pinned.copy(parentId = FOLDER))

        val changes = changes(moved, server).associateBy { it.id }

        assertEquals(setOf(TAB_PINNED, SPACE_PERSONAL, FOLDER), changes.keys)
        assertEquals(FOLDER, changes.getValue(TAB_PINNED).data!!.string("folderId"))
        assertEquals(listOf(FOLDER, SPLIT, TAB_NORMAL), changes.getValue(SPACE_PERSONAL).data!!.strings("children"))
        assertEquals(
            listOf(TAB_IN_FOLDER, SUBFOLDER, TAB_PINNED),
            changes.getValue(FOLDER).data!!.strings("children"),
        )
    }

    @Test
    fun `a Zen change applies without anything to send back`() {
        val (state, server) = applyDesktop()
        val moved = ZenRecords.tab(TAB_PINNED, "https://example.com/new", SPACE_PERSONAL, folder = FOLDER)
        val personal = ZenRecords.space(
            SPACE_PERSONAL,
            "Personal",
            children = listOf(FOLDER, SPLIT, TAB_NORMAL),
            icon = ZenRecords.STAR_ICON,
            theme = ZenRecords.theme,
        )
        val folder = ZenRecords.folder(FOLDER, "Reading", SPACE_PERSONAL, children = listOf(TAB_PINNED, TAB_IN_FOLDER, SUBFOLDER))
        val incoming = listOf(moved, personal, folder)

        val result = SpacesApplier(server, containerIds, firstSync = false, defaultName = "Home", now = 20L)
            .apply(state, incoming)

        val pin = result.state.pins.single { it.id == TAB_PINNED }
        assertEquals("https://example.com/new", pin.url)
        assertEquals(FOLDER, pin.parentId)
        assertEquals(listOf(TAB_PINNED, TAB_IN_FOLDER, SUBFOLDER), result.state.children(SPACE_PERSONAL, FOLDER).map { it.id })
        val updatedServer = server + incoming.associateBy { it.id }
        assertEquals(emptyList<String>(), changes(result.state, updatedServer).map { it.id })
    }

    @Test
    fun `deleted records remove pins and workspaces but keep open tabs`() {
        val (state, server) = applyDesktop()
        val withOpenTab = state.copy(
            pins = state.pins.map { if (it.id == TAB_RENAMED) it.copy(tabId = "open-tab") else it },
        )
        val incoming = listOf(SpacesRecord.tombstone(TAB_RENAMED), SpacesRecord.tombstone(SPACE_WORK))

        val result = SpacesApplier(server, containerIds, firstSync = false, defaultName = "Home", now = 20L)
            .apply(withOpenTab, incoming)

        assertEquals(listOf(SPACE_PERSONAL), result.state.workspaces.map { it.id })
        assertTrue(result.state.pins.none { it.id == TAB_RENAMED })
        assertEquals(SPACE_PERSONAL, result.state.assignments["open-tab"])
        assertEquals("Inbox", result.state.tabTitles["open-tab"])
    }

    @Test
    fun `the last workspace is never deleted by the server`() {
        val (state, server) = applyDesktop()
        val incoming = listOf(SpacesRecord.tombstone(SPACE_WORK), SpacesRecord.tombstone(SPACE_PERSONAL))

        val result = SpacesApplier(server, containerIds, firstSync = false, defaultName = "Home", now = 20L)
            .apply(state, incoming)

        assertEquals(1, result.state.workspaces.size)
    }

    @Test
    fun `a deleted folder keeps local items that never reached the server`() {
        val (state, server) = applyDesktop()
        val localOnly = state.pins.single { it.id == TAB_IN_FOLDER }.copy(id = "local-pin")
        val incoming = listOf(SpacesRecord.tombstone(FOLDER))

        val result = SpacesApplier(server, containerIds, firstSync = false, defaultName = "Home", now = 20L)
            .apply(state.copy(pins = state.pins + localOnly), incoming)

        val ids = result.state.pins.map { it.id }
        assertFalse(FOLDER in ids || SUBFOLDER in ids || TAB_IN_FOLDER in ids || TAB_IN_SUBFOLDER in ids)
        assertNull(result.state.pins.single { it.id == "local-pin" }.parentId)
    }

    @Test
    fun `records waiting for a missing space or container fail without changing anything`() {
        val tab = ZenRecords.tab("orphan", "https://orphan.example/", "{missing-space}")
        val space = ZenRecords.space("{new-space}", "New", emptyList(), containerGuid = "missing-container")

        val result = SpacesApplier(emptyMap(), containerIds, firstSync = false, defaultName = "Home", now = 1L)
            .apply(ZenRecords.freshDejavu(), listOf(tab, space))

        assertEquals(setOf("orphan", "{new-space}"), result.failed.map { it.id }.toSet())
        assertEquals(ZenRecords.freshDejavu(), result.state)
    }

    @Test
    fun `a workspace in a temporary container keeps it while the synced container is unchanged`() {
        val (state, server) = applyDesktop()
        val temporary = ContainerRecord("tmp-1", "tmp1", ContainerColor.ORANGE, ContainerState.Icon.CIRCLE, 1L, 1L, true)
        val local = state.copy(
            workspaces = state.workspaces.map { if (it.id == SPACE_PERSONAL) it.copy(containerId = "tmp-1") else it },
        )

        assertEquals(emptyList<String>(), changes(local, server, containers + temporary).map { it.id })

        val renamed = ZenRecords.space(
            SPACE_PERSONAL,
            "Renamed on Zen",
            children = listOf(TAB_PINNED, FOLDER, SPLIT, TAB_NORMAL),
            icon = ZenRecords.STAR_ICON,
            theme = ZenRecords.theme,
        )
        val result = SpacesApplier(server, containerIds, firstSync = false, defaultName = "Home", now = 2L)
            .apply(local, listOf(renamed))
        assertEquals("tmp-1", result.state.workspaces.first().containerId)
    }

    @Test
    fun `a fresh Dejavu with its own pins keeps its workspace on the first sync`() {
        val fresh = ZenRecords.freshDejavu()
        val home = fresh.workspaces.single()
        val withPin = fresh.copy(pins = ZenRecords.freshDejavu().pins + pin("local", home.id))
        val applier = SpacesApplier(emptyMap(), containerIds, firstSync = true, defaultName = "Home", now = 10L)

        val result = applier.apply(withPin, ZenRecords.desktop().filter { it.kind != RecordKind.CONTAINER })

        assertEquals(listOf(home.id, SPACE_PERSONAL, SPACE_WORK), result.state.workspaces.map { it.id })
        assertEquals(home.id, result.state.activeWorkspaceId)
    }

    @Test
    fun `Dejavu's own records have every field Zen writes`() {
        val fresh = ZenRecords.freshDejavu()
        val home = fresh.workspaces.single()
        val state = fresh.copy(pins = listOf(pin("local", home.id)))

        val records = SpacesProjector(LocalSpaces(state, emptyList()), emptyMap()).project().records

        assertEquals(
            setOf("uuid", "name", "icon", "theme", "containerGuid", "children"),
            records.getValue(home.id).data!!.keys().asSequence().toSet(),
        )
        assertEquals(
            setOf(
                "tabId", "url", "title", "icon", "containerGuid", "essential", "pinned", "workspaceUuid", "folderId",
                "staticLabel", "hasStaticIcon", "defaultContainer",
            ),
            records.getValue("local").data!!.keys().asSequence().toSet(),
        )
        assertEquals(setOf("spaces", "essentials"), records.getValue(LAYOUT_RECORD_ID).data!!.keys().asSequence().toSet())
    }

    @Test
    fun `orders merge local moves with ids only the server knows`() {
        val opaque = setOf("x", "y")
        assertEquals(listOf("x", "b", "y", "a"), mergeOrder(listOf("b", "a"), listOf("x", "a", "b", "y"), opaque::contains))
        assertEquals(listOf("a", "x", "b", "c"), mergeOrder(listOf("a", "b", "c"), listOf("a", "x", "b", "gone"), opaque::contains))
        assertEquals(listOf("c", "a", "b"), reorder(listOf("a", "b", "c"), listOf("c", "a")))
        assertEquals(listOf("b", "a", "new"), reorder(listOf("a", "new", "b"), listOf("b", "a")))
    }

    private fun pin(id: String, workspaceId: String) = org.mozilla.fenix.dejavu.workspaces.PinnedItem(
        id = id,
        workspaceId = workspaceId,
        parentId = null,
        kind = org.mozilla.fenix.dejavu.workspaces.PinKind.TAB,
        title = "Local",
        url = "https://local.example/",
        createdAt = 1L,
        updatedAt = 1L,
    )
}
