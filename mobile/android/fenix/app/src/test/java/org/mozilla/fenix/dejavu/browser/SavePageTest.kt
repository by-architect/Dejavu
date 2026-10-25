/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class SavePageTest {
    @Test
    fun `a saved page is named after its title`() {
        val name = savedPageName("Firefox - Wikipedia", "https://en.wikipedia.org/wiki/Firefox")

        assertEquals("Firefox - Wikipedia.html", name)
    }

    @Test
    fun `characters a file name cannot hold become spaces`() {
        assertEquals("a b c d e f g.html", savedPageName("a/b\\c:d*e?\"f<>|g", "https://example.com/"))
        assertEquals("Two lines.html", savedPageName("Two\nlines", "https://example.com/"))
    }

    @Test
    fun `a page without a title is named after its site`() {
        assertEquals("example.com.html", savedPageName("", "https://www.example.com/page"))
        assertEquals("example.com.html", savedPageName("   ", "https://example.com/"))
        assertEquals("page.html", savedPageName("", "not a url"))
    }

    @Test
    fun `leading and trailing dots are left out`() {
        assertEquals("hidden.html", savedPageName(".hidden.", "https://example.com/"))
    }

    @Test
    fun `a long title is cut without splitting a character`() {
        val title = "a".repeat(99) + "😀" + "b"
        val name = savedPageName(title, "https://example.com/")

        assertEquals("a".repeat(99) + ".html", name)
    }
}
