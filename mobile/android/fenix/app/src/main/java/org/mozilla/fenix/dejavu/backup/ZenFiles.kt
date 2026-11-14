/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.backup

import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.fenix.dejavu.sync.string

/**
 * The sidebar of a Zen profile, as Zen's session manager saves it in zen-sessions.jsonlz4 and the copies it keeps in
 * zen-sessions-backup: the spaces in their order, the tabs in the order of the tab strip, the folders and the split
 * views. The items stay as Zen wrote them, for [ZenRecords].
 */
internal class ZenSession(
    val spaces: List<JSONObject>,
    val tabs: List<JSONObject>,
    val folders: List<JSONObject>,
    val splits: List<JSONObject>,
)

/**
 * A container of a Zen profile, from its containers.json. [name] is `null` for Firefox's default containers that were
 * not renamed, which Firefox names in the language of the computer.
 */
internal class ZenContainer(val name: String?, val icon: String?, val color: String?)

/**
 * What the files picked from a Zen profile hold together.
 *
 * @property containers The containers of containers.json by userContextId, or `null` when it was not picked.
 * @property syncIds The ids Zen syncs its containers under, by userContextId, from zen-spaces-sync.json when it was
 *   picked too.
 */
internal class ZenProfile(
    val session: ZenSession,
    val containers: Map<Int, ZenContainer>?,
    val syncIds: Map<Int, String> = emptyMap(),
)

/** A file of a Zen profile, as [ZenFiles] reads it. */
internal sealed interface ZenFile {
    /** zen-sessions.jsonlz4, one of its copies, or Firefox's session file of the profile. */
    class Session(val session: ZenSession) : ZenFile

    /** containers.json, with the containers by userContextId. */
    class Containers(val containers: Map<Int, ZenContainer>) : ZenFile

    /** zen-spaces-sync.json, with the ids Zen syncs its containers under, by userContextId. */
    class SyncIds(val guids: Map<Int, String>) : ZenFile
}

/**
 * Reads the files of a Zen profile that Dejavu brings workspaces from, compressed as mozlz4 like Zen writes them, see
 * [MozLz4], or as plain JSON.
 */
internal object ZenFiles {
    /** Files larger than this are not read; a session of thousands of tabs stays well below it. */
    const val MAX_SIZE = 64L * 1024 * 1024

    /** The types a file to pick may have; the files of a Zen profile have no type a phone knows. */
    val OPEN_TYPES = arrayOf("*/*")

    /** Reads the file [bytes], or returns `null` when it is none of the files of [ZenFile]. */
    fun read(bytes: ByteArray): ZenFile? {
        val content = if (MozLz4.isMozLz4(bytes)) MozLz4.decompress(bytes) else bytes
        val text = content?.toString(Charsets.UTF_8)?.removePrefix(BYTE_ORDER_MARK) ?: return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        sessionOf(root)?.let { return ZenFile.Session(it) }
        root.optJSONArray("identities")?.let { return ZenFile.Containers(containersOf(it)) }
        val syncIds = root.optJSONObject("containers")?.takeIf { root.has("uploaded") } ?: return null
        return ZenFile.SyncIds(syncIdsOf(syncIds))
    }

    /** Puts [files] together, or returns `null` without a session among them. The last of each kind counts. */
    fun profileOf(files: List<ZenFile>): ZenProfile? {
        val session = files.filterIsInstance<ZenFile.Session>().lastOrNull() ?: return null
        return ZenProfile(
            session = session.session,
            containers = files.filterIsInstance<ZenFile.Containers>().lastOrNull()?.containers,
            syncIds = files.filterIsInstance<ZenFile.SyncIds>().lastOrNull()?.guids.orEmpty(),
        )
    }

    /**
     * The sidebar of Zen's session file, or of Firefox's session file of a Zen profile, whose windows hold it: the
     * first window's spaces, folders and split views, with the tabs of every window, each tab once, the one Zen marks
     * as active when windows disagree, like Zen's session manager collects them.
     */
    private fun sessionOf(root: JSONObject): ZenSession? {
        root.optJSONArray("spaces")?.let { spaces ->
            return ZenSession(
                spaces = spaces.objectList(),
                tabs = root.optJSONArray("tabs").objectList(),
                folders = root.optJSONArray("folders").objectList(),
                splits = root.optJSONArray("splitViewData").objectList(),
            )
        }
        val windows = root.optJSONArray("windows").objectList().filter { it.optJSONArray("spaces") != null }
        val first = windows.firstOrNull() ?: return null
        val tabs = LinkedHashMap<String, JSONObject>()
        windows.flatMap { it.optJSONArray("tabs").objectList() }.forEach { tab ->
            val syncId = tab.text("zenSyncId") ?: return@forEach
            if (tab.opt("zenIsEmpty") == true && tab.text("groupId") == null) return@forEach
            if (syncId !in tabs || tab.opt("_zenIsActiveTab") == true) tabs[syncId] = tab
        }
        return ZenSession(
            spaces = first.optJSONArray("spaces").objectList(),
            tabs = tabs.values.toList(),
            folders = first.optJSONArray("folders").objectList(),
            splits = first.optJSONArray("splitViewData").objectList(),
        )
    }

    /** The containers of containers.json that Firefox shows, by userContextId; its own hidden ones are left out. */
    private fun containersOf(identities: JSONArray): Map<Int, ZenContainer> =
        identities.objectList().mapNotNull { identity ->
            val id = (identity.opt("userContextId") as? Number)?.toLong() ?: return@mapNotNull null
            if (id <= 0 || id > Int.MAX_VALUE || identity.opt("public") == false) return@mapNotNull null
            id.toInt() to ZenContainer(
                name = identity.string("name")?.trim()?.takeIf { it.isNotEmpty() },
                icon = identity.string("icon"),
                color = identity.string("color"),
            )
        }.toMap()

    /**
     * The container ids of zen-spaces-sync.json, by userContextId. Older versions of Zen kept them the other way
     * round, by id, which Zen turns around as it reads them, and so does this.
     */
    private fun syncIdsOf(containers: JSONObject): Map<Int, String> =
        containers.keys().asSequence().mapNotNull { key ->
            val value: Any? = containers.opt(key)
            if (value is String) {
                key.toIntOrNull()?.let { id -> id to value }
            } else if (value is Number) {
                value.toInt() to key
            } else {
                null
            }
        }.filter { (id, guid) -> id > 0 && guid.isNotEmpty() }.toMap()

    private const val BYTE_ORDER_MARK = "﻿"
}

/** The objects of this array, leaving out anything else; none for no array. */
internal fun JSONArray?.objectList(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** The string at [key], or `null` when it is missing, empty or not a string. */
internal fun JSONObject.text(key: String): String? = string(key)?.takeIf { it.isNotEmpty() }
