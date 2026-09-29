/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.sync

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * What Kaizen remembers about the spaces collection between syncs.
 *
 * @property syncId The collection's sync id in meta/global. When another device resets the collection it changes, and
 *   Kaizen then syncs everything again.
 * @property lastModified Server time of the collection when it was last synced, in decimal seconds.
 * @property keysModified Server time of crypto/keys when it was last read.
 * @property account Storage user the data belongs to.
 * @property lastSynced Local time of the last complete sync, in milliseconds.
 * @property server The server's copy of every record Kaizen applied or uploaded, tombstones included. Local data is
 *   compared with it to find what to upload, and records start from it so fields Kaizen does not show are kept.
 * @property failed Incoming records that could not be applied yet; they are applied again on every sync.
 */
internal class SpacesSyncData(
    var syncId: String? = null,
    var lastModified: String? = null,
    var keysModified: String? = null,
    var account: String? = null,
    var lastSynced: Long = 0L,
    val server: MutableMap<String, SpacesRecord> = LinkedHashMap(),
    val failed: MutableMap<String, SpacesRecord> = LinkedHashMap(),
) {
    /** Forgets the collection, so the next sync downloads everything and uploads whatever differs locally. */
    fun resetCollection() {
        lastModified = null
        server.clear()
        failed.clear()
    }

    /** Forgets everything about the server, like after signing out. */
    fun resetAll() {
        syncId = null
        keysModified = null
        account = null
        lastSynced = 0L
        resetCollection()
    }
}

/** Keeps [SpacesSyncData] in a JSON file. Not thread safe; the sync engine uses it from one sync at a time. */
internal class SpacesSyncStore(file: File) {
    private val file = AtomicFile(file)
    private var cached: SpacesSyncData? = null

    fun load(): SpacesSyncData = cached ?: read().also { cached = it }

    fun save(data: SpacesSyncData) {
        cached = data
        val json = JSONObject()
            .put("version", VERSION)
            .putNullable("syncId", data.syncId)
            .putNullable("lastModified", data.lastModified)
            .putNullable("keysModified", data.keysModified)
            .putNullable("account", data.account)
            .put("lastSynced", data.lastSynced)
            .put("server", JSONArray().apply { data.server.values.forEach { put(it.toCleartext()) } })
            .put("failed", JSONArray().apply { data.failed.values.forEach { put(it.toCleartext()) } })
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
        cached = SpacesSyncData()
        file.delete()
    }

    private fun read(): SpacesSyncData {
        val json = runCatching { JSONObject(String(file.readFully(), Charsets.UTF_8)) }.getOrNull()
        if (json == null || json.optInt("version") != VERSION) return SpacesSyncData()
        fun records(key: String) = LinkedHashMap<String, SpacesRecord>().apply {
            val array = json.optJSONArray(key) ?: return@apply
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { SpacesRecord.fromCleartext(it) }?.let { put(it.id, it) }
            }
        }
        return SpacesSyncData(
            syncId = json.string("syncId"),
            lastModified = json.string("lastModified"),
            keysModified = json.string("keysModified"),
            account = json.string("account"),
            lastSynced = json.optLong("lastSynced"),
            server = records("server"),
            failed = records("failed"),
        )
    }

    private companion object {
        const val VERSION = 1
    }
}
