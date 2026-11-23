/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mozilla.fenix.R
import org.mozilla.fenix.browser.tabstrip.TabSidebarLayout
import org.mozilla.fenix.dejavu.browser.shownMenuTheme
import org.mozilla.fenix.dejavu.browser.workspaceTheme
import org.mozilla.fenix.dejavu.settings.BarButton
import org.mozilla.fenix.dejavu.settings.SidebarLook

/**
 * Where the docked tab sidebar's "More" button is on the screen, which the compact menu opened from it grows out of.
 * The button reports it while it is shown, so the menu keeps up with a sidebar that moves, as when the screen turns.
 */
object SidebarMenuAnchor {
    /** The button's bounds in screen pixels, or `null` while no sidebar shows it. */
    var bounds: Rect? by mutableStateOf(null)
}

/** The items of the "More" menu that the sidebar's page controls do too. */
private val ControlItems = listOf(MoreMenuItem.BACK, MoreMenuItem.FORWARD, MoreMenuItem.REFRESH)

/** The buttons of the sidebar's bottom bar, with the items of the "More" menu that do the same. */
private val BottomBarItems = mapOf(
    BarButton.DOWNLOADS to MoreMenuItem.DOWNLOADS,
    BarButton.EXTENSIONS to MoreMenuItem.EXTENSIONS,
    BarButton.BOOKMARKS to MoreMenuItem.BOOKMARKS,
    BarButton.HISTORY to MoreMenuItem.HISTORY,
)

/**
 * The items of the "More" menu that the sidebar with [look] shows as buttons of its own: its back, forward and reload
 * buttons, and the buttons of its bottom bar that the user did not hide. A hidden button is no duplicate, so its item
 * stays in the menu.
 */
fun sidebarShownItems(look: SidebarLook): Set<MoreMenuItem> = buildSet {
    if (look.showPageControls) addAll(ControlItems)
    if (look.showBottomBar) {
        BottomBarItems.forEach { (button, item) -> if (button !in look.hiddenBarButtons) add(item) }
    }
}

/**
 * The rows of the compact menu: [rows] in their order without the built-in entries in [shown], the rest regrouped
 * [columns] to a row. The extensions item keeps the row of its own that opens the extensions drawer, when it stays.
 */
fun compactRows(rows: List<List<MoreMenuEntry>>, shown: Set<MoreMenuItem>, columns: Int): List<List<MoreMenuEntry>> {
    val result = mutableListOf<List<MoreMenuEntry>>()
    val pending = mutableListOf<MoreMenuEntry>()
    fun flush() {
        result += pending.chunked(columns)
        pending.clear()
    }
    rows.flatten().filterNot { it is MoreMenuEntry.BuiltIn && it.item in shown }.forEach { entry ->
        if (entry == MoreMenuEntry.BuiltIn(MoreMenuItem.EXTENSIONS)) {
            flush()
            result += listOf(entry)
        } else {
            pending += entry
        }
    }
    flush()
    return result
}

/**
 * The "More" menu opened from the docked sidebar: a small panel right under the sidebar's button, in the see-through
 * look of the sidebar. It does not dim the page, and a tap outside it closes it, as Back does. It scrolls when the
 * screen is not tall enough. It grows out of the button's corner with the springs of the floating menu, and shrinks
 * back into it before [onDismiss] removes it.
 *
 * @param onDismiss Removes the menu, once it shrank back into the button.
 * @param content The menu's tiles.
 */
