/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.welcome

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StrictMode
import android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.browser.state.state.searchEngines
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.compose.base.theme.Theme
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.support.utils.Browsers
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.components.components
import org.mozilla.fenix.dejavu.browser.BarsLayout
import org.mozilla.fenix.dejavu.browser.UBlockOrigin
import org.mozilla.fenix.dejavu.home.LookChip
import org.mozilla.fenix.dejavu.home.SliderRow
import org.mozilla.fenix.dejavu.home.ThemeColorDialog
import org.mozilla.fenix.dejavu.home.ThemeColors
import org.mozilla.fenix.dejavu.home.WallpaperImage
import org.mozilla.fenix.dejavu.home.drawWorkspaceTheme
import org.mozilla.fenix.dejavu.home.rememberGrainBrush
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.DejavuPopup
import org.mozilla.fenix.dejavu.ui.glass
import org.mozilla.fenix.dejavu.workspaces.WallpaperArt
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpaper
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpapers
import org.mozilla.fenix.ext.getPreferenceKey
import org.mozilla.fenix.utils.Settings

/** The content of [page], below the progress and above the buttons. [onSignIn] leaves the pages for the sign-in. */
@Composable
internal fun WelcomePageContent(page: WelcomePage, look: WelcomeLook?, onSignIn: () -> Unit) {
    when (page) {
        WelcomePage.INTRO -> IntroPage()
        WelcomePage.SEARCH -> SearchPage()
        WelcomePage.LOOK -> if (look != null) LookPage(look)
        WelcomePage.BARS -> BarsPage()
        WelcomePage.THEME -> ThemePage()
        WelcomePage.ADS -> AdsPage()
        WelcomePage.DEFAULT_BROWSER -> DefaultBrowserPage()
        WelcomePage.SYNC -> SyncPage(onSignIn)
    }
}

@Composable
private fun IntroPage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
    ) {
        EchoLogo()
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.dejavu_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.rise(ECHO_ORDER),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.dejavu_welcome_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.rise(ECHO_ORDER + 1),
        )
    }
}

/**
 * Dejavu's logo coming in from two fading echoes of itself, which close in on it: something seen before, as its
 * name says.
 */
@Composable
private fun EchoLogo() {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(ECHO_MS, easing = FastOutSlowInEasing)) }
    val logo = painterResource(R.drawable.dejavu_logo)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(LOGO_SIZE * ECHO_ROOM)) {
        ECHOES.forEach { (scale, strength) ->
            Image(
                painter = logo,
                contentDescription = null,
                modifier = Modifier.size(LOGO_SIZE).graphicsLayer {
                    val shown = progress.value
                    val size = scale + (1f - scale) * shown
                    scaleX = size
                    scaleY = size
                    alpha = strength * (1f - shown)
                },
            )
        }
        Image(
            painter = logo,
            contentDescription = null,
            modifier = Modifier.size(LOGO_SIZE).graphicsLayer {
                val shown = progress.value
                val size = LOGO_START + (1f - LOGO_START) * shown
                scaleX = size
                scaleY = size
                alpha = shown
            },
        )
    }
}

