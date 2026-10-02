/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.os.Bundle
import android.os.StrictMode
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import mozilla.components.compose.base.button.FilledButton
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.compose.list.IconListItem
import org.mozilla.fenix.compose.list.SwitchListItem
import org.mozilla.fenix.compose.settings.SettingsSectionHeader
import org.mozilla.fenix.dejavu.actions.ActionPlace
import org.mozilla.fenix.dejavu.actions.CustomAction
import org.mozilla.fenix.dejavu.actions.RowAction
import org.mozilla.fenix.dejavu.actions.fits
import org.mozilla.fenix.dejavu.actions.icon
import org.mozilla.fenix.dejavu.actions.label
import org.mozilla.fenix.dejavu.containers.ContainerIcon
import org.mozilla.fenix.dejavu.containers.ContainerPick
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.containers.ContainerRemover
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.containers.NoContainerIcon
import org.mozilla.fenix.dejavu.containers.TemporaryContainerIcon
import org.mozilla.fenix.dejavu.containers.color
import org.mozilla.fenix.dejavu.containers.drawable
import org.mozilla.fenix.dejavu.menu.ActionsBarLayout
import org.mozilla.fenix.dejavu.menu.menuKey
import org.mozilla.fenix.dejavu.sync.workspaceIconText
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Base for the Dejavu settings screens: a Compose screen below the settings toolbar.
 */
abstract class DejavuComposeFragment(@param:StringRes private val defaultTitle: Int) :
    Fragment(), SystemInsetsPaddedFragment {
    /** Title of the settings toolbar. */
    @get:StringRes
    protected open val title: Int
        get() = defaultTitle

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { FirefoxTheme { DejavuScreen() } }
        }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(title))
    }

    /** The screen below the toolbar. Not named `Content` so it cannot resolve to [ComposeView.Content]. */
    @Composable
    abstract fun DejavuScreen()
}

/** A place whose buttons are chosen on a screen of their own in the tab actions settings. */
enum class ActionList(
    val place: ActionPlace,
    @param:DrawableRes val icon: Int,
    @param:StringRes val hint: Int,
) {
    PINNED(ActionPlace.PINNED_ROWS, iconsR.drawable.mozac_ic_pin_24, R.string.dejavu_settings_pinned_actions_hint),
    UNPINNED(ActionPlace.UNPINNED_ROWS, iconsR.drawable.mozac_ic_tab_24, R.string.dejavu_settings_unpinned_actions_hint),
    FOLDERS(ActionPlace.FOLDER_ROWS, iconsR.drawable.mozac_ic_folder_24, R.string.dejavu_settings_folder_actions_hint),
    SELECTION(ActionPlace.SELECTION, iconsR.drawable.mozac_ic_select_all_24, R.string.dejavu_settings_all_actions_hint),
}

/**
 * Every action in one list, the user's own included, with where each one shows. Tapping an action chooses its places;
 * the rows and the selection bar can also be set up on screens of their own.
 */
class DejavuTabActionsFragment : DejavuComposeFragment(R.string.dejavu_settings_tab_actions) {
    @Composable
    override fun DejavuScreen() {
        val settings = remember { dejavuSettings() }
        val customActions by settings.customActions.collectAsState()
        val placements = rememberPlacements(settings)
        val actions = RowAction.all(customActions)
        var choosing by remember { mutableStateOf<RowAction?>(null) }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.dejavu_settings_tab_actions_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            FilledButton(
                text = stringResource(R.string.dejavu_custom_action_add),
                icon = painterResource(iconsR.drawable.mozac_ic_plus_24),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                onClick = { openEditor(null) },
            )
            SettingsSectionHeader(
                text = stringResource(R.string.dejavu_settings_where_actions_show),
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp),
            )
            ActionList.entries.forEachIndexed { index, list ->
                IconListItem(
                    label = stringResource(list.place.label),
                    description = placeSummary(list.place, placements, customActions),
                    beforeIconPainter = painterResource(list.icon),
                    modifier = Modifier.settingsCard(index, ActionList.entries.size),
                    onClick = { openList(list) },
                )
            }
            SettingsSectionHeader(
                text = stringResource(R.string.dejavu_settings_all_actions),
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp),
            )
            actions.forEachIndexed { index, action ->
                val places = ActionPlace.entries.filter { placements.isIn(action, it) }
                IconListItem(
                    label = action.label,
                    description = if (places.isEmpty()) {
                        stringResource(R.string.dejavu_settings_action_places_none)
                    } else {
                        places.map { stringResource(it.label) }.joinToString(", ")
                    },
                    beforeIconPainter = painterResource(action.icon),
                    modifier = Modifier.settingsCard(index, actions.size),
                    onClick = { choosing = action },
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        choosing?.let { action ->
            ActionPlacesDialog(
                action = action,
                placements = placements,
                onPlace = { place, shown -> settings.setPlaced(action, place, shown) },
                onEdit = (action as? RowAction.Custom)?.let { custom ->
                    {
                        choosing = null
                        openEditor(custom.action.id)
                    }
                },
                onDismiss = { choosing = null },
            )
        }
    }

    private fun openList(list: ActionList) {
        findNavController().navigate(
            R.id.dejavuActionListFragment,
            Bundle().apply { putString(DejavuActionListFragment.ARG_LIST, list.name) },
        )
    }

    private fun openEditor(actionId: String?) {
        findNavController().navigate(
            R.id.dejavuCustomActionFragment,
            Bundle().apply { putString(DejavuCustomActionFragment.ARG_ACTION_ID, actionId) },
        )
    }
}

