/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.os.Handler
import android.os.Looper
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.DefaultDesktopModeAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

/**
 * A tab opened from another one, like a link opened in a new tab, a pop-up or a duplicate, shows its pages in the mode
 * of the tab it came from: a desktop site opens desktop sites, a mobile site mobile ones. A tab the page opened by
 * itself comes with an engine session made in the mobile mode, which is switched too.
 */
internal class InheritedDesktopModeMiddleware : Middleware<BrowserState, BrowserAction> {
    private val main = Handler(Looper.getMainLooper())

    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        val parent = (action as? TabListAction.AddTabAction)?.tab?.parentId?.let { store.state.findTab(it) }
        if (action !is TabListAction.AddTabAction || parent == null) {
            next(action)
            return
        }
        val tab = action.tab
        val desktop = parent.content.desktopMode
        val inherited = if (tab.content.desktopMode == desktop) {
            action
        } else {
            action.copy(tab = tab.copy(content = tab.content.copy(desktopMode = desktop)))
        }
        next(inherited)
        val session = tab.engineState.engineSession
        if (desktop && session != null) main.post { session.toggleDesktopMode(enable = true, reload = true) }
    }
}

/**
 * New tabs start in the mode the user last switched a tab to, desktop or mobile site, which becomes Firefox's "Always
 * request desktop site" setting. Only the user's own switches count: they are the ones that go through
 * [EngineAction.ToggleDesktopModeAction].
 */
internal class LastDesktopModeMiddleware : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        next(action)
        if (action is EngineAction.ToggleDesktopModeAction && action.enable != store.state.desktopMode) {
            store.dispatch(DefaultDesktopModeAction.ToggleDesktopMode)
        }
    }
}
