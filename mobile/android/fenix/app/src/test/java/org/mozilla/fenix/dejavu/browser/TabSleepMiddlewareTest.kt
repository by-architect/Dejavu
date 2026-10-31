/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.content.ComponentCallbacks2
import io.mockk.mockk
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.SystemAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.lib.state.Middleware
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TabSleepMiddlewareTest {
    private val session: EngineSession = mockk(relaxed = true)

    /** The tabs put to sleep, which stop here instead of reaching the engine's middleware. */
    private val sleeping = mutableListOf<String>()

    private val store = BrowserStore(
        initialState = BrowserState(
            tabs = listOf("a", "b", "c", "d", "shown").mapIndexed { index, id ->
                createTab("https://$id.example/", id = id, engineSession = session, lastAccess = index + 1L, createdAt = 0L)
            },
            selectedTabId = "shown",
        ),
        middleware = listOf(recordSleeping(), TabSleepMiddleware()),
    )

    private fun recordSleeping(): Middleware<BrowserState, BrowserAction> = { _, next, action ->
        if (action is EngineAction.SuspendEngineSessionAction) sleeping += action.tabId else next(action)
    }

    @Test
    fun `going to the home screen or another app leaves the tabs awake`() {
        store.dispatch(SystemAction.LowMemoryAction(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        store.dispatch(SystemAction.LowMemoryAction(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))

        assertEquals(emptyList<String>(), sleeping)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `when Android runs low on memory every tab but the most recent ones sleeps`() {
        store.dispatch(SystemAction.LowMemoryAction(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))

        assertEquals(listOf("b", "a"), sleeping)
    }

    @Test
    fun `closing Dejavu puts every tab but the most recent ones to sleep`() {
        TabSleepMiddleware.closed(store)

        assertEquals(listOf("b", "a"), sleeping)
    }
}