/** Chooses the buttons of the rows of one [ActionList], or the actions of the selection bar, from every action. */
class DejavuActionListFragment : DejavuComposeFragment(R.string.dejavu_settings_tab_actions) {
    private val list: ActionList
        get() = arguments?.getString(ARG_LIST)?.let { name -> ActionList.entries.firstOrNull { it.name == name } }
            ?: ActionList.SELECTION

    override val title: Int
        get() = list.place.label

    @Composable
    override fun DejavuScreen() {
        val settings = remember { dejavuSettings() }
        val customActions by settings.customActions.collectAsState()
        val place = list.place
        val actions = RowAction.available(place, customActions)

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (place == ActionPlace.SELECTION) {
                val hiddenKeys by settings.hiddenSelectionKeys.collectAsState()
                ActionSwitches(
                    title = R.string.dejavu_settings_actions,
                    hint = stringResource(list.hint),
                    actions = actions,
                    isChecked = { it.key !in hiddenKeys },
                    canCheckMore = true,
                    onChange = { key, on -> settings.setSelectionAction(key, enabled = on) },
                )
            } else {
                val keys by settings.rowKeys(place).collectAsState()
                ActionSwitches(
                    title = R.string.dejavu_settings_buttons,
                    hint = stringResource(list.hint, DejavuSettings.MAX_ROW_ACTIONS),
                    actions = actions,
                    isChecked = { it.key in keys },
                    canCheckMore = actions.count { it.key in keys } < DejavuSettings.MAX_ROW_ACTIONS,
                    onChange = { key, on -> settings.setRowAction(place, key = key, enabled = on) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    companion object {
        /** Name of the [ActionList] to show. */
        const val ARG_LIST = "list"
    }
}

/** What [place] shows: its buttons, or how many actions the selection bar has. */
@Composable
private fun placeSummary(place: ActionPlace, placements: Placements, customActions: List<CustomAction>): String {
    val available = RowAction.available(place, customActions)
    if (place == ActionPlace.SELECTION) {
        return stringResource(
            R.string.dejavu_settings_all_actions_summary,
            available.count { placements.isIn(it, place) },
            available.size,
        )
    }
    val shown = available.filter { placements.isIn(it, place) }.take(DejavuSettings.MAX_ROW_ACTIONS)
    return if (shown.isEmpty()) {
        stringResource(R.string.dejavu_settings_no_buttons)
    } else {
        shown.map { it.label }.joinToString(", ")
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
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
    )
    Text(
        text = hint,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    actions.forEachIndexed { index, action ->
        val checked = isChecked(action)
        SwitchListItem(
            label = action.label,
            checked = checked,
            enabled = checked || canCheckMore,
            showSwitchAfter = true,
            modifier = Modifier.settingsCard(index, actions.size),
            onClick = { onChange(action.key, it) },
        )
    }
}

/** Where every action is shown right now. */
private class Placements(
    private val rows: Map<ActionPlace, List<String>>,
    private val hiddenSelection: Set<String>,
    private val menu: Set<String>,
    private val bar: List<String>,
) {
    fun isIn(action: RowAction, place: ActionPlace): Boolean = action.fits(place) && when (place) {
        ActionPlace.SELECTION -> action.key !in hiddenSelection
        ActionPlace.MORE_MENU -> action.menuKey in menu
        ActionPlace.ACTIONS_BAR -> action.menuKey in bar
        else -> action.key in rows[place].orEmpty()
    }

    /** Whether [place] has no room for one more action. */
    fun isFull(place: ActionPlace): Boolean = when {
        place.isRow -> rows[place].orEmpty().size >= DejavuSettings.MAX_ROW_ACTIONS
        place == ActionPlace.ACTIONS_BAR -> bar.size >= ActionsBarLayout.MAX_BUTTONS
        else -> false
    }
}

@Composable
private fun rememberPlacements(settings: DejavuSettings): Placements {
    val pinned by settings.pinnedRowKeys.collectAsState()
    val unpinned by settings.unpinnedRowKeys.collectAsState()
    val folders by settings.folderRowKeys.collectAsState()
    val hidden by settings.hiddenSelectionKeys.collectAsState()
    val menuRows by settings.moreMenuRows.collectAsState()
    val bar by settings.actionsBarKeys.collectAsState()
    return Placements(
        rows = mapOf(
            ActionPlace.PINNED_ROWS to pinned,
            ActionPlace.UNPINNED_ROWS to unpinned,
            ActionPlace.FOLDER_ROWS to folders,
        ),
        hiddenSelection = hidden,
        menu = menuRows.flatten().toSet(),
        bar = bar,
    )
}

/** Shows [action] in [place], or stops showing it there. */
private fun DejavuSettings.setPlaced(action: RowAction, place: ActionPlace, shown: Boolean) {
    when (place) {
        ActionPlace.SELECTION -> setSelectionAction(action.key, enabled = shown)
        ActionPlace.MORE_MENU -> action.menuKey?.let { if (shown) addToMoreMenu(it) else removeFromMoreMenu(it) }
        ActionPlace.ACTIONS_BAR -> action.menuKey?.let { if (shown) addToActionsBar(it) else removeFromActionsBar(it) }
        else -> setRowAction(place, key = action.key, enabled = shown)
    }
}

/** Chooses every place [action] shows in. A custom action can be edited from here too, with [onEdit]. */
@Composable
private fun ActionPlacesDialog(
    action: RowAction,
    placements: Placements,
    onPlace: (ActionPlace, Boolean) -> Unit,
    onEdit: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painter = painterResource(action.icon), contentDescription = null) },
        title = { Text(action.label) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ActionPlace.entries.forEach { place ->
                    val checked = placements.isIn(action, place)
                    val fits = action.fits(place)
                    val full = !checked && placements.isFull(place)
                    PlaceRow(
                        label = stringResource(place.label),
                        note = when {
                            !fits -> stringResource(R.string.dejavu_settings_action_place_unavailable)
                            full -> stringResource(R.string.dejavu_settings_action_place_full)
                            else -> null
                        },
                        checked = checked,
                        enabled = fits && !full,
                        onChange = { onPlace(place, it) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dejavu_done)) } },
        dismissButton = onEdit?.let { edit -> { TextButton(onClick = edit) { Text(stringResource(R.string.dejavu_menu_edit)) } } },
    )
}

@Composable
private fun PlaceRow(label: String, note: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .alpha(if (enabled) 1f else PLACE_DISABLED_ALPHA)
            .padding(vertical = 2.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (note != null) {
                Text(text = note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val PLACE_DISABLED_ALPHA = 0.6f

/** Lists, adds, edits and deletes containers. */
class DejavuContainersFragment : DejavuComposeFragment(R.string.dejavu_settings_containers) {
    @Composable
    override fun DejavuScreen() {
        val storage = remember { DejavuContainerStorage.get(requireContext()) }
        val settings = remember { dejavuSettings() }
        val essentialsPerContainer by settings.essentialsPerContainer.collectAsState()
        val temporaryByDefault by settings.temporaryContainersByDefault.collectAsState()
        val records by storage.records.collectAsState()
        val containers = records?.filterNot { it.temporary }
        val scope = rememberCoroutineScope()
        var editing by remember { mutableStateOf<ContainerRecord?>(null) }
        var adding by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf<ContainerRecord?>(null) }
        LaunchedEffect(Unit) { storage.load() }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = stringResource(R.string.dejavu_settings_containers_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                SwitchListItem(
                    label = stringResource(R.string.dejavu_temporary_by_default),
                    description = stringResource(R.string.dejavu_temporary_by_default_summary),
                    maxDescriptionLines = 4,
                    checked = temporaryByDefault,
                    showSwitchAfter = true,
                    modifier = Modifier.settingsCard(index = 0, count = 2),
                    onClick = settings::setTemporaryContainersByDefault,
                )
            }
            item {
                SwitchListItem(
                    label = stringResource(R.string.dejavu_essentials_per_container),
                    description = stringResource(R.string.dejavu_essentials_per_container_summary),
                    maxDescriptionLines = 3,
                    checked = essentialsPerContainer,
                    showSwitchAfter = true,
                    modifier = Modifier.settingsCard(index = 1, count = 2),
                    onClick = settings::setEssentialsPerContainer,
                )
            }
            val containerRows = containers.orEmpty().size + 1
            itemsIndexed(containers.orEmpty(), key = { _, record -> record.contextId }) { index, record ->
                IconListItem(
                    label = record.name,
                    beforeIconPainter = painterResource(record.icon.drawable),
                    beforeIconTint = record.color.color,
                    modifier = Modifier.settingsCard(index, containerRows),
                    onClick = { editing = record },
                )
            }
            item {
                IconListItem(
                    label = stringResource(R.string.dejavu_container_add),
                    beforeIconPainter = painterResource(iconsR.drawable.mozac_ic_plus_24),
                    modifier = Modifier.settingsCard(containerRows - 1, containerRows),
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

/** Chooses the workspace and container that links from other apps open in. */
class DejavuExternalLinksFragment : DejavuComposeFragment(R.string.dejavu_settings_external_links) {
    @Composable
    override fun DejavuScreen() {
        val settings = remember { dejavuSettings() }
        val repository = remember {
            requireComponents.strictMode.allowViolation(StrictMode::allowThreadDiskReads) {
                WorkspaceRepository.get(requireContext())
            }
        }
        val storage = remember { DejavuContainerStorage.get(requireContext()) }
        val workspaces by repository.state.collectAsState()
        val records by storage.records.collectAsState()
        val workspaceId by settings.externalLinkWorkspaceId.collectAsState()
        val container by settings.externalLinkContainer.collectAsState()
        LaunchedEffect(Unit) { storage.load() }
        val chosenWorkspace = workspaces.workspaces.firstOrNull { it.id == workspaceId }
        val fenixSettings = requireComponents.settings
        var openInPrivate by remember { mutableStateOf(fenixSettings.openLinksInAPrivateTab) }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.dejavu_external_links_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            SwitchListItem(
                label = stringResource(R.string.dejavu_external_links_private),
                description = stringResource(R.string.dejavu_external_links_private_summary),
                maxDescriptionLines = 3,
                checked = openInPrivate,
                showSwitchAfter = true,
                modifier = Modifier.settingsCard(index = 0, count = 1),
                onClick = { checked ->
                    fenixSettings.openLinksInAPrivateTab = checked
                    openInPrivate = checked
                },
            )
            Column(modifier = Modifier.alpha(if (openInPrivate) DISABLED_ALPHA else 1f)) {
                SettingsSectionHeader(
                    text = stringResource(R.string.dejavu_external_links_workspace),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                val workspaceRows = workspaces.workspaces.size + 1
                ChoiceRow(
                    label = stringResource(R.string.dejavu_external_links_last_workspace),
                    selected = chosenWorkspace == null,
                    modifier = Modifier.settingsCard(0, workspaceRows),
                    onClick = { settings.setExternalLinkWorkspace(null) },
                )
                workspaces.workspaces.forEachIndexed { index, workspace ->
                    ChoiceRow(
                        label = listOfNotNull(workspaceIconText(workspace.icon), workspace.name).joinToString("  "),
                        selected = workspace.id == chosenWorkspace?.id,
                        modifier = Modifier.settingsCard(index + 1, workspaceRows),
                        onClick = { settings.setExternalLinkWorkspace(workspace.id) },
                    )
                }
                SettingsSectionHeader(
                    text = stringResource(R.string.dejavu_external_links_container),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
                val permanent = records.orEmpty().filterNot { it.temporary }
                val containerRows = permanent.size + 3
                ChoiceRow(
                    label = stringResource(R.string.dejavu_external_links_workspace_container),
                    selected = container == null,
                    modifier = Modifier.settingsCard(0, containerRows),
                    onClick = { settings.setExternalLinkContainer(null) },
                )
                ChoiceRow(
                    label = stringResource(R.string.dejavu_workspace_no_container),
                    selected = container == ContainerPick.NoContainer,
                    leading = { NoContainerIcon() },
                    modifier = Modifier.settingsCard(1, containerRows),
                    onClick = { settings.setExternalLinkContainer(ContainerPick.NoContainer) },
                )
                ChoiceRow(
                    label = stringResource(R.string.dejavu_new_temporary_container),
                    selected = container == ContainerPick.Temporary,
                    leading = { TemporaryContainerIcon() },
                    modifier = Modifier.settingsCard(2, containerRows),
                    onClick = { settings.setExternalLinkContainer(ContainerPick.Temporary) },
                )
                permanent.forEachIndexed { index, record ->
                    val pick = ContainerPick.Container(record.contextId)
                    ChoiceRow(
                        label = record.name,
                        selected = container == pick,
                        leading = { ContainerIcon(record) },
                        modifier = Modifier.settingsCard(index + 3, containerRows),
                        onClick = { settings.setExternalLinkContainer(pick) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    private companion object {
        const val DISABLED_ALPHA = 0.4f
    }

    @Composable
    private fun ChoiceRow(
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        leading: (@Composable () -> Unit)? = null,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick)
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

internal fun Fragment.dejavuSettings(): DejavuSettings =
    requireComponents.strictMode.allowViolation(StrictMode::allowThreadDiskReads) {
        DejavuSettings.get(requireContext())
    }
