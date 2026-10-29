/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.workspaces

import java.util.UUID
import org.mozilla.fenix.dejavu.sync.BuiltinContainer

/**
 * The examples a fresh install of Dejavu starts with, next to its Home workspace: a folder of pinned tabs in Home, a
 * few essentials, and the Work workspace, whose tabs open in the Work container. They are the user's to keep or change;
 * until changed they are not the user's own, see [WorkspaceState.withoutStarters], so they are never sent to Zen.
 */
internal object StarterWorkspaces {
    /**
     * A site the examples pin, with the [title] it shows until its page tells its own, and the [icon] its page names,
     * which shows before the page was opened.
     */
    class Site(val title: String, val url: String, val icon: String)

    /** The essentials, in order. */
    val Essentials = listOf(
        Site("YouTube", "https://m.youtube.com/", "https://m.youtube.com/static/favicon.ico"),
        Site("Wikipedia", "https://www.wikipedia.org/", "https://www.wikipedia.org/static/apple-touch/wikipedia.png"),
        Site("Reddit", "https://www.reddit.com/", "https://www.redditstatic.com/shreddit/assets/favicon/64x64.png"),
    )

    /** The pinned tabs in Home's folder: a map, a translator and the weather. */
    val Tools = listOf(
        Site("OpenStreetMap", "https://www.openstreetmap.org/", "https://www.openstreetmap.org/favicon.ico"),
        Site("Google Translate", "https://translate.google.com/", "https://www.gstatic.com/translate/favicon.ico"),
        Site("Windy", "https://www.windy.com/", "https://www.windy.com/img/favicon.png"),
    )

    /** The colors of the Work workspace, those of the wallpaper it starts with. */
    val WorkTheme = WorkspaceTheme(colors = listOf(0xFF11998E, 0xFF38EF7D).map { it.toInt() })

    /**
     * [state] with the examples, around its one workspace, [home]: Home's folder [folderName] and the essentials,
     * then the Work workspace named [workName], created at [now].
     */
    fun around(
        state: WorkspaceState,
        home: Workspace,
        workName: String,
        folderName: String,
        now: Long,
    ): WorkspaceState {
        val work = Workspace(
            id = WorkspaceSerializer.newWorkspaceId(),
            name = workName,
            containerId = BuiltinContainer.WORK.guid,
            theme = WorkTheme,
            createdAt = now,
            updatedAt = now,
        )
        val folder = PinnedItem(
            id = newId(),
            workspaceId = home.id,
            parentId = null,
            kind = PinKind.FOLDER,
            title = folderName,
            createdAt = now,
            updatedAt = now,
        )
        fun pin(site: Site, parent: PinnedItem?) = PinnedItem(
            id = newId(),
            workspaceId = parent?.workspaceId,
            parentId = parent?.id,
            kind = PinKind.TAB,
            title = site.title,
            url = site.url,
            essential = parent == null,
            createdAt = now,
            updatedAt = now,
        )
        val pins = listOf(folder) + Tools.map { pin(it, folder) } + Essentials.map { pin(it, null) }
        return state.copy(
            workspaces = state.workspaces + work,
            pins = state.pins + pins,
            starters = state.starters + work.id + pins.map { it.id },
        )
    }

    /**
     * The wallpapers the workspaces of a fresh install start with, by workspace id: gold for Home and the aurora for
     * Work, for those that still have the colors they started with.
     */
    fun firstWallpapers(state: WorkspaceState): Map<String, WallpaperArt> {
        val home = state.workspaces.firstOrNull()?.takeIf { it.theme == WorkspaceTheme.Gold }
        val works = state.workspaces.filter { it.id in state.starters && it.theme == WorkTheme }
        return buildMap {
            home?.let { put(it.id, WallpaperArt.GOLD) }
            works.forEach { put(it.id, WallpaperArt.AURORA) }
        }
    }

    private fun newId() = UUID.randomUUID().toString()
}

/**
 * This state without the examples of a fresh install, [WorkspaceState.starters], that are still as they came: what is
 * the user's own. A folder goes only with everything in it, and what is in a folder that stays, stays with it. The
 * Work workspace stays while it has open tabs or pinned tabs.
 */
internal fun WorkspaceState.withoutStarters(): WorkspaceState {
    if (starters.isEmpty()) return this
    val untouched = pins.filter { it.id in starters && it.updatedAt == it.createdAt }.map { it.id }.toSet()
    val folders = pins.filter { it.isFolder && it.id in untouched && descendantIds(it.id).all(untouched::contains) }
        .map { it.id }.toSet()
    val keptPins = pins.filterNot { item ->
        val gone = if (item.isFolder) item.id in folders else item.parentId == null || item.parentId in folders
        item.id in untouched && gone
    }
    val droppedWorkspaces = workspaces.filter { workspace ->
        workspace.id in starters && workspace.updatedAt == workspace.createdAt &&
            keptPins.none { it.workspaceId == workspace.id } && workspace.id !in assignments.values
    }.map { it.id }.toSet()
    val kept = workspaces.filterNot { it.id in droppedWorkspaces }.ifEmpty { return this }
    return copy(
        workspaces = kept,
        pins = keptPins,
        activeWorkspaceId = activeWorkspaceId.takeIf { id -> kept.any { it.id == id } } ?: kept.first().id,
        starters = emptySet(),
    )
}