@Composable
private fun SearchPage() {
    val fenix = components
    val search by fenix.core.store.observeAsComposableState { it.search }
    PageHeader(
        icon = R.drawable.dejavu_ic_search_24,
        title = R.string.dejavu_welcome_search_title,
        text = R.string.dejavu_welcome_search_text,
    )
    val selected = search.selectedOrDefaultSearchEngine
    // The search engines for everything, without the site searches added to the list, like GitHub, and the one in use
    // if it is another kind.
    val engines = remember(search) {
        val all = search.searchEngines.filter { it.type != SearchEngine.Type.APPLICATION }
        all.filter { (it.isGeneral && it.type != SearchEngine.Type.CUSTOM) || it.id == selected?.id }.ifEmpty { all }
    }
    if (!search.complete) {
        CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(24.dp), strokeWidth = 2.dp)
    }
    engines.forEachIndexed { index, engine ->
        ChoiceCard(
            selected = engine.id == selected?.id,
            onClick = { fenix.useCases.searchUseCases.selectSearchEngine(engine) },
            modifier = Modifier.rise(FIRST_CHOICE + index),
        ) {
            val icon = remember(engine.id) { BitmapPainter(engine.icon.asImageBitmap()) }
            Image(painter = icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Text(
                text = engine.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    SuggestionsSwitch(modifier = Modifier.rise(FIRST_CHOICE + engines.size))
}

/** Whether the search engine suggests searches while typing, which Dejavu leaves off, as it sends what is typed. */
@Composable
private fun SuggestionsSwitch(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = components.settings
    var checked by remember { mutableStateOf(settings.shouldShowSearchSuggestions) }
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .glass(glass, shape)
            .toggleable(value = checked, role = Role.Switch) { on ->
                checked = on
                settings.preferences.edit {
                    putBoolean(context.getPreferenceKey(R.string.pref_key_show_search_suggestions), on)
                }
            }
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.preferences_show_search_suggestions),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.dejavu_welcome_suggestions_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A wallpaper first, as the drawn ones and the user's photos; colors on the other tab, as the workspace dialog has. */
@Composable
private fun LookPage(look: WelcomeLook) {
    PageHeader(
        icon = iconsR.drawable.mozac_ic_wallpaper_24,
        title = R.string.dejavu_welcome_look_title,
        text = R.string.dejavu_welcome_look_text,
    )
    var colors by rememberSaveable { mutableStateOf(false) }
    var pickingColors by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.rise(FIRST_CHOICE)) {
        LookChip(R.string.dejavu_workspace_wallpaper, selected = !colors) { colors = false }
        LookChip(R.string.dejavu_workspace_theme_color, selected = colors) { colors = true }
    }
    Spacer(Modifier.height(16.dp))
    Column(modifier = Modifier.rise(FIRST_CHOICE + 1)) {
        if (colors) {
            ThemeColors(theme = look.theme, onChange = look::showTheme, onCustomColors = { pickingColors = true })
        } else {
            WallpaperChoices(look)
        }
    }
    if (pickingColors) {
        ThemeColorDialog(
            initial = look.theme?.colors.orEmpty(),
            onPick = { picked ->
                look.showTheme(
                    WorkspaceTheme(
                        colors = picked,
                        opacity = look.theme?.opacity ?: WorkspaceTheme.DEFAULT_OPACITY,
                        texture = look.theme?.texture ?: 0f,
                    ),
                )
                pickingColors = false
            },
            onDismiss = { pickingColors = false },
        )
    }
}

/**
 * The wallpapers to pick from, in a row: none, the ones Dejavu draws, and a picture of the user's own, with the slider
 * of how strongly the one picked shows through its veil.
 */
@Composable
private fun WallpaperChoices(look: WelcomeLook) {
    val context = LocalContext.current
    val fenix = components
    val wallpapers = remember {
        fenix.strictMode.allowViolation(StrictMode::allowThreadDiskReads) { WorkspaceWallpapers.get(context) }
    }
    val scope = rememberCoroutineScope()
    val screen = context.resources.displayMetrics
    var importing by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val shown = look.wallpaper
    val art = WallpaperArt.of(shown)

    // Pictures are read or drawn in the background; one at a time.
    fun pick(key: String, load: suspend () -> WorkspaceWallpaper?) {
        if (importing != null) return
        importing = key
        failed = false
        scope.launch {
            val picked = load()
            importing = null
            failed = picked == null
            picked?.let { look.showWallpaper(it.copy(intensity = look.wallpaper?.intensity ?: it.intensity)) }
        }
    }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) pick(PHOTO) { wallpapers.importPicture(uri, screen.widthPixels, screen.heightPixels) }
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = PAGE_MARGIN),
        modifier = Modifier.toPageEdges(),
    ) {
        item {
            WallpaperTile(
                selected = shown == null,
                description = stringResource(R.string.dejavu_welcome_wallpaper_none),
                onClick = { look.showWallpaper(null) },
            ) {
                NoWallpaper(look.theme)
            }
        }
        itemsIndexed(WallpaperArt.entries) { index, entry ->
            WallpaperTile(
                selected = art == entry,
                description = stringResource(R.string.dejavu_welcome_wallpaper_number, index + 1),
                importing = importing == entry.key,
                onClick = { pick(entry.key) { wallpapers.importArt(entry, screen.widthPixels, screen.heightPixels) } },
            ) {
                ArtPicture(entry)
            }
        }
        item {
            WallpaperTile(
                selected = shown != null && art == null,
                description = stringResource(R.string.dejavu_welcome_wallpaper_photo),
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
    shown?.let { wallpaper ->
        Spacer(Modifier.height(12.dp))
        SliderRow(R.string.dejavu_workspace_theme_intensity, wallpaper.intensity, 0f..1f, look::setIntensity)
    }
}

/** A wallpaper to pick, shaped like the screen, outlined while it is [selected]; a wheel turns while [importing]. */
@Composable
private fun WallpaperTile(
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
    importing: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(TILE_WIDTH)
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

/** A small picture of [art], drawn in the background at the tile's size. */
@Composable
private fun ArtPicture(art: WallpaperArt) {
    val configuration = LocalConfiguration.current
    val width = with(LocalDensity.current) { TILE_WIDTH.roundToPx() }
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

/** Where the bars go around web pages, and where the home screen's search bar goes, each with a drawing of it. */
@Composable
private fun BarsPage() {
    val settings = dejavuSettings()
    val layout by settings.barsLayout.collectAsState()
    val searchAtBottom by settings.homeSearchAtBottom.collectAsState()
    val colors = sketchColors()
    PageHeader(
        icon = iconsR.drawable.mozac_ic_device_mobile_24,
        title = R.string.dejavu_welcome_bars_title,
        text = R.string.dejavu_welcome_bars_text,
    )
    ChoicesLabel(R.string.dejavu_welcome_bars_pages, modifier = Modifier.rise(FIRST_CHOICE))
    BAR_CHOICES.forEachIndexed { index, (choice, title, summary) ->
        val selected = layout == choice
        ChoiceCard(
            selected = selected,
            onClick = { settings.setBarsLayout(choice) },
            modifier = Modifier.rise(FIRST_CHOICE + 1 + index),
        ) {
            PhoneSketch(colors = colors, highlighted = selected, modifier = Modifier.width(SKETCH_WIDTH)) {
                drawPage(choice, it)
            }
            Spacer(Modifier.width(16.dp))
            ChoiceText(title, summary)
        }
    }
    ChoicesLabel(R.string.dejavu_welcome_bars_home, modifier = Modifier.padding(top = 12.dp).rise(FIRST_CHOICE + 4))
    HOME_CHOICES.forEachIndexed { index, (atBottom, title, summary) ->
        val selected = searchAtBottom == atBottom
        ChoiceCard(
            selected = selected,
            onClick = { settings.setHomeSearchAtBottom(atBottom) },
            modifier = Modifier.rise(FIRST_CHOICE + 5 + index),
        ) {
            PhoneSketch(colors = colors, highlighted = selected, modifier = Modifier.width(SKETCH_WIDTH)) {
                drawHome(atBottom, it)
            }
            Spacer(Modifier.width(16.dp))
            ChoiceText(title, summary)
        }
    }
}

/** A choice's [title], with its [summary] below it. */
@Composable
private fun RowScope.ChoiceText(@StringRes title: Int, @StringRes summary: Int?) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        summary?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Light or dark, or as the phone is, each drawn in its colors. Picking one recreates the screen in it. */
@Composable
private fun ThemePage() {
    val activity = LocalActivity.current
    val fenix = components
    val current = remember { ThemeChoice.of(fenix.settings) }
    PageHeader(
        icon = iconsR.drawable.mozac_ic_themes_24,
        title = R.string.dejavu_welcome_theme_title,
        text = R.string.dejavu_welcome_theme_text,
    )
    // As tall as the tallest, whose name takes two lines.
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max).rise(FIRST_CHOICE),
    ) {
        ThemeChoice.available.forEach { choice ->
            ThemeOption(
                choice = choice,
                selected = current == choice,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = { activity?.let { applyTheme(it, fenix, choice) } },
            )
        }
    }
}

/** [choice] drawn as the home screen in its colors, half and half for following the phone, with its name below. */
@Composable
private fun ThemeOption(choice: ThemeChoice, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val light = sketchColors(Theme.Light)
    val dark = sketchColors(Theme.Dark)
    val shape = RoundedCornerShape(20.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .glass(glass, shape)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
    ) {
        when (choice) {
            ThemeChoice.LIGHT -> PhoneSketch(light, highlighted = selected, modifier = Modifier.fillMaxWidth()) {
                drawHome(false, it)
            }
            ThemeChoice.DARK -> PhoneSketch(dark, highlighted = selected, modifier = Modifier.fillMaxWidth()) {
                drawHome(false, it)
            }
            ThemeChoice.DEVICE -> SplitPhoneSketch(
                light = light,
                dark = dark,
                highlighted = selected,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(choice.label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/** Whether pages show their ads, or uBlock Origin removes them; trackers are blocked either way. */
@Composable
private fun AdsPage() {
    val context = LocalContext.current
    var blocks by remember { mutableStateOf(UBlockOrigin.blocksAds(context)) }
    PageHeader(
        icon = iconsR.drawable.mozac_ic_shield_checkmark_24,
        title = R.string.dejavu_welcome_ads_title,
        text = R.string.dejavu_welcome_ads_text,
    )
    listOf(
        Triple(true, R.string.dejavu_welcome_ads_block, R.string.dejavu_welcome_ads_block_summary),
        Triple(false, R.string.dejavu_welcome_ads_allow, R.string.dejavu_welcome_ads_allow_summary),
    ).forEachIndexed { index, (block, title, summary) ->
        ChoiceCard(
            selected = blocks == block,
            onClick = {
                if (blocks != block) {
                    blocks = block
                    UBlockOrigin.setBlocksAds(context, block)
                }
            },
            modifier = Modifier.rise(FIRST_CHOICE + index),
        ) {
            Icon(
                painter = painterResource(
                    if (block) iconsR.drawable.mozac_ic_shield_checkmark_24 else iconsR.drawable.mozac_ic_shield_24,
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(16.dp))
            ChoiceText(title, summary)
        }
    }
}

/**
 * Makes Dejavu the browser links from other apps open in, with the system's own question, or its default apps
 * settings once that question was turned down.
 */
@Composable
private fun DefaultBrowserPage() {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(Browsers.isDefaultBrowser(context)) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(StartActivityForResult()) {
        isDefault = Browsers.isDefaultBrowser(context)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isDefault = Browsers.isDefaultBrowser(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    PageHeader(
        icon = iconsR.drawable.mozac_ic_globe_24,
        title = R.string.dejavu_welcome_default_title,
        text = R.string.dejavu_welcome_default_text,
    )
    Box(modifier = Modifier.rise(FIRST_CHOICE)) {
        if (isDefault) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_checkmark_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.dejavu_welcome_default_done),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            DejavuDialogButton(
                text = stringResource(R.string.preferences_set_as_default_browser),
                onClick = {
                    askToBeDefault(context, launcher, settingsInstead = asked)
                    asked = true
                },
                style = DejavuButtonStyle.Tonal,
            )
        }
    }
}

/**
 * Asks the system to make Dejavu the default browser, or opens its default apps settings when it cannot ask, or when
 * [settingsInstead], as the system stops asking once the question was turned down.
 */
private fun askToBeDefault(
    context: Context,
    launcher: ActivityResultLauncher<Intent>,
    settingsInstead: Boolean,
) {
    if (!settingsInstead && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roles = context.getSystemService(RoleManager::class.java)
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
            !roles.isRoleHeld(RoleManager.ROLE_BROWSER)
        ) {
            launcher.launch(roles.createRequestRoleIntent(RoleManager.ROLE_BROWSER))
            return
        }
    }
    try {
        launcher.launch(Intent(ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    } catch (e: ActivityNotFoundException) {
        // A phone without the settings screen keeps its browser.
    }
}

/** Signing in to sync with Zen or Firefox, the last page. The account page opens once the pages are left. */
@Composable
private fun SyncPage(onSignIn: () -> Unit) {
    val fenix = components
    val signedIn = remember { fenix.backgroundServices.accountManager.authenticatedAccount() != null }
    PageHeader(
        icon = iconsR.drawable.mozac_ic_sync_24,
        title = R.string.dejavu_welcome_sync_title,
        text = R.string.dejavu_welcome_sync_text,
    )
    if (!signedIn) {
        Box(modifier = Modifier.rise(FIRST_CHOICE)) {
            DejavuDialogButton(
                text = stringResource(R.string.preferences_sign_in),
                onClick = onSignIn,
                style = DejavuButtonStyle.Tonal,
            )
        }
    }
}

/** The look Dejavu takes: the phone's, or always light or always dark. */
internal enum class ThemeChoice(@param:StringRes val label: Int, val mode: Int) {
    DEVICE(R.string.preference_follow_device_theme, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(R.string.preference_light_theme, AppCompatDelegate.MODE_NIGHT_NO),
    DARK(R.string.preference_dark_theme, AppCompatDelegate.MODE_NIGHT_YES),
    ;

    companion object {
        /** The choice [settings] hold, as the theme settings show it. */
        fun of(settings: Settings): ThemeChoice = when {
            settings.shouldUseLightTheme -> LIGHT
            settings.shouldUseDarkTheme || settings.shouldUseOledTheme -> DARK
            else -> DEVICE
        }

        /** The choices this phone has: following it needs Android 9. */
        val available: List<ThemeChoice>
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) entries else listOf(LIGHT, DARK)
    }
}

/** Gives Dejavu the look of [choice], as the theme settings do, which recreates [activity]. */
private fun applyTheme(activity: Activity, components: Components, choice: ThemeChoice) {
    val settings = components.settings
    settings.shouldUseLightTheme = choice == ThemeChoice.LIGHT
    settings.shouldUseDarkTheme = choice == ThemeChoice.DARK
    settings.shouldUseOledTheme = false
    settings.shouldFollowDeviceTheme = choice == ThemeChoice.DEVICE
    settings.preferences.edit {
        putBoolean(activity.getPreferenceKey(R.string.pref_key_auto_battery_theme), false)
    }
    if (AppCompatDelegate.getDefaultNightMode() == choice.mode) return
    AppCompatDelegate.setDefaultNightMode(choice.mode)
    activity.recreate()
    components.core.engine.settings.preferredColorScheme = components.core.getPreferredColorScheme()
    components.useCases.sessionUseCases.reload.invoke()
}

@Composable
private fun dejavuSettings(): DejavuSettings {
    val context = LocalContext.current
    val fenix = components
    return remember {
        fenix.strictMode.allowViolation(StrictMode::allowThreadDiskReads) { DejavuSettings.get(context) }
    }
}

private val BAR_CHOICES = listOf(
    Triple(BarsLayout.ACTIONS_BAR, R.string.dejavu_bars_actions_bar, R.string.dejavu_bars_actions_bar_summary),
    Triple(BarsLayout.TOP, R.string.dejavu_bars_top, R.string.dejavu_bars_top_summary),
    Triple(BarsLayout.BOTTOM, R.string.dejavu_bars_bottom, R.string.dejavu_bars_bottom_summary),
)

private val HOME_CHOICES = listOf(
    Triple(false, R.string.dejavu_home_search_top, R.string.dejavu_home_search_top_summary),
    Triple(true, R.string.dejavu_home_search_bottom, R.string.dejavu_home_search_bottom_summary),
)

/** Where a page's choices start in the order its parts come in, after its icon, title and text. */
private const val FIRST_CHOICE = 3
private const val PHOTO = "photo"
private const val ECHO_MS = 1100
private const val ECHO_ORDER = 6
private const val ECHO_ROOM = 1.6f
private const val LOGO_START = 0.85f
private val LOGO_SIZE = 104.dp
private val TILE_WIDTH = 72.dp
private val SKETCH_WIDTH = 44.dp

/** How much larger each echo of the logo starts, and how strongly it shows then. */
private val ECHOES = listOf(1.6f to 0.16f, 1.28f to 0.3f)
