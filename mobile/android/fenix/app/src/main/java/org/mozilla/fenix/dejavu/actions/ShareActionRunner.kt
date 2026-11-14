/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import mozilla.components.support.ktx.kotlin.isExtensionUrl
import mozilla.components.support.utils.ext.packageManagerCompatHelper
import org.mozilla.fenix.R

/**
 * Runs share actions, the [CustomAction]s with a [ShareTarget]. The target's app opens with the links of the tabs, one
 * per line, ready to send: in a chat with the target's recipient in the apps that can open one, see [DirectShare], and
 * otherwise on the target's share screen.
 */
object ShareActionRunner {
    /**
     * Shares [links], the address and title of every tab, to the app of [action].
     *
     * @return A short message for the user when the app could not be opened, `null` when it was.
     */
    fun run(context: Context, action: CustomAction, links: List<Pair<String, String>>): String? {
        val target = action.share ?: return null
        val pages = links.filter { it.first.isNotBlank() }
        if (pages.isEmpty()) return null
        val text = pages.joinToString("\n") { shareableUrl(it.first) }
        val subject = pages.singleOrNull()?.second?.takeIf { it.isNotBlank() }
        // An app that no longer opens chats from links still has its share screen, where the user picks the chat.
        val chat = chatIntent(context, target, text, subject)
        if (chat != null && start(context, chat)) return null
        val screen = shareScreenIntent(context, target, text, subject)
        return when {
            screen != null && start(context, screen) -> null
            !isInstalled(context, target.packageName) ->
                context.getString(R.string.dejavu_share_action_not_installed, action.name)
            else -> context.getString(R.string.dejavu_share_action_failed, action.name)
        }
    }

    /** [url] as it is shared: for a page in reader view, the page's own address, as Firefox's share sheet does. */
    private fun shareableUrl(url: String): String =
        if (url.isExtensionUrl()) url.toUri().getQueryParameter("url") ?: url else url

    /** What opens a chat with the recipient of [target], or `null` when it has none, or one its app cannot take. */
    private fun chatIntent(context: Context, target: ShareTarget, text: String, subject: String?): Intent? {
        val recipient = target.recipient ?: return null
        val direct = DirectShare.of(context, target.packageName) ?: return null
        if (!direct.kind.isValid(recipient)) return null
        val to = direct.kind.normalize(recipient)
        val intent = when (direct) {
            DirectShare.WHATSAPP -> Intent(
                Intent.ACTION_VIEW,
                Uri.Builder().scheme("https").authority(WHATSAPP_HOST).appendPath("send")
                    // WhatsApp takes the number with its country code but without "+" or the "00" dialed before it.
                    .appendQueryParameter("phone", to.removePrefix("+").removePrefix("00"))
                    .appendQueryParameter("text", text)
                    .build(),
            )
            DirectShare.TELEGRAM -> Intent(
                Intent.ACTION_VIEW,
                Uri.Builder().scheme("https").authority(TELEGRAM_HOST).appendPath(to)
                    .appendQueryParameter("text", text)
                    .build(),
            )
            DirectShare.SMS -> Intent(Intent.ACTION_SENDTO, "smsto:$to".toUri()).putExtra(SMS_BODY, text)
            DirectShare.EMAIL -> Intent(Intent.ACTION_SENDTO, "mailto:${Uri.encode(to, "@+")}".toUri())
                .putExtra(Intent.EXTRA_TEXT, text)
                .apply { if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject) }
        }
        return intent.setPackage(target.packageName)
    }

    /**
     * What opens the share screen of [target], or another share screen of its app when that one is gone, or `null`
     * when the app has none, or is gone too.
     */
    private fun shareScreenIntent(context: Context, target: ShareTarget, text: String, subject: String?): Intent? {
        val screens = queryShareScreens(context, target.packageName)
        val screen = screens.firstOrNull { it.activityInfo.name == target.activityName } ?: screens.firstOrNull()
        return screen?.let {
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                .apply { if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject) }
                .setClassName(target.packageName, it.activityInfo.name)
        }
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(LAUNCH_FLAGS))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { context.packageManagerCompatHelper.getApplicationInfoCompat(packageName, 0) }.isSuccess

    // As in Firefox's share sheet, the app opens in a task of its own, which Back leaves for Dejavu again.
    private const val LAUNCH_FLAGS =
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
    private const val SMS_BODY = "sms_body"
    private const val WHATSAPP_HOST = "api.whatsapp.com"
    private const val TELEGRAM_HOST = "t.me"
}
