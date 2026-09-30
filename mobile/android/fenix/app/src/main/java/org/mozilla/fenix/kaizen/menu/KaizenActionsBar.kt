/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.menu

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent.Source
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.browser.BrowserFragmentDirections
import org.mozilla.fenix.components.appstate.AppAction.FindInPageAction
import org.mozilla.fenix.components.components
import org.mozilla.fenix.components.toolbar.DisplayActions
import org.mozilla.fenix.ext.components as contextComponents
import org.mozilla.fenix.ext.nav
import org.mozilla.fenix.kaizen.browser.KaizenToolbar
import org.mozilla.fenix.kaizen.browser.openKaizenMenu
import org.mozilla.fenix.kaizen.browser.shownWorkspaceTheme
import org.mozilla.fenix.kaizen.browser.workspaceTheme
import org.mozilla.fenix.kaizen.settings.KaizenSettings
import org.mozilla.fenix.kaizen.workspaces.WorkspaceRepository
import org.mozilla.fenix.webcompat.DefaultWebCompatReporterMoreInfoSender
import org.mozilla.fenix.webcompat.WEB_COMPAT_REPORTER_URL
import org.mozilla.fenix.webcompat.middleware.DefaultWebCompatReporterRetrievalService
import mozilla.components.ui.icons.R as iconsR

/**
 * Kaizen's bar below web pages: the first row of the "More" menu, then a button that starts a search. Swiping the bar
 * up opens the whole menu.
 *
 * @param onEvent Carries out what the user tapped.
 */
@Composable
fun KaizenActionsBar(onEvent: (BrowserToolbarEvent) -> Unit) {
    val context = LocalContext.current
    val fenix = components
    val settings = remember { fenix.readOnMainThread { KaizenSettings.get(context) } }
    val repository = remember { fenix.readOnMainThread { WorkspaceRepository.get(context) } }
    val tab by fenix.core.store.observeAsComposableState { it.selectedTab }
    val workspaces by repository.state.collectAsState()
    val rowKeys by settings.moreMenuRows.collectAsState()
    val customActions by settings.customActions.collectAsState()
    val entries = remember(rowKeys, customActions) {
        MoreMenuLayout.resolve(rowKeys, customActions).firstOrNull().orEmpty()
    }
    val snackbar by fenix.appStore.observeAsComposableState { it.snackbarState }
    val url = tab?.content?.url.orEmpty()
    val isBookmarked by produceState(false, url, snackbar) {
        value = url.isNotEmpty() &&
            fenix.core.bookmarksStorage.getBookmarksWithUrl(url).getOrDefault(emptyList()).any { it.url == url }
    }
    val state = tab?.let { current ->
        moreMenuState(
            fenix = fenix,
            tab = current,
            workspaces = workspaces,
            isBookmarked = isBookmarked,
            isDesktopMode = current.content.desktopMode,
            canSummarize = !current.content.private && fenix.core.summarizeFeatureSettings.canShowFeature,
        )
    } ?: MoreMenuState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .background(MaterialTheme.colorScheme.surface)
            .workspaceTheme(shownWorkspaceTheme()),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp)
                .size(width = 28.dp, height = 3.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HANDLE_ALPHA)),
        )
        val buttons = entries.map { entry -> BarItem(entry.look(state)) { onEvent(entry.barEvent(state)) } } +
            BarItem(EntryLook(stringResource(R.string.kaizen_actions_bar_search), iconsR.drawable.mozac_ic_search_24)) {
                onEvent(KaizenToolbar.SearchClicked)
            }
        val home = BarItem(EntryLook(stringResource(R.string.kaizen_actions_bar_home), iconsR.drawable.mozac_ic_home_24)) {
            onEvent(DisplayActions.HomepageClicked(Source.NavigationBar))
        }
        // Screen readers cannot swipe the bar up, so every button also offers the menu as an action.
        val menuAction = listOf(
            CustomAccessibilityAction(stringResource(R.string.content_description_menu)) {
                onEvent(KaizenToolbar.MenuRequested)
                true
            },
        )
        // Home stays in the middle, with the other buttons shared out on both sides of it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        ) {
            Row(modifier = Modifier.weight(1f)) {
                buttons.take(buttons.size / 2).forEach { BarButton(it, menuAction, Modifier.weight(1f)) }
            }
            BarButton(home, menuAction, Modifier.width(HOME_SLOT_WIDTH))
            Row(modifier = Modifier.weight(1f)) {
                buttons.drop(buttons.size / 2).forEach { BarButton(it, menuAction, Modifier.weight(1f)) }
            }
        }
    }
}

private class BarItem(val look: EntryLook, val onClick: () -> Unit)

