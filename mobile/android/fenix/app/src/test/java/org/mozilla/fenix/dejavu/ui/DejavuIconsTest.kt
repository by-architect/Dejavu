/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.ui

import mozilla.components.ui.icons.R as iconsR
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.fenix.R

class DejavuIconsTest {
    @Test
    fun `Dejavu's own style keeps every icon as it is`() {
        assertEquals(R.drawable.dejavu_ic_home_24, iconIn(IconStyle.DEJAVU, R.drawable.dejavu_ic_home_24))
        assertEquals(iconsR.drawable.mozac_ic_back_24, iconIn(IconStyle.DEJAVU, iconsR.drawable.mozac_ic_back_24))
    }

    @Test
    fun `the classic style brings back the icons Dejavu had before its own`() {
        assertEquals(iconsR.drawable.mozac_ic_home_24, iconIn(IconStyle.FIREFOX, R.drawable.dejavu_ic_home_24))
        assertEquals(
            iconsR.drawable.mozac_ic_share_android_24,
            iconIn(IconStyle.FIREFOX, R.drawable.dejavu_ic_share_24),
        )
        assertEquals(
            R.drawable.dejavu_ic_classic_save_page_24,
            iconIn(IconStyle.FIREFOX, R.drawable.dejavu_ic_save_page_24),
        )
        // Firefox's icons are already in that style.
        assertEquals(iconsR.drawable.mozac_ic_back_24, iconIn(IconStyle.FIREFOX, iconsR.drawable.mozac_ic_back_24))
    }

    @Test
    fun `the rounded style has its own icons for Dejavu's and for the Firefox ones of the menus`() {
        assertEquals(R.drawable.dejavu_ic_rounded_home_24, iconIn(IconStyle.ROUNDED, R.drawable.dejavu_ic_home_24))
        assertEquals(
            R.drawable.dejavu_ic_rounded_arrow_back_24,
            iconIn(IconStyle.ROUNDED, iconsR.drawable.mozac_ic_back_24),
        )
    }

    @Test
    fun `pictures that are no icons stay as they are in every style`() {
        IconStyle.entries.forEach { style ->
            assertEquals(R.drawable.dejavu_illustration_history, iconIn(style, R.drawable.dejavu_illustration_history))
        }
    }

    @Test
    fun `a style is kept by its key, and an unknown key means Dejavu's own`() {
        IconStyle.entries.forEach { assertEquals(it, IconStyle.of(it.key)) }
        assertEquals(IconStyle.DEJAVU, IconStyle.of(null))
        assertEquals(IconStyle.DEJAVU, IconStyle.of("square"))
    }
}
