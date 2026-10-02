/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import android.content.Context
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.prompt.ShareData
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.dejavu.actions.ActionContext
import org.mozilla.fenix.dejavu.actions.CustomAction
import org.mozilla.fenix.dejavu.actions.CustomActionRunner
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.containers.ContainerPick
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.containers.TemporaryContainers
import org.mozilla.fenix.dejavu.containers.reopenInContainer
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.workspaces.MAX_ESSENTIALS
import org.mozilla.fenix.dejavu.workspaces.PinPlacement
import org.mozilla.fenix.dejavu.workspaces.PinSource
import org.mozilla.fenix.dejavu.workspaces.PinnedItem
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository

/**
 * Carries out tab actions on [ActionTargets], on the home screen and for the shown tab in the browser. Actions on
 * folders reach every tab inside them, subfolders included.
 *
 * @param notify Shows a short message about what happened.
 * @param openTab Selects the given tab and shows it in the browser.
 */
@Suppress("TooManyFunctions", "LongParameterList")
internal class TabActionRunner(
    private val context: Context,
    private val components: Components,
    private val repository: WorkspaceRepository,
    private val settings: DejavuSettings,
    private val containerStorage: DejavuContainerStorage,
    private val navController: NavController,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
    private val openTab: (String) -> Unit,
) : TabEditor {
    private val tabsUseCases = components.useCases.tabsUseCases
    private val store = components.core.store
    private val actionRunner by lazy { CustomActionRunner(components.core.client) }

    /** Runs a built-in action that needs no further input from the user, from the page of [workspaceId]. */
    @Suppress("CyclomaticComplexMethod")
    fun run(action: TabAction, targets: ActionTargets, workspaceId: String) {
        when (action) {
            TabAction.CLOSE -> closeTabs(targets.openTabs.map { it.id })
            TabAction.PIN -> repository.pinTabs(targets.tabs.map { it.toPinSource() })
            TabAction.UNPIN -> {
                // Like dragging a pin out: unpinned tabs stay as tabs, the closed ones reopened without loading.
                val pins = targets.allPins.filterNot { it.essential }
                reopenClosed(pins)
                repository.unpin(pins.map { it.id }.toSet())
            }
            TabAction.SLEEP -> targets.awakeTabs.forEach { store.dispatch(EngineAction.SuspendEngineSessionAction(it.id)) }
            TabAction.BOOKMARK -> bookmark(targets.links)
            TabAction.SHARE -> share(targets.links)
            TabAction.COPY_LINK -> copyLinks(targets.links)
            TabAction.DUPLICATE -> duplicate(targets)
            TabAction.RESET_PIN -> {
                targets.changedPins.forEach { (pin, tab) ->
                    pin.url?.let { components.useCases.sessionUseCases.loadUrl(it, tab.id) }
                }
                repository.forgetOpenPages(targets.closedChangedPins.map { it.id }.toSet())
            }
            TabAction.ADD_TO_ESSENTIALS -> addToEssentials(targets)
            TabAction.REMOVE_FROM_ESSENTIALS -> removeFromEssentials(targets.pins.filter { it.essential }, workspaceId)
            TabAction.UNPACK_FOLDER -> targets.folders.forEach { repository.unpackFolder(it.id) }
            TabAction.DELETE -> onDeleteItems(targets)
            TabAction.SPLIT_VIEW -> split(targets)
            TabAction.UNSPLIT -> repository.unsplit(targets.splitTabIds)
            TabAction.MOVE_TO_WORKSPACE, TabAction.MOVE_TO_FOLDER, TabAction.NEW_FOLDER, TabAction.NEW_SUBFOLDER,
            TabAction.RENAME_FOLDER, TabAction.RENAME_TAB, TabAction.CHANGE_CONTAINER,
            -> Unit
        }
    }

    /** Sends the requests of a custom action for [targets]. */
    fun runCustom(action: CustomAction, targets: ActionTargets) {
        val contexts = actionContexts(targets)
        if (contexts.isEmpty()) return
        scope.launch {
            val result = actionRunner.run(action, contexts)
            notify(result.message(context, action, contexts.size))
        }
    }

    override fun onCreateFolder(workspaceId: String, parentId: String?, name: String, targets: ActionTargets) {
        repository.createFolder(
            workspaceId = workspaceId,
            parentId = parentId,
            name = name,
            itemIds = targets.itemIds,
            newPins = targets.tabs.map { it.toPinSource() },
        )
    }

    override fun onRenameFolder(folderId: String, name: String) = repository.renameFolder(folderId, name)

    override fun onRenameTab(pinId: String?, tabId: String?, name: String) {
        when {
            pinId != null -> repository.renamePin(pinId, name)
            tabId != null -> repository.renameTab(tabId, name)
        }
    }

    override fun onMoveToFolder(workspaceId: String, targets: ActionTargets, folderId: String?) {
        repository.placePins(
            workspaceId = workspaceId,
            itemIds = targets.itemIds,
            newPins = targets.tabs.map { it.toPinSource() },
            placement = folderId?.let { PinPlacement.Into(it) } ?: PinPlacement.Edge(atEnd = true),
        )
    }

    override fun onDeleteItems(targets: ActionTargets) {
        val tabIds = targets.openTabs.map { it.id }
        repository.deleteItems(targets.itemIds)
        closeTabs(tabIds)
    }

    override fun onMoveToWorkspace(targets: ActionTargets, workspaceId: String) {
        repository.moveToWorkspace(
            tabIds = targets.tabs.map { it.id }.toSet(),
            itemIds = targets.itemIds,
            workspaceId = workspaceId,
        )
    }

    override fun onChangeContainer(targets: ActionTargets, pick: ContainerPick) {
        val contextId = when (pick) {
            ContainerPick.NoContainer -> null
            ContainerPick.Temporary -> TemporaryContainers.create(store, containerStorage)
            is ContainerPick.Container -> pick.contextId
        }
        repository.setPinContainer(targets.allPins.map { it.id }.toSet(), contextId)
        val selectedTabId = store.state.selectedTabId
        targets.openTabs.filter { it.contextId != contextId }.forEach { tab ->
            val selected = tab.id == selectedTabId
            components.reopenInContainer(tab, contextId, repository, selected = selected, load = selected || tab.isAwake)
        }
    }

    override fun onManageContainers() {
        navController.navigate(R.id.dejavu_containers_graph)
    }

    private fun closeTabs(tabIds: List<String>) {
        val open = tabIds.filter { store.state.findTab(it) != null }
        if (open.isNotEmpty()) tabsUseCases.removeTabs(open)
    }

    /** Shows the two open tabs of [targets] together, next to each other when they are both unpinned. */
    private fun split(targets: ActionTargets) {
        val (first, second) = targets.openTabs.takeIf { it.size == 2 } ?: return
        repository.createSplit(first.id, second.id)
        if (targets.pinnedTabs.isEmpty() && targets.folderTabs.isEmpty()) {
            store.dispatch(TabListAction.MoveTabsAction(listOf(second.id), first.id, placeAfter = true))
        }
        openTab(first.id)
    }

    /** Opens the closed ones of [pins] again without loading them, and returns the new tabs. */
    fun reopenClosed(pins: List<PinnedItem>): List<String> =
        pins.filter { pin -> pin.tabId == null || store.state.findTab(pin.tabId) == null }.mapNotNull { pin ->
            pin.pageUrl.takeIf { it.isNotEmpty() }?.let { url ->
                tabsUseCases.addTab(
                    url = url,
                    selectTab = false,
                    startLoading = false,
                    title = pin.openTitle ?: pin.title,
                    contextId = pin.containerId,
                    source = SessionState.Source.Internal.None,
                ).also { repository.attachPinned(pin.id, it) }
            }
        }

    /** Makes [targets] essentials, the pinned tabs inside folders included, as far as there is room. */
    fun addToEssentials(targets: ActionTargets) {
        val pins = targets.allPins.filterNot { it.essential }
        val candidates = targets.tabs.size + pins.size
        val before = repository.state.value.essentials.size
        repository.addToEssentials(
            sources = targets.tabs.map { it.toPinSource() },
            pinIds = pins.map { it.id }.toSet(),
            perContainer = settings.essentialsPerContainer.value,
        )
        if (repository.state.value.essentials.size - before < candidates) {
            notify(context.getString(R.string.dejavu_essentials_full, MAX_ESSENTIALS))
        }
    }

    /** Turns essentials back into normal tabs of [workspaceId]; closed ones are opened again without loading. */
    private fun removeFromEssentials(essentials: List<PinnedItem>, workspaceId: String) {
        reopenClosed(essentials)
        repository.removeFromEssentials(essentials.map { it.id }.toSet(), workspaceId)
    }

    private fun bookmark(links: List<Pair<String, String>>) {
        val valid = links.filter { it.first.isNotBlank() }
        if (valid.isEmpty()) return
        scope.launch {
            valid.forEach { (url, title) -> components.useCases.bookmarksUseCases.addBookmark(url, title.ifBlank { url }) }
            notify(context.resources.getQuantityString(R.plurals.dejavu_bookmarked, valid.size, valid.size))
        }
    }

    private fun share(links: List<Pair<String, String>>) {
        val data = links.filter { it.first.isNotBlank() }.map { (url, title) -> ShareData(title = title, url = url, private = false) }
        if (data.isEmpty()) return
        navController.navigate(NavGraphDirections.actionGlobalShareFragment(data = data.toTypedArray()))
    }

    fun copyLinks(links: List<Pair<String, String>>) {
        val urls = links.map { it.first }.filter { it.isNotBlank() }
        if (urls.isEmpty()) return
        components.clipboardHandler.text = urls.joinToString("\n")
        notify(context.resources.getQuantityString(R.plurals.dejavu_links_copied, urls.size, urls.size))
    }

    /** Opens a copy of every tab of [targets], the ones inside folders included, without showing them. */
    private fun duplicate(targets: ActionTargets) {
        val openTabs = targets.openTabs
        openTabs.forEach { tabsUseCases.duplicateTab(it, selectNewTab = false) }
        targets.allPins.filter { pin -> openTabs.none { it.id == pin.tabId } }.forEach { pin ->
            val url = pin.pageUrl.takeIf { it.isNotEmpty() } ?: return@forEach
            tabsUseCases.addTab(
                url = url,
                selectTab = false,
                title = pin.openTitle ?: pin.title,
                contextId = pin.containerId,
                source = SessionState.Source.Internal.None,
            )
        }
    }

    /** The values of the custom action variables for every target. */
    private fun actionContexts(targets: ActionTargets): List<ActionContext> {
        val state = repository.state.value
        val containerNames = containerStorage.records.value.orEmpty().associate { it.contextId to it.name }
        val date = SimpleDateFormat(ISO_DATE_PATTERN, Locale.US).format(Date())
        fun workspaceName(id: String) = state.workspaces.firstOrNull { it.id == id }?.name.orEmpty()

        val tabContexts = targets.tabs.map { tab ->
            ActionContext(
                url = tab.content.url,
                title = tab.content.title,
                container = tab.contextId?.let { containerNames[it] }.orEmpty(),
                workspace = workspaceName(state.workspaceOf(tab.id)),
                folderPath = "",
                date = date,
            )
        }
        val pinnedTabs = targets.pinnedTabs + targets.folderTabs
        val pinContexts = targets.allPins.map { pin ->
            val tab = pinnedTabs.firstOrNull { it.id == pin.tabId }
            ActionContext(
                url = tab?.content?.url ?: pin.pageUrl,
                title = tab?.content?.title?.ifBlank { null } ?: pin.openTitle ?: pin.title,
                container = (tab?.contextId ?: pin.containerId)?.let { containerNames[it] }.orEmpty(),
                workspace = workspaceName(pin.workspaceId ?: state.activeWorkspaceId),
                folderPath = state.folderPathOf(pin),
                date = date,
            )
        }
        return tabContexts + pinContexts
    }

    private fun TabSessionState.toPinSource() = PinSource(id, content.url, content.title, contextId)

    private companion object {
        const val ISO_DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ssXXX"
    }
}
