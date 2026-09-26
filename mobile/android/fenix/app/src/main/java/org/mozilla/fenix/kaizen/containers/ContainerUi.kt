/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.containers

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.state.ContainerState
import org.mozilla.fenix.R

/** Icon of a container, taken from Firefox desktop. */
@get:DrawableRes
val ContainerState.Icon.drawable: Int
    get() = when (this) {
        ContainerState.Icon.FINGERPRINT -> R.drawable.kaizen_container_fingerprint
        ContainerState.Icon.BRIEFCASE -> R.drawable.kaizen_container_briefcase
        ContainerState.Icon.DOLLAR -> R.drawable.kaizen_container_dollar
        ContainerState.Icon.CART -> R.drawable.kaizen_container_cart
        ContainerState.Icon.CIRCLE -> R.drawable.kaizen_container_circle
        ContainerState.Icon.GIFT -> R.drawable.kaizen_container_gift
        ContainerState.Icon.VACATION -> R.drawable.kaizen_container_vacation
        ContainerState.Icon.FOOD -> R.drawable.kaizen_container_food
        ContainerState.Icon.FRUIT -> R.drawable.kaizen_container_fruit
        ContainerState.Icon.PET -> R.drawable.kaizen_container_pet
        ContainerState.Icon.TREE -> R.drawable.kaizen_container_tree
        ContainerState.Icon.CHILL -> R.drawable.kaizen_container_chill
        ContainerState.Icon.FENCE -> R.drawable.kaizen_container_fence
    }

val ContainerColor.color: Color
    get() = Color(argb)

/** Draws [record]'s icon in its color. */
@Composable
fun ContainerIcon(
    record: ContainerRecord,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    Icon(
        painter = painterResource(record.icon.drawable),
        contentDescription = record.name,
        tint = record.color.color,
        modifier = modifier.size(size),
    )
}

/** Stands for "no container" wherever containers can be picked, so it never looks like one of them. */
@Composable
fun NoContainerIcon(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    Icon(
        painter = painterResource(R.drawable.kaizen_ic_no_container_24),
        contentDescription = stringResource(R.string.kaizen_workspace_no_container),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(size),
    )
}
