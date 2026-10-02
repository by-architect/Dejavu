/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import mozilla.components.ui.icons.R as iconsR
import org.json.JSONArray
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.actions.ActionPlace
import org.mozilla.fenix.dejavu.actions.CustomAction
import org.mozilla.fenix.dejavu.actions.RowAction
import org.mozilla.fenix.dejavu.actions.TabAction

/**
 * A built-in item of the Dejavu "More" menu of the browser.
 *
 * @property key Stable identifier used to persist the menu layout.
 * @property label Short label shown under the icon.
 * @property icon Icon of the item.
 */
enum class MoreMenuItem(
    val key: String,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
) {
    BACK("back", R.string.dejavu_menu_back, iconsR.drawable.mozac_ic_back_24),
    FORWARD("forward", R.string.dejavu_menu_forward, iconsR.drawable.mozac_ic_forward_24),
    SHARE("share", R.string.dejavu_menu_share, iconsR.drawable.mozac_ic_share_android_24),
    REFRESH("refresh", R.string.dejavu_menu_refresh, iconsR.drawable.mozac_ic_arrow_clockwise_24),
    FIND_IN_PAGE("find_in_page", R.string.dejavu_menu_find_in_page, iconsR.drawable.mozac_ic_find_in_page_24),
    BOOKMARK_PAGE("bookmark_page", R.string.dejavu_menu_bookmark_page, iconsR.drawable.mozac_ic_bookmark_24),
    DESKTOP_SITE("desktop_site", R.string.dejavu_menu_desktop_site, iconsR.drawable.mozac_ic_device_desktop_24),
    EXTENSIONS("extensions", R.string.dejavu_menu_extensions, iconsR.drawable.mozac_ic_extension_24),
    PASSWORDS("passwords", R.string.dejavu_menu_passwords, iconsR.drawable.mozac_ic_login_24),
    BOOKMARKS("bookmarks", R.string.dejavu_menu_bookmarks, iconsR.drawable.mozac_ic_bookmark_tray_24),
    DOWNLOADS("downloads", R.string.dejavu_menu_downloads, iconsR.drawable.mozac_ic_download_24),
    HISTORY("history", R.string.dejavu_menu_history, iconsR.drawable.mozac_ic_history_24),
    TRANSLATE("translate", R.string.dejavu_menu_translate, iconsR.drawable.mozac_ic_translate_24),
    SUMMARIZE("summarize", R.string.dejavu_menu_summarize, iconsR.drawable.mozac_ic_sparkle_24),
    REPORT_BROKEN_SITE("report_broken_site", R.string.dejavu_menu_report_broken_site, iconsR.drawable.mozac_ic_lightbulb_24),
    PIN_TAB("pin_tab", R.string.dejavu_menu_pin_tab, iconsR.drawable.mozac_ic_pin_24),
    ESSENTIAL_TAB("essential_tab", R.string.dejavu_menu_essential_tab, iconsR.drawable.mozac_ic_grid_add_24),
    SAVE_AS_PDF("save_as_pdf", R.string.dejavu_menu_save_as_pdf, iconsR.drawable.mozac_ic_save_file_24),
    PRINT("print", R.string.dejavu_menu_print, iconsR.drawable.mozac_ic_print_24),
    OPEN_IN_APP("open_in_app", R.string.dejavu_menu_open_in_app, iconsR.drawable.mozac_ic_external_link_24),
    RESET_PINNED_URL(
        "reset_pinned_url",
        R.string.dejavu_menu_reset_pinned_url,
        iconsR.drawable.mozac_ic_arrow_counter_clockwise_24,
    ),
    REPLACE_PINNED_URL("replace_pinned_url", R.string.dejavu_menu_replace_pinned_url, iconsR.drawable.mozac_ic_pin_fill_24),
    SPLIT_VIEW("split_view", R.string.dejavu_menu_split_view, R.drawable.dejavu_ic_split_24),
    SETTINGS("settings", R.string.dejavu_menu_settings, iconsR.drawable.mozac_ic_settings_24),
    ;

    companion object {
        fun fromKey(key: String): MoreMenuItem? = entries.firstOrNull { it.key == key }
    }
}

