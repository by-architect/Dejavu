/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.menu.MoreMenuEntry

/**
 * Arranges the buttons beside the address bar, of the actions bar below pages and of the browser's "More" menu, all
 * picked from the menu's own items, every tab action and the custom actions. The toolbar's reset button puts them all
 * back as they come.
 */
class DejavuMoreMenuFragment : DejavuComposeFragment(R.string.dejavu_settings_more_menu), MenuProvider {
    private var resets by mutableIntStateOf(0)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.dejavu_more_menu_settings, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId != R.id.dejavu_more_menu_reset) return false
        val settings = dejavuSettings()
        settings.resetMoreMenu()
        settings.resetActionsBar()
        settings.resetAddressBarButtons()
        resets++
        return true
    }

    @Composable
    override fun DejavuScreen() {
        val settings = remember { dejavuSettings() }
        // A reset starts the editor again from the saved buttons.
        key(resets) {
            MoreMenuLinesEditor(settings = settings)
        }
    }
}

internal val MoreMenuEntry.icon: Int
    get() = when (this) {
        is MoreMenuEntry.BuiltIn -> item.icon
        is MoreMenuEntry.Tab -> action.icon
        is MoreMenuEntry.Custom -> iconsR.drawable.mozac_ic_lightning_24
        MoreMenuEntry.Home -> R.drawable.dejavu_ic_home_24
        MoreMenuEntry.Search -> R.drawable.dejavu_ic_search_24
    }

internal val MoreMenuEntry.label: String
    @Composable get() = when (this) {
        is MoreMenuEntry.BuiltIn -> stringResource(item.label)
        is MoreMenuEntry.Tab -> stringResource(action.label)
        is MoreMenuEntry.Custom -> action.name
        MoreMenuEntry.Home -> stringResource(R.string.dejavu_actions_bar_home)
        MoreMenuEntry.Search -> stringResource(R.string.dejavu_actions_bar_search)
    }
