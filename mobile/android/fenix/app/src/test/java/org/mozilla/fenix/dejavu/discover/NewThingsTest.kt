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
import org.mozilla.fenix.dejavu.features.FeatureTour
import org.mozilla.fenix.dejavu.menu.MoreMenuEntry
import org.mozilla.fenix.dejavu.menu.MoreMenuItem
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NewThingsTest {
    @Test
    fun `the Full screen button has the dot until the user looked at it`() {
        val fullScreen = MoreMenuEntry.BuiltIn(MoreMenuItem.FULL_SCREEN)

        assertTrue(fullScreen.isNewAmong(setOf(NewThing.FULL_SCREEN)))
        assertFalse(fullScreen.isNewAmong(setOf(NewThing.ACCENT_COLOR)))
        assertFalse(MoreMenuEntry.BuiltIn(MoreMenuItem.BACK).isNewAmong(NewThing.entries.toSet()))
    }

    @Test
    fun `the Screenshot button has the dot until the user looked at it`() {
        val screenshot = MoreMenuEntry.BuiltIn(MoreMenuItem.SCREENSHOT)

        assertTrue(screenshot.isNewAmong(setOf(NewThing.SCREENSHOT)))
        assertFalse(screenshot.isNewAmong(setOf(NewThing.FULL_SCREEN)))
        assertTrue(NewThing.SCREENSHOT in NewThing.CUSTOMIZE)
    }

    @Test
    fun `Settings has the dot while anything in the settings is new, except in the menu's editor`() {
        val settings = MoreMenuEntry.BuiltIn(MoreMenuItem.SETTINGS)

        assertTrue(settings.isNewAmong(setOf(NewThing.ESSENTIALS)))
        assertTrue(settings.isNewAmong(setOf(NewThing.PRIVATE_BROWSING)))
        assertFalse(settings.isNewAmong(setOf(NewThing.ESSENTIALS), leadsToSettings = false))
        assertFalse(settings.isNewAmong(emptySet()))
    }

    @Test
    fun `the lock and history of workspaces are found on the home screen, so they lead nobody to the settings`() {
        val settings = MoreMenuEntry.BuiltIn(MoreMenuItem.SETTINGS)

        assertFalse(settings.isNewAmong(setOf(NewThing.WORKSPACE_PRIVACY)))
        assertFalse(setOf(NewThing.WORKSPACE_PRIVACY).inSettings())
    }

    @Test
    fun `a fresh install has nothing new, whatever the screen`() {
        listOf("4.1.0", "4.2.0").forEach { start ->
            NewThing.entries.forEach {
                assertFalse("${it.name} is new from $start", it.cameWithUpdate(start, largeScreen = true))
                assertFalse("${it.name} is new from $start", it.cameWithUpdate(start, largeScreen = false))
            }
        }
    }

    @Test
    fun `an update brings what the versions after the one it began with brought`() {
        val from40 = NewThing.entries.filter { it.cameWithUpdate("4.0", largeScreen = true) }.toSet()
        val from30 = NewThing.entries.filter { it.cameWithUpdate("3.0", largeScreen = true) }.toSet()

        assertEquals(setOf(NewThing.KEYBOARD_SHORTCUTS, NewThing.TAB_SIDEBAR, NewThing.WHATS_NEW), from40)
        assertEquals(NewThing.entries.toSet(), from30)
        assertFalse(NewThing.ACCENT_COLOR.cameWithUpdate("4.0.2", largeScreen = false))
        assertTrue(NewThing.KEYBOARD_SHORTCUTS.cameWithUpdate("4.0.2", largeScreen = false))
    }

    @Test
    fun `the tab sidebar is only new on large screens, where it shows`() {
        assertTrue(NewThing.TAB_SIDEBAR.cameWithUpdate("4.0", largeScreen = true))
        assertFalse(NewThing.TAB_SIDEBAR.cameWithUpdate("4.0", largeScreen = false))
    }

    @Test
    fun `Customize leads to the new things of its screens, and the entries of wide windows to theirs`() {
        assertEquals(NewThing.CUSTOMIZE, NewThing.ledToBy(R.string.pref_key_customize))
        assertTrue(NewThing.KEYBOARD_SHORTCUTS in NewThing.CUSTOMIZE)
        assertTrue(NewThing.TAB_SIDEBAR in NewThing.CUSTOMIZE)
        assertEquals(setOf(NewThing.TAB_SIDEBAR), NewThing.ledToBy(R.string.pref_key_dejavu_bars))
        assertEquals(NewThing.MENU_BUTTONS, NewThing.ledToBy(R.string.pref_key_dejavu_more_menu))
        assertTrue(NewThing.ledToBy(R.string.pref_key_addons).isEmpty())
    }

    @Test
    fun `What's new has the dot of its newest chapter`() {
        val newest = FeatureTour.Releases.last().version

        assertEquals(newest, NewThing.WHATS_NEW.version)
        assertEquals(setOf(NewThing.WHATS_NEW), NewThing.ledToBy(R.string.pref_key_dejavu_whats_new))
    }

    @Test
    fun `every new thing found in the settings has an entry of the Settings screen leading to it`() {
        val ledTo = NewThing.SETTINGS_ENTRIES.flatMap { it.second }.toSet()

        NewThing.entries.filter { it.inSettings }.forEach { assertTrue("${it.name} has no way", it in ledTo) }
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
