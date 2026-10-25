/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mozilla.components.concept.storage.HistoryMetadataStorage
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.components.components
import org.mozilla.fenix.dejavu.ui.DejavuButtonGap
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialog
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton

/**
 * What long-pressing a recent search in the search screen offers: deleting that search, or every recent search.
 */
object RecentSearchMenu {
    // Deleting goes on after the menu, or the whole search screen, has closed.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _shown = MutableStateFlow<String?>(null)
    private val _changes = MutableStateFlow(0)

    /** The search term the menu is open for, or `null` while it is closed. */
    val shown: StateFlow<String?> = _shown.asStateFlow()

    /** Goes up each time searches were deleted, so the search screen asks for its suggestions again. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** Opens the menu for [searchTerm]. */
    fun open(searchTerm: String) {
        _shown.value = searchTerm
    }

    internal fun close() {
        _shown.value = null
    }

    /** Closes the menu and deletes [searchTerm] from the recent searches, kept in [storage]. */
    internal fun delete(storage: HistoryMetadataStorage, searchTerm: String) {
        close()
        scope.launch {
            storage.deleteHistoryMetadata(searchTerm)
            _changes.update { it + 1 }
        }
    }

    /** Closes the menu and deletes every recent search in [storage], leaving the history of visited pages as it is. */
    internal fun clear(storage: HistoryMetadataStorage) {
        close()
        scope.launch {
            storage.getHistoryMetadataSince(Long.MIN_VALUE)
                .mapNotNull { it.key.searchTerm }
                .distinct()
                .forEach { storage.deleteHistoryMetadata(it) }
            _changes.update { it + 1 }
        }
    }
}

/** The menu of [RecentSearchMenu], while it is open. */
@Composable
fun RecentSearchMenuDialog() {
    val searchTerm by RecentSearchMenu.shown.collectAsState()
    val term = searchTerm ?: return
    val storage = components.core.historyStorage
    DejavuDialog(
        title = term,
        icon = iconsR.drawable.mozac_ic_history_24,
        onDismissRequest = RecentSearchMenu::close,
        buttons = {
            DejavuDialogButton(
                text = stringResource(R.string.dejavu_recent_search_clear),
                style = DejavuButtonStyle.DangerText,
                onClick = { RecentSearchMenu.clear(storage) },
            )
            DejavuButtonGap()
            DejavuDialogButton(text = stringResource(R.string.dejavu_cancel), onClick = RecentSearchMenu::close)
            DejavuDialogButton(
                text = stringResource(R.string.dejavu_delete),
                style = DejavuButtonStyle.Danger,
                onClick = { RecentSearchMenu.delete(storage, term) },
            )
        },
    ) {
        Text(text = stringResource(R.string.dejavu_recent_search_text))
    }
}
