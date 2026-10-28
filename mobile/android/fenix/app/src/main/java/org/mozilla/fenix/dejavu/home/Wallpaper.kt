/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.DejavuPopup
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpaper
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpapers

private val PREVIEW_WIDTH = 64.dp
private val COLOR_DOT = 14.dp
private const val CACHE_SHARE = 8

/** How much of their color the picture's colors keep in its veil, which takes the lightness of the background. */
private const val VEIL_CHROMA = 0.6f

/** The veil keeps the text over the picture at least this readable, as a contrast ratio, as far as it can. */
private const val READABLE_CONTRAST = 5.5f

/** How much of the picture the veil covers when its shades are not known yet. */
private const val DEFAULT_VEIL = 0.6f

/** How far a wallpaper's intensity takes its veil from the readable one, from one end of the slider to the other. */
private const val INTENSITY_REACH = 0.6f
private const val MIN_VEIL = 0.15f
private const val MAX_VEIL = 0.92f
private const val VEIL_STEP = 0.01f
private const val VEIL_STOPS = 3
private const val CONTRAST_OFFSET = 0.05f

/** Where the pages are while swiping: the index of the page on the left, plus how far the next one has come in. */
internal val PagerState.position: Float
    get() = currentPage + currentPageOffsetFraction

/**
 * How much the home's cover hides what lies under it at [position] among the pages, given the cover of each page by
 * [coverOf]: the veil of a page's wallpaper, see [Veil], or all of it on a page without one, whose theme then shows as
 * it is. In between while swiping.
 */
internal fun coverAt(position: Float, coverOf: (Int) -> Float): Float {
    val page = floor(position).toInt()
    val from = coverOf(page)
    val to = coverOf(page + 1)
    return from + (to - from) * (position - page)
}

/**
 * The veil over a wallpaper on the home screen, in place of a blur, which tires the eyes: a gradient of the picture's
 * own [colors] at the lightness of the background, covering [alpha] of the picture.
 */
@Immutable
internal class Veil(val colors: List<Color>, val alpha: Float)

/**
 * The veil of [wallpaper] over a background of [surface], with text of [text] on it. It covers just enough of the
 * picture for the text to read well over its lightest parts, or its darkest ones under dark text; a wallpaper's
 * intensity shows the picture more or less than that.
 */
internal fun veilOf(wallpaper: WorkspaceWallpaper, surface: Color, text: Color): Veil {
    val colors = wallpaper.colors.map { tinted(Color(it), surface) }.ifEmpty { listOf(surface) }
    val veil = colors[colors.size / 2]
    val shade = if (text.luminance() > veil.luminance()) wallpaper.light else wallpaper.dark
    val readable = shade?.let { readableVeil(it, veil.luminance(), text.luminance()) } ?: DEFAULT_VEIL
    val alpha = readable + (WorkspaceWallpaper.DEFAULT_INTENSITY - wallpaper.intensity) * INTENSITY_REACH
    return Veil(colors, alpha.coerceIn(MIN_VEIL, MAX_VEIL))
}

/** [color]'s hue at [surface]'s lightness, keeping [VEIL_CHROMA] of its color. */
private fun tinted(color: Color, surface: Color): Color {
    val lab = DoubleArray(LAB_CHANNELS).also { ColorUtils.colorToLAB(color.toArgb(), it) }
    val base = DoubleArray(LAB_CHANNELS).also { ColorUtils.colorToLAB(surface.toArgb(), it) }
    return Color(ColorUtils.LABToColor(base[0], lab[1] * VEIL_CHROMA, lab[2] * VEIL_CHROMA))
}

private const val LAB_CHANNELS = 3

/**
 * The least of a veil of [veil] luminance that covers a part of a picture of [shade] luminance for text of [text]
 * luminance to read on it, blending the way Android draws, in sRGB.
 */
