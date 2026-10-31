/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.browser.ADDRESS_FIELD_HEIGHT
import org.mozilla.fenix.dejavu.browser.ADDRESS_FIELD_MARGIN
import org.mozilla.fenix.dejavu.browser.ADDRESS_FIELD_SIDE_MARGIN
import org.mozilla.fenix.dejavu.browser.BOTTOM_ADDRESS_BAR_MARGIN
import org.mozilla.fenix.dejavu.browser.BarsLayout
import org.mozilla.fenix.dejavu.browser.currentBarsLayout
import org.mozilla.fenix.dejavu.browser.navigationBarRoomPadding
import org.mozilla.fenix.dejavu.browser.shownPopupTheme
import org.mozilla.fenix.dejavu.browser.statusBarRoomPadding
import org.mozilla.fenix.dejavu.browser.workspaceTheme

/**
 * A swipe on one of the browser's bars that opens a floating panel, like the swipe up on the actions bar that opens the
 * "More" menu. The swipe starts in the browser's window and stays there after the panel's dialog came up over it, so
 * the browser passes it on here for the panel to follow.
 */
internal open class PanelSwipe {
    private var startedAt = 0L
    private var claimed = true

    /** Whether the finger that opened the panel is still on the screen. */
    var isFollowing by mutableStateOf(false)
        private set

    /** How far the finger moved the way the panel opens since it touched the bar, in pixels. */
    var distance by mutableFloatStateOf(0f)
        private set

    /** How fast the finger moved the way the panel opens when it left the screen, in pixels per second. */
    var releaseVelocity = 0f
        private set

    fun start() {
        startedAt = SystemClock.uptimeMillis()
        claimed = false
        distance = 0f
        releaseVelocity = 0f
        isFollowing = true
    }

    fun move(distance: Float) {
        if (isFollowing) this.distance = distance
    }

    fun end(velocity: Float) {
        if (!isFollowing) return
        releaseVelocity = velocity
        isFollowing = false
    }

    fun cancel() {
        isFollowing = false
        claimed = true
    }

    /** Whether a swipe just opened the panel, which then follows it. Each swipe is followed by one panel only. */
    fun claim(): Boolean {
        val unclaimed = !claimed && SystemClock.uptimeMillis() - startedAt < CLAIM_WINDOW_MS
        claimed = true
        return unclaimed
    }

    private companion object {
        const val CLAIM_WINDOW_MS = 2000L
    }
}

/** The swipe on the bar that opens the "More" menu, up on a bar at the bottom or down on the address bar at the top. */
internal object MoreMenuSwipe : PanelSwipe()

/** The swipe the page's details would follow, see [PageInfoMenu]; no bar starts it, so they open without one. */
internal object PageInfoSwipe : PanelSwipe()

/** Closes a floating panel, shrinking it back into its bar first. */
internal interface MenuCloser {
    /** Closes the panel, running [then] once it shrank away, so its work does not hold up the animation. */
    fun close(then: () -> Unit = {})

    /**
     * Shrinks the panel away, then runs [action], which closes the panel itself once its work is done, so the work
     * neither holds up the animation nor gets cut short by the panel going away.
     */
    fun closeBy(action: () -> Unit)
}

/** Closes the floating panel the content is in; `null` outside such a panel. */
internal val LocalMenuCloser = staticCompositionLocalOf<MenuCloser?> { null }

/** The bar a floating panel grows out of. */
internal enum class PanelBar {
    ACTIONS_BAR,
    TOP_ADDRESS_BAR,
    BOTTOM_ADDRESS_BAR,
    ;

    /** Whether the bar is at the top of the screen, so the panel grows down from it. */
    val isAtTop: Boolean
        get() = this == TOP_ADDRESS_BAR

