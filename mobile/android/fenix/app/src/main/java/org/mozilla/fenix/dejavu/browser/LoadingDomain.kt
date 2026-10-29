/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SWEEP_MS = 1_400

// The band steps on about 30 times a second, which looks the same as every frame for a fraction of the work, and comes
// to rest after a while, so a page that keeps loading does not keep the phone busy.
private const val STEP_MS = 33L
private const val RUN_MS = 8_000L
private const val FADE_IN_MS = 220
private const val FADE_OUT_MS = 450
private const val SLANT = 0.6f
private const val HALF = 0.5f
private val MinBand = 72.dp

// Dejavu's gold leading round the colors around it and back, bright over the dark themes and deeper over the light one.
private val DarkColors = listOf(
    Color(0xFFFFC83D), Color(0xFFFF8A5B), Color(0xFFFF5FA2), Color(0xFFA685FF), Color(0xFF4FC3F7),
    Color(0xFF5BE0A8), Color(0xFFFFC83D),
)
private val LightColors = listOf(
    Color(0xFFB07A00), Color(0xFFD9502F), Color(0xFFC92A78), Color(0xFF6B47DC), Color(0xFF1C7FC4),
    Color(0xFF138A5E), Color(0xFFB07A00),
)

/**
 * While a page is [loading], colors run through the letters this draws, in place of a loading bar: a band of Dejavu's
 * gold and the colors around it sweeps along, resting after a few seconds, and the letters fade back to their own color
 * once the page is in. With [range], only the letters between the two ends it gives, in pixels from the start, take
 * the colors.
 */
@Composable
internal fun Modifier.loadingColors(
    loading: Boolean,
    range: (() -> ClosedFloatingPointRange<Float>?)? = null,
): Modifier {
    val shown = remember { Animatable(if (loading) 1f else 0f) }
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(loading) {
        if (!loading) {
            // The colors keep running while they fade.
            val runOn = launch {
                val further = sweep.value + FADE_OUT_MS.toFloat() / SWEEP_MS
                sweep.animateTo(further, tween(FADE_OUT_MS, easing = LinearEasing))
            }
            shown.animateTo(0f, tween(FADE_OUT_MS))
            runOn.cancel()
            sweep.snapTo(0f)
            return@LaunchedEffect
        }
        launch { shown.animateTo(1f, tween(FADE_IN_MS)) }
        repeat((RUN_MS / STEP_MS).toInt()) {
            delay(STEP_MS)
            sweep.snapTo(sweep.value + STEP_MS.toFloat() / SWEEP_MS)
        }
    }
    val colors = if (MaterialTheme.colorScheme.surface.luminance() < HALF) DarkColors else LightColors
    return this
        .graphicsLayer {
            // Offscreen, the colors only take the place of the letters, not of what lies behind them.
            compositingStrategy = if (shown.value > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        }
        .drawWithCache {
            val band = max(size.width, MinBand.toPx())
            onDrawWithContent {
                drawContent()
                val alpha = shown.value
                if (alpha <= 0f) return@onDrawWithContent
                val start = sweep.value % 1f * band
                val brush = Brush.linearGradient(
                    colors = colors,
                    start = Offset(start - band, 0f),
                    end = Offset(start, size.height * SLANT),
                    tileMode = TileMode.Repeated,
                )
                val letters = if (range == null) 0f..size.width else range() ?: return@onDrawWithContent
                clipRect(left = letters.start, right = letters.endInclusive) {
                    drawRect(brush, alpha = alpha, blendMode = BlendMode.SrcAtop)
                }
            }
        }
}

/** Where [part] of [text] shows in [this] layout, in pixels from the start, as far as it shows; `null` if not. */
internal fun TextLayoutResult.rangeOf(text: String, part: String): ClosedFloatingPointRange<Float>? {
    val from = text.indexOf(part, ignoreCase = true).takeIf { it >= 0 } ?: return null
    val to = from + part.length
    if (from >= layoutInput.text.length) return null
    val first = getHorizontalPosition(from, usePrimaryDirection = true)
    val last = getHorizontalPosition(to.coerceAtMost(layoutInput.text.length), usePrimaryDirection = true)
    val left = min(first, last).coerceIn(0f, size.width.toFloat())
    val right = max(first, last).coerceIn(0f, size.width.toFloat())
    return (left..right).takeIf { right > left }
}
