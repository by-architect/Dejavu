/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components.metrics

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import mozilla.components.support.base.log.logger.Logger

/**
 * The F-Droid build's stand-in for the install referrer handling service.
 *
 * Google Play's install referrer library is not free software, so it is not part of this build. A build installed
 * from F-Droid was not referred by a Play Store install, so there is no referrer to connect to and [response] stays
 * null.
 */
@Suppress("UNUSED_PARAMETER")
class InstallReferrerHandlingService(
    context: Context,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val logger = Logger("InstallReferrerHandlingService")

    /** Does nothing: there is no install referrer to read in this build. */
    fun start() {
        logger.debug("No install referrer to read in this build")
    }

    companion object {
        /** Kept so the debug tools can show it; it is never set in this build. */
        var response: String? = null
    }
}
