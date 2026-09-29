/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.browser

import android.content.Context
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.material3.ColorScheme
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.compose.browser.toolbar.concept.Action
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.components.menu.MenuAccessPoint
import org.mozilla.fenix.ext.nav
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.kaizen.containers.ContainerColor
import org.mozilla.fenix.kaizen.containers.KaizenContainerStorage
import org.mozilla.fenix.kaizen.containers.drawable
import org.mozilla.fenix.kaizen.home.siteName

/**
 * Kaizen's changes to the browser toolbar: one row with the shortcut the user picked on the left of the address bar
 * and a home button on its right. The menu opens by swiping the toolbar, so it has no menu button, and tabs are
 * managed from the home screen, so it has no tab counter.
 */
object KaizenToolbar {
    const val enabled: Boolean = true

    val showTabCounter: Boolean = false

    /** Colors of a translucent toolbar and address bar, so the page shows through them. */
    fun glass(scheme: ColorScheme): ColorScheme = scheme.copy(
        surface = scheme.surface.copy(alpha = GLASS_BAR_ALPHA),
        surfaceContainerHighest = scheme.onSurface.copy(alpha = GLASS_FIELD_ALPHA),
    )

    private const val GLASS_BAR_ALPHA = 0.84f
    private const val GLASS_FIELD_ALPHA = 0.09f

    /** The address bar shows the site's name (its host) rather than the whole URL until it is focused. */
    fun displayUrl(url: String): String = siteName(url)

    /** The icon of the container of the shown tab, in the container's color, or `null` outside of containers. */
    fun containerAction(context: Context, state: BrowserState): Action? {
        val contextId = state.selectedTab?.contextId ?: return null
        val record = KaizenContainerStorage.get(context).records.value?.firstOrNull { it.contextId == contextId }
        val container = state.containers[contextId]
        val icon = record?.icon ?: container?.icon ?: return null
        val color = record?.color ?: container?.let { ContainerColor.fromAcColor(it.color) } ?: return null
        val drawable = AppCompatResources.getDrawable(context, icon.drawable)?.mutate() ?: return null
        drawable.setTint(color.argb.toInt())
        return Action.ActionButton(
            drawable = drawable,
            shouldTint = false,
            contentDescription = context.getString(R.string.kaizen_toolbar_container, record?.name ?: container?.name.orEmpty()),
            onClick = object : BrowserToolbarEvent {},
        )
    }
}

/**
 * Handles Back on a tab without history: goes to the home screen and leaves the tab as it is. Custom tabs and tabs
 * opened by other apps keep Fenix's behavior.
 *
 * @return Whether Back was handled.
 */
fun Fragment.handleKaizenBackPressed(customTabSessionId: String?): Boolean {
    if (customTabSessionId != null) return false
    val tab = requireComponents.core.store.state.selectedTab ?: return false
    if (tab.source is SessionState.Source.External) return false

    findNavController().navigate(NavGraphDirections.actionGlobalHome())
    return true
}

/** Opens the "More" menu of the browser, as swiping the toolbar does. */
fun Fragment.openKaizenMenu() {
    findNavController().nav(
        R.id.browserFragment,
        NavGraphDirections.actionGlobalMenuDialogFragment(accesspoint = MenuAccessPoint.Browser),
    )
}
