/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.features

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.DialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.settings.workspaceRepository
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.DejavuPopup
import org.mozilla.fenix.dejavu.ui.LocalPopupTheme
import org.mozilla.fenix.dejavu.ui.popupGlow
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * A feature the tour shows: its title, where to find it, and its animation. The animations and the GIFs to share
 * them are made by scripts/dejavu-feature-gifs.py, which writes the same titles on the GIFs.
 *
 * @property title What the feature does, in a few words.
 * @property animation The phone showing the feature, an animated WebP.
 * @property where The steps to the feature's settings, or nothing when it has none.
 * @property whereIcon The icon in front of [where].
 */
enum class DejavuFeature(
    @param:StringRes val title: Int,
    @param:RawRes val animation: Int,
    val where: List<Int> = emptyList(),
    @param:DrawableRes val whereIcon: Int = iconsR.drawable.mozac_ic_settings_24,
) {
    SLEEP(
        R.string.dejavu_feature_sleep,
        R.raw.dejavu_feature_sleep,
        listOf(R.string.dejavu_settings, R.string.dejavu_settings_tab_sleep),
    ),
    THEMES(
        R.string.dejavu_feature_themes,
        R.raw.dejavu_feature_themes,
        listOf(R.string.dejavu_workspace_menu, R.string.dejavu_workspace_edit),
        iconsR.drawable.mozac_ic_ellipsis_vertical_24,
    ),
    SWIPE_MENU(
        R.string.dejavu_feature_swipe_menu,
        R.raw.dejavu_feature_swipe_menu,
        listOf(R.string.dejavu_settings, R.string.dejavu_settings_more_menu),
    ),
    ACTIONS(
        R.string.dejavu_feature_actions,
        R.raw.dejavu_feature_actions,
        listOf(R.string.dejavu_settings, R.string.dejavu_settings_tab_actions),
    ),
    ZEN_SYNC(
        R.string.dejavu_feature_zen_sync,
        R.raw.dejavu_feature_zen_sync,
        listOf(R.string.preferences_account_settings, R.string.dejavu_settings_sync),
        iconsR.drawable.mozac_ic_avatar_circle_24,
    ),
    FOLDERS(R.string.dejavu_feature_folders, R.raw.dejavu_feature_folders),
    DRAG(R.string.dejavu_feature_drag, R.raw.dejavu_feature_drag),
    WORKSPACE_ORDER(R.string.dejavu_feature_workspace_order, R.raw.dejavu_feature_workspace_order),
    SPLIT_VIEW(R.string.dejavu_feature_split_view, R.raw.dejavu_feature_split_view),
}

/** The tour of Dejavu's features, shown once when Dejavu starts and again from About. */
object FeatureTour {
    /** Raised when the tour gets new features, so that it shows once more. */
    const val VERSION = 1

    /** Whether the tour still has to be shown when Dejavu starts. */
    fun isDue(settings: DejavuSettings): Boolean = settings.featureTourSeen < VERSION

    fun markSeen(settings: DejavuSettings) {
        settings.featureTourSeen = VERSION
    }
}

/** The tour as a dialog, over the screen it opens on. */
@Composable
fun FeatureTourDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FeatureTourCard(onDone = onDismiss)
    }
}

/**
 * The tour, one feature a page: the title, where to find it, and the phone showing it. Swiping or Next goes on;
 * Skip and Done end it with [onDone].
 */
