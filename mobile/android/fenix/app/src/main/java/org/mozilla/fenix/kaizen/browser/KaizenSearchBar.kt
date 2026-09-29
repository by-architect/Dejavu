/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.browser

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

private const val MOVE_DURATION_MS = 280

/**
 * Lays out an address bar that sits at the top of the screen. While searching, the bar moves down to sit right above
 * the keyboard, full width, with the suggestions above it; it slides there when search starts and back to the top
 * when search ends.
 *
 * @param enabled Whether the bar moves at all; when not, [topLayout] is always shown.
 * @param isSearching Whether the address bar is being edited.
 * @param toolbar The address bar.
 * @param suggestions The search suggestions, shown above the bar while searching.
 * @param topLayout The usual layout with the bar at the top.
 */
@Composable
fun KaizenSearchAboveKeyboard(
    enabled: Boolean,
    isSearching: Boolean,
    toolbar: @Composable () -> Unit,
    suggestions: @Composable (Modifier) -> Unit,
    topLayout: @Composable () -> Unit,
) {
    val progress = remember { Animatable(if (enabled && isSearching) 1f else 0f) }
    val fromBottom = remember(isSearching) { isSearching && KaizenSearchStart.isFromBottom() }
    LaunchedEffect(isSearching, enabled) {
        val target = if (enabled && isSearching) 1f else 0f
        if (target == 1f && fromBottom) {
            progress.snapTo(target)
        } else {
            progress.animateTo(target, tween(MOVE_DURATION_MS, easing = FastOutSlowInEasing))
        }
    }
    if (!enabled || (!isSearching && progress.value == 0f)) {
        topLayout()
        return
    }
    val shown = if (isSearching && fromBottom) 1f else progress.value

    var height by remember { mutableIntStateOf(0) }
    var barHeight by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxWidth().wrapContentHeight().onSizeChanged { height = it.height }) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (isSearching) {
                suggestions(Modifier.fillMaxSize().graphicsLayer { alpha = shown })
            }
        }
        Box(
            modifier = Modifier
                .onSizeChanged { barHeight = it.height }
                .graphicsLayer {
                    translationY = -(1f - shown) * (height - barHeight).coerceAtLeast(0)
                    alpha = if (height == 0) 0f else 1f
                },
        ) {
            toolbar()
        }
    }
}

/**
 * The search screen of the home screen, with the address bar at its bottom, right above the keyboard. When [fromTop],
 * the search started from an address bar at the top of the screen, so the screen slides down from there with the
 * address bar as its lower edge.
 */
@Composable
fun KaizenSearchOverlay(fromTop: Boolean, content: @Composable () -> Unit) {
    val progress = remember { Animatable(if (fromTop && !KaizenSearchStart.isFromBottom()) 0f else 1f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(MOVE_DURATION_MS, easing = FastOutSlowInEasing))
    }
    var height by remember { mutableIntStateOf(0) }
    val barHeight = with(LocalDensity.current) { SEARCH_BAR_HEIGHT.toPx() }
    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { height = it.height }
                .graphicsLayer {
                    translationY = -(1f - progress.value) * (height - barHeight).coerceAtLeast(0f)
                    alpha = if (height == 0) 0f else 1f
                }
                .background(MaterialTheme.colorScheme.surface),
        ) {
            content()
        }
    }
}

private val SEARCH_BAR_HEIGHT = 64.dp

/**
 * Remembers that a search was just started from the bottom of the screen, so the search screens opening for it appear
 * there at once instead of coming down from the address bar.
 */
object KaizenSearchStart {
    private const val VALID_FOR_MS = 1_500L
    private var startedAt = 0L

    fun fromBottom() {
        startedAt = SystemClock.elapsedRealtime()
    }

    /** Whether the search starting now was started from the bottom of the screen. */
    fun isFromBottom(): Boolean = startedAt != 0L && SystemClock.elapsedRealtime() - startedAt < VALID_FOR_MS
}
