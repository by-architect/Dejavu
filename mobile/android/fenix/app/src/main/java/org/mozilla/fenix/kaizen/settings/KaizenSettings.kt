/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.fenix.kaizen.actions.TabAction

/**
 * Device local Kaizen preferences. They are not synced.
 */
class KaizenSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _pinnedRowActions =
        MutableStateFlow(readActions(KEY_PINNED_ROW_ACTIONS, DEFAULT_PINNED_ROW_ACTIONS, TabAction.forPinnedRows))
    private val _unpinnedRowActions =
        MutableStateFlow(readActions(KEY_UNPINNED_ROW_ACTIONS, DEFAULT_UNPINNED_ROW_ACTIONS, TabAction.forUnpinnedRows))

    /** Buttons shown on pinned tab rows. */
    val pinnedRowActions: StateFlow<List<TabAction>> = _pinnedRowActions.asStateFlow()

    /** Buttons shown on unpinned tab rows. */
    val unpinnedRowActions: StateFlow<List<TabAction>> = _unpinnedRowActions.asStateFlow()

    /**
     * Enables or disables [action] on pinned or unpinned rows. At most [MAX_ROW_ACTIONS] actions can be enabled; the
     * stored order always follows [TabAction.forPinnedRows] / [TabAction.forUnpinnedRows].
     */
    fun setRowAction(pinned: Boolean, action: TabAction, enabled: Boolean) {
        val flow = if (pinned) _pinnedRowActions else _unpinnedRowActions
        val available = if (pinned) TabAction.forPinnedRows else TabAction.forUnpinnedRows
        val current = flow.value.toSet()
        val updated = if (enabled) current + action else current - action
        if (updated.size > MAX_ROW_ACTIONS || action !in available) return
        val ordered = available.filter { it in updated }
        flow.value = ordered
        prefs.edit {
            putString(if (pinned) KEY_PINNED_ROW_ACTIONS else KEY_UNPINNED_ROW_ACTIONS, ordered.joinToString(",") { it.key })
        }
    }

    private fun readActions(key: String, default: List<TabAction>, available: List<TabAction>): List<TabAction> {
        val chosen = prefs.getString(key, null)?.split(",")?.mapNotNull { TabAction.fromKey(it) }?.toSet()
            ?: default.toSet()
        return available.filter { it in chosen }.take(MAX_ROW_ACTIONS)
    }

    companion object {
        /** The most buttons a tab row can show next to its title. */
        const val MAX_ROW_ACTIONS = 3

        private const val PREFS_NAME = "kaizen_settings"
        private const val KEY_PINNED_ROW_ACTIONS = "pinned_row_actions"
        private const val KEY_UNPINNED_ROW_ACTIONS = "unpinned_row_actions"
        private val DEFAULT_PINNED_ROW_ACTIONS = listOf(TabAction.CLOSE)
        private val DEFAULT_UNPINNED_ROW_ACTIONS = listOf(TabAction.PIN, TabAction.CLOSE)

        @Volatile
        private var instance: KaizenSettings? = null

        /** Returns the process wide [KaizenSettings]. Reads from disk on first use. */
        fun get(context: Context): KaizenSettings =
            instance ?: synchronized(this) {
                instance ?: KaizenSettings(context.applicationContext).also { instance = it }
            }
    }
}
