/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.actions

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import mozilla.components.ui.icons.R as iconsR

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
        is RowAction.Custom -> iconsR.drawable.mozac_ic_lightning_24
    }

val RowAction.label: String
    @Composable get() = when (this) {
        is RowAction.BuiltIn -> stringResource(action.label)
        is RowAction.Custom -> action.name
    }
