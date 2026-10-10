/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.content.ComponentCallbacks2
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.InitAction
import mozilla.components.browser.state.action.SystemAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository

/**
 * Puts tabs to sleep that were not looked at for a while, so their pages stop taking memory; they load again when
 * opened. Firefox for Android never unloads tabs by itself, so every tab opened since the browser
 * started otherwise stays loaded until Android runs out of memory and kills the whole browser. When Android asks for
 * memory while Dejavu is in the background, all tabs but the most recent ones sleep at once.
 *
 * The shown tab, the tab next to it in split view, tabs still loading and tabs playing media or using the camera or
 * microphone never sleep. [DejavuSettings.tabSleepMinutes] sets the wait, or turns sleeping off.
 */
internal class TabSleepMiddleware : Middleware<BrowserState, BrowserAction> {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null

    // When each tab stopped being the shown one. A tab's own last access is when it was last selected, so a tab read
    // for an hour would otherwise count as unused from the moment it is left.
    private val lastShown = ConcurrentHashMap<String, Long>()

    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        val shownBefore = store.state.selectedTabId
        next(action)
        val shownAfter = store.state.selectedTabId
        if (shownBefore != null && shownBefore != shownAfter) lastShown[shownBefore] = System.currentTimeMillis()

        when (action) {
            is InitAction -> startTicker(store)
            is SystemAction.LowMemoryAction -> if (isLowOnMemory(action.level)) {
                if (sleepMinutes() > 0) {
                    sleep(store, TabSleep.tabsForMemory(store.state, lastShown, keptAwake(store.state)))
                }
            }
            else -> Unit
        }
    }

    private fun startTicker(store: Store<BrowserState, BrowserAction>) {
        if (ticker != null) return
        ticker = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                val minutes = sleepMinutes()
                lastShown.keys.retainAll(store.state.tabs.mapTo(HashSet()) { it.id })
                if (minutes <= 0) continue
                val idle = TabSleep.idleTabs(
                    state = store.state,
                    lastShown = lastShown,
                    keep = keptAwake(store.state),
                    now = System.currentTimeMillis(),
                    idleMillis = minutes * MILLIS_PER_MINUTE,
                )
                if (idle.isNotEmpty()) withContext(Dispatchers.Main) { sleep(store, idle) }
            }
        }
    }

    /**
     * Whether Android asks for memory: the app went to the background, or, on Android 13 and older, which still say so
     * while the app is shown, memory runs critically low.
     */
    @Suppress("DEPRECATION")
    private fun isLowOnMemory(level: Int): Boolean =
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL

    private fun sleep(store: Store<BrowserState, BrowserAction>, tabIds: List<String>) {
        tabIds.forEach { store.dispatch(EngineAction.SuspendEngineSessionAction(it)) }
    }

    private fun sleepMinutes(): Int =
        DejavuSettings.peek()?.tabSleepMinutes?.value ?: DejavuSettings.DEFAULT_TAB_SLEEP_MINUTES

    /** The shown tab and the tab shown next to it in split view. */
    private fun keptAwake(state: BrowserState): Set<String> {
        val shown = state.selectedTabId ?: return emptySet()
        val split = WorkspaceRepository.peek()?.state?.value?.splitOf(shown)?.tabIds.orEmpty()
        return split.toSet() + shown
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 60_000L
        const val MILLIS_PER_MINUTE = 60_000L
    }
}

/** Which tabs [TabSleepMiddleware] puts to sleep. */
internal object TabSleep {
    /** How many tabs stay awake, besides the shown one, when Android asks for memory in the background. */
    const val KEEP_RECENT_FOR_MEMORY = 2

    /** Awake tabs not looked at for [idleMillis], leaving out [keep] and tabs that must stay awake. */
    fun idleTabs(
        state: BrowserState,
        lastShown: Map<String, Long>,
        keep: Set<String>,
        now: Long,
        idleMillis: Long,
    ): List<String> = sleepable(state, keep)
        .filter { now - lastUsed(it, lastShown) >= idleMillis }
        .map { it.id }

    /** Every awake tab but the [KEEP_RECENT_FOR_MEMORY] most recently looked at, leaving out [keep]. */
    fun tabsForMemory(state: BrowserState, lastShown: Map<String, Long>, keep: Set<String>): List<String> =
        sleepable(state, keep)
            .sortedByDescending { lastUsed(it, lastShown) }
            .drop(KEEP_RECENT_FOR_MEMORY)
            .map { it.id }

    private fun sleepable(state: BrowserState, keep: Set<String>): List<TabSessionState> = state.tabs.filter { tab ->
        tab.engineState.engineSession != null &&
            tab.id !in keep &&
            !tab.content.loading &&
            tab.content.recordingDevices.isEmpty() &&
            tab.mediaSessionState?.playbackState != MediaSession.PlaybackState.PLAYING
    }

    private fun lastUsed(tab: TabSessionState, lastShown: Map<String, Long>): Long =
        maxOf(tab.lastAccess, tab.createdAt, lastShown[tab.id] ?: 0L)
}
