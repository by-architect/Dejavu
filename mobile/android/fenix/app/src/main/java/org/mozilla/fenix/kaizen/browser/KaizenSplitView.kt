/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.browser

import android.content.Context
import android.os.Looper
import android.os.StrictMode
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import mozilla.components.concept.toolbar.ScrollableToolbar
import mozilla.components.feature.contextmenu.ContextMenuCandidate
import mozilla.components.feature.contextmenu.ContextMenuFeature
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import mozilla.components.lib.state.ext.flowScoped
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.compose.Favicon
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.kaizen.home.titleOf
import org.mozilla.fenix.kaizen.workspaces.WorkspaceRepository
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState
import org.mozilla.fenix.theme.FirefoxTheme
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import kotlin.math.roundToInt
import mozilla.components.ui.icons.R as iconsR

/**
 * Shows the other tab of a split view in a pane below the page of the selected tab, like Zen's split views. The
 * selected tab keeps the toolbar and every browser feature; the pane's bar shows its tab on top instead, closes the
 * split, and resizes the pane when dragged. Custom tabs are left alone.
 */
fun Fragment.installKaizenSplitView(
    browserLayout: CoordinatorLayout,
    swipeRefresh: View,
    mainEngineView: EngineView,
    customTabSessionId: String?,
    contextMenuCandidates: () -> List<ContextMenuCandidate>,
) {
    if (customTabSessionId != null) return
    val components = requireComponents
    val repository = components.strictMode.allowViolation(StrictMode::allowThreadDiskReads) {
        WorkspaceRepository.get(requireContext())
    }
    val pane = SplitPaneFeature(this, components, repository, browserLayout, swipeRefresh, mainEngineView, contextMenuCandidates)
    viewLifecycleOwner.lifecycle.addObserver(
        object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = pane.start()

            override fun onStop(owner: LifecycleOwner) = pane.stop()
        },
    )
}

/** The tab shown in the split pane. */
private data class PaneTarget(
    val tabId: String,
    val engineSession: EngineSession?,
    val title: String,
    val url: String,
)

/**
 * A page can only be shown by one view at a time, so the pane must let go of a tab before the browser shows it on
 * top, and wait for the browser to let go of the tab it showed before. [SplitViewMiddleware] tells the pane about
 * such changes while they happen, before the browser reacts to them.
 */
