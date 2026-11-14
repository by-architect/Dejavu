/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.actions

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.StrictMode
import android.os.SystemClock
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.settings.offerShareAction
import org.mozilla.fenix.ext.components

/**
 * The app the user last shared to through Android's own share sheet, which Dejavu uses from Android 14 on, instead of
 * its own where an app can be held to add it as a share action. Android tells which app was chosen to
 * [ShareChoiceReceiver]; once Dejavu shows again, it offers to add that app as a share action, once for each app.
 */
object ShareChoices {
    @Volatile
    private var chosen: ComponentName? = null

    @Volatile
    private var chosenAt = 0L

    /** What the share sheet tells the app the user chose to, see [ShareChoiceReceiver]. */
    fun callback(context: Context): IntentSender = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ShareChoiceReceiver::class.java),
        // Mutable, so Android can add the chosen app to it.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    ).intentSender

    internal fun record(component: ComponentName) {
        chosen = component
        chosenAt = SystemClock.elapsedRealtime()
    }

    /**
     * Offers to add the app the user just shared to as a share action, unless one already shares to it or Dejavu
     * offered it before.
     */
    fun offerIfChosen(activity: Activity) {
        val component = chosen?.takeIf { SystemClock.elapsedRealtime() - chosenAt < OFFER_WINDOW_MS } ?: return
        chosen = null
        val key = component.flattenToShortString()
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val offered = prefs.getStringSet(KEY_OFFERED, null).orEmpty()
        val settings = activity.components.strictMode.allowViolation(StrictMode::allowThreadDiskReads) {
            DejavuSettings.get(activity)
        }
        val isAction = settings.customActions.value.any { action ->
            action.share?.let { it.packageName == component.packageName && it.activityName == component.className } == true
        }
        if (key in offered || isAction) return
        val screens = queryShareScreens(activity, component.packageName)
        val name = namedShareScreens(activity, screens).firstOrNull { it.activityName == component.className }?.name
            ?: return
        prefs.edit { putStringSet(KEY_OFFERED, offered + key) }
        offerShareAction(activity, name, component.packageName, component.className)
    }

    // A choice older than this was not made just before Dejavu showed again.
    private const val OFFER_WINDOW_MS = 30 * 60 * 1000L
    private const val PREFS_NAME = "dejavu_share_offers"
    private const val KEY_OFFERED = "offered"
}

/** Hears from Android's share sheet which app the user shared to, see [ShareChoices]. */
class ShareChoiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
            ?.let(ShareChoices::record)
    }
}
