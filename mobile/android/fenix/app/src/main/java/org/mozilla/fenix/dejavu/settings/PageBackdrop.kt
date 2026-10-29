/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.Window
import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.findFragment
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.R as materialR
import com.google.android.material.color.MaterialColors
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.mozilla.fenix.R
import org.mozilla.fenix.StartupFragment
import org.mozilla.fenix.addons.AddonInternalSettingsFragment
import org.mozilla.fenix.addons.WebExtensionActionPopupFragment
import org.mozilla.fenix.browser.BaseBrowserFragment
import org.mozilla.fenix.dejavu.browser.PagePictures
import org.mozilla.fenix.dejavu.browser.SEARCH_BLUR_RADIUS
import org.mozilla.fenix.dejavu.browser.SEARCH_SCRIM_ALPHA
import org.mozilla.fenix.dejavu.home.pictureWindow
import org.mozilla.fenix.dejavu.welcome.DejavuWelcomeFragment
import org.mozilla.fenix.home.HomeFragment
import org.mozilla.fenix.onboarding.OnboardingFragment
import org.mozilla.fenix.pbmlock.UnlockPrivateTabsFragment
import org.mozilla.fenix.tabstray.ui.TabManagementFragment

// A screen pictured for a page is what it opens over for this long.
private const val FRESH_MS = 3_000L

// The backdrop's picture is the window halved this many times, which blurs it cheaply.
private const val HALVINGS = 3

// The spread of the Gaussian Android blurs with for a radius, see RenderEffect.createBlurEffect.
private const val SIGMA_PER_RADIUS = 0.57735f
private const val SIGMA_OFFSET = 0.5f

// Three box blurs in a row blur about as a Gaussian does.
private const val BOX_PASSES = 3
private const val BOX_SPREAD_DIVISOR = 3f
private const val MAX_ALPHA = 255
private const val CHANNEL = 0xFF
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

/**
 * What the full-screen pages, like the settings, the bookmarks, the history or the account, show behind them: the
 * screen they opened over, blurred and veiled the way a search screen shows what lies behind it. That is the home
 * screen, pictured as a page pours out of its button, or the web page it was opened over. The settings toolbar, the
 * status bar, the navigation bar and the backgrounds of the pages' own screens let it show through, see [showsThrough].
 */
internal object PageBackdrop {
    // Draws the screen the page opening next opens over, on a canvas as big as the window, and when it was set.
    private var next: ((Canvas) -> Unit)? = null
    private var nextAt = 0L

    /** What the page shown now or last opened over, blurred; `null` when it could not be pictured. */
    private var picture: Bitmap? = null

    // The window showing the backdrop and the picture it shows, and whether a page shows.
    private var shownIn: WeakReference<Window>? = null
    private var shownPicture: Bitmap? = null
    private var wanted = false

    // The full-screen screens that are no pages: the home screen, web pages and screens with backgrounds of their own.
    private val notPages = setOf(
        HomeFragment::class.java,
        BaseBrowserFragment::class.java,
        StartupFragment::class.java,
        OnboardingFragment::class.java,
        TabManagementFragment::class.java,
        DejavuWelcomeFragment::class.java,
        WebExtensionActionPopupFragment::class.java,
        AddonInternalSettingsFragment::class.java,
        UnlockPrivateTabsFragment::class.java,
    )

    /** The page opening next opens over [screen], a picture of the whole window. */
    fun opensOver(screen: Bitmap) = opensOver { it.drawBitmap(screen, 0f, 0f, null) }

    /**
     * Pictures the page of the tab with [tabId] as it is shown now, then opens a full-screen page over it with [open],
     * at once when the page cannot be pictured.
     */
    fun openOverPage(tabId: String, open: () -> Unit) {
        if (PagePictures.isCapturing) {
            open()
            return
        }
        PagePictures.capture(tabId) { shot ->
            if (shot != null) {
                val page = shot.bitmap.asAndroidBitmap()
                opensOver { canvas ->
                    canvas.drawColor(shot.edgeColor.toArgb())
                    canvas.drawBitmap(page, shot.offset.x, shot.offset.y, null)
                }
            }
            open()
        }
    }

    private fun opensOver(draw: (Canvas) -> Unit) {
        next = draw
        nextAt = SystemClock.elapsedRealtime()
    }

