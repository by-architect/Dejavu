/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.menu

import android.app.Dialog
import android.content.Context
import android.os.StrictMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.lib.state.ext.observeAsComposableState
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.components.components
import org.mozilla.fenix.components.menu.MenuAccessPoint
import org.mozilla.fenix.components.menu.store.MenuAction
import org.mozilla.fenix.components.menu.store.MenuStore
import org.mozilla.fenix.ext.components as contextComponents
import org.mozilla.fenix.kaizen.actions.ActionContext
import org.mozilla.fenix.kaizen.actions.CustomAction
import org.mozilla.fenix.kaizen.actions.CustomActionRunner
import org.mozilla.fenix.kaizen.containers.KaizenContainerStorage
import org.mozilla.fenix.kaizen.home.folderPathOf
import org.mozilla.fenix.kaizen.settings.KaizenSettings
import org.mozilla.fenix.kaizen.workspaces.MAX_ESSENTIALS
import org.mozilla.fenix.kaizen.workspaces.PinSource
import org.mozilla.fenix.kaizen.workspaces.WorkspaceRepository
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState
import org.mozilla.fenix.nimbus.FxNimbus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import mozilla.components.ui.icons.R as iconsR

/** How Kaizen's "More" menu takes the place of Fenix's main menu. */
object KaizenMenu {
    /** Whether Kaizen's menu replaces Fenix's for [accessPoint]. Custom tabs keep Fenix's menu. */
    fun replaces(accessPoint: MenuAccessPoint): Boolean = accessPoint != MenuAccessPoint.External

    /** Whether the menu comes down from the top of the screen, which it does when the address bar is at the top. */
    fun opensFromTop(context: Context, accessPoint: MenuAccessPoint): Boolean =
        accessPoint == MenuAccessPoint.Browser && !context.contextComponents.settings.shouldUseBottomToolbar

    /** A dialog showing its content at the top of the screen, sliding down from the edge. */
    fun createTopSheetDialog(context: Context, onMenuKey: () -> Unit): Dialog =
        object : Dialog(context, R.style.KaizenTopSheetDialog) {
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
                setWindowAnimations(R.style.KaizenTopSheetAnimation)
            }
        }

    private const val TOP_SHEET_DIM = 0.32f
}

/**
 * The content of the menu dialog when Kaizen replaces Fenix's menu: the "More" menu of the browser, or the
 * extensions of the home screen.
 *
 * @param menuStore Fenix's menu store, which carries out the actions Fenix's menu also has.
 * @param accessPoint Where the menu was opened from.
 * @param fromTop Whether the menu is shown at the top of the screen.
 * @param onDismiss Closes the menu.
 */
@Composable
fun KaizenMenuContent(
    menuStore: MenuStore,
    accessPoint: MenuAccessPoint,
    fromTop: Boolean,
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
                    text = stringResource(R.string.kaizen_menu_extensions),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            ExtensionList(extensions = extensions, onManage = manageExtensions)
            return@MoreMenuSheet
        }

        val context = LocalContext.current
        val settings = remember { fenix.readOnMainThread { KaizenSettings.get(context) } }
        val repository = remember { fenix.readOnMainThread { WorkspaceRepository.get(context) } }
        val tab by fenix.core.store.observeAsComposableState { it.selectedTab }
        val workspaces by repository.state.collectAsState()
        val rowKeys by settings.moreMenuRows.collectAsState()
        val customActions by settings.customActions.collectAsState()
        var extensionsExpanded by remember { mutableStateOf(false) }
        var pickingSplit by remember { mutableStateOf(false) }
        val current = tab ?: return@MoreMenuSheet
        val state = moreMenuState(fenix, current, workspaces, menuState.isDesktopMode, menuState)
        val actions = MenuActions(context, fenix, menuStore, repository, settings, current, onDismiss)

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
                    else -> actions.run(entry, state, workspaces)
                }
            },
            onManageExtensions = manageExtensions,
        )
    }
}

@Composable
private fun moreMenuState(
    fenix: Components,
    tab: TabSessionState,
    workspaces: WorkspaceState,
    isDesktopMode: Boolean,
    menuState: org.mozilla.fenix.components.menu.store.MenuState,
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
        isBookmarked = menuState.browserMenuState?.bookmarkState?.isBookmarked == true,
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
        canSummarize = menuState.summarizationMenuState.visible && menuState.summarizationMenuState.enabled,
        isWebPage = url.startsWith("http://") || url.startsWith("https://"),
    )
}

