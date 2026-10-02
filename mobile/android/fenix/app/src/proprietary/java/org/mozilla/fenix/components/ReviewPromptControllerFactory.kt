/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

import android.content.Context
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Builds the controller that drives Google Play's in-app review flow.
 *
 * The F-Droid build has its own version of this function that returns a controller doing nothing, so that nothing
 * outside this source set has to know whether Google Play is there.
 */
fun createPlayStoreReviewPromptController(
    context: Context,
    numberOfAppLaunches: () -> Int,
): PlayStoreReviewPromptController =
    PlayStoreReviewPromptController(
        manager = ReviewManagerFactory.create(context),
        numberOfAppLaunches = numberOfAppLaunches,
    )
