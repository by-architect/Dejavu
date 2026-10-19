/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.content.pm.ActivityInfo
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RotationAfterFullscreenTest {
    private val activity =
        Robolectric.buildActivity(FragmentActivity::class.java).setup().get().apply {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

    private fun browserScreen(state: Lifecycle.State): Fragment {
        val fragment = mockk<Fragment>()
        val lifecycle = LifecycleRegistry.createUnsafe(fragment).apply { currentState = state }
        every { fragment.lifecycle } returns lifecycle
        every { fragment.activity } returns activity
        return fragment
    }

    @Test
    fun `a page leaving fullscreen after the app went to the background turns the screen back`() {
        browserScreen(Lifecycle.State.CREATED).restoreDejavuRotationAfterFullscreen()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
    }

    @Test
    fun `while the browser screen is shown, its own features turn the screen back`() {
        browserScreen(Lifecycle.State.RESUMED).restoreDejavuRotationAfterFullscreen()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
    }
}
