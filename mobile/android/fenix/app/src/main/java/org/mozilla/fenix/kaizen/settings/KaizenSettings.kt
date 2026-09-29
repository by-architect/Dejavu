/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.mozilla.fenix.kaizen.actions.CustomAction
import org.mozilla.fenix.kaizen.actions.RowAction
import org.mozilla.fenix.kaizen.actions.TabAction
import org.mozilla.fenix.kaizen.containers.ContainerPick
import org.mozilla.fenix.kaizen.menu.MoreMenuLayout

/**
 * Device local Kaizen preferences: the buttons of tab rows and the custom actions. They are not synced.
 */
class KaizenSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _customActions = MutableStateFlow(readCustomActions())
    private val _pinnedRowKeys = MutableStateFlow(readKeys(KEY_PINNED_ROW_ACTIONS, DEFAULT_PINNED_ROW_ACTIONS))
    private val _unpinnedRowKeys = MutableStateFlow(readKeys(KEY_UNPINNED_ROW_ACTIONS, DEFAULT_UNPINNED_ROW_ACTIONS))
    private val _essentialsPerContainer = MutableStateFlow(prefs.getBoolean(KEY_ESSENTIALS_PER_CONTAINER, false))
    private val _temporaryContainersByDefault =
        MutableStateFlow(prefs.getBoolean(KEY_TEMPORARY_CONTAINERS_BY_DEFAULT, false))
    private val _externalLinkWorkspaceId = MutableStateFlow(prefs.getString(KEY_EXTERNAL_LINK_WORKSPACE, null))
    private val _externalLinkContainer =
        MutableStateFlow(ContainerPick.fromKey(prefs.getString(KEY_EXTERNAL_LINK_CONTAINER, null)))
    private val _moreMenuRows = MutableStateFlow(
        MoreMenuLayout.normalized(MoreMenuLayout.fromJson(prefs.getString(KEY_MORE_MENU_ROWS, null)) ?: MoreMenuLayout.DEFAULT),
    )
    private val _hiddenSelectionKeys = MutableStateFlow(
        prefs.getString(KEY_HIDDEN_SELECTION_ACTIONS, null)?.split(",")?.filter { it.isNotBlank() }?.toSet().orEmpty(),
    )

    /** Actions the user defined, in creation order. */
    val customActions: StateFlow<List<CustomAction>> = _customActions.asStateFlow()

    /** Keys of the [RowAction]s shown on pinned tab rows. */
    val pinnedRowKeys: StateFlow<List<String>> = _pinnedRowKeys.asStateFlow()

    /** Keys of the [RowAction]s shown on unpinned tab rows. */
    val unpinnedRowKeys: StateFlow<List<String>> = _unpinnedRowKeys.asStateFlow()

    /** Whether every container has its own essentials, shown in the workspaces using that container. */
    val essentialsPerContainer: StateFlow<Boolean> = _essentialsPerContainer.asStateFlow()

    /** Whether new tabs of workspaces without a default container open in new temporary containers. */
    val temporaryContainersByDefault: StateFlow<Boolean> = _temporaryContainersByDefault.asStateFlow()

    /** Workspace links from other apps open in, or `null` for the workspace shown last. */
    val externalLinkWorkspaceId: StateFlow<String?> = _externalLinkWorkspaceId.asStateFlow()

    /** Container links from other apps open in, or `null` for the default of their workspace. */
    val externalLinkContainer: StateFlow<ContainerPick?> = _externalLinkContainer.asStateFlow()

    /** Rows of the browser's "More" menu, as keys of its entries. See [MoreMenuLayout]. */
    val moreMenuRows: StateFlow<List<List<String>>> = _moreMenuRows.asStateFlow()

    /** Keys of the [RowAction]s left out of the selection bar. Every other action, new ones included, is shown. */
    val hiddenSelectionKeys: StateFlow<Set<String>> = _hiddenSelectionKeys.asStateFlow()

    /** Enables or disables a row button. At most [MAX_ROW_ACTIONS] can be enabled. */
    fun setRowAction(pinned: Boolean, key: String, enabled: Boolean) {
        val flow = if (pinned) _pinnedRowKeys else _unpinnedRowKeys
        val updated = if (enabled) flow.value + key else flow.value - key
        if (updated.distinct().size > MAX_ROW_ACTIONS) return
        flow.value = updated.distinct()
        prefs.edit { putString(if (pinned) KEY_PINNED_ROW_ACTIONS else KEY_UNPINNED_ROW_ACTIONS, flow.value.joinToString(",")) }
    }

    fun setTemporaryContainersByDefault(enabled: Boolean) {
        _temporaryContainersByDefault.value = enabled
        prefs.edit { putBoolean(KEY_TEMPORARY_CONTAINERS_BY_DEFAULT, enabled) }
    }

    fun setExternalLinkWorkspace(workspaceId: String?) {
        _externalLinkWorkspaceId.value = workspaceId
        prefs.edit { putString(KEY_EXTERNAL_LINK_WORKSPACE, workspaceId) }
    }

    fun setExternalLinkContainer(pick: ContainerPick?) {
        _externalLinkContainer.value = pick
        prefs.edit { putString(KEY_EXTERNAL_LINK_CONTAINER, pick?.key) }
    }

    fun setEssentialsPerContainer(enabled: Boolean) {
        _essentialsPerContainer.value = enabled
        prefs.edit { putBoolean(KEY_ESSENTIALS_PER_CONTAINER, enabled) }
    }

    fun setMoreMenuRows(rows: List<List<String>>) {
        _moreMenuRows.value = MoreMenuLayout.normalized(rows)
        prefs.edit { putString(KEY_MORE_MENU_ROWS, MoreMenuLayout.toJson(_moreMenuRows.value)) }
    }

    fun resetMoreMenu() {
        _moreMenuRows.value = MoreMenuLayout.DEFAULT
        prefs.edit { remove(KEY_MORE_MENU_ROWS) }
    }

    /** Shows or hides an action of the selection bar. */
    fun setSelectionAction(key: String, enabled: Boolean) {
        _hiddenSelectionKeys.value = if (enabled) _hiddenSelectionKeys.value - key else _hiddenSelectionKeys.value + key
        prefs.edit { putString(KEY_HIDDEN_SELECTION_ACTIONS, _hiddenSelectionKeys.value.joinToString(",")) }
    }

    /** Adds [action], or replaces the custom action with the same id. */
    fun saveCustomAction(action: CustomAction) {
        val current = _customActions.value
        _customActions.value = if (current.any { it.id == action.id }) {
            current.map { if (it.id == action.id) action else it }
        } else {
            current + action
        }
        writeCustomActions()
    }

    /** Deletes a custom action and forgets where it was shown. */
    fun deleteCustomAction(id: String) {
        _customActions.value = _customActions.value.filterNot { it.id == id }
        writeCustomActions()
        val key = RowAction.keyOf(id)
        setRowAction(pinned = true, key = key, enabled = false)
        setRowAction(pinned = false, key = key, enabled = false)
        setSelectionAction(key, enabled = true)
        setMoreMenuRows(_moreMenuRows.value.map { row -> row - key })
    }

    private fun readKeys(key: String, default: List<TabAction>): List<String> =
        prefs.getString(key, null)?.split(",")?.filter { it.isNotBlank() } ?: default.map { it.key }

    private fun readCustomActions(): List<CustomAction> {
        val array = prefs.getString(KEY_CUSTOM_ACTIONS, null)?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { CustomAction.fromJson(array.getJSONObject(it)) }
    }

    private fun writeCustomActions() {
        val array = JSONArray().apply { _customActions.value.forEach { put(it.toJson()) } }
        prefs.edit { putString(KEY_CUSTOM_ACTIONS, array.toString()) }
    }

    companion object {
        /** The most buttons a tab row can show next to its title. */
        const val MAX_ROW_ACTIONS = 3

        private const val PREFS_NAME = "kaizen_settings"
        private const val KEY_PINNED_ROW_ACTIONS = "pinned_row_actions"
        private const val KEY_UNPINNED_ROW_ACTIONS = "unpinned_row_actions"
        private const val KEY_CUSTOM_ACTIONS = "custom_actions"
        private const val KEY_HIDDEN_SELECTION_ACTIONS = "hidden_selection_actions"
        private const val KEY_ESSENTIALS_PER_CONTAINER = "essentials_per_container"
        private const val KEY_TEMPORARY_CONTAINERS_BY_DEFAULT = "temporary_containers_by_default"
        private const val KEY_EXTERNAL_LINK_WORKSPACE = "external_link_workspace"
        private const val KEY_EXTERNAL_LINK_CONTAINER = "external_link_container"
        private const val KEY_MORE_MENU_ROWS = "more_menu_rows"
        private val DEFAULT_PINNED_ROW_ACTIONS = listOf(TabAction.CLOSE)
        private val DEFAULT_UNPINNED_ROW_ACTIONS = listOf(TabAction.PIN, TabAction.CLOSE)

        @Volatile
        private var instance: KaizenSettings? = null

        /** Returns the process wide [KaizenSettings]. Reads from disk on first use. */
        fun get(context: Context): KaizenSettings =
            instance ?: synchronized(this) {
                instance ?: KaizenSettings(context.applicationContext).also { instance = it }
            }

        /** Returns the settings if they have already been read, without touching the disk. */
        fun peek(): KaizenSettings? = instance
    }
}

/** The row buttons chosen with [keys], in display order. */
fun resolveRowActions(keys: List<String>, pinned: Boolean, customActions: List<CustomAction>): List<RowAction> =
    RowAction.available(pinned, customActions).filter { it.key in keys }.take(KaizenSettings.MAX_ROW_ACTIONS)

/** The actions of the selection bar in display order, without the ones hidden with [hiddenKeys]. */
fun resolveSelectionActions(hiddenKeys: Set<String>, customActions: List<CustomAction>): List<RowAction> =
    RowAction.selectionBar(customActions).filter { it.key !in hiddenKeys }
