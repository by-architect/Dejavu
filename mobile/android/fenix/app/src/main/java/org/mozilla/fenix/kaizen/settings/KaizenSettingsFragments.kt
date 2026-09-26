/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.settings

import android.os.Bundle
import android.os.StrictMode
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.ContainerAction
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.IconListItem
import org.mozilla.fenix.compose.list.SwitchListItem
import org.mozilla.fenix.compose.list.TextListItem
import org.mozilla.fenix.compose.settings.SettingsSectionHeader
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.kaizen.actions.TabAction
import org.mozilla.fenix.kaizen.containers.ContainerRecord
import org.mozilla.fenix.kaizen.containers.KaizenContainerStorage
import org.mozilla.fenix.kaizen.containers.color
import org.mozilla.fenix.kaizen.containers.drawable
import org.mozilla.fenix.kaizen.workspaces.WorkspaceRepository
import org.mozilla.fenix.theme.FirefoxTheme
import mozilla.components.ui.icons.R as iconsR

/**
 * Base for the Kaizen settings screens: a Compose screen below the settings toolbar.
 */
abstract class KaizenComposeFragment(@param:StringRes private val title: Int) :
    Fragment(), SystemInsetsPaddedFragment {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { FirefoxTheme { Content() } }
        }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(title))
    }

    @Composable
    abstract fun Content()
}

/** Entry point of the Kaizen settings. */
class KaizenSettingsFragment : KaizenComposeFragment(R.string.kaizen_settings_title) {
    @Composable
    override fun Content() {
        val settings = remember { kaizenSettings() }
        val pinned by settings.pinnedRowActions.collectAsState()
        val unpinned by settings.unpinnedRowActions.collectAsState()
        val storage = remember { KaizenContainerStorage.get(requireContext()) }
        val containers by storage.records.collectAsState()
        LaunchedEffect(Unit) { storage.load() }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsSectionHeader(
                text = stringResource(R.string.kaizen_settings_home_page),
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            )
            TextListItem(
                label = stringResource(R.string.kaizen_settings_tab_actions),
                description = stringResource(
                    R.string.kaizen_settings_tab_actions_summary,
                    pinned.labels(),
                    unpinned.labels(),
                ),
                maxDescriptionLines = 2,
                onClick = { findNavController().navigate(R.id.kaizenTabActionsFragment) },
            )

            SettingsSectionHeader(
                text = stringResource(R.string.kaizen_settings_containers),
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
            )
            val count = containers?.size ?: 0
            TextListItem(
                label = stringResource(R.string.kaizen_settings_manage_containers),
                description = pluralStringResource(R.plurals.kaizen_settings_containers_count, count, count),
                onClick = { findNavController().navigate(R.id.kaizenContainersFragment) },
            )
        }
    }

    @Composable
    private fun List<TabAction>.labels(): String =
        if (isEmpty()) stringResource(R.string.kaizen_settings_no_actions) else map { stringResource(it.label) }.joinToString()
}

/** Chooses the buttons shown on pinned and unpinned tab rows of the home page. */
class KaizenTabActionsFragment : KaizenComposeFragment(R.string.kaizen_settings_tab_actions) {
    @Composable
    override fun Content() {
        val settings = remember { kaizenSettings() }
        val pinned by settings.pinnedRowActions.collectAsState()
        val unpinned by settings.unpinnedRowActions.collectAsState()

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.kaizen_settings_tab_actions_hint, KaizenSettings.MAX_ROW_ACTIONS),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            ActionSection(R.string.kaizen_settings_pinned_tabs, TabAction.forPinnedRows, pinned) { action, on ->
                settings.setRowAction(pinned = true, action = action, enabled = on)
            }
            ActionSection(R.string.kaizen_settings_unpinned_tabs, TabAction.forUnpinnedRows, unpinned) { action, on ->
                settings.setRowAction(pinned = false, action = action, enabled = on)
            }
        }
    }

    @Composable
    private fun ActionSection(
        @StringRes title: Int,
        available: List<TabAction>,
        enabled: List<TabAction>,
        onChange: (TabAction, Boolean) -> Unit,
    ) {
        SettingsSectionHeader(
            text = stringResource(title),
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
        )
        available.forEach { action ->
            val checked = action in enabled
            SwitchListItem(
                label = stringResource(action.label),
                checked = checked,
                enabled = checked || enabled.size < KaizenSettings.MAX_ROW_ACTIONS,
                showSwitchAfter = true,
                onClick = { onChange(action, it) },
            )
        }
    }
}

/** Lists, adds, edits and deletes containers. */
class KaizenContainersFragment : KaizenComposeFragment(R.string.kaizen_settings_containers) {
    @Composable
    override fun Content() {
        val storage = remember { KaizenContainerStorage.get(requireContext()) }
        val containers by storage.records.collectAsState()
        val scope = rememberCoroutineScope()
        var editing by remember { mutableStateOf<ContainerRecord?>(null) }
        var adding by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<ContainerRecord?>(null) }
        LaunchedEffect(Unit) { storage.load() }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = stringResource(R.string.kaizen_settings_containers_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(containers.orEmpty(), key = { it.contextId }) { record ->
                IconListItem(
                    label = record.name,
                    beforeIconPainter = painterResource(record.icon.drawable),
                    beforeIconTint = record.color.color,
                    onClick = { editing = record },
                )
            }
            item {
                IconListItem(
                    label = stringResource(R.string.kaizen_container_add),
                    beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_plus_24),
                    onClick = { adding = true },
                )
            }
        }

        if (adding || editing != null) {
            ContainerEditorDialog(
                record = editing,
                onSave = { name, color, icon ->
                    val contextId = editing?.contextId
                    scope.launch { storage.saveContainer(contextId, name, color, icon) }
                    adding = false
                    editing = null
                },
                onDelete = {
                    deleting = editing
                    editing = null
                },
                onDismiss = {
                    adding = false
                    editing = null
                },
            )
        }

        deleting?.let { record ->
            DeleteContainerDialog(
                name = record.name,
                onConfirm = {
                    deleteContainer(record)
                    deleting = null
                },
                onDismiss = { deleting = null },
            )
        }
    }

    private fun deleteContainer(record: ContainerRecord) {
        val components = requireComponents
        val store = components.core.store
        val tabIds = store.state.tabs.filter { it.contextId == record.contextId }.map { it.id }
        if (tabIds.isNotEmpty()) components.useCases.tabsUseCases.removeTabs(tabIds)
        components.core.geckoRuntime.storageController.clearDataForSessionContext(record.contextId)
        WorkspaceRepository.peek()?.forgetContainer(record.contextId)
        store.dispatch(ContainerAction.RemoveContainerAction(record.contextId))
    }
}

private fun Fragment.kaizenSettings(): KaizenSettings =
    requireComponents.strictMode.allowViolation(StrictMode::allowThreadDiskReads) {
        KaizenSettings.get(requireContext())
    }
