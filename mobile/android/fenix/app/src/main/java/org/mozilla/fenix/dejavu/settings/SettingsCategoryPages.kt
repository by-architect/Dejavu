/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.RecyclerView
import java.util.WeakHashMap
import org.mozilla.fenix.R
import org.mozilla.fenix.settings.SettingsFragment

/**
 * Turns the main Settings screen into the page of a category while the window is wide: the settings of the other
 * categories are hidden, and the links to the screens that open from elsewhere are added. The screen is the same one
 * a phone shows, so every setting on it keeps working as it does there, and Firefox's code stays as it is.
 *
 * @param isWide Whether the window is wide enough to show the categories beside the settings.
 */
internal class SettingsCategoryPages(
    private val isWide: () -> Boolean,
    private val chosen: () -> SettingsCategory? = { null },
) : FragmentManager.FragmentLifecycleCallbacks() {
    /** What a page changed on its screen, so it can be put back when the window gets narrow. */
    private class Page {
        val hidden = mutableSetOf<Preference>()
        val titles = mutableMapOf<PreferenceGroup, CharSequence?>()
        var shortcuts: PreferenceCategory? = null
        var working = false

        /** The animator the list had before the page switched it off, put back with the page. */
        var animator: RecyclerView.ItemAnimator? = null
        var animatorOff = false
    }

    private val pages = WeakHashMap<SettingsFragment, Page>()

    override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
        val fragment = f as? SettingsFragment ?: return
        pages.getOrPut(fragment) { Page() }
        // Firefox shows and hides settings itself, for example once an account has loaded, so the page is made again
        // whenever the list changes.
        fragment.listView?.adapter?.registerAdapterDataObserver(
            object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() {
                    if (isWide()) fragment.listView?.post { show(fragment) }
                }
            },
        )
    }

    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
        val fragment = f as? SettingsFragment ?: return
        show(fragment)
        if (isWide()) {
            (fragment.activity as? AppCompatActivity)?.title = fragment.getString(categoryOf(fragment).title)
        }
    }

    /** Shows or puts back the page of every main Settings screen there is, after the window changed its width. */
    fun update() = pages.keys.toList().forEach(::show)

    /** Makes the pages again for the category that was picked, and names the screen after it. */
    fun reload() {
        pages.keys.toList().forEach { fragment ->
            pages[fragment]?.let { page -> fragment.preferenceScreen?.let { restore(it, page) } }
            show(fragment)
            if (isWide()) {
                (fragment.activity as? AppCompatActivity)?.title = fragment.getString(categoryOf(fragment).title)
            }
        }
    }

    private fun categoryOf(fragment: SettingsFragment): SettingsCategory =
        chosen() ?: SettingsPane.categoryOf(fragment.arguments, fragment::getString)

    private fun show(fragment: SettingsFragment) {
        val screen = fragment.preferenceScreen ?: return
        val page = pages.getOrPut(fragment) { Page() }
        if (page.working || fragment.context == null) return
        page.working = true
        try {
            if (isWide()) {
                filter(fragment, screen, page)
            } else {
                restore(screen, page)
                unmute(fragment, page)
            }
        } finally {
            page.working = false
        }
    }

    private fun filter(fragment: SettingsFragment, screen: PreferenceGroup, page: Page) {
        val category = categoryOf(fragment)
        mute(fragment, page)
        // A setting Firefox showed again after the page hid it is the page's to hide again.
        page.hidden.removeAll { it.isVisible }
        if (page.shortcuts == null) page.shortcuts = addShortcuts(fragment, screen, category)
        keep(screen, page, category, fragment::getString)
    }

    /** Hides what is not [category]'s in [group], and returns whether anything in it is left to show. */
    private fun keep(group: PreferenceGroup, page: Page, category: SettingsCategory, resolve: (Int) -> String): Boolean {
        var shown = false
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            val keeps = when {
                preference === page.shortcuts -> true
                preference is PreferenceGroup -> keep(preference, page, category, resolve)
                else -> preference.isVisible && preference.key != null &&
                    SettingsCategories.categoryOfKey(preference.key, resolve) == category
            }
            shown = shown || keeps
            if (keeps && preference is PreferenceGroup) {
                // Titles of the sections Firefox groups its settings in would only repeat the category.
                page.titles.getOrPut(preference) { preference.title }
                preference.title = null
            }
            if (keeps && page.hidden.remove(preference)) {
                preference.isVisible = true
            } else if (!keeps && preference.isVisible) {
                preference.isVisible = false
                page.hidden.add(preference)
            }
        }
        return shown
    }

    /**
     * Switching a category hides and shows whole lists of settings, which the animator of the list would fade and
     * slide one by one. Picking a category is frequent, so the settings change at once instead.
     */
    private fun mute(fragment: SettingsFragment, page: Page) {
        val list = fragment.listView ?: return
        if (page.animatorOff) return
        page.animator = list.itemAnimator
        page.animatorOff = true
        list.itemAnimator = null
    }

    private fun unmute(fragment: SettingsFragment, page: Page) {
        if (!page.animatorOff) return
        fragment.listView?.itemAnimator = page.animator
        page.animator = null
        page.animatorOff = false
    }

    private fun restore(screen: PreferenceGroup, page: Page) {
        page.hidden.forEach { it.isVisible = true }
        page.hidden.clear()
        page.titles.forEach { (group, title) -> group.title = title }
        page.titles.clear()
        page.shortcuts?.let(screen::removePreference)
        page.shortcuts = null
    }

    private fun addShortcuts(
        fragment: SettingsFragment,
        screen: PreferenceGroup,
        category: SettingsCategory,
    ): PreferenceCategory? =
        PreferenceCategory(fragment.requireContext()).takeIf { hasShortcuts(category) }?.apply {
            layoutResource = R.layout.preference_category_no_icon_style
            isIconSpaceReserved = false
            key = SHORTCUTS_KEY
            screen.addPreference(this)
            SettingsCategories.shortcutsOf(category).forEach { shortcut ->
                addPreference(
                    Preference(fragment.requireContext()).apply {
                        key = "$SHORTCUTS_KEY${shortcut.destination}"
                        isIconSpaceReserved = false
                        setTitle(shortcut.title)
                        setSummary(shortcut.summary)
                        setOnPreferenceClickListener {
                            val navController = fragment.findNavController()
                            // A second tap while the screen slides in would open it twice.
                            if (navController.currentDestination?.id == R.id.settingsFragment) {
                                navController.slideTo(shortcut.destination)
                            }
                            true
                        }
                    },
                )
            }
        }

    private fun hasShortcuts(category: SettingsCategory) = SettingsCategories.shortcutsOf(category).isNotEmpty()

    private companion object {
        const val SHORTCUTS_KEY = "dejavu_shortcuts_"
    }
}
