/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.settings

import android.content.Context
import android.os.StrictMode
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.actions.CustomAction
import org.mozilla.fenix.dejavu.actions.RowAction
import org.mozilla.fenix.dejavu.actions.ShareTarget
import org.mozilla.fenix.dejavu.actions.namedShareScreens
import org.mozilla.fenix.dejavu.actions.queryShareScreens
import org.mozilla.fenix.ext.components

/**
 * Offers to add a share action for an app held in the share sheet. Once the user agrees, the action is saved and put in
 * the browser's "More" menu, see [addShareAction], and a short message tells where to find it.
 *
 * @param label What the share sheet calls the app, for when the app can no longer be asked.
 * @param packageName Package of the app.
 * @param activityName The share screen of the app that was held.
 */
fun offerShareAction(context: Context, label: String, packageName: String, activityName: String) {
    val name = namedShareScreens(context, queryShareScreens(context, packageName))
        .firstOrNull { it.activityName == activityName }?.name ?: label
    MaterialAlertDialogBuilder(context)
        .setTitle(name)
        .setMessage(R.string.dejavu_share_add_to_actions_text)
        .setNegativeButton(R.string.dejavu_cancel, null)
        .setPositiveButton(R.string.dejavu_share_add_to_actions) { _, _ ->
            val action = addShareAction(context, name, ShareTarget(packageName, activityName))
            val where = TAB_ACTIONS_PATH.joinToString(" › ") { context.getString(it) }
            val message = context.getString(R.string.dejavu_share_added, action.name, where)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
        .show()
}

/** The settings screens the user goes through to the tab actions, where share actions are moved and edited. */
private val TAB_ACTIONS_PATH = listOf(
    R.string.dejavu_settings,
    R.string.preferences_customize,
    R.string.dejavu_settings_tab_actions,
)

/**
 * Saves a share action named [name] that shares to [target], unless one already does, and puts it at the end of the
 * browser's "More" menu.
 *
 * @return The action, the new one or the one there was.
 */
fun addShareAction(context: Context, name: String, target: ShareTarget): CustomAction {
    val settings = context.components.strictMode.allowViolation(StrictMode::allowThreadDiskReads) {
        DejavuSettings.get(context)
    }
    val action = settings.customActions.value.firstOrNull { it.share == target }
        ?: CustomAction.shareAction(UUID.randomUUID().toString(), name, target).also(settings::saveCustomAction)
    settings.addToMoreMenu(RowAction.keyOf(action.id))
    return action
}
