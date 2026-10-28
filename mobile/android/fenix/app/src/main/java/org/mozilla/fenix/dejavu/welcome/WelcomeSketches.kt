/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.welcome

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import mozilla.components.compose.base.theme.Theme
import org.mozilla.fenix.dejavu.browser.BarsLayout
import org.mozilla.fenix.dejavu.ui.DejavuColors

/**
 * The colors of a drawing of a phone: its [screen], the [ink] of what is on it, the [bar] of the actions bar and the
 * workspace bar, the [accent] of the bars a choice is about, and the [frame] around it.
 */
@Immutable
internal class SketchColors(
    val screen: Color,
    val ink: Color,
    val bar: Color,
    val accent: Color,
    val frame: Color,
) {
    /** These colors with the accent [emphasis] of the way to full, from the ink's gray. */
    fun withEmphasis(emphasis: Float) =
        SketchColors(screen, ink, bar, lerp(ink.copy(alpha = MUTED_ALPHA), accent, emphasis), frame)
}

/** The colors of the phone drawings in [theme]'s colors, or the ones shown; the frame always stands out from these. */
@Composable
internal fun sketchColors(theme: Theme? = null): SketchColors {
    val scheme = theme?.let { DejavuColors.colorScheme(it) } ?: MaterialTheme.colorScheme
    return SketchColors(
        screen = scheme.surface,
        ink = scheme.onSurface,
        bar = scheme.surfaceContainerHighest,
        accent = scheme.primary,
        frame = MaterialTheme.colorScheme.onSurface.copy(alpha = FRAME_ALPHA),
    )
}

/**
 * A small phone of [colors] whose screen [draws] itself. Its accent shows in full while it is [highlighted], the
 * choice picked, and grays when it is not.
 */
@Composable
internal fun PhoneSketch(
    colors: SketchColors,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    draws: DrawScope.(SketchColors) -> Unit,
) {
    val emphasis by animateFloatAsState(if (highlighted) 1f else 0f, tween(EMPHASIS_MS), label = "sketchEmphasis")
    Canvas(modifier = modifier.aspectRatio(PHONE_RATIO)) {
        val shown = colors.withEmphasis(emphasis)
        phone(shown.frame) {
            drawRect(shown.screen)
            draws(shown)
        }
    }
}

/** The home screen half in [light] colors, half in [dark] ones, for following the phone's theme. */
@Composable
internal fun SplitPhoneSketch(
    light: SketchColors,
    dark: SketchColors,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val emphasis by animateFloatAsState(if (highlighted) 1f else 0f, tween(EMPHASIS_MS), label = "sketchEmphasis")
    Canvas(modifier = modifier.aspectRatio(PHONE_RATIO)) {
        phone(light.frame) {
            clipRect(right = size.width / 2) {
                drawRect(light.screen)
                drawHome(searchAtBottom = false, colors = light.withEmphasis(emphasis))
            }
            clipRect(left = size.width / 2) {
                drawRect(dark.screen)
                drawHome(searchAtBottom = false, colors = dark.withEmphasis(emphasis))
            }
        }
    }
}

/** Draws [screen] clipped to the phone's rounded shape, and the phone's [frame] around it. */
private fun DrawScope.phone(frame: Color, screen: DrawScope.() -> Unit) {
    val corner = CornerRadius(size.width * CORNER_SHARE)
    val shape = Path().apply { addRoundRect(RoundRect(size.toRect(), corner)) }
    clipPath(shape) { screen() }
    drawRoundRect(frame, cornerRadius = corner, style = Stroke(width = 1.dp.toPx()))
}

/** A web page with the bars of [layout] around it. */
internal fun DrawScope.drawPage(layout: BarsLayout, colors: SketchColors) {
    val u = size.width / UNITS
    statusBar(colors)
    if (!layout.addressBarAtBottom) addressBar(colors, top = 3.2f * u)
    val contentTop = if (layout.addressBarAtBottom) 3.4f * u else 6.6f * u
    val contentBottom = if (layout == BarsLayout.TOP) size.height - u else size.height - 4.8f * u
    pageLines(colors, contentTop, contentBottom)
    when (layout) {
        BarsLayout.ACTIONS_BAR -> actionsBar(colors, top = size.height - 4f * u)
        BarsLayout.BOTTOM -> addressBar(colors, top = size.height - 4f * u)
        BarsLayout.TOP -> Unit
    }
}

/**
 * The home screen: the search bar between the account and settings buttons, and the bar of the workspaces and the
 * page buttons, the search bar on top unless [searchAtBottom], with tabs between them.
 */
internal fun DrawScope.drawHome(searchAtBottom: Boolean, colors: SketchColors) {
    val u = size.width / UNITS
    statusBar(colors)
    val top = 3.2f * u
    val bottom = size.height - 4f * u
    searchLine(colors, top = if (searchAtBottom) bottom else top)
    workspaceBar(colors, top = if (searchAtBottom) top else bottom)
    tabRows(colors, from = top + 3.8f * u, to = bottom - 0.8f * u)
}

private fun DrawScope.statusBar(colors: SketchColors) {
    val u = size.width / UNITS
    val ink = colors.ink.copy(alpha = STATUS_ALPHA)
    drawRoundRect(ink, Offset(2f * u, 1.2f * u), Size(3.5f * u, 0.9f * u), CornerRadius(0.45f * u))
    drawCircle(ink, radius = 0.5f * u, center = Offset(16.4f * u, 1.65f * u))
    drawCircle(ink, radius = 0.5f * u, center = Offset(18f * u, 1.65f * u))
}

