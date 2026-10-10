/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Dejavu remembers about the Firefox computers it shows, between syncs.
 *
 * @property account Storage user the data belongs to.
 * @property keysModified Server time of crypto/keys when it was last read.
 * @property clientsModified Server time of the clients collection when [clients] were downloaded.
 * @property tabsModified Server time of the tabs collection when [tabs] were downloaded.
 * @property lastSynced Local time of the last complete sync, in milliseconds.
 * @property clients The decrypted records of the clients collection, by id.
 * @property tabs The decrypted records of the tabs collection, by id.
 * @property devices The Firefox computers as Dejavu last showed them.
 */
internal class FirefoxSyncData(
    var account: String? = null,
    var keysModified: String? = null,
    var clientsModified: String? = null,
    var tabsModified: String? = null,
    var lastSynced: Long = 0L,
    var clients: Map<String, JSONObject> = emptyMap(),
    var tabs: Map<String, JSONObject> = emptyMap(),
    var devices: List<MirroredDevice> = emptyList(),
) {
    /** Forgets the downloaded records, so the next sync downloads them again. */
    fun forgetRecords() {
        keysModified = null
        clientsModified = null
        tabsModified = null
        clients = emptyMap()
        tabs = emptyMap()
    }
}

/** Keeps [FirefoxSyncData] in a JSON file. Not thread safe; it is used by one sync at a time. */
internal class FirefoxSyncStore(file: File) {
    private val file = AtomicFile(file)
    private var cached: FirefoxSyncData? = null

    fun load(): FirefoxSyncData = cached ?: read().also { cached = it }

    fun save(data: FirefoxSyncData) {
        cached = data
        val json = JSONObject()
            .put("version", VERSION)
            .putNullable("account", data.account)
            .putNullable("keysModified", data.keysModified)
            .putNullable("clientsModified", data.clientsModified)
            .putNullable("tabsModified", data.tabsModified)
            .put("lastSynced", data.lastSynced)
            .put("clients", JSONObject(data.clients))
            .put("tabs", JSONObject(data.tabs))
            .put("devices", JSONArray().apply { data.devices.forEach { put(it.toJson()) } })
        val stream = file.startWrite()
        try {
            stream.write(SyncJson.stringify(json).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    fun clear() {
        cached = FirefoxSyncData()
        file.delete()
    }

    private fun read(): FirefoxSyncData {
        val json = runCatching { JSONObject(String(file.readFully(), Charsets.UTF_8)) }.getOrNull()
        if (json == null || json.optInt("version") != VERSION) return FirefoxSyncData()
        fun records(key: String): Map<String, JSONObject> {
            val records = json.optJSONObject(key) ?: return emptyMap()
            return records.keys().asSequence().mapNotNull { id -> records.optJSONObject(id)?.let { id to it } }.toMap()
        }
        val devices = json.optJSONArray("devices")
        return FirefoxSyncData(
            account = json.string("account"),
            keysModified = json.string("keysModified"),
            clientsModified = json.string("clientsModified"),
            tabsModified = json.string("tabsModified"),
            lastSynced = json.optLong("lastSynced"),
            clients = records("clients"),
            tabs = records("tabs"),
            devices = (0 until (devices?.length() ?: 0)).mapNotNull { devices?.optJSONObject(it)?.let(::deviceOf) },
        )
    }

    private fun MirroredDevice.toJson(): JSONObject = JSONObject()
        .put("clientId", clientId)
        .putNullable("fxaDeviceId", fxaDeviceId)
        .put("name", name)
        .put("workspaceId", workspaceId)
        .put("positioned", positioned)
        .put(
            "groups",
            JSONArray().apply {
                groups.forEach { group ->
                    put(
                        JSONObject()
                            .put("id", group.id)
                            .put("folderId", group.folderId)
                            .put("name", group.name)
                            .put("collapsed", group.collapsed),
                    )
                }
            },
        )
        .put(
            "tabs",
            JSONArray().apply {
                tabs.forEach { tab ->
                    put(
                        JSONObject()
                            .put("id", tab.id)
                            .put("url", tab.url)
                            .put("title", tab.title)
                            .put("pinned", tab.pinned)
                            .putNullable("groupId", tab.groupId)
                            .put("closedHere", tab.closedHere)
                            .put("confirmed", tab.confirmed),
                    )
                }
            },
        )

    private fun deviceOf(json: JSONObject): MirroredDevice? {
        val clientId = json.string("clientId") ?: return null
        val workspaceId = json.string("workspaceId") ?: return null
        val groups = json.optJSONArray("groups")
        val tabs = json.optJSONArray("tabs")
        return MirroredDevice(
            clientId = clientId,
            fxaDeviceId = json.string("fxaDeviceId"),
            name = json.string("name").orEmpty(),
            workspaceId = workspaceId,
            positioned = json.optBoolean("positioned", true),
            groups = (0 until (groups?.length() ?: 0)).mapNotNull { index ->
                val group = groups?.optJSONObject(index) ?: return@mapNotNull null
                MirroredGroup(
                    id = group.string("id") ?: return@mapNotNull null,
                    folderId = group.string("folderId") ?: return@mapNotNull null,
                    name = group.string("name").orEmpty(),
                    collapsed = group.optBoolean("collapsed"),
                )
            },
            tabs = (0 until (tabs?.length() ?: 0)).mapNotNull { index ->
                val tab = tabs?.optJSONObject(index) ?: return@mapNotNull null
                MirroredTab(
                    id = tab.string("id") ?: return@mapNotNull null,
                    url = tab.string("url") ?: return@mapNotNull null,
                    title = tab.string("title").orEmpty(),
                    pinned = tab.optBoolean("pinned"),
                    groupId = tab.string("groupId"),
                    closedHere = tab.optBoolean("closedHere"),
                    confirmed = tab.optBoolean("confirmed"),
                )
            },
        )
    }

    private companion object {
        const val VERSION = 1
    }
}
