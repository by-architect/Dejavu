/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.LAPTOP
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.LAPTOP_DEVICE
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.MAIL
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.PHONE
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.ZEN
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.ZEN_DEVICE
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.client
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.tab
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.tabsRecord
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirefoxTabsEngineTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val auth = SyncAuth(
        kid = "1727000000-abc",
        accessToken = "token",
        syncKey = SyncCryptoTest.syncKey(3),
        tokenServerUrl = "https://token.example",
    )
    private val laptopWorkspace = FirefoxMirror.workspaceIdOf(LAPTOP)
    private lateinit var server: FakeSyncServer
    private lateinit var local: FakeFirefoxLocal
    private lateinit var engine: FirefoxTabsEngine

    @Before
    fun setUp() {
        server = FakeSyncServer(auth.syncKey)
        local = FakeFirefoxLocal()
        engine = newEngine()
        server.put("clients", LAPTOP, client("Laptop", fxaDeviceId = LAPTOP_DEVICE))
        server.put("clients", ZEN, client("Zen desktop", application = "Zen", fxaDeviceId = ZEN_DEVICE))
        server.put("clients", PHONE, client("Pixel", application = null, type = "mobile", fxaDeviceId = PHONE))
        server.put("tabs", LAPTOP, FirefoxFixtures.laptopTabs())
        server.put("tabs", ZEN, tabsRecord("Zen desktop", listOf(tab("https://zen.example/", pinned = true))))
        server.put("tabs", PHONE, tabsRecord("Pixel", listOf(tab("https://phone.example/", window = "")), windows = emptyList()))
    }

    private fun newEngine() = FirefoxTabsEngine(server, FirefoxSyncStore(File(folder.root, "firefox.json")), local)

    @Test
    fun `only Firefox on computers is shown, and nothing is uploaded`() = runTest {
        val result = engine.sync(auth, connected = null)

        assertEquals(FirefoxSyncResult.Synced(listOf("Laptop")), result)
        assertEquals(listOf("Home", "Laptop"), local.state.workspaces.map { it.name })
        assertEquals(listOf(MAIL, null), local.state.pins.filter { it.parentId == null }.map { it.url })
        assertEquals(listOf(FirefoxFixtures.NEWS), local.tabs!!.map { it.url })
        assertTrue(server.requests.none { it.startsWith("POST") || it.startsWith("PUT") })
    }

    @Test
    fun `nothing is downloaded again while Firefox's tabs stay the same`() = runTest {
        engine.sync(auth, connected = null)
        server.requests.clear()

        engine.sync(auth, connected = null)

        assertEquals(listOf("GET info/collections", "GET storage/meta/global", "GET storage/crypto/keys"), server.requests)
    }

    @Test
    fun `changes in Firefox arrive`() = runTest {
        engine.sync(auth, connected = null)
        server.put("tabs", LAPTOP, FirefoxFixtures.laptopTabs(mail = "https://mail.example/inbox"))

        engine.sync(auth, connected = null)

        assertTrue(local.state.pins.any { it.url == "https://mail.example/inbox" })
        assertTrue(local.state.pins.none { it.url == MAIL })
    }

    @Test
    fun `turning off open tabs for the account stops following Firefox`() = runTest {
        server.setMeta(JSONObject(), declined = listOf("tabs"))

        assertEquals(FirefoxSyncResult.TabsOff, engine.sync(auth, connected = null))
        assertEquals(listOf("Home"), local.state.workspaces.map { it.name })
    }

    @Test
    fun `a tab closed here asks its computer to close it`() = runTest {
        engine.sync(auth, connected = null)
        engine.sync(auth, connected = null)
        local.state = local.state.copy(pins = local.state.pins.filterNot { it.url == MAIL })
        assertTrue(engine.hasLocalChanges())

        engine.sync(auth, connected = null)

        assertEquals(listOf(LAPTOP_DEVICE to listOf(MAIL)), local.closeCommands)
        assertFalse(engine.hasLocalChanges())
        assertTrue(local.state.pins.none { it.url == MAIL })
    }

    @Test
    fun `a tab closed right after it was shown asks its computer to close it`() = runTest {
        engine.sync(auth, connected = null)
        local.state = local.state.copy(pins = local.state.pins.filterNot { it.url == MAIL })
        assertTrue(engine.hasLocalChanges())

        engine.sync(auth, connected = null)

        assertEquals(listOf(LAPTOP_DEVICE to listOf(MAIL)), local.closeCommands)
        assertTrue(local.state.pins.none { it.url == MAIL })
    }

    @Test
    fun `tabs lost before they were kept here come back instead of being closed in Firefox`() = runTest {
        engine.sync(auth, connected = null)
        // The app stopped before the tabs were kept.
        engine = newEngine()
        local.state = local.state.copy(pins = local.state.pins.filterNot { it.url == MAIL })
        local.tabs = emptyList()
        assertFalse(engine.hasLocalChanges())

        engine.sync(auth, connected = null)

        assertTrue(local.closeCommands.isEmpty())
        assertEquals(1, local.state.pins.count { it.url == MAIL })
        assertEquals(listOf(FirefoxFixtures.NEWS), local.tabs!!.map { it.url })
    }

    @Test
    fun `a computer that left the account leaves Dejavu`() = runTest {
        engine.sync(auth, connected = setOf(LAPTOP_DEVICE, ZEN_DEVICE))
        assertTrue(local.state.workspaces.any { it.id == laptopWorkspace })

        assertEquals(FirefoxSyncResult.Synced(emptyList()), engine.sync(auth, connected = setOf(ZEN_DEVICE)))

        assertEquals(listOf("Home"), local.state.workspaces.map { it.name })
        assertTrue(local.tabs!!.isEmpty())
    }

    @Test
    fun `a computer that joined the account after its devices were read is shown once they are read again`() = runTest {
        val result = engine.sync(auth, connected = setOf(ZEN_DEVICE)) { setOf(LAPTOP_DEVICE, ZEN_DEVICE) }

        assertEquals(FirefoxSyncResult.Synced(listOf("Laptop")), result)
        assertTrue(local.state.workspaces.any { it.id == laptopWorkspace })
    }

    @Test
    fun `a computer that signed out of Firefox Sync leaves Dejavu`() = runTest {
        engine.sync(auth, connected = null)
        server.delete("tabs", LAPTOP)
        server.delete("clients", LAPTOP)

        engine.sync(auth, connected = null)

        assertEquals(listOf("Home"), local.state.workspaces.map { it.name })
    }

    @Test
    fun `stopping to follow Firefox removes its workspaces`() = runTest {
        engine.sync(auth, connected = null)

        engine.removeAll()

        assertEquals(ZenRecords.freshDejavu().workspaces, local.state.workspaces)
        assertTrue(local.state.pins.isEmpty())
        assertTrue(local.tabs!!.isEmpty())
    }

    @Test
    fun `what was shown survives a restart`() = runTest {
        engine.sync(auth, connected = null)
        val shown = local.state
        server.requests.clear()

        engine = newEngine()
        engine.sync(auth, connected = null)

        assertEquals(shown, local.state)
        assertTrue(server.requests.none { it.startsWith("GET storage/tabs") || it.startsWith("GET storage/clients") })
    }
}

/** Dejavu's workspaces and open tabs in memory. */
internal class FakeFirefoxLocal(
    var state: WorkspaceState = ZenRecords.freshDejavu(),
    var tabs: List<LocalTab>? = emptyList(),
    var normalTabs: Boolean = true,
) : FirefoxLocalData {
    override val unnamedGroup = "Tab group"

    /** The close commands sent, by device. */
    val closeCommands = mutableListOf<Pair<String, List<String>>>()

    override suspend fun read() = LocalSpaces(state, emptyList(), normalTabs, tabs)

    override fun <T> update(transform: (WorkspaceState) -> Pair<WorkspaceState, T>): T {
        val (updated, result) = transform(state)
        state = updated
        return result
    }

    override suspend fun runTabOps(ops: List<TabOp>) {
        val current = tabs ?: return
        val closed = ops.filterIsInstance<TabOp.Close>().map { it.id }.toSet()
        val opened = ops.filterIsInstance<TabOp.Open>().map { LocalTab(it.id, it.url, it.title, it.contextId, awake = false) }
        tabs = current.filterNot { it.id in closed } + opened
    }

    override suspend fun closeRemoteTabs(deviceId: String, urls: List<String>) {
        closeCommands += deviceId to urls
    }
}
