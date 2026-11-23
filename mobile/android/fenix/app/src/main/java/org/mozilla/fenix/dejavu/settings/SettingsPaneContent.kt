/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.menu.BORDER_ALPHA
import org.mozilla.fenix.settings.settingssearch.SettingsSearchItem

private val SearchShape = RoundedCornerShape(28.dp)

/** How long the search field takes to turn its hairline into the focus ring and back. Short, as it answers a tap. */
private const val FOCUS_RING_MS = 120

/** How long the list of categories and the found settings take to swap. A fade only, so typing never waits for it. */
private const val SWAP_MS = 100

/** What the list shows below the search field. */
private enum class ListMode { CATEGORIES, RESULTS, NO_RESULTS }

/**
 * The list of categories beside the settings: a search field on top and the categories below it, or the settings the
 * search finds while something is typed.
 *
 * @param selected The category whose settings are shown.
 * @param search Finds the settings for what was typed, for as long as it is typed.
 * @param categoryOf The category a found setting belongs to.
 * @param onSelect Called with the category that was tapped.
 * @param onOpen Called with the found setting that was tapped.
 */
@Composable
internal fun SettingsPaneContent(
    selected: SettingsCategory,
    search: suspend (String) -> List<SettingsSearchItem>,
    categoryOf: (SettingsSearchItem) -> SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    onOpen: (SettingsSearchItem) -> Unit,
) {
    val hairline = MaterialTheme.colorScheme.onSurface.copy(alpha = BORDER_ALPHA)
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf("" to emptyList<SettingsSearchItem>()) }
    LaunchedEffect(query) { results = query to if (query.isBlank()) emptyList() else search(query) }
    val found = results.second
    // "No results" is only true once the search of this query is done, not while the one before it is still shown.
    val mode = when {
        query.isBlank() -> ListMode.CATEGORIES
        found.isEmpty() && results.first == query -> ListMode.NO_RESULTS
        else -> ListMode.RESULTS
    }

    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        SearchField(query, onChange = { query = it })
        Crossfade(mode, animationSpec = tween(SWAP_MS, easing = FastOutSlowInEasing), label = "settingsList") { shown ->
            when (shown) {
                ListMode.CATEGORIES -> LazyColumn(
                    Modifier.padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(SettingsCategory.entries) { category ->
                        NavigationDrawerItem(
                            label = { Text(stringResource(category.title)) },
                            icon = { Icon(painterResource(category.icon), contentDescription = null) },
                            selected = category == selected,
                            onClick = { onSelect(category) },
                            // See-through like the cards beside it, over the backdrop of the page. The selection
                            // moves at once: picking a category is frequent, and the settings change with it.
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                    .copy(alpha = SETTINGS_CARD_ALPHA),
                                unselectedContainerColor = Color.Transparent,
                            ),
                            modifier = if (category == selected) {
                                Modifier.border(1.dp, hairline, SearchShape)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
                ListMode.NO_RESULTS -> Text(
                    text = stringResource(R.string.settings_search_no_results_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                ListMode.RESULTS -> LazyColumn {
                    items(found, key = { it.preferenceKey }) { item ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(item) }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                        ) {
                            // There is no surface here to give the text its color, which would leave it black.
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(categoryOf(item).title),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fill = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = SETTINGS_CARD_ALPHA)
    // The ring eases in rather than snapping, so a tap on the field reads as a response. Compose scales the tween
    // with the system animator scale, so it is skipped when animations are off.
    val ringSpec = tween<Color>(FOCUS_RING_MS, easing = FastOutSlowInEasing)
    val ringColor by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = BORDER_ALPHA),
        ringSpec,
        label = "searchRingColor",
    )
    val ringWidth by animateDpAsState(
        if (focused) 2.dp else 1.dp,
        tween(FOCUS_RING_MS, easing = FastOutSlowInEasing),
        label = "searchRingWidth",
    )
    TextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        shape = SearchShape,
        placeholder = { Text(stringResource(R.string.settings_search_title)) },
        leadingIcon = { Icon(painterResource(iconsR.drawable.mozac_ic_search_24), contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(
                        painterResource(iconsR.drawable.mozac_ic_cross_circle_fill_24),
                        contentDescription = stringResource(R.string.content_description_settings_search_clear_search),
                    )
                }
            }
        },
        // Drawn like the sidebar's search field: a see-through pill with a hairline that turns into the accent ring.
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = fill,
            unfocusedContainerColor = fill,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .onFocusChanged { focused = it.hasFocus }
            .border(width = ringWidth, color = ringColor, shape = SearchShape),
    )
}
