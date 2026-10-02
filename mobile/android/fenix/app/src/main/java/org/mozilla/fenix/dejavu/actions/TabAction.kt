/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.actions

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R

/** Where the user can put actions. Every place picks from the same list of actions. */
enum class ActionPlace(@param:StringRes val label: Int) {
    /** The buttons of pinned tab rows. */
    PINNED_ROWS(R.string.dejavu_settings_pinned_tabs),

    /** The buttons of unpinned tab rows. */
    UNPINNED_ROWS(R.string.dejavu_settings_unpinned_tabs),

    /** The buttons of folder rows, which act on everything inside the folder. */
    FOLDER_ROWS(R.string.dejavu_settings_folders),

    /** The bar shown when tabs, essentials or folders are selected. */
    SELECTION(R.string.dejavu_settings_selection_bar),

    /** The browser's "More" menu, acting on the shown tab. */
    MORE_MENU(R.string.dejavu_settings_more_menu),

    /** The bar below pages, acting on the shown tab. */
    ACTIONS_BAR(R.string.dejavu_settings_actions_bar),
    ;

    /** Whether this place is the buttons of a row, which has room for only a few. */
    val isRow: Boolean
        get() = this == PINNED_ROWS || this == UNPINNED_ROWS || this == FOLDER_ROWS
}

private val Everywhere = ActionPlace.entries.toSet()
private val ShownTab = setOf(ActionPlace.MORE_MENU, ActionPlace.ACTIONS_BAR)

/**
 * An action that can be applied to tabs, pinned tabs and folders: from the buttons of rows, from the selection bar, or
 * on the shown tab from the "More" menu and the actions bar.
 *
 * @property key Stable identifier used to persist the user's choice.
 * @property label Label shown in settings and in the selection bar.
 * @property icon Icon of the action button.
 * @property places Where the action can do something, and so can be put.
 */
enum class TabAction(
    val key: String,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    val places: Set<ActionPlace>,
) {
    CLOSE("close", R.string.dejavu_action_close, iconsR.drawable.mozac_ic_cross_24, Everywhere),
    PIN(
        "pin",
        R.string.dejavu_action_pin,
        iconsR.drawable.mozac_ic_pin_24,
        setOf(ActionPlace.UNPINNED_ROWS, ActionPlace.SELECTION) + ShownTab,
    ),
    UNPIN(
        "unpin",
        R.string.dejavu_action_unpin,
        iconsR.drawable.mozac_ic_pin_slash_24,
        Everywhere - ActionPlace.UNPINNED_ROWS,
    ),
    SLEEP("sleep", R.string.dejavu_action_sleep, R.drawable.dejavu_ic_sleep_24, Everywhere),
    BOOKMARK("bookmark", R.string.dejavu_action_bookmark, iconsR.drawable.mozac_ic_bookmark_24, Everywhere),
    SHARE("share", R.string.dejavu_action_share, iconsR.drawable.mozac_ic_share_android_24, Everywhere),
    COPY_LINK("copy_link", R.string.dejavu_action_copy_link, iconsR.drawable.mozac_ic_link_24, Everywhere),
    DUPLICATE("duplicate", R.string.dejavu_action_duplicate, iconsR.drawable.mozac_ic_copy_24, Everywhere),
    MOVE_TO_WORKSPACE(
        "move_to_workspace",
        R.string.dejavu_action_move_to_workspace,
        iconsR.drawable.mozac_ic_forward_24,
        Everywhere,
    ),
    CHANGE_CONTAINER(
        "change_container",
        R.string.dejavu_action_change_container,
        R.drawable.dejavu_ic_container_24,
        Everywhere,
    ),
    SPLIT_VIEW(
        "split_view",
        R.string.dejavu_action_split_view,
        R.drawable.dejavu_ic_split_24,
        setOf(ActionPlace.SELECTION) + ShownTab,
    ),
    UNSPLIT("unsplit", R.string.dejavu_action_unsplit, R.drawable.dejavu_ic_unsplit_24, Everywhere),
    MOVE_TO_FOLDER(
        "move_to_folder",
        R.string.dejavu_action_move_to_folder,
        iconsR.drawable.mozac_ic_folder_arrow_right_24,
        Everywhere,
    ),
    NEW_FOLDER(
        "new_folder",
        R.string.dejavu_action_new_folder,
        iconsR.drawable.mozac_ic_folder_add_24,
        Everywhere - ActionPlace.FOLDER_ROWS,
    ),
    RESET_PIN(
        "reset_pin",
        R.string.dejavu_action_reset_pin,
        iconsR.drawable.mozac_ic_arrow_counter_clockwise_24,
        Everywhere - ActionPlace.UNPINNED_ROWS,
    ),
    ADD_TO_ESSENTIALS(
        "add_to_essentials",
        R.string.dejavu_action_add_to_essentials,
        iconsR.drawable.mozac_ic_grid_add_24,
        Everywhere,
    ),
    REMOVE_FROM_ESSENTIALS(
        "remove_from_essentials",
        R.string.dejavu_action_remove_from_essentials,
        iconsR.drawable.mozac_ic_tab_ungroup_24,
        setOf(ActionPlace.SELECTION) + ShownTab,
    ),
    NEW_SUBFOLDER(
        "new_subfolder",
        R.string.dejavu_folder_new_subfolder,
        iconsR.drawable.mozac_ic_folder_add_24,
        setOf(ActionPlace.FOLDER_ROWS, ActionPlace.SELECTION),
    ),
    RENAME_FOLDER(
        "rename_folder",
        R.string.dejavu_folder_rename,
        iconsR.drawable.mozac_ic_edit_24,
        setOf(ActionPlace.FOLDER_ROWS, ActionPlace.SELECTION),
    ),
    RENAME_TAB(
        "rename_tab",
        R.string.dejavu_action_rename_tab,
        iconsR.drawable.mozac_ic_edit_24,
        Everywhere - ActionPlace.FOLDER_ROWS,
    ),
    UNPACK_FOLDER(
        "unpack_folder",
        R.string.dejavu_folder_unpack,
        iconsR.drawable.mozac_ic_tab_ungroup_24,
        setOf(ActionPlace.FOLDER_ROWS, ActionPlace.SELECTION),
    ),
    DELETE(
        "delete",
        R.string.dejavu_action_delete,
        iconsR.drawable.mozac_ic_delete_24,
        Everywhere - ActionPlace.UNPINNED_ROWS,
    ),
    ;

    companion object {
        /**
         * Every action in display order, the one list every place picks from. Delete and Close come last, so that they
         * stay the last buttons wherever they are shown.
         */
        val ordered: List<TabAction> = listOf(
            UNPIN, PIN, RESET_PIN, ADD_TO_ESSENTIALS, REMOVE_FROM_ESSENTIALS, RENAME_TAB, SLEEP, BOOKMARK, SHARE,
            COPY_LINK, DUPLICATE, SPLIT_VIEW, UNSPLIT, NEW_FOLDER, NEW_SUBFOLDER, RENAME_FOLDER, MOVE_TO_FOLDER,
            MOVE_TO_WORKSPACE, CHANGE_CONTAINER, UNPACK_FOLDER, DELETE, CLOSE,
        )

        fun fromKey(key: String): TabAction? = entries.firstOrNull { it.key == key }
    }
}
