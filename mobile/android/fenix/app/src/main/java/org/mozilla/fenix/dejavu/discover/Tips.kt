/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.discover

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.preference.SwitchPreferenceCompat
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.settings.DejavuSettings

/** How far the finger of the tip about a bar's menu swipes the bar. */
internal val MENU_SWIPE = 22.dp

/** The screen a tip shows on. */
internal enum class TipScreen { HOME, PAGE }

/**
 * A tip about a gesture that nothing on the screen shows, next to the place it works on. Tips show one at a time, in
 * this order, each once its place is in view, see [Tips].
 *
 * @property key Stable identifier used to remember that the tip was seen.
 * @property text What the tip says, unless its place says otherwise.
 */
internal enum class Tip(val key: String, @param:StringRes val text: Int, val screen: TipScreen = TipScreen.HOME) {
    WORKSPACE_NAME("workspace_name", R.string.dejavu_tip_workspace_name),
    NEW_TAB("new_tab", R.string.dejavu_tip_new_tab),
    PIN("pin", R.string.dejavu_tip_pin),
    FOLDER("folder", R.string.dejavu_tip_folder),
    CLEAR("clear", R.string.dejavu_tip_clear),
    ESSENTIAL_ADD("essential_add", R.string.dejavu_tip_essential_add),
    ESSENTIAL_HOLD("essential_hold", R.string.dejavu_tip_essential_hold),
    WORKSPACES("workspaces", R.string.dejavu_tip_workspaces),
    SPLIT("split", R.string.dejavu_tip_split),
    PAGE_MENU("page_menu", R.string.dejavu_tip_menu_up, TipScreen.PAGE),
}

/** The gesture a ghost finger shows on the place of a tip, over and over while the tip shows. */
internal sealed interface TipGesture {
    data object Tap : TipGesture

    data object Hold : TipGesture

    /** Holding, then dragging [dy] down, or up when negative, or up to the top of the screen when [toTop]. */
    data class Drag(val dy: Dp = 0.dp, val toTop: Boolean = false) : TipGesture

    /** A swipe of [dx] and [dy], over the page next to the place when [overPage], rather than on the place itself. */
    data class Swipe(val dx: Dp = 0.dp, val dy: Dp = 0.dp, val overPage: Boolean = false) : TipGesture
}

/**
 * The place a tip points at, as it was laid out last.
 *
 * @property text What the tip says there.
 * @property at Where across the place the finger touches it, from 0 at its left to 1 at its right.
 * @property bounds The part of the place that shows, in the window.
 * @property drawsItself Whether the place draws its ring and finger itself, rather than the screen's [TipHost].
 */
internal class TipTarget(
    val tip: Tip,
    val gesture: TipGesture,
    @param:StringRes val text: Int,
    val at: Float,
    val bounds: Rect,
    private val width: Int,
    private val height: Int,
    val drawsItself: Boolean,
) {
    /** Whether the place shows nearly whole, rather than cut off by the edge of a list or of the screen. */
    val inView: Boolean
        get() = !bounds.isEmpty && bounds.width >= width * IN_VIEW_SHARE && bounds.height >= height * IN_VIEW_SHARE
}

/**
 * The tips about gestures nothing on the screen shows. Places that a tip points at say so with `tipTarget`, and each
 * screen's [TipHost] shows the first tip not seen yet whose place is in view, after the screen has stayed still for a
 * moment. A tip counts as seen once it closes: by Got it, by a touch anywhere else, or because the user did what it
 * tells, which [done] reports also while it is not showing. Tips can be turned off, and on again in the settings.
 */
internal object Tips {
    private val targets = mutableStateMapOf<Any, TipTarget>()

    // The tips closed since Dejavu started, which wait to be among the seen ones.
    private val closed = mutableStateListOf<Tip>()
    private var closedAt = 0L

    /** The tip showing now, or `null`. */
    var shown by mutableStateOf<Tip?>(null)
        private set

    /** The place of [owner] for [tip], laid out at [coordinates]. */
    @Suppress("LongParameterList")
    fun place(
        owner: Any,
        tip: Tip,
        gesture: TipGesture,
        @StringRes text: Int,
        at: Float,
        drawsItself: Boolean,
        coordinates: LayoutCoordinates,
    ) {
        val target = TipTarget(
            tip = tip,
            gesture = gesture,
            text = text,
            at = at,
            bounds = coordinates.boundsInWindow(),
            width = coordinates.size.width,
            height = coordinates.size.height,
            drawsItself = drawsItself,
        )
        place(owner, target)
    }

    /** The place of [owner], as [target] has it. */
    fun place(owner: Any, target: TipTarget) {
        val old = targets[owner]
        if (old == null || old.bounds != target.bounds || old.tip != target.tip || old.text != target.text) {
            targets[owner] = target
        }
    }

    /** Forgets the place of [owner], which left the screen. */
    fun remove(owner: Any) {
        targets.remove(owner)
    }

    /** The place of [tip] that is in view, if any. */
    fun targetOf(tip: Tip): TipTarget? = targets.values.firstOrNull { it.tip == tip && it.inView }

    /**
     * The tip to show on [screen], where the tips with keys among [seen] were seen: the one showing, while its place
     * stays in view, or else the first one due whose place is in view.
     */
    fun pick(screen: TipScreen, seen: Set<String>): Tip? {
        fun due(tip: Tip) = tip.screen == screen && tip.key !in seen && tip !in closed && targetOf(tip) != null
        return shown?.takeIf(::due) ?: Tip.entries.firstOrNull(::due)
    }

    /** Shows [tip], or none. */
    fun show(tip: Tip?) {
        shown = tip
    }

    /** Hides the tip showing on [screen] without counting it as seen, as when the screen gets busy. */
    fun hide(screen: TipScreen) {
        if (shown?.screen == screen) shown = null
    }

    /** How long the next tip still waits after the last one closed, so tips do not come one right after another. */
    fun pauseLeft(): Long = (closedAt + NEXT_TIP_PAUSE_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)

    /** Hides [tip], closed before it could be read, for a while; it shows again later. */
    fun putOff(tip: Tip) {
        if (shown != tip) return
        shown = null
        closedAt = SystemClock.uptimeMillis()
    }

    /** Counts [tip] as seen, as when the user did what it tells or closed it, and closes it if it shows. */
    fun done(tip: Tip) {
        DejavuSettings.peek()?.markTipSeen(tip.key)
        if (tip !in closed) closed.add(tip)
        if (shown == tip) {
            shown = null
            closedAt = SystemClock.uptimeMillis()
        }
    }

    /** Turns the tips off, or on again, which shows every tip once more. */
    fun setEnabled(settings: DejavuSettings, enabled: Boolean) {
        settings.setTipsEnabled(enabled)
        if (enabled) closed.clear() else shown = null
    }

    /** Makes [preference] the switch that turns the tips on and off. */
    fun bindSwitch(preference: SwitchPreferenceCompat) {
        val settings = DejavuSettings.get(preference.context)
        preference.isChecked = settings.tipsEnabled.value
        preference.setOnPreferenceChangeListener { _, value ->
            setEnabled(settings, value == true)
            true
        }
    }

    private const val NEXT_TIP_PAUSE_MS = 6_000L
}

// How much of a place has to show for its tip to point at it.
private const val IN_VIEW_SHARE = 0.9f
