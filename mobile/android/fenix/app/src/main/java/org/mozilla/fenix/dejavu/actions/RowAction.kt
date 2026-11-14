/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.actions

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.workspaces.SplitOrientation

/** An action that can run on tabs: one of Dejavu's [TabAction]s or a [CustomAction] the user defined. */
sealed interface RowAction {
    /** Stable identifier used to persist the user's choice of row buttons. */
    val key: String

    data class BuiltIn(val action: TabAction) : RowAction {
        override val key: String
            get() = action.key
    }

    data class Custom(val action: CustomAction) : RowAction {
        override val key: String
            get() = keyOf(action.id)
    }

    companion object {
        fun keyOf(customActionId: String) = "custom:$customActionId"

        /**
         * Every action, the user's own included, in display order: Dejavu's actions, then the custom ones, then Delete
         * and Close so they stay the last buttons.
         */
        fun all(customActions: List<CustomAction>): List<RowAction> {
            val (last, first) = TabAction.ordered.partition { it == TabAction.DELETE || it == TabAction.CLOSE }
            return first.map { BuiltIn(it) } + customActions.map { Custom(it) } + last.map { BuiltIn(it) }
        }

        /** The actions of [all] that can do something in [place]. */
        fun available(place: ActionPlace, customActions: List<CustomAction>): List<RowAction> =
            all(customActions).filter { it.fits(place) }
    }
}

/** Whether this action can do something in [place]. Custom actions work everywhere. */
fun RowAction.fits(place: ActionPlace): Boolean = when (this) {
    is RowAction.BuiltIn -> place in action.places
    is RowAction.Custom -> true
}

@get:DrawableRes
val RowAction.icon: Int
    get() = when (this) {
        is RowAction.BuiltIn -> action.icon
        is RowAction.Custom -> action.icon
    }

/** The icon of a custom action's buttons: Share's for one that shares to an app, a lightning bolt for the others. */
@get:DrawableRes
val CustomAction.icon: Int
    get() = if (share != null) R.drawable.dejavu_ic_share_24 else iconsR.drawable.mozac_ic_lightning_24

val RowAction.label: String
    @Composable get() = when (this) {
        is RowAction.BuiltIn -> stringResource(action.label)
        is RowAction.Custom -> action.name
    }

/**
 * These actions as shown on a screen with room to split the [ways] given: Split view becomes one action for each way
 * when there is room for both, and goes away when there is room for none.
 */
fun List<RowAction>.forSplitWays(ways: List<SplitOrientation>): List<RowAction> = flatMap { action ->
    when {
        action != RowAction.BuiltIn(TabAction.SPLIT_VIEW) -> listOf(action)
        ways.size > 1 -> ways.map { RowAction.BuiltIn(it.tabAction) }
        else -> listOf(action).takeIf { ways.isNotEmpty() }.orEmpty()
    }
}

/** The action that splits the browser this way. */
val SplitOrientation.tabAction: TabAction
    get() = when (this) {
        SplitOrientation.TOP_AND_BOTTOM -> TabAction.SPLIT_TOP_AND_BOTTOM
        SplitOrientation.SIDE_BY_SIDE -> TabAction.SPLIT_SIDE_BY_SIDE
    }
