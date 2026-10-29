/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import androidx.core.content.edit
import io.mockk.every
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.settings.deletebrowsingdata.DeleteBrowsingDataOnQuitType
import org.mozilla.fenix.utils.Settings
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClearDataTest {
    @Before
    fun setup() {
        every { testContext.components.settings } returns Settings(testContext)
    }

    @Test
    fun `nothing is chosen at first, and the checkboxes choose what is cleared`() {
        assertTrue(ClearData.chosen(testContext).isEmpty())

        testContext.components.settings.preferences.edit {
            putBoolean(testContext.getString(R.string.pref_key_dejavu_clear_data_history), true)
            putBoolean(testContext.getString(R.string.pref_key_dejavu_clear_data_cookies), true)
        }

        assertEquals(
            setOf(DeleteBrowsingDataOnQuitType.HISTORY, DeleteBrowsingDataOnQuitType.COOKIES),
            ClearData.chosen(testContext),
        )
        assertEquals(
            DeleteBrowsingDataOnQuitType.DOWNLOADS,
            ClearData.typeOf(testContext, testContext.getString(R.string.pref_key_dejavu_clear_data_downloads)),
        )
        assertEquals(DeleteBrowsingDataOnQuitType.entries.size, ClearData.keys(testContext).size)
    }

    @Test
    fun `nothing chosen has nothing to clear`() {
        assertFalse(hasData(emptySet(), tabs = 3, closedTabs = 1, downloads = 2, lastVisit = VISIT))
    }

    @Test
    fun `tabs, history and downloads have something to clear while there are any`() {
        assertTrue(hasData(setOf(DeleteBrowsingDataOnQuitType.TABS), tabs = 1))
        assertFalse(hasData(setOf(DeleteBrowsingDataOnQuitType.TABS), tabs = 0))
        assertTrue(hasData(setOf(DeleteBrowsingDataOnQuitType.HISTORY), lastVisit = VISIT))
        assertTrue(hasData(setOf(DeleteBrowsingDataOnQuitType.HISTORY), closedTabs = 1))
        assertFalse(hasData(setOf(DeleteBrowsingDataOnQuitType.HISTORY), tabs = 4, downloads = 2))
        assertTrue(hasData(setOf(DeleteBrowsingDataOnQuitType.DOWNLOADS), downloads = 1))
        assertFalse(hasData(setOf(DeleteBrowsingDataOnQuitType.DOWNLOADS), lastVisit = VISIT))
    }

    @Test
    fun `what pages leave behind is there once a page was visited since it was last cleared`() {
        ClearData.SiteData.forEach { type ->
            assertTrue(hasData(setOf(type), lastVisit = VISIT))
            assertFalse(hasData(setOf(type), lastVisit = VISIT, clearedAt = VISIT + 1))
            assertTrue(hasData(setOf(type), lastVisit = VISIT, clearedAt = VISIT - 1))
            assertFalse(hasData(setOf(type), tabs = 2, lastVisit = null))
        }
    }

    @Suppress("LongParameterList")
    private fun hasData(
        types: Set<DeleteBrowsingDataOnQuitType>,
        tabs: Int = 0,
        closedTabs: Int = 0,
        downloads: Int = 0,
        lastVisit: Long? = null,
        clearedAt: Long = 0L,
    ) = ClearData.hasData(types, tabs, closedTabs, downloads, lastVisit, clearedAt)

    private companion object {
        const val VISIT = 1_000L
    }
}
