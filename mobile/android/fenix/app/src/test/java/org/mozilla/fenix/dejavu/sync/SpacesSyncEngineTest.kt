/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import java.io.File
import kotlinx.coroutines.test.runTest
import mozilla.components.browser.state.state.ContainerState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.sync.ZenRecords.CONTAINER
import org.mozilla.fenix.dejavu.sync.ZenRecords.SPACE_PERSONAL
import org.mozilla.fenix.dejavu.sync.ZenRecords.SPACE_WORK
import org.mozilla.fenix.dejavu.sync.ZenRecords.TAB_PINNED
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpacesSyncEngineTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val auth = SyncAuth(
        kid = "1727000000-abc",
        accessToken = "token",
        syncKey = SyncCryptoTest.syncKey(3),
        tokenServerUrl = "https://token.example",
    )
    private lateinit var server: FakeSyncServer
    private lateinit var local: FakeLocalSpaces
    private lateinit var engine: SpacesSyncEngine

    @Before
    fun setUp() {
        server = FakeSyncServer(auth.syncKey)
        local = FakeLocalSpaces()
        engine = newEngine()
    }

    private fun newEngine() = SpacesSyncEngine(server, SpacesSyncStore(File(folder.root, "spaces.json")), local)

    @Test
    fun `pulling Zen's spaces sends nothing back`() = runTest {
        server.upload(ZenRecords.desktop())

        val result = engine.sync(auth)

        assertEquals(SpacesSyncResult.Synced(received = 16, sent = 0, pending = 0), result)
        assertEquals(emptyList<List<String>>(), server.uploads)
        assertEquals(listOf(SPACE_PERSONAL, SPACE_WORK), local.state.workspaces.map { it.id })
        assertEquals(setOf(CONTAINER, "builtin-2"), local.containers.map { it.contextId }.toSet())
        assertEquals(FakeSyncServer.ZEN_SYNC_ID, server.meta().getJSONObject("engines").getJSONObject("spaces").getString("syncID"))
    }

    @Test
    fun `a sync without changes on either side only checks the server`() = runTest {
        server.upload(ZenRecords.desktop())
        engine.sync(auth)
        server.requests.clear()

        assertEquals(SpacesSyncResult.Synced(received = 0, sent = 0, pending = 0), engine.sync(auth))

        assertEquals(
            listOf("GET info/collections", "GET info/configuration", "GET storage/meta/global", "GET storage/crypto/keys"),
            server.requests,
        )
    }

    @Test
    fun `local changes upload only the records that changed`() = runTest {
        server.upload(ZenRecords.desktop())
        engine.sync(auth)
        local.state = local.state.copy(
            workspaces = local.state.workspaces.map { if (it.id == SPACE_WORK) it.copy(name = "Office") else it },
        )
        assertTrue(engine.hasLocalChanges())

        engine.sync(auth)

        assertEquals(listOf(listOf(SPACE_WORK)), server.uploads)
        assertEquals("Office", server.record(SPACE_WORK)!!.data!!.string("name"))
        assertEquals(false, engine.hasLocalChanges())
    }

    @Test
    fun `changes from Zen arrive without being sent back`() = runTest {
        server.upload(ZenRecords.desktop())
        engine.sync(auth)
        server.upload(listOf(ZenRecords.tab(TAB_PINNED, "https://example.com/moved", SPACE_PERSONAL)))

        val result = engine.sync(auth)

        assertEquals(SpacesSyncResult.Synced(received = 1, sent = 0, pending = 0), result)
        assertEquals("https://example.com/moved", local.state.pins.single { it.id == TAB_PINNED }.url)
        assertEquals(emptyList<List<String>>(), server.uploads)
    }

    @Test
    fun `spaces sync is only turned on for an account when asked`() = runTest {
        server.setMeta(JSONObject(), declined = listOf("spaces"))

        assertEquals(SpacesSyncResult.NotTurnedOn, engine.sync(auth))
        assertEquals(0, server.meta().getJSONObject("engines").length())

        engine.sync(auth, turnOn = true)

        assertEquals(0, server.meta().getJSONArray("declined").length())
        val entry = server.meta().getJSONObject("engines").getJSONObject("spaces")
        assertEquals(3, entry.getInt("version"))
        assertEquals(12, entry.getString("syncID").length)
        val home = local.state.workspaces.single().id
        assertEquals(setOf(home, LAYOUT_RECORD_ID), server.records().keys)
    }

    @Test
    fun `a newer or older version of spaces sync stops syncing`() = runTest {
        server.upload(ZenRecords.desktop())
        server.setMeta(JSONObject().put("spaces", JSONObject().put("version", 4).put("syncID", "newerSyncId1")))
        assertEquals(SpacesSyncResult.NeedsUpdate(4), engine.sync(auth))

        server.setMeta(JSONObject().put("spaces", JSONObject().put("version", 2).put("syncID", "olderSyncId1")))
        assertEquals(SpacesSyncResult.NotTurnedOn, engine.sync(auth))

        assertEquals(ZenRecords.freshDejavu(), local.state)
        assertEquals(emptyList<List<String>>(), server.uploads)
    }

    @Test
    fun `a change on the server during the sync is downloaded before uploading again`() = runTest {
        server.upload(ZenRecords.desktop())
        engine.sync(auth)
        local.state = local.state.copy(
            workspaces = local.state.workspaces.map { if (it.id == SPACE_WORK) it.copy(name = "Office") else it },
        )
        server.conflictOnNextUpload = true

        assertTrue(runCatching { engine.sync(auth) }.exceptionOrNull() is SyncConflictException)
        engine.sync(auth)

        assertEquals("Office", server.record(SPACE_WORK)!!.data!!.string("name"))
    }

    @Test
    fun `local data that looks reset does not delete what other devices have`() = runTest {
        server.upload(ZenRecords.desktop())
        engine.sync(auth)
        local.state = ZenRecords.freshDejavu()

        engine.sync(auth)

        assertEquals(emptyList<List<String>>(), server.uploads)
        assertEquals(listOf(SPACE_PERSONAL, SPACE_WORK), local.state.workspaces.map { it.id })
        assertEquals(ZenRecords.desktop().size, server.records().size)
    }

    @Test
    fun `records that need a missing space wait for it`() = runTest {
        val tab = ZenRecords.tab("waiting", "https://waiting.example/", "{later-space}")
        server.upload(listOf(tab))

        val first = engine.sync(auth) as SpacesSyncResult.Synced
        assertEquals(1, first.received)
        assertEquals(1, first.pending)
        assertTrue(local.state.pins.none { it.id == "waiting" })

        server.upload(listOf(ZenRecords.space("{later-space}", "Later", listOf("waiting"))))
        engine.sync(auth)

        assertEquals("{later-space}", local.state.pins.single { it.id == "waiting" }.workspaceId)
        assertTrue(server.uploads.none { "waiting" in it })
    }

    @Test
    fun `the sync state survives a restart`() = runTest {
        server.upload(ZenRecords.desktop())
        engine.sync(auth)
        server.requests.clear()

        engine = newEngine()
        engine.sync(auth)

        assertTrue(server.requests.none { it.startsWith("GET storage/spaces") || it.startsWith("POST") })
    }
}

/** Dejavu's data in memory. */
internal class FakeLocalSpaces(
    var state: WorkspaceState = ZenRecords.freshDejavu(),
    var containers: List<ContainerRecord> = emptyList(),
) : SpacesLocalData {
    override val defaultWorkspaceName = "Home"

    override suspend fun read() = LocalSpaces(state, containers)

    override fun <T> update(transform: (WorkspaceState) -> Pair<WorkspaceState, T>): T {
        val (updated, result) = transform(state)
        state = updated
        return result
    }

    override suspend fun saveContainer(
        contextId: String,
        name: String,
        color: ContainerColor,
        icon: ContainerState.Icon,
    ) {
        containers = if (containers.any { it.contextId == contextId }) {
            containers.map { if (it.contextId == contextId) it.copy(name = name, color = color, icon = icon) else it }
        } else {
            containers + ContainerRecord(contextId, name, color, icon, createdAt = 1L, updatedAt = 1L)
        }
    }

    override suspend fun removeContainer(contextId: String) {
        containers = containers.filterNot { it.contextId == contextId }
    }

    override fun builtinName(container: BuiltinContainer) = container.name.lowercase().replaceFirstChar { it.uppercase() }
}
