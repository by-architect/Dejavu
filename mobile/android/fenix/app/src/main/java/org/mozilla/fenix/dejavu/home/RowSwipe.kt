/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mozilla.fenix.dejavu.ui.iconPainter

/**
 * What swiping a row one way does: [run], marked by [icon], on the error color when it [closes] something, and where
 * the row [ends] once swiped far enough.
 */
internal class SwipeOption(
    @param:DrawableRes val icon: Int,
    val closes: Boolean,
    val ends: SwipeEnd,
    val run: () -> Unit,
)

/** Where a row swiped far enough goes. */
internal enum class SwipeEnd {
    /** Off the page, as what the row shows goes away with it. */
    AWAY,

    /** Off the page and back into its place, as the row of a pinned tab stays once its tab closed. */
    OUT_AND_BACK,

    /** Back into its place while the action runs, which may ask something first or change the row. */
    BACK,
}

/**
 * Lets the user run [right] or [left] by swiping this row that way. The row follows the finger over the action's mark
 * and runs it once swiped far or fast enough, then ends where its [SwipeEnd] says; otherwise it springs back. Only a
 * quick sideways move counts: a long press is left to dragging rows, a move up or down to scrolling, and a move toward
 * a way without an action to turning the workspaces. Without either, the row is left as it is.
 *
 * Put it after the row's padding and before its own shape and background, which then move with the finger.
 */
internal fun Modifier.rowSwipe(right: SwipeOption?, left: SwipeOption?): Modifier =
    if (right == null && left == null) {
        this
    } else {
        composed {
            val rightNow by rememberUpdatedState(right)
            val leftNow by rememberUpdatedState(left)
            val offset = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            val haptics = LocalHapticFeedback.current
            val rightMark = right?.let { iconPainter(it.icon) }
            val leftMark = left?.let { iconPainter(it.icon) }
            val scheme = MaterialTheme.colorScheme

            // The option of the way a row moved by dx goes, and how far it shows moved: not at all toward a way
            // without one.
            fun optionAt(dx: Float): SwipeOption? = when {
                dx > 0 -> rightNow
                dx < 0 -> leftNow
                else -> null
            }
            fun shownAt(dx: Float): Float = if (optionAt(dx) == null) 0f else dx

            pointerInput(Unit) {
                // The finger is followed in the first pass, before the row's buttons see it, and the swipe is claimed
                // before the pages can turn or the list scroll with it.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val slop = viewConfiguration.touchSlop
                    var moved = Offset.Zero
                    val started = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var decided: Boolean? = null
                        while (decided == null) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed || change.isConsumed) {
                                decided = false
                            } else {
                                moved += change.positionChange()
                                if (abs(moved.y) > slop) {
                                    decided = false
                                } else if (abs(moved.x) > slop && abs(moved.x) > abs(moved.y) * SIDEWAYS_RATIO) {
                                    val claimed = optionAt(moved.x) != null
                                    if (claimed) change.consume()
                                    decided = claimed
                                }
                            }
                        }
                        decided == true
                    } ?: false
                    if (!started) return@awaitEachGesture
                    scope.launch { offset.snapTo(shownAt(moved.x)) }

                    val tracker = VelocityTracker()
                    var past = false
                    var dragged = moved.x
                    var ended = false
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes
                            .firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) {
                            ended = true
                            break
                        }
                        tracker.addPosition(change.uptimeMillis, change.position)
                        dragged += change.positionChangeIgnoreConsumed().x
                        val value = shownAt(dragged)
                        scope.launch { offset.snapTo(value) }
                        val nowPast = abs(value) > size.width * RUN_SHARE
                        if (nowPast && !past) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        past = nowPast
                    }
                    val value = shownAt(dragged)
                    val option = optionAt(value)
                    val velocity = if (ended) tracker.calculateVelocity().x else 0f
                    val fast = abs(velocity) > FLING_VELOCITY && sign(velocity) == sign(value)
                    val width = size.width
                    scope.launch {
                        if (ended && option != null && (abs(value) > width * RUN_SHARE || fast)) {
                            when (option.ends) {
                                SwipeEnd.AWAY -> {
                                    offset.animateTo(sign(value) * width, tween(OUT_MS, easing = FastOutLinearInEasing))
                                    option.run()
                                }
                                SwipeEnd.OUT_AND_BACK -> {
                                    offset.animateTo(sign(value) * width, tween(OUT_MS, easing = FastOutLinearInEasing))
                                    option.run()
                                    delay(BACK_DELAY_MS)
                                    offset.snapTo(0f)
                                }
                                SwipeEnd.BACK -> {
                                    option.run()
                                    offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                }
                            }
                        } else {
                            offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                    }
                }
            }
                .drawBehind {
                    val value = offset.value
                    val option = optionAt(value) ?: return@drawBehind
                    val mark = (if (value > 0) rightMark else leftMark) ?: return@drawBehind
                    val shown = (abs(value) / (size.width * RUN_SHARE)).coerceIn(0f, 1f)
                    val back = if (option.closes) scheme.error else scheme.primary
                    val tint = if (option.closes) scheme.onError else scheme.onPrimary
                    drawRoundRect(
                        color = back.copy(alpha = BACK_ALPHA * shown),
                        cornerRadius = CornerRadius(CORNER.toPx()),
                    )
                    val icon = ICON.toPx()
                    val inset = INSET.toPx()
                    val x = if (value > 0) inset else size.width - inset - icon
                    translate(left = x, top = (size.height - icon) / 2) {
                        with(mark) { draw(Size(icon, icon), alpha = shown, colorFilter = ColorFilter.tint(tint)) }
                    }
                }
                .offset { IntOffset(offset.value.roundToInt(), 0) }
        }
    }

// How far a row is swiped, as a share of its width, before letting go runs its action, and how fast a fling must be.
private const val RUN_SHARE = 0.4f
private const val FLING_VELOCITY = 1500f

// How much more a move must go sideways than up or down to be a swipe rather than a scroll.
private const val SIDEWAYS_RATIO = 2f
private const val OUT_MS = 180
private const val BACK_DELAY_MS = 120L
private const val BACK_ALPHA = 0.9f
private val CORNER = 16.dp
private val ICON = 20.dp
private val INSET = 20.dp
