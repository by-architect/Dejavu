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
import androidx.preference.Preference
import kotlin.math.roundToInt
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.menu.MoreMenuEntry
import org.mozilla.fenix.dejavu.menu.MoreMenuItem
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.ext.components

/**
 * What the newest version of Dejavu brought, marked with an orange dot until the user looked at it: on the thing
 * itself, and on every step that leads to it, from the settings button of the home screen and the menu through the
 * settings. When a version brings new things, they take the place of these.
 *
 * @property key Stable identifier used to remember that the user looked at it.
 */
internal enum class NewThing(val key: String) {
    /** The Clear data and Clear data and quit buttons for the menu and the bars, found in the menu's settings. */
    CLEAR_DATA_ENTRIES("clear_data_entries"),

    /** What Clear data deletes, chosen in the settings of deleting browsing data on quit. */
    CLEAR_DATA_CHOICES("clear_data_choices"),
}

/** Which [NewThing]s the user has not looked at yet, see [NewThing]. */
internal object NewThings {
    /** Whether the user has not looked at [thing] yet. */
    fun isNew(context: Context, thing: NewThing): Boolean = thing.key !in settings(context).seenNewThings.value

    /** Remembers that the user looked at [thing], which loses its dot everywhere. */
    fun seen(context: Context, thing: NewThing) = settings(context).markNewThingSeen(thing.key)

    /** Shows the orange dot after the title of [preference] while [thing] is new, the way to it or the thing itself. */
    fun mark(preference: Preference, thing: NewThing) = preference.showNewDot(isNew(preference.context, thing))

    private fun settings(context: Context): DejavuSettings =
        context.components.strictMode.allowViolation(StrictMode::allowThreadDiskReads) { DejavuSettings.get(context) }
}

/** The [NewThing]s the user has not looked at yet, following as they look. */
@Composable
internal fun rememberUnseenNewThings(): Set<NewThing> {
    val context = LocalContext.current
    val settings = remember {
        context.components.strictMode.allowViolation(StrictMode::allowThreadDiskReads) { DejavuSettings.get(context) }
    }
    val seen by settings.seenNewThings.collectAsState()
    return remember(seen) { NewThing.entries.filterTo(mutableSetOf()) { it.key !in seen } }
}

/**
 * Whether this button of the menu or the bars has the orange dot, with the [unseen] new things not looked at: Clear
 * data while it is new, and Settings, which [leadsToSettings] where tapping it opens the settings, while anything there
 * is.
 */
internal fun MoreMenuEntry.isNewAmong(unseen: Set<NewThing>, leadsToSettings: Boolean = true): Boolean =
    when ((this as? MoreMenuEntry.BuiltIn)?.item) {
        MoreMenuItem.CLEAR_DATA, MoreMenuItem.CLEAR_DATA_AND_QUIT -> NewThing.CLEAR_DATA_ENTRIES in unseen
        MoreMenuItem.SETTINGS -> leadsToSettings && unseen.isNotEmpty()
        else -> false
    }

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
