/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.actions

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.mozilla.fenix.R

/** The ways the row of a tab on the home screen can be swiped, each running the action the user chose for it. */
enum class SwipeDirection(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    RIGHT(R.string.dejavu_settings_swipe_right, R.drawable.dejavu_ic_swipe_right_24),
    LEFT(R.string.dejavu_settings_swipe_left, R.drawable.dejavu_ic_swipe_left_24),
}

/**
 * The actions swiping the row of a tab runs, one for each [SwipeDirection]. Without one, swiping that way turns to the
 * next workspace, as swiping outside the tabs does.
 */
data class SwipeActions(val right: RowAction? = null, val left: RowAction? = null) {
    fun of(direction: SwipeDirection): RowAction? = when (direction) {
        SwipeDirection.RIGHT -> right
        SwipeDirection.LEFT -> left
    }
}

/** Whether this action can run from swiping the row of a tab, pinned or not. */
val RowAction.swipes: Boolean
    get() = fits(ActionPlace.PINNED_ROWS) || fits(ActionPlace.UNPINNED_ROWS)

/** Every action a swipe can run, the user's own included, in display order. */
fun swipeChoices(customActions: List<CustomAction>): List<RowAction> = RowAction.all(customActions).filter { it.swipes }

/** The action of [swipeChoices] with [key], or `null` when there is none. */
fun swipeActionOf(key: String?, customActions: List<CustomAction>): RowAction? =
    key?.let { swipeChoices(customActions).firstOrNull { action -> action.key == it } }
