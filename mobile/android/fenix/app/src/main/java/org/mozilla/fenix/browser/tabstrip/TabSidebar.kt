/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.browser.tabstrip

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.navigation.NavController
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.browser.state.selector.getNormalOrPrivateTabs
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.compose.base.theme.layout.AcornWindowSize
import mozilla.components.compose.browser.awesomebar.AwesomeBar
import mozilla.components.compose.browser.awesomebar.AwesomeBarDefaults
import mozilla.components.compose.browser.awesomebar.LocalSuggestionTitleMaxLines
import mozilla.components.concept.engine.EngineSession.LoadUrlFlags
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.support.ktx.kotlin.tryGetHostFromUrl
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.components.components
import org.mozilla.fenix.dejavu.browser.closeOnMiddleClick
import org.mozilla.fenix.dejavu.browser.loadingColors
import org.mozilla.fenix.dejavu.browser.openDejavuMenu
import org.mozilla.fenix.dejavu.browser.shownSidebarTheme
import org.mozilla.fenix.dejavu.browser.workspaceTheme
import org.mozilla.fenix.dejavu.home.DejavuHomeScreen
import org.mozilla.fenix.dejavu.home.TabRow
import org.mozilla.fenix.dejavu.home.displayTitle
import org.mozilla.fenix.dejavu.home.isAwake
import org.mozilla.fenix.dejavu.menu.BORDER_ALPHA
import org.mozilla.fenix.dejavu.menu.SidebarMenuAnchor
import org.mozilla.fenix.dejavu.menu.readOnMainThread
import org.mozilla.fenix.dejavu.search.EngineChip
import org.mozilla.fenix.dejavu.search.EngineShortcutProvider
import org.mozilla.fenix.dejavu.search.TypedEngines
import org.mozilla.fenix.dejavu.search.webSuggestionProviders
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.utils.Settings

/** The sidebar width a new install starts with, and the one a double tap on its edge goes back to. */
internal const val SIDEBAR_DEFAULT_WIDTH_DP = 320f

/** The narrowest the sidebar gets: tab icons and short titles still fit. */
internal const val SIDEBAR_MIN_WIDTH_DP = 200f

/** The widest the sidebar gets, as a share of the window, so the page always keeps most of it. */
internal const val SIDEBAR_MAX_WINDOW_FRACTION = 0.4f

private const val OPEN_FRACTION = 0.35f

/** The width the search field needs to show its whole hint, "Search tabs or enter address". */
private val FullHintMinWidth = 220.dp
private val openFlingVelocity = 400.dp

/** How far from the inner edge, inside the sidebar, a sideways drag resizes it and a double tap resets its width. */
private val ResizeTouchWidth = 28.dp

/** The room the grab line has along the inner edge, like a scrollbar's. */
private val ResizeGripWidth = 10.dp

/**
 * The width in dp the sidebar takes for [wished] dp, 0 or less meaning the default width, in a window [windowWidth]
 * dp wide. It stays between [SIDEBAR_MIN_WIDTH_DP] and [SIDEBAR_MAX_WINDOW_FRACTION] of the window; on a window too
 * narrow for both, the minimum wins.
 */
internal fun sidebarWidthDp(wished: Float, windowWidth: Float): Float {
    val max = (windowWidth * SIDEBAR_MAX_WINDOW_FRACTION).coerceAtLeast(SIDEBAR_MIN_WIDTH_DP)
    val width = if (wished <= 0f) SIDEBAR_DEFAULT_WIDTH_DP else wished
    return width.coerceIn(SIDEBAR_MIN_WIDTH_DP, max)
}

/**
 * Whether the auto-hidden sidebar, pulled out to [pulled] px of [width] px and let go at [velocity] px/s toward the end
 * of the screen, ends open. It opens past [OPEN_FRACTION] of its width or on a fling of [flingVelocity] toward the
 * end, and closes on a fling toward the start, whichever way it was dragged.
 */