@Composable
internal fun AnchoredMoreMenu(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val anchor = SidebarMenuAnchor.bounds
    val scope = rememberCoroutineScope()
    val panel = remember { AnchoredPanel(scope, onDismiss) }
    LaunchedEffect(panel) { panel.open() }
    // Back, and actions done with their work, close the menu through here; see DejavuMenu.closeFloating.
    BackHandler(onBack = panel::remove)
    // Without a sidebar to grow out of, as after the app was restored, there is nothing to attach the menu to.
    if (anchor == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val menuTheme = shownMenuTheme()
    val density = LocalDensity.current
    val shape = RoundedCornerShape(MENU_CORNER)
    val navigationBarBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { screenOrigin = it.positionOnScreen() }
            // Taps outside the menu close it without dimming the page, which the dialog window does not do itself.
            .clickable(
                interactionSource = null,
                indication = null,
                onClickLabel = stringResource(R.string.browser_close_main_menu_handlebar_content_description),
                onClick = panel::close,
            ),
    ) {
        val width = TabSidebarLayout.occupiedWidth.coerceIn(MENU_MIN_WIDTH, MENU_MAX_WIDTH)
            .coerceAtMost(maxWidth - MENU_MARGIN * 2)
        val widthPx = with(density) { width.toPx() }
        val marginPx = with(density) { MENU_MARGIN.toPx() }
        val local = anchor.translate(-screenOrigin)
        // Its end edge lines up with the button's, so the menu hangs from the button over the page and the sidebar.
        val farthest = (constraints.maxWidth - widthPx - marginPx).coerceAtLeast(marginPx)
        val x = (local.right - widthPx).coerceIn(marginPx, farthest)
        val y = local.bottom + with(density) { MENU_GAP.toPx() }
        val room: Dp = maxHeight - with(density) { y.toDp() } - navigationBarBottom - MENU_MARGIN
        val tileAlpha = if (menuTheme != null) SIDEBAR_TILE_ALPHA else 1f
        Column(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(width)
                .heightIn(max = room.coerceAtLeast(MENU_MIN_HEIGHT))
                .graphicsLayer {
                    val scale = MENU_START_SCALE + (1f - MENU_START_SCALE) * panel.progress
                    scaleX = scale
                    scaleY = scale
                    alpha = smoothstep(panel.progress / MENU_FADE)
                    transformOrigin = TransformOrigin(1f, 0f)
                }
                .shadow(MENU_ELEVATION, shape)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .workspaceTheme(menuTheme)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = BORDER_ALPHA), shape)
                // Taps on the menu itself, between its tiles, must not reach the closing area behind it.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            CompositionLocalProvider(LocalTileAlpha provides tileAlpha, LocalMenuCloser provides panel) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * How far the compact menu is open and what moves it: the spring that grows it out of its button and the one that
 * shrinks it back, the same as the floating menu's.
 */
private class AnchoredPanel(private val scope: CoroutineScope, private val onClosed: () -> Unit) : MenuCloser {
    private val settled = Animatable(0f, visibilityThreshold = VISIBILITY_THRESHOLD)
    private var motion: Job? = null
    private var isClosing = false
    private var hasShrunk = false

    /** How far the menu is open, from 0 at the size of its button to 1 at its full size. */
    val progress: Float
        get() = settled.value

    fun open() {
        motion = scope.launch { settled.animateTo(1f, OPEN_SPRING) }
    }

    override fun close(then: () -> Unit) = shrinkAway {
        then()
        onClosed()
    }

    override fun closeBy(action: () -> Unit) = shrinkAway {
        action()
        delay(REMOVAL_TIMEOUT_MS)
        onClosed()
    }

    /** Removes the menu when asked to: right away once it shrank away, after shrinking it otherwise. */
    fun remove() {
        if (hasShrunk) onClosed() else close()
    }

    private fun shrinkAway(after: suspend () -> Unit) {
        if (isClosing) return
        isClosing = true
        motion?.cancel()
        motion = scope.launch {
            settled.animateTo(0f, ANCHORED_CLOSE_SPRING)
            hasShrunk = true
            after()
        }
    }
}

/** How many tiles a row of the compact menu has, which fits its width of at least [MENU_MIN_WIDTH]. */
const val COMPACT_MENU_COLUMNS = 3

private val MENU_MIN_WIDTH = 280.dp
private val MENU_MAX_WIDTH = 360.dp
private val MENU_MIN_HEIGHT = 120.dp
private val MENU_MARGIN = 8.dp
private val MENU_GAP = 4.dp
private val MENU_CORNER = 24.dp
private val MENU_ELEVATION = 8.dp

/**
 * How small the menu starts out of its button, as a share of its size. It is a small panel next to its button, so it
 * only needs to start a little short of its size: a deeper start reads as a zoom, not as a menu that opens.
 */
private const val MENU_START_SCALE = 0.94f

/**
 * The spring that shrinks the menu back, stiffer than [CLOSE_SPRING] of the floating menu so it leaves faster than it
 * came in (about 150 ms against about 200 ms): a menu that is dismissed is no longer wanted. It stays critically
 * damped, so a close that interrupts the opening turns around smoothly, from the speed it had, without a wobble.
 */
private val ANCHORED_CLOSE_SPRING = spring<Float>(
    dampingRatio = 1f,
    stiffness = 900f,
    visibilityThreshold = VISIBILITY_THRESHOLD,
)

/** How far open the menu is when its fade is done: it is all there well before it stops moving. */
private const val MENU_FADE = 0.4f
