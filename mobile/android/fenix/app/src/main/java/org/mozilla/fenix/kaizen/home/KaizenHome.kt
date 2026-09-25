/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.state.TabSessionState
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.Favicon
import org.mozilla.fenix.kaizen.workspaces.Workspace
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState
import mozilla.components.ui.icons.R as iconsR

/**
 * Workspace based home screen. Each workspace is a page listing its tabs; swiping horizontally switches workspace.
 */
@Suppress("LongParameterList")
@Composable
fun KaizenHome(
    state: WorkspaceState,
    tabs: List<TabSessionState>,
    selectedTabId: String?,
    onWorkspaceSelected: (String) -> Unit,
    onAddWorkspace: (String) -> Unit,
    onTabClick: (String) -> Unit,
    onNewTabClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(initialPage = state.activeIndex) { state.workspaces.size }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.settledPage) {
        state.workspaces.getOrNull(pagerState.settledPage)?.let { onWorkspaceSelected(it.id) }
    }

    LaunchedEffect(state.activeIndex, state.workspaces.size) {
        if (pagerState.settledPage != state.activeIndex) {
            pagerState.animateScrollToPage(state.activeIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            key = { state.workspaces[it].id },
        ) { page ->
            val workspace = state.workspaces[page]
            WorkspacePage(
                workspace = workspace,
                tabs = tabs.filter { state.workspaceOf(it.id) == workspace.id },
                selectedTabId = selectedTabId,
                onTabClick = onTabClick,
                onNewTabClick = onNewTabClick,
            )
        }

        WorkspaceBar(
            workspaces = state.workspaces,
            activeIndex = pagerState.currentPage,
            onDotClick = { index -> onWorkspaceSelected(state.workspaces[index].id) },
            onAddClick = { showAddDialog = true },
            onDownloadsClick = onDownloadsClick,
        )
    }

    if (showAddDialog) {
        AddWorkspaceDialog(
            onConfirm = { name ->
                showAddDialog = false
                onAddWorkspace(name)
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun WorkspacePage(
    workspace: Workspace,
    tabs: List<TabSessionState>,
    selectedTabId: String?,
    onTabClick: (String) -> Unit,
    onNewTabClick: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        item(key = "header") {
            Text(
                text = workspace.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            )
        }

        items(tabs, key = { it.id }) { tab ->
            TabItem(
                tab = tab,
                isSelected = tab.id == selectedTabId,
                onClick = { onTabClick(tab.id) },
            )
        }

        item(key = "new_tab") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onNewTabClick)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
            ) {
                Icon(
                    painter = painterResource(iconsR.drawable.mozac_ic_plus_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.kaizen_new_tab),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: TabSessionState,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Favicon(url = tab.content.url, size = 20.dp)
        Spacer(Modifier.width(16.dp))
        Text(
            text = tab.content.title.ifBlank { tab.content.url },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WorkspaceBar(
    workspaces: List<Workspace>,
    activeIndex: Int,
    onDotClick: (Int) -> Unit,
    onAddClick: () -> Unit,
    onDownloadsClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
    ) {
        IconButton(onClick = onDownloadsClick) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_download_24),
                contentDescription = stringResource(R.string.kaizen_downloads),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        ) {
            workspaces.forEachIndexed { index, workspace ->
                val isActive = index == activeIndex
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = workspace.name) { onDotClick(index) },
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (isActive) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isActive) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                            ),
                    )
                }
            }
        }

        IconButton(onClick = onAddClick) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_plus_24),
                contentDescription = stringResource(R.string.kaizen_add_workspace),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AddWorkspaceDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kaizen_add_workspace)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.kaizen_workspace_name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) {
                Text(stringResource(R.string.kaizen_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.kaizen_cancel))
            }
        },
    )
}
