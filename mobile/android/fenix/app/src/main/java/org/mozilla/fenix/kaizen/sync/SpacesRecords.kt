/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.sync

import mozilla.components.browser.state.state.ContainerState
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.fenix.kaizen.containers.ContainerColor
import org.mozilla.fenix.kaizen.workspaces.WorkspaceTheme
import kotlin.math.roundToInt

/** Kinds of records in Zen's "spaces" sync collection, as written by Zen's ZenSpacesSyncModel. */
internal object RecordKind {
    const val CONTAINER = "container"
    const val SPACE = "space"
    const val TAB = "tab"
    const val FOLDER = "folder"
    const val SPLIT = "split"
    const val LAYOUT = "layout"
}

/** Id of the single record holding the order of the spaces and of the essentials. */
internal const val LAYOUT_RECORD_ID = "layout"

/**
 * One decrypted record of the spaces collection: a [kind] with its [data] as Zen writes them, or a tombstone.
 * Records are never changed once built; the data of a record is copied before it is modified.
 */
internal class SpacesRecord private constructor(
    val id: String,
    val kind: String?,
    val data: JSONObject?,
    val deleted: Boolean,
) {
    /** Text that two records share exactly when they have the same content. */
    val fingerprint: String by lazy {
        if (deleted) TOMBSTONE else SyncJson.canonical(JSONObject().put("kind", kind).put("data", data))
    }

    /** Whether this is a pinned tab, essential, folder, space, container, pinned split or the layout, which are synced. */
    val isSynced: Boolean
        get() = !deleted && when (kind) {
            RecordKind.TAB, RecordKind.SPLIT -> data?.opt("pinned") != false
            RecordKind.CONTAINER, RecordKind.SPACE, RecordKind.FOLDER, RecordKind.LAYOUT -> true
            else -> false
        }

    /** Whether this is a tab that is not pinned, which Zen syncs when "Include unpinned tabs" is on. */
    val isNormalTab: Boolean
        get() = !deleted && kind == RecordKind.TAB && data?.opt("pinned") == false && data.opt("essential") != true

    fun sameAs(other: SpacesRecord?): Boolean = other != null && fingerprint == other.fingerprint

    fun toCleartext(): JSONObject = if (deleted) {
        JSONObject().put("id", id).put("deleted", true)
    } else {
        JSONObject().put("id", id).put("kind", kind).put("data", data)
    }

    companion object {
        private const val TOMBSTONE = "deleted"

        fun of(id: String, kind: String, data: JSONObject) = SpacesRecord(id, kind, data, deleted = false)

        fun tombstone(id: String) = SpacesRecord(id, kind = null, data = null, deleted = true)

        /** Reads a decrypted cleartext, or returns `null` when it is not a record of a kind this version knows. */
        fun fromCleartext(id: String, cleartext: JSONObject): SpacesRecord? {
            if (cleartext.opt("deleted") == true) return tombstone(id)
            val kind = cleartext.string("kind") ?: return null
            val data = cleartext.optJSONObject("data") ?: return null
            return of(id, kind, data)
        }

        /** Reads a record written by [toCleartext]. */
        fun fromCleartext(cleartext: JSONObject): SpacesRecord? =
            cleartext.string("id")?.let { fromCleartext(it, cleartext) }
    }
}

/** A space or folder icon as Kaizen shows it: `null` for none, Zen also writes "" for none. */
internal fun iconOf(value: Any?): String? = (value as? String)?.takeIf { it.isNotEmpty() }

/** The container color Kaizen shows for a synced color name. */
internal fun containerColorOf(value: Any?): ContainerColor = syncedColor(ContainerColor.fromKey(value as? String))

/** [color] as it is synced: the color of temporary containers is Kaizen's own, so it goes out as gray. */
internal fun syncedColor(color: ContainerColor?): ContainerColor =
    color?.takeIf { it != ContainerColor.WHITE } ?: ContainerColor.GRAY

/** The container icon Kaizen shows for a synced icon name. */
internal fun containerIconOf(value: Any?): ContainerState.Icon =
    ContainerState.Icon.entries.firstOrNull { it.icon == value } ?: ContainerState.Icon.CIRCLE

/**
 * Firefox's four default containers, which Zen syncs under well-known ids instead of per-profile ones. Kaizen creates
 * them when a synced space or tab uses one.
 */
internal enum class BuiltinContainer(val guid: String, val icon: ContainerState.Icon, val color: ContainerColor) {
    PERSONAL("builtin-1", ContainerState.Icon.FINGERPRINT, ContainerColor.BLUE),
    WORK("builtin-2", ContainerState.Icon.BRIEFCASE, ContainerColor.ORANGE),
    BANKING("builtin-3", ContainerState.Icon.DOLLAR, ContainerColor.GREEN),
    SHOPPING("builtin-4", ContainerState.Icon.CART, ContainerColor.PINK),
    ;

    companion object {
        fun of(guid: String?): BuiltinContainer? = entries.firstOrNull { it.guid == guid }
    }
}

