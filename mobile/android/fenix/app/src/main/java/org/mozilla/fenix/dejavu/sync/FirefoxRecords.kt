/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import org.json.JSONObject
import org.mozilla.fenix.dejavu.workspaces.SyncedDevice

/**
 * A device's record in the "clients" collection of Firefox Sync, which every browser signed in to the account writes
 * about itself.
 *
 * @property id Id of the record. A desktop browser's tabs record has the same id.
 * @property name The device's name, like "Neo's Firefox on laptop".
 * @property type "desktop", "mobile" or "tablet".
 * @property application The browser's short name, like "Firefox", "Zen" or "LibreWolf". Only desktop browsers write it.
 * @property fxaDeviceId The device's id in the Mozilla account, which commands like closing tabs are sent to.
 */
internal data class ClientRecord(
    val id: String,
    val name: String,
    val type: String?,
    val application: String?,
    val fxaDeviceId: String?,
) {
    val isDesktop: Boolean
        get() = type == DESKTOP

    /** Whether the device is Zen, whose releases are called "Zen" and nightly builds "Twilight". */
    val isZen: Boolean
        get() = application != null && (application.startsWith(ZEN) || application == TWILIGHT)

    /**
     * The kind of device Dejavu shows as a workspace: Firefox on a computer, or a phone or tablet, which runs Firefox
     * or Dejavu. `null` for Zen, which Dejavu follows on its own, and for other kinds of devices.
     */
    val deviceKind: SyncedDevice?
        get() = when (type) {
            DESKTOP -> SyncedDevice.DESKTOP.takeIf { !isZen }
            MOBILE, TABLET -> SyncedDevice.PHONE
            else -> null
        }

    /** Whether this is the record of this device, whose account id is [localDeviceId]. */
    fun isLocal(localDeviceId: String?): Boolean =
        localDeviceId != null && (fxaDeviceId == localDeviceId || id == localDeviceId)

    companion object {
        private const val DESKTOP = "desktop"
        private const val MOBILE = "mobile"
        private const val TABLET = "tablet"
        private const val ZEN = "Zen"
        private const val TWILIGHT = "Twilight"

        fun fromCleartext(id: String, cleartext: JSONObject) = ClientRecord(
            id = id,
            name = cleartext.string("name").orEmpty(),
            type = cleartext.string("type"),
            application = cleartext.string("application"),
            fxaDeviceId = cleartext.string("fxaDeviceId"),
        )
    }
}

/**
 * An open tab of a [TabsRecord].
 *
 * @property url The page the tab shows.
 * @property pinned Whether the tab is pinned.
 * @property groupId The tab group the tab is in, or `null`.
 * @property windowId The window the tab is in, named after the window's place in the order the windows were last used
 *   in, so it changes when another window is used.
 * @property index The tab's place in its window.
 */
internal data class FirefoxTab(
    val url: String,
    val title: String,
    val pinned: Boolean = false,
    val groupId: String? = null,
    val windowId: String = "",
    val index: Int = 0,
)

/**
 * A tab group of a [TabsRecord]. Firefox leaves out empty and closed groups.
 *
 * @property color The group's color as Firefox names it, like "blue", or `null`.
 */
internal data class FirefoxTabGroup(val id: String, val name: String, val collapsed: Boolean, val color: String? = null)

/**
 * The record of the "tabs" collection where a device keeps the tabs it has open: Firefox's TabsRecord, written by
 * application-services' tabs component. Only tabs Dejavu can open are kept.
 *
 * @property id The device's id, the same as its [ClientRecord]'s.
 * @property clientName The device's name.
 * @property tabs The tabs in the order of their windows, most recently used first, and of their place in each window.
 * @property groups The tab groups of [tabs] by id.
 * @property positioned Whether the tabs come with their window and place. Older versions of Firefox leave them out and
 *   list their tabs by last use, which says nothing about their order.
 */
