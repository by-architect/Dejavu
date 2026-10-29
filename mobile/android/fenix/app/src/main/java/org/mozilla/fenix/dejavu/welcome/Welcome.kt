/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.welcome

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.components.accounts.FenixFxAEntryPoint
import org.mozilla.fenix.dejavu.settings.dejavuSettings
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpapers
import org.mozilla.fenix.ext.hideToolbar
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * The welcome pages a fresh install of Dejavu starts with, apart from the feature tour, which follows them: a few
 * choices to set Dejavu up, each kept as soon as it is made. About opens them again.
 */
object Welcome {
    private var openedAtStart = false

    /** Whether the welcome pages are due: on a fresh install, until they were [seen]. An update never shows them. */
    fun isDue(seen: Boolean, updated: Boolean): Boolean = !seen && !updated

    /**
     * Gives the workspace a fresh Dejavu starts with its first look, the gold wallpaper, while it still has the gold
     * theme it starts with and no wallpaper. The workspaces are saved first, so it stays with that workspace.
     */
    fun giveFirstLook(context: Context, repository: WorkspaceRepository, wallpapers: WorkspaceWallpapers) {
        val workspace = repository.state.value.workspaces.singleOrNull() ?: return
        if (workspace.theme != WorkspaceTheme.Gold) return
        repository.save()
        val screen = context.resources.displayMetrics
        wallpapers.giveFirstWallpaper(workspace.id, screen.widthPixels, screen.heightPixels)
    }

    /**
     * Opens the welcome pages when the home screen first shows while the app runs, so that leaving them with Back keeps
     * them away until the next start.
     */
    fun openAtStart(navController: NavController) {
        if (openedAtStart) return
        openedAtStart = true
        open(navController)
    }

    /** Opens the welcome pages, which fade in over the screen shown. */
    fun open(navController: NavController) {
        navController.navigate(
            R.id.dejavu_welcome_graph,
            null,
            navOptions {
                anim {
                    enter = R.anim.fade_in
                    exit = R.anim.fade_out
                    popEnter = R.anim.fade_in
                    popExit = R.anim.fade_out
                }
            },
        )
    }
}

/**
 * Shows the welcome pages. The page shown is kept when the app is recreated, as it is when the theme changes on one of
 * them. Back goes to the page before, and leaves from the first one.
 */
class DejavuWelcomeFragment : Fragment() {
    private var page by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getInt(KEY_PAGE) ?: 0
        requireActivity().onBackPressedDispatcher.addCallback(this) {
            if (page > 0) {
                page--
            } else {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                FirefoxTheme {
                    WelcomeFlow(page = page, onPage = { page = it }, onDone = ::finish)
                }
            }
        }

    override fun onResume() {
        super.onResume()
        hideToolbar()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PAGE, page)
    }

    /** Leaves the welcome pages, for the account's sign-in when [signIn]. */
    private fun finish(signIn: Boolean) {
        dejavuSettings().setWelcomeSeen(true)
        val navController = findNavController()
        navController.popBackStack(R.id.dejavu_welcome_graph, true)
        if (signIn) {
            navController.navigate(
                NavGraphDirections.actionGlobalTurnOnSync(entrypoint = FenixFxAEntryPoint.NewUserOnboarding),
            )
        }
    }

    private companion object {
        const val KEY_PAGE = "page"
    }
}
