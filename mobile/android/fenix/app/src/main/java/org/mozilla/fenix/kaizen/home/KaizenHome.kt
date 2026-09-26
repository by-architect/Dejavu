/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.compose.base.theme.success
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.Favicon
import org.mozilla.fenix.kaizen.workspaces.PinnedTab
import org.mozilla.fenix.kaizen.workspaces.Workspace
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState
import mozilla.components.ui.icons.R as iconsR

private val TabShape = RoundedCornerShape(16.dp)

/**
 * User actions on the Kaizen home screen.
 */
interface KaizenHomeInteractor {
    fun onWorkspaceSelected(workspaceId: String)
    fun onAddWorkspace(name: String)
    fun onTabClick(tabId: String)
    fun onTabClose(tabId: String)
    fun onTabPin(tab: TabSessionState)
    fun onPinnedClick(pinned: PinnedTab)
    fun onUnpin(pinnedId: String)
    fun onSearchClick()
    fun onAccountClick()
    fun onSettingsClick()
    fun onDownloadsClick()
}

/**
 * Workspace based home screen. Each workspace is a page listing its pinned tabs and then its other tabs; swiping
 * horizontally switches workspace.
 */
@Composable
fun KaizenHome(
    state: WorkspaceState,
    tabs: List<TabSessionState>,
    selectedTabId: String?,
    interactor: KaizenHomeInteractor,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(initialPage = state.activeIndex) { state.workspaces.size }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.settledPage) {
        state.workspaces.getOrNull(pagerState.settledPage)?.let { interactor.onWorkspaceSelected(it.id) }
    }

    LaunchedEffect(state.activeIndex, state.workspaces.size) {
        if (pagerState.settledPage != state.activeIndex) {
            pagerState.animateScrollToPage(state.activeIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        TopBar(interactor)

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
                interactor = interactor,
            )
        }

        WorkspaceBar(
            workspaces = state.workspaces,
            activeIndex = pagerState.currentPage,
            onDotClick = { index -> interactor.onWorkspaceSelected(state.workspaces[index].id) },
            onAddClick = { showAddDialog = true },
            onDownloadsClick = interactor::onDownloadsClick,
        )
    }

    if (showAddDialog) {
        AddWorkspaceDialog(
            onConfirm = { name ->
                showAddDialog = false
                interactor.onAddWorkspace(name)
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun TopBar(interactor: KaizenHomeInteractor) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
    ) {
        BarIconButton(
            icon = iconsR.drawable.mozac_ic_avatar_circle_24,
            contentDescription = stringResource(R.string.kaizen_account),
            onClick = interactor::onAccountClick,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = interactor::onSearchClick)
                .padding(horizontal = 14.dp),
        ) {
            Icon(
                painter = painterResource(iconsR.drawable.mozac_ic_search_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.kaizen_search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        BarIconButton(
            icon = iconsR.drawable.mozac_ic_settings_24,
            contentDescription = stringResource(R.string.kaizen_settings),
            onClick = interactor::onSettingsClick,
        )
    }
}

@Composable
private fun WorkspacePage(
    workspace: Workspace,
    tabs: List<TabSessionState>,
    selectedTabId: String?,
    interactor: KaizenHomeInteractor,
) {
    val tabsById = tabs.associateBy { it.id }
    val pinnedTabIds = workspace.pinned.mapNotNull { it.tabId }.toSet()
    val otherTabs = tabs.filterNot { it.id in pinnedTabIds }

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

        items(workspace.pinned, key = { "pin-${it.id}" }) { pinned ->
            val tab = pinned.tabId?.let { tabsById[it] }
            if (tab != null) {
                TabItem(
                    title = tab.displayTitle,
                    url = tab.content.url,
                    isOpen = true,
                    isSelected = tab.id == selectedTabId,
                    onClick = { interactor.onTabClick(tab.id) },
                ) {
                    RowIconButton(iconsR.drawable.mozac_ic_cross_24, R.string.kaizen_close_tab) {
                        interactor.onTabClose(tab.id)
                    }
                }
            } else {
                TabItem(
                    title = pinned.title.ifBlank { pinned.url },
                    url = pinned.url,
                    isOpen = false,
                    isSelected = false,
                    onClick = { interactor.onPinnedClick(pinned) },
                ) {
                    RowIconButton(iconsR.drawable.mozac_ic_pin_slash_24, R.string.kaizen_unpin_tab) {
                        interactor.onUnpin(pinned.id)
                    }
                }
            }
        }

        if (workspace.pinned.isNotEmpty()) {
            item(key = "pinned-divider") {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }

        items(otherTabs, key = { it.id }) { tab ->
            TabItem(
                title = tab.displayTitle,
                url = tab.content.url,
                isOpen = true,
                isSelected = tab.id == selectedTabId,
                onClick = { interactor.onTabClick(tab.id) },
            ) {
                RowIconButton(iconsR.drawable.mozac_ic_pin_24, R.string.kaizen_pin_tab) {
                    interactor.onTabPin(tab)
                }
                RowIconButton(iconsR.drawable.mozac_ic_cross_24, R.string.kaizen_close_tab) {
                    interactor.onTabClose(tab.id)
                }
            }
        }

        item(key = "new_tab") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(TabShape)
                    .clickable(onClick = interactor::onSearchClick)
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

private val TabSessionState.displayTitle: String
    get() = content.title.ifBlank { content.url }

@Suppress("LongParameterList")
@Composable
private fun TabItem(
    title: String,
    url: String,
    isOpen: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    actions: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(TabShape)
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(26.dp)
                .border(
                    BorderStroke(1.5.dp, if (isOpen) MaterialTheme.colorScheme.success else Color.Transparent),
                    RectangleShape,
                ),
        ) {
            Favicon(url = url, size = 18.dp, shape = RectangleShape)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isOpen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
private fun RowIconButton(
    @DrawableRes icon: Int,
    contentDescription: Int,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(contentDescription),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun BarIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
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
        BarIconButton(
            icon = iconsR.drawable.mozac_ic_download_24,
            contentDescription = stringResource(R.string.kaizen_downloads),
            onClick = onDownloadsClick,
        )

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

        BarIconButton(
            icon = iconsR.drawable.mozac_ic_plus_24,
            contentDescription = stringResource(R.string.kaizen_add_workspace),
            onClick = onAddClick,
        )
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
