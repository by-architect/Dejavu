/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentActivity
import mozilla.components.ui.icons.R as iconsR
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.workspaces.WorkspaceLock
import org.mozilla.fenix.settings.logins.ui.DefaultBiometricUtils

/**
 * What shows over a locked workspace or the locked private tabs: a lock, [title] and the button that unlocks them,
 * which a tap anywhere on it presses too. [onLeave], when given, adds a button that goes back to the workspaces.
 */
@Composable
internal fun LockCover(
    title: String,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    onLeave: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onUnlock)
            .padding(24.dp),
    ) {
        Icon(
            painter = iconPainter(iconsR.drawable.mozac_ic_lock_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        DejavuDialogButton(
            text = stringResource(R.string.pbm_authentication_unlock),
            onClick = onUnlock,
            style = DejavuButtonStyle.Primary,
        )
        if (onLeave != null) {
            Spacer(Modifier.height(8.dp))
            DejavuDialogButton(text = stringResource(R.string.dejavu_workspace_locked_leave), onClick = onLeave)
        }
    }
}

/**
 * Blurs what is drawn while [locked], too much to read, and keeps it from screen readers. Android draws blurs from 12
 * on; before, it is not drawn at all.
 */
internal fun Modifier.lockedContent(locked: Boolean): Modifier = when {
    !locked -> this
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> clearAndSetSemantics {}.blur(LOCK_BLUR.dp, BlurredEdgeTreatment.Rectangle)
    else -> clearAndSetSemantics {}.alpha(0f)
}

private const val LOCK_BLUR = 28

/**
 * Asks the user to unlock with their fingerprint, face or screen lock, as Firefox asks before it shows passwords. When
 * the phone has no screen lock, Firefox's warning about it lets the user go on without one.
 */
internal class UnlockPrompt(private val activity: FragmentActivity?) {
    /** Shows the phone's screen lock, for phones that unlock with no fingerprint or face. */
    internal var screenLock: ActivityResultLauncher<Intent>? = null
    private var onUnlocked: (() -> Unit)? = null

    /** Asks, with [title] on the prompt, and runs [onUnlocked] once the user unlocked. */
    fun ask(title: String, onUnlocked: () -> Unit) {
        val activity = activity ?: return
        if (WorkspaceLock.prompting) return
        this.onUnlocked = onUnlocked
        when {
            DefaultBiometricUtils.canUseBiometricAuthentication(activity) -> {
                WorkspaceLock.prompting = true
                BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), BiometricCallback()).authenticate(
                    BiometricPrompt.PromptInfo.Builder()
                        .setTitle(title)
                        .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                        .build(),
                )
            }
            DefaultBiometricUtils.canUsePinVerification(activity) -> {
                val launcher = screenLock ?: return
                val intent = DefaultBiometricUtils.getConfirmDeviceCredentialIntent(title, activity) ?: return
                WorkspaceLock.prompting = true
                launcher.launch(intent)
            }
            else -> DefaultBiometricUtils.showPinWarningPrompt(activity) { finish(unlocked = true) }
        }
    }

    /** Ends the prompt; [unlocked] tells whether the user unlocked. */
    fun finish(unlocked: Boolean) {
        WorkspaceLock.prompting = false
        val then = onUnlocked
        onUnlocked = null
        if (unlocked) then?.invoke()
    }

    // A fingerprint that does not match leaves the prompt open for another try, so only its end counts.
    private inner class BiometricCallback : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(unlocked = true)

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish(unlocked = false)
    }
}

/** An [UnlockPrompt] for this screen. */
@Composable
internal fun rememberUnlockPrompt(): UnlockPrompt {
    val context = LocalContext.current
    val prompt = remember(context) { UnlockPrompt(context.findFragmentActivity()) }
    prompt.screenLock = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        prompt.finish(unlocked = result.resultCode == Activity.RESULT_OK)
    }
    return prompt
}

/**
 * Whether a prompt to unlock may show by itself now: not while the phone's own screen lock shows, which a screen saver
 * can briefly leave Dejavu running under, as Firefox found for its private tabs.
 */
internal fun Context.mayAskToUnlock(): Boolean = getSystemService<KeyguardManager>()?.isDeviceLocked != true

private fun Context.findFragmentActivity(): FragmentActivity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is FragmentActivity) return context
        context = context.baseContext
    }
    return null
}
