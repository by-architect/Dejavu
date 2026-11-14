/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import mozilla.components.support.utils.toSafeIntent
import org.mozilla.fenix.BrowserDirection
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.browser.browsingmode.BrowsingMode
import org.mozilla.fenix.dejavu.ExternalLinkAsk
import org.mozilla.fenix.dejavu.ExternalLinkChoice
import org.mozilla.fenix.dejavu.containers.ContainerIcon
import org.mozilla.fenix.dejavu.containers.ContainerPick
import org.mozilla.fenix.dejavu.containers.ContainerRecord
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.containers.NoContainerIcon
import org.mozilla.fenix.dejavu.containers.TemporaryContainerIcon
import org.mozilla.fenix.dejavu.settings.dejavuSettings
import org.mozilla.fenix.dejavu.settings.workspaceRepository
import org.mozilla.fenix.dejavu.sync.workspaceIconText
import org.mozilla.fenix.dejavu.ui.DejavuButtonStyle
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.DejavuDialogCard
import org.mozilla.fenix.dejavu.ui.LocalPopupTheme
import org.mozilla.fenix.dejavu.ui.workspacePopupTheme
import org.mozilla.fenix.dejavu.workspaces.Workspace
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Asks where the link from another app that [ExternalLinkAsk] holds opens: in which workspace, and in which container
 * or in a private tab. It starts from the choices of the external links settings. Opening it processes the link's
 * intent as Dejavu would have right away; cancelling drops the link.
 */
class DejavuExternalLinkFragment : DialogFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                FirefoxTheme {
                    val repository = remember { workspaceRepository() }
                    val settings = remember { dejavuSettings() }
                    val storage = remember { DejavuContainerStorage.get(requireContext()) }
                    val state by repository.state.collectAsState()
                    val records by storage.records.collectAsState()
                    val pending by ExternalLinkAsk.pending.collectAsState()
                    LaunchedEffect(Unit) { storage.load() }
                    // Taken by another copy of this dialog, or by the user from somewhere else.
                    LaunchedEffect(pending) { if (pending == null) dismissAllowingStateLoss() }
                    val link = pending?.linkText().orEmpty()
                    val workspaces = state.workspaces.filter { it.device == null }
                    val startWorkspace = settings.externalLinkWorkspaceId.value
                        ?.takeIf { id -> workspaces.any { it.id == id } }
                        ?: state.activeWorkspaceId.takeIf { id -> workspaces.any { it.id == id } }
                        ?: workspaces.firstOrNull()?.id

                    CompositionLocalProvider(LocalPopupTheme provides workspacePopupTheme(state.activeWorkspaceId)) {
                        ExternalLinkChooser(
                            link = link,
                            workspaces = workspaces,
                            containers = records.orEmpty().filterNot { it.showsAsTemporary },
                            startWorkspaceId = startWorkspace,
                            startPlace = settings.externalLinkContainer.value?.let(LinkPlace::Pick) ?: LinkPlace.Default,
                            onOpen = ::open,
                            onCancel = ::cancel,
                        )
                    }
                }
            }
        }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        ExternalLinkAsk.take()
    }

    private fun cancel() {
        ExternalLinkAsk.take()
        dismissAllowingStateLoss()
    }

    /** Opens the waiting link in [workspaceId], in the container or private tab of [place]. */
    private fun open(workspaceId: String?, place: LinkPlace) {
        val intent = ExternalLinkAsk.take()
        val activity = activity as? HomeActivity
        dismissAllowingStateLoss()
        if (intent == null || activity == null) return
        val processors = requireComponents.intentProcessors
        val processor = if (place == LinkPlace.Private) {
            activity.browsingModeManager.mode = BrowsingMode.Private
            processors.privateIntentProcessor
        } else {
            if (workspaceId != null) ExternalLinkChoice.set(workspaceId, (place as? LinkPlace.Pick)?.pick)
            processors.intentProcessor
        }
        if (processor.process(intent)) activity.openToBrowser(BrowserDirection.FromGlobal)
    }

    companion object {
        const val TAG = "DejavuExternalLink"
    }
}

