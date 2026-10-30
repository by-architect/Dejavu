/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.menu

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.engine.EngineView
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.dejavu.browser.SplitPanes
import org.mozilla.fenix.ext.asActivity

private const val FOLDER = "Dejavu"

/**
 * Saves the whole page of the shown tab to Pictures/Dejavu, then offers to open or share it. Every way it can fail
 * says so. A [private] tab is only captured when screenshots are allowed in private browsing, as upstream does.
 */
internal fun captureFullPageScreenshot(context: Context, components: Components, tabId: String, private: Boolean) {
    val appContext = context.applicationContext
    fun tell(message: String) = Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
    fun fail() = tell(appContext.getString(R.string.dejavu_screenshot_failed))
    if (private && !components.settings.shouldSecureModeBeOverridden) {
        return tell(appContext.getString(R.string.dejavu_screenshot_private))
    }
    val activity = context.asActivity() ?: return fail()
    val engineView = SplitPanes.active?.engineViewOf(tabId) ?: activity.window.decorView.findEngineView()
        ?: return fail()
    engineView.captureFullPage { captured ->
        if (captured == null) return@captureFullPage fail()
        components.applicationScope.launch(Dispatchers.Main) {
            val uri = withContext(Dispatchers.IO) {
                capped(captured)?.let { bitmap ->
                    try {
                        save(appContext, bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            when {
                uri == null -> tell(appContext.getString(R.string.dejavu_screenshot_save_failed))
                // The screen it was asked from is gone, for example after a rotation: still say where it went.
                activity.isFinishing || activity.isDestroyed ->
                    tell(appContext.getString(R.string.dejavu_screenshot_saved, "Pictures/$FOLDER"))
                else -> showSaved(activity, uri)
            }
        }
    }
}

/** Largest width or height of a saved screenshot: image viewers and GPU textures often refuse bigger ones. */
internal const val MAX_SCREENSHOT_DIMENSION = 16_384

/** Largest size of a saved screenshot in memory, at 4 bytes per pixel. */
internal const val MAX_SCREENSHOT_BYTES = 64L * 1024 * 1024

private const val BYTES_PER_PIXEL = 4

/**
 * The size a [width] by [height] screenshot is saved at: scaled down, keeping its proportions, until neither side is
 * longer than [MAX_SCREENSHOT_DIMENSION] and it takes at most [MAX_SCREENSHOT_BYTES]. Sides stay at least 1 pixel.
 */
internal fun cappedScreenshotSize(width: Int, height: Int): Pair<Int, Int> {
    val bytes = width.toLong() * height * BYTES_PER_PIXEL
    val scale = minOf(
        1.0,
        MAX_SCREENSHOT_DIMENSION.toDouble() / width,
        MAX_SCREENSHOT_DIMENSION.toDouble() / height,
        sqrt(MAX_SCREENSHOT_BYTES.toDouble() / bytes),
    )
    if (scale >= 1.0) return width to height
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

/**
 * [bitmap] scaled down to [cappedScreenshotSize], recycling the original, or null when there is no memory for it.
 * Slow on big pages, so it runs off the main thread.
 * ponytail: the engine still captures the whole page at full size first, since GeckoView offers no scale for
 * captureFullPage through EngineView; capping before capture needs a scaled capture API in the engine.
 */
private fun capped(bitmap: Bitmap): Bitmap? {
    val (width, height) = cappedScreenshotSize(bitmap.width, bitmap.height)
    if (width == bitmap.width && height == bitmap.height) return bitmap
    // Catches OutOfMemoryError too.
    val scaled = runCatching { Bitmap.createScaledBitmap(bitmap, width, height, true) }.getOrNull()
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}

private fun View.findEngineView(): EngineView? = when {
    this is EngineView && isShown -> this
    this is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findEngineView() }
    else -> null
}

private fun save(context: Context, bitmap: Bitmap): Uri? {
    val name = "Dejavu-${System.currentTimeMillis()}.png"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            // Hidden from other apps until written, so a gallery never lists an empty file.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
            dir.mkdirs()
            @Suppress("DEPRECATION")
            put(MediaStore.Images.Media.DATA, File(dir, name).absolutePath)
        }
    }
    val resolver = context.contentResolver
    // Before Android 10 this needs the storage permission, which the app only has once it was granted for downloads.
    val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
        ?: return null
    val saved = runCatching {
        val written = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ?: false
        if (written && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        written
    }.getOrDefault(false)
    if (!saved) runCatching { resolver.delete(uri, null, null) }
    return uri.takeIf { saved }
}

private fun showSaved(activity: Context, uri: Uri) {
    AlertDialog.Builder(activity)
        .setMessage(activity.getString(R.string.dejavu_screenshot_saved, "Pictures/$FOLDER"))
        .setPositiveButton(R.string.dejavu_screenshot_open) { _, _ ->
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { activity.startActivity(view) }
        }
        .setNeutralButton(R.string.dejavu_screenshot_share) { _, _ ->
            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { activity.startActivity(Intent.createChooser(send, null)) }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
