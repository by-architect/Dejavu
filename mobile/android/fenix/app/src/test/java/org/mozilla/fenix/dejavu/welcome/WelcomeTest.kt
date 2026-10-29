/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.welcome

import android.content.Context
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.dejavu.workspaces.WallpaperArt
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpaper
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpapers
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WelcomeTest {
    private fun newWallpapers(): WorkspaceWallpapers =
        WorkspaceWallpapers::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(testContext)

    private fun newRepository(): WorkspaceRepository =
        WorkspaceRepository::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(testContext)

    @Test
    fun `the welcome pages are due on a fresh install until they were seen, and never after an update`() {
        assertTrue(Welcome.isDue(seen = false, updated = false))
        assertFalse(Welcome.isDue(seen = true, updated = false))
        assertFalse(Welcome.isDue(seen = false, updated = true))
    }

    @Test
    fun `a drawn wallpaper is told apart from a photo by the name of its picture`() {
        assertEquals(WallpaperArt.DUSK, WallpaperArt.of(WorkspaceWallpaper("art-dusk-1234.jpg")))
        assertNull(WallpaperArt.of(WorkspaceWallpaper("1234.jpg")))
        assertNull(WallpaperArt.of(null))
    }

    @Test
    fun `every drawn wallpaper is drawn at the size asked`() {
        WallpaperArt.entries.forEach { art ->
            val picture = art.draw(36, 80)

            assertEquals(36, picture.width)
            assertEquals(80, picture.height)
        }
    }

    @Test
    fun `a fresh workspace gets the gold wallpaper first, unless it has a wallpaper`() {
        val repository = newRepository()
        val wallpapers = newWallpapers()
        val workspace = repository.state.value.workspaces.single()

        wallpapers.giveFirstWallpaper(workspace.id, screenWidth = 36, screenHeight = 80)

        assertEquals(WallpaperArt.GOLD, WallpaperArt.of(waitForWallpaper(wallpapers, workspace.id)))
        assertEquals(WorkspaceTheme.Gold, repository.state.value.workspaces.single().theme)

        val mine = WorkspaceWallpaper("mine.jpg")
        wallpapers.set(workspace.id, mine)
        wallpapers.giveFirstWallpaper(workspace.id, screenWidth = 36, screenHeight = 80)

        assertEquals(mine, wallpapers.wallpapers.value[workspace.id])
    }

    @Test
    fun `the pages show a wallpaper given meanwhile, unless another one was picked there`() {
        val repository = newRepository()
        val wallpapers = newWallpapers()
        val workspace = repository.state.value.workspaces.first()
        val gold = WorkspaceWallpaper("art-gold-1.jpg")
        val look = WelcomeLook(repository, wallpapers, workspace.id, workspace.theme, wallpaper = null)

        look.follow(gold)
        look.save()

        assertEquals(gold, look.wallpaper)
        assertNull(wallpapers.wallpapers.value[workspace.id])

        val picked = WorkspaceWallpaper("art-sky-2.jpg")
        look.showWallpaper(picked)
        look.follow(WorkspaceWallpaper("art-gold-3.jpg"))

        assertEquals(picked, look.wallpaper)
    }

    private fun waitForWallpaper(wallpapers: WorkspaceWallpapers, workspaceId: String): WorkspaceWallpaper? {
        repeat(WAIT_STEPS) {
            wallpapers.wallpapers.value[workspaceId]?.let { return it }
            Thread.sleep(WAIT_STEP_MS)
        }
        return wallpapers.wallpapers.value[workspaceId]
    }

    @Test
    fun `a wallpaper gives the workspace its colors, colors take its place, and both are kept only once saved`() {
        val repository = newRepository()
        val wallpapers = newWallpapers()
        val workspace = repository.state.value.workspaces.first()
        val look = WelcomeLook(repository, wallpapers, workspace.id, workspace.theme, wallpaper = null)
        val colors = listOf(0xFF112233.toInt(), 0xFF445566.toInt())

        look.showWallpaper(WorkspaceWallpaper("art-dusk-1.jpg", colors = colors))

        assertEquals(colors, look.theme?.colors)
        assertNull(wallpapers.wallpapers.value[workspace.id])

        look.save()

        assertEquals("art-dusk-1.jpg", wallpapers.wallpapers.value[workspace.id]?.file)
        assertEquals(colors, repository.state.value.workspaces.first { it.id == workspace.id }.theme?.colors)

        look.showTheme(WorkspaceTheme.Gold)
        look.save()

        assertNull(look.wallpaper)
        assertNull(wallpapers.wallpapers.value[workspace.id])
        assertEquals(WorkspaceTheme.Gold, repository.state.value.workspaces.first { it.id == workspace.id }.theme)
    }

    private companion object {
        const val WAIT_STEPS = 100
        const val WAIT_STEP_MS = 50L
    }
}
