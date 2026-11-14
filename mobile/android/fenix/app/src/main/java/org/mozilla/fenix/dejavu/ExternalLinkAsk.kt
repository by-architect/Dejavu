/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mozilla.components.feature.customtabs.isCustomTabIntent
import mozilla.components.support.utils.toSafeIntent
import org.mozilla.fenix.dejavu.browser.DejavuExternalLinkFragment
import org.mozilla.fenix.dejavu.containers.ContainerPick
import org.mozilla.fenix.dejavu.settings.DejavuSettings

/**
 * Links from other apps that first ask where they open, when [DejavuSettings.externalLinkAsk] is on. Instead of opening
 * the link right away, the intent that brought it waits here while [DejavuExternalLinkFragment] asks for a workspace and
 * a container; it then opens there through [ExternalLinkChoice]. Nothing of the link loads before the user chose.
 */
object ExternalLinkAsk {
    private val _pending = MutableStateFlow<Intent?>(null)

    /** The intent of the link waiting for the user to choose where it opens. */
    val pending: StateFlow<Intent?> = _pending.asStateFlow()

    /**
     * Keeps [intent] to ask where its link opens, and returns `true`, when links ask and [intent] would open one in a
     * normal tab: a web address to view or a text shared with Dejavu. Private links, custom tabs and the other intents
     * go on as before, and `false` is returned.
     */
    fun holds(context: Context, intent: Intent, private: Boolean): Boolean {
        if (private || !DejavuSettings.get(context).externalLinkAsk.value) return false
        val safe = intent.toSafeIntent()
        if (isCustomTabIntent(safe)) return false
        val opensTab = when (safe.action) {
            Intent.ACTION_VIEW -> safe.dataString?.let { it.startsWith("https://") || it.startsWith("http://") } == true
            Intent.ACTION_SEND -> !safe.getStringExtra(Intent.EXTRA_TEXT).isNullOrBlank()
            else -> false
        }
        if (opensTab) _pending.value = Intent(intent)
        return opensTab
    }

    /** Takes the waiting intent, which then no longer waits. */
    fun take(): Intent? = _pending.value.also { _pending.value = null }

    /** Shows [DejavuExternalLinkFragment] over [activity] whenever a link waits, while the activity is in front. */
    fun askWhenPending(activity: FragmentActivity) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                pending.collect { intent ->
                    val manager = activity.supportFragmentManager
                    if (intent != null && manager.findFragmentByTag(DejavuExternalLinkFragment.TAG) == null &&
                        !manager.isStateSaved
                    ) {
                        DejavuExternalLinkFragment().show(manager, DejavuExternalLinkFragment.TAG)
                    }
                }
            }
        }
    }
}

/**
 * Where the next link from another app opens, as chosen in [DejavuExternalLinkFragment]: it overrides the workspace and
 * the container chosen in settings once, see [WorkspaceContainerMiddleware], and is dropped when it expires.
 */
object ExternalLinkChoice {
    private const val LIFETIME_MS = 10_000L

    /**
     * @property workspaceId The workspace the link opens in.
     * @property pick Its container, or `null` for the one of the workspace.
     */
    internal class Choice(val workspaceId: String, val pick: ContainerPick?, val expiresAt: Long)

    @Volatile
    private var choice: Choice? = null

    fun set(workspaceId: String, pick: ContainerPick?) {
        choice = Choice(workspaceId, pick, expiresAt = SystemClock.elapsedRealtime() + LIFETIME_MS)
    }

    /** Returns and forgets the choice, if it has not expired. */
    internal fun consume(): Choice? {
        val current = choice ?: return null
        choice = null
        return current.takeIf { SystemClock.elapsedRealtime() < current.expiresAt }
    }
}
