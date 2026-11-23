/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.StrictMode
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import mozilla.components.browser.state.selector.selectedTab
import org.mozilla.fenix.browser.tabstrip.TabSidebarLayout
import org.mozilla.fenix.dejavu.home.drawWorkspaceTheme
import org.mozilla.fenix.dejavu.home.grainBitmap
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpapers
import org.mozilla.fenix.ext.components
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/**
 * Gives the popups made of views the colors that [DejavuDropdownMenu] takes: those of the workspace of the shown tab,
 * see [popupThemeOf]. Private tabs and workspaces without a wallpaper keep the plain fill.
 */
object DejavuViewPopups {
    /** The colors popups over the shown tab take on, or `null` for none. */
    fun currentTheme(context: Context): WorkspaceTheme? {
        val tab = context.components.core.store.state.selectedTab?.takeUnless { it.content.private } ?: return null
        val wallpapers = context.components.strictMode.allowViolation(StrictMode::allowThreadDiskReads) {
            WorkspaceWallpapers.get(context)
        }
        val state = WorkspaceRepository.get(context).state.value
        val workspaceId = state.workspaceOf(tab.id)
        return popupThemeOf(wallpapers.wallpapers.value[workspaceId])
            // Next to the docked sidebar, the workspace's gradient stands in for a missing wallpaper, as it does there.
            ?: state.workspaces.firstOrNull { it.id == workspaceId }?.theme
                ?.takeIf { TabSidebarLayout.occupiedWidth > 0.dp }
    }

    /** Paints [theme] over the plain fill of the shown [dialog]. */
    fun apply(dialog: AlertDialog, theme: WorkspaceTheme?) {
        if (theme == null) return
        val panel = dialog.window?.decorView?.findViewById<View>(androidx.appcompat.R.id.parentPanel) ?: return
        val radius = DIALOG_RADIUS_DP * panel.resources.displayMetrics.density
        panel.background = PopupTintDrawable(theme.popupTint(), radius)
    }

    /**
     * Themes the dialogs of [DialogFragment]s that [matches], like the context menu of Firefox's page, while
     * [owner] lives.
     */
    fun themeDialogs(manager: FragmentManager, owner: LifecycleOwner, matches: (Fragment) -> Boolean) {
        val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                if (!matches(f)) return
                val dialog = (f as? DialogFragment)?.dialog as? AlertDialog ?: return
                apply(dialog, currentTheme(f.requireContext()))
            }
        }
        manager.registerFragmentLifecycleCallbacks(callbacks, false)
        owner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    manager.unregisterFragmentLifecycleCallbacks(callbacks)
                }
            },
        )
    }

    private const val DIALOG_RADIUS_DP = 28
}

/** [theme] drawn over the rounded card of a dialog, the way [popupGlow] draws it behind the content of a menu. */
private class PopupTintDrawable(private val theme: WorkspaceTheme, private val radius: Float) : Drawable() {
    private val grain = ShaderBrush(ImageShader(grainBitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    private val scope = CanvasDrawScope()
    private val clip = Path()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        clip.rewind()
        val card = RectF(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat())
        clip.addRoundRect(card, radius, radius, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.clipPath(clip)
        scope.draw(
            Density(1f),
            LayoutDirection.Ltr,
            ComposeCanvas(canvas),
            Size(bounds.width().toFloat(), bounds.height().toFloat()),
        ) {
            drawWorkspaceTheme(theme, next = null, fraction = 0f, grain = grain)
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
