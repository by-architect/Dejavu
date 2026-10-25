/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.DejavuPopup
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpaper
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpapers

/** How blurred a wallpaper is on the home screen at the highest blur setting. */
private val MAX_BLUR = 64.dp
private val PREVIEW_WIDTH = 64.dp
private val COLOR_DOT = 14.dp

/** How much of the plain background covers a wallpaper, so that the text over it stays easy to read. */
internal const val WALLPAPER_COVER = 0.4f

/** Older Android versions cannot blur while drawing, so they show a smaller copy of the picture, in this many steps. */
private const val SOFT_LEVELS = 8
private const val SOFT_SHRINK_PER_LEVEL = 3
private const val CACHE_SHARE = 8

private val canBlurLive: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Where the pages are while swiping: the index of the page on the left, plus how far the next one has come in. */
internal val PagerState.position: Float
    get() = currentPage + currentPageOffsetFraction

/**
 * How much the home's plain background covers what lies under it at [position] among the pages: [WALLPAPER_COVER]
 * over a page's wallpaper, fully on a page without one, whose theme then shows as it is, and in between while swiping.
 */
internal fun coverAt(position: Float, wallpaperAt: (Int) -> WorkspaceWallpaper?): Float {
    val page = floor(position).toInt()
    val from = if (wallpaperAt(page) != null) WALLPAPER_COVER else 1f
    val to = if (wallpaperAt(page + 1) != null) WALLPAPER_COVER else 1f
    return from + (to - from) * (position - page)
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
 * picture, the colors taken from it for the theme, and a slider for how blurred it is. [onImport] copies a picked
 * picture into a wallpaper, which then goes to [onPicked]. The theme of a workspace that [syncsWithZen] goes to Zen.
 */
@Composable
internal fun WallpaperSection(
    wallpaper: WorkspaceWallpaper?,
    syncsWithZen: Boolean,
    onImport: suspend (Uri) -> WorkspaceWallpaper?,
    onPicked: (WorkspaceWallpaper) -> Unit,
    onBlurChange: (Float) -> Unit,
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
        SliderRow(R.string.dejavu_workspace_wallpaper_blur, current.blur, 0f..1f, onBlurChange)
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
            WallpaperImage(
                wallpaper = it,
                modifier = Modifier.fillMaxSize(),
                blurScale = PREVIEW_WIDTH.value / configuration.screenWidthDp.coerceAtLeast(1),
            )
            Box(modifier = Modifier.fillMaxSize().background(surface.copy(alpha = WALLPAPER_COVER)))
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
 * [wallpaper]'s picture, cropped to fill its place and blurred as much as it asks for. [blurScale] shrinks the blur
 * for a small picture of the home screen. [alpha] is read while drawing, so changing it redraws nothing else.
 */
@Composable
private fun WallpaperImage(
    wallpaper: WorkspaceWallpaper,
    modifier: Modifier = Modifier,
    blurScale: Float = 1f,
    alpha: () -> Float = { 1f },
) {
    val softness = if (canBlurLive) 0 else (wallpaper.blur * SOFT_LEVELS).roundToInt()
    val image = rememberWallpaperImage(wallpaper.file, softness) ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.graphicsLayer {
            this.alpha = alpha().coerceIn(0f, 1f)
            val radius = MAX_BLUR.toPx() * wallpaper.blur * blurScale
            renderEffect = if (canBlurLive && radius > 0f) BlurEffect(radius, radius, TileMode.Clamp) else null
            clip = true
        },
    )
}

/**
 * The picture [file], read in the background, shrunk by [softness] steps where it cannot be blurred while drawing.
 * Until another softness is read, the one read before stays.
 */
@Composable
private fun rememberWallpaperImage(file: String, softness: Int): ImageBitmap? {
    val context = LocalContext.current
    var image by remember(file) { mutableStateOf(WallpaperPictures.cached(file, softness)) }
    LaunchedEffect(file, softness) {
        image = WallpaperPictures.cached(file, softness) ?: WallpaperPictures.read(context, file, softness) ?: image
    }
    return image
}

/** The wallpaper pictures read so far, kept while they fit in an eighth of the memory the app may use. */
private object WallpaperPictures {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / CACHE_SHARE).toInt()
    private val cache = object : LruCache<String, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().allocationByteCount
    }

    private fun key(file: String, softness: Int) = "$file/$softness"

    fun cached(file: String, softness: Int): ImageBitmap? = cache.get(key(file, softness))

    suspend fun read(context: Context, file: String, softness: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val path = WorkspaceWallpapers.get(context).pictureFile(file).path
        val shown = runCatching {
            val picture = BitmapFactory.decodeFile(path) ?: return@runCatching null
            if (softness > 0) picture.shrunk(1 + softness * SOFT_SHRINK_PER_LEVEL) else picture
        }.getOrNull() ?: return@withContext null
        shown.asImageBitmap().also { cache.put(key(file, softness), it) }
    }
}

/** A copy [factor] times smaller, shrunk by halves so that every pixel counts, which looks blurred when drawn big. */
private fun Bitmap.shrunk(factor: Int): Bitmap {
    val targetWidth = max(1, width / factor)
    val targetHeight = max(1, height / factor)
    var bitmap = this
    while (bitmap.width / 2 >= targetWidth && bitmap.height / 2 >= targetHeight) {
        val half = bitmap.scale(bitmap.width / 2, bitmap.height / 2)
        if (bitmap !== this) bitmap.recycle()
        bitmap = half
    }
    return if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
        bitmap
    } else {
        bitmap.scale(targetWidth, targetHeight).also { if (bitmap !== this) bitmap.recycle() }
    }
}
