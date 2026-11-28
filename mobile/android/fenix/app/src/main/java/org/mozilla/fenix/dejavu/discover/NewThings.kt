/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.discover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.StrictMode
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.features.FeatureTour
import org.mozilla.fenix.dejavu.features.dataStartVersion
import org.mozilla.fenix.dejavu.menu.MoreMenuEntry
import org.mozilla.fenix.dejavu.menu.MoreMenuItem
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.utils.isLargeScreenSize

/**
 * What the versions of Dejavu brought, marked with an orange dot until the user looked at it: on the thing itself, and
 * on every step that leads to it, from the settings button of the home screen and the menu through the settings.
 *
 * Only an update brings new things: a thing is new to a Dejavu whose data began with a version before the one that
 * brought it, so a fresh install has none, and an update has the ones of the versions it went past.
 *
 * @property key Stable identifier used to remember that the user looked at it.
 * @property version The version that brought it, like "4.1".
 * @property inSettings Whether it is found through the settings.
 * @property largeScreens Whether it only does something on large screens, like tablets, the only ones it is new on.
 */
internal enum class NewThing(
    val key: String,
    val version: String,
    val inSettings: Boolean = true,
    val largeScreens: Boolean = false,
) {
    /** The app icons, in Customize. */
    APP_ICON("app_icon", "4.0"),

    /** The accent color, in Customize. */
    ACCENT_COLOR("accent_color", "4.0"),

    /** The styles of icons, in Customize. */
    ICON_STYLE("icon_style", "4.0"),

    /** The switches that turn the animations off, in Customize. */
    ANIMATIONS("animations", "4.0"),

    /** The Full screen button for the menu and the bars, found in the menu's settings. */
    FULL_SCREEN("full_screen", "4.0"),

    /** The full page screenshot button for the menu and the bars, found in the menu's settings. */
    SCREENSHOT("screenshot", "4.0"),

    /** The essentials hidden on this phone, under Tabs, workspaces and containers. */
    ESSENTIALS("essentials", "4.0"),

    /** When unlocked workspaces lock again, under Privacy and security. */
    WORKSPACE_LOCK("workspace_lock", "4.0"),

    /** The private browsing settings, back under Privacy and security: the lock of private tabs, and screenshots. */
    PRIVATE_BROWSING("private_browsing", "4.0"),

    /**
     * Lock this workspace and Don't save history, in the dialog that edits a workspace, the private one too, found
     * through the menu of each workspace on the home screen rather than in the settings.
     */
    WORKSPACE_PRIVACY("workspace_privacy", "4.0", inSettings = false),

    /** The keys of the shortcuts for a hardware keyboard, in Customize. */
    KEYBOARD_SHORTCUTS("keyboard_shortcuts", "4.1"),

    /** The tab sidebar and its look, in Customize's bars. */
    TAB_SIDEBAR("tab_sidebar", "4.1", largeScreens = true),

    /**
     * The new chapters of What's new, on top of the settings, which show what has no dot of its own, see
     * [org.mozilla.fenix.dejavu.features.FeatureTour]. A version that adds a chapter gives it its key and version.
     */
    WHATS_NEW("whats_new_4_1", "4.1"),
    ;

    companion object {
        /** The new things found in Customize. */
        val CUSTOMIZE = setOf(
            APP_ICON,
            ACCENT_COLOR,
            ICON_STYLE,
            ANIMATIONS,
            FULL_SCREEN,
            SCREENSHOT,
            KEYBOARD_SHORTCUTS,
            TAB_SIDEBAR,
        )

        /** The new buttons for the menu and the bars, which wait in the menu's settings. */
        val MENU_BUTTONS = setOf(FULL_SCREEN, SCREENSHOT)

        /**
         * The entries of the Settings screen that lead to new things, by the key of their setting, with the things each
         * leads to. On wide windows Customize's bars and the menu's settings are entries of their categories too, see
         * [org.mozilla.fenix.dejavu.settings.SettingsCategories].
         */
        val SETTINGS_ENTRIES: List<Pair<Int, Set<NewThing>>> = listOf(
            R.string.pref_key_dejavu_whats_new to setOf(WHATS_NEW),
            R.string.pref_key_customize to CUSTOMIZE,
            R.string.pref_key_dejavu_tabs_workspaces to setOf(ESSENTIALS),
            R.string.pref_key_dejavu_workspace_lock to setOf(WORKSPACE_LOCK),
            R.string.pref_key_private_browsing to setOf(PRIVATE_BROWSING),
            R.string.pref_key_dejavu_bars to setOf(TAB_SIDEBAR),
            R.string.pref_key_dejavu_more_menu to MENU_BUTTONS,
        )

        /** The new things the entry of the Settings screen with the key [key] leads to, see [SETTINGS_ENTRIES]. */
        fun ledToBy(@StringRes key: Int): Set<NewThing> =
            SETTINGS_ENTRIES.firstOrNull { it.first == key }?.second.orEmpty()
    }
}