/** Carries out the entries of the "More" menu for [tab]. */
@Suppress("LongParameterList")
private class MenuActions(
    context: Context,
    private val components: Components,
    private val menuStore: MenuStore,
    private val repository: WorkspaceRepository,
    private val settings: KaizenSettings,
    private val tab: TabSessionState,
    private val dismiss: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val sessionUseCases = components.useCases.sessionUseCases

    @Suppress("CyclomaticComplexMethod")
    fun run(entry: MoreMenuEntry, state: MoreMenuState, workspaces: WorkspaceState) {
        val item = (entry as? MoreMenuEntry.BuiltIn)?.item
        if (item == null) {
            runCustomAction((entry as MoreMenuEntry.Custom).action, workspaces)
            dismiss()
            return
        }
        val pin = workspaces.pinOf(tab.id)
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
            MoreMenuItem.SUMMARIZE -> dispatch(MenuAction.Navigate.Summarizer)
            MoreMenuItem.REPORT_BROKEN_SITE -> dispatch(MenuAction.Navigate.WebCompatReporter)
            MoreMenuItem.OPEN_IN_APP -> dispatch(MenuAction.OpenInApp)
            MoreMenuItem.SETTINGS -> dispatch(MenuAction.Navigate.Settings)
            MoreMenuItem.SAVE_AS_PDF -> andDismiss { sessionUseCases.saveToPdf(tab.id) }
            MoreMenuItem.PRINT -> andDismiss { sessionUseCases.printContent(tab.id) }
            MoreMenuItem.PIN_TAB -> andDismiss {
                if (pin != null && !pin.essential) repository.unpin(setOf(pin.id)) else repository.pinTabs(listOf(tab.toPinSource()))
            }
            MoreMenuItem.ESSENTIAL_TAB -> andDismiss { toggleEssential(workspaces) }
            MoreMenuItem.RESET_PINNED_URL -> andDismiss { pin?.url?.let { sessionUseCases.loadUrl(it, tab.id) } }
            MoreMenuItem.REPLACE_PINNED_URL -> andDismiss {
                pin?.let { repository.replacePinUrl(it.id, tab.content.url, tab.content.title) }
            }
            MoreMenuItem.SPLIT_VIEW -> andDismiss { repository.unsplit(setOf(tab.id)) }
            MoreMenuItem.EXTENSIONS -> Unit
        }
    }

    /** Shows [tab] and [other] side by side, next to each other in the tab list when neither is pinned. */
    fun split(workspaces: WorkspaceState, other: TabSessionState) {
        repository.createSplit(tab.id, other.id)
        if (workspaces.pinOf(tab.id) == null && workspaces.pinOf(other.id) == null) {
            components.core.store.dispatch(TabListAction.MoveTabsAction(listOf(other.id), tab.id, placeAfter = true))
        }
        dismiss()
    }

    private fun toggleEssential(workspaces: WorkspaceState) {
        val pin = workspaces.pinOf(tab.id)
        if (pin?.essential == true) {
            repository.removeFromEssentials(setOf(pin.id), workspaces.workspaceOf(tab.id))
            return
        }
        val before = repository.state.value.essentials.size
        repository.addToEssentials(
            sources = if (pin == null) listOf(tab.toPinSource()) else emptyList(),
            pinIds = setOfNotNull(pin?.id),
            perContainer = settings.essentialsPerContainer.value,
        )
        if (repository.state.value.essentials.size == before) {
            Toast.makeText(appContext, appContext.getString(R.string.kaizen_essentials_full, MAX_ESSENTIALS), Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun runCustomAction(action: CustomAction, workspaces: WorkspaceState) {
        val pin = workspaces.pinOf(tab.id)
        val workspaceId = pin?.workspaceId ?: workspaces.workspaceOf(tab.id)
        val container = tab.contextId?.let { id ->
            KaizenContainerStorage.get(appContext).records.value?.firstOrNull { it.contextId == id }?.name
        }
        val actionContext = ActionContext(
            url = tab.content.url,
            title = tab.content.title,
            container = container.orEmpty(),
            workspace = workspaces.workspaces.firstOrNull { it.id == workspaceId }?.name.orEmpty(),
            folderPath = pin?.let { workspaces.folderPathOf(it) }.orEmpty(),
            date = SimpleDateFormat(ISO_DATE_PATTERN, Locale.US).format(Date()),
        )
        components.applicationScope.launch(Dispatchers.Main) {
            val result = CustomActionRunner(components.core.client).run(action, listOf(actionContext))
            Toast.makeText(appContext, result.message(appContext, action, 1), Toast.LENGTH_LONG).show()
        }
    }

    private fun dispatch(action: MenuAction) = menuStore.dispatch(action)

    private inline fun andDismiss(block: () -> Unit) {
        block()
        dismiss()
    }

    private fun TabSessionState.toPinSource() = PinSource(id, content.url, content.title, contextId)

    private companion object {
        const val ISO_DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ssXXX"
    }
}

private fun <T> Components.readOnMainThread(block: () -> T): T =
    strictMode.allowViolation(StrictMode::allowThreadDiskReads, block)
