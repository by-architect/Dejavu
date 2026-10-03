/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.library.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import org.mozilla.fenix.dejavu.history.HistoryHeaders
import org.mozilla.fenix.library.history.viewholders.HistoryListItemViewHolder
import org.mozilla.fenix.selection.SelectionHolder

/** Adapter for the list of visited pages, that uses Paging 3 versions of the Paging library. */
class HistoryAdapter(
    private val store: HistoryFragmentStore,
    private val onRecentlyClosedClicked: () -> Unit,
    private val onHistoryItemClicked: (History) -> Unit,
    private val onDeleteInitiated: (Set<History>) -> Unit,
    private val onEmptyStateChanged: (Boolean) -> Unit,
) : PagingDataAdapter<History, HistoryListItemViewHolder>(historyDiffCallback), SelectionHolder<History> {

    private var mode: HistoryFragmentState.Mode = HistoryFragmentState.Mode.Normal
    private var pendingDeletionItems = emptySet<PendingDeletionHistory>()
    private val itemsWithHeaders: MutableMap<Any, Int> = mutableMapOf()

    /** Dejavu's groups to show headers for instead of days, or `null` for days. */
    var dejavuHeaders: HistoryHeaders? = null
        set(value) {
            field = value
            itemsWithHeaders.clear()
            headersChanged = true
        }
    private var headersChanged = false

    init {
        // Rows that stay in the list keep their old header unless they are bound again once the new list is shown.
        addOnPagesUpdatedListener {
            if (headersChanged) {
                headersChanged = false
                itemsWithHeaders.clear()
                notifyItemRangeChanged(0, itemCount)
            }
        }
    }

    // A flag to track the empty state of the list. Items are not being deleted immediately,
    // but hidden from the UI until the Undo snackbar will execute the delayed operation.
    // Whether the adapter has actually zero items or all present items are hidden,
    // the screen should be updated into proper empty/not empty state.
    private var isEmpty = true

    override val selectedItems
        get() = mode.selectedItems

    override fun getItemViewType(position: Int): Int = HistoryListItemViewHolder.LAYOUT_ID

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryListItemViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)

        return HistoryListItemViewHolder(
            view = view,
            selectionHolder = this,
            store = store,
            onHistoryItemClicked = onHistoryItemClicked,
            onRecentlyClosedClicked = onRecentlyClosedClicked,
            onDeleteInitiated = onDeleteInitiated,
        )
    }

    fun updateMode(mode: HistoryFragmentState.Mode) {
        this.mode = mode
        // Update the delete button alpha that the first item holds
        if (itemCount > 0) notifyItemChanged(0)
    }

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod")
    override fun onBindViewHolder(holder: HistoryListItemViewHolder, position: Int) {
        val current = getItem(position) ?: return
        var isPendingDeletion = false
        var groupPendingDeletionCount = 0
        var timeGroup: HistoryItemTimeGroup? = null
        val headers = dejavuHeaders
        val headerKey: Any = headers?.keyOf(current) ?: current.historyTimeGroup
        if (position == 0) {
            isEmpty = true
        }

        if (pendingDeletionItems.isNotEmpty()) {
            when (current) {
                is History.Regular -> {
                    isPendingDeletion =
                        pendingDeletionItems.find {
                            it is PendingDeletionHistory.Item && it.visitedAt == current.visitedAt
                        } != null
                }
                is History.Group -> {
                    isPendingDeletion =
                        pendingDeletionItems.find {
                            it is PendingDeletionHistory.Group && it.visitedAt == current.visitedAt
                        } != null

                    if (!isPendingDeletion) {
                        groupPendingDeletionCount =
                            current.items
                                .count { historyMetadata ->
                                    pendingDeletionItems.find {
                                        it is PendingDeletionHistory.MetaData &&
                                            it.key == historyMetadata.historyMetadataKey &&
                                            it.visitedAt == historyMetadata.visitedAt
                                    } != null
                                }
                                .also {
                                    if (it == current.items.size) {
                                        isPendingDeletion = true
                                    }
                                }
                    }
                }
                else -> {}
            }
        }

        // Dejavu's groups: a row starts its group when the row above it, leaving out rows about to be deleted, is in
        // another group. Unlike the map below, this does not depend on the order rows are bound in.
        if (headers != null) {
            val previous = (position - 1 downTo 0).asSequence().mapNotNull { peek(it) }.firstOrNull { !isHidden(it) }
            if (!isPendingDeletion && (previous == null || headers.keyOf(previous) != headerKey)) {
                timeGroup = current.historyTimeGroup
            }
        } else if (itemsWithHeaders.containsKey(headerKey)) {
            // Add or remove the header and position to the map depending on it's deletion status
            if (isPendingDeletion && itemsWithHeaders[headerKey] == position) {
                itemsWithHeaders.remove(headerKey)
            } else if (isPendingDeletion && itemsWithHeaders[headerKey] != position) {
                // do nothing
            } else {
                if (position <= itemsWithHeaders[headerKey] as Int) {
                    itemsWithHeaders[headerKey] = position
                    timeGroup = current.historyTimeGroup
                }
            }
        } else if (!isPendingDeletion) {
            itemsWithHeaders[headerKey] = position
            timeGroup = current.historyTimeGroup
        }

        // If there is a single visible item, it's enough to change the empty state of the view.
        if (isEmpty && !isPendingDeletion) {
            isEmpty = false
            onEmptyStateChanged.invoke(isEmpty)
        } else if (position + 1 == itemCount) {
            // If we reached the bottom of the list and there still has been zero visible items,
            // we can can change the History view state to empty.
            if (isEmpty) {
                onEmptyStateChanged.invoke(isEmpty)
            }
        }

        holder.bind(
            current,
            timeGroup,
            position == 0,
            mode,
            isPendingDeletion,
            groupPendingDeletionCount,
            headerText = timeGroup?.let { headers?.labelOf(current) },
        )
    }

    /** Whether [item] is hidden because it is about to be deleted. */
    private fun isHidden(item: History): Boolean = pendingDeletionItems.isNotEmpty() && when (item) {
        is History.Regular -> pendingDeletionItems.any {
            it is PendingDeletionHistory.Item && it.visitedAt == item.visitedAt
        }
        is History.Group -> pendingDeletionItems.any {
            it is PendingDeletionHistory.Group && it.visitedAt == item.visitedAt
        } || item.items.all { metadata ->
            pendingDeletionItems.any {
                it is PendingDeletionHistory.MetaData &&
                    it.key == metadata.historyMetadataKey &&
                    it.visitedAt == metadata.visitedAt
            }
        }
        is History.Metadata -> false
    }

    /** @param pendingDeletionItems is used to filter out the items that should not be displayed. */
    fun updatePendingDeletionItems(pendingDeletionItems: Set<PendingDeletionHistory>) {
        this.pendingDeletionItems = pendingDeletionItems
    }

    companion object {
        private val historyDiffCallback =
            object : DiffUtil.ItemCallback<History>() {
                override fun areItemsTheSame(oldItem: History, newItem: History): Boolean {
                    return oldItem == newItem
                }

                override fun areContentsTheSame(oldItem: History, newItem: History): Boolean {
                    return oldItem == newItem
                }

                override fun getChangePayload(oldItem: History, newItem: History): Any? {
                    return newItem
                }
            }
    }
}
