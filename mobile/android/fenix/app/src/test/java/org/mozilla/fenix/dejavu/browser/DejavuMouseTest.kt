/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DejavuMouseTest {
    @Test
    fun `opens web links only`() {
        assertTrue(isOpenableLink("https://example.com/a?b#c"))
        assertTrue(isOpenableLink("HTTP://example.com"))
        listOf(
            "",
            "https://",
            "https:example.com",
            "file:///sdcard/a.html",
            "about:config",
            "about:blank",
            "chrome://browser/content/a.xhtml",
            "resource://android/assets/a.js",
            "intent://scan#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "data:text/html,<b>a</b>",
            " https://example.com",
        ).forEach { assertFalse(it, isOpenableLink(it)) }
    }
}
