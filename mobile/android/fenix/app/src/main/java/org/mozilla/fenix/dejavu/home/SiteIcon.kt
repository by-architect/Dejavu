/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import mozilla.components.browser.icons.Icon.Source
import mozilla.components.browser.icons.compose.Placeholder
import mozilla.components.browser.icons.compose.WithIcon
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.components.components

/**
 * The icon of the site at [url], round. Until the site's own icon is known, as for tabs synced from other devices
 * that never loaded here, a globe stands in for it instead of a made up letter.
 */
@Composable
internal fun SiteIcon(url: String, size: Dp, modifier: Modifier = Modifier) {
    components.core.icons.LoadableImage(url = url) {
        Placeholder { DefaultSiteIcon(size, modifier) }
        WithIcon { icon ->
            if (icon.source == Source.GENERATOR) {
                DefaultSiteIcon(size, modifier)
            } else {
                Image(
                    painter = icon.painter,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = modifier.size(size).clip(CircleShape),
                )
            }
        }
    }
}

@Composable
private fun DefaultSiteIcon(size: Dp, modifier: Modifier) {
    Icon(
        painter = painterResource(iconsR.drawable.mozac_ic_globe_24),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(size),
    )
}
