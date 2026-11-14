/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.fragment.findNavController
import java.util.UUID
import kotlinx.coroutines.launch
import mozilla.components.compose.base.button.FilledButton
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.actions.ActionVariable
import org.mozilla.fenix.dejavu.actions.CustomAction
import org.mozilla.fenix.dejavu.actions.CustomActionExample
import org.mozilla.fenix.dejavu.actions.CustomHeader
import org.mozilla.fenix.dejavu.actions.DirectShare
import org.mozilla.fenix.dejavu.actions.HttpMethod
import org.mozilla.fenix.dejavu.actions.ShareTarget
import org.mozilla.fenix.dejavu.actions.customActionExamples
import org.mozilla.fenix.dejavu.actions.isSecretHeader
import org.mozilla.fenix.dejavu.ui.DejavuTextField

/** Adds or edits a [CustomAction]. */
class DejavuCustomActionFragment : DejavuComposeFragment(R.string.dejavu_custom_action) {
    @Composable
    override fun DejavuScreen() {
        val settings = remember { dejavuSettings() }
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
    val context = LocalContext.current
    // A share action keeps an empty request, so for one the request fields start as they do for a new action.
    val request = initial?.takeIf { it.share == null }
    var sharing by remember { mutableStateOf(initial?.share != null) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var method by remember { mutableStateOf(request?.method ?: HttpMethod.POST) }
    var url by remember { mutableStateOf(TextFieldValue(request?.url.orEmpty())) }
    var body by remember { mutableStateOf(TextFieldValue(request?.body ?: DEFAULT_BODY)) }
    val headers = remember {
        mutableStateListOf<HeaderFields>().apply {
            val saved = request?.headers ?: listOf(CustomHeader("Content-Type", "application/json"))
            addAll(saved.map { HeaderFields(TextFieldValue(it.name), TextFieldValue(it.value)) })
        }
    }
    var shareApps by remember { mutableStateOf<List<ShareApp>?>(null) }
    // The share screen picked, without its recipient, which the recipient field holds.
    var shareScreen by remember { mutableStateOf(initial?.share?.copy(recipient = null)) }
    var recipient by remember { mutableStateOf(TextFieldValue(initial?.share?.recipient.orEmpty())) }
    // Who the recipient is, as the contact picker named them.
    var recipientName by remember { mutableStateOf<String?>(null) }
    // The name filled in from the app and the recipient. The name follows them while the user keeps that one.
    var suggestedName by remember { mutableStateOf<String?>(null) }
    val direct = remember(shareScreen?.packageName) { shareScreen?.let { DirectShare.of(context, it.packageName) } }
    var focused by remember { mutableStateOf<EditorField>(EditorField.Url) }
    // The header value shown in clear while it is being edited; other secret values are hidden.
    var revealedHeader by remember { mutableStateOf<Int?>(null) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

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

    fun fill(example: CustomActionExample) {
        name = example.name
        method = example.method
        url = TextFieldValue(example.url)
        body = TextFieldValue(example.body)
        headers.clear()
        headers.addAll(example.headers.map { HeaderFields(TextFieldValue(it.name), TextFieldValue(it.value)) })
        scope.launch { scrollState.animateScrollTo(0) }
    }

    /** Fills in the name of the app picked, with [who] after it, unless the user wrote a name of their own. */
    fun suggestName(who: String?) {
        val app = shareApps?.firstOrNull { it.isFor(shareScreen) } ?: return
        val suggestion = shareActionName(context, app.screen.name, who)
        if (name.isBlank() || name.trim() == suggestedName) name = suggestion
        suggestedName = suggestion
    }

    LaunchedEffect(sharing) {
        if (!sharing || shareApps != null) return@LaunchedEffect
        val loaded = loadShareApps(context)
        shareApps = loaded
        // A share screen that is gone gives way to another one of its app, or to none when the app is gone too.
        shareScreen = shareScreen?.let { saved ->
            val app = loaded.firstOrNull { it.isFor(saved) }
                ?: loaded.firstOrNull { it.screen.packageName == saved.packageName }
            app?.let { ShareTarget(it.screen.packageName, it.screen.activityName) }
        }
        // A name the action got from its app and recipient keeps following them.
        initial?.share?.let { saved ->
            suggestedName = loaded.firstOrNull { it.isFor(saved) }
                ?.let { shareActionName(context, it.screen.name, saved.recipient) }
        }
    }

    val trimmedUrl = url.text.trim()
    val isRequestValid = trimmedUrl.startsWith("https://") || trimmedUrl.startsWith("http://")
    val isShareValid = shareScreen != null &&
        (direct == null || recipient.text.isBlank() || direct.kind.isValid(recipient.text))
    val isValid = name.isNotBlank() && if (sharing) isShareValid else isRequestValid

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(
                text = stringResource(R.string.dejavu_custom_action_kind_request),
                selected = !sharing,
                onClick = { sharing = false },
            )
            Chip(
                text = stringResource(R.string.dejavu_custom_action_kind_share),
                selected = sharing,
                onClick = { sharing = true },
            )
        }

        DejavuTextField(
            value = name,
            onValueChange = { name = it },
            label = stringResource(R.string.dejavu_custom_action_name),
            modifier = Modifier.fillMaxWidth(),
        )

        if (sharing) {
            ShareActionFields(
                apps = shareApps,
                picked = shareScreen,
                direct = direct,
                recipient = recipient,
                onPick = { app ->
                    // A recipient of another kind, like a phone number for an email app, no longer fits.
                    if (DirectShare.of(context, app.screen.packageName)?.kind != direct?.kind) {
                        recipient = TextFieldValue()
                        recipientName = null
                    }
                    shareScreen = ShareTarget(app.screen.packageName, app.screen.activityName)
                    suggestName(recipientName ?: recipient.text)
                },
                onRecipientChange = { value, contactName ->
                    val typed = value.text != recipient.text
                    recipient = value
                    if (typed || contactName != null) {
                        recipientName = contactName
                        suggestName(contactName ?: value.text)
                    }
                },
            )
        } else {
            SectionLabel(R.string.dejavu_custom_action_method)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HttpMethod.entries.forEach { option ->
                    Chip(text = option.name, selected = option == method, onClick = { method = option })
                }
            }

            DejavuTextField(
                value = url,
                onValueChange = { url = it },
                label = stringResource(R.string.dejavu_custom_action_url),
                placeholder = "https://example.com/save?url=${ActionVariable.WEBSITE_URL.token}",
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focused = EditorField.Url },
            )
            if (trimmedUrl.startsWith("http://")) {
                Text(
                    text = stringResource(R.string.dejavu_custom_action_http_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SectionLabel(R.string.dejavu_custom_action_headers)
            headers.forEachIndexed { index, header ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DejavuTextField(
                        value = header.name,
                        onValueChange = { headers[index] = header.copy(name = it) },
                        label = stringResource(R.string.dejavu_custom_action_header_name),
                        modifier = Modifier.weight(1f).onFocusChanged {
                            if (it.isFocused) focused = EditorField.HeaderName(index)
                        },
                    )
                    val hidden = isSecretHeader(header.name.text) && revealedHeader != index
                    DejavuTextField(
                        value = header.value,
                        onValueChange = { headers[index] = header.copy(value = it) },
                        label = stringResource(R.string.dejavu_custom_action_header_value),
                        visualTransformation = if (hidden) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        modifier = Modifier.weight(1.4f).onFocusChanged {
                            if (it.isFocused) {
                                focused = EditorField.HeaderValue(index)
                                revealedHeader = index
                            } else if (revealedHeader == index) {
                                revealedHeader = null
                            }
                        },
                    )
                    IconButton(onClick = { headers.removeAt(index) }) {
                        Icon(
                            painter = painterResource(iconsR.drawable.mozac_ic_cross_24),
                            contentDescription = stringResource(R.string.dejavu_custom_action_remove_header),
                        )
                    }
                }
            }
            TextButton(onClick = { headers.add(HeaderFields(TextFieldValue(), TextFieldValue())) }) {
                Text(stringResource(R.string.dejavu_custom_action_add_header))
            }

            if (method.hasBody) {
                DejavuTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = stringResource(R.string.dejavu_custom_action_body),
                    singleLine = false,
                    minLines = 4,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focused = EditorField.Body },
                )
            }

            SectionLabel(R.string.dejavu_custom_action_variables)
            Text(
                text = stringResource(R.string.dejavu_custom_action_variables_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActionVariable.entries.chunked(VARIABLES_PER_ROW).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { variable ->
                        Chip(
                            text = variable.token,
                            selected = false,
                            monospace = true,
                            onClick = { insert(variable.token) },
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.dejavu_custom_action_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.dejavu_delete), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.weight(1f))
            FilledButton(
                text = stringResource(R.string.dejavu_save),
                enabled = isValid,
                onClick = {
                    val id = initial?.id ?: UUID.randomUUID().toString()
                    val target = shareScreen
                    onSave(
                        if (sharing && target != null) {
                            val to = direct?.kind?.normalize(recipient.text)?.takeIf { it.isNotEmpty() }
                            CustomAction.shareAction(id, name.trim(), target.copy(recipient = to))
                        } else {
                            CustomAction(
                                id = id,
                                name = name.trim(),
                                method = method,
                                url = trimmedUrl,
                                headers = headers
                                    .filter { it.name.text.isNotBlank() }
                                    .map { CustomHeader(it.name.text.trim(), it.value.text) },
                                body = if (method.hasBody) body.text else "",
                            )
                        },
                    )
                },
            )
        }

        if (!sharing && request == null) {
            SectionLabel(R.string.dejavu_custom_action_examples)
            Text(
                text = stringResource(R.string.dejavu_custom_action_examples_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            customActionExamples.forEach { example -> ExampleRow(example, onClick = { fill(example) }) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ExampleRow(example: CustomActionExample, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = example.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(example.description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SectionLabel(@StringRes text: Int) {
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
