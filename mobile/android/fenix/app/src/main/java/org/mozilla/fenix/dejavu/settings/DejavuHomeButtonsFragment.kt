/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.IconListItem
import org.mozilla.fenix.dejavu.home.HomeButton
import org.mozilla.fenix.dejavu.home.HomeButtons
import org.mozilla.fenix.dejavu.home.HomeSlot
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialog
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton

/**
 * Chooses where each button of the home screen's bars shows: before or after the search, before or after the
 * workspaces, in the menu of a "More" button, or nowhere, so the workspaces get the room the user wants. A picture of
 * the bars on top shows the result. The toolbar's reset button puts every button back where it came.
 */
class DejavuHomeButtonsFragment : DejavuComposeFragment(R.string.dejavu_settings_home_buttons), MenuProvider {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.dejavu_more_menu_settings, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId != R.id.dejavu_more_menu_reset) return false
        dejavuSettings().resetHomeButtons()
        return true
    }

    @Composable
    override fun DejavuScreen() {
        val settings = remember { dejavuSettings() }
        val buttons by settings.homeButtons.collectAsState()
        val searchAtBottom by settings.homeSearchAtBottom.collectAsState()
        var choosing by remember { mutableStateOf<HomeButton?>(null) }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsHint(stringResource(R.string.dejavu_home_buttons_hint))
            BarsPicture(buttons = buttons, searchAtBottom = searchAtBottom)
            HomeSlot.entries.forEach { slot ->
                val inSlot = buttons.buttonsIn(slot)
                if (inSlot.isEmpty()) return@forEach
                SettingsHeader(stringResource(slot.label))
                inSlot.forEachIndexed { index, button ->
                    IconListItem(
                        label = stringResource(button.label),
                        beforeIconPainter = painterResource(button.icon),
                        modifier = Modifier.settingsCard(index, inSlot.size),
                        onClick = { choosing = button },
                        afterListAction = {
                            MoveButtons(
                                earlier = buttons.moved(button, -1)?.let { moved -> { settings.setHomeButtons(moved) } },
                                later = buttons.moved(button, 1)?.let { moved -> { settings.setHomeButtons(moved) } },
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        choosing?.let { button ->
            SlotDialog(
                button = button,
                slot = buttons.slotOf(button),
                onChoose = { slot -> settings.setHomeButtons(buttons.with(button, slot)) },
                onDismiss = { choosing = null },
            )
        }
    }
}

/** The arrows that move a button earlier or later among the buttons of its place; a `null` one is greyed out. */
@Composable
private fun MoveButtons(earlier: (() -> Unit)?, later: (() -> Unit)?) {
    Row {
        IconButton(onClick = { earlier?.invoke() }, enabled = earlier != null) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_chevron_up_24),
                contentDescription = stringResource(R.string.dejavu_home_buttons_move_earlier),
                modifier = Modifier.alpha(if (earlier != null) 1f else DISABLED_ALPHA),
            )
        }
        IconButton(onClick = { later?.invoke() }, enabled = later != null) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_chevron_down_24),
                contentDescription = stringResource(R.string.dejavu_home_buttons_move_later),
                modifier = Modifier.alpha(if (later != null) 1f else DISABLED_ALPHA),
            )
        }
    }
}

/** Chooses the place of [button], now in [slot]. Settings cannot be hidden. */
@Composable
private fun SlotDialog(button: HomeButton, slot: HomeSlot, onChoose: (HomeSlot) -> Unit, onDismiss: () -> Unit) {
    DejavuDialog(
        title = stringResource(button.label),
        icon = button.icon,
        onDismissRequest = onDismiss,
        buttons = {
            DejavuDialogButton(
                text = stringResource(R.string.dejavu_done),
                onClick = onDismiss,
                style = DejavuButtonStyle.Primary,
            )
        },
    ) {
        HomeSlot.entries.forEach { option ->
            val enabled = option != HomeSlot.HIDDEN || button.canHide
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = option == slot, enabled = enabled, role = Role.RadioButton) {
                        onChoose(option)
                    }
                    .alpha(if (enabled) 1f else DISABLED_ALPHA)
                    .padding(vertical = 2.dp),
            ) {
                RadioButton(selected = option == slot, onClick = null, enabled = enabled)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(option.label),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!enabled) {
                        Text(
                            text = stringResource(R.string.dejavu_home_buttons_settings_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** A small picture of the home screen's two bars as [buttons] lay them out, the search's bar below when [searchAtBottom]. */
@Composable
private fun BarsPicture(buttons: HomeButtons, searchAtBottom: Boolean) {
    val bars: List<@Composable () -> Unit> = listOf(
        { SearchBarPicture(buttons) },
        { WorkspaceBarPicture(buttons) },
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
    ) {
        (if (searchAtBottom) bars.reversed() else bars).forEach { it() }
    }
}

@Composable
private fun SearchBarPicture(buttons: HomeButtons) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(PictureBarHeight)) {
        buttons.buttonsIn(HomeSlot.SEARCH_START).forEach { PictureIcon(it.icon) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
                .height(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 10.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.dejavu_ic_search_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.dejavu_search_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        buttons.buttonsIn(HomeSlot.SEARCH_END).forEach { PictureIcon(it.icon) }
        if (buttons.buttonsIn(HomeSlot.MENU).isNotEmpty()) PictureIcon(iconsR.drawable.mozac_ic_ellipsis_vertical_24)
    }
}

@Composable
private fun WorkspaceBarPicture(buttons: HomeButtons) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(PictureBarHeight)) {
        buttons.buttonsIn(HomeSlot.WORKSPACES_START).forEach { PictureIcon(it.icon) }
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            repeat(PICTURE_WORKSPACES) { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(if (index == 0) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = if (index == 0) 1f else INACTIVE_DOT_ALPHA),
                        ),
                )
            }
        }
        buttons.buttonsIn(HomeSlot.WORKSPACES_END).forEach { PictureIcon(it.icon) }
    }
}

@Composable
private fun PictureIcon(icon: Int) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(PictureBarHeight)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
    }
}

private val PictureBarHeight = 32.dp
private const val PICTURE_WORKSPACES = 5
private const val INACTIVE_DOT_ALPHA = 0.45f
private const val DISABLED_ALPHA = 0.38f
