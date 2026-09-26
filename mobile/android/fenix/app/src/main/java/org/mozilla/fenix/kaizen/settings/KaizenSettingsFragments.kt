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
import org.mozilla.fenix.kaizen.actions.CustomAction
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
abstract class KaizenComposeFragment(@param:StringRes private val defaultTitle: Int) :
    Fragment(), SystemInsetsPaddedFragment {
    /** Title of the settings toolbar. */
    @get:StringRes
    protected open val title: Int
        get() = defaultTitle

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

/** A list of actions to choose from in the tab actions settings. */
enum class ActionList(@param:StringRes val title: Int) {
    /** The buttons of pinned tab rows. */
    PINNED(R.string.kaizen_settings_pinned_tabs),

    /** The buttons of unpinned tab rows. */
    UNPINNED(R.string.kaizen_settings_unpinned_tabs),

    /** The actions of the selection bar. */
    ALL(R.string.kaizen_settings_all_actions),
}

/** Leads to the three [ActionList]s: pinned tab rows, unpinned tab rows and the selection bar. */
class KaizenTabActionsFragment : KaizenComposeFragment(R.string.kaizen_settings_tab_actions) {
    @Composable
    override fun KaizenScreen() {
        val settings = remember { kaizenSettings() }
        val customActions by settings.customActions.collectAsState()
        val pinnedKeys by settings.pinnedRowKeys.collectAsState()
        val unpinnedKeys by settings.unpinnedRowKeys.collectAsState()
        val hiddenKeys by settings.hiddenSelectionKeys.collectAsState()
        val allActions = RowAction.selectionBar(customActions)

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.kaizen_settings_tab_actions_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            IconListItem(
                label = stringResource(R.string.kaizen_settings_pinned_tabs),
                description = rowSummary(resolveRowActions(pinnedKeys, pinned = true, customActions = customActions)),
                beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_pin_24),
                onClick = { openList(ActionList.PINNED) },
            )
            IconListItem(
                label = stringResource(R.string.kaizen_settings_unpinned_tabs),
                description = rowSummary(resolveRowActions(unpinnedKeys, pinned = false, customActions = customActions)),
                beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_tab_24),
                onClick = { openList(ActionList.UNPINNED) },
            )
            IconListItem(
                label = stringResource(R.string.kaizen_settings_all_actions),
                description = stringResource(
                    R.string.kaizen_settings_all_actions_summary,
                    allActions.count { it.key !in hiddenKeys },
                    allActions.size,
                ),
                beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_select_all_24),
                onClick = { openList(ActionList.ALL) },
            )
        }
    }

    @Composable
    private fun rowSummary(actions: List<RowAction>): String =
        if (actions.isEmpty()) {
            stringResource(R.string.kaizen_settings_no_buttons)
        } else {
            actions.map { it.label }.joinToString(", ")
        }

    private fun openList(list: ActionList) {
        findNavController().navigate(
            R.id.kaizenActionListFragment,
            Bundle().apply { putString(KaizenActionListFragment.ARG_LIST, list.name) },
        )
    }
}

/** Chooses the actions of one [ActionList], below the custom actions and the button that adds one. */
class KaizenActionListFragment : KaizenComposeFragment(R.string.kaizen_settings_tab_actions) {
    private val list: ActionList
        get() = arguments?.getString(ARG_LIST)?.let { name -> ActionList.entries.firstOrNull { it.name == name } }
            ?: ActionList.ALL

    override val title: Int
        get() = list.title

    @Composable
    override fun KaizenScreen() {
        val settings = remember { kaizenSettings() }
        val customActions by settings.customActions.collectAsState()

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            AddActionSection(customActions)
            when (val current = list) {
                ActionList.PINNED, ActionList.UNPINNED -> {
                    val pinned = current == ActionList.PINNED
                    val keys by (if (pinned) settings.pinnedRowKeys else settings.unpinnedRowKeys).collectAsState()
                    val available = RowAction.available(pinned, customActions)
                    val enabledCount = available.count { it.key in keys }
                    val hint = if (pinned) {
                        R.string.kaizen_settings_pinned_actions_hint
                    } else {
                        R.string.kaizen_settings_unpinned_actions_hint
                    }
                    ActionSwitches(
                        title = R.string.kaizen_settings_buttons,
                        hint = stringResource(hint, KaizenSettings.MAX_ROW_ACTIONS),
                        actions = available,
                        isChecked = { it.key in keys },
                        canCheckMore = enabledCount < KaizenSettings.MAX_ROW_ACTIONS,
                        onChange = { key, on -> settings.setRowAction(pinned = pinned, key = key, enabled = on) },
                    )
                }
                ActionList.ALL -> {
                    val hiddenKeys by settings.hiddenSelectionKeys.collectAsState()
                    ActionSwitches(
                        title = R.string.kaizen_settings_actions,
                        hint = stringResource(R.string.kaizen_settings_all_actions_hint),
                        actions = RowAction.selectionBar(customActions),
                        isChecked = { it.key !in hiddenKeys },
                        canCheckMore = true,
                        onChange = { key, on -> settings.setSelectionAction(key, enabled = on) },
                    )
                }
            }
        }
    }

    /** The button that adds a custom action, and the custom actions to edit. */
    @Composable
    private fun AddActionSection(customActions: List<CustomAction>) {
        FilledButton(
            text = stringResource(R.string.kaizen_custom_action_add),
            icon = painterResource(iconsR.drawable.mozac_ic_plus_24),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
            onClick = { openEditor(null) },
        )
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

    @Suppress("LongParameterList")
    @Composable
    private fun ActionSwitches(
        @StringRes title: Int,
        hint: String,
        actions: List<RowAction>,
        isChecked: (RowAction) -> Boolean,
        canCheckMore: Boolean,
        onChange: (String, Boolean) -> Unit,
    ) {
        SettingsSectionHeader(
            text = stringResource(title),
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp),
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        actions.forEach { action ->
            val checked = isChecked(action)
            SwitchListItem(
                label = action.label,
                checked = checked,
                enabled = checked || canCheckMore,
                showSwitchAfter = true,
                onClick = { onChange(action.key, it) },
            )
        }
    }

    private fun openEditor(actionId: String?) {
        findNavController().navigate(
            R.id.kaizenCustomActionFragment,
            Bundle().apply { putString(KaizenCustomActionFragment.ARG_ACTION_ID, actionId) },
        )
    }

    companion object {
        /** Name of the [ActionList] to show. */
        const val ARG_LIST = "list"
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
