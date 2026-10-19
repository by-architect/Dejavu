/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.WebExtensionState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.webextension.WebExtensionBrowserAction
import mozilla.components.feature.addons.Addon
import mozilla.components.support.test.middleware.CaptureActionsMiddleware
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.fenix.components.menu.WebExtensionsMenuBinding
import org.mozilla.fenix.components.menu.store.MenuAction
import org.mozilla.fenix.components.menu.store.MenuState
import org.mozilla.fenix.components.menu.store.MenuStore
import org.mozilla.fenix.components.menu.store.WebExtensionMenuItem
import org.mozilla.fenix.dejavu.menu.openingAddonPages

class ExtensionsWithoutTabTest {
    private val dispatcher = StandardTestDispatcher()
    private val actions = CaptureActionsMiddleware<MenuState, MenuAction>()
    private val menuStore = MenuStore(initialState = MenuState(), middleware = listOf(actions))

    private val uBlockOrigin =
        WebExtensionState(
            id = "uBlock0@raymondhill.net",
            name = "uBlock Origin",
            browserAction =
                WebExtensionBrowserAction(
                    title = "uBlock Origin",
                    enabled = true,
                    loadIcon = { null },
                    badgeText = "",
                    badgeTextColor = 0,
                    badgeBackgroundColor = 0,
                    onClick = {},
                ),
        )

    private fun listedExtensions(state: BrowserState): List<WebExtensionMenuItem> {
        WebExtensionsMenuBinding(
                browserStore = BrowserStore(state),
                customTabId = null,
                menuStore = menuStore,
                iconSize = 24,
                onDismiss = {},
                mainDispatcher = dispatcher,
            )
            .start()
        dispatcher.scheduler.advanceUntilIdle()
        return actions.findLastAction(MenuAction.UpdateWebExtensionBrowserMenuItems::class).webExtensionBrowserMenuItem
    }

    private fun listedWithoutTab() = listedExtensions(BrowserState(extensions = mapOf(uBlockOrigin.id to uBlockOrigin)))

    @Test
    fun `with no tab open, as after a fresh install, the extensions are listed`() = runTest {
        assertEquals(listOf("uBlock Origin"), listedWithoutTab().map { it.label })
    }

    @Test
    fun `a private tab still leaves out the extensions that are not allowed in private tabs`() = runTest {
        val tab = createTab("https://example.org", id = "tab", private = true)
        val state =
            BrowserState(
                tabs = listOf(tab),
                selectedTabId = tab.id,
                extensions = mapOf(uBlockOrigin.id to uBlockOrigin),
            )

        assertEquals(emptyList<String>(), listedExtensions(state).map { it.label })
    }

    @Test
    fun `with no tab open, an extension opens its page among the installed extensions`() = runTest {
        val addon = Addon(id = uBlockOrigin.id)

        listedWithoutTab().openingAddonPages(listOf(addon), menuStore).single().onClick()

        actions.assertLastAction(MenuAction.Navigate.InstalledAddonDetails::class) { assertEquals(addon, it.addon) }
    }

    @Test
    fun `while the installed extensions are still loading, an extension opens the list of extensions`() = runTest {
        listedWithoutTab().openingAddonPages(emptyList(), menuStore).single().onClick()

        actions.assertLastAction(MenuAction.Navigate.ManageExtensions::class)
    }
}