/**
 * Whether this thing can be new to a Dejavu whose data began with [startVersion]: that version is older than the one
 * that brought it, and the screen, large when [largeScreen], is one it does something on.
 */
internal fun NewThing.cameWithUpdate(startVersion: String, largeScreen: Boolean): Boolean =
    FeatureTour.compareVersions(startVersion, version) < 0 && (!largeScreens || largeScreen)

/** Which [NewThing]s the user has not looked at yet, see [NewThing]. */
internal object NewThings {
    /** Whether [thing] is new to this Dejavu, see [NewThing], and the user has not looked at it yet. */
    fun isNew(context: Context, thing: NewThing): Boolean = isNew(context, thing, settings(context).seenNewThings.value)

    /** The [NewThing]s new to this Dejavu that the user has not looked at, of the ones whose keys are in [seen]. */
    fun unseen(context: Context, seen: Set<String>): Set<NewThing> =
        NewThing.entries.filterTo(mutableSetOf()) { isNew(context, it, seen) }

    /** Remembers that the user looked at [thing], which loses its dot everywhere. */
    fun seen(context: Context, thing: NewThing) = settings(context).markNewThingSeen(thing.key)

    /** Shows the orange dot after the title of [preference] while [thing] is new, the way to it or the thing itself. */
    fun mark(preference: Preference, thing: NewThing) = preference.showNewDot(isNew(preference.context, thing))

    /** Shows the orange dot after the title of [preference] while any of [things] is new, the way to them. */
    fun markAny(preference: Preference, things: Set<NewThing>) =
        preference.showNewDot(things.any { isNew(preference.context, it) })

    /**
     * Shows the orange dot after the title of [preference] while any of [things] is new, and takes it away as soon as
     * the user looked at them, also on a screen further on that closes only after [owner] showed again.
     */
    fun follow(preference: Preference, things: Set<NewThing>, owner: LifecycleOwner) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings(preference.context).seenNewThings.collect { seen ->
                    preference.showNewDot(things.any { isNew(preference.context, it, seen) })
                }
            }
        }
    }

    private fun isNew(context: Context, thing: NewThing, seen: Set<String>): Boolean =
        thing.key !in seen && thing.cameWithUpdate(context.dataStartVersion(), context.isLargeScreenSize())

    private fun settings(context: Context): DejavuSettings =
        context.components.strictMode.allowViolation(StrictMode::allowThreadDiskReads) { DejavuSettings.get(context) }
}

/** The [NewThing]s new to this Dejavu that the user has not looked at yet, following as they look. */
@Composable
internal fun rememberUnseenNewThings(): Set<NewThing> {
    val context = LocalContext.current
    val settings = remember {
        context.components.strictMode.allowViolation(StrictMode::allowThreadDiskReads) { DejavuSettings.get(context) }
    }
    val seen by settings.seenNewThings.collectAsState()
    return remember(seen) { NewThings.unseen(context, seen) }
}

