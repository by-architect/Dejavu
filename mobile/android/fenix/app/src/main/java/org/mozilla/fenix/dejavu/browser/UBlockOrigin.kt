/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.browser

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.WebExtensionAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.extension.WebExtensionPromptRequest
import mozilla.components.concept.engine.webextension.InstallationMethod
import mozilla.components.concept.engine.webextension.PermissionPromptResponse
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.concept.engine.webextension.WebExtensionInstallException
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import org.mozilla.fenix.dejavu.features.wasUpdated
import org.mozilla.fenix.ext.components

/**
 * uBlock Origin, the ad and tracker blocker, which a fresh install of Dejavu adds from addons.mozilla.org in the
 * background, allowed in private tabs too and without the add-on dialogs. When it cannot be downloaded, the next start
 * tries again. An update of Dejavu does not add it, and once it was added Dejavu never adds it again, also after the
 * user removed it, or chose on the welcome pages to block trackers only. Only the install started here goes without
 * dialogs: the add-on must come from [URL], which only the real uBlock Origin can, also when add-on signatures are not
 * checked.
 */
object UBlockOrigin {
    const val ID = "uBlock0@raymondhill.net"

    @VisibleForTesting
    internal const val URL = "https://addons.mozilla.org/android/downloads/latest/uBlock0@raymondhill.net/latest.xpi"

    // Kept with Dejavu's other once-only defaults.
    private const val PREFS_NAME = "kaizen_settings"
    private const val KEY_STATE = "ublock_origin"
    private const val DUE = "due"
    private const val DONE = "done"
    private const val DECLINED = "declined"

    // How long its prompts are answered without dialogs: while it installs, and for prompts that arrive after.
    private const val QUIET_INSTALL_MS = 120_000L
    private const val QUIET_AFTER_MS = 10_000L

    /** Until when, in [SystemClock.elapsedRealtime], the prompts of the install started here need no dialog. */
    @Volatile
    @VisibleForTesting
    internal var quietUntil = 0L

    /** Whether the install started here may still fail with a download error that names no add-on. */
    @Volatile
    @VisibleForTesting
    internal var failureUnclaimed = false

    /** Whether [extension] is the one the install started here downloads, so its prompts need no dialog. */
    internal fun isOwnInstall(extension: WebExtension?, now: Long = SystemClock.elapsedRealtime()): Boolean =
        now < quietUntil && extension != null && extension.id == ID && extension.getMetadata()?.downloadUrl == URL

    /**
     * Whether a failed install is the one started here, so needs no dialog. A download that fails names no add-on, so
     * one such network failure counts as this install's.
     */
    internal fun claimsFailure(
        extension: WebExtension?,
        exception: WebExtensionInstallException,
        now: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        if (extension != null) return isOwnInstall(extension, now)
        if (now >= quietUntil || !failureUnclaimed || exception !is WebExtensionInstallException.NetworkFailure) {
            return false
        }
        failureUnclaimed = false
        return true
    }

    /** Adds uBlock Origin when this install is due to get it and the add-ons the engine has, [installed], lack it. */
    fun addIfDue(context: Context, installed: List<WebExtension>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val state = stateOf(context, prefs)
        if (!prefs.contains(KEY_STATE)) prefs.edit { putString(KEY_STATE, state) }
        if (state != DUE) return
        if (installed.any { it.id == ID }) {
            prefs.edit { putString(KEY_STATE, DONE) }
        } else if (SystemClock.elapsedRealtime() >= quietUntil) {
            install(context, prefs)
        }
    }

    /** Whether ads are blocked: uBlock Origin is there, or about to be added. */
    fun blocksAds(context: Context): Boolean {
        val state = stateOf(context, context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        return state == DUE || (state != DECLINED && isInstalled(context))
    }

    /**
     * Blocks ads with uBlock Origin, which is added unless it is there, or stops blocking them: uBlock Origin is
     * removed, and Dejavu does not add it again by itself. The welcome pages ask.
     */
    fun setBlocksAds(context: Context, block: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        when {
            block && isInstalled(context) -> prefs.edit { putString(KEY_STATE, DONE) }
            block -> {
                prefs.edit { putString(KEY_STATE, DUE) }
                // An install already under way ends as wanted.
                if (SystemClock.elapsedRealtime() >= quietUntil) install(context, prefs)
            }
            else -> {
                prefs.edit { putString(KEY_STATE, DECLINED) }
                if (isInstalled(context)) remove(context)
            }
        }
    }

    private fun stateOf(context: Context, prefs: SharedPreferences): String =
        prefs.getString(KEY_STATE, null) ?: if (context.wasUpdated()) DONE else DUE

    private fun isInstalled(context: Context) = context.components.core.store.state.extensions.containsKey(ID)

    private fun remove(context: Context) {
        val manager = context.components.addonManager
        context.components.applicationScope.launch {
            runCatching { manager.getAddonByID(ID) }.getOrNull()?.let { manager.uninstallAddon(it) }
        }
    }

    private fun install(context: Context, prefs: SharedPreferences) {
        quietUntil = SystemClock.elapsedRealtime() + QUIET_INSTALL_MS
        failureUnclaimed = true
        val ended = { quietUntil = SystemClock.elapsedRealtime() + QUIET_AFTER_MS }
        context.components.addonManager.installAddon(
            url = URL,
            installationMethod = InstallationMethod.MANAGER,
            onSuccess = { addon ->
                // Blocking ads may have been turned off while it was downloading.
                if (prefs.getString(KEY_STATE, null) == DECLINED) {
                    context.components.addonManager.uninstallAddon(addon)
                } else {
                    prefs.edit { putString(KEY_STATE, DONE) }
                }
                failureUnclaimed = false
                ended()
            },
            onError = { ended() },
        )
    }
}

/**
 * Answers the prompts of the uBlock Origin install that [UBlockOrigin] starts, so no dialog shows: its permissions are
 * granted, in private tabs too, and the dialogs after it was added or could not be added are left out. Other prompts,
 * those of other installs of uBlock Origin included, pass on.
 */
internal class UBlockOriginPromptMiddleware(
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : Middleware<BrowserState, BrowserAction> {
    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        val request = (action as? WebExtensionAction.UpdatePromptRequestWebExtensionAction)?.promptRequest
        val own = when (request) {
            is WebExtensionPromptRequest.AfterInstallation.Permissions.Required ->
                UBlockOrigin.isOwnInstall(request.extension, now())
            is WebExtensionPromptRequest.AfterInstallation.PostInstallation ->
                UBlockOrigin.isOwnInstall(request.extension, now())
            is WebExtensionPromptRequest.BeforeInstallation.InstallationFailed ->
                UBlockOrigin.claimsFailure(request.extension, request.exception, now())
            else -> false
        }
        if (!own) return next(action)
        if (request is WebExtensionPromptRequest.AfterInstallation.Permissions.Required) {
            request.onConfirm(PermissionPromptResponse(isPermissionsGranted = true, isPrivateModeGranted = true))
        }
    }
}
