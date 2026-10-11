/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.state.TabSessionState
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.components.components
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.browser.closeShownTab
import org.mozilla.fenix.dejavu.browser.themeOfTab
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.home.ActionTargets
import org.mozilla.fenix.dejavu.home.DeviceTabActions
import org.mozilla.fenix.dejavu.home.HomeDialog
import org.mozilla.fenix.dejavu.home.ItemDialogs
import org.mozilla.fenix.dejavu.home.TabActionRunner
import org.mozilla.fenix.dejavu.home.TabEditor
import org.mozilla.fenix.dejavu.home.label
import org.mozilla.fenix.dejavu.home.titleOf
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.ui.LocalPopupTheme
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.ext.components as contextComponents

/** Whether this action can run on the shown tab, as [state] describes it. */
internal fun TabAction.canRunOnShownTab(state: MoreMenuState): Boolean =
    (!state.isDeviceTab || this in DeviceTabActions) && canRunOnTab(state)

private fun TabAction.canRunOnTab(state: MoreMenuState): Boolean = when (this) {
    TabAction.COPY_LINK -> state.isWebPage
    TabAction.MOVE_TO_WORKSPACE, TabAction.CHANGE_CONTAINER, TabAction.RENAME_TAB -> !state.isPrivate
    TabAction.MOVE_TO_FOLDER, TabAction.NEW_FOLDER -> !state.isPrivate && state.isWebPage
    TabAction.DELETE -> state.isPinned || state.isEssential
    else -> true
}

/** Whether this action asks for something first, like a folder or a name, see [ShownTabActionDialog]. */
internal val TabAction.asksFirst: Boolean
    get() = this in setOf(
        TabAction.MOVE_TO_WORKSPACE,
        TabAction.CHANGE_CONTAINER,
        TabAction.MOVE_TO_FOLDER,
        TabAction.NEW_FOLDER,
        TabAction.RENAME_TAB,
        TabAction.DELETE,
    )

/**
 * Runs [action], one that asks for nothing, on [tab], the shown tab. Closing the tab or putting it to sleep leaves it,
 * for the tab it was opened from or the home screen.
 */
internal fun runOnShownTab(action: TabAction, context: Context, navController: NavController, tab: TabSessionState) {
    val components = context.contextComponents
    when (action) {
        TabAction.CLOSE -> {
            // A pinned tab of a device on the account goes away too, which closes it on the device.
            val repository = WorkspaceRepository.get(context)
            val state = repository.state.value
            val pin = state.pinOf(tab.id)?.takeIf { state.isDeviceWorkspace(it.workspaceId) }
            if (pin != null) repository.deleteItems(setOf(pin.id))
            closeShownTab(components, navController, tab)
        }
        TabAction.SLEEP -> {
            navController.navigate(NavGraphDirections.actionGlobalHome())
            components.core.store.dispatch(EngineAction.SuspendEngineSessionAction(tab.id))
        }
        TabAction.COPY_LINK -> {
            components.clipboardHandler.text = tab.content.url
            Toast.makeText(context, context.resources.getQuantityString(R.plurals.dejavu_links_copied, 1, 1), Toast.LENGTH_SHORT)
                .show()
        }
        TabAction.DUPLICATE -> components.useCases.tabsUseCases.duplicateTab(tab, selectNewTab = true)
        else -> Unit
    }
}

/**
 * Asks for what [action] on [tab], the shown tab, needs, like a folder, a workspace or a name, then carries it out.
 *
 * @param onDone Invoked once the dialog is closed, whether the action ran or not.
 */
@Composable
internal fun ShownTabActionDialog(
    action: TabAction,
    tab: TabSessionState,
    navController: NavController,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val fenix = components
    val repository = remember { fenix.readOnMainThread { WorkspaceRepository.get(context) } }
    val settings = remember { fenix.readOnMainThread { DejavuSettings.get(context) } }
    val containerStorage = remember { DejavuContainerStorage.get(context) }
    val scope = rememberCoroutineScope()
    val workspaces by repository.state.collectAsState()
    val records by containerStorage.records.collectAsState()
    val containers = remember(records) { records.orEmpty().associateBy { it.contextId } }
    val runner = remember {
        TabActionRunner(
            context = context,
            components = fenix,
            repository = repository,
            settings = settings,
            containerStorage = containerStorage,
            navController = navController,
            scope = scope,
            notify = { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() },
            openTab = { fenix.useCases.tabsUseCases.selectTab(it) },
        )
    }
    val dialog = remember { shownTabDialog(action, tab, workspaces) }
    if (dialog == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val theme = if (tab.content.private) null else workspaces.themeOfTab(tab.id)
    CompositionLocalProvider(LocalPopupTheme provides theme) {
        ItemDialogs(
            dialog = dialog,
            state = workspaces,
            containers = containers,
            editor = object : TabEditor by runner {
                // Deleting the shown tab's pin closes the tab too, so the page has to go.
                override fun onDeleteItems(targets: ActionTargets) {
                    runner.onDeleteItems(targets)
                    navController.navigate(NavGraphDirections.actionGlobalHome())
                }
            },
            onDismiss = onDone,
        )
    }
}

/** The dialog [action] needs for [tab], the shown tab, or `null` when it needs none. */
private fun shownTabDialog(action: TabAction, tab: TabSessionState, workspaces: WorkspaceState): HomeDialog? {
    val pin = workspaces.pinOf(tab.id)
    val workspaceId = pin?.workspaceId ?: workspaces.workspaceOf(tab.id)
    val restricted = workspaces.isDeviceWorkspace(workspaceId)
    val targets = if (pin != null) {
        ActionTargets.ofPin(pin, tab, restricted)
    } else {
        ActionTargets(tabs = listOf(tab), restricted = restricted)
    }
    return when (action) {
        TabAction.MOVE_TO_WORKSPACE -> HomeDialog.MoveToWorkspace(workspaceId, targets)
        TabAction.CHANGE_CONTAINER -> HomeDialog.ChangeContainer(targets)
        TabAction.MOVE_TO_FOLDER -> HomeDialog.MoveToFolder(workspaceId, targets)
        TabAction.NEW_FOLDER -> HomeDialog.NewFolder(workspaceId, parentId = pin?.parentId, targets = targets)
        TabAction.RENAME_TAB -> if (pin != null) {
            HomeDialog.RenameTab(pin.id, null, pin.label(tab))
        } else {
            HomeDialog.RenameTab(null, tab.id, workspaces.titleOf(tab))
        }
        TabAction.DELETE -> targets.takeIf { pin != null }?.let { HomeDialog.DeleteItems(it) }
        else -> null
    }
}
