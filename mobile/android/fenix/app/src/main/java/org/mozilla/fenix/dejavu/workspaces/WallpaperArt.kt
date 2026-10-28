/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.workspaces

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import org.mozilla.fenix.dejavu.home.grainBitmap

/**
 * A soft glow of a wallpaper Dejavu draws itself.
 *
 * @property color The glow's color.
 * @property x Where its center is, as a share of the picture's width.
 * @property y Where its center is, as a share of the picture's height.
 * @property reach How far it reaches, as a share of the picture's height.
 * @property strength How much of its color shows at its center.
 */
class Glow(val color: Int, val x: Float, val y: Float, val reach: Float, val strength: Float)

/**
 * Wallpapers Dejavu draws itself: soft glows of a few colors over a deep or a pale ground, with a little grain so the
 * gradients show no bands. They are drawn at the size they show at, so they take no room in the app. Over a deep
 * ground the glows add up like light; over a pale one they lie over each other like paint.
 *
 * @property key Names the pictures drawn of it, so the one a workspace shows can be told apart.
 * @property ground The color under the glows.
 */
enum class WallpaperArt(val key: String, val ground: Int, val glows: List<Glow>) {
    GOLD(
        "gold",
        0xFF0F0A06.toInt(),
        listOf(
            Glow(0xFFF2B84B.toInt(), 0.08f, 0.08f, 0.5f, 0.95f),
            Glow(0xFFFFD67A.toInt(), 1f, 0.4f, 0.3f, 0.6f),
            Glow(0xFFB4521C.toInt(), 0.3f, 0.98f, 0.55f, 0.9f),
            Glow(0xFF6B3410.toInt(), 0.95f, 0.9f, 0.4f, 0.7f),
        ),
    ),
    AURORA(
        "aurora",
        0xFF030A14.toInt(),
        listOf(
            Glow(0xFF11998E.toInt(), 0f, 0.88f, 0.55f, 0.95f),
            Glow(0xFF38EF7D.toInt(), 0.9f, 0.58f, 0.32f, 0.5f),
            Glow(0xFF2193B0.toInt(), 0.55f, 0.02f, 0.48f, 0.85f),
            Glow(0xFF6A3093.toInt(), 1f, 0.18f, 0.3f, 0.6f),
        ),
    ),
    DUSK(
        "dusk",
        0xFF10071A.toInt(),
        listOf(
            Glow(0xFFF857A6.toInt(), 1f, 0.88f, 0.5f, 0.85f),
            Glow(0xFFFF7E5F.toInt(), 0.05f, 1f, 0.45f, 0.9f),
            Glow(0xFF6A3093.toInt(), 0.1f, 0.06f, 0.55f, 0.9f),
            Glow(0xFFFEB47B.toInt(), 0.75f, 0.62f, 0.22f, 0.3f),
        ),
    ),
    SAND(
        "sand",
        0xFFFBF6EE.toInt(),
        listOf(
            Glow(0xFFFFD67A.toInt(), 0.9f, 0.1f, 0.6f, 0.75f),
            Glow(0xFFFFB88C.toInt(), 0.05f, 0.85f, 0.65f, 0.7f),
            Glow(0xFFF6E2B3.toInt(), 0.4f, 0.45f, 0.5f, 0.6f),
            Glow(0xFFDE6262.toInt(), 0.95f, 0.95f, 0.35f, 0.3f),
        ),
    ),
    MIST(
        "mist",
        0xFFF3EFF8.toInt(),
        listOf(
            Glow(0xFFA18CD1.toInt(), 0.05f, 0.12f, 0.65f, 0.7f),
            Glow(0xFFFBC2EB.toInt(), 0.95f, 0.8f, 0.65f, 0.75f),
            Glow(0xFFC9E4FF.toInt(), 0.15f, 0.7f, 0.45f, 0.65f),
        ),
    ),
    SKY(
        "sky",
        0xFFEEF5FB.toInt(),
        listOf(
            Glow(0xFF89F7FE.toInt(), 0.85f, 0.1f, 0.6f, 0.7f),
            Glow(0xFF66A6FF.toInt(), 0.95f, 0.9f, 0.65f, 0.55f),
            Glow(0xFFA8E6CF.toInt(), 0.05f, 0.7f, 0.55f, 0.6f),
        ),
    ),
    ;

    /** Whether its ground is deep, so its glows add up like light. */
    val isDeep: Boolean
        get() = ColorUtils.calculateLuminance(ground) < HALF

    /** Draws it on a new picture of [width] by [height] pixels. */
    fun draw(width: Int, height: Int): Bitmap {
        val picture = createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1))
        val canvas = Canvas(picture)
        canvas.drawColor(ground)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        if (isDeep) paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        glows.forEach { glow ->
            val colors = FALLOFF.map { ColorUtils.setAlphaComponent(glow.color, (it * glow.strength * OPAQUE).toInt()) }
            paint.shader = RadialGradient(
                glow.x * picture.width,
                glow.y * picture.height,
                (glow.reach * picture.height).coerceAtLeast(1f),
                colors.toIntArray(),
                STOPS,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, picture.width.toFloat(), picture.height.toFloat(), paint)
        }
        val grain = Paint().apply {
            shader = BitmapShader(grainBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            alpha = GRAIN_ALPHA
        }
        canvas.drawRect(0f, 0f, picture.width.toFloat(), picture.height.toFloat(), grain)
        return picture
    }

    companion object {
        /** How the names of the pictures drawn of one start, before its [key]. */
        const val FILE_PREFIX = "art-"

        /** The one [wallpaper] was drawn of, or `null` for a picture of the user's own. */
        fun of(wallpaper: WorkspaceWallpaper?): WallpaperArt? =
            wallpaper?.let { shown -> entries.firstOrNull { shown.file.startsWith("$FILE_PREFIX${it.key}-") } }
    }
}

private const val HALF = 0.5
private const val OPAQUE = 255
private const val GRAIN_ALPHA = 15

// How a glow fades from its center to its edge: how much of its strength shows at each stop.
private val STOPS = floatArrayOf(0f, 0.35f, 0.7f, 1f)
private val FALLOFF = listOf(1f, 0.62f, 0.2f, 0f)