private fun DrawScope.addressBar(colors: SketchColors, top: Float) {
    val u = size.width / UNITS
    val corner = CornerRadius(BAR_HEIGHT * u / 2)
    drawRoundRect(colors.accent.copy(alpha = FILL_ALPHA), Offset(1.5f * u, top), Size(17f * u, BAR_HEIGHT * u), corner)
    drawRoundRect(
        colors.accent,
        Offset(1.5f * u, top),
        Size(17f * u, BAR_HEIGHT * u),
        corner,
        style = Stroke(width = 0.3f * u),
    )
    drawCircle(colors.accent, radius = 0.55f * u, center = Offset(3.3f * u, top + BAR_HEIGHT * u / 2))
    drawRoundRect(
        colors.ink.copy(alpha = TEXT_ALPHA),
        Offset(4.7f * u, top + 0.95f * u),
        Size(7f * u, 0.7f * u),
        CornerRadius(0.35f * u),
    )
}

private fun DrawScope.actionsBar(colors: SketchColors, top: Float) {
    val u = size.width / UNITS
    drawRoundRect(colors.bar, Offset(1.5f * u, top), Size(17f * u, BAR_HEIGHT * u), CornerRadius(BAR_HEIGHT * u / 2))
    repeat(ACTIONS) { index ->
        drawCircle(
            colors.accent,
            radius = 0.55f * u,
            center = Offset((3.6f + index * 4.25f) * u, top + BAR_HEIGHT * u / 2),
        )
    }
}

/** A picture, then lines of text, from [top] down to [bottom]. */
private fun DrawScope.pageLines(colors: SketchColors, top: Float, bottom: Float) {
    val u = size.width / UNITS
    val picture = colors.ink.copy(alpha = PICTURE_ALPHA)
    drawRoundRect(picture, Offset(1.5f * u, top), Size(17f * u, 6f * u), CornerRadius(0.8f * u))
    var y = top + 7.2f * u
    for (width in LINE_WIDTHS) {
        if (y + 0.9f * u > bottom) break
        val line = colors.ink.copy(alpha = LINE_ALPHA)
        drawRoundRect(line, Offset(1.5f * u, y), Size(width * u, 0.9f * u), CornerRadius(0.45f * u))
        y += 1.9f * u
    }
}

private fun DrawScope.searchLine(colors: SketchColors, top: Float) {
    val u = size.width / UNITS
    val button = colors.ink.copy(alpha = STATUS_ALPHA)
    drawCircle(button, radius = 1.1f * u, center = Offset(2.6f * u, top + BAR_HEIGHT * u / 2))
    drawCircle(button, radius = 1.1f * u, center = Offset(17.4f * u, top + BAR_HEIGHT * u / 2))
    val corner = CornerRadius(BAR_HEIGHT * u / 2)
    val fill = colors.accent.copy(alpha = FILL_ALPHA)
    drawRoundRect(fill, Offset(4.6f * u, top), Size(10.8f * u, BAR_HEIGHT * u), corner)
    drawRoundRect(
        colors.accent,
        Offset(4.6f * u, top),
        Size(10.8f * u, BAR_HEIGHT * u),
        corner,
        style = Stroke(width = 0.3f * u),
    )
}

private fun DrawScope.workspaceBar(colors: SketchColors, top: Float) {
    val u = size.width / UNITS
    drawRoundRect(colors.bar, Offset(1.5f * u, top), Size(17f * u, BAR_HEIGHT * u), CornerRadius(BAR_HEIGHT * u / 2))
    repeat(WORKSPACE_BUTTONS) { index ->
        drawRoundRect(
            if (index == WORKSPACE_BUTTONS / 2) colors.accent else colors.ink.copy(alpha = STATUS_ALPHA),
            Offset((2.9f + index * 3.15f) * u, top + 0.75f * u),
            Size(1.1f * u, 1.1f * u),
            CornerRadius(0.3f * u),
        )
    }
}

/** Rows of tabs, each with its site's icon and title, from [from] down to [to]. */
private fun DrawScope.tabRows(colors: SketchColors, from: Float, to: Float) {
    val u = size.width / UNITS
    var y = from
    var index = 0
    while (y + 2.4f * u <= to) {
        val row = colors.ink.copy(alpha = ROW_ALPHA)
        drawRoundRect(row, Offset(1.5f * u, y), Size(17f * u, 2.4f * u), CornerRadius(0.8f * u))
        drawCircle(colors.accent, radius = 0.55f * u, center = Offset(3f * u, y + 1.2f * u))
        drawRoundRect(
            colors.ink.copy(alpha = TEXT_ALPHA),
            Offset(4.4f * u, y + 0.85f * u),
            Size(TITLE_WIDTHS[index % TITLE_WIDTHS.size] * u, 0.7f * u),
            CornerRadius(0.35f * u),
        )
        y += 3f * u
        index++
    }
}

/** A drawing is this many units wide, and twice as tall. */
private const val UNITS = 20f
private const val PHONE_RATIO = 0.5f
private const val CORNER_SHARE = 0.16f
private const val BAR_HEIGHT = 2.6f
private const val ACTIONS = 4
private const val WORKSPACE_BUTTONS = 5
private const val EMPHASIS_MS = 250
private const val MUTED_ALPHA = 0.35f
private const val FRAME_ALPHA = 0.35f
private const val STATUS_ALPHA = 0.4f
private const val FILL_ALPHA = 0.22f
private const val TEXT_ALPHA = 0.32f
private const val PICTURE_ALPHA = 0.1f
private const val LINE_ALPHA = 0.16f
private const val ROW_ALPHA = 0.07f
private val LINE_WIDTHS = listOf(15f, 17f, 12f, 16f, 9f, 17f, 14f, 11f, 16f, 13f)
private val TITLE_WIDTHS = listOf(9f, 11f, 7f, 10f, 8f)
