/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import org.mozilla.fenix.R
import org.mozilla.fenix.settings.settingssearch.SettingsIndexer
import org.mozilla.fenix.settings.settingssearch.SettingsSearchItem
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Shows the categories of the settings beside them while the window is wide, in a list on the left with a search field
 * on top. A phone, or a window narrower than [SettingsLayout.WIDE_MIN_WIDTH_DP] such as one of a split screen, keeps
 * the single column of Settings.
 *
 * Settings and the screens below it stay where they are in the navigation of the app, so every way into them and
 * every link to them keeps working. The list is not part of any of them: it sits beside the host of the screens for as
 * long as one of the settings screens is on top, and the toolbar above both keeps the Back button of the screen.
 *
 * @param activity The activity that shows the screens.
 * @param navController Navigates between the screens.
 * @param root The layout of the activity, which holds the toolbar and the [R.id.container] the screens are in.
 * @param indexer What the search finds the settings with.
 */
class SettingsPane(
    private val activity: AppCompatActivity,
    private val navController: NavController,
    private val root: FrameLayout,
    private val indexer: SettingsIndexer,
) {
    private val screens: View = root.findViewById(R.id.container)
    private val density = activity.resources.displayMetrics.density
    private val paneWidth = (SettingsLayout.PANE_WIDTH_DP * density).toInt()
    private val selected = mutableStateOf(SettingsCategory.entries.first())
    private val pages = SettingsCategoryPages(::isWide, ::chosenFor)

    // The category picked in the list, and the entry of Settings it was picked on. Settings stays where it is while
    // the category changes, so the screen never passes through the one below it.
    private var chosen: Pair<String, SettingsCategory>? = null
    private var pane: ComposeView? = null

    /** Starts to follow the navigation and the size of the window. Call it once, when the activity is created. */
    fun install() {
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(pages, true)
        // The search of the list finds the settings that were indexed, which only Firefox's search screen does.
        activity.lifecycleScope.launch { indexer.indexAllSettings() }
        navController.addOnDestinationChangedListener { _, _, _ -> refresh() }
        activity.registerComponentCallbacks(
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) = refresh()

                @Deprecated("Part of ComponentCallbacks, which has nothing to free here.")
                override fun onLowMemory() = Unit
            },
        )
        screens.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ -> alignWithScreens(top) }
    }

    private fun isWide() = SettingsLayout.isWide(activity.resources.configuration.screenWidthDp)

    private fun refresh() {
        update()
        // The back stack of the navigation may not hold the screen that just opened yet while it announces it.
        screens.post(::update)
    }

    private fun update() {
        val category = if (isWide()) selection(steps()) else null
        if (category != null) selected.value = category
        setShown(category != null)
        pages.update()
    }

    private fun steps(): List<Step> = navController.currentBackStack.value.asReversed().map { entry ->
        val ids = generateSequence(entry.destination) { it.parent }.map { it.id }.toList()
        val picked = chosen?.takeIf { it.first == entry.id }?.second
        Step(ids, picked?.name ?: categoryName(entry.arguments))
    }

    private fun categoryName(arguments: Bundle?): String = categoryOf(arguments, activity::getString).name

    private fun setShown(shown: Boolean) {
        val params = screens.layoutParams as? LinearLayout.LayoutParams ?: return
        val inset = if (shown) paneWidth else 0
        if (params.marginStart != inset) {
            params.marginStart = inset
            screens.layoutParams = params
        }
        if (shown) {
            ensurePane().visibility = View.VISIBLE
            alignWithScreens(screens.top)
        } else {
            pane?.visibility = View.GONE
        }
    }

    // The list starts below the toolbar, where the screens do.
    private fun alignWithScreens(top: Int) {
        val view = pane ?: return
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.topMargin != top) {
            params.topMargin = top
            view.layoutParams = params
        }
    }

    private fun ensurePane(): ComposeView = pane ?: ComposeView(activity).apply {
        layoutParams = FrameLayout.LayoutParams(paneWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            FirefoxTheme {
                SettingsPaneContent(
                    selected = selected.value,
                    search = indexer::getSettingsWithQuery,
                    categoryOf = ::categoryOfResult,
                    onSelect = ::select,
                    onOpen = ::open,
                )
            }
        }
        root.addView(this)
        pane = this
    }

    private fun categoryOfResult(item: SettingsSearchItem): SettingsCategory =
        SettingsCategories.categoryOfScreen(item.preferenceFileInformation.fragmentId)
            ?: SettingsCategories.categoryOfKey(item.preferenceKey, activity::getString)

    private fun settingsEntry() = runCatching { navController.getBackStackEntry(R.id.settingsFragment) }.getOrNull()

    private fun chosenFor(): SettingsCategory? {
        val entry = settingsEntry() ?: return null
        return chosen?.takeIf { it.first == entry.id }?.second
    }

    /**
     * Shows the settings of [category] on the main Settings screen. The screen stays and only changes what it shows,
     * so nothing is navigated away from, shown in between or animated.
     */
    private fun select(category: SettingsCategory) {
        val entry = settingsEntry()
        if (entry == null) {
            navController.navigate(
                R.id.settingsFragment,
                Bundle().apply { putString(ARG_CATEGORY, category.name) },
            )
            return
        }
        chosen = entry.id to category
        // Screens opened below Settings from the category go; Settings itself stays.
        navController.popBackStack(R.id.settingsFragment, false)
        selected.value = category
        pages.reload()
        refresh()
    }

    private fun open(item: SettingsSearchItem) = SettingsSearch.open(navController, item, activity::getString)

    /** What one screen of the navigation tells: the ids it and its graphs have, and the category it was opened for. */
    internal data class Step(val ids: List<Int>, val category: String? = null)

    companion object {
        /** The argument that tells the main Settings screen which category to show on a wide window. */
        const val ARG_CATEGORY = "dejavu_settings_category"

        private const val ARG_PREFERENCE_TO_SCROLL_TO = "preference_to_scroll_to"

        // Where Settings is not shown: the search of Settings has the whole window, and so do pages and the home.
        private val OUTSIDE = listOf(R.id.settingsSearchFragment, R.id.homeFragment, R.id.browserFragment)

        /**
         * The category the main Settings screen shows for its [arguments]: the one it was opened for, else the one of
         * the setting it was opened to scroll to, else the first.
         */
        internal fun categoryOf(arguments: Bundle?, resolve: (Int) -> String): SettingsCategory {
            arguments?.getString(ARG_CATEGORY)?.let { return SettingsCategory.fromName(it) }
            val key = arguments?.getString(ARG_PREFERENCE_TO_SCROLL_TO) ?: return SettingsCategory.entries.first()
            return SettingsCategories.categoryOfKey(key, resolve)
        }

        /**
         * The category to highlight while one of the screens of Settings is on top, or `null` when none is. [steps]
         * are the screens of the back stack, the one on top first: the settings screen below the main one belong to
         * their own category, and the main one to the category it shows.
         */
        internal fun selection(steps: List<Step>): SettingsCategory? {
            var screen: SettingsCategory? = null
            for (step in steps) {
                if (step.ids.any { it in OUTSIDE }) return null
                if (R.id.settingsFragment in step.ids) return screen ?: SettingsCategory.fromName(step.category)
                screen = screen ?: step.ids.firstNotNullOfOrNull(SettingsCategories::categoryOfScreen)
            }
            return null
        }
    }
}
