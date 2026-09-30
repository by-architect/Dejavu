/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mozilla.components.compose.base.progressbar.AnimatedProgressBar
import mozilla.components.compose.browser.toolbar.ActionContainer
import mozilla.components.compose.browser.toolbar.R as toolbarR
import mozilla.components.compose.browser.toolbar.concept.PageOrigin.Companion.ContextualMenuOption
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarStore
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.support.utils.ClipboardHandler
import org.mozilla.fenix.dejavu.ui.glass

/**
 * Dejavu's address bar while a page is shown: only its state, like Safari's. The page title sits in the middle of a
 * rounded field, after the icons of the tab's container and of the site's security, with no buttons, over the colors of
 * the tab's workspace. Tapping the bar starts a search, and long-pressing it copies the address or pastes one.
 *
 * @param store The store of the address bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DejavuTopBar(store: BrowserToolbarStore) {
    val display by store.observeAsComposableState { it.displayState }
    val origin = display.pageOrigin
    val hint = stringResource(origin.hint)
    val text = origin.url?.toString()?.takeIf { it.isNotBlank() } ?: origin.title?.takeIf { it.isNotBlank() } ?: hint
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .background(MaterialTheme.colorScheme.surface)
            .workspaceTheme(shownWorkspaceTheme())
            .semantics {
                contentDescription = if (text == hint) hint else "$text. $hint"
                role = Role.Button
            }
            .combinedClickable(
                onClick = { origin.onClick?.let { store.dispatch(it) } },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = SIDE_PADDING)
                .height(FIELD_HEIGHT)
                .glass(glass, CircleShape)
                .padding(horizontal = FIELD_PADDING),
        ) {
            ActionContainer(actions = display.pageActionsStart, onInteraction = { store.dispatch(it) })
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (text == hint) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The bar's own description already says it.
                modifier = Modifier.weight(1f, fill = false).padding(end = TITLE_END_PADDING).clearAndSetSemantics {},
            )
        }
        display.progressBarConfig?.let { config ->
            AnimatedProgressBar(
                progress = config.progress,
                color = config.color,
                trackColor = Color.Transparent,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        LongPressMenu(
            expanded = menuOpen,
            options = origin.contextualMenuOptions,
            onPick = { option ->
                menuOpen = false
                store.dispatch(option.event)
            },
            onDismiss = { menuOpen = false },
        )
    }
}

@Composable
private fun LongPressMenu(
    expanded: Boolean,
    options: List<ContextualMenuOption>,
    onPick: (ContextualMenuOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = remember(context) { ClipboardHandler(context) }
    val shown = remember(expanded, options) {
        if (!expanded) return@remember emptyList()
        options.filter { option ->
            when (option) {
                ContextualMenuOption.CopyURLToClipboard -> true
                ContextualMenuOption.PasteFromClipboard -> clipboard.containsText()
                ContextualMenuOption.LoadFromClipboard -> clipboard.containsURL()
            }
        }
    }
    DropdownMenu(expanded = expanded && shown.isNotEmpty(), onDismissRequest = onDismiss) {
        shown.forEach { option ->
            DropdownMenuItem(
                text = { Text(stringResource(option.label)) },
                onClick = { onPick(option) },
            )
        }
    }
}

private val ContextualMenuOption.label: Int
    get() = when (this) {
        ContextualMenuOption.CopyURLToClipboard -> toolbarR.string.mozac_browser_toolbar_long_press_popup_copy
        ContextualMenuOption.PasteFromClipboard -> toolbarR.string.mozac_browser_toolbar_long_press_popup_paste
        ContextualMenuOption.LoadFromClipboard -> toolbarR.string.mozac_browser_toolbar_long_press_popup_paste_and_go
    }

private val BAR_HEIGHT = 64.dp
private val SIDE_PADDING = 16.dp
private val FIELD_HEIGHT = 46.dp
private val FIELD_PADDING = 12.dp
private val TITLE_END_PADDING = 8.dp