/**
 * Whether this button of the menu or the bars has the orange dot, with the [unseen] new things not looked at: Full
 * screen and Screenshot while they are new, and Settings, which [leadsToSettings] where tapping it opens the settings,
 * while anything there is.
 */
internal fun MoreMenuEntry.isNewAmong(unseen: Set<NewThing>, leadsToSettings: Boolean = true): Boolean =
    when ((this as? MoreMenuEntry.BuiltIn)?.item) {
        MoreMenuItem.FULL_SCREEN -> NewThing.FULL_SCREEN in unseen
        MoreMenuItem.SCREENSHOT -> NewThing.SCREENSHOT in unseen
        MoreMenuItem.SETTINGS -> leadsToSettings && unseen.inSettings()
        else -> false
    }

/** Whether any of these new things is found in the settings. */
internal fun Set<NewThing>.inSettings(): Boolean = any { it.inSettings }

/**
 * Puts the orange dot on the top end corner of this, [inset] from its edges, while [show], which screen readers read
 * out as "New".
 */
@Composable
internal fun Modifier.newDot(show: Boolean, inset: Dp = DOT_INSET): Modifier {
    if (!show) return this
    val label = stringResource(R.string.dejavu_new)
    val color = colorResource(R.color.dejavu_new_dot)
    val halo = MaterialTheme.colorScheme.surface
    return semantics { stateDescription = label }
        .drawWithContent {
            drawContent()
            val center = Offset(size.width - inset.toPx(), inset.toPx())
            drawCircle(halo, (DOT_RADIUS + DOT_HALO).toPx(), center)
            drawCircle(color, DOT_RADIUS.toPx(), center)
        }
}

/** Puts the orange dot after this text, in room it keeps for it at its end, while [show]. */
@Composable
internal fun Modifier.newDotAfterText(show: Boolean): Modifier =
    if (show) newDot(true, inset = TEXT_DOT_INSET).padding(end = TEXT_DOT_ROOM) else this

/**
 * Shows the orange dot after this preference's title, or takes it away. Screen readers read the dot as "New", which
 * the title holds under it.
 */
internal fun Preference.showNewDot(show: Boolean) {
    val current = title ?: return
    val plain = (current as? Spanned)?.let { spanned ->
        spanned.getSpans(0, spanned.length, NewDotSpan::class.java).firstOrNull()
            ?.let { spanned.subSequence(0, spanned.getSpanStart(it)) }
    } ?: current
    if (!show) {
        if (plain !== current) title = plain
        return
    }
    if (plain !== current) return
    val metrics = context.resources.displayMetrics
    val dot = NewDotSpan(
        color = ContextCompat.getColor(context, R.color.dejavu_new_dot),
        radius = DOT_RADIUS.value * metrics.density,
        gap = DOT_GAP.value * metrics.density,
    )
    val word = " " + context.getString(R.string.dejavu_new)
    title = SpannableStringBuilder(plain).append(word, dot, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

/** Draws the orange dot, [gap] after the text before it, in place of the text it covers, which screen readers read. */
private class NewDotSpan(
    private val color: Int,
    private val radius: Float,
    private val gap: Float,
) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.let { paint.getFontMetricsInt(it) }
        return (gap + radius * 2).roundToInt()
    }

    @Suppress("LongParameterList")
    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val textColor = paint.color
        paint.color = color
        val metrics = paint.fontMetrics
        canvas.drawCircle(x + gap + radius, y + (metrics.ascent + metrics.descent) / 2, radius, paint)
        paint.color = textColor
    }
}

private val DOT_RADIUS = 4.dp
private val DOT_HALO = 1.5.dp
private val DOT_INSET = 6.dp
private val DOT_GAP = 8.dp
private val TEXT_DOT_INSET = 4.dp
private val TEXT_DOT_ROOM = 14.dp
