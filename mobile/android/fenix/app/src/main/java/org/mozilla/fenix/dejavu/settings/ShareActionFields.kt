/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.feature.share.RecentAppsStorage
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.actions.DirectShare
import org.mozilla.fenix.dejavu.actions.RecipientKind
import org.mozilla.fenix.dejavu.actions.ShareScreen
import org.mozilla.fenix.dejavu.actions.ShareTarget
import org.mozilla.fenix.dejavu.actions.namedShareScreens
import org.mozilla.fenix.dejavu.actions.queryShareScreens
import org.mozilla.fenix.dejavu.ui.DejavuDialog
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.DejavuPopup
import org.mozilla.fenix.dejavu.ui.DejavuTextField

/**
 * A share screen in the app picker of the custom action editor.
 *
 * @property screen The share screen, with the name share actions give it.
 * @property icon The icon of the share screen, `null` when it could not be read.
 * @property recent Whether the user shared to it lately from the share sheet.
 */
internal class ShareApp(val screen: ShareScreen, val icon: ImageBitmap?, val recent: Boolean)

/** Whether this is the share screen of [target]. */
internal fun ShareApp.isFor(target: ShareTarget?): Boolean =
    target != null && screen.packageName == target.packageName && screen.activityName == target.activityName

/**
 * Every share screen for text with its icon: the ones the user shared to lately from the share sheet first, as the
 * share sheet orders them, then the others by name.
 */
internal suspend fun loadShareApps(context: Context): List<ShareApp> = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val infos = queryShareScreens(appContext)
    val screens = namedShareScreens(appContext, infos)
    // The share sheet scores every share screen it lists; only the ones shared to at all have a score.
    val recentRanks = runCatching { RecentAppsStorage(appContext).getRecentAppsUpTo(RECENT_APPS) }
        .getOrDefault(emptyList())
        .filter { it.score > 0 }
        .withIndex()
        .associate { (rank, app) -> app.activityName to rank }
    val packageManager = appContext.packageManager
    val iconSize = (APP_ICON_SIZE.value * appContext.resources.displayMetrics.density).roundToInt()
    val apps = screens.zip(infos) { screen, info ->
        val icon = runCatching { info.loadIcon(packageManager).toBitmap(iconSize, iconSize).asImageBitmap() }
        ShareApp(screen, icon.getOrNull(), recent = screen.activityName in recentRanks)
    }
    apps.sortedWith(
        compareBy<ShareApp> { recentRanks[it.screen.activityName] ?: Int.MAX_VALUE }
            .thenBy(Collator.getInstance()) { it.screen.name },
    )
}

/**
 * The name a share action to [screenName] gets, with [recipientName] after it when it sends straight to someone, like
 * "WhatsApp: Alice".
 */
internal fun shareActionName(context: Context, screenName: String, recipientName: String?): String =
    recipientName?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { context.getString(R.string.dejavu_share_action_name_recipient, screenName, it) }
        ?: screenName

