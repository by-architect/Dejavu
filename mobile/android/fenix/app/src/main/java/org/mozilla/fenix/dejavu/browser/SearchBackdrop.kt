/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How much the search screens blur what lies behind them, and the settings what they opened over. */
internal val SEARCH_BLUR_RADIUS = 24.dp

/** How much of the app's background color veils what lies behind the search screens and the settings. */
internal const val SEARCH_SCRIM_ALPHA = 0.65f
private const val BLUR_DURATION_MS = 280

/** Whether Android can blur what lies behind the search screens; older versions keep their plain background. */
internal val canBlurBehindSearch: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** The page under the search screens opened from the browser, which show it blurred behind them. */
internal object SearchBackdrop {
    private val _page = MutableStateFlow<PageShot?>(null)

    /** The page pictured for the search screen open now, until that search screen is gone. */
    val page: StateFlow<PageShot?> = _page

    /**
     * Pictures the page of the tab with [tabId] as it is shown now, for the search screen about to open over it, and
     * opens that search screen with [open] once the picture is taken, or soon after when it takes long. A search screen
     * asked for while a picture is being taken is not opened twice.
     */
    fun capturePage(tabId: String, open: () -> Unit) {
        if (!isSearchScreenSeeThrough) {
            open()
            return
        }
        PagePictures.capture(tabId) { shot ->
            if (shot != null) _page.value = shot
            open()
        }
    }

    /** Lets go of [shot] once the search screen showing it is gone. */
    fun clear(shot: PageShot) {
        _page.compareAndSet(shot, null)
    }
}

/**
 * What a search screen opened from the browser shows behind it while [active]: the page pictured when it opened,
 * blurring and dimming in, or the plain background when there is no picture of it. Once the search screen closes, the
 * page comes back into focus as it fades away.
 */
@Composable
internal fun SearchPageBackdrop(active: Boolean, modifier: Modifier = Modifier) {
    val shot by SearchBackdrop.page.collectAsState()
    val progress = remember { Animatable(0f) }
    // Whether the search screen opened, and the page it showed; the picture comes before the search screen opens.
    var opened by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<PageShot?>(null) }
    LaunchedEffect(active, shot) {
        when {
            active -> {
                opened = true
                shown = shot
                if (progress.value == 0f) awaitSteadyFrames()
                progress.animateTo(1f, tween(BLUR_DURATION_MS))
            }
            opened -> {
                progress.animateTo(0f, tween(BLUR_DURATION_MS))
                opened = false
                shown?.let(SearchBackdrop::clear)
                shown = null
            }
        }
    }
    val page = if (active) shot else shown
    val showing by rememberUpdatedState(page)
    DisposableEffect(Unit) {
        onDispose { showing?.let(SearchBackdrop::clear) }
    }
    if (!active && !opened) return

    val surface = MaterialTheme.colorScheme.surface
    if (page == null) {
        if (active) Canvas(modifier = modifier) { drawRect(surface) }
        return
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .onGloballyPositioned { origin = it.positionInWindow() }
            .graphicsLayer { alpha = if (active) 1f else progress.value },
    ) {
        Canvas(modifier = Modifier.matchParentSize().graphicsLayer { blurBy(progress.value) }) {
            drawRect(page.edgeColor)
            drawImage(page.bitmap, topLeft = page.offset - origin)
        }
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRect(surface, alpha = SEARCH_SCRIM_ALPHA * progress.value)
        }
    }
}

/** Blurs and dims what this draws while a search screen is open over it ([active]), where Android can blur. */
@Composable
internal fun Modifier.blurBehindSearch(active: Boolean): Modifier {
    if (!canBlurBehindSearch) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            awaitSteadyFrames()
            progress.animateTo(1f, tween(BLUR_DURATION_MS))
        } else {
            progress.animateTo(0f, tween(BLUR_DURATION_MS))
        }
    }
    val surface = MaterialTheme.colorScheme.surface
    return this
        .drawWithContent {
            drawContent()
            if (progress.value > 0f) drawRect(surface, alpha = SEARCH_SCRIM_ALPHA * progress.value)
        }
        .graphicsLayer { blurBy(progress.value) }
}

/** Whether the search screens are see-through, showing what lies behind them blurred. */
val isSearchScreenSeeThrough: Boolean
    get() = DejavuToolbar.enabled && canBlurBehindSearch

/** The background of what a search screen shows: see-through where what lies behind it is blurred, else [opaque]. */
@Composable
fun searchScreenBackground(opaque: Color = MaterialTheme.colorScheme.surface): Color =
    if (isSearchScreenSeeThrough) Color.Transparent else opaque

private fun GraphicsLayerScope.blurBy(progress: Float) {
    val radius = SEARCH_BLUR_RADIUS.toPx() * progress
    renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Clamp) else null
    clip = true
}
