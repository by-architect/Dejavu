/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.ARTICLE
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.GROUP
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.LAPTOP
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.LAPTOP_DEVICE
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.LATER
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.MAIL
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.NEWS
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.tab
import org.mozilla.fenix.dejavu.sync.FirefoxFixtures.tabsRecord
import org.mozilla.fenix.dejavu.workspaces.PinKind
import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirefoxMirrorTest {
    private val laptop = ClientRecord(LAPTOP, "Laptop", "desktop", "Firefox", LAPTOP_DEVICE)
    private val workspaceId = FirefoxMirror.workspaceIdOf(LAPTOP)
    private val folderId = FirefoxMirror.folderIdOf(LAPTOP, GROUP)
    private var ids = 0

    /** One sync: Dejavu's workspaces and open tabs after it, what the mirror asked of the tabs, and what it showed. */
    private class Synced(val state: WorkspaceState, val ops: List<TabOp>, val devices: List<MirroredDevice>) {
        /** The open tabs once the ones this sync opened are open, none of them loaded. */
        fun tabs(before: List<LocalTab> = emptyList()): List<LocalTab> {
            val closed = ops.filterIsInstance<TabOp.Close>().map { it.id }.toSet()
            val opened = ops.filterIsInstance<TabOp.Open>().map { LocalTab(it.id, it.url, it.title, null, awake = false) }
            return before.filterNot { it.id in closed } + opened
        }
    }

    private fun mirror(tabs: List<LocalTab>? = emptyList(), normalTabs: Boolean = true) = FirefoxMirror(
        now = 10L,
        normalTabs = normalTabs,
        tabs = tabs?.associateBy { it.id },
        unnamedGroup = "Tab group",
        newId = { "id-${++ids}" },
    )

    private fun sync(
        record: JSONObject?,
        state: WorkspaceState = ZenRecords.freshDejavu(),
        previous: List<MirroredDevice> = emptyList(),
        tabs: List<LocalTab>? = emptyList(),
        normalTabs: Boolean = true,
    ): Synced {
        val mirror = mirror(tabs, normalTabs)
        val computers = listOfNotNull(record?.let { FirefoxComputer(laptop, TabsRecord.fromCleartext(LAPTOP, it)) })
        val matched = computers.map { computer ->
            mirror.match(previous.firstOrNull { it.clientId == computer.client.id }, computer, state)
        }
        val result = mirror.apply(state, previous, matched)
        return Synced(result.state, result.tabOps, matched)
    }

    /** The devices once a later sync saw here every tab [synced] showed. */
    private fun confirm(synced: Synced) = mirror(synced.tabs()).findClosedHere(synced.state, synced.devices).devices

    private fun WorkspaceState.pinsIn(parentId: String?) = pins.filter { it.workspaceId == workspaceId && it.parentId == parentId }

    private fun idOf(synced: Synced, url: String) = synced.devices.single().tabs.single { it.url == url }.id

    @Test
    fun `a Firefox computer becomes a workspace with its pinned tabs, groups as folders and other tabs`() {
        val result = sync(FirefoxFixtures.laptopTabs())

        val workspace = result.state.workspaces.last()
        assertEquals(workspaceId to "Laptop", workspace.id to workspace.name)
        assertEquals(workspaceId, result.state.activeWorkspaceId)
        val top = result.state.pinsIn(null)
        assertEquals(listOf(MAIL to PinKind.TAB, null to PinKind.FOLDER), top.map { it.url to it.kind })
        assertEquals(folderId to "Reading", top[1].id to top[1].title)
        assertEquals(listOf(ARTICLE, LATER), result.state.pinsIn(folderId).map { it.url })
        assertEquals(listOf(TabOp.Open(idOf(result, NEWS), NEWS, "Title of $NEWS", null, workspaceId)), result.ops)
        assertEquals(workspaceId, result.state.assignments[idOf(result, NEWS)])
    }

    @Test
    fun `the same tabs again change nothing`() {
        val first = sync(FirefoxFixtures.laptopTabs())

        val second = sync(FirefoxFixtures.laptopTabs(), first.state, first.devices, first.tabs())

        assertEquals(first.state, second.state)
        assertTrue(second.ops.isEmpty())
        assertEquals(first.devices, second.devices)
    }

    @Test
    fun `a tab that went to another page stays the same tab, and follows it while not loaded`() {
        val first = sync(FirefoxFixtures.laptopTabs())
        val story = "https://news.example/story"

        val second = sync(FirefoxFixtures.laptopTabs(news = story), first.state, first.devices, first.tabs())

        val newsId = idOf(first, NEWS)
        assertEquals(newsId, idOf(second, story))
        assertEquals(listOf(TabOp.Retarget(newsId, story, "Title of $story")), second.ops)
        val awake = first.tabs().map { it.copy(awake = true) }
        assertTrue(sync(FirefoxFixtures.laptopTabs(news = story), first.state, first.devices, awake).ops.isEmpty())
    }

    @Test
    fun `pinned tabs and folders follow their page and name in Firefox`() {
        val first = sync(FirefoxFixtures.laptopTabs())
        val inbox = "https://mail.example/inbox"

        val second = sync(
            FirefoxFixtures.laptopTabs(mail = inbox, groupName = "Later"),
            first.state,
            first.devices,
            first.tabs(),
        )

        val pin = second.state.pins.single { it.id == idOf(first, MAIL) }
        assertEquals(inbox to "Title of $inbox", pin.url to pin.title)
        assertEquals("Later", second.state.pins.single { it.id == folderId }.title)
    }

    @Test
    fun `tabs and groups Firefox closed leave, and what was added to their folder here moves up`() {
        val first = sync(FirefoxFixtures.laptopTabs())
        val added = PinnedItem("added", workspaceId, folderId, PinKind.TAB, "Added", "https://added.example/", createdAt = 1L, updatedAt = 1L)
        val state = first.state.copy(pins = first.state.pins + added)

        val second = sync(
            tabsRecord("Laptop", listOf(tab(MAIL, pinned = true))),
            state,
            first.devices,
            first.tabs(),
        )

        assertEquals(listOf(MAIL, "https://added.example/"), second.state.pinsIn(null).map { it.url })
        assertTrue(second.state.pins.none { it.id == folderId || it.url == ARTICLE || it.url == LATER })
        assertEquals(listOf(TabOp.Close(idOf(first, NEWS))), second.ops)
    }

    @Test
    fun `a tab closed here is closed in Firefox, and does not come back while Firefox has it`() {
        val first = sync(FirefoxFixtures.laptopTabs())
        val mailId = idOf(first, MAIL)
        val state = first.state.copy(pins = first.state.pins.filterNot { it.id == mailId })

        val closed = mirror(first.tabs()).findClosedHere(state, confirm(first))

        assertEquals(mapOf(LAPTOP_DEVICE to listOf(MAIL)), closed.commands)
        assertEquals(1, closed.marked)
        val again = sync(FirefoxFixtures.laptopTabs(), state, closed.devices, first.tabs())
        assertTrue(again.state.pins.none { it.url == MAIL })
        assertTrue(again.devices.single().tabs.single { it.id == mailId }.closedHere)
        assertEquals(0, mirror(first.tabs()).findClosedHere(again.state, again.devices).marked)

        val gone = sync(
            tabsRecord("Laptop", listOf(tab(NEWS), tab(ARTICLE, group = GROUP), tab(LATER, group = GROUP)), mapOf(GROUP to "Reading")),
            again.state,
            again.devices,
            first.tabs(),
        )
        assertTrue(gone.devices.single().tabs.none { it.url == MAIL })
        assertTrue(gone.ops.isEmpty())
    }

    @Test
    fun `an open tab closed here is closed in Firefox too, and a tab unpinned here is not`() {
        val first = sync(FirefoxFixtures.laptopTabs())
        val newsId = idOf(first, NEWS)
        val mailId = idOf(first, MAIL)
        val unpinnedTab = LocalTab("tab-7", MAIL, "Mail", null, awake = true)
        val state = first.state.copy(
            pins = first.state.pins.filterNot { it.id == mailId },
            tabSyncIds = mapOf("tab-7" to mailId),
        )
        val tabs = first.tabs().filterNot { it.id == newsId } + unpinnedTab

        val closed = mirror(tabs).findClosedHere(state, confirm(first))

        assertEquals(mapOf(LAPTOP_DEVICE to listOf(NEWS)), closed.commands)
    }

    @Test
    fun `a tab gone before it was ever seen here is shown again rather than closed in Firefox`() {
        val first = sync(FirefoxFixtures.laptopTabs())
        val lost = first.state.copy(pins = first.state.pins.filterNot { it.url == MAIL })

        val closed = mirror(emptyList()).findClosedHere(lost, first.devices)

        assertTrue(closed.commands.isEmpty())
        assertEquals(0, closed.marked)
        assertTrue(closed.devices.single().tabs.none { it.url == MAIL || it.url == NEWS })
        val again = sync(FirefoxFixtures.laptopTabs(), lost, closed.devices, tabs = emptyList())
        assertEquals(1, again.state.pins.count { it.url == MAIL })
        assertEquals(listOf(NEWS), again.ops.filterIsInstance<TabOp.Open>().map { it.url })
    }

    @Test
    fun `changes made here stay until Firefox changes the same thing`() {
        val first = sync(FirefoxFixtures.laptopTabs())
        val renamed = first.state.copy(
            pins = first.state.pins.map { if (it.id == folderId) it.copy(title = "Mine") else it },
        )

        val unchanged = sync(FirefoxFixtures.laptopTabs(mail = "https://mail.example/inbox"), renamed, first.devices, first.tabs())
        assertEquals("Mine", unchanged.state.pins.single { it.id == folderId }.title)

        val changed = sync(FirefoxFixtures.laptopTabs(groupName = "Study"), renamed, first.devices, first.tabs())
        assertEquals("Study", changed.state.pins.single { it.id == folderId }.title)
    }

    @Test
    fun `without unpinned tabs the other tabs are neither opened, closed nor closed in Firefox`() {
        val hidden = sync(FirefoxFixtures.laptopTabs(), normalTabs = false)
        assertTrue(hidden.ops.isEmpty())
        assertTrue(hidden.devices.single().tabs.none { it.url == NEWS })

        val first = sync(FirefoxFixtures.laptopTabs())
        val withoutNews = tabsRecord("Laptop", listOf(tab(MAIL, pinned = true)))
        val second = sync(withoutNews, first.state, first.devices, first.tabs(), normalTabs = false)
        assertTrue(second.ops.none { it is TabOp.Close })
        assertEquals(0, mirror(emptyList(), normalTabs = false).findClosedHere(first.state, first.devices).marked)
    }

    @Test
    fun `tabs wait while the browser restores its tabs`() {
        val first = sync(FirefoxFixtures.laptopTabs())

        val restoring = sync(FirefoxFixtures.laptopTabs(news = "https://news.example/story"), first.state, first.devices, tabs = null)

        assertTrue(restoring.ops.isEmpty())
        assertEquals(idOf(first, NEWS), restoring.devices.single().tabs.single { !it.isPin }.id)
        assertEquals(NEWS, restoring.devices.single().tabs.single { !it.isPin }.url)
        assertEquals(0, mirror(tabs = null).findClosedHere(ZenRecords.freshDejavu(), first.devices).marked)
    }

    @Test
    fun `windows keep their order when another window is used in Firefox`() {
        val docs = "https://docs.example/"
        val twoWindows = tabsRecord(
            "Laptop",
            listOf(tab(MAIL, pinned = true, window = "window-0"), tab(docs, pinned = true, window = "window-1")),
            windows = listOf("window-0", "window-1"),
        )
        val first = sync(twoWindows)
        assertEquals(listOf(MAIL, docs), first.state.pinsIn(null).map { it.url })

        val swapped = tabsRecord(
            "Laptop",
            listOf(tab(docs, pinned = true, window = "window-0"), tab(MAIL, pinned = true, window = "window-1")),
            windows = listOf("window-0", "window-1"),
        )
        val second = sync(swapped, first.state, first.devices, first.tabs())

        assertEquals(first.state, second.state)
    }

    @Test
    fun `a tab pinned or grouped in Firefox becomes a pinned tab of its open tab`() {
        val first = sync(FirefoxFixtures.laptopTabs())
        val newsId = idOf(first, NEWS)
        val pinnedNews = tabsRecord(
            "Laptop",
            listOf(tab(MAIL, pinned = true), tab(NEWS, pinned = true, index = 1), tab(ARTICLE, group = GROUP, index = 2), tab(LATER, group = GROUP, index = 3)),
            mapOf(GROUP to "Reading"),
        )

        val second = sync(pinnedNews, first.state, first.devices, first.tabs())

        val pin = second.state.pins.single { it.id == newsId }
        assertEquals(newsId, pin.tabId)
        assertEquals(listOf(MAIL, NEWS, null), second.state.pinsIn(null).map { it.url })
        assertTrue(second.ops.isEmpty())
    }

    @Test
    fun `a tab unpinned in Firefox stays open as a normal tab`() {
        val first = sync(FirefoxFixtures.laptopTabs())
        val mailId = idOf(first, MAIL)
        val withTab = first.state.copy(
            pins = first.state.pins.map { if (it.id == mailId) it.copy(tabId = "tab-3") else it },
        )
        val tabs = first.tabs() + LocalTab("tab-3", MAIL, "Mail", null, awake = true)
        val unpinned = tabsRecord("Laptop", listOf(tab(MAIL), tab(NEWS, index = 1)))

        val second = sync(unpinned, withTab, first.devices, tabs)

        assertNull(second.state.pins.firstOrNull { it.id == mailId })
        assertEquals(mailId, second.state.syncIdOf("tab-3"))
        assertEquals(workspaceId, second.state.assignments["tab-3"])
        assertTrue(second.ops.none { it is TabOp.Close || it is TabOp.Open })
    }

    @Test
    fun `a computer that left takes its workspace along, unless something was added to it here`() {
        val first = sync(FirefoxFixtures.laptopTabs())

        val gone = sync(null, first.state, first.devices, first.tabs())
        assertTrue(gone.state.workspaces.none { it.id == workspaceId })
        assertTrue(gone.state.pins.isEmpty())
        assertEquals(listOf(TabOp.Close(idOf(first, NEWS))), gone.ops)

        val added = PinnedItem("added", workspaceId, null, PinKind.TAB, "Added", "https://added.example/", createdAt = 1L, updatedAt = 1L)
        val kept = sync(null, first.state.copy(pins = first.state.pins + added), first.devices, first.tabs())
        assertEquals("Laptop", kept.state.workspaces.single { it.id == workspaceId }.name)
        assertEquals(listOf(added), kept.state.pins)
    }

    @Test
    fun `a computer shown again after Dejavu forgot it takes its tabs back instead of doubling them`() {
        val first = sync(FirefoxFixtures.laptopTabs())

        val again = sync(FirefoxFixtures.laptopTabs(), first.state, previous = emptyList(), tabs = first.tabs())

        assertEquals(first.state.pins, again.state.pins)
        assertTrue(again.ops.isEmpty())
        assertEquals(first.devices.single().tabs.map { it.id }.toSet(), again.devices.single().tabs.map { it.id }.toSet())
    }

    @Test
    fun `stopping to follow Firefox removes everything it showed`() {
        val first = sync(FirefoxFixtures.laptopTabs())

        val removed = mirror(first.tabs()).removeAll(first.state, first.devices)

        assertTrue(removed.state.workspaces.none { it.id == workspaceId })
        assertTrue(removed.state.pins.isEmpty())
        assertEquals(listOf(TabOp.Close(idOf(first, NEWS))), removed.tabOps)
        assertEquals(ZenRecords.freshDejavu().workspaces, removed.state.workspaces)
    }

    @Test
    fun `older Firefox versions list tabs by last use, which only places new tabs`() {
        val unordered = tabsRecord("Laptop", listOf(tab(MAIL, pinned = true, window = ""), tab("https://docs.example/", pinned = true, window = "")), windows = emptyList())
        val first = sync(unordered)
        assertFalse(first.devices.single().positioned)

        val reversed = tabsRecord("Laptop", listOf(tab("https://docs.example/", pinned = true, window = ""), tab(MAIL, pinned = true, window = "")), windows = emptyList())
        val second = sync(reversed, first.state, first.devices, first.tabs())

        assertEquals(first.state.pinsIn(null), second.state.pinsIn(null))
    }
}
