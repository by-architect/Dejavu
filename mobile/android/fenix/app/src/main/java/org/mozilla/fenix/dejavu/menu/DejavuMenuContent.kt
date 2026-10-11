/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.StrictMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.navigation.NavController
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.components.components
import org.mozilla.fenix.components.menu.MenuAccessPoint
import org.mozilla.fenix.components.menu.store.MenuAction
import org.mozilla.fenix.components.menu.store.MenuStore
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.ext.components as contextComponents
import org.mozilla.fenix.nimbus.FxNimbus

/** How Dejavu's "More" menu takes the place of Fenix's main menu. */
object DejavuMenu {
    private const val ARG_FROM_BOTTOM = "dejavu_menu_from_bottom"
    private const val ARG_OPENING = "dejavu_menu_opening"

    /** What the menu shows when it opens. */
    enum class Opening { MENU, EXTENSIONS, SPLIT_PICKER }

    /** Whether Dejavu's menu replaces Fenix's for [accessPoint]. Custom tabs keep Fenix's menu. */
    fun replaces(accessPoint: MenuAccessPoint): Boolean = accessPoint != MenuAccessPoint.External

    /**
     * Whether the menu comes down from the top of the screen, which it does when it was opened from an address bar at
     * the top.
     *
     * @param arguments The arguments of the menu dialog, as made by [arguments].
     */
    fun opensFromTop(context: Context, accessPoint: MenuAccessPoint, arguments: Bundle?): Boolean =
        accessPoint == MenuAccessPoint.Browser &&
            !context.contextComponents.settings.shouldUseBottomToolbar &&
            arguments?.getBoolean(ARG_FROM_BOTTOM) != true

    /** What the menu opened with [arguments] shows first. */
    fun opening(arguments: Bundle?): Opening =
        arguments?.getString(ARG_OPENING)?.let { name -> Opening.entries.firstOrNull { it.name == name } } ?: Opening.MENU

    /** The arguments that open the menu dialog from the bottom of the screen or not, showing [opening] first. */
    fun arguments(fromBottom: Boolean, opening: Opening): Bundle = Bundle().apply {
        putBoolean(ARG_FROM_BOTTOM, fromBottom)
        putString(ARG_OPENING, opening.name)
    }

    /** A dialog showing its content at the top of the screen, sliding down from the edge. */
    fun createTopSheetDialog(context: Context, onMenuKey: () -> Unit): Dialog =
        object : Dialog(context, R.style.DejavuTopSheetDialog) {
            override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                    onMenuKey()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
        }.apply {
            setCanceledOnTouchOutside(true)
            window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.TOP)
                setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(TOP_SHEET_DIM)
                setWindowAnimations(R.style.DejavuTopSheetAnimation)
            }
        }

    private const val TOP_SHEET_DIM = 0.32f
}

