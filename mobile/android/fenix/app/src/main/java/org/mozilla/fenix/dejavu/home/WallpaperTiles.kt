/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.ui.DejavuPopup
import org.mozilla.fenix.dejavu.workspaces.WallpaperArt
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpaper

private const val PHOTO = "photo"

/**
 * The wallpapers to pick from, in a row: the ones Dejavu draws and a photo of the user's own, after no wallpaper when
 * [noneTheme] is given, which shows the workspace's colors. [shown] is outlined. A drawn one is drawn with [drawArt]
 * and a photo copied with [importPicture] before it goes to [onPick], one at a time; no wallpaper goes as `null`.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun WallpaperTiles(
    shown: WorkspaceWallpaper?,
    onPick: (WorkspaceWallpaper?) -> Unit,
    drawArt: suspend (WallpaperArt) -> WorkspaceWallpaper?,
    importPicture: suspend (Uri) -> WorkspaceWallpaper?,
    modifier: Modifier = Modifier,
    tileWidth: Dp = 72.dp,
    contentPadding: PaddingValues = PaddingValues(),
    showsNone: Boolean = false,
    noneTheme: WorkspaceTheme? = null,
) {
    val scope = rememberCoroutineScope()
    val latestOnPick by rememberUpdatedState(onPick)
    var importing by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val art = WallpaperArt.of(shown)

    // Pictures are drawn or copied in the background; one at a time.
    fun pick(key: String, load: suspend () -> WorkspaceWallpaper?) {
        if (importing != null) return
        importing = key
        failed = false
        scope.launch {
            val picked = load()
            importing = null
            failed = picked == null
            picked?.let(latestOnPick)
        }
    }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) pick(PHOTO) { importPicture(uri) }
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        if (showsNone) {
            item {
                WallpaperTile(
                    selected = shown == null,
                    description = stringResource(R.string.dejavu_welcome_wallpaper_none),
                    width = tileWidth,
                    onClick = { onPick(null) },
                ) {
                    NoWallpaper(noneTheme)
                }
            }
        }
        itemsIndexed(WallpaperArt.entries) { index, entry ->
            WallpaperTile(
                selected = art == entry,
                description = stringResource(R.string.dejavu_welcome_wallpaper_number, index + 1),
                width = tileWidth,
                importing = importing == entry.key,
                onClick = { pick(entry.key) { drawArt(entry) } },
            ) {
                ArtPicture(entry, tileWidth)
            }
        }
        item {
            WallpaperTile(
                selected = shown != null && art == null,
                description = stringResource(R.string.dejavu_welcome_wallpaper_photo),
                width = tileWidth,
                importing = importing == PHOTO,
                onClick = {
                    try {
                        picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                    } catch (e: ActivityNotFoundException) {
                        failed = true
                    }
                },
            ) {
                PhotoTile(photo = shown?.takeIf { art == null })
            }
        }
    }
    if (failed) {
        Text(
            text = stringResource(R.string.dejavu_workspace_wallpaper_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** A wallpaper to pick, shaped like the screen, outlined while it is [selected]; a wheel turns while [importing]. */
@Suppress("LongParameterList")
@Composable
private fun WallpaperTile(
    selected: Boolean,
    description: String,
    width: Dp,
    onClick: () -> Unit,
    importing: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(width)
            .aspectRatio(configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.coerceAtLeast(1))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else DejavuPopup.edgeColor,
                shape = shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        content()
        if (importing) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

/** The workspace's colors, which show without a wallpaper. */
@Composable
private fun NoWallpaper(theme: WorkspaceTheme?) {
    val grain = rememberGrainBrush()
    Spacer(modifier = Modifier.fillMaxSize().drawBehind { drawWorkspaceTheme(theme, null, 0f, grain) })
    // The tile says what it is, so the sign is not read out as "No container".
    Icon(
        painter = painterResource(R.drawable.dejavu_ic_no_container_24),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

/** A small picture of [art], drawn in the background at the size of a tile [tileWidth] wide. */
@Composable
private fun ArtPicture(art: WallpaperArt, tileWidth: Dp) {
    val configuration = LocalConfiguration.current
    val width = with(LocalDensity.current) { tileWidth.roundToPx() }
    val height = width * configuration.screenHeightDp / configuration.screenWidthDp.coerceAtLeast(1)
    val picture by produceState<ImageBitmap?>(null, art, width, height) {
        value = withContext(Dispatchers.Default) { art.draw(width, height).asImageBitmap() }
    }
    picture?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The user's own [photo] when it is the wallpaper, else what picks one. */
@Composable
private fun PhotoTile(photo: WorkspaceWallpaper?) {
    if (photo != null) {
        WallpaperImage(wallpaper = photo, modifier = Modifier.fillMaxSize())
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
        Icon(
            painter = painterResource(iconsR.drawable.mozac_ic_wallpaper_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.dejavu_welcome_wallpaper_photo),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