    /**
     * Shows the backdrop behind everything in the window of [activity] as soon as there is a picture for it, then
     * calls [shown]. A screen just pictured for it takes the place of the one pictured before. A page opening over the
     * home screen ([overHome]) without a picture pictures it now, while it still shows.
     */
    fun show(activity: Activity, overHome: Boolean, shown: () -> Unit) {
        wanted = true
        val window = activity.window
        val draw = next?.takeIf { SystemClock.elapsedRealtime() - nextAt < FRESH_MS }
        next = null
        if (draw != null) {
            picture = blurred(window, draw)
        } else if (picture == null && overHome) {
            pictureWindow(activity, window.decorView) { screen ->
                if (screen == null || !wanted || picture != null) return@pictureWindow
                picture = blurred(window) { it.drawBitmap(screen, 0f, 0f, null) }
                if (apply(window)) shown()
            }
            return
        }
        if (apply(window)) shown()
    }

    /** Takes the backdrop away from [window], and forgets its picture unless [keep], for pages shown again. */
    fun hide(window: Window, keep: Boolean) {
        wanted = false
        if (!keep) picture = null
        if (SystemClock.elapsedRealtime() - nextAt >= FRESH_MS) next = null
        if (shownIn?.get() !== window) return
        shownIn = null
        shownPicture = null
        val background = TypedValue()
        if (window.context.theme.resolveAttribute(android.R.attr.windowBackground, background, true)) {
            window.setBackgroundDrawableResource(background.resourceId)
        }
    }

    /** Whether [window] shows the backdrop. */
    fun isShown(window: Window): Boolean = shownIn?.get() === window

    /** Whether [fragment] is a full-screen page, which shows the backdrop. */
    fun isPage(fragment: Fragment): Boolean =
        fragment.parentFragment is NavHostFragment && fragment !is DialogFragment &&
            notPages.none { it.isInstance(fragment) }

    /**
     * Whether the Compose screen in [view] is part of a full-screen page, so its background lets the backdrop show
     * through.
     */
    fun showsThrough(view: View): Boolean = runCatching { isPage(view.findFragment()) }.getOrDefault(false)

    private fun apply(window: Window): Boolean {
        val picture = picture ?: return false
        if (shownIn?.get() === window && shownPicture === picture) return true
        val surface = MaterialColors.getColor(window.context, materialR.attr.colorSurface, 0)
        val veil = ColorUtils.setAlphaComponent(surface, (SEARCH_SCRIM_ALPHA * MAX_ALPHA).roundToInt())
        window.setBackgroundDrawable(BackdropDrawable(picture, veil))
        shownIn = WeakReference(window)
        shownPicture = picture
        return true
    }
}

/**
 * Shows the [PageBackdrop] behind the full-screen pages of an activity and takes it away from its other screens,
 * following the screens of its navigation host; registered with child fragments.
 */
internal class PageBackdropCallbacks : FragmentManager.FragmentLifecycleCallbacks() {
    // The settings toolbar while the backdrop shows through it, with its own background and elevation.
    private var toolbar: WeakReference<View>? = null
    private var toolbarBackground: Drawable? = null
    private var toolbarElevation = 0f

    override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
        if (!f.isScreen) return
        val activity = f.activity ?: return
        if (PageBackdrop.isPage(f)) {
            val overHome = f.findNavController().previousBackStackEntry?.destination?.id == R.id.homeFragment
            PageBackdrop.show(activity, overHome) { clearToolbar(activity) }
        } else {
            // Pages opened from the home screen picture it anew, but a page left for a web page may be shown again.
            PageBackdrop.hide(activity.window, keep = f !is HomeFragment)
            restoreToolbar()
        }
    }

    // A screen shows the settings toolbar once it is resumed.
    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
        val activity = f.activity ?: return
        if (f.isScreen && PageBackdrop.isShown(activity.window)) clearToolbar(activity)
    }

    // A screen may have painted the toolbar since it was cleared, as the history does when its content changes.
    private fun clearToolbar(activity: Activity) {
        val view = activity.findViewById<View>(R.id.navigationToolbar) ?: return
        if (toolbar?.get() !== view) {
            toolbar = WeakReference(view)
            toolbarBackground = view.background
            toolbarElevation = view.elevation
        }
        view.background = null
        view.elevation = 0f
    }

    private fun restoreToolbar() {
        val view = toolbar?.get() ?: return
        toolbar = null
        view.background = toolbarBackground
        view.elevation = toolbarElevation
        toolbarBackground = null
    }

    private val Fragment.isScreen: Boolean
        get() = parentFragment is NavHostFragment && this !is DialogFragment
}