/**
 * An item of the "More" menu or of the actions bar: a built-in one, a tab action or one of the user's custom actions,
 * run on the shown tab.
 */
sealed interface MoreMenuEntry {
    val key: String

    /** A [MoreMenuItem]. */
    data class BuiltIn(val item: MoreMenuItem) : MoreMenuEntry {
        override val key: String get() = item.key
    }

    /** A [TabAction] that has no [MoreMenuItem] doing the same, see [isMenuEntry]. */
    data class Tab(val action: TabAction) : MoreMenuEntry {
        override val key: String get() = action.key
    }

    /** A [CustomAction]. */
    data class Custom(val action: CustomAction) : MoreMenuEntry {
        override val key: String get() = RowAction.keyOf(action.id)
    }

    /** Goes to the home screen. Only the actions bar has it. */
    data object Home : MoreMenuEntry {
        override val key: String get() = "home"
    }

    /** Starts a search. Only the actions bar has it. */
    data object Search : MoreMenuEntry {
        override val key: String get() = "search"
    }
}

/** The [MoreMenuItem] that does to the shown tab what this action does, for the actions the menu already had. */
val TabAction.menuItem: MoreMenuItem?
    get() = when (this) {
        TabAction.SHARE -> MoreMenuItem.SHARE
        TabAction.BOOKMARK -> MoreMenuItem.BOOKMARK_PAGE
        TabAction.PIN, TabAction.UNPIN -> MoreMenuItem.PIN_TAB
        TabAction.ADD_TO_ESSENTIALS, TabAction.REMOVE_FROM_ESSENTIALS -> MoreMenuItem.ESSENTIAL_TAB
        TabAction.RESET_PIN -> MoreMenuItem.RESET_PINNED_URL
        TabAction.SPLIT_VIEW, TabAction.UNSPLIT -> MoreMenuItem.SPLIT_VIEW
        else -> null
    }

/** Whether this action is an entry of its own in the "More" menu, rather than one of its [MoreMenuItem]s. */
val TabAction.isMenuEntry: Boolean
    get() = ActionPlace.MORE_MENU in places && menuItem == null

/** The key this action has in the "More" menu and the actions bar, or `null` when it cannot be put there. */
val RowAction.menuKey: String?
    get() = when (this) {
        is RowAction.BuiltIn -> if (ActionPlace.MORE_MENU in action.places) action.menuItem?.key ?: action.key else null
        is RowAction.Custom -> key
    }

/**
 * The rows of the "More" menu, as lists of entry keys. A row holds up to [MAX_PER_ROW] items, except the
 * extensions item, which fills a row of its own.
 */
object MoreMenuLayout {
    const val MAX_PER_ROW = 4

    val DEFAULT: List<List<String>> = listOf(
        listOf(MoreMenuItem.BACK, MoreMenuItem.FORWARD, MoreMenuItem.SHARE, MoreMenuItem.REFRESH),
        listOf(MoreMenuItem.FIND_IN_PAGE, MoreMenuItem.DESKTOP_SITE, MoreMenuItem.PIN_TAB, MoreMenuItem.OPEN_IN_APP),
        listOf(MoreMenuItem.EXTENSIONS),
        listOf(MoreMenuItem.HISTORY, MoreMenuItem.BOOKMARKS, MoreMenuItem.DOWNLOADS, MoreMenuItem.PASSWORDS),
    ).map { row -> row.map { it.key } }

    /** Whether the entry with [key] fills a row on its own. */
    fun isFullRow(key: String): Boolean = key == MoreMenuItem.EXTENSIONS.key

