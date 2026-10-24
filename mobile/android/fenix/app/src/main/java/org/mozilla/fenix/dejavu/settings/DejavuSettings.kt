/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.lib.dataprotect.SecureAbove22Preferences
import org.json.JSONArray
import org.mozilla.fenix.dejavu.actions.ActionPlace
import org.mozilla.fenix.dejavu.actions.CustomAction
import org.mozilla.fenix.dejavu.actions.RowAction
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.browser.BarsLayout
import org.mozilla.fenix.dejavu.containers.ContainerPick
import org.mozilla.fenix.dejavu.history.HistoryGrouping
import org.mozilla.fenix.dejavu.menu.ActionsBarLayout
import org.mozilla.fenix.dejavu.menu.AddressBarButtons
import org.mozilla.fenix.dejavu.menu.MoreMenuLayout

/**
 * Device local Dejavu preferences: where actions are shown, the custom actions and the menus. They are not synced.
 */
class DejavuSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Custom actions can hold API tokens in their headers and addresses, so they are encrypted with the Android
    // Keystore. Where the Keystore fails, they stay in the plain preferences rather than being lost.
    private val secrets by lazy { SecureAbove22Preferences(context, SECRETS_NAME) }

    private val _customActions = MutableStateFlow(readCustomActions())
    private val _pinnedRowKeys = MutableStateFlow(readKeys(KEY_PINNED_ROW_ACTIONS, DEFAULT_PINNED_ROW_ACTIONS))
    private val _unpinnedRowKeys = MutableStateFlow(readKeys(KEY_UNPINNED_ROW_ACTIONS, DEFAULT_UNPINNED_ROW_ACTIONS))
    private val _folderRowKeys = MutableStateFlow(readKeys(KEY_FOLDER_ROW_ACTIONS, DEFAULT_FOLDER_ROW_ACTIONS))
    private val _essentialsPerContainer = MutableStateFlow(
        prefs.getBoolean(KEY_ESSENTIALS_PER_CONTAINER, prefs.getBoolean(KEY_ESSENTIALS_PER_CONTAINER_SYNCED, false)),
    )
    private val _temporaryContainersByDefault =
        MutableStateFlow(prefs.getBoolean(KEY_TEMPORARY_CONTAINERS_BY_DEFAULT, false))
    private val _externalLinkWorkspaceId = MutableStateFlow(prefs.getString(KEY_EXTERNAL_LINK_WORKSPACE, null))
    private val _externalLinkContainer =
        MutableStateFlow(ContainerPick.fromKey(prefs.getString(KEY_EXTERNAL_LINK_CONTAINER, null)))
    private val _moreMenuRows = MutableStateFlow(
        MoreMenuLayout.normalized(MoreMenuLayout.fromJson(prefs.getString(KEY_MORE_MENU_ROWS, null)) ?: MoreMenuLayout.DEFAULT),
    )
    private val _moreMenuEditHidden = MutableStateFlow(prefs.getBoolean(KEY_MORE_MENU_EDIT_HIDDEN, false))
    private val _systemBarsHidden = MutableStateFlow(prefs.getBoolean(KEY_SYSTEM_BARS_HIDDEN, false))
    private val _barsLayout = MutableStateFlow(BarsLayout.fromKey(prefs.getString(KEY_BARS_LAYOUT, null)))
    private val _addressBarButtons = MutableStateFlow(readAddressBarButtons())
    private val _actionsBarKeys = MutableStateFlow(readActionsBar())
    private val _tabSleepMinutes = MutableStateFlow(prefs.getInt(KEY_TAB_SLEEP_MINUTES, DEFAULT_TAB_SLEEP_MINUTES))
    private val _hiddenSelectionKeys = MutableStateFlow(
        prefs.getString(KEY_HIDDEN_SELECTION_ACTIONS, null)?.split(",")?.filter { it.isNotBlank() }?.toSet().orEmpty(),
    )

    /** Actions the user defined, in creation order. */
    val customActions: StateFlow<List<CustomAction>> = _customActions.asStateFlow()

    /** Keys of the [RowAction]s shown on pinned tab rows. */
    val pinnedRowKeys: StateFlow<List<String>> = _pinnedRowKeys.asStateFlow()

    /** Keys of the [RowAction]s shown on unpinned tab rows. */
    val unpinnedRowKeys: StateFlow<List<String>> = _unpinnedRowKeys.asStateFlow()

    /** Keys of the [RowAction]s shown on folder rows. */
    val folderRowKeys: StateFlow<List<String>> = _folderRowKeys.asStateFlow()

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

    /** Whether the "More" menu hides its button that opens the menu's settings. */
    val moreMenuEditHidden: StateFlow<Boolean> = _moreMenuEditHidden.asStateFlow()

    /** Whether the status bar and the navigation bar are hidden while a page shows. */
    val systemBarsHidden: StateFlow<Boolean> = _systemBarsHidden.asStateFlow()

    /** Where the bars show around pages, and so where the "More" menu opens from. */
    val barsLayout: StateFlow<BarsLayout> = _barsLayout.asStateFlow()

    /**
     * The buttons beside the address bar the user chose, which every layout of the bars shows, or `null` before they
     * chose any, while each layout shows the ones of [AddressBarButtons.defaultFor] it, see [addressBarButtonsIn].
     */
    val addressBarButtons: StateFlow<AddressBarButtons?> = _addressBarButtons.asStateFlow()

    /** Buttons of the actions bar below pages, as keys of its entries. See [ActionsBarLayout]. */
    val actionsBarKeys: StateFlow<List<String>> = _actionsBarKeys.asStateFlow()

    /** Keys of the [RowAction]s left out of the selection bar. Every other action, new ones included, is shown. */
    val hiddenSelectionKeys: StateFlow<Set<String>> = _hiddenSelectionKeys.asStateFlow()

    /** Minutes a tab stays awake after it was last looked at, or 0 to never put tabs to sleep. */
    val tabSleepMinutes: StateFlow<Int> = _tabSleepMinutes.asStateFlow()

    fun setTabSleepMinutes(minutes: Int) {
        _tabSleepMinutes.value = minutes
        prefs.edit { putInt(KEY_TAB_SLEEP_MINUTES, minutes) }
    }

    /** The version of the feature tour the user has seen, 0 for none. */
    var featureTourSeen: Int
        get() = prefs.getInt(KEY_FEATURE_TOUR_SEEN, 0)
        set(value) = prefs.edit { putInt(KEY_FEATURE_TOUR_SEEN, value) }

    /** The newest version whose changes the tour showed, like "2.0", or `null` before Dejavu 2.0 showed any. */
    var whatsNewSeen: String?
        get() = prefs.getString(KEY_WHATS_NEW_SEEN, null)
        set(value) = prefs.edit { putString(KEY_WHATS_NEW_SEEN, value) }

    /** How History lists pages. */
    var historyGrouping: HistoryGrouping
        get() = HistoryGrouping.fromKey(prefs.getString(KEY_HISTORY_GROUPING, null))
        set(value) = prefs.edit { putString(KEY_HISTORY_GROUPING, value.key) }

    /** Keys of the buttons of the rows of [place], which must be one of the rows. */
    fun rowKeys(place: ActionPlace): StateFlow<List<String>> = when (place) {
        ActionPlace.PINNED_ROWS -> pinnedRowKeys
        ActionPlace.UNPINNED_ROWS -> unpinnedRowKeys
        else -> folderRowKeys
    }

    /** Enables or disables a button of the rows of [place]. At most [MAX_ROW_ACTIONS] can be enabled. */
    fun setRowAction(place: ActionPlace, key: String, enabled: Boolean) {
        val (flow, prefKey) = when (place) {
            ActionPlace.PINNED_ROWS -> _pinnedRowKeys to KEY_PINNED_ROW_ACTIONS
            ActionPlace.UNPINNED_ROWS -> _unpinnedRowKeys to KEY_UNPINNED_ROW_ACTIONS
            ActionPlace.FOLDER_ROWS -> _folderRowKeys to KEY_FOLDER_ROW_ACTIONS
            else -> return
        }
        val updated = if (enabled) flow.value + key else flow.value - key
        if (updated.distinct().size > MAX_ROW_ACTIONS) return
        flow.value = updated.distinct()
        prefs.edit { putString(prefKey, flow.value.joinToString(",")) }
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

    /**
     * Follows what synced essentials show of Zen's container-specific essentials, which Zen does not sync itself. What
     * the user chose in the settings is kept.
     */
    fun setEssentialsPerContainerFromSync(enabled: Boolean) {
        if (prefs.contains(KEY_ESSENTIALS_PER_CONTAINER)) return
        _essentialsPerContainer.value = enabled
        prefs.edit { putBoolean(KEY_ESSENTIALS_PER_CONTAINER_SYNCED, enabled) }
    }

    fun setMoreMenuRows(rows: List<List<String>>) {
        _moreMenuRows.value = MoreMenuLayout.normalized(rows)
        prefs.edit { putString(KEY_MORE_MENU_ROWS, MoreMenuLayout.toJson(_moreMenuRows.value)) }
    }

    fun setMoreMenuEditHidden(hidden: Boolean) {
        _moreMenuEditHidden.value = hidden
        prefs.edit { putBoolean(KEY_MORE_MENU_EDIT_HIDDEN, hidden) }
    }

    fun setSystemBarsHidden(hidden: Boolean) {
        _systemBarsHidden.value = hidden
        prefs.edit { putBoolean(KEY_SYSTEM_BARS_HIDDEN, hidden) }
    }

    fun setBarsLayout(layout: BarsLayout) {
        _barsLayout.value = layout
        prefs.edit { putString(KEY_BARS_LAYOUT, layout.key) }
    }

    /** The buttons beside the address bar in [layout]: the ones the user chose, or else the ones it starts with. */
    fun addressBarButtonsIn(layout: BarsLayout): AddressBarButtons =
        _addressBarButtons.value ?: AddressBarButtons.defaultFor(layout)

    /** Sets the buttons beside the address bar, which every layout of the bars shows from then on. */
    fun setAddressBarButtons(buttons: AddressBarButtons) {
        _addressBarButtons.value = buttons
        prefs.edit {
            putString(KEY_ADDRESS_BAR_START, buttons.start.orEmpty())
            putString(KEY_ADDRESS_BAR_END, buttons.end.orEmpty())
        }
    }

    /** Lets every layout of the bars show the buttons beside the address bar it starts with again. */
    fun resetAddressBarButtons() {
        _addressBarButtons.value = null
        prefs.edit {
            remove(KEY_ADDRESS_BAR_START)
            remove(KEY_ADDRESS_BAR_END)
        }
    }

    fun resetMoreMenu() {
        _moreMenuRows.value = MoreMenuLayout.DEFAULT
        prefs.edit { remove(KEY_MORE_MENU_ROWS) }
    }

    /** Adds the entry with [key] to the end of the menu's last row, or of a new row when that one is full. */
    fun addToMoreMenu(key: String) {
        val rows = _moreMenuRows.value
        if (rows.any { key in it }) return
        val last = rows.lastOrNull()
        val fits = last != null && last.size < MoreMenuLayout.MAX_PER_ROW && last.none(MoreMenuLayout::isFullRow) &&
            !MoreMenuLayout.isFullRow(key)
        setMoreMenuRows(if (fits) rows.dropLast(1) + listOf(last.orEmpty() + key) else rows + listOf(listOf(key)))
    }

    /** Takes the entry with [key] out of the menu, every time it is there. */
    fun removeFromMoreMenu(key: String) =
        setMoreMenuRows(_moreMenuRows.value.map { row -> row.filterNot { it == key } })

    /** Sets the buttons of the actions bar, up to [ActionsBarLayout.MAX_BUTTONS], a button maybe more than once. */
    fun setActionsBar(keys: List<String>) {
        _actionsBarKeys.value = keys.take(ActionsBarLayout.MAX_BUTTONS)
        prefs.edit { putString(KEY_ACTIONS_BAR, _actionsBarKeys.value.joinToString(",")) }
    }

    /** Adds the entry with [key] to the end of the actions bar, if it has room. */
    fun addToActionsBar(key: String) {
        val keys = _actionsBarKeys.value
        if (key !in keys && keys.size < ActionsBarLayout.MAX_BUTTONS) setActionsBar(keys + key)
    }

    /** Takes the entry with [key] out of the actions bar, every time it is there. */
    fun removeFromActionsBar(key: String) = setActionsBar(_actionsBarKeys.value.filterNot { it == key })

    fun resetActionsBar() = setActionsBar(ActionsBarLayout.DEFAULT)

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
        listOf(ActionPlace.PINNED_ROWS, ActionPlace.UNPINNED_ROWS, ActionPlace.FOLDER_ROWS).forEach { place ->
            setRowAction(place, key = key, enabled = false)
        }
        setSelectionAction(key, enabled = true)
        removeFromMoreMenu(key)
        removeFromActionsBar(key)
        _addressBarButtons.value?.let { buttons ->
            val kept = AddressBarButtons(buttons.start.takeUnless { it == key }, buttons.end.takeUnless { it == key })
            if (kept != buttons) setAddressBarButtons(kept)
        }
    }

    private fun readKeys(key: String, default: List<TabAction>): List<String> =
        prefs.getString(key, null)?.split(",")?.filter { it.isNotBlank() } ?: default.map { it.key }

    private fun readAddressBarButtons(): AddressBarButtons? {
        if (!prefs.contains(KEY_ADDRESS_BAR_START) && !prefs.contains(KEY_ADDRESS_BAR_END)) return null
        return AddressBarButtons(
            start = prefs.getString(KEY_ADDRESS_BAR_START, null)?.takeIf { it.isNotBlank() },
            end = prefs.getString(KEY_ADDRESS_BAR_END, null)?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The saved actions bar. Before it could be changed on its own, the bar showed the first row of the menu, so that
     * is what it starts as, saved right away so that later changes to the menu leave it alone.
     */
    private fun readActionsBar(): List<String> {
        prefs.getString(KEY_ACTIONS_BAR, null)?.let { saved -> return saved.split(",").filter { it.isNotBlank() } }
        val initial = ActionsBarLayout.fromMenu(_moreMenuRows.value)
        prefs.edit { putString(KEY_ACTIONS_BAR, initial.joinToString(",")) }
        return initial
    }

    /** The saved custom actions. Ones an older Dejavu saved in plain text move to the encrypted storage. */
    private fun readCustomActions(): List<CustomAction> {
        val saved = runCatching { secrets.getString(KEY_CUSTOM_ACTIONS) }.getOrNull()
            ?: prefs.getString(KEY_CUSTOM_ACTIONS, null)?.also { saveCustomActions(it) }
        val array = saved?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until array.length()).mapNotNull { CustomAction.fromJson(array.getJSONObject(it)) }
    }

    private fun writeCustomActions() =
        saveCustomActions(JSONArray().apply { _customActions.value.forEach { put(it.toJson()) } }.toString())

    private fun saveCustomActions(json: String) {
        if (runCatching { secrets.putString(KEY_CUSTOM_ACTIONS, json) }.isSuccess) {
            prefs.edit { remove(KEY_CUSTOM_ACTIONS) }
        } else {
            // An older encrypted copy would hide this one when they are read.
            runCatching { secrets.remove(KEY_CUSTOM_ACTIONS) }
            prefs.edit { putString(KEY_CUSTOM_ACTIONS, json) }
        }
    }

    companion object {
        /** The most buttons a tab row can show next to its title. */
        const val MAX_ROW_ACTIONS = 3

        /** How long a tab stays awake by default after it was last looked at. */
        const val DEFAULT_TAB_SLEEP_MINUTES = 20

        // Kept from before the rename to Dejavu, so saved data still loads.
        private const val PREFS_NAME = "kaizen_settings"
        private const val SECRETS_NAME = "dejavu_secrets"
        private const val KEY_PINNED_ROW_ACTIONS = "pinned_row_actions"
        private const val KEY_UNPINNED_ROW_ACTIONS = "unpinned_row_actions"
        private const val KEY_FOLDER_ROW_ACTIONS = "folder_row_actions"
        private const val KEY_CUSTOM_ACTIONS = "custom_actions"
        private const val KEY_HIDDEN_SELECTION_ACTIONS = "hidden_selection_actions"
        private const val KEY_ESSENTIALS_PER_CONTAINER = "essentials_per_container"
        private const val KEY_ESSENTIALS_PER_CONTAINER_SYNCED = "essentials_per_container_synced"
        private const val KEY_TEMPORARY_CONTAINERS_BY_DEFAULT = "temporary_containers_by_default"
        private const val KEY_EXTERNAL_LINK_WORKSPACE = "external_link_workspace"
        private const val KEY_EXTERNAL_LINK_CONTAINER = "external_link_container"
        private const val KEY_MORE_MENU_ROWS = "more_menu_rows"
        private const val KEY_MORE_MENU_EDIT_HIDDEN = "more_menu_edit_hidden"
        private const val KEY_SYSTEM_BARS_HIDDEN = "system_bars_hidden"
        private const val KEY_BARS_LAYOUT = "bars_layout"
        private const val KEY_ADDRESS_BAR_START = "address_bar_start"
        private const val KEY_ADDRESS_BAR_END = "address_bar_end"
        private const val KEY_ACTIONS_BAR = "actions_bar"
        private const val KEY_HISTORY_GROUPING = "history_grouping"
        private const val KEY_TAB_SLEEP_MINUTES = "tab_sleep_minutes"
        private const val KEY_FEATURE_TOUR_SEEN = "feature_tour_seen"
        private const val KEY_WHATS_NEW_SEEN = "whats_new_seen"
        private val DEFAULT_PINNED_ROW_ACTIONS = listOf(TabAction.CLOSE)
        private val DEFAULT_UNPINNED_ROW_ACTIONS = listOf(TabAction.PIN, TabAction.CLOSE)
        private val DEFAULT_FOLDER_ROW_ACTIONS = listOf(TabAction.SLEEP)

        @Volatile
        private var instance: DejavuSettings? = null

        /** Returns the process wide [DejavuSettings]. Reads from disk on first use. */
        fun get(context: Context): DejavuSettings =
            instance ?: synchronized(this) {
                instance ?: DejavuSettings(context.applicationContext).also { instance = it }
            }

        /** Returns the settings if they have already been read, without touching the disk. */
        fun peek(): DejavuSettings? = instance
    }
}

/** The buttons chosen with [keys] for the rows of [place], in display order. */
fun resolveRowActions(keys: List<String>, place: ActionPlace, customActions: List<CustomAction>): List<RowAction> =
    RowAction.available(place, customActions).filter { it.key in keys }.take(DejavuSettings.MAX_ROW_ACTIONS)

/** The actions of the selection bar in display order, without the ones hidden with [hiddenKeys]. */
fun resolveSelectionActions(hiddenKeys: Set<String>, customActions: List<CustomAction>): List<RowAction> =
    RowAction.available(ActionPlace.SELECTION, customActions).filter { it.key !in hiddenKeys }
