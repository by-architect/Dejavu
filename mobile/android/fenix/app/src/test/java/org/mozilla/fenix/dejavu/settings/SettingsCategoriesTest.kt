/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.settings.SettingsPane.Step
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsCategoriesTest {
    private val resolve: (Int) -> String = testContext::getString

    private fun categoryOf(key: Int) = SettingsCategories.categoryOfKey(testContext.getString(key), resolve)

    @Test
    fun `the settings the table names belong to their category`() {
        assertEquals(SettingsCategory.TABS, categoryOf(R.string.pref_key_dejavu_tabs_workspaces))
        assertEquals(SettingsCategory.APPEARANCE, categoryOf(R.string.pref_key_customize))
        assertEquals(SettingsCategory.SEARCH, categoryOf(R.string.pref_key_search_settings))
        assertEquals(SettingsCategory.PRIVACY, categoryOf(R.string.pref_key_dejavu_tracking_protection))
        assertEquals(SettingsCategory.PRIVACY, categoryOf(R.string.pref_key_delete_browsing_data))
    }

    @Test
    fun `a setting no category names falls to the last one so no setting is lost`() {
        assertEquals(SettingsCategory.SYSTEM, categoryOf(R.string.pref_key_addons))
        assertEquals(SettingsCategory.SYSTEM, categoryOf(R.string.pref_key_about))
        assertEquals(SettingsCategory.SYSTEM, SettingsCategories.categoryOfKey("a_new_setting", resolve))
        assertEquals(SettingsCategory.SYSTEM, SettingsCategories.categoryOfKey(null, resolve))
    }

    @Test
    fun `a shortcut belongs to the category that shows it`() {
        SettingsCategories.shortcuts.forEach {
            assertEquals(it.category, SettingsCategories.categoryOfKey(testContext.getString(it.key), resolve))
            assertTrue(it in SettingsCategories.shortcutsOf(it.category))
        }
        assertEquals(SettingsCategory.TABS, categoryOf(R.string.pref_key_dejavu_tab_sleep))
    }

    @Test
    fun `every category has something to show`() {
        val settings = listOf(
            R.string.pref_key_dejavu_tabs_workspaces,
            R.string.pref_key_customize,
            R.string.pref_key_search_settings,
            R.string.pref_key_site_permissions,
            R.string.pref_key_addons,
        ).map(::categoryOf)

        SettingsCategory.entries.forEach { category ->
            val count = settings.count { it == category } + SettingsCategories.shortcutsOf(category).size
            assertTrue("${category.name} is empty", count > 0)
        }
    }

    @Test
    fun `a screen below Settings belongs to its category through the graph it is in`() {
        assertEquals(SettingsCategory.TABS, SettingsCategories.categoryOfScreen(R.id.dejavu_tab_sleep_graph))
        assertEquals(SettingsCategory.PRIVACY, SettingsCategories.categoryOfScreen(R.id.sitePermissionsFragment))
        assertNull(SettingsCategories.categoryOfScreen(R.id.aboutFragment))
    }

    @Test
    fun `a search for a setting with a screen of its own opens that screen`() {
        val screen = { key: Int -> SettingsCategories.screenOfKey(testContext.getString(key), resolve) }

        assertEquals(R.id.dejavu_tab_sleep_graph, screen(R.string.pref_key_dejavu_tab_sleep))
        assertEquals(R.id.dejavu_tabs_workspaces_graph, screen(R.string.pref_key_dejavu_tabs_workspaces))
        assertNull(screen(R.string.pref_key_addons))
    }

    @Test
    fun `the category shown is the one of the screen on top, else the one the main screen was opened for`() {
        val main = Step(listOf(R.id.settingsFragment), SettingsCategory.SEARCH.name)
        val tabSleep = Step(listOf(R.id.dejavuTabSleepFragment, R.id.dejavu_tab_sleep_graph))
        val home = Step(listOf(R.id.homeFragment))

        assertEquals(SettingsCategory.SEARCH, SettingsPane.selection(listOf(main, home)))
        assertEquals(SettingsCategory.TABS, SettingsPane.selection(listOf(tabSleep, main, home)))
    }

    @Test
    fun `no category is shown outside Settings or in its search`() {
        val main = Step(listOf(R.id.settingsFragment))
        val search = Step(listOf(R.id.settingsSearchFragment))
        val browser = Step(listOf(R.id.browserFragment))
        val home = Step(listOf(R.id.homeFragment))

        assertNull(SettingsPane.selection(listOf(home)))
        assertNull(SettingsPane.selection(listOf(search, main, home)))
        assertNull(SettingsPane.selection(listOf(browser, main, home)))
        assertNull(SettingsPane.selection(emptyList()))
    }
}