    companion object {
        /** The bar the "More" menu grows out of in [layout]: the one swiping opens it on. */
        fun menuIn(layout: BarsLayout): PanelBar = when (layout) {
            BarsLayout.ACTIONS_BAR -> ACTIONS_BAR
            BarsLayout.TOP -> TOP_ADDRESS_BAR
            BarsLayout.BOTTOM -> BOTTOM_ADDRESS_BAR
        }

        /** The address bar in [layout]. */
        fun addressBarIn(layout: BarsLayout): PanelBar =
            if (layout.addressBarAtBottom) BOTTOM_ADDRESS_BAR else TOP_ADDRESS_BAR
    }
}

/**
 * The "More" menu of a web page: a panel that grows out of the bar that opens it, see [FloatingPanel].
 *
 * @param onDismiss Removes the menu, once the panel shrank back into the bar.
 * @param content The menu's tiles.
 */
@Composable
internal fun FloatingMoreMenu(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val bar = PanelBar.menuIn(currentBarsLayout())
    FloatingPanel(bar = bar, swipe = MoreMenuSwipe, onDismiss = onDismiss, content = content)
}

/**
 * A panel that grows out of one of the browser's bars and keeps the bar's margins: the actions bar or the address bar
 * at the bottom of the screen, or the address bar at the top. While the finger that swiped the bar stays down, the
 * panel follows it, and letting go opens or closes the panel. The content waits where it ends up while the panel grows
 * over it, and fades in as the panel nears its full size. The open panel can be dragged back by its handle, on its far
 * edge, or by its content to close it.
 *
 * @param bar The bar the panel grows out of.
 * @param swipe The swipe on that bar that opens the panel.
 * @param onDismiss Removes the panel, once it shrank back into the bar.
 * @param content What the panel shows, in a column that scrolls when it does not fit.
 */
@Composable
internal fun FloatingPanel(
    bar: PanelBar,
    swipe: PanelSwipe,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val look = remember(bar) { lookOf(bar) }
    val panel = remember {
        MenuPanel(
            scope = scope,
            swipe = swipe,
            opensDown = bar.isAtTop,
            barHeight = with(density) { look.barHeight.toPx() },
            flingVelocity = with(density) { FLING_VELOCITY.toPx() },
            onClosed = onDismiss,
        )
    }
    val close = remember(panel) { { panel.close() } }

    LaunchedEffect(panel) {
        if (swipe.claim()) panel.followSwipe() else panel.open()
    }
    // Back, and actions done with their work, close the panel through here; see DejavuMenu.closeFloating.
    BackHandler(onBack = panel::remove)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(panel) {
                awaitPointerEventScope {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull() ?: continue
                        panel.fingerAt(change.position.y, change.uptimeMillis, change.changedToDownIgnoreConsumed())
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = panel.progress.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClickLabel = stringResource(R.string.browser_close_main_menu_handlebar_content_description),
                    onClick = close,
                ),
        )
        PanelLayout(
            panel = panel,
            look = look,
            modifier = Modifier.align(if (panel.opensDown) Alignment.TopCenter else Alignment.BottomCenter),
            content = content,
        )
    }
}

/** The panel itself, in the margins of its bar and as tall as it is open, with its content and its handle. */
@Composable
private fun PanelLayout(
    panel: MenuPanel,
    look: PanelLook,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Layout(
        content = {
            CompositionLocalProvider(LocalMenuCloser provides panel) {
                PanelContent(panel, content)
            }
            PanelHandle(panel)
        },
        modifier = modifier
            .statusBarRoomPadding()
            .navigationBarRoomPadding()
            .padding(
                start = look.sideMargin,
                end = look.sideMargin,
                top = if (panel.opensDown) look.barMargin else PANEL_FAR_MARGIN,
                bottom = if (panel.opensDown) PANEL_FAR_MARGIN else look.barMargin,
            )
            .graphicsLayer {
                alpha = smoothstep(panel.progress / SURFACE_FADE)
                shape = look.shape
                clip = true
            }
            .background(MaterialTheme.colorScheme.surface)
            .workspaceTheme(shownPopupTheme())
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = BORDER_ALPHA), look.shape),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val handle = measurables[1].measure(Constraints.fixedWidth(width))
        val menu = measurables[0].measure(
            Constraints(
                minWidth = width,
                maxWidth = width,
                maxHeight = (constraints.maxHeight - handle.height).coerceAtLeast(0),
            ),
        )
        val travel = (handle.height + menu.height - panel.barHeight).coerceAtLeast(1f)
        panel.measured(travel)
        val height = (panel.barHeight + travel * panel.progress).roundToInt().coerceIn(0, constraints.maxHeight)
        layout(width, height) { placePanel(panel, menu, handle, height) }
    }
}

