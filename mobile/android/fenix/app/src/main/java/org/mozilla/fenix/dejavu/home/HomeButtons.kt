/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.fenix.R

/**
 * A button of the bars of the home screen.
 *
 * @property key Stable identifier used to persist where it shows.
 * @property genie The page it opens, which pours out of it, or `null` for a button that opens no page of its own.
 */
enum class HomeButton(
    val key: String,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    internal val genie: PageGenie.Button?,
) {
    ACCOUNT("account", R.string.dejavu_account, R.drawable.dejavu_ic_profile_24, PageGenie.Button.ACCOUNT),
    DOWNLOADS("downloads", R.string.dejavu_downloads, R.drawable.dejavu_ic_downloads_24, PageGenie.Button.DOWNLOADS),
    EXTENSIONS("extensions", R.string.dejavu_extensions, R.drawable.dejavu_ic_extensions_24, null),
    BOOKMARKS("bookmarks", R.string.dejavu_bookmarks, R.drawable.dejavu_ic_bookmarks_24, PageGenie.Button.BOOKMARKS),
    HISTORY("history", R.string.dejavu_history, R.drawable.dejavu_ic_history_24, PageGenie.Button.HISTORY),
    SETTINGS("settings", R.string.dejavu_settings, R.drawable.dejavu_ic_settings_24, PageGenie.Button.SETTINGS),
    ;

    /** Whether it can be hidden. Settings cannot, as nothing else on the home screen leads there. */
    val canHide: Boolean
        get() = this != SETTINGS

    companion object {
        fun fromKey(key: String): HomeButton? = entries.firstOrNull { it.key == key }
    }
}

/** Where a [HomeButton] shows on the home screen. */
enum class HomeSlot(val key: String, @param:StringRes val label: Int) {
    /** In the bar of the search, before it. */
    SEARCH_START("search_start", R.string.dejavu_home_slot_search_start),

    /** In the bar of the search, after it. */
    SEARCH_END("search_end", R.string.dejavu_home_slot_search_end),

    /** In the bar of the workspaces, before them. */
    WORKSPACES_START("workspaces_start", R.string.dejavu_home_slot_workspaces_start),

    /** In the bar of the workspaces, after them. */
    WORKSPACES_END("workspaces_end", R.string.dejavu_home_slot_workspaces_end),

    /** In the menu of a "More" button at the end of the bar of the search, which shows while it holds a button. */
    MENU("menu", R.string.dejavu_home_slot_menu),

    /** Nowhere on the home screen. */
    HIDDEN("hidden", R.string.dejavu_home_slot_hidden),
    ;

    companion object {
        fun fromKey(key: String?): HomeSlot? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Where each button of the home screen's bars shows. Buttons sharing a slot show in the order of [order].
 *
 * @property order Every [HomeButton], once.
 * @property slots The slot of every button.
 */
@Immutable
data class HomeButtons(val order: List<HomeButton>, val slots: Map<HomeButton, HomeSlot>) {
    fun slotOf(button: HomeButton): HomeSlot = slots[button] ?: DEFAULT_SLOTS.getValue(button)

    /** The buttons shown in [slot], in their order. */
    fun buttonsIn(slot: HomeSlot): List<HomeButton> = order.filter { slotOf(it) == slot }

    /** These buttons with [button] in [slot]: at the end of what the slot already holds. */
    fun with(button: HomeButton, slot: HomeSlot): HomeButtons {
        if (slot == HomeSlot.HIDDEN && !button.canHide) return this
        val others = order - button
        val lastInSlot = others.lastOrNull { slotOf(it) == slot }
        val index = lastInSlot?.let { others.indexOf(it) + 1 } ?: others.size
        return copy(
            order = others.toMutableList().apply { add(index, button) },
            slots = slots + (button to slot),
        )
    }

    /**
     * These buttons with [button] moved one place earlier ([delta] -1) or later (+1) among the buttons of its slot, or
     * `null` when it is already first or last there.
     */
    fun moved(button: HomeButton, delta: Int): HomeButtons? {
        val inSlot = buttonsIn(slotOf(button))
        val other = inSlot.getOrNull(inSlot.indexOf(button) + delta) ?: return null
        val swapped = order.toMutableList()
        val a = swapped.indexOf(button)
        val b = swapped.indexOf(other)
        swapped[a] = other
        swapped[b] = button
        return copy(order = swapped)
    }

    fun toJson(): String = JSONArray().apply {
        order.forEach { put(JSONObject().put("button", it.key).put("slot", slotOf(it).key)) }
    }.toString()

    companion object {
        private val DEFAULT_SLOTS = mapOf(
            HomeButton.ACCOUNT to HomeSlot.SEARCH_START,
            HomeButton.SETTINGS to HomeSlot.SEARCH_END,
            HomeButton.DOWNLOADS to HomeSlot.WORKSPACES_START,
            HomeButton.EXTENSIONS to HomeSlot.WORKSPACES_START,
            HomeButton.BOOKMARKS to HomeSlot.WORKSPACES_END,
            HomeButton.HISTORY to HomeSlot.WORKSPACES_END,
        )

        /** The bars as Dejavu had them before they could be changed. */
        val DEFAULT = HomeButtons(order = HomeButton.entries.toList(), slots = DEFAULT_SLOTS)

        /**
         * The buttons saved as [json], or `null` without any. A button missing from it, like one added by a later
         * Dejavu, shows where it starts out; one that cannot be hidden but was shows where it starts out too.
         */
        fun fromJson(json: String?): HomeButtons? {
            val array = json?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
            val saved = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val button = HomeButton.fromKey(item.optString("button")) ?: return@mapNotNull null
                val slot = HomeSlot.fromKey(item.optString("slot"))
                    ?.takeUnless { it == HomeSlot.HIDDEN && !button.canHide }
                    ?: DEFAULT_SLOTS.getValue(button)
                button to slot
            }.distinctBy { it.first }
            val order = saved.map { it.first } + HomeButton.entries.filter { button -> saved.none { it.first == button } }
            return HomeButtons(order = order, slots = DEFAULT_SLOTS + saved.toMap())
        }
    }
}
