/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.discover

import androidx.preference.Preference
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.menu.MoreMenuEntry
import org.mozilla.fenix.dejavu.menu.MoreMenuItem
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NewThingsTest {
    @Test
    fun `the Clear data buttons have the dot until the user looked at them`() {
        val clearData = MoreMenuEntry.BuiltIn(MoreMenuItem.CLEAR_DATA)
        val clearAndQuit = MoreMenuEntry.BuiltIn(MoreMenuItem.CLEAR_DATA_AND_QUIT)

        assertTrue(clearData.isNewAmong(setOf(NewThing.CLEAR_DATA_ENTRIES)))
        assertTrue(clearAndQuit.isNewAmong(setOf(NewThing.CLEAR_DATA_ENTRIES)))
        assertFalse(clearData.isNewAmong(setOf(NewThing.CLEAR_DATA_CHOICES)))
        assertFalse(MoreMenuEntry.BuiltIn(MoreMenuItem.BACK).isNewAmong(NewThing.entries.toSet()))
    }

    @Test
    fun `Settings has the dot while anything in the settings is new, except in the menu's editor`() {
        val settings = MoreMenuEntry.BuiltIn(MoreMenuItem.SETTINGS)

        assertTrue(settings.isNewAmong(setOf(NewThing.CLEAR_DATA_CHOICES)))
        assertFalse(settings.isNewAmong(setOf(NewThing.CLEAR_DATA_CHOICES), leadsToSettings = false))
        assertFalse(settings.isNewAmong(emptySet()))
    }

    @Test
    fun `the dot after a preference's title comes and goes, and screen readers read it as New`() {
        val preference = Preference(testContext).apply { title = "Customize" }
        val new = testContext.getString(R.string.dejavu_new)

        preference.showNewDot(true)
        preference.showNewDot(true)
        assertEquals("Customize $new", preference.title.toString())

        preference.showNewDot(false)
        assertEquals("Customize", preference.title.toString())
    }
}