/**
 * The content of the menu dialog when Dejavu replaces Fenix's menu: the "More" menu of the browser, or the
 * extensions of the home screen.
 *
 * @param menuStore Fenix's menu store, which carries out the actions Fenix's menu also has.
 * @param accessPoint Where the menu was opened from.
 * @param fromTop Whether the menu is shown at the top of the screen.
 * @param opening What the menu shows first.
 * @param navController Opens the screens tab actions lead to.
 * @param onEdit Opens the settings of the menu.
 * @param onDismiss Closes the menu.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun DejavuMenuContent(
    menuStore: MenuStore,
    accessPoint: MenuAccessPoint,
    fromTop: Boolean,
    opening: DejavuMenu.Opening,
    navController: NavController,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fenix = components
    val menuState by menuStore.stateFlow.collectAsState()
    val extensions = menuState.extensionMenuState.browserWebExtensionMenuItem
    val manageExtensions = { menuStore.dispatch(MenuAction.Navigate.ManageExtensions) }

    MoreMenuSheet(fromTop = fromTop, onDismiss = onDismiss) {
        if (accessPoint == MenuAccessPoint.Home) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_extension_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.dejavu_menu_extensions),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            ExtensionList(extensions = extensions, onManage = manageExtensions)
            return@MoreMenuSheet
        }

        val context = LocalContext.current
        val settings = remember { fenix.readOnMainThread { DejavuSettings.get(context) } }
        val repository = remember { fenix.readOnMainThread { WorkspaceRepository.get(context) } }
        val tab by fenix.core.store.observeAsComposableState { it.selectedTab }
        val workspaces by repository.state.collectAsState()
        val rowKeys by settings.moreMenuRows.collectAsState()
        val customActions by settings.customActions.collectAsState()
        val editHidden by settings.moreMenuEditHidden.collectAsState()
        var extensionsExpanded by remember { mutableStateOf(opening == DejavuMenu.Opening.EXTENSIONS) }
        var pickingSplit by remember { mutableStateOf(opening == DejavuMenu.Opening.SPLIT_PICKER) }
        var asking by remember { mutableStateOf<TabAction?>(null) }
        val current = tab ?: return@MoreMenuSheet
        val state = moreMenuState(
            fenix = fenix,
            tab = current,
            workspaces = workspaces,
            isBookmarked = menuState.browserMenuState?.bookmarkState?.isBookmarked == true,
            isDesktopMode = menuState.isDesktopMode,
        )
        val actions = MenuActions(context, fenix, menuStore, repository, settings, current, navController, onDismiss)

        asking?.let { action ->
            ShownTabActionDialog(action = action, tab = current, navController = navController, onDone = onDismiss)
        }

        if (pickingSplit) {
            val workspaceId = workspaces.workspaceOf(current.id)
            val candidates = fenix.core.store.state.normalTabs.filter { other ->
                other.id != current.id &&
                    workspaces.workspaceOf(other.id) == workspaceId &&
                    workspaces.splits.none { other.id in it.tabIds }
            }
            SplitTabPicker(
                tabs = candidates,
                onPick = { other -> actions.split(workspaces, other) },
                onBack = { pickingSplit = false },
            )
            return@MoreMenuSheet
        }

        MoreMenu(
            rows = remember(rowKeys, customActions) { MoreMenuLayout.resolve(rowKeys, customActions) },
            state = state,
            extensions = extensions,
            extensionsExpanded = extensionsExpanded,
            onToggleExtensions = { extensionsExpanded = !extensionsExpanded },
            onEntryClick = { entry ->
                when {
                    entry == MoreMenuEntry.BuiltIn(MoreMenuItem.EXTENSIONS) -> extensionsExpanded = !extensionsExpanded
                    entry == MoreMenuEntry.BuiltIn(MoreMenuItem.SPLIT_VIEW) && !state.isSplit -> pickingSplit = true
                    entry is MoreMenuEntry.Tab && entry.action.asksFirst -> asking = entry.action
                    else -> actions.run(entry, state, workspaces)
                }
            },
            onManageExtensions = manageExtensions,
            onEdit = onEdit.takeUnless { editHidden },
        )
    }
}

/** What the "More" menu and the actions bar show about [tab]. */
@Suppress("LongParameterList")
@Composable
internal fun moreMenuState(
    fenix: Components,
    tab: TabSessionState,
    workspaces: WorkspaceState,
    isBookmarked: Boolean,
    isDesktopMode: Boolean,
): MoreMenuState {
    val url = tab.content.url
    val pin = workspaces.pinOf(tab.id)
    val hasExternalApp = remember(url) {
        runCatching { fenix.useCases.appLinksUseCases.appLinkRedirect(url).hasExternalApp() }.getOrDefault(false)
    }
    val translationEngineSupported = fenix.core.store.state.translationEngine.isEngineSupported == true
    return MoreMenuState(
        canGoBack = tab.content.canGoBack,
        canGoForward = tab.content.canGoForward,
        isLoading = tab.content.loading,
        isBookmarked = isBookmarked,
        isDesktopMode = isDesktopMode,
        isPinned = pin != null && !pin.essential,
        isEssential = pin?.essential == true,
        isPinChanged = pin?.url != null && pin.url != url,
        isSplit = workspaces.splits.any { tab.id in it.tabIds },
        isPrivate = tab.content.private,
        hasExternalApp = hasExternalApp,
        canTranslate = translationEngineSupported &&
            FxNimbus.features.translations.value().mainFlowBrowserMenuEnabled &&
            !tab.content.isPdf,
        isTranslated = tab.translationsState.isTranslated,
        isWebPage = url.startsWith("http://") || url.startsWith("https://"),
        isDeviceTab = !tab.content.private &&
            workspaces.isDeviceWorkspace(pin?.workspaceId ?: workspaces.workspaceOf(tab.id)),
    )
}