/** Places the content and the handle of a panel that is [height] tall. */
private fun Placeable.PlacementScope.placePanel(panel: MenuPanel, menu: Placeable, handle: Placeable, height: Int) {
    if (panel.opensDown) {
        // The same as below, upside down: the handle is on the bottom edge, and an open panel pushed back up takes the
        // content along over its edge.
        val handleY = height - handle.height
        val menuY = if (panel.ridesEdge) handleY - menu.height else 0
        panel.hiddenTop = 0f
        panel.hiddenBottom = (menuY + menu.height - handleY).coerceAtLeast(0).toFloat()
        menu.place(0, menuY)
        handle.place(0, handleY)
    } else {
        // The menu waits where it ends up while the panel grows over it, so its tiles do not ride up from the bar; an
        // open panel pulled down takes the menu along under its edge.
        val menuY = if (panel.ridesEdge) handle.height else height - menu.height
        panel.hiddenTop = (handle.height - menuY).coerceAtLeast(0).toFloat()
        panel.hiddenBottom = 0f
        menu.place(0, menuY)
        handle.place(0, 0)
    }
}

@Composable
private fun PanelHandle(panel: MenuPanel) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .draggable(
                state = rememberDraggableState { panel.follow(startY = panel.downY) },
                orientation = Orientation.Vertical,
                enabled = !panel.isClosing,
                startDragImmediately = true,
                onDragStopped = { panel.release() },
            )
            .padding(vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HANDLE_ALPHA)),
        )
    }
}

@Composable
private fun PanelContent(panel: MenuPanel, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .drawWithContent {
                clipRect(top = panel.hiddenTop, bottom = size.height - panel.hiddenBottom) {
                    this@drawWithContent.drawContent()
                }
            }
            .graphicsLayer { alpha = smoothstep((panel.progress - CONTENT_FADE_START) / CONTENT_FADE) }
            .nestedScroll(panel)
            .verticalScroll(rememberScrollState())
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = if (panel.opensDown) CONTENT_END_PADDING else 0.dp,
                bottom = if (panel.opensDown) 0.dp else CONTENT_END_PADDING,
            ),
        content = content,
    )
}

/**
 * How far the floating panel is open and what moves it: the swipe that opened it, drags on the panel, and the springs
 * that settle it open or shrink it away. The panel grows down from the top of the screen when [opensDown], and up from
 * the bottom otherwise.
 */
