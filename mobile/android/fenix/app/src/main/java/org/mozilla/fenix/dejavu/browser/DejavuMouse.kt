/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.toolbar.ScrollableToolbar
import mozilla.components.feature.contextmenu.R as contextMenuR
import mozilla.components.lib.state.ext.flowScoped
import org.json.JSONObject
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.snackbar.FenixSnackbarDelegate

/**
 * Mouse support the engine lacks: a middle-click on a link opens it in a background tab, and hovering a link shows its
 * address in a pill at the bottom-left of the page. A built-in extension reports both. Touch input is ignored.
 */
object DejavuMouse {
    private const val ID = "dejavu-mouse@dejavu.app"
    private const val URL = "resource://android/assets/extensions/dejavu_mouse/"
    private const val NAME = "dejavuMouse"

    private var onHover: ((String) -> Unit)? = null
    private var onOpen: ((String, EngineSession?) -> Unit)? = null
    private var installed = false

    fun install(fragment: Fragment, browserLayout: CoordinatorLayout, snackbarContainer: View) {
        val components = fragment.requireComponents
        if (!components.settings.isMouseSupportEnabled) return
        val context = fragment.requireContext()
        val density = context.resources.displayMetrics.density
        val pill = TextView(context).apply {
            visibility = View.GONE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xE6202020.toInt())
                cornerRadius = 8 * density
            }
            layoutParams = CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins((8 * density).toInt(), 0, 0, (16 * density).toInt())
            }
        }
        browserLayout.addView(pill)

        fragment.viewLifecycleOwner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    onHover = { url ->
                        pill.text = url
                        pill.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                            bottomMargin = browserLayout.bottomToolbarHeight() + (16 * density).toInt()
                        }
                        pill.visibility = if (url.isEmpty()) View.GONE else View.VISIBLE
                    }
                    onOpen = { url, source -> openInBackground(fragment, snackbarContainer, url, source) }
                }

                override fun onStop(owner: LifecycleOwner) {
                    onHover = null
                    onOpen = null
                    pill.visibility = View.GONE
                }

                override fun onDestroy(owner: LifecycleOwner) = browserLayout.removeView(pill)
            },
        )

        if (installed) return
        installed = true
        val store = components.core.store
        components.core.engine.installBuiltInWebExtension(
            ID,
            URL,
            onSuccess = { extension ->
                // Each page reports to the handler of its own session, so a click is known to come from its tab. Only a
                // change in the engine sessions of the tabs passes, compared in place, so the many other updates of a
                // loading page cost one walk over the tabs and nothing else.
                store.flowScoped(dispatcher = Dispatchers.Main) { flow ->
                    flow.map { state -> state.tabs }
                        .distinctUntilChanged { old, new -> haveSameSessions(old, new) }
                        .collect { tabs ->
                            tabs.mapNotNull { it.engineState.engineSession }
                                .filterNot { extension.hasContentMessageHandler(it, NAME) }
                                .forEach { extension.registerContentMessageHandler(it, NAME, handler) }
                        }
                }
            },
            onError = { installed = false },
        )
    }

    /** Whether [old] and [new] have the same engine sessions in the same order. */
    private fun haveSameSessions(old: List<TabSessionState>, new: List<TabSessionState>): Boolean =
        old === new || (
            old.size == new.size &&
                old.indices.all { old[it].engineState.engineSession === new[it].engineState.engineSession }
            )

    /** How far the visible toolbars at the bottom reach into [this], as they are shown right now. */
    private fun CoordinatorLayout.bottomToolbarHeight(): Int = children
        .filter { it is ScrollableToolbar && it.isVisible && it.top >= height / 2 }
        .maxOfOrNull { (height - it.top - it.translationY.toInt()).coerceAtLeast(0) } ?: 0

    private val handler = object : MessageHandler {
        override fun onMessage(message: Any, source: EngineSession?): Any? {
            val json = message as? JSONObject ?: return null
            val url = json.optString("url")
            when (json.optString("type")) {
                "hover" -> onHover?.invoke(url.takeIf(::isOpenableLink).orEmpty())
                "open" -> if (isOpenableLink(url)) onOpen?.invoke(url, source)
            }
            return null
        }
    }

    /** Opens [url] next to the tab whose page, shown by [source], was clicked: the split pane may show another tab. */
    private fun openInBackground(fragment: Fragment, snackbarContainer: View, url: String, source: EngineSession?) {
        val components = fragment.requireComponents
        val state = components.core.store.state
        val tab = state.tabs.firstOrNull { source != null && it.engineState.engineSession === source } ?: return
        val tabId = components.useCases.tabsUseCases.addTab(
            url = url,
            selectTab = false,
            startLoading = true,
            parentId = tab.id,
            contextId = tab.contextId,
            private = tab.content.private,
            textDirectiveUserActivation = true,
        )
        FenixSnackbarDelegate(snackbarContainer).show(
            snackBarParentView = snackbarContainer,
            text = fragment.getString(contextMenuR.string.mozac_feature_contextmenu_snackbar_new_tab_opened),
            duration = Snackbar.LENGTH_LONG,
            action = fragment.getString(contextMenuR.string.mozac_feature_contextmenu_snackbar_action_switch),
            listener = { components.useCases.tabsUseCases.selectTab(tabId) },
        )
    }
}

/** Schemes a page may open in a new tab with a middle-click: web pages only, never local or privileged ones. */
private val OPENABLE_SCHEMES = setOf("http", "https")

/** Whether [url], reported by a page, is a web address that a middle-click may open. */
internal fun isOpenableLink(url: String): Boolean {
    val scheme = url.substringBefore(':', missingDelimiterValue = "")
    return scheme.lowercase() in OPENABLE_SCHEMES && url.startsWith("$scheme://") && url.length > scheme.length + 3
}
