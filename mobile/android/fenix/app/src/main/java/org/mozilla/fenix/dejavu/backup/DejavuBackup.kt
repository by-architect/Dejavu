/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.backup

import mozilla.components.browser.state.state.ContainerState
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.fenix.dejavu.actions.CustomAction
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.sync.DejavuSyncSource
import org.mozilla.fenix.dejavu.sync.string
import org.mozilla.fenix.dejavu.workspaces.WorkspaceSerializer
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.dejavu.workspaces.WorkspaceWallpaper
import org.mozilla.fenix.dejavu.workspaces.readWallpapers
import org.mozilla.fenix.dejavu.workspaces.writeWallpapers

/** An open tab of a backup, opened again without loading it when the backup is restored. */
internal data class BackupTab(
    val id: String,
    val url: String,
    val title: String,
    val contextId: String?,
    val workspaceId: String,
)

/** A container of a backup. Temporary containers are left out. */
internal data class BackupContainer(
    val contextId: String,
    val name: String,
    val color: ContainerColor,
    val icon: ContainerState.Icon,
)

/**
 * What a backup keeps of syncing workspaces: whether this device syncs with Zen and its unpinned tabs too, and which
 * browser workspaces follow. Never the account, its keys or anything else secret.
 */
internal data class BackupSync(val enabled: Boolean, val normalTabs: Boolean, val source: DejavuSyncSource?)

/**
 * A backup of Dejavu, which backup.json holds in the backup file, see [BackupJson]. The pictures of the [wallpapers]
 * go next to it in the file, see [BackupZip].
 *
 * @property appVersion The version of Dejavu that made it.
 * @property createdAt When it was made, in milliseconds.
 * @property state The workspaces, pinned tabs, folders, essentials and split views, as they are saved on the phone.
 * @property tabs The open tabs that are not private.
 * @property wallpapers The wallpapers of the workspaces that have one, by workspace id.
 * @property settings Dejavu's settings of this device as their preferences keep them, by key: booleans, numbers, texts
 *   and sets of texts.
 * @property customActions The custom actions, with any tokens they hold, or `null` when the file has none at all.
 * @property sync What the backup keeps of syncing workspaces, or `null` when the file has nothing of it.
 */
internal class DejavuBackup(
    val appVersion: String,
    val createdAt: Long,
    val state: WorkspaceState,
    val tabs: List<BackupTab>,
    val containers: List<BackupContainer>,
    val wallpapers: Map<String, WorkspaceWallpaper>,
    val settings: Map<String, Any>,
    val customActions: List<CustomAction>?,
    val sync: BackupSync?,
) {
    /** The user's own workspaces, without those showing the tabs of a device on the account, which sync brings. */
    val ownWorkspaceIds: Set<String>
        get() = state.workspaces.filter { it.device == null }.map { it.id }.toSet()

    /** What the backup holds, for the user to confirm restoring it. */
    val summary: BackupSummary
        get() {
            val own = ownWorkspaceIds
            val pinnedTabIds = state.pins.mapNotNull { it.tabId }.toSet()
            return BackupSummary(
                workspaces = own.size,
                pinnedTabs = state.pins.count { !it.isFolder && !it.essential && it.workspaceId in own },
                essentials = state.pins.count { it.essential },
                folders = state.pins.count { it.isFolder && it.workspaceId in own },
                containers = containers.size,
                tabs = tabs.count { it.id !in pinnedTabIds && it.workspaceId in own },
            )
        }
}

/** How much of each kind a backup holds; [tabs] counts the open tabs that are not pinned. */
internal data class BackupSummary(
    val workspaces: Int,
    val pinnedTabs: Int,
    val essentials: Int,
    val folders: Int,
    val containers: Int,
    val tabs: Int,
)

/** What reading a backup file found. */
internal sealed interface BackupRead {
    /** A backup this version of Dejavu restores. */
    class Found(val backup: DejavuBackup) : BackupRead

