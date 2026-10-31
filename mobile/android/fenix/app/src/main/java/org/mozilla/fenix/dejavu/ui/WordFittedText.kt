/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.ui

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/**
 * [text] on up to [maxLines] lines that wrap only between words: when its longest word is wider than the room, or the
 * text does not fit in its lines, the whole text gets smaller, down to three quarters of [style]'s size, and is cut off
 * only after that. Plain wrapping would break such a word in the middle, like "Bağlantıy / ı kopyala". It is a plain
 * [Text] that learns its room while laid out, so parents can still ask it for its intrinsic size.
 */
@Composable
fun WordFittedText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = 2,
) {
    val measurer = rememberTextMeasurer()
    var room by remember { mutableIntStateOf(Constraints.Infinity) }
    val fitted = remember(text, style, room) {
        val words = text.split(' ', '\n').filter { it.isNotEmpty() }
        if (room == Constraints.Infinity || words.isEmpty()) return@remember style
        var scale = 1f
        while (scale > MIN_SCALE) {
            val sized = style.copy(fontSize = style.fontSize * scale)
            val widest = words.maxOf { word ->
                measurer.measure(word, sized, maxLines = 1, softWrap = false).size.width
            }
            if (widest <= room) break
            scale -= SCALE_STEP
        }
        style.copy(fontSize = style.fontSize * scale.coerceAtLeast(MIN_SCALE))
    }
    Text(
        text = text,
        style = fitted,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        autoSize = (style.fontSize * MIN_SCALE).takeIf { it < fitted.fontSize }?.let { smallest ->
            TextAutoSize.StepBased(minFontSize = smallest, maxFontSize = fitted.fontSize)
        },
        modifier = modifier.layout { measurable, constraints ->
            if (constraints.hasBoundedWidth && constraints.maxWidth != room) room = constraints.maxWidth
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        },
    )
}

private const val MIN_SCALE = 0.75f
private const val SCALE_STEP = 0.05f
