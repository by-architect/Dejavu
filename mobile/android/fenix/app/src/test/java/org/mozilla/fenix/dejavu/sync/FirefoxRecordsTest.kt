/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.GROUP
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.client
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.tab
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.tabsRecord
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirefoxRecordsTest {
    @Test
    fun `tabs are read in the order of their windows and places, not of their last use`() {
        val record = tabsRecord(
            "Laptop",
            listOf(
                tab("https://c.example/", window = "window-1"),
                tab("https://b.example/", window = "window-0", index = 1),
                tab("https://a.example/", window = "window-0"),
            ),
            windows = listOf("window-0", "window-1"),
        )

        val tabs = TabsRecord.fromCleartext("laptop", record)

        assertTrue(tabs.positioned)
        assertEquals(listOf("https://a.example/", "https://b.example/", "https://c.example/"), tabs.tabs.map { it.url })
    }

    @Test
    fun `pinned tabs and groups are read, and pages Dejavu cannot open are left out`() {
        val record = tabsRecord(
            "Laptop",
            listOf(
                tab("https://pinned.example/", pinned = true),
                tab("https://grouped.example/", group = GROUP, index = 1),
                tab("https://lost.example/", group = "missing-group", index = 2),
                tab("about:preferences", index = 3),
                tab("file:///home/neo/notes.txt", index = 4),
            ),
            groups = mapOf(GROUP to "Reading"),
            collapsed = setOf(GROUP),
        )

        val tabs = TabsRecord.fromCleartext("laptop", record)

        assertEquals(
            listOf(
                FirefoxTab("https://pinned.example/", "Title of https://pinned.example/", pinned = true, windowId = "window-0"),
                FirefoxTab("https://grouped.example/", "Title of https://grouped.example/", groupId = GROUP, windowId = "window-0", index = 1),
                FirefoxTab("https://lost.example/", "Title of https://lost.example/", windowId = "window-0", index = 2),
            ),
            tabs.tabs,
        )
        assertEquals(mapOf(GROUP to FirefoxTabGroup(GROUP, "Reading", collapsed = true)), tabs.groups)
    }

    @Test
    fun `Zen and its nightly Twilight are told apart from Firefox`() {
        fun isZen(application: String?) = ClientRecord.fromCleartext("id", client("Device", application)).isZen

        assertTrue(isZen("Zen"))
        assertTrue(isZen("Zen Browser"))
        assertTrue(isZen("Twilight"))
        assertFalse(isZen("Firefox"))
        assertFalse(isZen("LibreWolf"))
        assertFalse(isZen(null))
    }

    @Test
    fun `computers are Firefox on a desktop with open tabs, still in the account`() {
        val firefox = ClientRecord("laptop", "Laptop", "desktop", "Firefox", "fxa-laptop")
        val librewolf = ClientRecord("work", "Work", "desktop", "LibreWolf", null)
        val zen = ClientRecord("zen", "Zen desktop", "desktop", "Zen", "fxa-zen")
        val phone = ClientRecord("fxa-phone", "Pixel", "mobile", null, "fxa-phone")
        val idle = ClientRecord("idle", "Idle", "desktop", "Firefox", "fxa-idle")
        val record = TabsRecord.fromCleartext("x", tabsRecord("x", listOf(tab("https://example.com/"))))
        val tabs = listOf("laptop", "work", "zen", "fxa-phone").associateWith { record }

        val computers = firefoxComputers(listOf(zen, phone, idle, librewolf, firefox), tabs, connected = null)

        assertEquals(listOf("Laptop", "Work"), computers.map { it.name })
        val connected = firefoxComputers(listOf(firefox, librewolf), tabs, connected = setOf("fxa-other"))
        assertEquals(listOf("Work"), connected.map { it.name })
        assertNull(firefoxComputers(listOf(firefox), emptyMap(), connected = null).firstOrNull())
    }

    @Test
    fun `computers with the same name are numbered in a lasting order`() {
        val record = TabsRecord.fromCleartext("x", tabsRecord("x", listOf(tab("https://example.com/"))))
        val clients = listOf("b", "c", "a").map { ClientRecord(it, "Laptop", "desktop", "Firefox", null) } +
            ClientRecord("d", "Desktop", "desktop", "Firefox", null)

        val computers = firefoxComputers(clients, clients.associate { it.id to record }, connected = null)

        assertEquals(listOf("Desktop", "Laptop", "Laptop (2)", "Laptop (3)"), computers.map { it.name })
        assertEquals(listOf("d", "a", "b", "c"), computers.map { it.client.id })
    }

    @Test
    fun `the account is asked for its devices again only for a desktop browser it did not have`() = runTest {
        val firefox = ClientRecord("laptop", "Laptop", "desktop", "Firefox", "fxa-laptop")
        val phone = ClientRecord("fxa-phone", "Pixel", "mobile", null, "fxa-phone")
        var asked = 0
        val refresh: suspend () -> Set<String>? = { asked++; setOf("fxa-laptop", "fxa-new") }

        assertEquals(setOf("fxa-laptop"), devicesFor(listOf(firefox, phone), setOf("fxa-laptop"), refresh))
        assertNull(devicesFor(listOf(firefox), null, refresh))
        assertEquals(0, asked)
        assertEquals(setOf("fxa-laptop", "fxa-new"), devicesFor(listOf(firefox), setOf("fxa-other"), refresh))
        assertEquals(1, asked)
        assertEquals(setOf("fxa-other"), devicesFor(listOf(firefox), setOf("fxa-other")) { null })
    }
}
