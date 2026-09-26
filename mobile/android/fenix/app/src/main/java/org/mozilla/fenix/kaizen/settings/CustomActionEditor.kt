/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.fragment.findNavController
import mozilla.components.compose.base.button.FilledButton
import org.mozilla.fenix.R
import org.mozilla.fenix.kaizen.actions.ActionVariable
import org.mozilla.fenix.kaizen.actions.CustomAction
import org.mozilla.fenix.kaizen.actions.CustomHeader
import org.mozilla.fenix.kaizen.actions.HttpMethod
import java.util.UUID
import mozilla.components.ui.icons.R as iconsR

/** Adds or edits a [CustomAction]. */
class KaizenCustomActionFragment : KaizenComposeFragment(R.string.kaizen_custom_action) {
    @Composable
    override fun KaizenScreen() {
        val settings = remember { kaizenSettings() }
        val existing = remember {
            val id = arguments?.getString(ARG_ACTION_ID)
            settings.customActions.value.firstOrNull { it.id == id }
        }
        CustomActionEditor(
            initial = existing,
            onSave = { action ->
                settings.saveCustomAction(action)
                findNavController().popBackStack()
            },
            onDelete = existing?.let { action ->
                {
                    settings.deleteCustomAction(action.id)
                    findNavController().popBackStack()
                }
            },
        )
    }

    companion object {
        /** Id of the custom action to edit; missing to add a new one. */
        const val ARG_ACTION_ID = "actionId"
    }
}

private sealed interface EditorField {
    data object Url : EditorField
    data object Body : EditorField
    data class HeaderName(val index: Int) : EditorField
    data class HeaderValue(val index: Int) : EditorField
}

private data class HeaderFields(val name: TextFieldValue, val value: TextFieldValue)

private const val DEFAULT_BODY = "{\n  \"url\": \"\${websiteurl}\",\n  \"title\": \"\${websitetitle}\"\n}"
private const val VARIABLES_PER_ROW = 2

@Suppress("LongMethod", "CognitiveComplexMethod")
@Composable
private fun CustomActionEditor(
    initial: CustomAction?,
    onSave: (CustomAction) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var method by remember { mutableStateOf(initial?.method ?: HttpMethod.POST) }
    var url by remember { mutableStateOf(TextFieldValue(initial?.url.orEmpty())) }
    var body by remember { mutableStateOf(TextFieldValue(initial?.body ?: DEFAULT_BODY)) }
    val headers = remember {
        mutableStateListOf<HeaderFields>().apply {
            val saved = initial?.headers ?: listOf(CustomHeader("Content-Type", "application/json"))
            addAll(saved.map { HeaderFields(TextFieldValue(it.name), TextFieldValue(it.value)) })
        }
    }
    var focused by remember { mutableStateOf<EditorField>(EditorField.Url) }

    fun insert(token: String) {
        when (val field = focused) {
            EditorField.Url -> url = url.inserting(token)
            EditorField.Body -> body = body.inserting(token)
            is EditorField.HeaderName -> headers.getOrNull(field.index)?.let {
                headers[field.index] = it.copy(name = it.name.inserting(token))
            }
            is EditorField.HeaderValue -> headers.getOrNull(field.index)?.let {
                headers[field.index] = it.copy(value = it.value.inserting(token))
            }
        }
    }

    val trimmedUrl = url.text.trim()
    val isValid = name.isNotBlank() && (trimmedUrl.startsWith("https://") || trimmedUrl.startsWith("http://"))

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text(stringResource(R.string.kaizen_custom_action_name)) },
            modifier = Modifier.fillMaxWidth(),
        )

        SectionLabel(R.string.kaizen_custom_action_method)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HttpMethod.entries.forEach { option ->
                Chip(text = option.name, selected = option == method, onClick = { method = option })
            }
        }

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            label = { Text(stringResource(R.string.kaizen_custom_action_url)) },
            placeholder = { Text("https://example.com/save?url=${ActionVariable.WEBSITE_URL.token}") },
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focused = EditorField.Url },
        )

        SectionLabel(R.string.kaizen_custom_action_headers)
        headers.forEachIndexed { index, header ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = header.name,
                    onValueChange = { headers[index] = header.copy(name = it) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.kaizen_custom_action_header_name)) },
                    modifier = Modifier.weight(1f).onFocusChanged {
                        if (it.isFocused) focused = EditorField.HeaderName(index)
                    },
                )
                OutlinedTextField(
                    value = header.value,
                    onValueChange = { headers[index] = header.copy(value = it) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.kaizen_custom_action_header_value)) },
                    modifier = Modifier.weight(1.4f).onFocusChanged {
                        if (it.isFocused) focused = EditorField.HeaderValue(index)
                    },
                )
                IconButton(onClick = { headers.removeAt(index) }) {
                    Icon(
                        painter = painterResource(iconsR.drawable.mozac_ic_cross_24),
                        contentDescription = stringResource(R.string.kaizen_custom_action_remove_header),
                    )
                }
            }
        }
        TextButton(onClick = { headers.add(HeaderFields(TextFieldValue(), TextFieldValue())) }) {
            Text(stringResource(R.string.kaizen_custom_action_add_header))
        }

        if (method.hasBody) {
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text(stringResource(R.string.kaizen_custom_action_body)) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                minLines = 4,
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focused = EditorField.Body },
            )
        }

        SectionLabel(R.string.kaizen_custom_action_variables)
        Text(
            text = stringResource(R.string.kaizen_custom_action_variables_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ActionVariable.entries.chunked(VARIABLES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { variable ->
                    Chip(text = variable.token, selected = false, monospace = true, onClick = { insert(variable.token) })
                }
            }
        }

        Text(
            text = stringResource(R.string.kaizen_custom_action_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.kaizen_delete), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.weight(1f))
            FilledButton(
                text = stringResource(R.string.kaizen_save),
                enabled = isValid,
                onClick = {
                    onSave(
                        CustomAction(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            method = method,
                            url = trimmedUrl,
                            headers = headers
                                .filter { it.name.text.isNotBlank() }
                                .map { CustomHeader(it.name.text.trim(), it.value.text) },
                            body = if (method.hasBody) body.text else "",
                        ),
                    )
                },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    monospace: Boolean = false,
) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.let { if (monospace) it.copy(fontFamily = FontFamily.Monospace) else it },
        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private fun TextFieldValue.inserting(token: String): TextFieldValue {
    val start = selection.min
    val end = selection.max
    return TextFieldValue(text.replaceRange(start, end, token), TextRange(start + token.length))
}