/** Where a link from another app opens, besides its workspace. */
private sealed interface LinkPlace {
    /** In the container of its workspace, or in none when the workspace has none. */
    data object Default : LinkPlace

    data class Pick(val pick: ContainerPick) : LinkPlace

    data object Private : LinkPlace
}

/** The address or the shared text the intent brings, as the dialog shows it. */
private fun Intent.linkText(): String {
    val safe = toSafeIntent()
    return safe.dataString ?: safe.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
}

@Suppress("LongParameterList", "LongMethod")
@Composable
private fun ExternalLinkChooser(
    link: String,
    workspaces: List<Workspace>,
    containers: List<ContainerRecord>,
    startWorkspaceId: String?,
    startPlace: LinkPlace,
    onOpen: (String?, LinkPlace) -> Unit,
    onCancel: () -> Unit,
) {
    var workspaceId by rememberSaveable { mutableStateOf(startWorkspaceId) }
    var place by remember { mutableStateOf(startPlace) }
    val workspace = workspaces.firstOrNull { it.id == workspaceId }
    val workspaceContainer = workspace?.containerId?.let { id -> containers.firstOrNull { it.contextId == id } }

    DejavuDialogCard(
        title = stringResource(R.string.dejavu_external_link_ask_title),
        icon = R.drawable.dejavu_ic_new_tab_24,
        buttons = {
            DejavuDialogButton(text = stringResource(R.string.dejavu_cancel), onClick = onCancel)
            DejavuDialogButton(
                text = stringResource(R.string.dejavu_external_link_ask_open),
                style = DejavuButtonStyle.Primary,
                enabled = workspace != null || place == LinkPlace.Private,
                onClick = { onOpen(workspaceId, place) },
            )
        },
    ) {
        Text(
            text = link,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val privateChosen = place == LinkPlace.Private
        Label(R.string.dejavu_external_links_workspace)
        ChipRow {
            workspaces.forEach { option ->
                Chip(
                    text = listOfNotNull(workspaceIconText(option.icon), option.name).joinToString("  "),
                    selected = option.id == workspaceId && !privateChosen,
                    enabled = !privateChosen,
                    onClick = { workspaceId = option.id },
                )
            }
        }
        Label(R.string.dejavu_external_links_container)
        ChipRow {
            Chip(
                text = workspaceContainer?.name?.let { stringResource(R.string.dejavu_external_link_ask_workspace_container, it) }
                    ?: stringResource(R.string.dejavu_external_link_ask_default),
                selected = place == LinkPlace.Default,
                leading = workspaceContainer?.let { record -> { ContainerIcon(record, size = 16.dp) } },
                onClick = { place = LinkPlace.Default },
            )
            Chip(
                text = stringResource(R.string.dejavu_workspace_no_container),
                selected = place == LinkPlace.Pick(ContainerPick.NoContainer),
                leading = { NoContainerIcon(size = 16.dp) },
                onClick = { place = LinkPlace.Pick(ContainerPick.NoContainer) },
            )
            containers.forEach { record ->
                val pick = LinkPlace.Pick(ContainerPick.Container(record.contextId))
                Chip(
                    text = record.name,
                    selected = place == pick,
                    leading = { ContainerIcon(record, size = 16.dp) },
                    onClick = { place = pick },
                )
            }
            Chip(
                text = stringResource(R.string.dejavu_new_temporary_container),
                selected = place == LinkPlace.Pick(ContainerPick.Temporary),
                leading = { TemporaryContainerIcon(size = 16.dp) },
                onClick = { place = LinkPlace.Pick(ContainerPick.Temporary) },
            )
            Chip(
                text = stringResource(R.string.dejavu_external_link_ask_private),
                selected = privateChosen,
                leading = {
                    Icon(
                        painter = painterResource(R.drawable.dejavu_ic_private_tab_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(1.dp).heightIn(max = 16.dp),
                    )
                },
                onClick = { place = LinkPlace.Private },
            )
        }
    }
}

@Composable
private fun Label(text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        content()
    }
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(36.dp)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.border(BorderStroke(2.dp, colors.primary), CircleShape)
                } else {
                    Modifier.border(BorderStroke(1.dp, colors.outlineVariant), CircleShape)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