/**
 * Converts space themes. Zen's theme is `{type: "gradient", gradientColors, opacity, texture}` where each gradient
 * color is a dot of its color picker with the color in `c`. Kaizen keeps up to three colors, the opacity and the grain.
 */
internal object ZenThemes {
    private const val MAX_COLORS = 3
    private const val ZEN_DEFAULT_OPACITY = 0.5
    private const val FREE_HARMONY = "floating"
    private const val EXPLICIT_LIGHTNESS = "explicit-lightness"
    private const val CHANNEL_MAX = 255
    private const val PERCENT = 100
    private const val OPAQUE = 0xFF

    /** The theme Kaizen shows for a synced theme; `null` for none, including Zen's default theme without colors. */
    fun toKaizen(value: Any?): WorkspaceTheme? {
        val theme = value as? JSONObject ?: return null
        val dots = theme.optJSONArray("gradientColors") ?: return null
        val colors = (0 until dots.length())
            .mapNotNull { index -> (dots.opt(index) as? JSONObject)?.let(::dotColor) }
            .take(MAX_COLORS)
        if (colors.isEmpty()) return null
        return WorkspaceTheme(
            colors = colors,
            opacity = unit(theme.optDouble("opacity", ZEN_DEFAULT_OPACITY)),
            texture = unit(theme.optDouble("texture", 0.0)),
        )
    }

    /**
     * The synced form of [theme]. Dots of [previous] that still have the same color are kept as they are, so Zen keeps
     * their place on its color picker.
     */
    fun fromKaizen(theme: WorkspaceTheme?, previous: Any?): Any {
        theme ?: return JSONObject.NULL
        val before = previous as? JSONObject
        val previousDots = before?.optJSONArray("gradientColors")
        val dots = JSONArray()
        theme.colors.forEachIndexed { index, color ->
            val kept = (previousDots?.opt(index) as? JSONObject)?.takeIf { dotColor(it) == color }
            dots.put(kept?.let(SyncJson::copy) ?: newDot(color, primary = index == 0))
        }
        return (before?.let(SyncJson::copy) ?: JSONObject())
            .put("type", "gradient")
            .put("gradientColors", dots)
            .put("opacity", decimal(theme.opacity))
            .put("texture", decimal(theme.texture))
    }

    private fun newDot(color: Int, primary: Boolean): JSONObject {
        val (red, green, blue) = channels(color)
        return JSONObject()
            .put("c", JSONArray().put(red).put(green).put(blue))
            .put("isCustom", false)
            .put("algorithm", FREE_HARMONY)
            .put("isPrimary", primary)
            .put("lightness", lightness(red, green, blue))
            .put("type", EXPLICIT_LIGHTNESS)
    }

    @Suppress("MagicNumber")
    private fun dotColor(dot: JSONObject): Int? {
        val color = dot.opt("c")
        if (color is String) return CssColors.parse(color)
        if (color !is JSONArray || color.length() < 3) return null
        return argb(color.optDouble(0), color.optDouble(1), color.optDouble(2))
    }

    @Suppress("MagicNumber")
    private fun channels(color: Int) = Triple((color shr 16) and CHANNEL_MAX, (color shr 8) and CHANNEL_MAX, color and CHANNEL_MAX)

    @Suppress("MagicNumber")
    private fun argb(red: Double, green: Double, blue: Double): Int? {
        if (red.isNaN() || green.isNaN() || blue.isNaN()) return null
        fun channel(value: Double) = value.roundToInt().coerceIn(0, CHANNEL_MAX)
        return (OPAQUE shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    }

    private fun lightness(red: Int, green: Int, blue: Int): Int {
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        return ((max + min) * PERCENT / (2.0 * CHANNEL_MAX)).roundToInt()
    }

    private fun unit(value: Double): Float = if (value.isNaN()) 0f else value.toFloat().coerceIn(0f, 1f)

    /** Writes a float the way it reads, 0.35 rather than 0.3499999940395355. */
    private fun decimal(value: Float): Double = value.toString().toDouble()
}

/** Reads the CSS colors Zen allows as custom theme colors: `#rgb`, `#rrggbb` (with alpha) and `rgb()` or `rgba()`. */
internal object CssColors {
    private const val OPAQUE = 0xFF
    private const val CHANNEL_MAX = 255
    private val rgbFunction = Regex("""rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+).*\)""", RegexOption.IGNORE_CASE)

    @Suppress("MagicNumber")
    fun parse(text: String): Int? {
        val value = text.trim()
        if (value.startsWith("#")) {
            val hex = value.substring(1)
            val full = when (hex.length) {
                3, 4 -> hex.take(3).map { "$it$it" }.joinToString("")
                6, 8 -> hex.take(6)
                else -> return null
            }
            return full.toIntOrNull(16)?.let { (OPAQUE shl 24) or it }
        }
        val match = rgbFunction.matchEntire(value) ?: return null
        val (red, green, blue) = match.destructured.toList().map {
            it.toDoubleOrNull()?.roundToInt()?.coerceIn(0, CHANNEL_MAX) ?: return null
        }
        return (OPAQUE shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
