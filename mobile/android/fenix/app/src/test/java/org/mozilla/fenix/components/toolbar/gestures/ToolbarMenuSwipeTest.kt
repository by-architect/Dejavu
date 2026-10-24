/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.components.toolbar.gestures

import android.graphics.PointF
import android.view.View
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.R
import org.mozilla.fenix.components.AppStore
import org.mozilla.fenix.components.toolbar.ToolbarPosition

private const val SCREEN_HEIGHT = 2050
private const val SCREEN_WIDTH = 1080
private const val BAR_HEIGHT = 100

@RunWith(AndroidJUnit4::class)
class ToolbarMenuSwipeTest {
    private val toolbarLayout = spyk(View(testContext))
    private val navBarLayout = spyk(View(testContext))
    private val navController: NavController = mockk(relaxed = true)
    private val menuSwipe = RecordedMenuSwipe()
    private val downSwipe = RecordedMenuSwipe()
    private var swipedFromNavBar: Boolean? = null

    @Before
    fun setup() {
        place(toolbarLayout, top = 0)
        place(navBarLayout, top = SCREEN_HEIGHT - BAR_HEIGHT)
        val browser: NavDestination = mockk()
        every { browser.id } returns R.id.browserFragment
        every { navController.currentDestination } returns browser
    }

    @Test
    fun `a swipe up on the bar is followed from when it is long enough until the finger lets go`() {
        val handler = handler()
        val start = PointF(SCREEN_WIDTH / 2f, SCREEN_HEIGHT - BAR_HEIGHT / 2f)

        assertTrue(handler.onSwipeStarted(start, PointF(start.x, start.y - 60f)))
        handler.onSwipeUpdate(distanceX = 0f, distanceY = 40f)
        handler.onSwipeUpdate(distanceX = 0f, distanceY = -30f)
        handler.onSwipeFinished(velocityX = 0f, velocityY = -2000f)

        assertEquals(1, menuSwipe.starts)
        assertEquals(listOf(60f, 100f, 70f), menuSwipe.moves)
        assertEquals(2000f, menuSwipe.endVelocity)
        assertNull(swipedFromNavBar)
    }

    @Test
    fun `a swipe up the menu does not follow opens it the usual way`() {
        menuSwipe.follows = false
        val handler = handler()
        val start = PointF(SCREEN_WIDTH / 2f, SCREEN_HEIGHT - BAR_HEIGHT / 2f)

        assertFalse(handler.onSwipeStarted(start, PointF(start.x, start.y - 60f)))
        handler.onSwipeFinished(velocityX = 0f, velocityY = -2000f)

        assertEquals(true, swipedFromNavBar)
        assertTrue(menuSwipe.moves.isEmpty())
        assertNull(menuSwipe.endVelocity)
    }

    @Test
    fun `a swipe down on the address bar at the top is followed where it opens the menu`() {
        val handler = handler()
        val start = PointF(SCREEN_WIDTH / 2f, BAR_HEIGHT / 2f)

        assertTrue(handler.onSwipeStarted(start, PointF(start.x, start.y + 60f)))
        handler.onSwipeUpdate(distanceX = 0f, distanceY = -40f)
        handler.onSwipeUpdate(distanceX = 0f, distanceY = 30f)
        handler.onSwipeFinished(velocityX = 0f, velocityY = 1500f)

        assertEquals(1, downSwipe.starts)
        assertEquals(listOf(60f, 100f, 70f), downSwipe.moves)
        assertEquals(1500f, downSwipe.endVelocity)
        assertEquals(0, menuSwipe.starts)
        assertNull(swipedFromNavBar)
    }

    @Test
    fun `a swipe down on the address bar at the top is not followed where it does not open the menu`() {
        downSwipe.follows = false
        val handler = handler()
        val start = PointF(SCREEN_WIDTH / 2f, BAR_HEIGHT / 2f)

        assertFalse(handler.onSwipeStarted(start, PointF(start.x, start.y + 60f)))

        assertEquals(false, swipedFromNavBar)
        assertTrue(downSwipe.moves.isEmpty())
        assertEquals(0, menuSwipe.starts)
    }

    @Test
    fun `a swipe up on the address bar at the top is not followed`() {
        val handler = handler()
        val start = PointF(SCREEN_WIDTH / 2f, BAR_HEIGHT / 2f)

        assertFalse(handler.onSwipeStarted(start, PointF(start.x, start.y - 60f)))

        assertNull(swipedFromNavBar)
        assertEquals(0, menuSwipe.starts)
        assertEquals(0, downSwipe.starts)
    }

    private fun handler() = ToolbarVerticalGesturesHandler(
        appStore = AppStore(),
        toolbarLayout = toolbarLayout,
        navBarLayout = navBarLayout,
        toolbarPosition = ToolbarPosition.TOP,
        navController = navController,
        onSwipe = { fromNavBar -> swipedFromNavBar = fromNavBar },
        menuSwipe = menuSwipe,
        downSwipe = downSwipe,
    )

    private fun place(view: View, top: Int) {
        every { view.height } returns BAR_HEIGHT
        every { view.width } returns SCREEN_WIDTH
        every { view.getLocationInWindow(any()) } answers {
            val location = arg<IntArray>(0)
            location[0] = 0
            location[1] = top
        }
    }

    private class RecordedMenuSwipe : ToolbarVerticalGesturesHandler.MenuSwipe {
        var follows = true
        var starts = 0
        val moves = mutableListOf<Float>()
        var endVelocity: Float? = null

        override fun onStart(): Boolean {
            starts++
            return follows
        }

        override fun onMove(distance: Float) {
            moves += distance
        }

        override fun onEnd(velocity: Float) {
            endVelocity = velocity
        }
    }
}
