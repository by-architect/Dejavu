/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.search.SearchEngine
import org.mozilla.fenix.components.components
import org.mozilla.fenix.ext.WIDE_SCREEN_WIDTH_DP

private const val MOVE_DURATION_MS = 280

/**
 * Lays out an address bar that sits at the top of the screen. While searching, the bar moves down to sit right above
 * the keyboard, full width, with the suggestions above it; it slides there when search starts and back to the top
 * when search ends. An address bar below pages leaves the top empty, so the search bar shows and goes at once there.
 *
 * @param enabled Whether the bar moves at all; when not, [topLayout] is always shown.
 * @param isSearching Whether the address bar is being edited.
 * @param fromTop Whether the address bar sits at the top, for the search bar to slide down from and back up to.
 * @param toolbar The address bar.
 * @param suggestions The search suggestions, shown above the bar while searching.
 * @param topLayout The usual layout with the bar at the top.
 */
@Composable
fun DejavuSearchAboveKeyboard(
    enabled: Boolean,
    isSearching: Boolean,
    fromTop: Boolean,
    toolbar: @Composable () -> Unit,
    suggestions: @Composable (Modifier) -> Unit,
    topLayout: @Composable () -> Unit,
) {
    val progress = remember { Animatable(if (enabled && isSearching) 1f else 0f) }
    val fromBottom = remember(isSearching) { isSearching && DejavuSearchStart.isFromBottom() }
    LaunchedEffect(isSearching, enabled) {
        val target = if (enabled && isSearching) 1f else 0f
        if ((target == 1f && fromBottom) || !fromTop) {
            progress.snapTo(target)
        } else {
            if (target == 1f && progress.value == 0f) awaitSteadyFrames()
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
fun DejavuSearchOverlay(fromTop: Boolean, content: @Composable () -> Unit) {
    // The floating search on tablets shows where it is at once, it fades and grows in by itself.
    val slides = fromTop && !DejavuSearchStart.isFromBottom() && !isFloatingSearch()
    val progress = remember { Animatable(if (slides) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value == 1f) return@LaunchedEffect
        awaitSteadyFrames()
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
                .background(searchScreenBackground()),
        ) {
            content()
        }
    }
}

/** Whether search opens as a pill floating in the middle of the screen instead of above the keyboard: on tablets. */
@Composable
fun isFloatingSearch(): Boolean =
    DejavuToolbar.enabled && LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_WIDTH_DP

/** [isFloatingSearch] outside of a composition. */
fun isFloatingSearch(context: Context): Boolean =
    DejavuToolbar.enabled && context.resources.configuration.screenWidthDp >= WIDE_SCREEN_WIDTH_DP

/** Dims the page the floating search shows over, which stays in sight around it. */
@Composable
fun FloatingSearchScrim(modifier: Modifier) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(FLOAT_IN_MS)) }
    val scrim = MaterialTheme.colorScheme.scrim
    Box(modifier = modifier.graphicsLayer { alpha = shown.value }.background(scrim.copy(alpha = FLOATING_SCRIM_ALPHA)))
}