internal fun readableVeil(shade: Float, veil: Float, text: Float): Float {
    val under = toSrgb(shade)
    val over = toSrgb(veil)
    var alpha = 0f
    while (alpha < 1f) {
        val shown = toLinear(under + (over - under) * alpha)
        val ratio = (max(shown, text) + CONTRAST_OFFSET) / (min(shown, text) + CONTRAST_OFFSET)
        if (ratio >= READABLE_CONTRAST) return alpha
        alpha += VEIL_STEP
    }
    return 1f
}

private fun toSrgb(linear: Float): Float =
    if (linear <= LINEAR_KNEE) linear * SRGB_SLOPE else (1 + SRGB_OFFSET) * linear.pow(1 / SRGB_GAMMA) - SRGB_OFFSET

private fun toLinear(srgb: Float): Float =
    if (srgb <= SRGB_KNEE) srgb / SRGB_SLOPE else ((srgb + SRGB_OFFSET) / (1 + SRGB_OFFSET)).pow(SRGB_GAMMA)

private const val LINEAR_KNEE = 0.0031308f
private const val SRGB_KNEE = 0.04045f
private const val SRGB_SLOPE = 12.92f
private const val SRGB_OFFSET = 0.055f
private const val SRGB_GAMMA = 2.4f

/**
 * Draws the veil of a page's wallpaper of [colors], [fraction] of the way to the veil of [next] while swiping, from the
 * top start corner to the bottom end corner, at [alpha].
 */
internal fun DrawScope.drawVeil(colors: List<Color>, next: List<Color>, fraction: Float, alpha: Float = 1f) {
    fun List<Color>.at(stop: Int) = this[(stop * size / VEIL_STOPS).coerceAtMost(size - 1)]
    val stops = (0 until VEIL_STOPS).map { lerp(colors.at(it), next.at(it), fraction) }
    drawRect(Brush.linearGradient(stops, start = Offset.Zero, end = Offset(size.width, size.height)), alpha = alpha)
}

/**
 * The wallpapers behind the workspace pages, for [wallpaperAt] each page. While swiping, the wallpaper of the page
 * going away fades out over the one of the page coming in.
 */
@Composable
internal fun WallpaperLayers(pagerState: PagerState, wallpaperAt: (Int) -> WorkspaceWallpaper?) {
    val page by remember(pagerState) { derivedStateOf { floor(pagerState.position).toInt() } }
    val current = wallpaperAt(page)
    val incoming = { pagerState.position - page }
    wallpaperAt(page + 1)?.let { next ->
        key(next.file) {
            WallpaperImage(next, Modifier.fillMaxSize(), alpha = { if (current != null) 1f else incoming() })
        }
    }
    current?.let {
        key(it.file) { WallpaperImage(it, Modifier.fillMaxSize(), alpha = { 1f - incoming() }) }
    }
}

/**
 * The workspace dialog's wallpaper choice: a small picture of the home screen with [wallpaper], a button to pick a
 * picture, the colors taken from it for the theme, and a slider for how strongly it shows through its veil. [onImport]
 * copies a picked picture into a wallpaper, which then goes to [onPicked]. The theme of a workspace that [syncsWithZen]
 * goes to Zen.
 */
