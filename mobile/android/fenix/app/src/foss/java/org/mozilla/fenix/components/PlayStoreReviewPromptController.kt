/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components

import android.app.Activity
import android.content.Context
import androidx.annotation.VisibleForTesting
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.GleanMetrics.ReviewPrompt
import org.mozilla.fenix.components.ReviewPromptAttemptResult.Displayed
import org.mozilla.fenix.components.ReviewPromptAttemptResult.Error
import org.mozilla.fenix.components.ReviewPromptAttemptResult.NotDisplayed
import org.mozilla.fenix.components.ReviewPromptAttemptResult.Unknown

val logger = Logger("PlayStoreReviewPromptController")

/**
 * The F-Droid build's stand-in for the Play Store In-App Review API.
 *
 * Google Play's review library is not free software, so it is not part of this build. Every entry point keeps the
 * shape the rest of the app expects and reports that no prompt was shown.
 */
class PlayStoreReviewPromptController(
    @Suppress("UNUSED_PARAMETER") numberOfAppLaunches: () -> Int,
) {

    /** Reports that no review prompt was shown, because there is no Play Store to show one. */
    suspend fun tryPromptReview(@Suppress("UNUSED_PARAMETER") activity: Activity): ReviewPromptAttemptResult {
        logger.info("No in-app review flow in this build.")
        return NotDisplayed
    }

    /** Does nothing: this build is not installed from the Play Store, so it has no listing to open. */
    fun tryLaunchPlayStoreReview(
        @Suppress("UNUSED_PARAMETER") activity: Activity,
        @Suppress("UNUSED_PARAMETER") openInNewTab: (url: String) -> Unit,
    ) {
        logger.info("No Play Store listing to open in this build.")
    }
}

/**
 * Builds the controller the rest of the app uses.
 *
 * The Google Play build has its own version of this function that wires up the real review manager.
 */
fun createPlayStoreReviewPromptController(
    @Suppress("UNUSED_PARAMETER") context: Context,
    numberOfAppLaunches: () -> Int,
): PlayStoreReviewPromptController = PlayStoreReviewPromptController(numberOfAppLaunches)

/** Result of an attempt to show a Play Store In-App Review Prompt. */
sealed interface ReviewPromptAttemptResult {
    /** Attempted completed without error, but the API didn't allow to display the prompt. */
    data object NotDisplayed : ReviewPromptAttemptResult

    /** Prompt has been shown. */
    data object Displayed : ReviewPromptAttemptResult

    /** There was an error, for example this is a device without Play Store. */
    data class Error(val exception: Exception) : ReviewPromptAttemptResult

    /** Attempt completed without error, but we weren't able to determine if prompt has been shown or not. */
    data object Unknown : ReviewPromptAttemptResult

    companion object {
        /** Reads the review flow's outcome out of its string form, as the Google Play build does. */
        fun from(reviewInfoAsString: String): ReviewPromptAttemptResult {
            return when {
                reviewInfoAsString.contains("isNoOp=true") -> NotDisplayed
                reviewInfoAsString.contains("isNoOp=false") -> Displayed
                else -> Unknown
            }
        }
    }
}

/** Records a [ReviewPrompt] with the required data. */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
fun recordReviewPromptEvent(
    promptAttemptResult: ReviewPromptAttemptResult,
    numberOfAppLaunches: Int,
    now: Date,
) {
    val formattedLocalDatetime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(now)

    val promptWasDisplayed =
        when (promptAttemptResult) {
            NotDisplayed -> "false"
            Displayed -> "true"
            is Error,
            Unknown -> "error"
        }

    ReviewPrompt.promptAttempt.record(
        ReviewPrompt.PromptAttemptExtra(
            promptWasDisplayed = promptWasDisplayed,
            localDatetime = formattedLocalDatetime,
            numberOfAppLaunches = numberOfAppLaunches,
        )
    )
}
