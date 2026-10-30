/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.shortcuts

import android.os.StrictMode
import android.view.KeyEvent
import android.view.View
import androidx.annotation.StringRes
import androidx.navigation.findNavController
import mozilla.components.browser.state.selector.getNormalOrPrivateTabs
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.EngineView
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.customtabs.ExternalAppBrowserActivity
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.ext.components

/** A key and the modifiers held with it, as [KeyEvent.META_CTRL_ON], [KeyEvent.META_SHIFT_ON] and [KeyEvent.META_ALT_ON]. */
data class KeyCombo(val keyCode: Int, val meta: Int) {
    val hasCtrlOrAlt: Boolean get() = meta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON) != 0

    /** Whether text fields and web pages use this combo themselves: clipboard, undo, select all, moving the caret. */
    val editsText: Boolean
        get() = keyCode in CARET_KEYS ||
            (keyCode in CLIPBOARD_KEYS && meta and KeyEvent.META_CTRL_ON != 0 && meta and KeyEvent.META_ALT_ON == 0)

    fun encode() = "$meta:$keyCode"

    fun label(): String = buildString {
        if (meta and KeyEvent.META_CTRL_ON != 0) append("Ctrl+")
        if (meta and KeyEvent.META_ALT_ON != 0) append("Alt+")
        if (meta and KeyEvent.META_SHIFT_ON != 0) append("Shift+")
        append(KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_"))
    }

    companion object {
        private const val MODIFIERS = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON

        private val CLIPBOARD_KEYS = setOf(
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_C,
            KeyEvent.KEYCODE_V,
            KeyEvent.KEYCODE_X,
            KeyEvent.KEYCODE_Y,
            KeyEvent.KEYCODE_Z,
        )

        private val CARET_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL,
        )

        /**
         * The combo [event] presses, or `null` when only a modifier key is down. Left and right modifier flags count
         * as the plain ones, since some keyboards only send `META_CTRL_LEFT_ON`.
         */
        fun of(event: KeyEvent): KeyCombo? =
            if (KeyEvent.isModifierKey(event.keyCode) || event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                null
            } else {
                KeyCombo(event.keyCode, KeyEvent.normalizeMetaState(event.metaState) and MODIFIERS)
            }

        fun parse(text: String): KeyCombo? {
            val parts = text.split(":")
            val meta = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val key = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return KeyCombo(key, meta)
        }
    }
}

private const val CTRL = KeyEvent.META_CTRL_ON
private const val SHIFT = KeyEvent.META_SHIFT_ON

/** Everything a keyboard shortcut can do, with the key it is on until the user remaps it. */
enum class ShortcutAction(
    val key: String,
    @param:StringRes val label: Int,
    val default: KeyCombo,
    val labelArg: Int = 0,
) {
    NEXT_TAB("next_tab", R.string.dejavu_shortcut_next_tab, KeyCombo(KeyEvent.KEYCODE_TAB, CTRL)),
    PREVIOUS_TAB("previous_tab", R.string.dejavu_shortcut_previous_tab, KeyCombo(KeyEvent.KEYCODE_TAB, CTRL or SHIFT)),
    NEW_TAB("new_tab", R.string.dejavu_shortcut_new_tab, KeyCombo(KeyEvent.KEYCODE_T, CTRL)),
    CLOSE_TAB("close_tab", R.string.dejavu_shortcut_close_tab, KeyCombo(KeyEvent.KEYCODE_W, CTRL)),
    RESTORE_TAB("restore_tab", R.string.dejavu_shortcut_restore_tab, KeyCombo(KeyEvent.KEYCODE_T, CTRL or SHIFT)),
    ADDRESS_BAR("address_bar", R.string.dejavu_shortcut_address_bar, KeyCombo(KeyEvent.KEYCODE_L, CTRL)),
    RELOAD("reload", R.string.dejavu_shortcut_reload, KeyCombo(KeyEvent.KEYCODE_R, CTRL)),
    TAB_1("tab_1", R.string.dejavu_shortcut_tab_n, KeyCombo(KeyEvent.KEYCODE_1, CTRL), 1),
    TAB_2("tab_2", R.string.dejavu_shortcut_tab_n, KeyCombo(KeyEvent.KEYCODE_2, CTRL), 2),
    TAB_3("tab_3", R.string.dejavu_shortcut_tab_n, KeyCombo(KeyEvent.KEYCODE_3, CTRL), 3),
    TAB_4("tab_4", R.string.dejavu_shortcut_tab_n, KeyCombo(KeyEvent.KEYCODE_4, CTRL), 4),
    TAB_5("tab_5", R.string.dejavu_shortcut_tab_n, KeyCombo(KeyEvent.KEYCODE_5, CTRL), 5),
    TAB_6("tab_6", R.string.dejavu_shortcut_tab_n, KeyCombo(KeyEvent.KEYCODE_6, CTRL), 6),
    TAB_7("tab_7", R.string.dejavu_shortcut_tab_n, KeyCombo(KeyEvent.KEYCODE_7, CTRL), 7),
    TAB_8("tab_8", R.string.dejavu_shortcut_tab_n, KeyCombo(KeyEvent.KEYCODE_8, CTRL), 8),
    LAST_TAB("last_tab", R.string.dejavu_shortcut_last_tab, KeyCombo(KeyEvent.KEYCODE_9, CTRL)),
}