internal fun sidebarSettlesOpen(pulled: Float, width: Float, velocity: Float, flingVelocity: Float): Boolean = when {
    velocity >= flingVelocity -> true
    velocity <= -flingVelocity -> false
    else -> pulled > width * OPEN_FRACTION
}

/** Whether the home screen shows in a sidebar next to pages, which then need no Home button. */
@Composable
fun isHomeSidebarShown(settings: Settings = components.settings): Boolean =
    settings.isTabSidebarShown(AcornWindowSize.isLargeWindow()) && !settings.isVerticalTabsAutoHide

/** Where the sidebar sits, for what lays itself out beside it, like the "More" menu sheet. */
object TabSidebarLayout {
    /** The width the sidebar takes next to pages, 0 when it is not shown there. It follows a drag of its edge live. */
    var occupiedWidth: Dp by mutableStateOf(0.dp)
}

/**
 * The Dejavu home screen in a sidebar next to the page, under one search field that finds open tabs and loads
 * addresses and searches. Shows nothing on small windows, so the page keeps the full width there. Dragging the edge
 * that faces the page changes the width, and double tapping it resets it.
 *
 * @param activity The activity, for the search suggestions.
 * @param navController Used by the home screen to open settings, bookmarks and the like.
 * @param onNoTabs Invoked when the last tab is closed.
 * @param overlayShown Whether the auto-hidden sidebar is shown over the page, or null when the sidebar sits next to the
 *   page. Back, a tap beside it and a swipe toward the start of the screen close it.
 * @param showHandle Whether the handle that pulls out the auto-hidden sidebar is drawn. It works either way.
 * @param pageControls Whether the sidebar stands in for the page's bars: it then has back, forward, reload and the
 *   "More" menu above the search field, and the field shows the page's address while nothing is typed.
 * @param browserStore The [BrowserStore] the tabs are observed from.
 * @param tabsUseCases The [TabsUseCases] used to select tabs.
 */