internal class TabsRecord(
    val id: String,
    val clientName: String,
    val tabs: List<FirefoxTab>,
    val groups: Map<String, FirefoxTabGroup>,
    val positioned: Boolean,
) {
    companion object {
        fun fromCleartext(id: String, cleartext: JSONObject): TabsRecord {
            val windows = cleartext.optJSONObject("windows")
            val windowOrder = windows?.keys()?.asSequence()
                ?.associateWith { windows.optJSONObject(it)?.optInt("index", Int.MAX_VALUE) ?: Int.MAX_VALUE }
                .orEmpty()
            val groupsJson = cleartext.optJSONObject("tabGroups")
            val groups = groupsJson?.keys()?.asSequence()
                ?.mapNotNull { key ->
                    val group = groupsJson.optJSONObject(key) ?: return@mapNotNull null
                    val groupId = group.string("id") ?: key
                    groupId to FirefoxTabGroup(
                        id = groupId,
                        name = group.string("name").orEmpty(),
                        collapsed = group.optBoolean("collapsed"),
                        color = group.string("color"),
                    )
                }
                ?.toMap()
                .orEmpty()
            val array = cleartext.optJSONArray("tabs")
            val tabs = (0 until (array?.length() ?: 0)).mapNotNull { index ->
                val tab = array?.optJSONObject(index) ?: return@mapNotNull null
                val url = tab.optJSONArray("urlHistory")?.opt(0) as? String
                if (url == null || !isSyncableUrl(url)) return@mapNotNull null
                val pinned = tab.optBoolean("pinned")
                FirefoxTab(
                    url = url,
                    title = tab.string("title").orEmpty(),
                    pinned = pinned,
                    // Firefox keeps pinned tabs out of groups; a group that was left out of the record holds nothing.
                    groupId = tab.string("tabGroupId")?.takeIf { !pinned && it in groups },
                    windowId = tab.string("windowId").orEmpty(),
                    index = tab.optInt("index"),
                )
            }
            val positioned = tabs.any { it.windowId.isNotEmpty() }
            val ordered = if (positioned) {
                tabs.sortedWith(compareBy({ windowOrder[it.windowId] ?: Int.MAX_VALUE }, { it.windowId }, { it.index }))
            } else {
                tabs
            }
            return TabsRecord(
                id = id,
                clientName = cleartext.string("clientName").orEmpty(),
                tabs = ordered,
                groups = groups.filterKeys { groupId -> ordered.any { it.groupId == groupId } },
                positioned = positioned,
            )
        }
    }
}

/**
 * A device on the account whose open tabs Dejavu shows: Firefox on a computer, or a phone with Firefox or Dejavu.
 *
 * @property number Tells apart devices with the same name, like two Firefox profiles on one computer: the second one
 *   is named "Name (2)".
 */
internal class FirefoxComputer(val client: ClientRecord, val record: TabsRecord, private val number: Int = 1) {
    /** Whether this is a computer or a phone, which its workspace's icon shows. */
    val kind: SyncedDevice
        get() = client.deviceKind ?: SyncedDevice.DESKTOP

    /** The computer's name, or the browser's when it has none. */
    val name: String
        get() {
            val name = client.name.ifBlank { record.clientName }.ifBlank { client.application ?: FIREFOX }
            return if (number > 1) "$name ($number)" else name
        }

    private companion object {
        const val FIREFOX = "Firefox"
    }
}

/**
 * The devices among [clients] whose open tabs Dejavu shows, Firefox on computers and phones, with their open tabs from
 * [tabs], in the order of their names. Zen, this device ([localDeviceId] is its id in the account) and devices that left
 * the account ([connected], when known, has the account's devices) are left out.
 */
internal fun firefoxComputers(
    clients: Collection<ClientRecord>,
    tabs: Map<String, TabsRecord>,
    connected: Set<String>?,
    localDeviceId: String? = null,
): List<FirefoxComputer> {
    val computers = clients
        .filter { it.deviceKind != null && !it.isLocal(localDeviceId) && it.isConnected(connected) }
        .mapNotNull { client -> tabs[client.id]?.let { FirefoxComputer(client, it) } }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.client.id }))
    val named = HashMap<String, Int>()
    return computers.map { computer ->
        val number = (named[computer.name] ?: 0) + 1
        named[computer.name] = number
        if (number == 1) computer else FirefoxComputer(computer.client, computer.record, number)
    }
}

/** Whether the device is still in the account, as far as [connected], the ids of the account's devices, tells. */
internal fun ClientRecord.isConnected(connected: Set<String>?): Boolean =
    connected == null || fxaDeviceId == null || fxaDeviceId in connected

/**
 * The ids of the account's devices to check [clients] against: [known] while every device Dejavu shows among them is in
 * it, and otherwise the ones [refresh] gets from the account for the ids it does not know, since a device that just
 * joined is not known yet. [refresh] returns `null` when it could not tell. This device, [localDeviceId], is never
 * among the account's other devices.
 */
internal suspend fun devicesFor(
    clients: Collection<ClientRecord>,
    known: Set<String>?,
    localDeviceId: String? = null,
    refresh: suspend (unknown: Set<String>) -> Set<String>?,
): Set<String>? {
    if (known == null) return null
    val unknown = clients
        .filter { it.deviceKind != null && !it.isLocal(localDeviceId) && !it.isConnected(known) }
        .mapNotNullTo(HashSet()) { it.fxaDeviceId }
    return if (unknown.isEmpty()) known else refresh(unknown) ?: known
}