/**
 * The search on wide screens: the address bar as a pill floating in the middle of the screen, with where the search
 * opens above it, in a strip as wide as the pill that scrolls sideways, and the suggestions in a rounded card below
 * it, over what lies behind, as in Zen. A tap beside them ends the search. "@" and some letters of an engine's name
 * list the engines that match in place of the suggestions, to pick with the keyboard: the arrows or Tab move through
 * them, Enter or a space picks one, which empties the field for the search.
 *
 * @param query What the address bar holds.
 * @param onQueryChange Replaces what the address bar holds.
 * @param onDismiss Ends the search, for a tap beside the pill and the suggestions.
 * @param chips Where the search opens and which engine it searches with.
 * @param toolbar The address bar, drawn see-through so the pill shows around its field.
 * @param suggestions The search suggestions.
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
fun DejavuFloatingSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    chips: @Composable () -> Unit,
    toolbar: @Composable () -> Unit,
    suggestions: @Composable (Modifier) -> Unit,
) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(FLOAT_IN_MS, easing = FastOutSlowInEasing)) }
    val colors = MaterialTheme.colorScheme
    val seeThrough = isSearchScreenSeeThrough
    val cardColor = colors.surfaceContainerHigh.copy(alpha = if (seeThrough) FLOATING_CARD_ALPHA else 1f)
    val stripColor = colors.surfaceContainer.copy(alpha = if (seeThrough) FLOATING_CARD_ALPHA else 1f)

    val appStore = components.appStore
    val engines = rememberAtEngines()
    val matches = remember(query, engines) { enginesMatching(query, engines) }
    var highlighted by remember(matches) { mutableIntStateOf(0) }
    fun pick(engine: SearchEngine) {
        selectSearchEngine(appStore, engine)
        onQueryChange("")
    }
    // A space after "@name" picks the best match, as Enter does.
    LaunchedEffect(query, engines) {
        if (query.startsWith('@') && query.endsWith(' ') && query.count { it == ' ' } == 1) {
            enginesMatching(query.trimEnd(), engines)?.firstOrNull()?.let { pick(it.engine) }
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
    ) {
        // With little height, as with a keyboard up on a tablet held sideways, the pill moves up so the card below
        // it keeps room for more rows.
        val topRoom = if (maxHeight < FLOATING_TIGHT_HEIGHT) {
            FLOATING_MIN_TOP
        } else {
            (maxHeight * FLOATING_TOP_FRACTION).coerceIn(FLOATING_MIN_TOP, FLOATING_MAX_TOP)
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = FLOATING_MAX_WIDTH)
                .fillMaxWidth()
                .padding(start = FLOATING_SIDE_ROOM, end = FLOATING_SIDE_ROOM, top = topRoom, bottom = FLOATING_GAP)
                .graphicsLayer {
                    alpha = shown.value
                    val scale = FLOATING_START_SCALE + (1f - FLOATING_START_SCALE) * shown.value
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                // Seen before the address bar's field, so the keys move through the engines instead of its text.
                .onPreviewKeyEvent { event ->
                    val listed = matches ?: return@onPreviewKeyEvent false
                    handleEngineKey(
                        event = event,
                        count = listed.size,
                        highlighted = highlighted.coerceAtMost(listed.lastIndex),
                        onHighlight = { highlighted = it },
                        onPick = { pick(listed[highlighted.coerceAtMost(listed.lastIndex)].engine) },
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FLOATING_CARD_RADIUS))
                    .background(stripColor),
            ) {
                chips()
            }
            Spacer(modifier = Modifier.height(FLOATING_GAP))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(FLOATING_ELEVATION, CircleShape)
                    .clip(CircleShape)
                    .background(cardColor),
            ) {
                toolbar()
            }
            // "@" and letters that match no engine have nothing to suggest, so no empty card shows below the pill.
            val noEngine = matches == null && query.startsWith('@') && !query.contains(' ')
            if (noEngine) return@Column
            Spacer(modifier = Modifier.height(FLOATING_GAP))
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = FLOATING_MAX_SUGGESTIONS_HEIGHT)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FLOATING_CARD_RADIUS))
                    .background(cardColor),
            ) {
                if (matches != null) {
                    EngineList(
                        matches = matches,
                        highlighted = highlighted.coerceAtMost(matches.lastIndex),
                        onPick = ::pick,
                        modifier = Modifier.fillMaxWidth().padding(bottom = FLOATING_GAP),
                    )
                } else {
                    suggestions(Modifier.fillMaxSize())
                }
            }
        }
    }
}

private const val FLOAT_IN_MS = 180
private const val FLOATING_SCRIM_ALPHA = 0.32f
private const val FLOATING_START_SCALE = 0.96f
private const val FLOATING_CARD_ALPHA = 0.92f
private const val FLOATING_TOP_FRACTION = 0.18f
private val FLOATING_MIN_TOP = 16.dp
private val FLOATING_MAX_TOP = 160.dp
private val FLOATING_TIGHT_HEIGHT = 560.dp
private val FLOATING_MAX_WIDTH = 720.dp
private val FLOATING_SIDE_ROOM = 24.dp
private val FLOATING_GAP = 8.dp
private val FLOATING_ELEVATION = 12.dp
private val FLOATING_CARD_RADIUS = 24.dp
private val FLOATING_MAX_SUGGESTIONS_HEIGHT = 480.dp

private val SEARCH_BAR_HEIGHT = 64.dp

private const val STEADY_FRAMES = 3
private const val STEADY_FRAME_NANOS = 34_000_000L
private const val MAX_SETTLE_NANOS = 500_000_000L

/**
 * Waits until the screen is drawn at a steady pace again, for at most [maxNanos], so that an animation started next is
 * not skipped through while a screen that just opened is still setting itself up.
 */
internal suspend fun awaitSteadyFrames(maxNanos: Long = MAX_SETTLE_NANOS) {
    val first = withFrameNanos { it }
    var last = first
    var steady = 0
    while (steady < STEADY_FRAMES && last - first < maxNanos) {
        val now = withFrameNanos { it }
        steady = if (now - last <= STEADY_FRAME_NANOS) steady + 1 else 0
        last = now
    }
}

/**
 * Remembers that a search was just started from the bottom of the screen, so the search screens opening for it appear
 * there at once instead of coming down from the address bar.
 */
object DejavuSearchStart {
    private const val VALID_FOR_MS = 1_500L
    private var startedAt = 0L

    fun fromBottom() {
        startedAt = SystemClock.elapsedRealtime()
    }

    /** Whether the search starting now was started from the bottom of the screen. */
    fun isFromBottom(): Boolean = startedAt != 0L && SystemClock.elapsedRealtime() - startedAt < VALID_FOR_MS
}

/**
 * A search started with New tab over a shown page. It opens over the page, as tapping the address bar does, and what
 * is searched or typed opens in a new tab instead of in that page's tab.
 */
object NewTabSearch {
    @Volatile
    private var tabId: String? = null

    /** Starts a search over the tab with [tabId] that opens in a new tab. */
    fun start(tabId: String) {
        this.tabId = tabId
    }

    /** Lets the next search started from a page open in that page, as usual. */
    fun clear() {
        tabId = null
    }

    /** Whether the search started from the tab with [sourceTabId] opens in a new tab rather than in that tab. */
    fun isFor(sourceTabId: String?): Boolean = sourceTabId != null && sourceTabId == tabId
}
