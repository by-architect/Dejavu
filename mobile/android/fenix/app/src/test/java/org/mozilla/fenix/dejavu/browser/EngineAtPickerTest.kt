/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.graphics.Bitmap
import mozilla.components.browser.state.search.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EngineAtPickerTest {
    private val engines = listOf("Google", "DuckDuckGo", "YouTube", "Wikipedia (en)", "Bookmarks", "History").map {
        SearchEngine(
            id = it,
            name = it,
            icon = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            type = SearchEngine.Type.BUNDLED,
        )
    }

    private fun names(text: String) = enginesMatching(text, engines)?.map { it.engine.name }

    @Test
    fun `the typed letters match in order anywhere in the name`() {
        assertEquals(listOf(0, 4, 9), fuzzyMatch("ddo", "DuckDuckGo")?.second)
        assertNull(fuzzyMatch("odd", "DuckDuckGo"))
    }

    @Test
    fun `letters starting the name or a word count more`() {
        assertEquals("DuckDuckGo", names("@ddg")?.first())
        assertEquals("YouTube", names("@yt")?.first())
        assertEquals("Wikipedia (en)", names("@wen")?.first())
    }

    @Test
    fun `only an at sign lists every engine in the user order`() {
        assertEquals(engines.map { it.name }, names("@"))
    }

    @Test
    fun `no at sign, a space or no match searches as usual`() {
        assertNull(names("ddg"))
        assertNull(names("@ddg cats"))
        assertNull(names("@zzz"))
    }
}
