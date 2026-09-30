/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import android.graphics.Bitmap
import android.icu.text.BreakIterator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import kotlin.random.Random
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme

/** Gradients offered when editing a workspace, each a list of ARGB colors. */
internal val themePresets: List<List<Int>> = listOf(
    listOf(0xFFFF7E5F, 0xFFFEB47B),
    listOf(0xFFF857A6, 0xFFFF5858),
    listOf(0xFFF12711, 0xFFF5AF19),
    listOf(0xFFFFB88C, 0xFFDE6262),
    listOf(0xFFA18CD1, 0xFFFBC2EB),
    listOf(0xFF8E2DE2, 0xFF4A00E0),
    listOf(0xFF2193B0, 0xFF6DD5ED),
    listOf(0xFF89F7FE, 0xFF66A6FF),
    listOf(0xFF11998E, 0xFF38EF7D),
    listOf(0xFFA8E6CF, 0xFFDCEDC1),
    listOf(0xFF00C9FF, 0xFF92FE9D, 0xFFFC466B),
    listOf(0xFF757F9A, 0xFFD7DDE8),
    listOf(0xFF141E30, 0xFF243B55),
).map { colors -> colors.map { it.toInt() } }

/** Emojis offered as workspace icons, written as code points; any other one can be typed. */
internal val iconSuggestions: List<String> = listOf(
    intArrayOf(0x1F3E0), intArrayOf(0x1F4BC), intArrayOf(0x1F393), intArrayOf(0x1F4BB), intArrayOf(0x1F3AE),
    intArrayOf(0x1F3B5), intArrayOf(0x1F3AC), intArrayOf(0x1F4DA), intArrayOf(0x1F6D2), intArrayOf(0x2708, 0xFE0F),
    intArrayOf(0x1F354), intArrayOf(0x2615), intArrayOf(0x2764, 0xFE0F), intArrayOf(0x2B50), intArrayOf(0x1F525),
    intArrayOf(0x1F331), intArrayOf(0x1F319), intArrayOf(0x2600, 0xFE0F), intArrayOf(0x1F3A8), intArrayOf(0x1F4F7),
    intArrayOf(0x1F3CB, 0xFE0F), intArrayOf(0x26BD), intArrayOf(0x1F43E), intArrayOf(0x1F4A1), intArrayOf(0x1F52C),
    intArrayOf(0x1F4F0), intArrayOf(0x1F4AC), intArrayOf(0x1F4E7), intArrayOf(0x1F5C2, 0xFE0F), intArrayOf(0x1F9EA),
    intArrayOf(0x1F6E0, 0xFE0F), intArrayOf(0x1F4B0), intArrayOf(0x1F9D8), intArrayOf(0x1F389), intArrayOf(0x1F30D),
    intArrayOf(0x1F680), intArrayOf(0x1F512), intArrayOf(0x1F4CC), intArrayOf(0x1F9E9), intArrayOf(0x1F3A7),
).map { codePoints -> buildString { codePoints.forEach { appendCodePoint(it) } } }

private const val GRAIN_SIZE = 128
private const val GRAIN_MAX_ALPHA = 90
private const val GRAIN_STRENGTH = 0.5f
private const val GRADIENT_STOPS = 3

/** The last character typed into an icon field, emoji sequences included, or `null` when the field is empty. */
internal fun lastGrapheme(text: String): String? {
    if (text.isBlank()) return null
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val end = iterator.last()
    val start = iterator.previous()
    return text.substring(start, end).trim().ifEmpty { null }
}

/** A brush of fine noise, tiled over the background to give a theme its grain like Zen's texture. */
@Composable
internal fun rememberGrainBrush(): ShaderBrush = remember {
    val random = Random(GRAIN_SIZE)
    val pixels = IntArray(GRAIN_SIZE * GRAIN_SIZE) {
        val shade = if (random.nextBoolean()) 255 else 0
        android.graphics.Color.argb(random.nextInt(GRAIN_MAX_ALPHA), shade, shade, shade)
    }
    val bitmap = Bitmap.createBitmap(pixels, GRAIN_SIZE, GRAIN_SIZE, Bitmap.Config.ARGB_8888)
    ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/** A diagonal gradient of [colors] at [opacity]. */
internal fun themeBrush(colors: List<Color>, opacity: Float): Brush =
    if (colors.size == 1) {
        Brush.linearGradient(listOf(colors[0].copy(alpha = opacity), colors[0].copy(alpha = opacity)))
    } else {
        Brush.linearGradient(colors.map { it.copy(alpha = opacity) })
    }

/**
 * Draws the theme of the workspace being shown, [fraction] of the way to the theme of the next one while swiping.
 * A workspace without a theme fades the gradient out.
 */
internal fun DrawScope.drawWorkspaceTheme(
    theme: WorkspaceTheme?,
    next: WorkspaceTheme?,
    fraction: Float,
    grain: ShaderBrush,
) {
    val from = theme ?: next?.copy(opacity = 0f, texture = 0f) ?: return
    val to = next ?: theme?.copy(opacity = 0f, texture = 0f) ?: return
    val colors = (0 until GRADIENT_STOPS).map { stop ->
        lerp(from.colorAt(stop), to.colorAt(stop), fraction)
    }
    val opacity = from.opacity + (to.opacity - from.opacity) * fraction
    val texture = from.texture + (to.texture - from.texture) * fraction
    drawRect(
        brush = Brush.linearGradient(
            colors = colors.map { it.copy(alpha = opacity) },
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
    )
    if (texture > 0f) drawRect(brush = grain, alpha = texture * GRAIN_STRENGTH)
}

private fun WorkspaceTheme.colorAt(stop: Int): Color = Color(colors[stop.coerceAtMost(colors.size - 1)])
