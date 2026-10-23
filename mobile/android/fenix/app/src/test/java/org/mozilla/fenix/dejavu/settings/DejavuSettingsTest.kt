/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.content.Context
import mozilla.components.support.test.robolectric.testContext
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.actions.CustomAction
import org.mozilla.fenix.dejavu.actions.HttpMethod
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DejavuSettingsTest {
    /** Settings as the app reads them when it starts. */
    private fun newSettings(): DejavuSettings =
        DejavuSettings::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(testContext)

    @Test
    fun `essentials per container follow sync until the user chooses`() {
        val settings = newSettings()
        assertFalse(settings.essentialsPerContainer.value)

        settings.setEssentialsPerContainerFromSync(true)
        assertTrue(settings.essentialsPerContainer.value)
        assertTrue(newSettings().essentialsPerContainer.value)

        settings.setEssentialsPerContainer(false)
        settings.setEssentialsPerContainerFromSync(true)
        assertFalse(settings.essentialsPerContainer.value)
        assertFalse(newSettings().essentialsPerContainer.value)
    }

    @Test
    fun `the status bar and the navigation bar show over pages until the user hides them`() {
        val settings = newSettings()
        assertFalse(settings.systemBarsHidden.value)

        settings.setSystemBarsHidden(true)
        assertTrue(settings.systemBarsHidden.value)
        assertTrue(newSettings().systemBarsHidden.value)

        settings.setSystemBarsHidden(false)
        assertFalse(newSettings().systemBarsHidden.value)
    }

    @Test
    fun `custom actions an older Dejavu saved in plain text still load, and saved ones load again`() {
        val action = CustomAction("a", "Karakeep", HttpMethod.POST, "https://example.com", emptyList(), "{}")
        testContext.getSharedPreferences("kaizen_settings", Context.MODE_PRIVATE).edit()
            .putString("custom_actions", JSONArray().put(action.toJson()).toString())
            .commit()

        val settings = newSettings()
        assertEquals(listOf(action), settings.customActions.value)

        val renamed = action.copy(name = "Linkwarden")
        settings.saveCustomAction(renamed)
        assertEquals(listOf(renamed), newSettings().customActions.value)
    }
}
