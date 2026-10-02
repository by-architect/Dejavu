/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.history

import android.content.Context
import androidx.annotation.StringRes
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.fenix.R
import org.mozilla.fenix.components.history.HistoryDB
import org.mozilla.fenix.components.history.PagedHistoryProvider
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.sync.workspaceIconText
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.library.history.History
import org.mozilla.fenix.library.history.HistoryDataSource
import org.mozilla.fenix.library.history.positionWithOffset

/** How History lists pages: by day, the way Firefox does, or by the workspace (and folder) they were opened in. */
enum class HistoryGrouping(val key: String, @StringRes val label: Int) {
    DATE("date", R.string.dejavu_history_group_by_date),
    WORKSPACE("workspace", R.string.dejavu_history_group_by_workspace),
    FOLDER("folder", R.string.dejavu_history_group_by_folder),
    ;

    companion object {
        fun fromKey(key: String?): HistoryGrouping = entries.firstOrNull { it.key == key } ?: DATE
    }
}

/** The header each page of History is shown under when it is grouped, see [HistoryGrouping]. */
class HistoryHeaders(private val headers: Map<History, Header>) {
    /** A header: [key] tells the groups apart, [label] is shown. */
    data class Header(val key: Any, val label: String)

    /** The key of [item]'s group, or `null` if it has none. */
    fun keyOf(item: History): Any? = headers[item]?.key

    /** The label of [item]'s group, or `null` if it has none. */
    fun labelOf(item: History): String? = headers[item]?.label
}

/** Dejavu's side of the History screen: the grouping button and the pages to list for it. */
object DejavuHistory {
    /**
     * The pages to list, in the grouping chosen in the settings. [onHeaders] gets the headers to show, or `null` to show
     * days, before the first pages load.
     */
    fun dataSource(
        context: Context,
        provider: PagedHistoryProvider,
        onHeaders: (HistoryHeaders?) -> Unit,
    ): PagingSource<Int, History> {
        val grouping = DejavuSettings.peek()?.historyGrouping ?: HistoryGrouping.DATE
        if (grouping == HistoryGrouping.DATE) {
            onHeaders(null)
            return HistoryDataSource(historyProvider = provider)
        }
        return GroupedHistoryDataSource(context.applicationContext, provider, grouping, onHeaders)
    }

    /** Asks how to group History, and calls [onChanged] once the choice is saved. */
    fun showGroupingDialog(context: Context, onChanged: () -> Unit) {
        val settings = DejavuSettings.get(context)
        val choices = HistoryGrouping.entries
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dejavu_history_group_by)
            .setSingleChoiceItems(
                choices.map { context.getString(it.label) }.toTypedArray(),
                choices.indexOf(settings.historyGrouping),
            ) { dialog, which ->
                dialog.dismiss()
                if (choices[which] != settings.historyGrouping) {
                    settings.historyGrouping = choices[which]
                    onChanged()
                }
            }
            .setNegativeButton(R.string.dejavu_cancel, null)
            .show()
    }
}

/**
 * Reads up to [MAX_PAGES] pages of history, since grouping has to see them all first, unlike the list by day. Sorts
 * them into groups by where they were opened, newest first inside each group, then hands them out page by page.
 */
private class GroupedHistoryDataSource(
    private val context: Context,
    private val provider: PagedHistoryProvider,
    private val grouping: HistoryGrouping,
    private val onHeaders: (HistoryHeaders?) -> Unit,
) : PagingSource<Int, History>() {
    private var sorted: List<History>? = null

    override fun getRefreshKey(state: PagingState<Int, History>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, History> {
        val all = sorted ?: readAndGroup().also { sorted = it }
        val offset = params.key ?: 0
        val page = all.drop(offset).take(params.loadSize)
        val next = offset + page.size
        return LoadResult.Page(data = page, prevKey = null, nextKey = next.takeIf { it < all.size })
    }

    private suspend fun readAndGroup(): List<History> {
        val read = mutableListOf<HistoryDB>()
        var offset = 0
        while (read.size < MAX_PAGES) {
            val chunk = provider.getHistory(offset, CHUNK)
            if (chunk.isEmpty()) break
            read += chunk
            offset += CHUNK
        }
        val items = read.take(MAX_PAGES).positionWithOffset(0)
        val origins = VisitOrigins.get(context).apply { load() }
        val state = WorkspaceRepository.get(context).state.value
        val groups = items.associateWith { item -> groupOf(item, origins, state) }
        val headers = groups.mapValues { (_, group) -> HistoryHeaders.Header(group.key, group.label) }
        onHeaders(HistoryHeaders(headers))
        return items.sortedWith(compareBy({ groups.getValue(it).workspaceOrder }, { groups.getValue(it).folderOrder }))
    }

    private class Group(val workspaceOrder: Int, val folderOrder: Int, val label: String) {
        val key: Any
            get() = workspaceOrder to folderOrder
    }

    private fun groupOf(item: History, origins: VisitOrigins, state: WorkspaceState): Group {
        val origin = urlOf(item)?.let(origins::originOf)
        val workspaceIndex = state.workspaces.indexOfFirst { it.id == origin?.workspaceId }
        if (origin == null || workspaceIndex < 0) {
            return Group(Int.MAX_VALUE, NO_FOLDER, context.getString(R.string.dejavu_history_other_pages))
        }
        val workspace = state.workspaces[workspaceIndex]
        val workspaceLabel = listOfNotNull(workspaceIconText(workspace.icon), workspace.name).joinToString(" ")
        if (grouping != HistoryGrouping.FOLDER) return Group(workspaceIndex, NO_FOLDER, workspaceLabel)
        val folderIndex = state.pins.indexOfFirst { it.id == origin.folderId && it.isFolder }
        if (folderIndex < 0) return Group(workspaceIndex, NO_FOLDER, workspaceLabel)
        val path = generateSequence(state.pins[folderIndex]) { folder ->
            state.pins.firstOrNull { it.id == folder.parentId && it.isFolder }
        }.map { it.title }.toList().asReversed()
        return Group(workspaceIndex, folderIndex, (listOf(workspaceLabel) + path).joinToString(PATH_SEPARATOR))
    }

    private fun urlOf(item: History): String? = when (item) {
        is History.Regular -> item.url
        is History.Metadata -> item.url
        is History.Group -> item.items.firstOrNull()?.url
    }

    private companion object {
        const val MAX_PAGES = 1500
        const val CHUNK = 100
        const val NO_FOLDER = -1
        const val PATH_SEPARATOR = " › "
    }
}
