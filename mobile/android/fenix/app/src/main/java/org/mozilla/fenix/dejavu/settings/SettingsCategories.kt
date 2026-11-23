/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R

/**
 * What the settings are grouped into on wide windows: the entries of the list beside the settings. A category shows
 * the settings of Firefox and Dejavu that belong to it, where they are, and links to the Dejavu screens that live
 * deeper down.
 *
 * Their names are strings Firefox and Dejavu already translated, so they need no translation of their own.
 */
enum class SettingsCategory(@param:StringRes val title: Int, @param:DrawableRes val icon: Int) {
    /** Workspaces, tabs, containers and the homepage. */
    TABS(R.string.dejavu_settings_tabs_workspaces, R.drawable.dejavu_ic_tabs_24),

    /** The theme, the bars and what the icons look like. */
    APPEARANCE(R.string.preferences_customize, iconsR.drawable.mozac_ic_themes_24),

    /** Gestures, tab actions, the menus and the other ways to control the browser. */
    CONTROLS(R.string.preferences_gestures, iconsR.drawable.mozac_ic_cursor_arrow_24),

    /** Search engines and where links open. */
    SEARCH(R.string.preferences_search, iconsR.drawable.mozac_ic_search_24),

    /** Protections, site permissions, passwords and deleting data. */
    PRIVACY(R.string.preferences_category_privacy_security, iconsR.drawable.mozac_ic_shield_24),

    /** Sync, extensions, downloads, Dejavu itself and everything that fits nowhere else. */
    SYSTEM(R.string.preferences_category_advanced, iconsR.drawable.mozac_ic_sync_24),
    ;

    internal companion object {
        /** The category with the [name], or the first one when no category has it. */
        fun fromName(name: String?): SettingsCategory = entries.firstOrNull { it.name == name } ?: entries.first()
    }
}

/**
 * A link to a Dejavu screen that opens from another screen, shown in its [category] too so it is one tap away.
 *
 * @property category The category that shows the link.
 * @property key The key of the setting that opens the screen, which the settings search finds it by.
 * @property title Title of the link.
 * @property summary What the screen is for, below the title.
 * @property destination The screen or graph the link opens.
 */
data class SettingsShortcut(
    val category: SettingsCategory,
    @param:StringRes val key: Int,
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    @param:IdRes val destination: Int,
)

/**
 * Which category each setting and screen belongs to. This is the one table to extend: Firefox's preference files stay
 * as they are, and a category only names the settings and screens they already hold.
 *
 * Settings of the main Settings screen that no category names fall to [SettingsCategory.SYSTEM], so a setting
 * Firefox adds later is not lost on a wide window.
 *
 * Features that arrive with other changes get their entry here, in one place each:
 * - keyboard shortcuts and mouse support: a [SettingsShortcut] of [SettingsCategory.CONTROLS]
 * - the sidebar and its look: a [SettingsShortcut] of [SettingsCategory.TABS]
 * - search keywords: a [SettingsShortcut] of [SettingsCategory.SEARCH]
 * - loading colors and the page color switch: a [SettingsShortcut] of [SettingsCategory.APPEARANCE]
 * Their search words go in [SettingsSearch.SYNONYMS].
 */
object SettingsCategories {
    /** The settings of the main Settings screen, by category. */
    private val settingsOf: List<Pair<SettingsCategory, List<Int>>> = listOf(
        SettingsCategory.TABS to listOf(
            R.string.pref_key_dejavu_tabs_workspaces,
        ),
        SettingsCategory.APPEARANCE to listOf(
            R.string.pref_key_customize,
        ),
        SettingsCategory.SEARCH to listOf(
            R.string.pref_key_search_settings,
            R.string.pref_key_link_sharing,
        ),
        SettingsCategory.PRIVACY to listOf(
            R.string.pref_key_dejavu_passwords_language,
            R.string.pref_key_private_browsing,
            R.string.pref_key_dejavu_tracking_protection,
            R.string.pref_key_ip_protection_settings,
            R.string.pref_key_https_only_settings,
            R.string.pref_key_doh_settings,
            R.string.pref_key_tracking_protection_settings,
            R.string.pref_key_site_permissions,
            R.string.pref_key_delete_browsing_data,
            R.string.pref_key_delete_browsing_data_on_quit_preference,
            R.string.pref_key_notifications,
            R.string.pref_key_data_choices,
            R.string.pref_key_local_network_access,
        ),
    )

