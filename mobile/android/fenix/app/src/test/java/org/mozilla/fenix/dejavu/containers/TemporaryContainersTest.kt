/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.containers

import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.state.recover.TabState
import org.junit.Assert.assertEquals
import org.junit.Test

class TemporaryContainersTest {
    @Test
    fun `open and recently closed tabs keep their containers`() {
        val state = BrowserState(
            tabs = listOf(createTab("https://open.example", contextId = "open"), createTab("https://none.example")),
            closedTabs = listOf(
                TabState(id = "closed", url = "https://closed.example", contextId = "closed"),
                TabState(id = "plain", url = "https://plain.example"),
            ),
        )
        assertEquals(setOf("open", "closed"), TemporaryContainerMiddleware.usedContextIds(state))
    }
}
