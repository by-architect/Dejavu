/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.sync

import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.Header
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

/** An HTTP response of a sync server. */
internal class SyncResponse(val status: Int, headers: Map<String, String>, val body: String) {
    private val headers = headers.mapKeys { it.key.lowercase() }

    val isSuccess: Boolean
        get() = status in SUCCESS

    fun header(name: String): String? = headers[name.lowercase()]

    private companion object {
        val SUCCESS = 200..299
    }
}

/** Sends HTTP requests to the sync servers. Blocking, so it must be called off the main thread. */
internal fun interface SyncHttp {
    @Throws(IOException::class)
    fun send(method: String, url: String, headers: Map<String, String>, body: String?): SyncResponse
}

/** [SyncHttp] backed by the browser's fetch client, so sync requests use the browser's network settings. */
internal class FetchSyncHttp(private val client: Client) : SyncHttp {
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): SyncResponse {
        val request = Request(
            url = url,
            method = Request.Method.valueOf(method),
            headers = MutableHeaders(headers.map { (name, value) -> Header(name, value) }),
            connectTimeout = CONNECT_TIMEOUT_SECONDS to TimeUnit.SECONDS,
            readTimeout = READ_TIMEOUT_SECONDS to TimeUnit.SECONDS,
            body = body?.let { Request.Body.fromString(it) },
            cookiePolicy = Request.CookiePolicy.OMIT,
            useCaches = false,
            conservative = true,
        )
        return client.fetch(request).use { response ->
            SyncResponse(
                status = response.status,
                headers = response.headers.associate { it.name to it.value },
                body = response.body.string(Charsets.UTF_8),
            )
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_SECONDS = 60L
    }
}

/**
 * What the Mozilla account gives to reach the sync servers: an OAuth token for the sync scope with the scope's key.
 *
 * @property kid Id of the sync key, sent to the token server.
 * @property accessToken OAuth access token for the sync scope.
 * @property syncKey The sync key, base64url; decrypts the collection keys.
 * @property tokenServerUrl The token server of the account's servers.
 */
internal class SyncAuth(val kid: String, val accessToken: String, val syncKey: String, val tokenServerUrl: String)

/**
 * Hawk credentials for the account's storage, from the token server.
 *
 * @property id Hawk id.
 * @property key Hawk key.
 * @property endpoint Base URL of the account's storage.
 * @property uid The storage user, which changes when the account's storage is reset or moved.
 * @property expiresAt When to ask for a new token, in local milliseconds.
 */
internal class SyncToken(val id: String, val key: String, val endpoint: String, val uid: String, val expiresAt: Long)

/** A sync server refused or failed a request. [retryAfterSeconds] is set when the server asked to wait. */
internal open class SyncServerException(val status: Int, message: String, val retryAfterSeconds: Long? = null) :
    IOException(message)

/** The account's token was refused; syncing needs the account to be signed in again. */
internal class SyncAuthException(message: String) : IOException(message)

/** The collection changed on the server while syncing; syncing again downloads the change first. */
internal class SyncConflictException : IOException("The server changed during the sync")

/** Keeps the difference between the local clock and the servers' clock, which Hawk signatures must follow. */
internal class SyncClock(private val now: () -> Long = System::currentTimeMillis) {
    @Volatile
    private var offset = 0L

    fun millis(): Long = now()

    fun serverSeconds(): Long = (now() + offset) / MILLIS

    /** Updates the offset from a server time header, in decimal seconds. */
    fun update(serverSeconds: String?) {
        serverSeconds?.toDoubleOrNull()?.let { offset = (it * MILLIS).toLong() - now() }
    }

    private companion object {
        const val MILLIS = 1000L
    }
}

