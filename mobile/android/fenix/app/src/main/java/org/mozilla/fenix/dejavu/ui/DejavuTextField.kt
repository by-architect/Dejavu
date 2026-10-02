/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * A text field in Dejavu's look: a rounded well, sunk a little below the dialog or screen around it, with [label]
 * inside it and a hairline edge that turns gold while typing. See [DejavuPopup] for the dialogs it goes in.
 */
@Composable
fun DejavuTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val interactionSource = remember { MutableInteractionSource() }
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fieldEdge(interactionSource),
        textStyle = textStyle,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        interactionSource = interactionSource,
        shape = FieldShape,
        colors = dejavuTextFieldColors(),
    )
}

/** A [DejavuTextField] for a [TextFieldValue], for fields that also need the selection, like inserting at the cursor. */
@Suppress("LongParameterList")
@Composable
fun DejavuTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val interactionSource = remember { MutableInteractionSource() }
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fieldEdge(interactionSource),
        textStyle = textStyle,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = FieldShape,
        colors = dejavuTextFieldColors(),
    )
}

@Composable
private fun Modifier.fieldEdge(interactionSource: InteractionSource): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    val color by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.primary else DejavuPopup.edgeColor,
        label = "fieldEdge",
    )
    return border(if (focused) FOCUSED_EDGE else 1.dp, color, FieldShape)
}

@Composable
private fun dejavuTextFieldColors(): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    val well = scheme.surfaceContainerLowest
    return TextFieldDefaults.colors(
        focusedContainerColor = well,
        unfocusedContainerColor = well,
        disabledContainerColor = well.copy(alpha = DISABLED_ALPHA),
        errorContainerColor = well,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        errorIndicatorColor = Color.Transparent,
        cursorColor = scheme.primary,
        focusedLabelColor = scheme.primary,
        unfocusedLabelColor = scheme.onSurfaceVariant,
        focusedPlaceholderColor = scheme.onSurfaceVariant.copy(alpha = PLACEHOLDER_ALPHA),
        unfocusedPlaceholderColor = scheme.onSurfaceVariant.copy(alpha = PLACEHOLDER_ALPHA),
    )
}

private val FieldShape: Shape = RoundedCornerShape(16.dp)
private val FOCUSED_EDGE = 1.5.dp
private const val DISABLED_ALPHA = 0.5f
private const val PLACEHOLDER_ALPHA = 0.6f