@Suppress("LongParameterList")
private class MenuPanel(
    private val scope: CoroutineScope,
    private val swipe: PanelSwipe,
    val opensDown: Boolean,
    val barHeight: Float,
    private val flingVelocity: Float,
    private val onClosed: () -> Unit,
) : NestedScrollConnection, MenuCloser {
    private val settled = Animatable(0f, visibilityThreshold = VISIBILITY_THRESHOLD)
    private var dragged by mutableStateOf<Float?>(null)
    private var followsSwipe by mutableStateOf(false)
    private var isMeasured by mutableStateOf(false)
    private var travel = 1f
    private var dragStart = 0f
    private var anchorY = 0f
    private var fingerY = 0f
    private val fingerVelocity = VelocityTracker()
    private var motion: Job? = null

    /** How a finger's way down counts toward opening the panel: 1 when it opens downward, -1 when it opens upward. */
    private val opening = if (opensDown) 1f else -1f

    /** Where the finger last touched down on the panel, in the coordinates of [fingerAt]. */
    var downY = 0f
        private set

    /** Whether the panel is shrinking away for good. */
    var isClosing by mutableStateOf(false)
        private set

    /** Whether the content moves with the panel's far edge, as it does while an open panel is dragged back. */
    var ridesEdge by mutableStateOf(false)
        private set

    /** How much of the top of the content is behind the strip of a handle on top, which it is not drawn over, in px. */
    var hiddenTop by mutableFloatStateOf(0f)

    /** How much of the bottom of the content is behind the strip of a handle at the bottom, in pixels. */
    var hiddenBottom by mutableFloatStateOf(0f)

    private var hasShrunk = false

    /** How far the panel is open, from 0 at the size of its bar to 1 at the size of the whole content. */
    val progress: Float
        get() = if (followsSwipe) (swipe.distance / travel).coerceIn(0f, 1f) else dragged ?: settled.value

    /** Takes how far the panel grows from the size of the bar to the size of the whole content, in pixels. */
    fun measured(travel: Float) {
        this.travel = travel
        if (!isMeasured) isMeasured = true
    }

    fun open() = move {
        awaitMeasured()
        settled.animateTo(1f, OPEN_SPRING)
    }

    fun followSwipe() = move {
        awaitMeasured()
        followsSwipe = true
        snapshotFlow { swipe.isFollowing }.first { !it }
        settle(swipe.releaseVelocity, startedAt = 0f)
    }

    /** Takes where the finger on the panel is, in the coordinates of the box around the panel, which does not move. */
    fun fingerAt(y: Float, uptimeMillis: Long, pressed: Boolean) {
        if (pressed) {
            downY = y
            fingerVelocity.resetTracking()
        }
        fingerY = y
        fingerVelocity.addPosition(uptimeMillis, Offset(0f, y))
    }

    /**
     * Moves the panel with the finger, by as much as the finger moved from [startY] when the panel started following
     * it. Drags follow where the finger is rather than add up their own steps, which come out short while the panel
     * moves the content under the finger.
     */
    fun follow(startY: Float) {
        if (isClosing) return
        if (dragged == null) {
            dragStart = progress
            anchorY = startY
            motion?.cancel()
            followsSwipe = false
            // Only a panel picked up at its full size takes the content along, where both places of the content meet.
            if (dragStart >= FULLY_OPEN) ridesEdge = true
        }
        dragged = (dragStart + opening * (fingerY - anchorY) / travel).coerceIn(0f, 1f)
    }

    /** Opens or closes the panel the finger let go of. */
    fun release() {
        if (dragged == null || isClosing) return
        val velocity = opening * fingerVelocity.calculateVelocity().y
        val startedAt = dragStart
        move { settle(velocity, startedAt) }
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

    /** Removes the panel when asked to: right away once it shrank away, after shrinking it otherwise. */
    fun remove() {
        if (hasShrunk) onClosed() else close()
    }

    private fun shrinkAway(after: suspend () -> Unit) {
        if (isClosing) return
        isClosing = true
        val from = progress
        move {
            settled.snapTo(from)
            followsSwipe = false
            dragged = null
            settled.animateTo(0f, CLOSE_SPRING)
            hasShrunk = true
            after()
        }
    }

    /**
     * Opens or closes the panel the finger let go of, whichever way it took the panel far enough from where it picked
     * the panel up.
     *
     * @param velocity How fast the finger moved the way the panel opens, in pixels per second.
     * @param startedAt How far the panel was open when the finger picked it up.
     */
    private suspend fun settle(velocity: Float, startedAt: Float) {
        val from = progress
        val threshold = SETTLE_SHARE + (1f - 2 * SETTLE_SHARE) * startedAt
        val open = when {
            velocity > flingVelocity -> true
            velocity < -flingVelocity -> false
            else -> from > threshold
        }
        settled.snapTo(from)
        followsSwipe = false
        dragged = null
        if (open) {
            settled.animateTo(1f, OPEN_SPRING, initialVelocity = velocity / travel)
            ridesEdge = false
        } else {
            isClosing = true
            settled.animateTo(0f, CLOSE_SPRING, initialVelocity = velocity / travel)
            onClosed()
        }
    }

    private fun move(block: suspend () -> Unit) {
        motion?.cancel()
        motion = scope.launch { block() }
    }

    private suspend fun awaitMeasured() {
        snapshotFlow { isMeasured }.first { it }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val from = dragged ?: return Offset.Zero
        if (source != NestedScrollSource.UserInput || available.y * opening <= 0f || from >= 1f) return Offset.Zero
        // A finger going back the way the panel opens first opens it again, then scrolls the content.
        follow(anchorY)
        return Offset(0f, available.y)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y * opening >= 0f || isClosing) return Offset.Zero
        // The content is at its end, so the rest of the finger's way back drags the panel back.
        follow(startY = fingerY - available.y)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (dragged == null) return Velocity.Zero
        release()
        return available
    }
}