@Composable
fun FeatureTourCard(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val features = DejavuFeature.entries
    val pagerState = rememberPagerState { features.size }
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == features.lastIndex
    val animationHeight = (LocalConfiguration.current.screenHeightDp * ANIMATION_SHARE).dp.coerceAtMost(MAX_ANIMATION)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .padding(16.dp)
            .widthIn(max = MAX_WIDTH)
            .fillMaxWidth()
            .shadow(12.dp, DejavuPopup.DialogShape)
            .clip(DejavuPopup.DialogShape)
            .background(DejavuPopup.containerColor)
            .popupGlow()
            .border(1.dp, DejavuPopup.edgeColor, DejavuPopup.DialogShape)
            .padding(vertical = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.dejavu_features_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.dejavu_features_page, pagerState.currentPage + 1, features.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { page ->
            FeaturePage(
                feature = features[page],
                playing = page == pagerState.currentPage,
                animationHeight = animationHeight,
            )
        }
        PageDots(count = features.size, current = pagerState.currentPage)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        ) {
            if (!isLast) {
                DejavuDialogButton(text = stringResource(R.string.dejavu_features_skip), onClick = onDone)
            }
            DejavuDialogButton(
                text = stringResource(if (isLast) R.string.dejavu_done else R.string.dejavu_features_next),
                style = DejavuButtonStyle.Primary,
                onClick = {
                    if (isLast) onDone() else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
            )
        }
    }
}

@Composable
private fun FeaturePage(feature: DejavuFeature, playing: Boolean, animationHeight: Dp) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    ) {
        Text(
            text = stringResource(feature.title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Pages without settings keep the same room, so the phone stays in place while swiping.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.height(WHERE_HEIGHT).padding(top = 8.dp)) {
            if (feature.where.isNotEmpty()) WherePill(feature)
        }
        FeatureAnimation(
            animation = feature.animation,
            playing = playing,
            modifier = Modifier.padding(top = 8.dp).height(animationHeight).aspectRatio(ANIMATION_RATIO),
        )
    }
}

/** Where to find the feature, like "Settings › Sleeping tabs". */
@Composable
private fun WherePill(feature: DejavuFeature) {
    val separator = if (LocalLayoutDirection.current == LayoutDirection.Rtl) "  ‹  " else "  ›  "
    val steps = feature.where.map { stringResource(it) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            painter = painterResource(feature.whereIcon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = steps.joinToString(separator),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 14.dp),
    ) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == current) 8.dp else 6.dp)
                    .align(Alignment.CenterVertically)
                    .clip(CircleShape)
                    .background(
                        if (index == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DOT_ALPHA)
                        },
                    ),
            )
        }
    }
}

/**
 * Plays [animation] while [playing]. Android 9 and newer play animated WebP; older versions show its first frame.
 * The title above already says what it shows, so screen readers skip it.
 */
@Composable
private fun FeatureAnimation(@RawRes animation: Int, playing: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(null, animation) {
        value = withContext(Dispatchers.IO) { decodeAnimation(context, animation) }
    }
    AndroidView(
        factory = {
            ImageView(it).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        },
        update = { view ->
            if (view.drawable !== drawable) view.setImageDrawable(drawable)
            (drawable as? Animatable)?.let { if (playing) it.start() else it.stop() }
        },
        modifier = modifier,
        // A page swiped away lets go of its animation and the frames it decoded.
        onRelease = { view ->
            (view.drawable as? Animatable)?.stop()
            view.setImageDrawable(null)
        },
    )
}

private fun decodeAnimation(context: Context, @RawRes animation: Int): Drawable? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, animation)).also {
            (it as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        }
    } else {
        context.resources.openRawResource(animation).use { BitmapFactory.decodeStream(it) }
            ?.let { BitmapDrawable(context.resources, it) }
    }
}.getOrNull()

/** The tour opened from About, over the settings, in the colors of the workspace shown on the home screen. */
class DejavuFeatureTourFragment : DialogFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                FirefoxTheme {
                    val repository = remember { workspaceRepository() }
                    val workspaces by repository.state.collectAsState()
                    CompositionLocalProvider(LocalPopupTheme provides workspaces.activeWorkspace?.theme) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                            FeatureTourCard(onDone = { dismiss() })
                        }
                    }
                }
            }
        }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    companion object {
        const val TAG = "DejavuFeatureTour"
    }
}

/** The size of the animations, a phone with its frame and shadow. */
private const val ANIMATION_RATIO = 488f / 921f
private const val ANIMATION_SHARE = 0.48f
private const val DOT_ALPHA = 0.3f
private val MAX_ANIMATION = 440.dp
private val MAX_WIDTH = 480.dp
private val WHERE_HEIGHT = 40.dp
