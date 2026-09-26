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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import mozilla.components.compose.base.button.FilledButton
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.IconListItem
import org.mozilla.fenix.compose.list.SwitchListItem
import org.mozilla.fenix.compose.settings.SettingsSectionHeader
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.kaizen.actions.RowAction
import org.mozilla.fenix.kaizen.actions.label
import org.mozilla.fenix.kaizen.containers.ContainerRecord
import org.mozilla.fenix.kaizen.containers.ContainerRemover
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
            setContent { FirefoxTheme { KaizenScreen() } }
        }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(title))
    }

    /** The screen below the toolbar. Not named `Content` so it cannot resolve to [ComposeView.Content]. */
    @Composable
    abstract fun KaizenScreen()
}

/** Chooses the buttons of pinned and unpinned tab rows, and lists the custom actions. */
class KaizenTabActionsFragment : KaizenComposeFragment(R.string.kaizen_settings_tab_actions) {
    @Composable
    override fun KaizenScreen() {
        val settings = remember { kaizenSettings() }
        val customActions by settings.customActions.collectAsState()
        val pinnedKeys by settings.pinnedRowKeys.collectAsState()
        val unpinnedKeys by settings.unpinnedRowKeys.collectAsState()

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FilledButton(
                text = stringResource(R.string.kaizen_custom_action_add),
                icon = painterResource(iconsR.drawable.mozac_ic_plus_24),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                onClick = { openEditor(null) },
            )
            Text(
                text = stringResource(R.string.kaizen_settings_tab_actions_hint, KaizenSettings.MAX_ROW_ACTIONS),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            ActionSection(
                title = R.string.kaizen_settings_pinned_tabs,
                available = RowAction.available(pinned = true, customActions = customActions),
                enabledKeys = pinnedKeys,
            ) { key, on -> settings.setRowAction(pinned = true, key = key, enabled = on) }
            ActionSection(
                title = R.string.kaizen_settings_unpinned_tabs,
                available = RowAction.available(pinned = false, customActions = customActions),
                enabledKeys = unpinnedKeys,
            ) { key, on -> settings.setRowAction(pinned = false, key = key, enabled = on) }
            if (customActions.isNotEmpty()) {
                SettingsSectionHeader(
                    text = stringResource(R.string.kaizen_custom_actions),
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                )
                customActions.forEach { action ->
                    IconListItem(
                        label = action.name,
                        description = "${action.method.name} ${action.url}",
                        beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_lightning_24),
                        onClick = { openEditor(action.id) },
                    )
                }
            }
        }
    }

    private fun openEditor(actionId: String?) {
        findNavController().navigate(
            R.id.kaizenCustomActionFragment,
            Bundle().apply { putString(KaizenCustomActionFragment.ARG_ACTION_ID, actionId) },
        )
    }

    @Composable
    private fun ActionSection(
        @StringRes title: Int,
        available: List<RowAction>,
        enabledKeys: List<String>,
        onChange: (String, Boolean) -> Unit,
    ) {
        SettingsSectionHeader(
            text = stringResource(title),
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
        )
        val enabledCount = available.count { it.key in enabledKeys }
        available.forEach { action ->
            val checked = action.key in enabledKeys
            SwitchListItem(
                label = action.label,
                checked = checked,
                enabled = checked || enabledCount < KaizenSettings.MAX_ROW_ACTIONS,
                showSwitchAfter = true,
                onClick = { onChange(action.key, it) },
            )
        }
    }
}

/** Lists, adds, edits and deletes containers. */
class KaizenContainersFragment : KaizenComposeFragment(R.string.kaizen_settings_containers) {
    @Composable
    override fun KaizenScreen() {
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
                record = record,
                otherContainers = containers.orEmpty().filter { it.contextId != record.contextId },
                onConfirm = { removal ->
                    ContainerRemover(requireComponents, WorkspaceRepository.peek()).remove(record, removal)
                    deleting = null
                },
                onDismiss = { deleting = null },
            )
        }
    }
}

internal fun Fragment.kaizenSettings(): KaizenSettings =
    requireComponents.strictMode.allowViolation(StrictMode::allowThreadDiskReads) {
        KaizenSettings.get(requireContext())
    }