@Suppress("TooManyFunctions")
internal class SplitPaneFeature(
    private val fragment: Fragment,
    private val components: Components,
    private val repository: WorkspaceRepository,
    private val browserLayout: CoordinatorLayout,
    private val swipeRefresh: View,
    private val mainEngineView: EngineView,
    private val contextMenuCandidates: () -> List<ContextMenuCandidate>,
) {
    private val store = components.core.store
    private var scope: CoroutineScope? = null
    private var pane: SplitPane? = null
    private var target by mutableStateOf<PaneTarget?>(null)
    private var shownTabId: String? = null
    private var shownSession: EngineSession? = null
    private var contextMenu: ContextMenuFeature? = null
    private var baseBottomMargin = 0
    private var appliedBottomMargin: Int? = null
    private var previousSelectedTabId: String? = null
    private var mainSessionAtSwitch: GeckoSession? = null
    private val retryBind = Runnable { target?.let(::bind) }
    private val layoutListener = View.OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
        if (bottom - top != oldBottom - oldTop) browserLayout.post { applyLayout() }
    }

    fun start() {
        SplitPanes.active = this
        previousSelectedTabId = store.state.selectedTabId
        scope = store.flowScoped(dispatcher = Dispatchers.Main) { flow ->
            flow.combine(repository.state) { state, workspaces -> targetOf(state, workspaces) }
                .distinctUntilChanged()
                .collect { update(it) }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        if (SplitPanes.active === this) SplitPanes.active = null
        hide()
    }

    /** Called while the selected tab changes from [previous] to [selected], before the browser shows it. */
    fun onSelectionChanging(previous: String?, selected: String?) {
        previousSelectedTabId = previous
        mainSessionAtSwitch = mainEngineView.asView().findGeckoView()?.session
        if (selected != null && selected == shownTabId) releaseEngine()
    }

    /** Called before the tabs [tabIds] are closed or unloaded. */
    fun onTabsLeaving(tabIds: Collection<String>) {
        if (shownTabId in tabIds) releaseEngine()
    }

    private fun targetOf(state: BrowserState, workspaces: WorkspaceState): PaneTarget? {
        val selected = state.selectedTab ?: return null
        if (selected.content.private || selected.content.fullScreen) return null
        val partnerId = workspaces.splitOf(selected.id)?.tabIds?.firstOrNull { it != selected.id } ?: return null
        val partner = state.findTab(partnerId)?.takeIf { !it.content.private } ?: return null
        return PaneTarget(partner.id, partner.engineState.engineSession, workspaces.titleOf(partner), partner.content.url)
    }

    private fun update(next: PaneTarget?) {
        target = next
        if (next == null) {
            hide()
        } else {
            show()
            bind(next)
        }
    }

    private fun bind(next: PaneTarget) {
        val pane = pane ?: return
        browserLayout.removeCallbacks(retryBind)
        val session = next.engineSession
        if (next.tabId == shownTabId && session === shownSession) return
        if (session == null) {
            releaseEngine()
            store.dispatch(EngineAction.CreateEngineSessionAction(next.tabId))
            return
        }
        val mainSession = mainEngineView.asView().findGeckoView()?.session
        if (next.tabId == previousSelectedTabId && mainSession != null && mainSession === mainSessionAtSwitch) {
            // The browser still shows this tab on top; try again once it shows the newly selected one.
            browserLayout.postDelayed(retryBind, BIND_RETRY_MS)
            return
        }
        releaseEngine()
        pane.engineView.render(session)
        shownTabId = next.tabId
        shownSession = session
        contextMenu = ContextMenuFeature(
            fragmentManager = fragment.parentFragmentManager,
            store = store,
            candidates = contextMenuCandidates(),
            engineView = pane.engineView,
            useCases = components.useCases.contextMenuUseCases,
            tabId = next.tabId,
        ).also { it.start() }
    }

    private fun releaseEngine() {
        contextMenu?.stop()
        contextMenu = null
        if (shownTabId != null) pane?.engineView?.release()
        shownTabId = null
        shownSession = null
    }

    private fun show() {
        if (pane != null) return
        val context = browserLayout.context
        val engineView = components.core.engine.createView(context)
        engineView.asView().findGeckoView()?.let { geckoView ->
            // Drawn as a normal view, so the pane covers the page on top where their areas meet while the toolbar
            // slides, and its scrolling does not move the toolbar.
            geckoView.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
            ViewCompat.setNestedScrollingEnabled(geckoView, false)
        }
        val bar = ComposeView(context).apply {
            setContent {
                FirefoxTheme {
                    target?.let { current ->
                        SplitPaneBar(
                            title = current.title,
                            url = current.url,
                            onShowOnTop = { components.useCases.tabsUseCases.selectTab(current.tabId) },
                            onClose = { repository.unsplit(setOf(current.tabId)) },
                            onDragTo = ::resizeTo,
                        )
                    }
                }
            }
        }
        val newPane = SplitPane(context, engineView, bar)
        browserLayout.addView(
            newPane,
            CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { gravity = Gravity.BOTTOM },
        )
        pane = newPane
        baseBottomMargin = swipeRefresh.marginParams.bottomMargin
        browserLayout.addOnLayoutChangeListener(layoutListener)
        applyLayout()
    }

    private fun hide() {
        val current = pane ?: return
        browserLayout.removeCallbacks(retryBind)
        releaseEngine()
        browserLayout.removeOnLayoutChangeListener(layoutListener)
        browserLayout.removeView(current)
        pane = null
        val params = swipeRefresh.marginParams
        if (appliedBottomMargin != null && params.bottomMargin == appliedBottomMargin) {
            params.bottomMargin = baseBottomMargin
            swipeRefresh.layoutParams = params
        }
        appliedBottomMargin = null
    }

    /** Height left for the two pages between the toolbars, and the height of the toolbars at the bottom. */
    private fun pageArea(): Pair<Int, Int> {
        val height = browserLayout.height
        val toolbars = browserLayout.children.filter { it is ScrollableToolbar && it.isVisible }.toList()
        val topBars = toolbars.filter { it.top < height / 2 }.maxOfOrNull { it.bottom } ?: 0
        val bottomBars = toolbars.filter { it.top >= height / 2 }.minOfOrNull { it.top }?.let { height - it } ?: 0
        return (height - topBars - bottomBars) to bottomBars
    }

    private fun applyLayout() {
        val pane = pane ?: return
        val (available, bottomBars) = pageArea()
        if (available <= 0) return
        val paneHeight = (available * (1 - mainFraction)).roundToInt()

        val paneParams = pane.layoutParams as CoordinatorLayout.LayoutParams
        if (paneParams.height != paneHeight || paneParams.bottomMargin != bottomBars) {
            paneParams.height = paneHeight
            paneParams.bottomMargin = bottomBars
            pane.layoutParams = paneParams
        }

        val params = swipeRefresh.marginParams
        // Fenix may have set its own margin since, which then becomes the one to restore.
        if (appliedBottomMargin != null && params.bottomMargin != appliedBottomMargin) baseBottomMargin = params.bottomMargin
        val margin = baseBottomMargin + paneHeight
        if (params.bottomMargin != margin) {
            params.bottomMargin = margin
            swipeRefresh.layoutParams = params
        }
        appliedBottomMargin = margin
    }

    /** Resizes the pane so that its bar starts at [barTop], in window coordinates. */
    private fun resizeTo(barTop: Float) {
        val (available, bottomBars) = pageArea()
        if (available <= 0) return
        val location = IntArray(2)
        browserLayout.getLocationInWindow(location)
        val paneBottom = location[1] + browserLayout.height - bottomBars
        mainFraction = (1 - (paneBottom - barTop) / available).coerceIn(MIN_MAIN_FRACTION, MAX_MAIN_FRACTION)
        applyLayout()
    }

    private val View.marginParams: ViewGroup.MarginLayoutParams
        get() = layoutParams as ViewGroup.MarginLayoutParams

    companion object {
        private const val BIND_RETRY_MS = 50L
        private const val MIN_MAIN_FRACTION = 0.25f
        private const val MAX_MAIN_FRACTION = 0.8f

        /** Share of the page area the tab on top gets, kept while the app runs. */
        private var mainFraction = 0.5f
    }
}