/** Carries out the entries of the "More" menu for [tab]. */
@Suppress("LongParameterList")
private class MenuActions(
    private val context: Context,
    components: Components,
    private val menuStore: MenuStore,
    repository: WorkspaceRepository,
    settings: DejavuSettings,
    private val tab: TabSessionState,
    private val navController: NavController,
    private val dismiss: () -> Unit,
) {
    private val commands = DejavuTabCommands(context, components, repository, settings, tab)

    @Suppress("CyclomaticComplexMethod")
    fun run(entry: MoreMenuEntry, state: MoreMenuState, workspaces: WorkspaceState) {
        val item = when (entry) {
            is MoreMenuEntry.BuiltIn -> entry.item
            is MoreMenuEntry.Custom -> {
                commands.runCustomAction(entry.action, workspaces)
                dismiss()
                return
            }
            is MoreMenuEntry.Tab -> {
                dismiss()
                runOnShownTab(entry.action, context, navController, tab)
                return
            }
            MoreMenuEntry.Home, MoreMenuEntry.Search -> return
        }
        if (commands.run(item, workspaces)) {
            dismiss()
            return
        }
        when (item) {
            MoreMenuItem.BACK -> dispatch(MenuAction.Navigate.Back(viewHistory = false))
            MoreMenuItem.FORWARD -> dispatch(MenuAction.Navigate.Forward(viewHistory = false))
            MoreMenuItem.REFRESH -> dispatch(
                if (state.isLoading) MenuAction.Navigate.Stop else MenuAction.Navigate.Reload(bypassCache = false),
            )
            MoreMenuItem.SHARE -> dispatch(MenuAction.Navigate.Share)
            MoreMenuItem.FIND_IN_PAGE -> dispatch(MenuAction.FindInPage)
            MoreMenuItem.BOOKMARK_PAGE -> dispatch(
                if (state.isBookmarked) MenuAction.Navigate.EditBookmark() else MenuAction.AddBookmark,
            )
            MoreMenuItem.DESKTOP_SITE -> dispatch(
                if (state.isDesktopMode) MenuAction.RequestMobileSite else MenuAction.RequestDesktopSite,
            )
            MoreMenuItem.PASSWORDS -> dispatch(MenuAction.Navigate.Passwords)
            MoreMenuItem.BOOKMARKS -> dispatch(MenuAction.Navigate.Bookmarks)
            MoreMenuItem.DOWNLOADS -> dispatch(MenuAction.Navigate.Downloads)
            MoreMenuItem.HISTORY -> dispatch(MenuAction.Navigate.History)
            MoreMenuItem.TRANSLATE -> dispatch(MenuAction.Navigate.Translate)
            MoreMenuItem.REPORT_BROKEN_SITE -> dispatch(MenuAction.Navigate.WebCompatReporter)
            MoreMenuItem.OPEN_IN_APP -> dispatch(MenuAction.OpenInApp)
            MoreMenuItem.SETTINGS -> dispatch(MenuAction.Navigate.Settings)
            else -> Unit
        }
    }

    /** Shows the tab of the menu and [other] side by side. */
    fun split(workspaces: WorkspaceState, other: TabSessionState) {
        commands.split(workspaces, other)
        dismiss()
    }

    private fun dispatch(action: MenuAction) = menuStore.dispatch(action)
}

internal fun <T> Components.readOnMainThread(block: () -> T): T =
    strictMode.allowViolation(StrictMode::allowThreadDiskReads, block)
