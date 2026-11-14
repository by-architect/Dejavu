/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.ui.icons.R as iconsR

/**
 * Lets the user close what this row shows by swiping it sideways, with [onClose], when swiping tabs closed is turned on
 * in the settings. The row follows the finger over a close mark and goes away on that side once swiped far or fast
 * enough; otherwise it springs back. Only a quick sideways move counts: a long press is left to dragging rows, and a
 * move up or down to scrolling. A `null` [onClose] leaves the row as it is. With [stays], for a pinned tab, whose row
 * stays once its tab closed, the row comes back into its place after closing.
 *
 * Put it after the row's padding and before its own shape and background, which then move with the finger.
 */
internal fun Modifier.swipeToClose(onClose: (() -> Unit)?, stays: Boolean = false): Modifier =
    if (onClose == null) {
        this
    } else {
        composed {
            val close by rememberUpdatedState(onClose)
            val offset = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            val haptics = LocalHapticFeedback.current
            val cross = painterResource(iconsR.drawable.mozac_ic_cross_24)
            val back = MaterialTheme.colorScheme.error
            val mark = MaterialTheme.colorScheme.onError

            pointerInput(stays) {
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
                                    change.consume()
                                    decided = true
                                }
                            }
                        }
                        decided == true
                    } ?: false
                    if (!started) return@awaitEachGesture
                    scope.launch { offset.snapTo(moved.x) }

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
                        val value = dragged
                        scope.launch { offset.snapTo(value) }
                        val nowPast = abs(value) > size.width * CLOSE_SHARE
                        if (nowPast && !past) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        past = nowPast
                    }
                    val value = dragged
                    val velocity = if (ended) tracker.calculateVelocity().x else 0f
                    val fast = abs(velocity) > FLING_VELOCITY && sign(velocity) == sign(value)
                    scope.launch {
                        if (ended && value != 0f && (abs(value) > size.width * CLOSE_SHARE || fast)) {
                            offset.animateTo(sign(value) * size.width, tween(OUT_MS, easing = FastOutLinearInEasing))
                            close()
                            if (stays) {
                                delay(BACK_DELAY_MS)
                                offset.snapTo(0f)
                            }
                        } else {
                            offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                    }
                }
            }
                .drawBehind {
                    val value = offset.value
                    if (value == 0f) return@drawBehind
                    val shown = (abs(value) / (size.width * CLOSE_SHARE)).coerceIn(0f, 1f)
                    drawRoundRect(
                        color = back.copy(alpha = BACK_ALPHA * shown),
                        cornerRadius = CornerRadius(CORNER.toPx()),
                    )
                    val icon = ICON.toPx()
                    val inset = INSET.toPx()
                    val x = if (value > 0) inset else size.width - inset - icon
                    translate(left = x, top = (size.height - icon) / 2) {
                        with(cross) { draw(Size(icon, icon), alpha = shown, colorFilter = ColorFilter.tint(mark)) }
                    }
                }
                .offset { IntOffset(offset.value.roundToInt(), 0) }
        }
    }

// How far a row is swiped, as a share of its width, before letting go closes it, and how fast a fling must be.
private const val CLOSE_SHARE = 0.4f
private const val FLING_VELOCITY = 1500f

// How much more a move must go sideways than up or down to be a swipe rather than a scroll.
private const val SIDEWAYS_RATIO = 2f
private const val OUT_MS = 180
private const val BACK_DELAY_MS = 120L
private const val BACK_ALPHA = 0.9f
private val CORNER = 16.dp
private val ICON = 20.dp
private val INSET = 20.dp
