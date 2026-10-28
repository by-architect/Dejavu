/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import io.mockk.mockk
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.MediaSessionState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.createTab
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.media.RecordingDevice
import mozilla.components.concept.engine.mediasession.MediaSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.settings.DejavuTabSleepFragment

class TabSleepTest {
    private val session: EngineSession = mockk(relaxed = true)

    private fun awake(id: String, lastAccess: Long = 0L) =
        createTab(url = "https://$id.example/", id = id, engineSession = session, lastAccess = lastAccess, createdAt = 0L)

    private fun state(vararg tabs: TabSessionState, selected: String? = null) =
        BrowserState(tabs = tabs.toList(), selectedTabId = selected)

    @Test
    fun `tabs not looked at for the wait sleep, newer and shown ones stay awake`() {
        val state = state(awake("old", lastAccess = 1_000), awake("new", lastAccess = 50_000), awake("shown"), selected = "shown")

        val idle = TabSleep.idleTabs(state, emptyMap(), keep = setOf("shown"), now = 61_000, idleMillis = 60_000)

        assertEquals(listOf("old"), idle)
    }

    @Test
    fun `a tab counts as used until it stops being the shown one`() {
        val state = state(awake("read", lastAccess = 1_000), awake("shown"), selected = "shown")

        val idle = TabSleep.idleTabs(
            state,
            lastShown = mapOf("read" to 55_000L),
            keep = setOf("shown"),
            now = 61_000,
            idleMillis = 60_000,
        )

        assertEquals(emptyList<String>(), idle)
    }

    @Test
    fun `sleeping, loading, playing and recording tabs are left alone`() {
        val sleeping = createTab(url = "https://sleeping.example/", id = "sleeping", createdAt = 0L)
        val loading = awake("loading").let { it.copy(content = it.content.copy(loading = true)) }
        val playing = awake("playing").copy(
            mediaSessionState = MediaSessionState(controller = mockk(relaxed = true), playbackState = MediaSession.PlaybackState.PLAYING),
        )
        val recording = awake("recording").let {
            it.copy(
                content = it.content.copy(
                    recordingDevices = listOf(
                        RecordingDevice(RecordingDevice.Type.MICROPHONE, RecordingDevice.Status.RECORDING),
                    ),
                ),
            )
        }
        val state = state(sleeping, loading, playing, recording, awake("idle"))

        val idle = TabSleep.idleTabs(state, emptyMap(), keep = emptySet(), now = 1_000_000, idleMillis = 60_000)

        assertEquals(listOf("idle"), idle)
    }

    @Test
    fun `when memory runs low every tab but the most recent ones sleeps`() {
        val state = state(
            awake("a", lastAccess = 1),
            awake("b", lastAccess = 2),
            awake("c", lastAccess = 3),
            awake("d", lastAccess = 4),
            awake("shown", lastAccess = 0),
            selected = "shown",
        )

        val sleeping = TabSleep.tabsForMemory(state, emptyMap(), keep = setOf("shown"))

        assertEquals(listOf("b", "a"), sleeping)
    }

    @Test
    fun `one tab beyond the limit puts the tab used longest ago to sleep`() {
        val state = state(
            awake("c", lastAccess = 3),
            awake("a", lastAccess = 1),
            awake("b", lastAccess = 2),
            awake("shown", lastAccess = 0),
            selected = "shown",
        )

        val sleeping = TabSleep.tabsOverLimit(state, emptyMap(), keep = setOf("shown"), limit = 3)

        assertEquals(listOf("a"), sleeping)
    }

    @Test
    fun `tabs that must stay awake count toward the limit while the next ones sleep`() {
        val playing = awake("playing", lastAccess = 1).copy(
            mediaSessionState = MediaSessionState(
                controller = mockk(relaxed = true),
                playbackState = MediaSession.PlaybackState.PLAYING,
            ),
        )
        val state = state(
            playing,
            awake("b", lastAccess = 2),
            awake("c", lastAccess = 3),
            awake("shown"),
            selected = "shown",
        )

        val sleeping = TabSleep.tabsOverLimit(state, emptyMap(), keep = setOf("shown"), limit = 2)

        assertEquals(listOf("b", "c"), sleeping)
    }

    @Test
    fun `sleeping tabs do not count toward the limit`() {
        val asleep = createTab(url = "https://asleep.example/", id = "asleep", createdAt = 0L)
        val state = state(asleep, awake("a", lastAccess = 1), awake("shown"), selected = "shown")

        val sleeping = TabSleep.tabsOverLimit(state, emptyMap(), keep = setOf("shown"), limit = 2)

        assertEquals(emptyList<String>(), sleeping)
    }

    @Test
    fun `a tab left a moment ago counts as recently used for the limit`() {
        val state = state(
            awake("read", lastAccess = 1),
            awake("other", lastAccess = 2),
            awake("shown"),
            selected = "shown",
        )

        val sleeping = TabSleep.tabsOverLimit(state, mapOf("read" to 50L), keep = setOf("shown"), limit = 2)

        assertEquals(listOf("other"), sleeping)
    }

    @Test
    fun `the default limit is one of the choices in the settings`() {
        val limits = DejavuTabSleepFragment.AWAKE_LIMITS.map { it.first }

        assertTrue(DejavuSettings.DEFAULT_AWAKE_TAB_LIMIT in limits)
        assertTrue(0 in limits)
    }
}