/** Exchanges the account's OAuth token for storage credentials. */
internal class TokenServerClient(private val http: SyncHttp, private val clock: SyncClock) {
    fun fetch(auth: SyncAuth): SyncToken {
        val response = http.send(
            method = "GET",
            url = endpointOf(auth.tokenServerUrl),
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer ${auth.accessToken}",
                "X-KeyID" to auth.kid,
            ),
            body = null,
        )
        clock.update(response.header("X-Timestamp"))
        if (response.status == HTTP_UNAUTHORIZED || response.status == HTTP_FORBIDDEN) {
            throw SyncAuthException("The token server refused the account (${response.status})")
        }
        if (!response.isSuccess) {
            throw SyncServerException(response.status, "Token server error ${response.status}", retryAfter(response))
        }
        val json = JSONObject(response.body)
        val duration = json.optLong("duration", DEFAULT_DURATION_SECONDS)
        return SyncToken(
            id = json.getString("id"),
            key = json.getString("key"),
            endpoint = json.getString("api_endpoint").trimEnd('/'),
            uid = json.opt("uid")?.toString().orEmpty(),
            expiresAt = clock.millis() + duration * TOKEN_LIFETIME_USED_PERMILLE,
        )
    }

    companion object {
        private const val SYNC_PATH = "1.0/sync/1.5"
        private const val DEFAULT_DURATION_SECONDS = 3600L

        /** Renews tokens after 80% of their lifetime, in milliseconds per second of lifetime. */
        private const val TOKEN_LIFETIME_USED_PERMILLE = 800L

        /** The account's token server URL may or may not end with the Sync 1.5 path, like application-services allows. */
        fun endpointOf(url: String): String {
            val base = url.trimEnd('/')
            return if (base.endsWith(SYNC_PATH)) base else "$base/$SYNC_PATH"
        }
    }
}

/**
 * Talks to the account's Sync 1.5 storage with Hawk signed requests. 401, 412 and 503 answers are thrown as
 * [SyncAuthException], [SyncConflictException] and [SyncServerException]; other answers are returned.
 *
 * @param http Sends the requests.
 * @param token Credentials for the storage.
 * @param clock Server time for the signatures.
 * @param onBackoff Called with the seconds the server asks clients to wait before the next sync.
 */
internal class StorageClient(
    private val http: SyncHttp,
    private val token: SyncToken,
    private val clock: SyncClock,
    private val onBackoff: (Long) -> Unit,
) {
    fun request(method: String, path: String, body: String? = null, ifUnmodifiedSince: String? = null): SyncResponse {
        val url = "${token.endpoint}/$path"
        val headers = mutableMapOf(
            "Accept" to "application/json",
            "Authorization" to Hawk.header(token.id, token.key, method, url, clock.serverSeconds()),
        )
        if (body != null) headers["Content-Type"] = "application/json; charset=utf-8"
        ifUnmodifiedSince?.let { headers["X-If-Unmodified-Since"] = it }
        val response = http.send(method, url, headers, body)
        clock.update(response.header("X-Weave-Timestamp"))
        response.header("X-Weave-Backoff")?.toLongOrNull()?.let(onBackoff)
        when (response.status) {
            HTTP_UNAUTHORIZED -> throw SyncAuthException("The storage server refused the token")
            HTTP_PRECONDITION_FAILED -> throw SyncConflictException()
            HTTP_TOO_MANY_REQUESTS, HTTP_UNAVAILABLE -> throw SyncServerException(
                response.status,
                "Storage server busy (${response.status})",
                retryAfter(response),
            )
        }
        return response
    }

    /** Like [request], and throws for any answer other than a success or [allowed]. */
    fun expect(
        method: String,
        path: String,
        body: String? = null,
        ifUnmodifiedSince: String? = null,
        allowed: Set<Int> = emptySet(),
    ): SyncResponse {
        val response = request(method, path, body, ifUnmodifiedSince)
        if (!response.isSuccess && response.status !in allowed) {
            throw SyncServerException(response.status, "$method $path failed with ${response.status}")
        }
        return response
    }
}

/** Compares server timestamps, which are decimal seconds like "1727600000.12". */
internal fun isNewerTimestamp(value: String?, than: String?): Boolean {
    val a = value?.toBigDecimalOrNull() ?: return false
    val b = than?.toBigDecimalOrNull() ?: return true
    return a > b
}

/** A server timestamp read from JSON, where it is a number, in the server's own two decimals form. */
internal fun timestampOf(value: Any?): String? = when (value) {
    is Number -> BigDecimal(value.toString()).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
    is String -> value.takeIf { it.toBigDecimalOrNull() != null }
    else -> null
}

private fun retryAfter(response: SyncResponse): Long? =
    (response.header("Retry-After") ?: response.header("X-Weave-Backoff"))?.toLongOrNull()

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_PRECONDITION_FAILED = 412
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_UNAVAILABLE = 503
