/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.view.children
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import mozilla.components.concept.engine.EngineView
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoView

private const val CAPTURE_TIMEOUT_MS = 300L

/**
 * A picture of the page of the tab with [tabId].
 *
 * @property bitmap The page, shown as wide as the window.
 * @property offset Where the page was shown in the window.
 * @property tabId The tab showing the page.
 * @property edgeColor The color that showed above the page, see [colorAbovePage].
 */
internal class PageShot(val bitmap: ImageBitmap, val offset: Offset, val tabId: String, val edgeColor: Color)

/**
 * The color that showed above a page whose top edge is [edgeColor]: that color, as the bars take it on, or the app's
 * own while the user turned matching the page color off in Customize, see [PageTint].
 */
internal fun Context.colorAbovePage(edgeColor: Int): Color = Color(
    if (DejavuSettings.peek()?.matchPageColor?.value == false) {
        ContextCompat.getColor(this, R.color.fx_mobile_surface)
    } else {
        edgeColor
    },
)

/** Pictures of the page shown in the browser, for the screens that open over it or take its place. */
internal object PagePictures {
    private var engineView: WeakReference<EngineView>? = null
    private var capturing = false

    /** Lets the page of [view] be pictured while it is on screen. */
    fun attach(view: EngineView) {
        engineView = WeakReference(view)
    }

    /** The view showing the page, while it is on screen. */
    val shownView: View?
        get() = engineView?.get()?.asView()?.takeIf { it.isAttachedToWindow }

    /** The GeckoView drawing the page, while it is on screen. */
    val shownGeckoView: GeckoView?
        get() = (shownView as? ViewGroup)?.children?.firstOrNull { it is GeckoView } as? GeckoView

    /** Whether a picture is being taken. */
    val isCapturing: Boolean
        get() = capturing

    /**
     * Pictures the page of the tab with [tabId] as it is shown now, then calls [then] with the picture, or with `null`
     * when no page is on screen or the picture took longer than [CAPTURE_TIMEOUT_MS]. Asked again while a picture is
     * being taken, it calls nothing.
     */
    fun capture(tabId: String, then: (PageShot?) -> Unit) {
        val engineView = engineView?.get()
        val view = engineView?.asView()
        if (view == null || !view.isAttachedToWindow) {
            then(null)
            return
        }
        if (capturing) return
        capturing = true
        val location = IntArray(2).also(view::getLocationInWindow)
        var done = false
        fun finish(shot: PageShot?) {
            if (done) return
            done = true
            capturing = false
            then(shot)
        }
        val timeout = Runnable { finish(null) }
        view.postDelayed(timeout, CAPTURE_TIMEOUT_MS)
        runCatching {
            engineView.captureThumbnail { bitmap ->
                view.removeCallbacks(timeout)
                // A picture coming after the timeout would change what is shown by then at once, so it is dropped.
                finish(
                    bitmap?.let {
                        val offset = Offset(location[0].toFloat(), location[1].toFloat())
                        PageShot(it.asImageBitmap(), offset, tabId, view.context.colorAbovePage(it.topEdgeColor()))
                    },
                )
            }
        }.onFailure {
            view.removeCallbacks(timeout)
            finish(null)
        }
    }
}

/** The value of [this] result once it completes, or `null` when it fails. */
internal suspend fun <T> GeckoResult<T>.valueOrNull(): T? = suspendCancellableCoroutine { continuation ->
    then(
        { value ->
            continuation.resume(value)
            GeckoResult<Void>()
        },
        {
            continuation.resume(null)
            GeckoResult<Void>()
        },
    )
}
