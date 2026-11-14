/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.MediaSessionState
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.feature.media.R as mediaR
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.ui.glass

/**
 * The media of a tab, for the player on the home screen.
 *
 * @property tabId The tab playing it.
 * @property url Address of the tab's page.
 * @property pageTitle Title of the tab's page.
 * @property state The tab's media session.
 */
@Immutable
data class HomeMedia(val tabId: String, val url: String, val pageTitle: String, val state: MediaSessionState) {
    /** Stays the same while the page keeps its media session, see [MediaSessionState.timestamp]. */
    val key: String
        get() = "$tabId:${state.timestamp}"

    val isPlaying: Boolean
        get() = state.playbackState == MediaSession.PlaybackState.PLAYING
}

/**
 * The media the player on the home screen shows: of the tab that plays, or else of the one paused last, among the
 * normal tabs that are awake. Private tabs are left out, the way their media notification does not tell what they play,
 * and so are sleeping tabs, whose pages are gone.
 */
internal fun BrowserState.homeMedia(): HomeMedia? = normalTabs
    .filter { it.isAwake }
    .mapNotNull { tab ->
        tab.mediaSessionState
            ?.takeIf { it.playbackState in ShownPlaybackStates }
            ?.let { HomeMedia(tab.id, tab.content.url, tab.content.title, it) }
    }
    .maxByOrNull { it.state }

private val ShownPlaybackStates = setOf(MediaSession.PlaybackState.PLAYING, MediaSession.PlaybackState.PAUSED)

/**
 * The media the user closed the player of, by [HomeMedia.key]: it stays hidden until it plays again. Kept outside the
 * home screen, so the player does not come back each time the home screen shows.
 */
private var closedMediaKey by mutableStateOf<String?>(null)

/**
 * The player of the media [media] holds on the home screen: its picture, title and artist, play and pause, the tracks
 * before and after when the page has them, and how far it got. Tapping it opens its tab with [onOpen]. Closing it pauses
 * the media and hides the player until the media plays again.
 */
@Composable
internal fun MediaPlayerBar(media: State<HomeMedia?>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    // Read here, so that the media moving on redraws only the player.
    val playing = media.value
    LaunchedEffect(playing?.key, playing?.isPlaying) {
        if (playing?.isPlaying == true && closedMediaKey == playing.key) closedMediaKey = null
    }
    val shown = playing?.takeIf { it.key != closedMediaKey }
    AnimatedVisibility(
        visible = shown != null,
        enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
        modifier = modifier,
    ) {
        // The player keeps showing the media it had while it goes away.
        var last by remember { mutableStateOf(shown) }
        if (shown != null) last = shown
        last?.let { current ->
            PlayerCard(
                media = current,
                onOpen = { onOpen(current.tabId) },
                onClose = {
                    if (current.isPlaying) current.state.controller.pause()
                    closedMediaKey = current.key
                },
            )
        }
    }
}

