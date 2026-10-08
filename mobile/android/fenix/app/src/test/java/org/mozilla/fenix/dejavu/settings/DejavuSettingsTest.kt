/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.content.Context
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
}
