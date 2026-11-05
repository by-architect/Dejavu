/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.os.Bundle
import androidx.annotation.IdRes
import androidx.navigation.NavController
import androidx.navigation.navOptions
import org.mozilla.fenix.R
import org.mozilla.fenix.settings.settingssearch.SettingsSearchItem

/**
 * What Dejavu adds to the search of the settings: the other words people use for a setting, and a search result that
 * opens the screen of the setting instead of the screen that lists it.
 */
object SettingsSearch {
    /** Words that begin a search are not matched against synonyms before this many letters. */
    private const val MIN_QUERY_LENGTH = 2

    /** Other words for settings, each with the keys of the settings it finds. */
    internal val SYNONYMS: List<Pair<List<String>, List<Int>>> = listOf(
        listOf("arc", "spaces", "space", "workspace") to listOf(R.string.pref_key_dejavu_tabs_workspaces),
        listOf("freeze", "sleep", "suspend", "discard", "memory", "ram", "hibernate") to
            listOf(R.string.pref_key_dejavu_tab_sleep),
        listOf("container") to listOf(R.string.pref_key_dejavu_containers),
        listOf("dark", "light", "night", "oled", "amoled", "appearance") to listOf(R.string.pref_key_light_theme),
        listOf("bottom", "top", "fullscreen", "statusbar", "navigation") to listOf(R.string.pref_key_dejavu_bars),
        listOf("actions", "buttons") to listOf(R.string.pref_key_dejavu_tab_actions),
        listOf("overflow", "toolbar") to listOf(R.string.pref_key_dejavu_more_menu),
        listOf("tracker", "tracking", "adblock", "ads", "cookies", "privacy") to
            listOf(R.string.pref_key_dejavu_tracking_protection),
        listOf("password", "login", "autofill", "language") to listOf(R.string.pref_key_dejavu_passwords_language),
        listOf("external", "intent") to listOf(R.string.pref_key_dejavu_external_links),
        // Keyboard shortcuts ("hotkey", "keybinding"), mouse support ("mouse", "middle click"), the sidebar, search
        // keywords ("bang", "prefix"), loading colors and the page color switch come with their own changes, which
        // add their words here.
    )

    /** The keys of the settings the other words for them find for the [query], as the resources that hold them. */
    internal fun synonymKeys(query: String): Set<Int> {
        val word = query.trim().lowercase()
        if (word.length < MIN_QUERY_LENGTH) return emptySet()
        return SYNONYMS.filter { (words, _) -> words.any { it.startsWith(word) } }.flatMap { it.second }.toSet()
    }

    /**
     * Whether the [query] is another word for the setting with the [key]. [resolve] gives the key a string resource
     * holds.
     */
    fun matches(key: String, query: String, resolve: (Int) -> String): Boolean =
        synonymKeys(query).any { resolve(it) == key }

    /** The screen to open for a search result, which is the screen of the setting itself where Dejavu has one. */
    @IdRes
    fun screenFor(item: SettingsSearchItem, resolve: (Int) -> String): Int =
        SettingsCategories.screenOfKey(item.preferenceKey, resolve) ?: item.preferenceFileInformation.fragmentId

    /**
     * Opens the screen of a search result from any screen of Settings. The settings below the main screen replace
     * each other, so Back always leads to the main screen.
     */
    fun open(navController: NavController, item: SettingsSearchItem, resolve: (Int) -> String) {
        val screen = screenFor(item, resolve)
        val category = SettingsCategories.categoryOfScreen(screen)
            ?: SettingsCategories.categoryOfKey(item.preferenceKey, resolve)
        val arguments = Bundle().apply {
            putString(ARG_PREFERENCE_TO_SCROLL_TO, item.preferenceKey)
            putBoolean(ARG_SEARCH_IN_PROGRESS, true)
            putString(SettingsPane.ARG_CATEGORY, category.name)
        }
        // The main screen is the one that shows the category, so it starts over for a setting on it.
        val startsOver = screen == R.id.settingsFragment
        navController.navigate(
            screen,
            arguments,
            navOptions { popUpTo(R.id.settingsFragment) { inclusive = startsOver } },
        )
    }

    private const val ARG_PREFERENCE_TO_SCROLL_TO = "preference_to_scroll_to"
    private const val ARG_SEARCH_IN_PROGRESS = "search_in_progress"
}