/** The size and place of the bar a panel grows out of, which the panel has while it is that small. */
private class PanelLook(val barHeight: Dp, val sideMargin: Dp, val barMargin: Dp) {
    val shape: Shape = RoundedCornerShape(barHeight / 2)
}

private fun lookOf(bar: PanelBar) = when (bar) {
    PanelBar.ACTIONS_BAR -> PanelLook(BAR_HEIGHT, BAR_SIDE_MARGIN, ACTIONS_BAR_BOTTOM_MARGIN)
    PanelBar.TOP_ADDRESS_BAR -> PanelLook(ADDRESS_FIELD_HEIGHT, ADDRESS_FIELD_SIDE_MARGIN, ADDRESS_FIELD_MARGIN)
    PanelBar.BOTTOM_ADDRESS_BAR ->
        PanelLook(ADDRESS_FIELD_HEIGHT, ADDRESS_FIELD_SIDE_MARGIN, ADDRESS_FIELD_MARGIN + BOTTOM_ADDRESS_BAR_MARGIN)
}

private fun smoothstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** How far a panel stays from the edge of the screen it grows toward, past the system bars. */
private val PANEL_FAR_MARGIN = 16.dp

/** The space between the content and the edge of the panel across from its handle. */
private val CONTENT_END_PADDING = 8.dp

/** How long the shrunk panel waits at most for an action to close it, in case the action never does. */
private const val REMOVAL_TIMEOUT_MS = 3000L

/** How fast a finger has to leave the screen for the panel to go its way whatever its size, per second. */
private val FLING_VELOCITY = 1000.dp

/** How close the panel has to come to where it settles to stop there, small enough not to show the last step. */
private const val VISIBILITY_THRESHOLD = 0.001f
private val OPEN_SPRING = spring(dampingRatio = 0.9f, stiffness = 600f, visibilityThreshold = VISIBILITY_THRESHOLD)
private val CLOSE_SPRING = spring(dampingRatio = 1f, stiffness = 500f, visibilityThreshold = VISIBILITY_THRESHOLD)

/**
 * How much of the way between the bar and the whole content a finger has to take the panel for it to go on that way
 * when let go, rather than back.
 */
private const val SETTLE_SHARE = 0.3f

/** How far the panel opens while its surface fades in over the bar. */
private const val SURFACE_FADE = 0.2f

/** How far the panel is open when its content starts to fade in, and over how much of the way it does. */
private const val CONTENT_FADE_START = 0.55f
private const val CONTENT_FADE = 0.45f

/** How far open a panel picked up has to be to take the content along when dragged back, see [MenuPanel.ridesEdge]. */
private const val FULLY_OPEN = 0.995f
private const val SCRIM_ALPHA = 0.32f
private const val HANDLE_ALPHA = 0.4f