@Composable
private fun PlayerCard(media: HomeMedia, onOpen: () -> Unit, onClose: () -> Unit) {
    val state = media.state
    val host = remember(media.url) { runCatching { java.net.URI(media.url).host }.getOrNull().orEmpty() }
    val title = state.metadata?.title?.takeIf { it.isNotBlank() } ?: media.pageTitle.ifBlank { host }
    val artist = state.metadata?.artist?.takeIf { it.isNotBlank() } ?: host
    val progress = rememberProgress(state)
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = PROGRESS_ALPHA)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(PlayerHeight)
            .glass(glass, PlayerShape)
            .clickable(onClick = onOpen)
            .drawWithContent {
                drawContent()
                val fraction = progress.floatValue
                if (fraction > 0f) {
                    val line = ProgressHeight.toPx()
                    drawRect(
                        color = lineColor,
                        topLeft = Offset(0f, size.height - line),
                        size = Size(size.width * fraction, line),
                    )
                }
            }
            .padding(start = 8.dp),
    ) {
        Artwork(media)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.features.contains(MediaSession.Feature.PREVIOUS_TRACK)) {
            PlayerButton(
                icon = iconsR.drawable.mozac_ic_play_previous_fill_24,
                description = stringResource(mediaR.string.mozac_feature_media_notification_action_previous),
                onClick = { state.controller.previousTrack() },
            )
        }
        if (media.isPlaying) {
            PlayerButton(
                icon = iconsR.drawable.mozac_ic_pause_24,
                description = stringResource(mediaR.string.mozac_feature_media_notification_action_pause),
                onClick = { state.controller.pause() },
            )
        } else {
            PlayerButton(
                icon = iconsR.drawable.mozac_ic_play_fill_24,
                description = stringResource(mediaR.string.mozac_feature_media_notification_action_play),
                onClick = { state.controller.play() },
            )
        }
        if (state.features.contains(MediaSession.Feature.NEXT_TRACK)) {
            PlayerButton(
                icon = iconsR.drawable.mozac_ic_play_next_fill_24,
                description = stringResource(mediaR.string.mozac_feature_media_notification_action_next),
                onClick = { state.controller.nextTrack() },
            )
        }
        PlayerButton(
            icon = iconsR.drawable.mozac_ic_cross_24,
            description = stringResource(R.string.dejavu_media_close),
            onClick = onClose,
            small = true,
        )
    }
}

/** The page's picture of the media, or else the site's icon. */
@Composable
private fun Artwork(media: HomeMedia) {
    val metadata = media.state.metadata
    val artwork by produceState<Bitmap?>(initialValue = null, metadata) {
        value = runCatching { metadata?.getArtwork?.invoke() }.getOrNull()
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ArtworkSize)
            .clip(ArtworkShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = ARTWORK_BACKGROUND_ALPHA)),
    ) {
        val picture = artwork
        if (picture != null) {
            val bitmap = remember(picture) { picture.asImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(ArtworkSize),
            )
        } else {
            SiteIcon(url = media.url, size = 22.dp)
        }
    }
}

/**
 * How far the media got, from 0 to 1, or 0 when its length is not known. Pages tell the position only now and then, so
 * while the media plays it moves on by itself from the last position told. Read it while drawing.
 */
@Composable
private fun rememberProgress(state: MediaSessionState): MutableFloatState {
    val progress = remember { mutableFloatStateOf(0f) }
    val position = state.positionState
    val duration = position.duration.takeIf { it > 0 } ?: state.elementMetadata?.duration?.takeIf { it > 0 }
    val playing = state.playbackState == MediaSession.PlaybackState.PLAYING
    LaunchedEffect(position, duration, playing) {
        if (duration == null) {
            progress.floatValue = 0f
            return@LaunchedEffect
        }
        val toldAt = SystemClock.elapsedRealtime()
        // A rate of 0 is what pages that never told one send; they play at normal speed.
        val rate = position.playbackRate.takeIf { it > 0 } ?: 1.0
        do {
            val elapsed = if (playing) (SystemClock.elapsedRealtime() - toldAt) / MILLIS_PER_SECOND * rate else 0.0
            progress.floatValue = ((position.position + elapsed) / duration).toFloat().coerceIn(0f, 1f)
            if (playing) withFrameMillis { }
        } while (playing)
    }
    return progress
}

@Composable
private fun PlayerButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit, small: Boolean = false) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = if (small) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(if (small) 18.dp else 22.dp),
        )
    }
}

private val PlayerHeight = 56.dp
private val PlayerShape = RoundedCornerShape(20.dp)
private val ArtworkSize = 40.dp
private val ArtworkShape = RoundedCornerShape(10.dp)
private val ProgressHeight = 2.dp
private const val PROGRESS_ALPHA = 0.45f
private const val ARTWORK_BACKGROUND_ALPHA = 0.08f
private const val MILLIS_PER_SECOND = 1000.0