    /** No backup of Dejavu, or a damaged one. */
    data object NotBackup : BackupRead

    /** A backup of a newer version of Dejavu, which this one cannot be sure to read right. */
    data object TooNew : BackupRead
}

/**
 * Reads and writes backup.json, the heart of a backup file:
 *
 * ```
 * {
 *   "format": "dejavu-backup", "formatVersion": 1, "appVersion": "5.0.0", "createdAt": 1763078400000,
 *   "workspaces": { the workspaces as the phone saves them, see WorkspaceSerializer },
 *   "tabs": [{ "id", "url", "title", "contextId", "workspaceId" }],
 *   "containers": [{ "contextId", "name", "color", "icon" }],
 *   "wallpapers": { by workspace id: { "file", "intensity", "colors", "light", "dark", "lightLook" } },
 *   "settings": { by key: { "type": "boolean" | "int" | "long" | "float" | "string" | "strings", "value" } },
 *   "customActions": [ custom actions as CustomAction.toJson writes them ],
 *   "sync": { "enabled", "normalTabs", "source": "zen" | "firefox" }
 * }
 * ```
 *
 * A newer Dejavu may add to it; [VERSION] only goes up when an older Dejavu could no longer read it right.
 */
internal object BackupJson {
    const val FORMAT = "dejavu-backup"
    const val VERSION = 1

    private const val TYPE = "type"
    private const val VALUE = "value"
    private const val BOOLEAN = "boolean"
    private const val INT = "int"
    private const val LONG = "long"
    private const val FLOAT = "float"
    private const val STRING = "string"
    private const val STRINGS = "strings"

    fun write(backup: DejavuBackup): String = JSONObject()
        .put("format", FORMAT)
        .put("formatVersion", VERSION)
        .put("appVersion", backup.appVersion)
        .put("createdAt", backup.createdAt)
        .put("workspaces", JSONObject(WorkspaceSerializer.write(backup.state)))
        .put("tabs", JSONArray().apply { backup.tabs.forEach { put(tabJson(it)) } })
        .put("containers", JSONArray().apply { backup.containers.forEach { put(containerJson(it)) } })
        .put("wallpapers", JSONObject(writeWallpapers(backup.wallpapers)))
        .put("settings", JSONObject().apply { backup.settings.forEach { (key, value) -> putOpt(key, typed(value)) } })
        .putOpt("customActions", backup.customActions?.let { actions -> JSONArray(actions.map { it.toJson() }) })
        .putOpt("sync", backup.sync?.let(::syncJson))
        .toString()

    /**
     * Reads backup.json from [text]. Workspaces that cannot be read make it no backup, as do their absence; anything
     * else that cannot be read is left out.
     *
     * @param defaultName The name of a workspace that has none.
     */
    fun read(text: String, defaultName: String, now: Long): BackupRead {
        val root = runCatching { JSONObject(text.removePrefix(BYTE_ORDER_MARK)) }.getOrNull()
        if (root == null || root.optString("format") != FORMAT) return BackupRead.NotBackup
        val version = root.optInt("formatVersion")
        if (version > VERSION) return BackupRead.TooNew
        if (version < 1) return BackupRead.NotBackup
        val workspaces = root.optJSONObject("workspaces")
        val workspaceCount = workspaces?.optJSONArray("workspaces")?.length() ?: 0
        if (workspaces == null || workspaceCount == 0) return BackupRead.NotBackup
        val state = runCatching { WorkspaceSerializer.read(workspaces.toString(), defaultName, now) }.getOrNull()
        if (state == null || state.workspaces.none { it.device == null }) return BackupRead.NotBackup
        return BackupRead.Found(
            DejavuBackup(
                appVersion = root.optString("appVersion"),
                createdAt = root.optLong("createdAt"),
                state = state,
                tabs = root.optJSONArray("tabs").objectList().mapNotNull(::tabOf),
                containers = root.optJSONArray("containers").objectList().mapNotNull(::containerOf),
                wallpapers = readWallpapers(root.optJSONObject("wallpapers")?.toString()),
                settings = root.optJSONObject("settings")?.let(::settingsOf).orEmpty(),
                customActions = root.optJSONArray("customActions")?.let { actions ->
                    actions.objectList().mapNotNull { CustomAction.fromJson(it) }
                },
                sync = root.optJSONObject("sync")?.let(::syncOf),
            ),
        )
    }

