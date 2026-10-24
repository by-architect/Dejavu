/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.content.Context
import android.os.StrictMode
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarStore
import org.mozilla.fenix.components.components
import org.mozilla.fenix.dejavu.menu.ACTIONS_BAR_BOTTOM_MARGIN
import org.mozilla.fenix.dejavu.menu.BAR_HEIGHT
import org.mozilla.fenix.dejavu.menu.DejavuActionsBars
import org.mozilla.fenix.dejavu.menu.readOnMainThread
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.ext.components as contextComponents

/**
 * Where Dejavu's bars show around web pages, as the user chose in Customize. It also says where the "More" menu comes
 * from: out of the bar that swiping opens it on.
 *
 * @property key Stable identifier used to persist the choice.
 */
enum class BarsLayout(val key: String) {
    /** The address bar above pages and the actions bar below them; swiping the actions bar up opens the menu. */
    ACTIONS_BAR("actions_bar"),

    /** Only the address bar, above pages; swiping it down opens the menu. */
    TOP("top"),

    /** Only the address bar, below pages; swiping it up opens the menu. */
    BOTTOM("bottom"),
    ;

    /** Whether the actions bar shows below pages. */
    val hasActionsBar: Boolean
        get() = this == ACTIONS_BAR

    /** Whether the address bar shows below pages instead of above them. */
    val addressBarAtBottom: Boolean
        get() = this == BOTTOM

    /** Whether the "More" menu comes down from the address bar at the top instead of up from a bar at the bottom. */
    val menuFromTop: Boolean
        get() = this == TOP

    /** The room the bar below pages takes above the navigation bar, with the space below it; none without one. */
    internal val bottomBarHeight: Dp
        get() = when (this) {
            ACTIONS_BAR -> BAR_HEIGHT + ACTIONS_BAR_BOTTOM_MARGIN
            TOP -> 0.dp
            BOTTOM -> ADDRESS_BAR_HEIGHT + BOTTOM_ADDRESS_BAR_MARGIN
        }

    companion object {
        /** The layout saved as [key], or the one Dejavu starts with. */
        fun fromKey(key: String?): BarsLayout = entries.firstOrNull { it.key == key } ?: ACTIONS_BAR
    }
}

/** The layout of the bars the user chose. */
internal val Context.barsLayout: BarsLayout
    get() = contextComponents.strictMode.allowViolation(StrictMode::allowThreadDiskReads) {
        DejavuSettings.get(this)
    }.barsLayout.value

/** The layout of the bars the user chose, following changes to it. */
@Composable
internal fun currentBarsLayout(): BarsLayout {
    val context = LocalContext.current
    val fenix = components
    val settings = remember { fenix.readOnMainThread { DejavuSettings.get(context) } }
    val layout by settings.barsLayout.collectAsState()
    return layout
}

/**
 * What Dejavu shows below web pages, as the layout of the bars has it: the actions bar, the address bar, or nothing.
 *
 * @param store The store of the address bar.
 * @param onEvent Carries out what the user tapped on the actions bar.
 */
@Composable
fun DejavuBottomBar(store: BrowserToolbarStore, onEvent: (BrowserToolbarEvent) -> Unit) {
    when (currentBarsLayout()) {
        BarsLayout.ACTIONS_BAR -> DejavuActionsBars(onEvent = onEvent)
        BarsLayout.TOP -> Unit
        BarsLayout.BOTTOM -> MaterialTheme(colorScheme = DejavuToolbar.glass(MaterialTheme.colorScheme)) {
            BelowPages { DejavuAddressBar(store) }
        }
    }
}

/**
 * Places the address bar below pages, above the navigation bar also while it is hidden, where it floats over the page,
 * see [LocalAddressBarFloats].
 */
@Composable
internal fun BelowPages(content: @Composable () -> Unit) {
    Box(modifier = Modifier.navigationBarRoomPadding().padding(bottom = BOTTOM_ADDRESS_BAR_MARGIN)) {
        CompositionLocalProvider(LocalAddressBarFloats provides true, content = content)
    }
}

/**
 * The space between the address bar below pages and the navigation bar, which keeps its field as far from the bottom of
 * the screen as the actions bar.
 */
internal val BOTTOM_ADDRESS_BAR_MARGIN = ACTIONS_BAR_BOTTOM_MARGIN - ADDRESS_FIELD_MARGIN