    /** The screens Dejavu links to from a category although they open from another screen. */
    val shortcuts: List<SettingsShortcut> = listOf(
        SettingsShortcut(
            SettingsCategory.TABS,
            R.string.pref_key_dejavu_tab_sleep,
            R.string.dejavu_settings_tab_sleep,
            R.string.dejavu_settings_tab_sleep_summary,
            R.id.dejavu_tab_sleep_graph,
        ),
        SettingsShortcut(
            SettingsCategory.TABS,
            R.string.pref_key_dejavu_containers,
            R.string.dejavu_settings_containers,
            R.string.dejavu_settings_containers_entry_summary,
            R.id.dejavu_containers_graph,
        ),
        SettingsShortcut(
            SettingsCategory.APPEARANCE,
            R.string.pref_key_dejavu_bars,
            R.string.dejavu_settings_bars_2,
            R.string.dejavu_settings_bars_summary,
            R.id.dejavu_bars_graph,
        ),
        SettingsShortcut(
            SettingsCategory.CONTROLS,
            R.string.pref_key_dejavu_tab_actions,
            R.string.dejavu_settings_tab_actions,
            R.string.dejavu_settings_tab_actions_entry_summary,
            R.id.dejavu_tab_actions_graph,
        ),
        SettingsShortcut(
            SettingsCategory.CONTROLS,
            R.string.pref_key_dejavu_more_menu,
            R.string.dejavu_settings_more_menu,
            R.string.dejavu_settings_more_menu_summary_2,
            R.id.dejavu_more_menu_graph,
        ),
        SettingsShortcut(
            SettingsCategory.SEARCH,
            R.string.pref_key_dejavu_external_links,
            R.string.dejavu_settings_external_links,
            R.string.dejavu_settings_external_links_summary,
            R.id.dejavu_external_links_graph,
        ),
    )

    /**
     * The category of the screens below the main Settings screen, by the id of the screen or of the graph it is in.
     * A screen the table does not name stays under the category it was opened from.
     */
    private val screens: Map<Int, SettingsCategory> = mapOf(
        R.id.dejavu_tabs_workspaces_graph to SettingsCategory.TABS,
        R.id.tabsSettingsFragment to SettingsCategory.TABS,
        R.id.dejavu_tab_sleep_graph to SettingsCategory.TABS,
        R.id.dejavu_containers_graph to SettingsCategory.TABS,
        R.id.homeSettingsFragment to SettingsCategory.TABS,
        R.id.customizationFragment to SettingsCategory.APPEARANCE,
        R.id.dejavu_bars_graph to SettingsCategory.APPEARANCE,
        R.id.dejavu_tab_actions_graph to SettingsCategory.CONTROLS,
        R.id.dejavu_more_menu_graph to SettingsCategory.CONTROLS,
        R.id.accessibilityFragment to SettingsCategory.CONTROLS,
        R.id.search_engine_graph to SettingsCategory.SEARCH,
        R.id.openLinksInAppsFragment to SettingsCategory.SEARCH,
        R.id.dejavu_external_links_graph to SettingsCategory.SEARCH,
        R.id.translations_settings_graph to SettingsCategory.SEARCH,
        R.id.dejavu_passwords_language_graph to SettingsCategory.PRIVACY,
        R.id.autofill_graph to SettingsCategory.PRIVACY,
        R.id.savedLogins to SettingsCategory.PRIVACY,
        R.id.sitePermissionsFragment to SettingsCategory.PRIVACY,
        R.id.trackingProtectionFragment to SettingsCategory.PRIVACY,
        R.id.privateBrowsingFragment to SettingsCategory.PRIVACY,
        R.id.dejavu_tracking_protection_graph to SettingsCategory.PRIVACY,
    )

    // Settings of the main screen that only open a screen of Dejavu, which a search goes to without the detour.
    private val directScreens: List<Pair<Int, Int>> = listOf(
        R.string.pref_key_dejavu_tabs_workspaces to R.id.dejavu_tabs_workspaces_graph,
        R.string.pref_key_customize to R.id.customizationFragment,
        R.string.pref_key_dejavu_passwords_language to R.id.dejavu_passwords_language_graph,
        R.string.pref_key_dejavu_tracking_protection to R.id.dejavu_tracking_protection_graph,
    )

    /**
     * The category of the setting with the [key] on the main Settings screen. [resolve] gives the key a string
     * resource holds.
     */
    fun categoryOfKey(key: String?, resolve: (Int) -> String): SettingsCategory =
        settingsOf.firstOrNull { (_, keys) -> keys.any { resolve(it) == key } }?.first
            ?: shortcuts.firstOrNull { resolve(it.key) == key }?.category
            ?: SettingsCategory.SYSTEM

    /** The category of the screen with the id [destination], or `null` when the table does not name it. */
    fun categoryOfScreen(@IdRes destination: Int): SettingsCategory? = screens[destination]

    /** The shortcuts a [category] shows. */
    fun shortcutsOf(category: SettingsCategory): List<SettingsShortcut> = shortcuts.filter { it.category == category }

    /** The screen a search for the setting with the [key] opens straight, or `null` for the screen it is on. */
    @IdRes
    fun screenOfKey(key: String, resolve: (Int) -> String): Int? =
        shortcuts.firstOrNull { resolve(it.key) == key }?.destination
            ?: directScreens.firstOrNull { (setting, _) -> resolve(setting) == key }?.second
}