object KeyboardShortcuts {
    /** Set by the browser screen to focus its address bar. */
    var focusAddressBar: (() -> Unit)? = null

    /** True while the remap dialog records a combo, so every key reaches it instead of running a shortcut. */
    var recording = false

    /** The key whose press ran a shortcut, so its repeats and its release are consumed too. */
    private var consumedKey: Int? = null

    /** The user's changed shortcuts, by action key. */
    fun saved(activity: HomeActivity): Map<String, String> =
        activity.components.strictMode.allowViolation(StrictMode::allowThreadDiskReads) {
            DejavuSettings.get(activity).shortcuts.value
        }

    /** The combo of [action] given the user's [saved] choices, or `null` when cleared. A saved "" means cleared. */
    fun comboOf(action: ShortcutAction, saved: Map<String, String>): KeyCombo? = when (val text = saved[action.key]) {
        null -> action.default
        "" -> null
        else -> KeyCombo.parse(text) ?: action.default
    }

    fun actionFor(combo: KeyCombo, saved: Map<String, String>): ShortcutAction? =
        ShortcutAction.entries.firstOrNull { comboOf(it, saved) == combo }

    /** The other action that already has [combo], which giving it to [action] would take it from. */
    fun conflictOf(action: ShortcutAction, combo: KeyCombo, saved: Map<String, String>): ShortcutAction? =
        actionFor(combo, saved)?.takeIf { it != action }

    /**
     * Runs the shortcut [event] presses, for [HomeActivity.dispatchKeyEvent]. Returns whether it consumed [event].
     * Text editing combos stay with a focused text field or web page. Custom tabs and web apps get no shortcuts,
     * since they would act on the tabs of the main browser behind them.
     */
    fun dispatch(activity: HomeActivity, event: KeyEvent): Boolean {
        val consumed = consumedKey
        return when {
            activity is ExternalAppBrowserActivity -> false
            event.action == KeyEvent.ACTION_UP -> (event.keyCode == consumed).also { if (it) consumedKey = null }
            event.action != KeyEvent.ACTION_DOWN -> false
            event.repeatCount > 0 -> event.keyCode == consumed
            else -> {
                // A new press of the same key means its release went to another window, like a dialog it opened.
                if (event.keyCode == consumed) consumedKey = null
                val combo = KeyCombo.of(event)?.takeIf { it.hasCtrlOrAlt }
                val action = combo?.takeUnless { recording || (it.editsText && activity.currentFocus.takesTyping()) }
                    ?.let { actionFor(it, saved(activity)) }
                (action != null && run(activity, action)).also { if (it) consumedKey = event.keyCode }
            }
        }
    }

    /** Whether this focused view, or a view around it, takes typing: a text field or a web page. */
    private fun View?.takesTyping(): Boolean =
        generateSequence(this) { it.parent as? View }.any { it is EngineView || it.onCheckIsTextEditor() }

    /** Does what [action] says. Returns whether it did something. */
    fun run(activity: HomeActivity, action: ShortcutAction): Boolean {
        val components = activity.components
        val state = components.core.store.state
        val tabs = state.getNormalOrPrivateTabs(activity.browsingModeManager.mode.isPrivate)
        val selectedId = state.selectedTab?.id
        val index = tabs.indexOfFirst { it.id == selectedId }
        val tabsUseCases = components.useCases.tabsUseCases
        fun select(target: Int?) = target?.let { tabs.getOrNull(it) }?.let { tabsUseCases.selectTab(it.id) } != null
        return when (action) {
            ShortcutAction.NEXT_TAB -> tabs.size > 1 && select((index + 1) % tabs.size)
            ShortcutAction.PREVIOUS_TAB -> tabs.size > 1 && select((index - 1 + tabs.size) % tabs.size)
            ShortcutAction.LAST_TAB -> select(tabs.lastIndex)
            ShortcutAction.CLOSE_TAB -> selectedId?.let { tabsUseCases.removeTab(it) } != null
            ShortcutAction.RESTORE_TAB -> {
                tabsUseCases.undo.invoke()
                true
            }
            ShortcutAction.RELOAD -> selectedId?.let { components.useCases.sessionUseCases.reload(it) } != null
            ShortcutAction.NEW_TAB -> {
                activity.findNavController(R.id.container).navigate(NavGraphDirections.actionGlobalHome(focusOnAddressBar = true))
                true
            }
            ShortcutAction.ADDRESS_BAR -> focusAddressBar?.invoke() != null
            else -> select(action.labelArg - 1)
        }
    }
}
