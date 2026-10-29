/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.mozilla.fenix.theme.FirefoxTheme

// Where the text of a header and of a choice starts, as on Firefox's settings screens made of preferences.
private val HEADER_START = 72.dp
private val ROW_HEIGHT = 48.dp
private val RADIO_START = 8.dp
private val RADIO_GAP = 16.dp
private val ROW_END = 16.dp
private val ROW_VERTICAL = 12.dp
private val LEADING_GAP = 12.dp

/**
 * The title of a group of settings, placed and colored as the category titles of Firefox's settings screens are:
 * in line with the title of the settings toolbar.
 */
@Composable
internal fun SettingsHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = FirefoxTheme.typography.headline8,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(start = HEADER_START, end = ROW_END)
            .semantics { heading() },
    )
}

/** A few lines that tell what a settings screen, or the settings around them, do. */
@Composable
internal fun SettingsHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(16.dp),
    )
}

/**
 * A choice of a settings screen, a radio button with [label] and maybe a [leading] icon, laid out as the choices of
 * Firefox's settings screens are.
 */
@Composable
internal fun SettingsChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = RADIO_START, end = ROW_END),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.width(ROW_HEIGHT))
        Spacer(Modifier.width(RADIO_GAP))
        if (leading != null) {
            leading()
            Spacer(Modifier.width(LEADING_GAP))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = ROW_VERTICAL),
        )
    }
}
