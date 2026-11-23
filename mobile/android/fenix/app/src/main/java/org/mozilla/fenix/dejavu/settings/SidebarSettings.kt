/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import kotlin.math.roundToInt
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.IconListItem
import org.mozilla.fenix.compose.list.SwitchListItem
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Sets up the tab sidebar's rows on Customize's bars screen: the sidebar itself, hiding it until a swipe from the edge,
 * its handle and its look. The handle only matters while the sidebar hides, and the sidebar replaces the tab bar.
 */
fun PreferenceFragmentCompat.setupTabSidebarCategory(onSidebarChanged: () -> Unit) {
    val settings = requireContext().components.settings
    val sidebar = findPreference<SwitchPreferenceCompat>(getString(R.string.pref_key_dejavu_sidebar)) ?: return
    val autoHide = findPreference<SwitchPreferenceCompat>(getString(R.string.pref_key_dejavu_sidebar_auto_hide))
    val handle = findPreference<SwitchPreferenceCompat>(getString(R.string.pref_key_dejavu_sidebar_handle))
    val look = findPreference<Preference>(getString(R.string.pref_key_dejavu_sidebar_look))
    fun update() {
        sidebar.isChecked = settings.isVerticalTabsEnabled
        autoHide?.isChecked = settings.isVerticalTabsAutoHide
        autoHide?.isEnabled = settings.isVerticalTabsEnabled
        handle?.isChecked = settings.isSidebarHandleShown
        handle?.isEnabled = settings.isVerticalTabsEnabled && settings.isVerticalTabsAutoHide
        look?.isEnabled = settings.isVerticalTabsEnabled
    }
    update()
    sidebar.setOnPreferenceChangeListener { _, value ->
        settings.isVerticalTabsEnabled = value == true
        update()
        onSidebarChanged()
        true
    }
    autoHide?.setOnPreferenceChangeListener { _, value ->
        settings.isVerticalTabsAutoHide = value == true
        update()
        true
    }
    handle?.setOnPreferenceChangeListener { _, value ->
        settings.isSidebarHandleShown = value == true
        true
    }
}

/** How the tab sidebar looks: its row height, icon sizes and which of its parts show. See [SidebarLook]. */
class DejavuSidebarLookFragment : DejavuComposeFragment(R.string.dejavu_settings_sidebar_look) {
    @Suppress("LongMethod")
    @Composable
    override fun DejavuScreen() {
        val context = LocalContext.current
        val settings = remember { DejavuSettings.get(context) }
        val look by settings.sidebarLook.collectAsState()
        fun update(change: SidebarLook.() -> SidebarLook) = settings.setSidebarLook(look.change())

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsHeader(stringResource(R.string.dejavu_sidebar_look_sizes))
            SizeSlider(R.string.dejavu_sidebar_look_row_height, look.rowHeight, SidebarLook.ROW_HEIGHTS, 0) {
                update { copy(rowHeight = it) }
            }
            SizeSlider(R.string.dejavu_sidebar_look_site_icon_size, look.siteIconSize, SidebarLook.SITE_ICON_SIZES, 1) {
                update { copy(siteIconSize = it) }
            }
            SizeSlider(R.string.dejavu_sidebar_look_bar_icon_size, look.barIconSize, SidebarLook.BAR_ICON_SIZES, 2) {
                update { copy(barIconSize = it) }
            }
            SettingsHeader(stringResource(R.string.dejavu_sidebar_look_parts))
            SwitchListItem(
                label = stringResource(R.string.dejavu_sidebar_look_site_icons),
                checked = look.showSiteIcons,
                showSwitchAfter = true,
                modifier = Modifier.settingsCard(index = 0, count = PARTS),
                onClick = { update { copy(showSiteIcons = it) } },
            )
            SwitchListItem(
                label = stringResource(R.string.dejavu_sidebar_look_workspace_header),
                checked = look.showWorkspaceHeader,
                showSwitchAfter = true,
                modifier = Modifier.settingsCard(index = 1, count = PARTS),
                onClick = { update { copy(showWorkspaceHeader = it) } },
            )
            SwitchListItem(
                label = stringResource(R.string.dejavu_sidebar_look_page_controls),
                description = stringResource(R.string.dejavu_sidebar_look_page_controls_summary),
                maxDescriptionLines = 3,
                checked = look.showPageControls,
                showSwitchAfter = true,
                modifier = Modifier.settingsCard(index = 2, count = PARTS),
                onClick = { update { copy(showPageControls = it) } },
            )
            SwitchListItem(
                label = stringResource(R.string.dejavu_sidebar_look_bottom_bar),
                checked = look.showBottomBar,
                showSwitchAfter = true,
                modifier = Modifier.settingsCard(index = 3, count = PARTS),
                onClick = { update { copy(showBottomBar = it) } },
            )
            SwitchListItem(
                label = stringResource(R.string.dejavu_sidebar_look_pin_buttons),
                description = stringResource(R.string.dejavu_sidebar_look_pin_buttons_summary),
                maxDescriptionLines = 3,
                checked = look.showPinButtons,
                showSwitchAfter = true,
                modifier = Modifier.settingsCard(index = 4, count = PARTS),
                onClick = { update { copy(showPinButtons = it) } },
            )
            SettingsHeader(stringResource(R.string.dejavu_sidebar_look_bar_buttons))
            BarButton.entries.forEachIndexed { index, button ->
                SwitchListItem(
                    label = stringResource(button.label),
                    checked = button !in look.hiddenBarButtons,
                    enabled = look.showBottomBar,
                    showSwitchAfter = true,
                    modifier = Modifier.settingsCard(index, BarButton.entries.size),
                    onClick = { shown ->
                        update {
                            copy(hiddenBarButtons = if (shown) hiddenBarButtons - button else hiddenBarButtons + button)
                        }
                    },
                )
            }
            IconListItem(
                label = stringResource(R.string.dejavu_sidebar_look_reset),
                beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_arrow_counter_clockwise_24),
                modifier = Modifier.padding(top = 16.dp).settingsCard(index = 0, count = 1),
                onClick = settings::resetSidebarLook,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private const val PARTS = 5
private const val SIZE_SLIDERS = 3

private val BarButton.label: Int
    get() = when (this) {
        BarButton.DOWNLOADS -> R.string.dejavu_downloads
        BarButton.EXTENSIONS -> R.string.dejavu_extensions
        BarButton.BOOKMARKS -> R.string.dejavu_bookmarks
        BarButton.HISTORY -> R.string.dejavu_history
    }

/** A size in dp of [range], chosen with a slider, with the value beside its label. It is setting [index] of a card. */
@Composable
private fun SizeSlider(@StringRes label: Int, value: Int, range: IntRange, index: Int, onChange: (Int) -> Unit) {
    // Padded as the list items of the other cards are.
    val padding = PaddingValues(
        start = FirefoxTheme.layout.space.dynamic200,
        top = FirefoxTheme.layout.space.static150,
        end = FirefoxTheme.layout.space.dynamic200,
    )
    Column(modifier = Modifier.settingsCard(index, SIZE_SLIDERS).padding(padding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(label),
                style = FirefoxTheme.typography.subtitle1,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.dejavu_sidebar_look_dp, value),
                style = FirefoxTheme.typography.body2,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { picked -> picked.roundToInt().takeIf { it != value }?.let(onChange) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}