/**
 * The fields of a share action in the custom action editor: the app it shares to, and who it sends straight to in the
 * apps that can open a chat with one person.
 *
 * @param apps Every share screen, see [loadShareApps], or `null` while they load.
 * @param picked The share screen picked, `null` before one is.
 * @param direct How the app picked reaches one person directly, `null` when it cannot.
 * @param recipient What the recipient field holds.
 * @param onPick Invoked with the share screen the user picks.
 * @param onRecipientChange Invoked with what the recipient field holds now, and the name of the contact it was picked
 * from, if it was.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun ShareActionFields(
    apps: List<ShareApp>?,
    picked: ShareTarget?,
    direct: DirectShare?,
    recipient: TextFieldValue,
    onPick: (ShareApp) -> Unit,
    onRecipientChange: (TextFieldValue, String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val kind = direct?.kind
        if (result.resultCode == Activity.RESULT_OK && uri != null && kind != null) {
            scope.launch {
                readContact(context, uri, kind)?.let { (contactName, address) ->
                    onRecipientChange(TextFieldValue(address, TextRange(address.length)), contactName)
                }
            }
        }
    }

    SectionLabel(R.string.dejavu_share_action_app)
    PickedApp(
        app = apps?.firstOrNull { it.isFor(picked) },
        loading = apps == null && picked != null,
        onClick = { choosing = true },
    )

    if (direct != null) {
        val kind = direct.kind
        SectionLabel(R.string.dejavu_share_action_recipient)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DejavuTextField(
                value = recipient,
                onValueChange = { onRecipientChange(it, null) },
                label = stringResource(kind.hint),
                keyboardOptions = KeyboardOptions(keyboardType = kind.keyboardType),
                isError = recipient.text.isNotBlank() && !kind.isValid(recipient.text),
                modifier = Modifier.weight(1f),
            )
            if (kind != RecipientKind.USERNAME) {
                IconButton(
                    onClick = {
                        val rows = if (kind == RecipientKind.EMAIL) Email.CONTENT_URI else Phone.CONTENT_URI
                        try {
                            contactPicker.launch(Intent(Intent.ACTION_PICK, rows))
                        } catch (_: ActivityNotFoundException) {
                            // Without a contacts app, the number or address can still be typed.
                        }
                    },
                ) {
                    Icon(
                        painter = painterResource(iconsR.drawable.mozac_ic_avatar_circle_24),
                        contentDescription = stringResource(R.string.dejavu_share_action_pick_contact),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.dejavu_share_action_recipient_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Text(
        text = stringResource(R.string.dejavu_share_action_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (choosing) {
        AppPickerDialog(
            apps = apps,
            picked = picked,
            onPick = { app ->
                choosing = false
                onPick(app)
            },
            onDismiss = { choosing = false },
        )
    }
}

/** The app picked, or a prompt to choose one, which opens the [AppPickerDialog] when tapped. */
@Composable
private fun PickedApp(app: ShareApp?, loading: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.size(APP_ICON_SIZE), strokeWidth = 2.dp)
            app != null -> AppIcon(app.icon)
        }
        if (loading || app != null) Spacer(Modifier.width(14.dp))
        Text(
            text = when {
                loading -> ""
                app != null -> app.screen.name
                else -> stringResource(R.string.dejavu_share_action_choose_app)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (app != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(iconsR.drawable.mozac_ic_chevron_down_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Lists every share screen, the recently used ones under a heading of their own, to pick the one an action uses. */
@Composable
private fun AppPickerDialog(
    apps: List<ShareApp>?,
    picked: ShareTarget?,
    onPick: (ShareApp) -> Unit,
    onDismiss: () -> Unit,
) {
    DejavuDialog(
        title = stringResource(R.string.dejavu_share_action_choose_app),
        onDismissRequest = onDismiss,
        buttons = { DejavuDialogButton(text = stringResource(R.string.dejavu_cancel), onClick = onDismiss) },
    ) {
        if (apps == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp).size(24.dp),
                strokeWidth = 2.dp,
            )
        } else {
            val (recent, others) = apps.partition { it.recent }
            if (recent.isNotEmpty()) {
                PickerHeading(stringResource(R.string.share_link_recent_apps_subheader))
                recent.forEach { app -> AppChoice(app, selected = app.isFor(picked), onClick = { onPick(app) }) }
                PickerHeading(stringResource(R.string.dejavu_share_action_all_apps))
            }
            others.forEach { app -> AppChoice(app, selected = app.isFor(picked), onClick = { onPick(app) }) }
        }
    }
}

@Composable
private fun PickerHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun AppChoice(app: ShareApp, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(DejavuPopup.ItemShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        AppIcon(app.icon)
        Spacer(Modifier.width(16.dp))
        Text(
            text = app.screen.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.run { if (selected) onSecondaryContainer else onSurface },
        )
    }
}

@Composable
private fun AppIcon(icon: ImageBitmap?) {
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(APP_ICON_SIZE))
    } else {
        Icon(
            painter = painterResource(R.drawable.dejavu_ic_share_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(APP_ICON_SIZE),
        )
    }
}

private val RecipientKind.keyboardType: KeyboardType
    get() = when (this) {
        RecipientKind.PHONE -> KeyboardType.Phone
        RecipientKind.EMAIL -> KeyboardType.Email
        RecipientKind.USERNAME -> KeyboardType.Ascii
    }

/**
 * The name and the phone number or email address of the contact at [uri], a row the system's contact picker returned.
 * Reading the row picked needs no permission. The name is the address itself for a contact without one.
 */
private suspend fun readContact(context: Context, uri: Uri, kind: RecipientKind): Pair<String, String>? =
    withContext(Dispatchers.IO) {
        val columns = arrayOf(
            ContactsContract.Contacts.DISPLAY_NAME,
            if (kind == RecipientKind.EMAIL) Email.ADDRESS else Phone.NUMBER,
        )
        runCatching {
            context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val contactName = cursor.getString(0)?.trim().orEmpty()
                val address = cursor.getString(1)?.trim().orEmpty()
                if (address.isEmpty()) null else contactName.ifEmpty { address } to address
            }
        }.getOrNull()
    }

private val APP_ICON_SIZE = 32.dp

/** How many share screens count as recently used, as many as the share sheet shows. */
private const val RECENT_APPS = 6
