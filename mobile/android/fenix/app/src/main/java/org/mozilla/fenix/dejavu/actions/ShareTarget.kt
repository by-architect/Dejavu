/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.actions

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import androidx.annotation.StringRes
import androidx.core.net.toUri
import mozilla.components.support.utils.ext.packageManagerCompatHelper
import org.json.JSONObject
import org.mozilla.fenix.R

/**
 * Where a share action sends the links of its tabs: one of the share screens of an app, or straight to one person in
 * the apps that can open a chat with someone, see [DirectShare].
 *
 * @property packageName Package of the app.
 * @property activityName The app's share screen. One app can have several, like a feed and stories.
 * @property recipient Phone number, email address or username the links go straight to, or `null` for the app's share
 * screen, where the user picks who gets them.
 */
data class ShareTarget(
    val packageName: String,
    val activityName: String,
    val recipient: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("package", packageName)
        .put("activity", activityName)
        .apply { recipient?.let { put("recipient", it) } }

    companion object {
        fun fromJson(json: JSONObject): ShareTarget? = runCatching {
            ShareTarget(
                packageName = json.getString("package"),
                activityName = json.getString("activity"),
                recipient = json.optString("recipient").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }
}

/**
 * Who a share action can send straight to.
 *
 * @property hint What to type, the label of the recipient field in the editor.
 */
enum class RecipientKind(@param:StringRes val hint: Int) {
    /** A phone number with its country code. */
    PHONE(R.string.dejavu_share_recipient_phone),

    /** An email address. */
    EMAIL(R.string.dejavu_share_recipient_email),

    /** A username. */
    USERNAME(R.string.dejavu_share_recipient_username),
    ;

    /**
     * [text] as a share action keeps it: a phone number without spaces, dashes or brackets, and a username without its
     * "@" or the link it was copied from.
     */
    fun normalize(text: String): String {
        val trimmed = text.trim()
        return when (this) {
            PHONE -> trimmed
                .filterNot { it.isWhitespace() || it in PHONE_SEPARATORS || it.category == CharCategory.FORMAT }
                .map { if (it.isDigit()) '0' + it.digitToInt() else it }
                .joinToString("")
            EMAIL -> trimmed
            USERNAME -> trimmed.substringAfterLast("t.me/").removePrefix("@")
        }
    }

    /**
     * Whether [text], once [normalize]d, is a recipient of this kind: digits after an optional "+" for a phone number,
     * an "@" between two parts for an email address, and letters, digits and "_" for a username.
     */
    fun isValid(text: String): Boolean {
        val pattern = when (this) {
            PHONE -> PHONE_PATTERN
            EMAIL -> EMAIL_PATTERN
            USERNAME -> USERNAME_PATTERN
        }
        return pattern.matches(normalize(text))
    }
}

/**
 * How a share action reaches one person directly, in the apps that can open a chat with someone.
 *
 * @property kind Who the app can be given.
 */
enum class DirectShare(val kind: RecipientKind) {
    /** WhatsApp and WhatsApp Business, through their link to a chat with a phone number. */
    WHATSAPP(RecipientKind.PHONE),

    /** Telegram, through its link to a chat with a username. */
    TELEGRAM(RecipientKind.USERNAME),

    /** Apps that write text messages to a phone number, messengers like Signal included. */
    SMS(RecipientKind.PHONE),

    /** Apps that write emails. */
    EMAIL(RecipientKind.EMAIL),
    ;

    companion object {
        /** How the app with [packageName] reaches one person directly, or `null` when it can only share. */
        fun of(context: Context, packageName: String): DirectShare? = when {
            packageName in WHATSAPP_PACKAGES -> WHATSAPP
            packageName in TELEGRAM_PACKAGES -> TELEGRAM
            context.writesTo(packageName, "smsto") -> SMS
            context.writesTo(packageName, "mailto") -> EMAIL
            else -> null
        }
    }
}

/**
 * One of the share screens of an app.
 *
 * @property packageName Package of the app.
 * @property activityName The share screen.
 * @property name What share actions call it: what the share sheet calls it, after the app's name when the app has
 * several share screens, like "Instagram Stories".
 */
data class ShareScreen(val packageName: String, val activityName: String, val name: String)

/** The share screens for text of every app, or only of the app with [packageName], without Dejavu's own. */
fun queryShareScreens(context: Context, packageName: String? = null): List<ResolveInfo> {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
    if (packageName != null) intent.setPackage(packageName)
    return context.packageManagerCompatHelper.queryIntentActivitiesCompat(intent, 0)
        .filter { it.activityInfo.packageName != context.packageName }
}

/** [screens], found with [queryShareScreens], with the names share actions give them, see [ShareScreen.name]. */
fun namedShareScreens(context: Context, screens: List<ResolveInfo>): List<ShareScreen> {
    val packageManager = context.packageManager
    val perApp = screens.groupingBy { it.activityInfo.packageName }.eachCount()
    return screens.map { info ->
        val packageName = info.activityInfo.packageName
        val app = info.activityInfo.applicationInfo.loadLabel(packageManager).toString().trim()
        val screen = info.loadLabel(packageManager).toString().trim()
        val name = when {
            screen.isEmpty() || screen == app -> app
            (perApp[packageName] ?: 0) > 1 && !screen.contains(app, ignoreCase = true) -> "$app $screen"
            else -> screen
        }
        ShareScreen(packageName, info.activityInfo.name, name.ifEmpty { packageName })
    }
}

/** Whether the app with [packageName] writes messages to an address with [scheme], like `smsto` or `mailto`. */
private fun Context.writesTo(packageName: String, scheme: String): Boolean =
    packageManagerCompatHelper.queryIntentActivitiesCompat(
        Intent(Intent.ACTION_SENDTO, "$scheme:".toUri()).setPackage(packageName),
        0,
    ).isNotEmpty()

private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
private val TELEGRAM_PACKAGES = setOf(
    "org.telegram.messenger",
    "org.telegram.messenger.web",
    "org.thunderdog.challegram",
)
private const val PHONE_SEPARATORS = "-.()/"
private val PHONE_PATTERN = Regex("\\+?[0-9]{3,15}")
private val EMAIL_PATTERN = Regex("[^@\\s]+@[^@\\s]+")
private val USERNAME_PATTERN = Regex("[A-Za-z0-9_]{1,32}")
