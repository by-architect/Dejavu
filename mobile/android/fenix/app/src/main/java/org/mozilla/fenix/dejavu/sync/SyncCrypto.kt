/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import java.net.URI
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/** A sync record that could not be decrypted, usually because it was encrypted with other keys. */
internal class SyncCryptoException(message: String) : GeneralSecurityException(message)

/**
 * An AES-256 key and an HMAC-SHA256 key that encrypt and authenticate Firefox Sync records, like Firefox's
 * `BulkKeyBundle`. A payload is `{ciphertext, IV, hmac}`: base64 AES-CBC ciphertext and IV, and the hex HMAC of the
 * base64 ciphertext text.
 */
internal class KeyBundle(private val encryptionKey: ByteArray, private val hmacKey: ByteArray) {
    fun encrypt(cleartext: String): JSONObject {
        val iv = ByteArray(IV_SIZE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv))
        val ciphertext = base64.encodeToString(cipher.doFinal(cleartext.toByteArray(Charsets.UTF_8)))
        return JSONObject()
            .put("ciphertext", ciphertext)
            .put("IV", base64.encodeToString(iv))
            .put("hmac", hmac(ciphertext))
    }

    fun decrypt(payload: JSONObject): String {
        val ciphertext = payload.string("ciphertext") ?: throw SyncCryptoException("No ciphertext")
        val iv = payload.string("IV") ?: throw SyncCryptoException("No IV")
        val expected = payload.string("hmac")?.lowercase() ?: throw SyncCryptoException("No HMAC")
        if (!MessageDigest.isEqual(hmac(ciphertext).toByteArray(), expected.toByteArray())) {
            throw SyncCryptoException("HMAC mismatch")
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(decoder.decode(iv)))
            String(cipher.doFinal(decoder.decode(ciphertext)), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw SyncCryptoException("Malformed payload: ${e.message}")
        }
    }

    private fun hmac(ciphertext: String): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(hmacKey, HMAC))
        return mac.doFinal(ciphertext.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
        private const val HMAC = "HmacSHA256"
        private const val IV_SIZE = 16
        private const val KEY_SIZE = 32
        private val random = SecureRandom()
        private val base64 = Base64.getEncoder()
        private val decoder = Base64.getDecoder()

        /** The bundle of the account's sync key: the `k` of the sync scope's key, 64 bytes in base64url. */
        fun fromSyncKey(key: String): KeyBundle {
            val bytes = Base64.getUrlDecoder().decode(key.trimEnd('='))
            if (bytes.size != 2 * KEY_SIZE) throw SyncCryptoException("Unexpected sync key length ${bytes.size}")
            return KeyBundle(bytes.copyOfRange(0, KEY_SIZE), bytes.copyOfRange(KEY_SIZE, 2 * KEY_SIZE))
        }

        /** A bundle as crypto/keys stores it: `[encryption key, HMAC key]`, both in base64. */
        fun fromPair(pair: JSONArray?): KeyBundle? {
            val encryption = pair?.opt(0) as? String ?: return null
            val hmac = pair.opt(1) as? String ?: return null
            return runCatching { KeyBundle(decoder.decode(encryption), decoder.decode(hmac)) }.getOrNull()
                ?.takeIf { it.encryptionKey.size == KEY_SIZE && it.hmacKey.size == KEY_SIZE }
        }
    }
}

/**
 * Signs requests to the sync storage server with Hawk, like Firefox's `CryptoUtils.computeHAWK`.
 */
internal object Hawk {
    private const val NONCE_SIZE = 8
    private const val HTTP_PORT = 80
    private const val HTTPS_PORT = 443
    private val random = SecureRandom()

    /**
     * The `Authorization` header for a request.
     *
     * @param id Hawk id, from the token server.
     * @param key Hawk key, from the token server.
     * @param method HTTP method of the request.
     * @param url Full URL of the request.
     * @param seconds Current time on the server, in seconds.
     * @param nonce Random text that makes the signature unique.
     * @param payloadHash See [payloadHash]; storage servers do not require it.
     * @param ext Application data covered by the signature; not used by sync.
     */
    @Suppress("LongParameterList")
    fun header(
        id: String,
        key: String,
        method: String,
        url: String,
        seconds: Long,
        nonce: String = newNonce(),
        payloadHash: String? = null,
        ext: String? = null,
    ): String {
        val uri = URI(url)
        val resource = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        val port = uri.port.takeIf { it != -1 } ?: if (uri.scheme == "http") HTTP_PORT else HTTPS_PORT
        val normalized = buildString {
            append("hawk.1.header\n")
            append(seconds).append('\n')
            append(nonce).append('\n')
            append(method.uppercase()).append('\n')
            append(resource).append('\n')
            append(uri.host.lowercase()).append('\n')
            append(port).append('\n')
            append(payloadHash.orEmpty()).append('\n')
            ext?.let { append(it.replace("\\", "\\\\").replace("\n", "\\n")) }
            append('\n')
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = Base64.getEncoder().encodeToString(mac.doFinal(normalized.toByteArray(Charsets.UTF_8)))
        return buildString {
            append("Hawk id=\"").append(id).append("\", ts=\"").append(seconds).append("\", nonce=\"").append(nonce)
            append("\", ")
            payloadHash?.let { append("hash=\"").append(it).append("\", ") }
            ext?.let { append("ext=\"").append(it.replace("\\", "\\\\").replace("\"", "\\\"")).append("\", ") }
            append("mac=\"").append(signature).append('"')
        }
    }

    /** The Hawk hash of a request body with the given MIME type (without parameters). */
    fun payloadHash(contentType: String, payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("hawk.1.payload\n$contentType\n$payload\n".toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun newNonce(): String =
        Base64.getEncoder().encodeToString(ByteArray(NONCE_SIZE).also { random.nextBytes(it) })
}
