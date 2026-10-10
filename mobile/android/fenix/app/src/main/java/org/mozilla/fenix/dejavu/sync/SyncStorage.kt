/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** Storage credentials of the account, fetched from the token server and kept until they are about to expire. */
internal class SyncTokens(private val http: SyncHttp, private val clock: SyncClock) {
    @Volatile
    private var token: SyncToken? = null

    fun get(auth: SyncAuth): SyncToken =
        token?.takeIf { it.expiresAt > clock.millis() } ?: TokenServerClient(http, clock).fetch(auth).also { token = it }

    fun forget() {
        token = null
    }
}

/**
 * Runs [block] once more with a new token when the servers refuse the current one: tokens sometimes expire before
 * their stated end.
 */
internal inline fun <T> SyncTokens.retryingOnce(block: () -> T): T = try {
    block()
} catch (e: SyncAuthException) {
    forget()
    block()
}

/**
 * meta/global, which lists the collections the account syncs.
 *
 * @property engines Each collection's entry, with its version and sync id.
 * @property declined Collections the user turned off for every device.
 */
internal class MetaGlobal(val engines: JSONObject, val declined: Set<String>) {
    fun has(collection: String): Boolean = engines.optJSONObject(collection) != null && collection !in declined
}

/** The keys of crypto/keys: one for every collection, unless a collection has its own. */
internal class CollectionKeys(private val default: KeyBundle, private val own: Map<String, KeyBundle>, val modified: String?) {
    fun of(collection: String): KeyBundle = own[collection] ?: default
}

/** Reads meta/global; `null` while Firefox Sync has not set up the account's storage. */
internal fun StorageClient.metaGlobal(): MetaGlobal? {
    val response = expect("GET", "storage/meta/global", allowed = setOf(HTTP_NOT_FOUND))
    if (response.status == HTTP_NOT_FOUND) return null
    val payload = JSONObject(JSONObject(response.body).getString("payload"))
    if (payload.optInt("storageVersion") != STORAGE_VERSION) return null
    return MetaGlobal(payload.optJSONObject("engines") ?: JSONObject(), payload.strings("declined").orEmpty().toSet())
}

/** Reads crypto/keys with the account's [syncKeys]; `null` while Firefox Sync has not set up the account's storage. */
internal fun StorageClient.collectionKeys(syncKeys: KeyBundle): CollectionKeys? {
    val response = expect("GET", "storage/crypto/keys", allowed = setOf(HTTP_NOT_FOUND))
    if (response.status == HTTP_NOT_FOUND) return null
    val keys = JSONObject(syncKeys.decrypt(JSONObject(JSONObject(response.body).getString("payload"))))
    val default = KeyBundle.fromPair(keys.optJSONArray("default"))
        ?: throw SyncCryptoException("crypto/keys has no default key")
    val collections = keys.optJSONObject("collections")
    val own = collections?.keys()?.asSequence()
        ?.mapNotNull { name -> KeyBundle.fromPair(collections.optJSONArray(name))?.let { name to it } }
        ?.toMap()
        .orEmpty()
    return CollectionKeys(default, own, response.header("X-Last-Modified"))
}

/**
 * Downloads every record of [collection] and decrypts it with [keys], by id. Records that cannot be read are left out.
 */
internal fun StorageClient.downloadAll(collection: String, keys: KeyBundle): Map<String, JSONObject> {
    val records = LinkedHashMap<String, JSONObject>()
    var modified: String? = null
    var offset: String? = null
    do {
        val query = buildString {
            append("full=1&limit=").append(PAGE_SIZE)
            offset?.let { append("&offset=").append(URLEncoder.encode(it, "UTF-8")) }
        }
        val response = expect(
            "GET",
            "storage/$collection?$query",
            ifUnmodifiedSince = modified.takeIf { offset != null },
            allowed = setOf(HTTP_NOT_FOUND),
        )
        if (response.status == HTTP_NOT_FOUND) break
        if (modified == null) modified = response.header("X-Last-Modified")
        val items = JSONArray(response.body)
        for (index in 0 until items.length()) {
            val bso = items.optJSONObject(index) ?: continue
            val id = bso.string("id") ?: continue
            runCatching { JSONObject(keys.decrypt(JSONObject(bso.getString("payload")))) }
                .getOrNull()
                ?.takeIf { it.string("id").let { inner -> inner == null || inner == id } }
                ?.let { records[id] = it }
        }
        offset = response.header("X-Weave-Next-Offset")
    } while (offset != null)
    return records
}

private const val STORAGE_VERSION = 5
private const val PAGE_SIZE = 1000
private const val HTTP_NOT_FOUND = 404
