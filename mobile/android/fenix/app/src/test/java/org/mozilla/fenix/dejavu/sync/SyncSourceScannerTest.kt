/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.LAPTOP
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.LAPTOP_DEVICE
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.PHONE
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.ZEN
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.ZEN_DEVICE
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.client
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.tab
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.tabsRecord
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSourceScannerTest {
    private val auth = SyncAuth(
        kid = "1727000000-abc",
        accessToken = "token",
        syncKey = SyncCryptoTest.syncKey(3),
        tokenServerUrl = "https://token.example",
    )
    private lateinit var server: FakeSyncServer
    private lateinit var scanner: SyncSourceScanner

    @Before
    fun setUp() {
        server = FakeSyncServer(auth.syncKey)
        scanner = SyncSourceScanner(server)
        server.put("clients", PHONE, client("Pixel", application = null, type = "mobile", fxaDeviceId = PHONE))
        server.put("tabs", PHONE, tabsRecord("Pixel", listOf(tab("https://phone.example/", window = "")), windows = emptyList()))
    }

    private fun addLaptop() {
        server.put("clients", LAPTOP, client("Laptop", fxaDeviceId = LAPTOP_DEVICE))
        server.put("tabs", LAPTOP, FirefoxFixtures.laptopTabs())
    }

    private fun addZen() {
        server.put("clients", ZEN, client("Zen desktop", application = "Zen", fxaDeviceId = ZEN_DEVICE))
        server.put("tabs", ZEN, tabsRecord("Zen desktop", listOf(tab("https://zen.example/", pinned = true))))
    }

    private fun scan(connected: Set<String>? = null) =
        (runBlocking { scanner.scan(auth, connected) } as ScanResult.Found).data

    @Test
    fun `Firefox computers and Zen are found with what they have`() {
        addLaptop()
        addZen()
        server.upload(ZenRecords.desktop())

        val found = scan()

        assertEquals(listOf(FoundFirefox("Laptop", tabs = 4, pinned = 1, groups = 1)), found.firefox)
        assertEquals(
            FoundZen(listOf("Zen desktop"), spacesSyncOn = true, spaces = 2, pinned = 6, essentials = 2, folders = 2),
            found.zen,
        )
        assertEquals(null, found.onlySource)
    }

    @Test
    fun `Zen without its sidebar sync is found with its sync off`() {
        server.setMeta(JSONObject())
        addZen()

        val found = scan()

        assertEquals(FoundZen(listOf("Zen desktop"), spacesSyncOn = false), found.zen)
        assertEquals(DejavuSyncSource.ZEN, found.onlySource)
    }

    @Test
    fun `an account with only phones finds nothing to choose`() {
        server.setMeta(JSONObject())

        assertTrue(scan().isEmpty)
    }

    @Test
    fun `computers that left the account are not found`() {
        addLaptop()

        assertEquals(DejavuSyncSource.FIREFOX, scan(connected = setOf(LAPTOP_DEVICE)).onlySource)
        assertTrue(scan(connected = setOf(PHONE)).firefox.isEmpty())
    }

    @Test
    fun `looking again without changes downloads nothing`() {
        addLaptop()
        scan()
        server.requests.clear()

        scan()

        assertEquals(listOf("GET info/collections"), server.requests)
    }
}
