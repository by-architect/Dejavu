/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.state.createTab
import mozilla.components.support.utils.ext.packageManagerCompatHelper
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.sync.DejavuSync
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpapers
import org.mozilla.fenix.ext.components

/**
 * Makes backup files of Dejavu and restores them, see [DejavuBackup]. Files are read and written on [Dispatchers.IO],
 * and the workspaces change together with the tabs on the main thread.
 */
internal class DejavuBackups(context: Context) {
    private val appContext: Context = context.applicationContext

    /** Writes a backup of everything [DejavuBackup] holds to [uri]. Returns whether it was written. */
    suspend fun write(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val wallpapers = WorkspaceWallpapers.get(appContext)
            val backup = collect(wallpapers)
            val pictures = backup.wallpapers.values.map { it.file }.distinct()
                .associateWith { wallpapers.pictureFile(it) }
                .filterValues { it.isFile }
            checkNotNull(appContext.contentResolver.openOutputStream(uri, "wt")).use { output ->
                BackupZip.write(output, BackupJson.write(backup), pictures)
            }
        }.isSuccess
    }

    /** Reads the backup file at [uri]. */
    suspend fun read(uri: Uri): BackupRead = withContext(Dispatchers.IO) {
        val json = runCatching {
            checkNotNull(appContext.contentResolver.openInputStream(uri)).use(BackupZip::readJson)
        }.getOrNull() ?: return@withContext BackupRead.NotBackup
        BackupJson.read(json, appContext.getString(R.string.dejavu_workspace_default_name), System.currentTimeMillis())
    }

    /**
     * Restores [backup], read from the file at [uri]. Its containers join the ones here, its settings and custom
     * actions replace the ones here, and its workspaces replace the user's own, with its tabs opened again without
     * loading them; tabs open here stay open. Its wallpapers come back, and so does how workspaces sync. Returns
     * whether all of it came back.
     */
    suspend fun restore(uri: Uri, backup: DejavuBackup): Boolean {
        val containers = restoreContainers(backup.containers)
        val settings = restoreSettings(backup)
        // Before the workspaces, so the sync that follows them is already with the browser of the backup.
        restoreSync(backup.sync)
        // Starting the next sync over deletes what sync kept, so it is off the main thread.
        withContext(Dispatchers.IO) {
            DejavuSync.replacingWorkspaces { withContext(Dispatchers.Main) { restoreWorkspaces(backup) } }
        }
        val wallpapers = restoreWallpapers(uri, backup)
        return containers && settings && wallpapers
    }

    private suspend fun collect(wallpapers: WorkspaceWallpapers): DejavuBackup {
        val storage = DejavuContainerStorage.get(appContext)
        storage.load()
        val state = WorkspaceRepository.get(appContext).state.value
        val own = state.workspaces.filter { it.device == null }.map { it.id }.toSet()
        // A temporary container is gone once its tabs close, so its tabs are left out.
        val tabs = appContext.components.core.store.state.normalTabs
            .filterNot { tab -> tab.contextId?.let(storage::isTemporary) == true }
            .map { tab ->
                val url = tab.readerState.activeUrl ?: tab.content.url
                BackupTab(tab.id, url, tab.content.title, tab.contextId, state.workspaceOf(tab.id))
            }
            .filter { it.url.isNotBlank() }
        val status = DejavuSync.status.value
        return DejavuBackup(
            appVersion = versionName(),
            createdAt = System.currentTimeMillis(),
            state = state,
            tabs = tabs,
            containers = storage.records.value.orEmpty()
                .filterNot { it.temporary }
                .map { BackupContainer(it.contextId, it.name, it.color, it.icon) },
            wallpapers = wallpapers.wallpapers.value.filterKeys(own::contains),
            settings = settingsPreferences().all
                .filterKeys { it !in DEVICE_KEYS }
                .mapNotNull { (key, value) -> value?.let { key to it } }
                .toMap(),
            customActions = DejavuSettings.get(appContext).customActions.value,
            sync = BackupSync(enabled = status.enabled, normalTabs = status.normalTabs, source = status.source),
        )
    }

    /** Adds the containers of a backup, or gives the ones with the same ids their names, colors and icons. */
    private suspend fun restoreContainers(containers: List<BackupContainer>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val storage = DejavuContainerStorage.get(appContext)
            storage.load()
            val existing = storage.records.value.orEmpty().associateBy { it.contextId }
            containers.forEach { container ->
                val record = existing[container.contextId]
                val same = record != null && record.name == container.name && record.color == container.color &&
                    record.icon == container.icon
                if (!same) storage.saveContainer(container.contextId, container.name, container.color, container.icon)
            }
        }.isSuccess
    }

    /**
     * Puts the settings of [backup] in place of the ones here, apart from what belongs to this install, see
     * [DEVICE_KEYS], and has the settings read them again.
     */
    private suspend fun restoreSettings(backup: DejavuBackup): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val preferences = settingsPreferences()
            preferences.edit(commit = true) {
                preferences.all.keys.filterNot(DEVICE_KEYS::contains).forEach { remove(it) }
                backup.settings.filterKeys { it !in DEVICE_KEYS }.forEach { (key, value) -> putValue(key, value) }
            }
            val settings = DejavuSettings.get(appContext)
            backup.customActions?.let { settings.replaceCustomActions(it) }
            settings.reload()
        }.isSuccess
    }

    /**
     * Replaces the workspaces with those of [backup], and opens its tabs again without loading them, all at once on
     * the main thread, so nothing sees the workspaces without their tabs in between.
     */
    private fun restoreWorkspaces(backup: DejavuBackup) {
        val repository = WorkspaceRepository.get(appContext)
        val store = appContext.components.core.store
        val open = store.state.normalTabs.map { it.id }.toSet()
        val reopening = BackupRestore.tabsToOpen(backup, open)
        val reopened = reopening.associate { it.id to it.workspaceId }
        repository.update { current -> BackupRestore.restoredState(current, backup, open, reopened) to Unit }
        if (reopening.isEmpty()) return
        val containers = DejavuContainerStorage.get(appContext).records.value.orEmpty().map { it.contextId }.toSet()
        val now = System.currentTimeMillis()
        reopening.forEach { repository.expectTab(it.id) }
        store.dispatch(
            TabListAction.AddMultipleTabsAction(
                reopening.map { tab ->
                    createTab(
                        url = tab.url,
                        id = tab.id,
                        title = tab.title,
                        contextId = tab.contextId?.takeIf(containers::contains),
                        lastAccess = now,
                        createdAt = now,
                    )
                },
            ),
        )
    }

    /**
     * Puts the wallpaper pictures of [backup] back from the file at [uri] and gives their workspaces their wallpapers.
     * The user's own workspaces without a wallpaper in the backup lose theirs. Returns whether every picture came back.
     */
    private suspend fun restoreWallpapers(uri: Uri, backup: DejavuBackup): Boolean = withContext(Dispatchers.IO) {
        val wallpapers = WorkspaceWallpapers.get(appContext)
        val wanted = backup.wallpapers.filterKeys(backup.ownWorkspaceIds::contains)
        val names = wanted.values.map { it.file }.toSet()
        val copied = mutableSetOf<String>()
        if (names.isNotEmpty()) {
            runCatching {
                checkNotNull(appContext.contentResolver.openInputStream(uri)).use { input ->
                    BackupZip.readPictures(input, names) { name, picture ->
                        if (BackupZip.copyTo(picture, wallpapers.pictureFile(name))) copied += name
                    }
                }
            }
        }
        val state = WorkspaceRepository.get(appContext).state.value
        val devices = state.workspaces.filter { it.device != null }.map { it.id }.toSet()
        val kept = wanted.filterValues { it.file in copied }
        wallpapers.wallpapers.value.keys
            .filter { it !in devices && it !in kept }
            .forEach { wallpapers.set(it, null) }
        kept.forEach { (workspaceId, wallpaper) -> wallpapers.set(workspaceId, wallpaper) }
        copied.containsAll(names)
    }

    /** Syncs workspaces the way [sync] says, with Zen or Firefox, without touching the account. */
    private fun restoreSync(sync: BackupSync?) {
        sync ?: return
        val status = DejavuSync.status.value
        if (sync.enabled != status.enabled) DejavuSync.setEnabled(appContext, sync.enabled)
        if (sync.normalTabs != status.normalTabs) DejavuSync.setNormalTabs(appContext, sync.normalTabs)
        val source = sync.source
        if (source != null && source != status.source) DejavuSync.chooseSource(appContext, source)
    }

    private fun settingsPreferences(): SharedPreferences =
        appContext.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE)

    private fun versionName(): String = runCatching {
        appContext.packageManagerCompatHelper.getPackageInfoCompat(appContext.packageName, 0).versionName
    }.getOrNull().orEmpty()

    private companion object {
        // Where DejavuSettings keeps Dejavu's settings, under the name from before the rename to Dejavu.
        const val SETTINGS_PREFERENCES = "kaizen_settings"

        /**
         * Keys of those preferences that belong to this install rather than to the user, which a backup leaves out:
         * the defaults Dejavu set once, whether it added uBlock Origin, the welcome pages, when data was last
         * cleared, and the custom actions, which a backup keeps on their own.
         */
        val DEVICE_KEYS = setOf(
            "defaults_version",
            "search_defaults_version",
            "ublock_origin",
            "welcome_seen",
            "whats_new_seen",
            "cleared_data_at",
            "custom_actions",
        )
    }
}

/** Puts [value], a value of [SharedPreferences.getAll], under [key] with its own type. */
private fun SharedPreferences.Editor.putValue(key: String, value: Any) {
    when (value) {
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is String -> putString(key, value)
        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
    }
}
