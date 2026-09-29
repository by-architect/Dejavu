/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.menu

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.json.JSONArray
import org.mozilla.fenix.R
import org.mozilla.fenix.kaizen.actions.CustomAction
import org.mozilla.fenix.kaizen.actions.RowAction
import mozilla.components.ui.icons.R as iconsR

/**
 * A built-in item of the Kaizen "More" menu of the browser.
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
    BACK("back", R.string.kaizen_menu_back, iconsR.drawable.mozac_ic_back_24),
    FORWARD("forward", R.string.kaizen_menu_forward, iconsR.drawable.mozac_ic_forward_24),
    SHARE("share", R.string.kaizen_menu_share, iconsR.drawable.mozac_ic_share_android_24),
    REFRESH("refresh", R.string.kaizen_menu_refresh, iconsR.drawable.mozac_ic_arrow_clockwise_24),
    FIND_IN_PAGE("find_in_page", R.string.kaizen_menu_find_in_page, iconsR.drawable.mozac_ic_find_in_page_24),
    BOOKMARK_PAGE("bookmark_page", R.string.kaizen_menu_bookmark_page, iconsR.drawable.mozac_ic_bookmark_24),
    DESKTOP_SITE("desktop_site", R.string.kaizen_menu_desktop_site, iconsR.drawable.mozac_ic_device_desktop_24),
    EXTENSIONS("extensions", R.string.kaizen_menu_extensions, iconsR.drawable.mozac_ic_extension_24),
    PASSWORDS("passwords", R.string.kaizen_menu_passwords, iconsR.drawable.mozac_ic_login_24),
    BOOKMARKS("bookmarks", R.string.kaizen_menu_bookmarks, iconsR.drawable.mozac_ic_bookmark_tray_24),
    DOWNLOADS("downloads", R.string.kaizen_menu_downloads, iconsR.drawable.mozac_ic_download_24),
    HISTORY("history", R.string.kaizen_menu_history, iconsR.drawable.mozac_ic_history_24),
    TRANSLATE("translate", R.string.kaizen_menu_translate, iconsR.drawable.mozac_ic_translate_24),
    SUMMARIZE("summarize", R.string.kaizen_menu_summarize, iconsR.drawable.mozac_ic_sparkle_24),
    REPORT_BROKEN_SITE("report_broken_site", R.string.kaizen_menu_report_broken_site, iconsR.drawable.mozac_ic_lightbulb_24),
    PIN_TAB("pin_tab", R.string.kaizen_menu_pin_tab, iconsR.drawable.mozac_ic_pin_24),
    ESSENTIAL_TAB("essential_tab", R.string.kaizen_menu_essential_tab, iconsR.drawable.mozac_ic_grid_add_24),
    SAVE_AS_PDF("save_as_pdf", R.string.kaizen_menu_save_as_pdf, iconsR.drawable.mozac_ic_save_file_24),
    PRINT("print", R.string.kaizen_menu_print, iconsR.drawable.mozac_ic_print_24),
    OPEN_IN_APP("open_in_app", R.string.kaizen_menu_open_in_app, iconsR.drawable.mozac_ic_external_link_24),
    RESET_PINNED_URL(
        "reset_pinned_url",
        R.string.kaizen_menu_reset_pinned_url,
        iconsR.drawable.mozac_ic_arrow_counter_clockwise_24,
    ),
    REPLACE_PINNED_URL("replace_pinned_url", R.string.kaizen_menu_replace_pinned_url, iconsR.drawable.mozac_ic_pin_fill_24),
    SPLIT_VIEW("split_view", R.string.kaizen_menu_split_view, R.drawable.kaizen_ic_split_24),
    SETTINGS("settings", R.string.kaizen_menu_settings, iconsR.drawable.mozac_ic_settings_24),
    ;

    companion object {
        fun fromKey(key: String): MoreMenuItem? = entries.firstOrNull { it.key == key }
    }
}

/** An item of the "More" menu: a built-in one or one of the user's custom actions. */
sealed interface MoreMenuEntry {
    val key: String

    /** A [MoreMenuItem]. */
    data class BuiltIn(val item: MoreMenuItem) : MoreMenuEntry {
        override val key: String get() = item.key
    }

    /** A [CustomAction] run on the shown tab. */
    data class Custom(val action: CustomAction) : MoreMenuEntry {
        override val key: String get() = RowAction.keyOf(action.id)
    }
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
            ?: customActions.firstOrNull { RowAction.keyOf(it.id) == key }?.let { MoreMenuEntry.Custom(it) }

    /** Every entry that can be put in the menu. */
    fun available(customActions: List<CustomAction>): List<MoreMenuEntry> =
        MoreMenuItem.entries.map { MoreMenuEntry.BuiltIn(it) } + customActions.map { MoreMenuEntry.Custom(it) }

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
