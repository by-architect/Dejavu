/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.sync

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class SyncCryptoTest {
    private val url = "https://example.net/somewhere/over/the/rainbow"

    // Vectors from Firefox's services/crypto/tests/unit/test_utils_hawk.js.
    @Test
    fun `Hawk headers match Firefox's`() {
        val hash = Hawk.payloadHash("text/plain", "something to write about")
        assertEquals("2QfCt3GuY9HQnHWyWD3wX68ZOKbynqlfYmuO2ZBRqtY=", hash)
        assertEquals(
            "Hawk id=\"123456\", ts=\"1353809207\", nonce=\"Ygvqdz\", " +
                "hash=\"2QfCt3GuY9HQnHWyWD3wX68ZOKbynqlfYmuO2ZBRqtY=\", ext=\"Bazinga!\", " +
                "mac=\"q1CwFoSHzPZSkbIvl0oYlD+91rBUEvFk763nMjMndj8=\"",
            Hawk.header("123456", "2983d45yun89q", "POST", url, 1353809207, "Ygvqdz", hash, ext = "Bazinga!"),
        )
        assertEquals(
            "Hawk id=\"123456\", ts=\"1353809207\", nonce=\"Ygvqdz\", " +
                "hash=\"2QfCt3GuY9HQnHWyWD3wX68ZOKbynqlfYmuO2ZBRqtY=\", " +
                "mac=\"HTgtd0jPI6E4izx8e4OHdO36q00xFCU0FolNq3RiCYs=\"",
            Hawk.header("123456", "2983d45yun89q", "post", url.replace("example.net", "EXAMPLE.NET"), 1353809207, "Ygvqdz", hash),
        )
    }

    @Test
    fun `records encrypt and decrypt with the same keys only`() {
        val keys = KeyBundle.fromSyncKey(syncKey(1))
        val payload = keys.encrypt("""{"id":"a","kind":"space"}""")

        assertEquals("""{"id":"a","kind":"space"}""", keys.decrypt(payload))
        assertTrue(runCatching { KeyBundle.fromSyncKey(syncKey(2)).decrypt(payload) }.exceptionOrNull() is SyncCryptoException)
        payload.put("ciphertext", Base64.getEncoder().encodeToString(ByteArray(32)))
        assertTrue(runCatching { keys.decrypt(payload) }.exceptionOrNull() is SyncCryptoException)
    }

    @Test
    fun `collection keys are read from base64 pairs`() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        val bundle = KeyBundle.fromPair(JSONArray().put(key).put(key))!!

        assertEquals("[]", bundle.decrypt(bundle.encrypt("[]")))
        assertEquals(null, KeyBundle.fromPair(JSONArray().put("short").put(key)))
    }

    @Test
    fun `token server urls get the Sync path once`() {
        val expected = "https://token.services.mozilla.com/1.0/sync/1.5"
        assertEquals(expected, TokenServerClient.endpointOf("https://token.services.mozilla.com"))
        assertEquals(expected, TokenServerClient.endpointOf("https://token.services.mozilla.com/"))
        assertEquals(expected, TokenServerClient.endpointOf(expected))
        assertEquals(expected, TokenServerClient.endpointOf("$expected/"))
    }

    @Test
    fun `server timestamps compare as decimals`() {
        assertTrue(isNewerTimestamp("1727600000.12", "1727600000.1"))
        assertFalse(isNewerTimestamp("1727600000.10", "1727600000.1"))
        assertTrue(isNewerTimestamp("5", null))
        assertEquals("1727600000.12", timestampOf(1727600000.12))
        assertEquals("1727600000.00", timestampOf(1727600000))
    }

    @Test
    fun `sync ids look like Firefox's`() {
        val id = SpacesSyncEngine.newSyncId()
        assertEquals(12, id.length)
        assertTrue(id.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    companion object {
        fun syncKey(seed: Int): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64) { (it * seed + seed).toByte() })
    }
}