@Composable
private fun BarButton(item: BarItem, a11yActions: List<CustomAccessibilityAction>, modifier: Modifier = Modifier) {
    val look = item.look
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (look.active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable(enabled = look.enabled, role = Role.Button, onClick = item.onClick)
                .semantics {
                    contentDescription = look.label
                    customActions = a11yActions
                }
                .alpha(if (look.enabled) 1f else DISABLED_ALPHA),
        ) {
            Icon(
                painter = painterResource(look.icon),
                contentDescription = null,
                tint = if (look.active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** What tapping [this] in the actions bar does: the address bar's own action where it has one. */
private fun MoreMenuEntry.barEvent(state: MoreMenuState): BrowserToolbarEvent {
    val source = Source.NavigationBar
    return when ((this as? MoreMenuEntry.BuiltIn)?.item) {
        MoreMenuItem.BACK -> DisplayActions.NavigateBackClicked(source)
        MoreMenuItem.FORWARD -> DisplayActions.NavigateForwardClicked
        MoreMenuItem.REFRESH ->
            if (state.isLoading) DisplayActions.StopRefreshClicked else DisplayActions.RefreshClicked(bypassCache = false)
        MoreMenuItem.SHARE -> DisplayActions.ShareClicked(source)
        MoreMenuItem.BOOKMARK_PAGE ->
            if (state.isBookmarked) DisplayActions.EditBookmarkClicked(source) else DisplayActions.AddBookmarkClicked(source)
        MoreMenuItem.TRANSLATE -> DisplayActions.TranslateClicked(source)
        MoreMenuItem.SUMMARIZE -> DisplayActions.SummarizeClicked(source)
        else -> KaizenToolbar.MenuEntryClicked(key)
    }
}

/**
 * Carries out the entry with [key] of the actions bar on the shown tab, for the entries the address bar has no action
 * of its own for.
 */
@Suppress("CyclomaticComplexMethod")
internal fun runActionsBarEntry(context: Context, navController: NavController, key: String) {
    val components = context.contextComponents
    val settings = KaizenSettings.get(context)
    val repository = WorkspaceRepository.get(context)
    val tab = components.core.store.state.selectedTab ?: return
    val workspaces = repository.state.value
    val commands = KaizenTabCommands(context, components, repository, settings, tab)
    val entry = MoreMenuLayout.entryOf(key, settings.customActions.value) ?: return
    val item = (entry as? MoreMenuEntry.BuiltIn)?.item
    if (item == null) {
        commands.runCustomAction((entry as MoreMenuEntry.Custom).action, workspaces)
        return
    }
    val isSplit = workspaces.splits.any { tab.id in it.tabIds }
    if (item == MoreMenuItem.SPLIT_VIEW && !isSplit) {
        navController.openKaizenMenu(fromBottom = true, opening = KaizenMenu.Opening.SPLIT_PICKER)
        return
    }
    if (commands.run(item, workspaces)) return
    when (item) {
        MoreMenuItem.EXTENSIONS -> navController.openKaizenMenu(fromBottom = true, opening = KaizenMenu.Opening.EXTENSIONS)
        MoreMenuItem.FIND_IN_PAGE -> components.appStore.dispatch(FindInPageAction.FindInPageStarted)
        MoreMenuItem.DESKTOP_SITE ->
            components.useCases.sessionUseCases.requestDesktopSite(!tab.content.desktopMode, tab.id)
        MoreMenuItem.PASSWORDS -> navController.nav(R.id.browserFragment, NavGraphDirections.actionLoginsListFragment())
        MoreMenuItem.BOOKMARKS ->
            navController.nav(R.id.browserFragment, NavGraphDirections.actionGlobalBookmarkFragment(BookmarkRoot.Mobile.id))
        MoreMenuItem.DOWNLOADS -> navController.nav(R.id.browserFragment, NavGraphDirections.actionGlobalDownloadsFragment())
        MoreMenuItem.HISTORY -> navController.nav(R.id.browserFragment, NavGraphDirections.actionGlobalHistoryFragment())
        MoreMenuItem.SETTINGS -> navController.nav(R.id.browserFragment, NavGraphDirections.actionGlobalSettingsFragment())
        MoreMenuItem.REPORT_BROKEN_SITE -> reportBrokenSite(context, navController, tab)
        MoreMenuItem.OPEN_IN_APP -> {
            val redirect = components.useCases.appLinksUseCases.appLinkRedirect(tab.content.url)
            if (redirect.hasExternalApp()) {
                components.settings.openInAppOpened = true
                components.useCases.appLinksUseCases.openAppLink(redirect.appIntent)
            }
        }
        else -> Unit
    }
}

/** Reports the page of [tab] as broken, the way Fenix's menu does. */
private fun reportBrokenSite(context: Context, navController: NavController, tab: TabSessionState) {
    val components = context.contextComponents
    val url = tab.content.url
    if (components.settings.isTelemetryEnabled) {
        navController.nav(
            R.id.browserFragment,
            BrowserFragmentDirections.actionBrowserFragmentToWebCompatReporterFragment(tabUrl = url),
        )
        return
    }
    components.applicationScope.launch(Dispatchers.Main) {
        DefaultWebCompatReporterMoreInfoSender(DefaultWebCompatReporterRetrievalService(components.core.store))
            .sendMoreWebCompatInfo(
                reason = null,
                problemDescription = null,
                enteredUrl = null,
                tabUrl = url,
                engineSession = tab.engineState.engineSession,
            )
        components.useCases.fenixBrowserUseCases.loadUrlOrSearch(
            searchTermOrURL = "$WEB_COMPAT_REPORTER_URL$url",
            newTab = true,
        )
    }
}

private val BAR_HEIGHT = 60.dp
private val HOME_SLOT_WIDTH = 64.dp
private const val HANDLE_ALPHA = 0.3f
private const val DISABLED_ALPHA = 0.38f