    /** The entry with [key], or `null` if it does not exist (anymore). */
    fun entryOf(key: String, customActions: List<CustomAction>): MoreMenuEntry? =
        MoreMenuItem.fromKey(key)?.let { MoreMenuEntry.BuiltIn(it) }
            ?: TabAction.fromKey(key)?.takeIf { it.isMenuEntry }?.let { MoreMenuEntry.Tab(it) }
            ?: customActions.firstOrNull { RowAction.keyOf(it.id) == key }?.let { MoreMenuEntry.Custom(it) }

    /** Every entry that can be put in the menu: its own items, then the tab actions, then the custom ones. */
    fun available(customActions: List<CustomAction>): List<MoreMenuEntry> =
        MoreMenuItem.entries.map { MoreMenuEntry.BuiltIn(it) } +
            TabAction.ordered.filter { it.isMenuEntry }.map { MoreMenuEntry.Tab(it) } +
            customActions.map { MoreMenuEntry.Custom(it) }

    /** The entries of [rows], leaving out the ones that no longer exist and rows left empty. */
    fun resolve(rows: List<List<String>>, customActions: List<CustomAction>): List<List<MoreMenuEntry>> =
        normalized(rows).map { row -> row.mapNotNull { entryOf(it, customActions) } }.filter { it.isNotEmpty() }

    /**
     * [rows] without duplicates or empty rows, with full row entries on rows of their own and at most [MAX_PER_ROW]
     * entries per row.
     */
    fun normalized(rows: List<List<String>>): List<List<String>> {
        val seen = mutableSetOf<String>()
        return rows.flatMap { row ->
            val keys = row.filter { seen.add(it) }
            val (full, others) = keys.partition(::isFullRow)
            full.map { listOf(it) } + others.chunked(MAX_PER_ROW)
        }.filter { it.isNotEmpty() }
    }

    fun toJson(rows: List<List<String>>): String =
        JSONArray().apply { rows.forEach { row -> put(JSONArray(row)) } }.toString()

    fun fromJson(json: String?): List<List<String>>? {
        val array = json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
        return (0 until array.length()).map { index ->
            val row = array.optJSONArray(index) ?: JSONArray()
            (0 until row.length()).map { row.optString(it) }.filter { it.isNotBlank() }
        }
    }
}

/**
 * The buttons of the actions bar below pages, as keys of [MoreMenuEntry]s. Home and Search are buttons like the others,
 * so they can be moved or removed too.
 */
object ActionsBarLayout {
    const val MAX_BUTTONS = 7

    /**
     * The bar Dejavu showed before it could be changed on its own: the first row of the menu laid out as [menuRows]
     * and Search, around Home in the middle.
     */
    fun fromMenu(menuRows: List<List<String>>): List<String> {
        val buttons = menuRows.firstOrNull().orEmpty() + MoreMenuEntry.Search.key
        return buttons.toMutableList().apply { add(size / 2, MoreMenuEntry.Home.key) }.distinct().take(MAX_BUTTONS)
    }

    /** The entry with [key], or `null` if it does not exist (anymore). */
    fun entryOf(key: String, customActions: List<CustomAction>): MoreMenuEntry? = when (key) {
        MoreMenuEntry.Home.key -> MoreMenuEntry.Home
        MoreMenuEntry.Search.key -> MoreMenuEntry.Search
        else -> MoreMenuLayout.entryOf(key, customActions)
    }

    /** Every entry that can be put in the bar. */
    fun available(customActions: List<CustomAction>): List<MoreMenuEntry> =
        listOf(MoreMenuEntry.Home, MoreMenuEntry.Search) + MoreMenuLayout.available(customActions)

    /** The entries of [keys], leaving out the ones that no longer exist. */
    fun resolve(keys: List<String>, customActions: List<CustomAction>): List<MoreMenuEntry> =
        keys.distinct().mapNotNull { entryOf(it, customActions) }.take(MAX_BUTTONS)
}
