/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * A token server and Sync 1.5 storage in memory, with the account's crypto/keys and meta/global set up like Firefox
 * does. [upload] plays another device, like Zen desktop.
 */
internal class FakeSyncServer(syncKey: String) : SyncHttp {
    private class Bso(val payload: String, val modified: Long)

    private val accountKeys = KeyBundle.fromSyncKey(syncKey)
    private val collectionKeys = KeyBundle(ByteArray(KEY_SIZE) { 1 }, ByteArray(KEY_SIZE) { 2 })
    private val collections = HashMap<String, LinkedHashMap<String, Bso>>()
    private var now = START

    /** "METHOD path" of every request, in order. */
    val requests = mutableListOf<String>()

    /** Ids of the records of every upload of the spaces collection. */
    val uploads = mutableListOf<List<String>>()

    /** Makes the next upload fail as if another device wrote first. */
    var conflictOnNextUpload = false

    init {
        val key = Base64.getEncoder().encodeToString(ByteArray(KEY_SIZE) { 1 })
        val hmac = Base64.getEncoder().encodeToString(ByteArray(KEY_SIZE) { 2 })
        val keys = JSONObject()
            .put("id", "keys")
            .put("collection", "crypto")
            .put("default", JSONArray().put(key).put(hmac))
            .put("collections", JSONObject())
        put("crypto", "keys", SyncJson.stringify(accountKeys.encrypt(SyncJson.stringify(keys))), tick())
        setMeta(JSONObject().put("spaces", JSONObject().put("version", 3).put("syncID", ZEN_SYNC_ID)))
    }

    fun setMeta(engines: JSONObject, declined: List<String> = emptyList()) {
        val payload = JSONObject()
            .put("syncID", "global-sync")
            .put("storageVersion", 5)
            .put("engines", engines)
            .put("declined", JSONArray(declined))
        put("meta", "global", SyncJson.stringify(payload), tick())
    }

    fun meta(): JSONObject = JSONObject(collections.getValue("meta").getValue("global").payload)

    /** Uploads [records] as another device would, in one batch. */
    fun upload(records: List<SpacesRecord>) {
        val modified = tick()
        records.forEach { put(COLLECTION, it.id, encrypt(it), modified) }
    }

    fun record(id: String): SpacesRecord? = collections[COLLECTION]?.get(id)?.let { decrypt(id, it) }

    fun records(): Map<String, SpacesRecord> =
        collections[COLLECTION].orEmpty().mapValues { (id, bso) -> decrypt(id, bso) }

    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): SyncResponse {
        val uri = URI(url)
        if (uri.host == "token.example") {
            requests += "$method token"
            check(headers["X-KeyID"] != null && headers["Authorization"] == "Bearer token")
            val token = JSONObject()
                .put("id", "hawk-id")
                .put("key", "hawk-key")
                .put("api_endpoint", "https://storage.example/1.5/42")
                .put("uid", 42)
                .put("duration", 3600)
            return SyncResponse(200, mapOf("X-Timestamp" to format(now)), token.toString())
        }
        check(headers["Authorization"].orEmpty().startsWith("Hawk id=\"hawk-id\""))
        val path = uri.rawPath.removePrefix("/1.5/42/")
        val query = uri.rawQuery?.split("&")?.associate {
            it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }.orEmpty()
        val since = headers["X-If-Unmodified-Since"]
        requests += "$method $path"
        return when {
            path == "info/collections" -> {
                val info = collections.keys.joinToString(",", "{", "}") { "\"$it\":${format(modified(it))}" }
                SyncResponse(200, emptyMap(), info)
            }
            path == "storage/meta/global" && method == "PUT" -> {
                if (since != null && isNewerTimestamp(format(modified("meta")), since)) return conflict()
                val modified = tick()
                put("meta", "global", JSONObject(body!!).getString("payload"), modified)
                SyncResponse(200, mapOf("X-Last-Modified" to format(modified)), format(modified))
            }
            path == "storage/meta/global" -> bso("meta", "global")
            path == "storage/crypto/keys" -> bso("crypto", "keys")
            path == "storage/$COLLECTION" && method == "GET" -> {
                val newer = query["newer"]
                val items = JSONArray()
                collections[COLLECTION].orEmpty().entries
                    .filter { newer == null || isNewerTimestamp(format(it.value.modified), newer) }
                    .sortedBy { it.value.modified }
                    .forEach { (id, bso) -> items.put(JSONObject().put("id", id).put("payload", bso.payload)) }
                SyncResponse(200, mapOf("X-Last-Modified" to format(modified(COLLECTION))), items.toString())
            }
            path == "storage/$COLLECTION" && method == "POST" -> {
                if (conflictOnNextUpload) {
                    conflictOnNextUpload = false
                    return conflict()
                }
                if (since != null && isNewerTimestamp(format(modified(COLLECTION)), since)) return conflict()
                val modified = tick()
                val items = JSONArray(body!!)
                val ids = (0 until items.length()).map { items.getJSONObject(it).getString("id") }
                (0 until items.length()).forEach {
                    val item = items.getJSONObject(it)
                    put(COLLECTION, item.getString("id"), item.getString("payload"), modified)
                }
                uploads += ids
                val result = JSONObject().put("success", JSONArray(ids)).put("failed", JSONObject())
                SyncResponse(200, mapOf("X-Last-Modified" to format(modified)), result.toString())
            }
            else -> SyncResponse(404, emptyMap(), "{}")
        }
    }

    private fun bso(collection: String, id: String): SyncResponse {
        val bso = collections[collection]?.get(id) ?: return SyncResponse(404, emptyMap(), "{}")
        val body = JSONObject().put("id", id).put("payload", bso.payload)
        return SyncResponse(200, mapOf("X-Last-Modified" to format(bso.modified)), body.toString())
    }

    private fun conflict() = SyncResponse(412, emptyMap(), "{}")

    private fun encrypt(record: SpacesRecord) = SyncJson.stringify(collectionKeys.encrypt(SyncJson.stringify(record.toCleartext())))

    private fun decrypt(id: String, bso: Bso) =
        SpacesRecord.fromCleartext(id, JSONObject(collectionKeys.decrypt(JSONObject(bso.payload))))!!

    private fun put(collection: String, id: String, payload: String, modified: Long) {
        collections.getOrPut(collection) { LinkedHashMap() }[id] = Bso(payload, modified)
    }

    private fun modified(collection: String): Long = collections[collection]?.values?.maxOfOrNull { it.modified } ?: 0L

    private fun tick(): Long {
        now += 1
        return now
    }

    private fun format(centiseconds: Long) = "%d.%02d".format(centiseconds / 100, centiseconds % 100)

    companion object {
        const val ZEN_SYNC_ID = "zenSyncId001"
        private const val COLLECTION = "spaces"
        private const val KEY_SIZE = 32
        private const val START = 172_700_000_000L
    }
}
