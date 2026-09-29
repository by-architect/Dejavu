/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.browser

import android.content.Context
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.material3.ColorScheme
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.compose.browser.toolbar.concept.Action
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarAction
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarMenu
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarMenuItem
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarMenuItem.BrowserToolbarMenuButton
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarMenuItem.BrowserToolbarMenuButton.ContentDescription.StringContentDescription
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarMenuItem.BrowserToolbarMenuButton.Icon
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarMenuItem.BrowserToolbarMenuButton.Text.StringText
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarMenuItem.BrowserToolbarMenuDivider
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarState
import mozilla.components.lib.state.Store
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.components.menu.MenuAccessPoint
import org.mozilla.fenix.components.toolbar.PageOriginInteractions
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.kaizen.containers.ContainerColor
import org.mozilla.fenix.kaizen.containers.ContainerPick
import org.mozilla.fenix.kaizen.containers.KaizenContainerStorage
import org.mozilla.fenix.kaizen.containers.TemporaryContainers
import org.mozilla.fenix.kaizen.containers.drawable
import org.mozilla.fenix.kaizen.containers.reopenInContainer
import org.mozilla.fenix.kaizen.home.displayTitle
import org.mozilla.fenix.kaizen.home.titleOf
import org.mozilla.fenix.kaizen.menu.KaizenMenu
import org.mozilla.fenix.kaizen.menu.runActionsBarEntry
import org.mozilla.fenix.kaizen.workspaces.WorkspaceRepository

/**
 * Kaizen's changes to the browser toolbar: one row with the address bar and a home button on its right, and the
 * actions bar below pages. The menu opens by swiping the toolbar or the actions bar, so it has no menu button, and
 * tabs are managed from the home screen, so it has no tab counter.
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

    /**
     * What the address bar shows until it is focused: the tab's title as the home screen shows it, or the address
     * when the page has no title.
     */
    fun displayText(tab: TabSessionState?): String {
        tab ?: return ""
        return WorkspaceRepository.peek()?.state?.value?.titleOf(tab) ?: tab.displayTitle
    }

    /**
     * The icon of the container of the shown tab, in the container's color, or `null` outside of containers. Tapping
     * it lists the other containers to reopen the tab in.
     */
    fun containerAction(context: Context, state: BrowserState): Action? {
        val contextId = state.selectedTab?.contextId ?: return null
        val record = KaizenContainerStorage.get(context).records.value?.firstOrNull { it.contextId == contextId }
        val container = state.containers[contextId]
        val icon = record?.icon ?: container?.icon ?: return null
        val color = record?.color ?: container?.let { ContainerColor.fromAcColor(it.color) } ?: return null
        val drawable = tintedIcon(context, icon, color) ?: return null
        return Action.ActionButton(
            drawable = drawable,
            shouldTint = false,
            contentDescription = context.getString(R.string.kaizen_toolbar_container, record?.name ?: container?.name.orEmpty()),
            onClick = BrowserToolbarMenu { containerMenu(context, current = contextId) },
        )
    }

    /** The user picked a container, by its [ContainerPick.key], in the container menu of the address bar. */
    data class ContainerPicked(val pickKey: String) : KaizenToolbarEvent

    /** The user tapped the entry with [key] of the actions bar. */
    data class MenuEntryClicked(val key: String) : KaizenToolbarEvent

    /** The user tapped the search button of the actions bar. */
    data object SearchClicked : KaizenToolbarEvent

    /** Carries out [event], with [navController] and the [store] of the address bar. */
    fun onEvent(
        context: Context,
        navController: NavController,
        store: Store<BrowserToolbarState, BrowserToolbarAction>,
        event: KaizenToolbarEvent,
    ) {
        when (event) {
            is ContainerPicked -> onContainerPicked(context, event)
            is MenuEntryClicked -> runActionsBarEntry(context, navController, event.key)
            SearchClicked -> {
                KaizenSearchStart.fromBottom()
                store.dispatch(PageOriginInteractions.OriginClicked)
            }
        }
    }

    /** Reopens the shown tab in the container of [event], keeping its place, pin and workspace. */
    private fun onContainerPicked(context: Context, event: ContainerPicked) {
        val pick = ContainerPick.fromKey(event.pickKey) ?: return
        val components = context.components
        val store = components.core.store
        val tab = store.state.selectedTab ?: return
        val contextId = when (pick) {
            ContainerPick.NoContainer -> null
            ContainerPick.Temporary -> TemporaryContainers.create(store, KaizenContainerStorage.get(context))
            is ContainerPick.Container -> pick.contextId
        }
        if (contextId == tab.contextId) return
        val repository = WorkspaceRepository.peek()
        repository?.state?.value?.pinOf(tab.id)?.let { repository.setPinContainer(setOf(it.id), contextId) }
        components.reopenInContainer(tab, contextId, repository, selected = true, load = true)
    }

    private fun containerMenu(context: Context, current: String): List<BrowserToolbarMenuItem> {
        val others = KaizenContainerStorage.get(context).records.value.orEmpty().filter { it.contextId != current }
        val (temporary, permanent) = others.partition { it.temporary }
        val containers = permanent + temporary.sortedWith(compareBy({ it.name.length }, { it.name }))
        fun button(icon: Icon, text: String, pick: ContainerPick) =
            BrowserToolbarMenuButton(icon, StringText(text), StringContentDescription(text), ContainerPicked(pick.key))
        return buildList {
            add(
                button(
                    Icon.DrawableResIcon(R.drawable.kaizen_ic_no_container_24),
                    context.getString(R.string.kaizen_workspace_no_container),
                    ContainerPick.NoContainer,
                ),
            )
            add(
                button(
                    Icon.DrawableResIcon(R.drawable.kaizen_ic_temporary_container_24),
                    context.getString(R.string.kaizen_new_temporary_container),
                    ContainerPick.Temporary,
                ),
            )
            if (containers.isNotEmpty()) add(BrowserToolbarMenuDivider)
            containers.forEach { record ->
                val drawable = tintedIcon(context, record.icon, record.color) ?: return@forEach
                add(button(Icon.DrawableIcon(drawable, shouldTint = false), record.name, ContainerPick.Container(record.contextId)))
            }
        }
    }

    private fun tintedIcon(context: Context, icon: ContainerState.Icon, color: ContainerColor) =
        AppCompatResources.getDrawable(context, icon.drawable)?.mutate()?.apply { setTint(color.argb.toInt()) }
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

/** An interaction with Kaizen's parts of the browser toolbars, carried out by [KaizenToolbar.onEvent]. */
sealed interface KaizenToolbarEvent : BrowserToolbarEvent

/**
 * Opens the "More" menu of the browser, as swiping the toolbar does.
 *
 * @param fromBottom Whether the menu comes up from the bottom of the screen, as when swiping the actions bar.
 * @param opening What the menu shows first.
 */
fun NavController.openKaizenMenu(fromBottom: Boolean, opening: KaizenMenu.Opening = KaizenMenu.Opening.MENU) {
    val directions = NavGraphDirections.actionGlobalMenuDialogFragment(accesspoint = MenuAccessPoint.Browser)
    if (currentDestination?.id != R.id.browserFragment) return
    navigate(directions.actionId, directions.arguments.apply { putAll(KaizenMenu.arguments(fromBottom, opening)) })
}