/** Draws [picture] over all of its bounds, cut to keep its shape, under a [veil]. */
private class BackdropDrawable(private val picture: Bitmap, @ColorInt veil: Int) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val veilPaint = Paint().apply { color = veil }
    private val target = RectF()

    override fun draw(canvas: Canvas) {
        val scale = max(bounds.width().toFloat() / picture.width, bounds.height().toFloat() / picture.height)
        val width = picture.width * scale
        val height = picture.height * scale
        target.set(0f, 0f, width, height)
        target.offset(bounds.exactCenterX() - width / 2, bounds.exactCenterY() - height / 2)
        canvas.drawBitmap(picture, null, target, paint)
        canvas.drawRect(bounds, veilPaint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * A small picture of what [draw] draws on a canvas as big as [window], blurred as much as the search screens blur what
 * lies behind them, or `null` when it cannot be made.
 */
private fun blurred(window: Window, draw: (Canvas) -> Unit): Bitmap? = runCatching {
    val decor = window.decorView
    var small = Bitmap.createBitmap(
        (decor.width / 2).coerceAtLeast(1),
        (decor.height / 2).coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    Canvas(small).apply {
        drawFilter = PaintFlagsDrawFilter(0, Paint.FILTER_BITMAP_FLAG)
        scale(1f / 2, 1f / 2)
        draw(this)
    }
    repeat(HALVINGS - 1) {
        val half = Bitmap.createScaledBitmap(
            small,
            (small.width / 2).coerceAtLeast(1),
            (small.height / 2).coerceAtLeast(1),
            true,
        )
        small.recycle()
        small = half
    }
    val radiusPx = SEARCH_BLUR_RADIUS.value * decor.resources.displayMetrics.density
    val sigma = (SIGMA_PER_RADIUS * radiusPx + SIGMA_OFFSET) / (1 shl HALVINGS)
    val pixels = IntArray(small.width * small.height)
    small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
    pixels.boxBlur(small.width, small.height, boxRadiusFor(sigma))
    Bitmap.createBitmap(pixels, small.width, small.height, Bitmap.Config.ARGB_8888).also { small.recycle() }
}.getOrNull()

/** The radius of the boxes that blur as much as a Gaussian spread [sigma] pixels wide, in [BOX_PASSES] passes. */
internal fun boxRadiusFor(sigma: Float): Int {
    // A box reaching r pixels to each side spreads by r(r + 1) / 3, and the spreads of the passes add up.
    val product = BOX_SPREAD_DIVISOR * sigma * sigma / BOX_PASSES
    return ((sqrt(1f + 4f * product) - 1f) / 2f).roundToInt().coerceAtLeast(1)
}

/**
 * Blurs the [width] by [height] pixels of [this] picture with [BOX_PASSES] passes of boxes reaching [radius] pixels to
 * each side, across and down.
 */
internal fun IntArray.boxBlur(width: Int, height: Int, radius: Int) {
    val other = IntArray(size)
    repeat(BOX_PASSES) {
        blurLines(this, other, lines = height, length = width, lineStep = width, step = 1, radius = radius)
        blurLines(other, this, lines = width, length = height, lineStep = 1, step = width, radius = radius)
    }
}

/**
 * Blurs [lines] lines of [length] pixels of [from] into [into]: each pixel takes the average of the pixels up to
 * [radius] away along its line, its end pixels standing in for those beyond the ends. A line starts [lineStep] pixels
 * after the one before it, and its pixels are [step] apart.
 */
@Suppress("LongParameterList")
private fun blurLines(from: IntArray, into: IntArray, lines: Int, length: Int, lineStep: Int, step: Int, radius: Int) {
    val count = radius * 2 + 1
    for (line in 0 until lines) {
        val start = line * lineStep
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (i in -radius..radius) {
            val color = from[start + i.coerceIn(0, length - 1) * step]
            alpha += color ushr ALPHA_SHIFT
            red += color shr RED_SHIFT and CHANNEL
            green += color shr GREEN_SHIFT and CHANNEL
            blue += color and CHANNEL
        }
        for (i in 0 until length) {
            into[start + i * step] = (alpha / count shl ALPHA_SHIFT) or (red / count shl RED_SHIFT) or
                (green / count shl GREEN_SHIFT) or (blue / count)
            val gone = from[start + (i - radius).coerceIn(0, length - 1) * step]
            val come = from[start + (i + radius + 1).coerceIn(0, length - 1) * step]
            alpha += (come ushr ALPHA_SHIFT) - (gone ushr ALPHA_SHIFT)
            red += (come shr RED_SHIFT and CHANNEL) - (gone shr RED_SHIFT and CHANNEL)
            green += (come shr GREEN_SHIFT and CHANNEL) - (gone shr GREEN_SHIFT and CHANNEL)
            blue += (come and CHANNEL) - (gone and CHANNEL)
        }
    }
}
