/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components.metrics

import android.app.Application
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import mozilla.components.lib.crash.CrashReporter
import mozilla.components.support.base.log.logger.Logger

/**
 * The F-Droid build's stand-in for the Adjust marketing attribution service.
 *
 * Adjust is a closed source SDK, so it is not part of this build. Nothing is measured and nothing is
 * sent: marketing telemetry is off by default in Dejavu, and this build has no attribution service
 * to report to.
 */
@Suppress("UNUSED_PARAMETER")
class AdjustMetricsService(
    application: Application,
    storage: MetricsStorage,
    crashReporter: CrashReporter,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MetricsService {
    private val logger = Logger("AdjustMetricsService")
    override val type = MetricServiceType.Marketing

    override fun start() {
        logger.debug("No attribution service in this build")
    }

    override fun stop() = Unit

    override fun track(event: Event) = Unit

    override fun shouldTrack(event: Event): Boolean = false
}
