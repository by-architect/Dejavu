/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components.metrics

import android.content.Context
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.utils.Settings

/**
 * The F-Droid build's stand-in for the install referrer metrics service.
 *
 * Google Play's install referrer library is not free software, so it is not part of this build. A build installed
 * from F-Droid has no Play Store install to be referred by, so there are no UTM parameters to derive.
 */
@Suppress("UNUSED_PARAMETER")
class InstallReferrerMetricsService(
    context: Context,
    settings: Settings,
) : MetricsService {
    private val logger = Logger("InstallReferrerMetricsService")
    override val type = MetricServiceType.Data

    override fun start() {
        logger.debug("No install referrer to read in this build")
    }

    override fun stop() = Unit

    override fun track(event: Event) = Unit

    override fun shouldTrack(event: Event): Boolean = false
}
