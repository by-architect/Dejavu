/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.actions

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R

/**
 * An action that can be applied to a tab or a pinned tab, either from the buttons of a tab row or from the selection
 * bar.
 *
 * @property key Stable identifier used to persist the user's choice.
 * @property label Label shown in settings and in the selection bar.
 * @property icon Icon of the action button.
 */
enum class TabAction(
    val key: String,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
) {
    CLOSE("close", R.string.dejavu_action_close, iconsR.drawable.mozac_ic_cross_24),
    PIN("pin", R.string.dejavu_action_pin, iconsR.drawable.mozac_ic_pin_24),
    UNPIN("unpin", R.string.dejavu_action_unpin, iconsR.drawable.mozac_ic_pin_slash_24),
    SLEEP("sleep", R.string.dejavu_action_sleep, R.drawable.dejavu_ic_sleep_24),
    BOOKMARK("bookmark", R.string.dejavu_action_bookmark, iconsR.drawable.mozac_ic_bookmark_24),
    SHARE("share", R.string.dejavu_action_share, iconsR.drawable.mozac_ic_share_android_24),
    COPY_LINK("copy_link", R.string.dejavu_action_copy_link, iconsR.drawable.mozac_ic_link_24),
    DUPLICATE("duplicate", R.string.dejavu_action_duplicate, iconsR.drawable.mozac_ic_copy_24),
    MOVE_TO_WORKSPACE("move_to_workspace", R.string.dejavu_action_move_to_workspace, iconsR.drawable.mozac_ic_forward_24),
    CHANGE_CONTAINER("change_container", R.string.dejavu_action_change_container, R.drawable.dejavu_ic_container_24),
    SPLIT_VIEW("split_view", R.string.dejavu_action_split_view, R.drawable.dejavu_ic_split_24),
    UNSPLIT("unsplit", R.string.dejavu_action_unsplit, R.drawable.dejavu_ic_unsplit_24),
    MOVE_TO_FOLDER("move_to_folder", R.string.dejavu_action_move_to_folder, iconsR.drawable.mozac_ic_folder_arrow_right_24),
    NEW_FOLDER("new_folder", R.string.dejavu_action_new_folder, iconsR.drawable.mozac_ic_folder_add_24),
    RESET_PIN("reset_pin", R.string.dejavu_action_reset_pin, iconsR.drawable.mozac_ic_arrow_counter_clockwise_24),
    ADD_TO_ESSENTIALS("add_to_essentials", R.string.dejavu_action_add_to_essentials, iconsR.drawable.mozac_ic_grid_add_24),
    REMOVE_FROM_ESSENTIALS(
        "remove_from_essentials",
        R.string.dejavu_action_remove_from_essentials,
        iconsR.drawable.mozac_ic_tab_ungroup_24,
    ),
    NEW_SUBFOLDER("new_subfolder", R.string.dejavu_folder_new_subfolder, iconsR.drawable.mozac_ic_folder_add_24),
    RENAME_FOLDER("rename_folder", R.string.dejavu_folder_rename, iconsR.drawable.mozac_ic_edit_24),
    RENAME_TAB("rename_tab", R.string.dejavu_action_rename_tab, iconsR.drawable.mozac_ic_edit_24),
    UNPACK_FOLDER("unpack_folder", R.string.dejavu_folder_unpack, iconsR.drawable.mozac_ic_tab_ungroup_24),
    DELETE("delete", R.string.dejavu_action_delete, iconsR.drawable.mozac_ic_delete_24),
    ;

    companion object {
        /** Actions the user can put on pinned tab rows, in display order. */
        val forPinnedRows = listOf(
            UNPIN, RESET_PIN, ADD_TO_ESSENTIALS, RENAME_TAB, SLEEP, BOOKMARK, SHARE, COPY_LINK, DUPLICATE,
            MOVE_TO_FOLDER, MOVE_TO_WORKSPACE, CHANGE_CONTAINER, CLOSE,
        )

        /** Actions the user can put on unpinned tab rows, in display order. */
        val forUnpinnedRows = listOf(
            PIN, ADD_TO_ESSENTIALS, RENAME_TAB, SLEEP, BOOKMARK, SHARE, COPY_LINK, DUPLICATE, MOVE_TO_FOLDER,
            MOVE_TO_WORKSPACE, CHANGE_CONTAINER, CLOSE,
        )

        /** Actions of the selection bar, in display order. */
        val forSelection = listOf(
            SHARE, SLEEP, BOOKMARK, COPY_LINK, PIN, UNPIN, RESET_PIN, RENAME_TAB, ADD_TO_ESSENTIALS,
            REMOVE_FROM_ESSENTIALS, SPLIT_VIEW, UNSPLIT, NEW_FOLDER, NEW_SUBFOLDER, RENAME_FOLDER, MOVE_TO_FOLDER,
            MOVE_TO_WORKSPACE, CHANGE_CONTAINER, UNPACK_FOLDER, DUPLICATE, DELETE, CLOSE,
        )

        fun fromKey(key: String): TabAction? = entries.firstOrNull { it.key == key }
    }
}
