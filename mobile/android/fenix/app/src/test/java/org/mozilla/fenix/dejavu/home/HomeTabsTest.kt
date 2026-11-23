/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import mozilla.components.browser.state.state.createTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeTabsTest {
    private val tab = createTab(url = "https://a.example/", id = "a", title = "A")

    private fun homeOf(vararg tabs: mozilla.components.browser.state.state.TabSessionState) = HomeTabs(tabs.toList())

    @Test
    fun `load progress and other tab state leave the list equal`() {
        val loading = tab.copy(content = tab.content.copy(progress = 40, loading = true))
        assertEquals(homeOf(tab), homeOf(loading))
        assertEquals(homeOf(tab).hashCode(), homeOf(loading).hashCode())
    }

    @Test
    fun `a new title or address makes the list differ`() {
        assertNotEquals(homeOf(tab), homeOf(tab.copy(content = tab.content.copy(title = "B"))))
        assertNotEquals(homeOf(tab), homeOf(tab.copy(content = tab.content.copy(url = "https://b.example/"))))
    }

    @Test
    fun `a container change makes the list differ`() {
        assertNotEquals(homeOf(tab), homeOf(tab.copy(contextId = "work")))
    }

    @Test
    fun `adding, removing or reordering tabs makes the list differ`() {
        val other = createTab(url = "https://b.example/", id = "b", title = "B")
        assertNotEquals(homeOf(tab), homeOf(tab, other))
        assertNotEquals(homeOf(tab, other), homeOf(other, tab))
    }
}