@Suppress("LongMethod", "LongParameterList", "CognitiveComplexMethod")
@Composable
fun TabSidebar(
    activity: HomeActivity,
    navController: NavController,
    onNoTabs: () -> Unit,
    overlayShown: MutableState<Boolean>? = null,
    showHandle: Boolean = true,
    pageControls: Boolean = false,
    browserStore: BrowserStore = components.core.store,
    tabsUseCases: TabsUseCases = components.useCases.tabsUseCases,
) {
    DisposableEffect(Unit) { onDispose { TabSidebarLayout.occupiedWidth = 0.dp } }
    val settings = components.settings
    if (!settings.isTabSidebarShown(AcornWindowSize.isLargeWindow())) {
        SideEffect { TabSidebarLayout.occupiedWidth = 0.dp }
        return
    }

    val selectedTabId by browserStore.observeAsComposableState { it.selectedTabId }
    val restoreComplete by browserStore.observeAsComposableState { it.restoreComplete }
    LaunchedEffect(selectedTabId, restoreComplete) {
        if (selectedTabId == null && restoreComplete) onNoTabs()
    }

    // The width: the saved one, or the one being dragged, kept inside what this window allows. Rotating or resizing
    // the window recomposes with its new width.
    val windowWidth = LocalConfiguration.current.screenWidthDp.toFloat()
    var wishedWidth by remember { mutableFloatStateOf(settings.sidebarWidthDp.toFloat()) }
    val widthDp = sidebarWidthDp(wishedWidth, windowWidth)
    val currentWidth = rememberUpdatedState(widthDp)
    SideEffect { TabSidebarLayout.occupiedWidth = if (overlayShown == null) widthDp.dp else 0.dp }
    var resizing by remember { mutableStateOf(false) }
    fun saveWidth(width: Float) {
        wishedWidth = width
        settings.sidebarWidthDp = width.roundToInt()
    }

    val isShown = overlayShown?.value ?: true
    val hide: () -> Unit = { overlayShown?.value = false }
    BackHandler(enabled = overlayShown?.value == true, onBack = hide)
    val density = LocalDensity.current
    val widthPx = with(density) { widthDp.dp.toPx() }
    val flingPx = with(density) { openFlingVelocity.toPx() }
    var pulled by remember { mutableFloatStateOf(if (isShown) widthPx else 0f) }
    val isPulled by remember { derivedStateOf { pulled > 0f } }
    // One state for the handle and the open sidebar. Animations run through it too, so a new drag stops them.
    val dragState = rememberDraggableState { delta -> pulled = (pulled + delta).coerceIn(0f, widthPx) }
    suspend fun animatePulled(target: Float) {
        dragState.drag { animate(pulled, target) { value, _ -> pulled = value } }
    }
    // Drags are in screen pixels: toward the end of the screen opens, which is to the left in RTL.
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val onDragStopped: suspend CoroutineScope.(Float) -> Unit = { velocity ->
        // The velocity is reversed along with the deltas in RTL.
        val open = sidebarSettlesOpen(pulled, widthPx, velocity, flingPx)
        if (open == isShown) animatePulled(if (open) widthPx else 0f)
        overlayShown?.value = open
    }
    LaunchedEffect(isShown) { animatePulled(if (isShown) widthPx else 0f) }
    // A shown sidebar takes its new width at once, so its outer edge stays on the screen edge while it is resized.
    LaunchedEffect(widthPx) { if (isShown) pulled = widthPx }

    val hasScrim = overlayShown != null && (isShown || isPulled)
    Box(modifier = if (hasScrim) Modifier.fillMaxSize() else Modifier.fillMaxHeight()) {
        if (hasScrim) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = pulled / widthPx }
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .pointerInput(Unit) { detectTapGestures { hide() } },
            )
        }

        if (overlayShown != null && !isShown) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .graphicsLayer { alpha = 1f - pulled / widthPx }
                    .systemGestureExclusion()
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        reverseDirection = isRtl,
                        onDragStopped = onDragStopped,
                    )
                    .clickable { overlayShown.value = true }
                    .padding(start = 2.dp, end = 8.dp)
                    .size(width = 6.dp, height = 120.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = if (showHandle) 0.4f else 0f),
                        RoundedCornerShape(3.dp),
                    ),
            )
        }

        if (isShown || isPulled) {
            val placement = remember { arrayOfNulls<LayoutCoordinates>(1) }
            Box(
                modifier = Modifier
                    // Mirrored in RTL, so the closed sidebar sits past the start edge there too. A docked sidebar never
                    // slides, so it stays on the screen edge even in the frame where its width changed.
                    .offset { IntOffset(if (overlayShown == null) 0 else (pulled - widthPx).roundToInt(), 0) }
                    .onPlaced { placement[0] = it }
                    .resizeEdge(
                        isRtl = isRtl,
                        placement = { placement[0] },
                        currentWidth = currentWidth,
                        onResize = { width ->
                            resizing = true
                            wishedWidth = width
                            // In the same frame as the width, else a fast drag shows the sidebar off the screen edge.
                            if (isShown) pulled = with(density) { sidebarWidthDp(width, windowWidth).dp.toPx() }
                        },
                        onResizeEnd = {
                            resizing = false
                            saveWidth(currentWidth.value)
                        },
                        onReset = { saveWidth(0f) },
                    )
                    .then(
                        if (overlayShown != null) {
                            // Swiping the open sidebar toward the start of the screen closes it.
                            Modifier.draggable(
                                state = dragState,
                                orientation = Orientation.Horizontal,
                                reverseDirection = isRtl,
                                onDragStopped = onDragStopped,
                            )
                        } else {
                            Modifier
                        },
                    )
                    .width(widthDp.dp)
                    .fillMaxHeight(),
            ) {
                SidebarContent(
                    activity = activity,
                    navController = navController,
                    isShown = isShown,
                    pageControls = pageControls,
                    browserStore = browserStore,
                    tabsUseCases = tabsUseCases,
                    onDone = hide,
                    modifier = Modifier.fillMaxSize(),
                )
                ResizeGrip(
                    active = resizing,
                    onReset = { saveWidth(0f) },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

/**
 * Resizes the sidebar by sideways drags that start within [ResizeTouchWidth] of its inner edge, the end edge, mirrored
 * in RTL, far from the screen edge where Android's back gesture starts. The drag is taken before the content sees it,
 * so the workspace pager does not scroll, while taps and vertical drags there still reach the content. The width comes
 * from window positions, since the sidebar itself moves under the finger in RTL as it resizes.
 */
private fun Modifier.resizeEdge(
    isRtl: Boolean,
    placement: () -> LayoutCoordinates?,
    currentWidth: State<Float>,
    onResize: (widthDp: Float) -> Unit,
    onResizeEnd: () -> Unit,
    onReset: () -> Unit,
): Modifier = pointerInput(isRtl) {
    val zone = ResizeTouchWidth.toPx()
    var lastTapUp = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val inZone = if (isRtl) down.position.x <= zone else down.position.x >= size.width - zone
        if (!inZone) return@awaitEachGesture
        var dx = 0f
        var dy = 0f
        while (hypot(dx, dy) <= viewConfiguration.touchSlop) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
            if (change == null) return@awaitEachGesture
            if (!change.pressed) {
                // A tap, seen without taking it from the content: the second one in a row resets the width.
                val up = change.uptimeMillis
                if (up - lastTapUp <= viewConfiguration.doubleTapTimeoutMillis) {
                    lastTapUp = 0L
                    onReset()
                } else {
                    lastTapUp = up
                }
                return@awaitEachGesture
            }
            dx += change.positionChange().x
            dy += change.positionChange().y
        }
        if (abs(dy) >= abs(dx)) return@awaitEachGesture
        val start = placement() ?: return@awaitEachGesture
        val startX = start.localToWindow(down.position).x
        val startWidth = currentWidth.value
        val direction = if (isRtl) -1f else 1f
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed) break
            change.consume()
            val layout = placement()?.takeIf { it.isAttached } ?: break
            val x = layout.localToWindow(change.position).x
            onResize(startWidth + (x - startX) * direction / density)
        }
        onResizeEnd()
    }
}