    private fun tabJson(tab: BackupTab) = JSONObject()
        .put("id", tab.id)
        .put("url", tab.url)
        .put("title", tab.title)
        .putOpt("contextId", tab.contextId)
        .put("workspaceId", tab.workspaceId)

    private fun tabOf(item: JSONObject): BackupTab? {
        val id = item.text("id") ?: return null
        val url = item.text("url") ?: return null
        val workspaceId = item.text("workspaceId") ?: return null
        return BackupTab(id, url, item.string("title").orEmpty(), item.text("contextId"), workspaceId)
    }

    private fun containerJson(container: BackupContainer) = JSONObject()
        .put("contextId", container.contextId)
        .put("name", container.name)
        .put("color", container.color.key)
        .put("icon", container.icon.icon)

    private fun containerOf(item: JSONObject): BackupContainer? {
        val contextId = item.text("contextId") ?: return null
        val name = item.text("name") ?: return null
        val color = ContainerColor.fromKey(item.string("color"))?.takeIf { it != ContainerColor.WHITE }
            ?: ContainerColor.GRAY
        val icon = ContainerState.Icon.entries.firstOrNull { it.icon == item.string("icon") }
            ?: ContainerState.Icon.CIRCLE
        return BackupContainer(contextId, name, color, icon)
    }

    private fun syncJson(sync: BackupSync) = JSONObject()
        .put("enabled", sync.enabled)
        .put("normalTabs", sync.normalTabs)
        .putOpt("source", sync.source?.key)

    private fun syncOf(item: JSONObject) = BackupSync(
        enabled = item.optBoolean("enabled", true),
        normalTabs = item.optBoolean("normalTabs"),
        source = DejavuSyncSource.fromKey(item.string("source")),
    )

    /** [value], a value of the preferences, with its type, so it is read back as the same type; `null` for others. */
    private fun typed(value: Any): JSONObject? = when (value) {
        is Boolean -> JSONObject().put(TYPE, BOOLEAN).put(VALUE, value)
        is Int -> JSONObject().put(TYPE, INT).put(VALUE, value)
        is Long -> JSONObject().put(TYPE, LONG).put(VALUE, value)
        // Written the way it reads, 0.6 rather than 0.6000000238418579.
        is Float -> JSONObject().put(TYPE, FLOAT).put(VALUE, value.toString().toDouble())
        is String -> JSONObject().put(TYPE, STRING).put(VALUE, value)
        is Set<*> -> JSONObject().put(TYPE, STRINGS).put(VALUE, JSONArray(value.filterIsInstance<String>().sorted()))
        else -> null
    }

    private fun settingsOf(settings: JSONObject): Map<String, Any> =
        settings.keys().asSequence().mapNotNull { key ->
            settings.optJSONObject(key)?.let(::untyped)?.let { key to it }
        }.toMap()

    private fun untyped(item: JSONObject): Any? {
        val value = item.opt(VALUE)
        return when (item.optString(TYPE)) {
            BOOLEAN -> value as? Boolean
            INT -> (value as? Number)?.toInt()
            LONG -> (value as? Number)?.toLong()
            FLOAT -> (value as? Number)?.toFloat()
            STRING -> value as? String
            STRINGS -> (value as? JSONArray)?.let { array ->
                (0 until array.length()).mapNotNull { array.opt(it) as? String }.toSet()
            }
            else -> null
        }
    }

    private const val BYTE_ORDER_MARK = "﻿"
}
