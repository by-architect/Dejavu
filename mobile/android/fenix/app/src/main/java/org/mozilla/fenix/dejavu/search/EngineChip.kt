/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R

/**
 * The engine picked with "@name" at the start of a search field: its icon, its name when [showName], and an x that
 * calls [onRemove].
 */
@Composable
fun EngineChip(engine: SearchEngine, showName: Boolean, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val icon = remember(engine.icon) { engine.icon.asImageBitmap() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(scheme.secondaryContainer, CircleShape)
            .padding(start = 8.dp),
    ) {
        Image(
            bitmap = icon,
            contentDescription = engine.name.takeUnless { showName },
            modifier = Modifier.size(ICON_SIZE.dp).clip(RoundedCornerShape(4.dp)),
        )
        if (showName) {
            Text(
                text = engine.name,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp).widthIn(max = NAME_MAX_WIDTH.dp),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(REMOVE_SIZE.dp).clip(CircleShape).clickable(onClick = onRemove),
        ) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_cross_20),
                contentDescription = stringResource(R.string.dejavu_engine_chip_remove, engine.name),
                tint = scheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private const val ICON_SIZE = 18
private const val NAME_MAX_WIDTH = 120
private const val REMOVE_SIZE = 32