/** The split pane of the browser screen that is shown, if any. */
internal object SplitPanes {
    @Volatile
    var active: SplitPaneFeature? = null
}

/**
 * Tells the split pane about tab changes while they happen, before the browser reacts to them: see
 * [SplitPaneFeature].
 */
internal class SplitViewMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        val pane = SplitPanes.active
        if (pane == null || Looper.myLooper() != Looper.getMainLooper()) {
            next(action)
            return
        }
        when (action) {
            is TabListAction.RemoveTabAction -> pane.onTabsLeaving(listOf(action.tabId))
            is TabListAction.RemoveTabsAction -> pane.onTabsLeaving(action.tabIds)
            is TabListAction.RemoveAllTabsAction, TabListAction.RemoveAllNormalTabsAction ->
                pane.onTabsLeaving(store.state.tabs.map { it.id })
            is EngineAction.SuspendEngineSessionAction -> pane.onTabsLeaving(listOf(action.tabId))
            is EngineAction.KillEngineSessionAction -> pane.onTabsLeaving(listOf(action.tabId))
            else -> Unit
        }
        val before = store.state.selectedTabId
        next(action)
        val after = store.state.selectedTabId
        if (before != after) pane.onSelectionChanging(before, after)
    }
}

/** The bar above the page and the page of the tab in the split pane. */
private class SplitPane(context: Context, val engineView: EngineView, bar: View) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(engineView.asView(), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }
}

private fun View.findGeckoView(): GeckoView? = when (this) {
    is GeckoView -> this
    is ViewGroup -> children.firstNotNullOfOrNull { it.findGeckoView() }
    else -> null
}

/**
 * The bar of the split pane: tapping it shows its tab on top, dragging it resizes the pane. The bar moves with the
 * finger, so the drag is followed in window coordinates.
 *
 * @param onDragTo Called with where the top of the bar should be, in window coordinates.
 */
@Composable
private fun SplitPaneBar(
    title: String,
    url: String,
    onShowOnTop: () -> Unit,
    onClose: () -> Unit,
    onDragTo: (Float) -> Unit,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var grabOffset by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .onGloballyPositioned { coordinates = it }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { grabOffset = it.y },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        coordinates?.takeIf { it.isAttached }?.let { onDragTo(it.localToWindow(change.position).y - grabOffset) }
                    },
                )
            }
            .clickable(onClickLabel = stringResource(R.string.kaizen_split_show_on_top), onClick = onShowOnTop),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp),
        ) {
            Favicon(url = url, size = 16.dp, shape = CircleShape)
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onShowOnTop) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_chevron_up_24),
                    contentDescription = stringResource(R.string.kaizen_split_show_on_top),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_cross_24),
                    contentDescription = stringResource(R.string.kaizen_split_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
