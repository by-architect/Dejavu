/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Colors of Kaizen's translucent "glass" surfaces, which let the background show through.
 *
 * @property top Fill at the top edge, a little brighter to catch the light.
 * @property bottom Fill at the bottom edge.
 * @property border Hairline around the surface.
 */
@Immutable
data class Glass(val top: Color, val bottom: Color, val border: Color)

private val DarkGlass = Glass(
    top = Color.White.copy(alpha = 0.16f),
    bottom = Color.White.copy(alpha = 0.08f),
    border = Color.White.copy(alpha = 0.14f),
)

private val LightGlass = Glass(
    top = Color.White.copy(alpha = 0.72f),
    bottom = Color.White.copy(alpha = 0.5f),
    border = Color.White.copy(alpha = 0.8f),
)

/** The glass colors for the current theme. */
val glass: Glass
    @Composable @ReadOnlyComposable
    get() = if (MaterialTheme.colorScheme.surface.luminance() < HALF) DarkGlass else LightGlass

/** Draws a [Glass] surface in [shape] behind the content, and clips the content to it. */
fun Modifier.glass(glass: Glass, shape: Shape): Modifier =
    clip(shape)
        .background(Brush.verticalGradient(listOf(glass.top, glass.bottom)))
        .border(1.dp, glass.border, shape)

private const val HALF = 0.5f