@Composable
internal fun WallpaperSection(
    wallpaper: WorkspaceWallpaper?,
    syncsWithZen: Boolean,
    onImport: suspend (Uri) -> WorkspaceWallpaper?,
    onPicked: (WorkspaceWallpaper) -> Unit,
    onIntensityChange: (Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val latestOnPicked by rememberUpdatedState(onPicked)
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        failed = false
        scope.launch {
            val picked = onImport(uri)
            importing = false
            failed = picked == null
            picked?.let(latestOnPicked)
        }
    }
    val pick = {
        try {
            picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) {
            failed = true
        }
    }

    Text(
        text = stringResource(
            if (syncsWithZen) {
                R.string.dejavu_workspace_wallpaper_hint
            } else {
                R.string.dejavu_workspace_wallpaper_hint_local
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.size(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        WallpaperPreview(wallpaper = wallpaper, importing = importing, onClick = pick)
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DejavuDialogButton(
                text = stringResource(
                    if (wallpaper == null) {
                        R.string.dejavu_workspace_wallpaper_choose
                    } else {
                        R.string.dejavu_workspace_wallpaper_change
                    },
                ),
                onClick = pick,
                enabled = !importing,
            )
            if (wallpaper != null && wallpaper.colors.isNotEmpty()) PictureColors(wallpaper.colors, syncsWithZen)
            if (failed) {
                Text(
                    text = stringResource(R.string.dejavu_workspace_wallpaper_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    wallpaper?.let { current ->
        SliderRow(R.string.dejavu_workspace_theme_intensity, current.intensity, 0f..1f, onIntensityChange)
    }
}

/** The colors taken from a wallpaper, which the workspace's theme syncs to Zen when it [syncsWithZen]. */
@Composable
private fun PictureColors(colors: List<Int>, syncsWithZen: Boolean) {
    val description = stringResource(
        if (syncsWithZen) {
            R.string.dejavu_workspace_wallpaper_colors
        } else {
            R.string.dejavu_workspace_wallpaper_colors_local
        },
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .padding(start = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        colors.forEach { color ->
            Box(
                modifier = Modifier
                    .size(COLOR_DOT)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
    }
}

/** A small picture of the home screen with [wallpaper]. Tapping it picks another picture. */
@Composable
private fun WallpaperPreview(wallpaper: WorkspaceWallpaper?, importing: Boolean, onClick: () -> Unit) {
    val configuration = LocalConfiguration.current
    val surface = MaterialTheme.colorScheme.surface
    val text = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(12.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(PREVIEW_WIDTH)
            .aspectRatio(configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.coerceAtLeast(1))
            .clip(shape)
            .background(surface)
            .border(1.dp, DejavuPopup.edgeColor, shape)
            .clickable(enabled = !importing, onClick = onClick),
    ) {
        wallpaper?.let {
            WallpaperImage(wallpaper = it, modifier = Modifier.fillMaxSize())
            val veil = remember(it, surface, text) { veilOf(it, surface, text) }
            Spacer(modifier = Modifier.fillMaxSize().drawBehind { drawVeil(veil.colors, veil.colors, 0f, veil.alpha) })
        }
        if (importing) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else if (wallpaper == null) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_wallpaper_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * [wallpaper]'s picture, sharp and cropped to fill its place. [alpha] is read while drawing, so changing it redraws
 * nothing else.
 */
@Composable
internal fun WallpaperImage(
    wallpaper: WorkspaceWallpaper,
    modifier: Modifier = Modifier,
    alpha: () -> Float = { 1f },
) {
    val image = rememberWallpaperImage(wallpaper.file) ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.graphicsLayer {
            this.alpha = alpha().coerceIn(0f, 1f)
            clip = true
        },
    )
}

/** The picture [file], read in the background. */
@Composable
private fun rememberWallpaperImage(file: String): ImageBitmap? {
    val context = LocalContext.current
    var image by remember(file) { mutableStateOf(WallpaperPictures.cached(file)) }
    LaunchedEffect(file) {
        image = WallpaperPictures.cached(file) ?: WallpaperPictures.read(context, file) ?: image
    }
    return image
}

/** The wallpaper pictures read so far, kept while they fit in an eighth of the memory the app may use. */
private object WallpaperPictures {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / CACHE_SHARE).toInt()
    private val cache = object : LruCache<String, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().allocationByteCount
    }

    fun cached(file: String): ImageBitmap? = cache.get(file)

    suspend fun read(context: Context, file: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val path = WorkspaceWallpapers.get(context).pictureFile(file).path
        val picture = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return@withContext null
        picture.asImageBitmap().also { cache.put(file, it) }
    }
}
