/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

private val OuterRadius = 24.dp
private val InnerRadius = 6.dp

/**
 * Draws the setting at [index] of a group of [count] settings as part of one rounded card, the way
 * [DejavuPreferenceCards] shows the settings screens of Firefox.
 */
@Composable
fun Modifier.settingsCard(index: Int, count: Int): Modifier {
    val first = index == 0
    val last = index == count - 1
    val top = if (first) OuterRadius else InnerRadius
    val bottom = if (last) OuterRadius else InnerRadius
    return padding(start = 16.dp, end = 16.dp, top = if (first) 0.dp else 2.dp, bottom = if (last) 8.dp else 0.dp)
        .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
}
