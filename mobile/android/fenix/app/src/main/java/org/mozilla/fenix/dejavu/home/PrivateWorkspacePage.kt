/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.actions.RowAction
import org.mozilla.fenix.dejavu.actions.TabAction
import org.mozilla.fenix.dejavu.workspaces.WorkspaceTheme

/** Key of the private workspace page. Workspace IDs are braced UUIDs, so it cannot clash with one. */
internal const val PRIVATE_PAGE_KEY = "private"

/** Background of the private workspace, in the purple of Firefox's private browsing. */
internal val privateWorkspaceTheme = WorkspaceTheme(
    colors = listOf(0xFF7542E5.toInt(), 0xFF25003E.toInt()),
    opacity = 0.35f,
    texture = 0.2f,
)

/**
 * The private tabs, shown as a workspace of their own while any are open. It cannot hold pinned tabs, folders or
 * essentials, and is not saved: it goes away with its last tab.
 */
@Composable
internal fun PrivateWorkspacePage(
    tabs: List<TabSessionState>,
    selectedTabId: String?,
    onTabClick: (TabSessionState) -> Unit,
    onCloseTab: (TabSessionState) -> Unit,
    onNewTab: () -> Unit,
    onCloseAll: () -> Unit,
) {
    val close = RowAction.BuiltIn(TabAction.CLOSE)
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        item(key = "header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_private_mode_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.dejavu_private_workspace),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCloseAll) {
                    Icon(
                        painter = painterResource(iconsR.drawable.mozac_ic_delete_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.dejavu_private_close_all),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        item(key = "divider") {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            )
        }
        item(key = "new_tab") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onNewTab)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
            ) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_plus_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).padding(start = 3.dp),
                )
                Spacer(Modifier.width(19.dp))
                Text(
                    text = stringResource(R.string.dejavu_new_private_tab),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(tabs, key = { it.id }) { tab ->
            TabRow(
                title = tab.displayTitle,
                url = tab.content.url,
                depth = 0,
                container = null,
                isAwake = tab.isAwake,
                isCurrent = tab.id == selectedTabId,
                isSplit = false,
                selection = null,
                actions = listOf(close),
                onAction = { onCloseTab(tab) },
                onClick = { onTabClick(tab) },
            )
        }
    }
}