/**
 * The grab line on the inner edge of the sidebar, drawn on its own background like a scrollbar: faint at rest, clear
 * while dragged. It takes no touches, [resizeEdge] does, and gives TalkBack the double tap that resets the width.
 */
@Composable
private fun ResizeGrip(active: Boolean, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(
        targetValue = if (active) GRIP_ACTIVE_ALPHA else GRIP_REST_ALPHA,
        label = "SidebarGripAlpha",
    )
    val label = stringResource(R.string.dejavu_sidebar_resize)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .width(ResizeGripWidth)
            .fillMaxHeight()
            .semantics {
                contentDescription = label
                onClick {
                    onReset()
                    true
                }
            },
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 40.dp)
                .graphicsLayer { this.alpha = alpha }
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
        )
    }
}

private const val GRIP_REST_ALPHA = 0.18f

/** How much of the search field shows over a workspace gradient, the same as the menu tiles beside the sidebar. */
private const val SIDEBAR_FIELD_ALPHA = 0.55f
private const val GRIP_ACTIVE_ALPHA = 0.7f

/**
 * The search field on top and, under it, the home screen or, while something is typed, the open tabs that match and
 * then the suggestions of the address bar. Enter loads what was typed in the shown tab, Shift+Enter in a new tab.
 */
@Suppress("LongMethod", "LongParameterList")
@Composable
private fun SidebarContent(
    activity: HomeActivity,
    navController: NavController,
    isShown: Boolean,
    pageControls: Boolean,
    browserStore: BrowserStore,
    tabsUseCases: TabsUseCases,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fenix = components
    val context = LocalContext.current
    val look by remember { fenix.readOnMainThread { DejavuSettings.get(context) } }.sidebarLook.collectAsState()
    // Without its buttons above the field, the "More" menu keeps one beside the field, as nothing else opens it there.
    val controls = pageControls && look.showPageControls
    val menuBesideField = pageControls && !look.showPageControls
    var query by remember { mutableStateOf("") }
    // The engine picked with "@name", shown as a chip in the field. Its search replaces the default one, once.
    var picked by remember { mutableStateOf<SearchEngine?>(null) }
    val typedEngines = remember { TypedEngines(browserStore) }
    val listedEngines = remember(query) { typedEngines.listed(query) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val selectedTabId by browserStore.observeAsComposableState { it.selectedTabId }
    val isPrivate by browserStore.observeAsComposableState { it.selectedTab?.content?.private == true }

    fun close() {
        query = ""
        picked = null
        focusManager.clearFocus()
    }

    fun pick(engine: SearchEngine) {
        picked = engine
        query = ""
    }

    fun openTab(tabId: String) {
        tabsUseCases.selectTab(tabId)
        close()
        onDone()
    }

    // The address bar's own loading, so search keywords, search engines and new tabs work the same.
    fun load(
        text: String,
        newTab: Boolean,
        forceSearch: Boolean = false,
        engine: SearchEngine? = null,
        flags: LoadUrlFlags = LoadUrlFlags.none(),
    ) {
        if (text.isBlank()) return
        fenix.useCases.fenixBrowserUseCases.loadUrlOrSearch(
            searchTermOrURL = text.trim(),
            newTab = newTab,
            private = isPrivate == true,
            // A search with the picked engine is never read as an address or a keyword.
            forceSearch = forceSearch || (picked != null && engine == picked),
            searchEngine = engine
                ?: browserStore.state.search.selectedOrDefaultSearchEngine(private = isPrivate == true),
            flags = flags,
        )
        close()
        onDone()
    }

    BackHandler(enabled = query.isNotEmpty() || focused) { close() }

    // A private field shows no history, see webSuggestionProviders.
    val webProviders = remember(isPrivate, picked) {
        webSuggestionProviders(
            activity = activity,
            isPrivate = isPrivate == true,
            engine = picked ?: browserStore.state.search.selectedOrDefaultSearchEngine(private = isPrivate == true),
            // Open tabs have their own section above the suggestions.
            showOpenTabs = false,
            typedQuery = { query },
            onLoad = { url, forceSearch, engine, flags -> load(url, newTab = false, forceSearch, engine, flags) },
            onSelectTab = ::openTab,
            picked = picked != null,
        )
    }
    // While "@name" is typed, only the engines it matches.
    val engineProviders = remember { listOf(EngineShortcutProvider(context, typedEngines, ::pick)) }
    val providers = if (listedEngines != null) engineProviders else webProviders

    // Opaque, as the auto-hidden sidebar slides over the page. Drawn like the bars, so it matches them when docked.
    val theme = shownSidebarTheme()
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .workspaceTheme(theme)
            // The sidebar runs behind the system bars, like pages do, so its buttons keep below the status bar while
            // it shows and none while it is hidden. A bar swiped in for a moment brings it back.
            .windowInsetsPadding(WindowInsets.statusBars)
            // Dejavu: and the bottom workspace bar keeps above a navigation bar, which DejavuHome leaves to the sidebar.
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        val selectedUrl by browserStore.observeAsComposableState { state -> state.selectedTab?.content?.url }
        val loading by browserStore.observeAsComposableState { it.selectedTab?.content?.loading == true }
        // Only pages with a host are shown, and their whole address is put in the field to edit when it is tapped.
        val pageHost = selectedUrl?.let(::shownHost)?.takeIf { pageControls }
        val pageUrl = selectedUrl?.takeIf { pageHost != null }
        if (controls) PageControls(navController = navController, browserStore = browserStore)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SidebarSearchField(
                value = query,
                // A space after a whole or unique "@name" picks that engine.
                onValueChange = { typed ->
                    val engine = typedEngines.pickedBySpace(typed)
                    if (engine != null) pick(engine) else query = typed
                },
                // Enter on "@name" picks its first engine, as Tab does.
                onSubmit = { newTab ->
                    listedEngines?.let { pick(it.first()) } ?: load(query, newTab, engine = picked)
                },
                onClear = ::close,
                // The x empties the field and keeps editing, as the address bar does. Esc leaves it.
                onClearText = {
                    query = ""
                    focus.requestFocus()
                },
                loading = loading == true,
                // See-through on a workspace gradient, like the tiles of the menu opened from the sidebar.
                fillAlpha = if (theme != null) SIDEBAR_FIELD_ALPHA else 1f,
                onMoveSelection = { down ->
                    focusManager.moveFocus(if (down) FocusDirection.Down else FocusDirection.Up)
                },
                restText = pageHost,
                editText = pageUrl,
                engine = picked,
                onRemoveEngine = { picked = null },
                onTab = {
                    val first = listedEngines?.first()
                    first?.let(::pick)
                    first != null
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = 8.dp,
                        end = if (menuBesideField) 0.dp else 8.dp,
                        top = if (controls) 0.dp else 8.dp,
                        bottom = 8.dp,
                    )
                    .focusRequester(focus)
                    .onFocusChanged {
                        focused = it.hasFocus
                        // Leaving the address unchanged shows the host again.
                        if (!it.hasFocus && query == pageUrl) query = ""
                    },
            )
            if (menuBesideField) MenuButton(navController)
        }
        // The page's own address, not yet edited, keeps the home content instead of suggestions for it.
        if (query.isBlank() || query == pageUrl) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                DejavuHomeScreen(navController = navController, onOpenTab = ::openTab, isSidebar = true)
            }
        } else {
            val typed = query.trim()
            // Dejavu: the tabs are only watched while something is typed, and filtered again only when they change.
            val tabs by browserStore.observeAsComposableState { state ->
                state.getNormalOrPrivateTabs(private = state.selectedTab?.content?.private == true)
            }
            val matches = remember(typed, tabs, listedEngines) {
                tabs.takeIf { listedEngines == null }.orEmpty().filter {
                    it.content.title.contains(typed, true) || it.content.url.contains(typed, true)
                }
            }
            if (matches.isNotEmpty()) {
                SectionHeader(stringResource(R.string.dejavu_sidebar_open_tabs))
                Column(
                    modifier = Modifier
                        .heightIn(max = OpenTabsMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                ) {
                    matches.forEach { tab ->
                        TabRow(
                            title = tab.displayTitle,
                            url = tab.content.url,
                            depth = 0,
                            container = null,
                            isAwake = tab.isAwake,
                            isCurrent = tab.id == selectedTabId,
                            isSplit = false,
                            selection = null,
                            actions = emptyList(),
                            onAction = {},
                            onClick = { openTab(tab.id) },
                            // A middle-click closes the tab, as on the tab rows and the tab strip.
                            modifier = Modifier.closeOnMiddleClick { tabsUseCases.removeTab(tab.id) },
                            look = look,
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // The narrow sidebar has no room for a title on two lines.
                CompositionLocalProvider(LocalSuggestionTitleMaxLines provides 1) {
                    AwesomeBar(
                        text = typed,
                        // Rows sit on the sidebar's own background, with headers like the address bar's dropdown.
                        colors = AwesomeBarDefaults.colors(
                            background = Color.Transparent,
                            groupTitle = MaterialTheme.colorScheme.primary,
                        ),
                        providers = providers,
                        onSuggestionClicked = { it.onSuggestionClicked?.invoke() },
                        onAutoComplete = { suggestion -> suggestion.editSuggestion?.let { query = it } },
                        onRemoveClicked = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        // Like the suggestion group headers below it.
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .heightIn(min = 36.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { heading() },
    )
}

private val OpenTabsMaxHeight = 280.dp

/** The height of the search field, the same as the address bar's field. */
private val FieldHeight = 48.dp

/**
 * Back, forward, reload and the "More" menu of the shown page, for when the sidebar stands in for the page's bars.
 * Each value is its own slice of the store, so a loading page only redraws the reload button.
 */
@Composable
private fun PageControls(navController: NavController, browserStore: BrowserStore) {
    val sessionUseCases = components.useCases.sessionUseCases
    val canGoBack by browserStore.observeAsComposableState { it.selectedTab?.content?.canGoBack == true }
    val canGoForward by browserStore.observeAsComposableState { it.selectedTab?.content?.canGoForward == true }
    val loading by browserStore.observeAsComposableState { it.selectedTab?.content?.loading == true }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        ControlButton(iconsR.drawable.mozac_ic_back_24, stringResource(R.string.browser_menu_back), canGoBack == true) {
            sessionUseCases.goBack()
        }
        ControlButton(
            iconsR.drawable.mozac_ic_forward_24,
            stringResource(R.string.browser_menu_forward),
            canGoForward == true,
        ) {
            sessionUseCases.goForward()
        }
        if (loading == true) {
            ControlButton(iconsR.drawable.mozac_ic_cross_24, stringResource(R.string.browser_menu_stop)) {
                sessionUseCases.stopLoading()
            }
        } else {
            ControlButton(iconsR.drawable.mozac_ic_arrow_clockwise_24, stringResource(R.string.browser_menu_refresh)) {
                sessionUseCases.reload()
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        MenuButton(navController)
    }
}

/** Opens the "More" menu of the shown page. */
@Composable
private fun MenuButton(navController: NavController) {
    // Dejavu: beside a page, the menu is a compact panel under this button, which tells where it is.
    // Dejavu: no button, no anchor.
    DisposableEffect(Unit) { onDispose { SidebarMenuAnchor.bounds = null } }
    Box(
        modifier = Modifier.onGloballyPositioned {
            // Dejavu: tells the menu where the button is.
            SidebarMenuAnchor.bounds = Rect(it.positionOnScreen(), it.size.toSize())
        },
    ) {
        ControlButton(iconsR.drawable.mozac_ic_ellipsis_vertical_24, stringResource(R.string.content_description_menu)) {
            // Dejavu: docked, the menu is a compact panel under this button.
            val docked = TabSidebarLayout.occupiedWidth > 0.dp
            navController.openDejavuMenu(fromBar = false, anchored = docked)
        }
    }
}

@Composable
private fun ControlButton(icon: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = color,
            disabledContentColor = color.copy(alpha = DISABLED_ALPHA),
        ),
    ) {
        Icon(painter = painterResource(icon), contentDescription = label)
    }
}

private const val DISABLED_ALPHA = 0.38f

/** The host of [url] without "www.", or null for pages that have none, like the home page. */
private fun shownHost(url: String): String? =
    url.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.tryGetHostFromUrl()?.removePrefix("www.")

/**
 * The one search field of the sidebar, drawn like the field of the address bar (see DejavuAddressBar): a 48dp pill
 * with a hairline that turns into the accent ring while focused.
 *
 * @param value What is typed.
 * @param onValueChange Called as the user types.
 * @param onSubmit Enter or the keyboard's Go, with whether it opens a new tab (Shift+Enter).
 * @param onClear Esc: empties the field and leaves it.
 * @param onClearText The clear button: empties the field and keeps editing, as the address bar's does.
 * @param fillAlpha How opaque the field is drawn, below 1 so a gradient behind it shows through.
 * @param loading Whether the shown page loads, which runs colors through the letters of [restText], as the address
 *   bar's do, see loadingColors.
 * @param onMoveSelection Up or down arrow, true for down, to move through the results.
 * @param restText Shown instead of the hint while the field is empty and not focused, like the shown page's address.
 * @param editText Put in the field, all selected, when it gets focus while empty, like the shown page's whole address.
 * @param engine The engine picked with "@name", shown as a chip in place of the search icon.
 * @param onRemoveEngine The chip's x, Esc, or Backspace in the empty field, while [engine] is set.
 * @param onTab Tab, returns whether it was used.
 * @param modifier The [Modifier] of the field.
 */
@Suppress("LongMethod", "LongParameterList", "CognitiveComplexMethod")
@Composable
internal fun SidebarSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (newTab: Boolean) -> Unit,
    onClear: () -> Unit,
    onMoveSelection: (down: Boolean) -> Unit,
    onClearText: () -> Unit = onClear,
    loading: Boolean = false,
    fillAlpha: Float = 1f,
    restText: String? = null,
    editText: String? = null,
    engine: SearchEngine? = null,
    onRemoveEngine: () -> Unit = {},
    onTab: () -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    // Kept for its selection. A [value] changed from outside puts the cursor at its end.
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))
    val currentEditText by rememberUpdatedState(editText)
    // After the tap that focused the field has placed its cursor, so the selection is not undone by it.
    LaunchedEffect(focused) {
        val text = currentEditText
        if (focused && value.isEmpty() && text != null && engine == null) {
            field = TextFieldValue(text, TextRange(0, text.length))
            onValueChange(text)
        }
    }
    val scheme = MaterialTheme.colorScheme
    val textStyle = MaterialTheme.typography.bodyLarge
    val hint = stringResource(R.string.dejavu_sidebar_search_hint)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(FieldHeight)
            .onFocusChanged { focused = it.hasFocus }
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh.copy(alpha = fillAlpha))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) scheme.primary else scheme.onSurface.copy(alpha = BORDER_ALPHA),
                shape = CircleShape,
            ),
    ) {
        if (engine != null) {
            EngineChip(
                engine = engine,
                showName = false,
                onRemove = onRemoveEngine,
                modifier = Modifier.padding(start = 8.dp, end = 6.dp),
            )
        } else {
            Box(modifier = Modifier.size(FieldHeight), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.dejavu_ic_search_24),
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .padding(end = if (value.isEmpty()) 16.dp else 0.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            // The narrow sidebar has no room for the whole hint, so it gets the short one instead of a cut one.
            val shownHint = if (maxWidth < FullHintMinWidth) stringResource(R.string.dejavu_search_tabs_short) else hint
            val rest = restText?.takeUnless { focused }
            if (value.isEmpty()) {
                Text(
                    text = rest ?: shownHint,
                    style = textStyle,
                    color = if (rest != null) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The field itself carries the hint for screen readers. Colors run through the loading page's host.
                    modifier = Modifier.clearAndSetSemantics {}.then(
                        if (rest != null) Modifier.loadingColors(loading) else Modifier,
                    ),
                )
            }
            BasicTextField(
                value = shown,
                onValueChange = {
                    field = it
                    if (it.text != value) onValueChange(it.text)
                },
                singleLine = true,
                textStyle = textStyle.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onSubmit(false) }),
                modifier = Modifier
                    .fillMaxWidth()
                    // The shown page's host is read out too, as its text is cleared from the semantics above.
                    .semantics { contentDescription = rest?.let { "$it, $hint" } ?: hint }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> onSubmit(event.isShiftPressed)
                            // Esc drops the picked engine first, then empties the field.
                            Key.Escape -> if (engine != null) onRemoveEngine() else onClear()
                            Key.DirectionDown -> onMoveSelection(true)
                            Key.DirectionUp -> onMoveSelection(false)
                            Key.Tab -> return@onPreviewKeyEvent onTab()
                            Key.Backspace -> if (engine != null && value.isEmpty()) {
                                onRemoveEngine()
                            } else {
                                return@onPreviewKeyEvent false
                            }
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    },
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = onClearText) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_cross_24),
                    contentDescription = stringResource(R.string.dejavu_sidebar_clear),
                    tint = scheme.onSurfaceVariant,
                )
            }
        }
    }
}
