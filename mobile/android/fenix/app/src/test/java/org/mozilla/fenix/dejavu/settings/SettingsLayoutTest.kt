/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLayoutTest {
    @Test
    fun `windows are wide from 600 dp on`() {
        assertFalse(SettingsLayout.isWide(599))
        assertTrue(SettingsLayout.isWide(600))
        assertTrue(SettingsLayout.isWide(1280))
    }

    @Test
    fun `content stays at its widest and in the middle of a wide space`() {
        assertEquals(460, SettingsLayout.sideInset(available = 1600, maxWidth = 680, minimum = 16))
    }

    @Test
    fun `content in a narrow space keeps its margin`() {
        assertEquals(16, SettingsLayout.sideInset(available = 360, maxWidth = 680, minimum = 16))
        assertEquals(16, SettingsLayout.sideInset(available = 700, maxWidth = 680, minimum = 16))
    }
}
