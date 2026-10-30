/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.createTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepShownTabTest {
    private fun tab(id: String, lastAccess: Long = 0L, private: Boolean = false) =
        createTab(url = "https://$id.example/", id = id, private = private, lastAccess = lastAccess)

    private fun state(vararg tabs: TabSessionState, selected: String? = null) =
        BrowserState(tabs = tabs.toList(), selectedTabId = selected)

    private val sameWorkspace: (String) -> String? = { "main" }

    @Test
    fun `a shown tab that does not sleep stays`() {
        val state = state(tab("shown"), tab("other", 5), tab("sleeper", 9), selected = "shown")

        assertNull(tabToShowInstead(state, setOf("sleeper"), sameWorkspace))
    }

    @Test
    fun `nothing is shown when no tab is`() {
        assertNull(tabToShowInstead(state(tab("a"), tab("b")), setOf("a"), sameWorkspace))
    }

    @Test
    fun `the tab used last takes its place`() {
        val state = state(tab("old", 1), tab("shown", 9), tab("recent", 7), tab("older", 3), selected = "shown")

        assertEquals("recent", tabToShowInstead(state, setOf("shown"), sameWorkspace))
    }

    @Test
    fun `tabs that sleep with it do not take its place`() {
        val state = state(tab("shown", 9), tab("recent", 7), tab("older", 3), selected = "shown")

        assertEquals("older", tabToShowInstead(state, setOf("shown", "recent"), sameWorkspace))
    }

    @Test
    fun `a tab of the same workspace comes first`() {
        val workspaces = mapOf("shown" to "work", "recent" to "home", "older" to "work")
        val state = state(tab("shown", 9), tab("recent", 7), tab("older", 3), selected = "shown")

        assertEquals("older", tabToShowInstead(state, setOf("shown")) { workspaces[it] })
    }

    @Test
    fun `another workspace serves when the own has no other tab`() {
        val workspaces = mapOf("shown" to "work", "recent" to "home", "older" to "play")
        val state = state(tab("shown", 9), tab("older", 3), tab("recent", 7), selected = "shown")

        assertEquals("recent", tabToShowInstead(state, setOf("shown")) { workspaces[it] })
    }

    @Test
    fun `a private tab only leads to a private one`() {
        val state = state(tab("shown", 1, private = true), tab("normal", 9), tab("hidden", 4, private = true), selected = "shown")

        assertEquals("hidden", tabToShowInstead(state, setOf("shown"), sameWorkspace))
    }

    @Test
    fun `a normal tab never leads to a private one`() {
        val state = state(tab("shown", 1), tab("secret", 9, private = true), tab("plain", 4), selected = "shown")

        assertEquals("plain", tabToShowInstead(state, setOf("shown"), sameWorkspace))
    }

    @Test
    fun `no tab of the same kind leaves nothing`() {
        val privateOnly = state(tab("shown", 1, private = true), tab("normal", 9), selected = "shown")
        val normalOnly = state(tab("shown", 1), tab("secret", 9, private = true), selected = "shown")
        val alone = state(tab("shown"), selected = "shown")

        assertNull(tabToShowInstead(privateOnly, setOf("shown"), sameWorkspace))
        assertNull(tabToShowInstead(normalOnly, setOf("shown"), sameWorkspace))
        assertNull(tabToShowInstead(alone, setOf("shown"), sameWorkspace))
    }
}
