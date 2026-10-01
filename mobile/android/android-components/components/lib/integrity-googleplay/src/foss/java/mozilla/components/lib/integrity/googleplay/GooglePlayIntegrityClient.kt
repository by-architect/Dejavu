/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.integrity.googleplay

import android.content.Context
import mozilla.components.concept.integrity.IntegrityClient
import mozilla.components.concept.integrity.IntegrityToken
import mozilla.components.concept.integrity.RequestHashProvider

/**
 * Identifies the caller a token was requested for.
 *
 * Kept so that everything calling into this module still compiles; this build issues no tokens.
 */
@JvmInline
value class IntegrityConsumer(val value: String) {
    companion object {
        val Summarize = IntegrityConsumer("summarize")
        val IpProtection = IntegrityConsumer("ip_protection")
        val Unknown = IntegrityConsumer("unknown")
    }
}

/**
 * The F-Droid build's stand-in for the Google Play Integrity client.
 *
 * The Play Integrity API is not free software, so it is not part of this build. It attests the app
 * and device to Google, which cannot work on a device without Google Play services in any case, so
 * every request fails here and the features that use it fall back as they already do on such a
 * device.
 */
class GooglePlayIntegrityClient internal constructor() : IntegrityClient {

    companion object {
        /** Builds a client that issues no tokens. */
        @Suppress("UNUSED_PARAMETER")
        fun create(
            context: Context,
            projectNumberToken: String,
            requestHashProvider: RequestHashProvider,
        ) = GooglePlayIntegrityClient()
    }

    override suspend fun warmUp(): Boolean = false

    override suspend fun request(): Result<IntegrityToken> = Result.failure(NoIntegrityService())

    /** Returns a view for [consumer]; it issues no tokens either. */
    @Suppress("UNUSED_PARAMETER")
    fun forConsumer(consumer: IntegrityConsumer): IntegrityClient =
        object : IntegrityClient {
            override suspend fun warmUp() = false

            override suspend fun request(): Result<IntegrityToken> =
                Result.failure(NoIntegrityService())
        }
}

/** Raised when an integrity token is requested from a build that has no integrity service. */
class NoIntegrityService :
    IllegalStateException("This build has no Google Play Integrity service.")

/** Kept for source compatibility with the Google Play build. */
class InvalidProjectNumber :
    IllegalStateException("Google Cloud project number is missing or not a valid number.")
