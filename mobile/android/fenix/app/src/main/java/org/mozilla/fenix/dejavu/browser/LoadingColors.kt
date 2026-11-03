/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import org.mozilla.fenix.components.components
import org.mozilla.fenix.dejavu.menu.readOnMainThread
import org.mozilla.fenix.dejavu.settings.DejavuSettings

/** The colors that run through the address bar's letters while a page loads, as the user chose them in Bars. */
enum class LoadingColorsChoice(val key: String) {
    /** Dejavu's gold and the colors around it, as Dejavu starts with. */
    RAINBOW("rainbow"),

    /**
     * The colors of the tab's workspace: those of its wallpaper, as the bars around pages take them on, or else the
     * gradient of its own background. A private tab, or a workspace with neither, shows the rainbow.
     */
    WORKSPACE("workspace"),

    /** A gradient of the user's own colors, picked as a workspace's own colors are; the rainbow until they pick any. */
    CUSTOM("custom"),
    ;

    companion object {
        /** The choice saved as [key], or the rainbow. */
        fun fromKey(key: String?): LoadingColorsChoice = entries.firstOrNull { it.key == key } ?: RAINBOW
    }
}

// Letters in colors lighter than this over a light bar, or darker than that over a dark one, are hard to read, so the
// colors are taken toward black or white until they are not. The rainbow's own colors stay within both.
private const val MAX_LUMINANCE_ON_LIGHT = 0.25f
private const val MIN_LUMINANCE_ON_DARK = 0.25f
private const val READABLE_STEP = 0.1f

// A bar darker than this is a dark one, as the rainbow tells them apart.
private const val DARK_SURFACE = 0.5f

// A single color runs as a band of that color between letters that show their own color through it.
private const val LONE_COLOR_ALPHA = 0.3f

/**
 * The colors of the band that sweeps through the letters for [choice], over a [dark] bar or a light one, from the
 * workspace's [workspaceColors] and the user's [customColors], both ARGB: the gradient made to run back to where it
 * started, so the band repeats without a seam. `null` for the rainbow, which is also what a choice without colors
 * shows.
 */
internal fun loadingGradientOf(
    choice: LoadingColorsChoice,
    workspaceColors: List<Int>?,
    customColors: List<Int>,
    dark: Boolean,
): List<Color>? {
    val colors = when (choice) {
        LoadingColorsChoice.RAINBOW -> null
        LoadingColorsChoice.WORKSPACE -> workspaceColors
        LoadingColorsChoice.CUSTOM -> customColors
    }?.takeIf { it.isNotEmpty() } ?: return null
    val readable = colors.map { readableOn(Color(it), dark) }
    return if (readable.size == 1) {
        val color = readable.single()
        listOf(color, color.copy(alpha = LONE_COLOR_ALPHA), color)
    } else {
        readable + readable.dropLast(1).asReversed()
    }
}

/** [color] taken toward white over a [dark] bar, or toward black over a light one, as far as it takes to read it. */
internal fun readableOn(color: Color, dark: Boolean): Color {
    val target = if (dark) Color.White else Color.Black
    fun readable(shade: Color) =
        if (dark) shade.luminance() >= MIN_LUMINANCE_ON_DARK else shade.luminance() <= MAX_LUMINANCE_ON_LIGHT
    var fraction = 0f
    var shown = color
    while (!readable(shown) && fraction < 1f) {
        fraction = (fraction + READABLE_STEP).coerceAtMost(1f)
        shown = lerp(color, target, fraction)
    }
    return shown
}

/**
 * The colors of the band for the tab with [tabId], or the shown tab when `null`, see [loadingGradientOf]; `null` for
 * the rainbow. Follows changes to the choice and to the workspace's colors.
 */
@Composable
internal fun loadingGradient(tabId: String?): List<Color>? {
    val context = LocalContext.current
    val fenix = components
    val settings = remember { fenix.readOnMainThread { DejavuSettings.get(context) } }
    val choice by settings.loadingColors.collectAsState()
    val customColors by settings.loadingCustomColors.collectAsState()
    val dark = MaterialTheme.colorScheme.surface.luminance() < DARK_SURFACE
    val workspaceColors = if (choice == LoadingColorsChoice.WORKSPACE) shownWorkspaceColors(tabId) else null
    return remember(choice, workspaceColors, customColors, dark) {
        loadingGradientOf(choice, workspaceColors, customColors, dark)
    }
}
