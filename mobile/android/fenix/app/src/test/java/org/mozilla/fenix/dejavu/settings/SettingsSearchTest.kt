/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.R
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsSearchTest {
    private val resolve: (Int) -> String = testContext::getString

    private fun finds(query: String, key: Int) = SettingsSearch.matches(testContext.getString(key), query, resolve)

    @Test
    fun `other words find the setting they stand for`() {
        assertTrue(finds("freeze", R.string.pref_key_dejavu_tab_sleep))
        assertTrue(finds("arc", R.string.pref_key_dejavu_tabs_workspaces))
        assertTrue(finds("oled", R.string.pref_key_light_theme))
    }

    @Test
    fun `the beginning of a word is enough, in any case and with spaces around`() {
        assertTrue(finds("  Free ", R.string.pref_key_dejavu_tab_sleep))
        assertTrue(finds("WORKSP", R.string.pref_key_dejavu_tabs_workspaces))
    }

    @Test
    fun `other words do not find other settings`() {
        assertFalse(finds("freeze", R.string.pref_key_dejavu_containers))
        assertFalse(finds("arc", R.string.pref_key_dejavu_tab_sleep))
    }

    @Test
    fun `a single letter or nothing finds no setting`() {
        assertTrue(SettingsSearch.synonymKeys("f").isEmpty())
        assertTrue(SettingsSearch.synonymKeys("   ").isEmpty())
        assertTrue(SettingsSearch.synonymKeys("zzzz").isEmpty())
    }

    @Test
    fun `every word of the table finds its settings`() {
        SettingsSearch.SYNONYMS.forEach { (words, keys) ->
            words.forEach { assertTrue("$it finds nothing", SettingsSearch.synonymKeys(it).containsAll(keys)) }
        }
    }
}
