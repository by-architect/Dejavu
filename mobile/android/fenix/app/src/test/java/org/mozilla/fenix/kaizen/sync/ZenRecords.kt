/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.sync

import mozilla.components.browser.state.state.ContainerState
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.fenix.kaizen.containers.ContainerColor
import org.mozilla.fenix.kaizen.containers.ContainerRecord
import org.mozilla.fenix.kaizen.workspaces.Workspace
import org.mozilla.fenix.kaizen.workspaces.WorkspaceState

/** Records shaped exactly like the ones Zen's ZenSpacesSyncModel uploads. */
internal object ZenRecords {
    const val CONTAINER = "5e3f2c1a-9b8d-4e7f-a6c5-0d1e2f3a4b5c"
    const val SPACE_PERSONAL = "{11111111-2222-4333-8444-555555555555}"
    const val SPACE_WORK = "{66666666-7777-4888-8999-aaaaaaaaaaaa}"
    const val FOLDER = "1727000000000-12"
    const val SUBFOLDER = "1727000000001-34"
    const val TAB_PINNED = "1727000000100-0f0e0d0c-0b0a-4908-8706-050403020100"
    const val TAB_IN_FOLDER = "1727000000101-1f1e1d1c-1b1a-4918-8716-151413121110"
    const val TAB_IN_SUBFOLDER = "1727000000102-2f2e2d2c-2b2a-4928-8726-252423222120"
    const val TAB_ESSENTIAL = "1727000000103-3f3e3d3c-3b3a-4938-8736-353433323130"
    const val TAB_ESSENTIAL_CONTAINER = "1727000000104-4f4e4d4c-4b4a-4948-8746-454443424140"
    const val TAB_SPLIT_LEFT = "1727000000105-5f5e5d5c-5b5a-4958-8756-555453525150"
    const val TAB_SPLIT_RIGHT = "1727000000106-6f6e6d6c-6b6a-4968-8766-656463626160"
    const val TAB_RENAMED = "1727000000107-7f7e7d7c-7b7a-4978-8776-757473727170"
    const val TAB_NORMAL = "1727000000108-8f8e8d8c-8b8a-4988-8786-858483828180"
    const val SPLIT = "1727000000200-99"
    const val STAR_ICON = "chrome://browser/skin/zen-icons/selectable/star.svg"

    val theme: JSONObject
        get() = JSONObject(
            """{"type":"gradient","gradientColors":[
                {"c":[124,133,255],"isCustom":false,"algorithm":"complementary","isPrimary":true,"lightness":74,
                 "position":{"x":120,"y":40},"type":"explicit-lightness"},
                {"c":[255,203,124],"isCustom":false,"algorithm":"complementary","isPrimary":false,"lightness":74,
                 "position":{"x":80,"y":200},"type":"explicit-lightness"}],
               "opacity":0.65,"texture":0.2}""",
        )

    fun container(guid: String = CONTAINER, name: String = "Research", icon: String = "briefcase", color: String = "cyan") =
        record(guid, RecordKind.CONTAINER, JSONObject().put("guid", guid).put("name", name).put("icon", icon).put("color", color))

    @Suppress("LongParameterList")
    fun space(
        uuid: String,
        name: String,
        children: List<String>,
        icon: String? = null,
        theme: JSONObject? = null,
        containerGuid: String? = null,
    ) = record(
        uuid,
        RecordKind.SPACE,
        JSONObject()
            .put("uuid", uuid)
            .put("name", name)
            .putNullable("icon", icon)
            .putNullable("theme", theme)
            .putNullable("containerGuid", containerGuid)
            .put("children", JSONArray(children)),
    )

    fun folder(id: String, name: String, workspace: String, children: List<String>, parent: String? = null) = record(
        id,
        RecordKind.FOLDER,
        JSONObject()
            .put("folderId", id)
            .put("name", name)
            .put("icon", JSONObject.NULL)
            .put("workspaceUuid", workspace)
            .putNullable("parentFolderId", parent)
            .put("live", JSONObject.NULL)
            .put("children", JSONArray(children)),
    )

    @Suppress("LongParameterList")
    fun tab(
        id: String,
        url: String,
        workspace: String?,
        folder: String? = null,
        essential: Boolean = false,
        containerGuid: String? = null,
        staticLabel: String? = null,
        pinned: Boolean = true,
        title: String = "Title of $url",
    ) = record(
        id,
        RecordKind.TAB,
        JSONObject()
            .put("tabId", id)
            .put("url", url)
            .put("title", title)
            .put("icon", "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=")
            .putNullable("containerGuid", containerGuid)
            .put("essential", essential)
            .put("pinned", pinned || essential)
            .putNullable("workspaceUuid", if (essential) null else workspace)
            .putNullable("folderId", folder)
            .putNullable("staticLabel", staticLabel)
            .put("hasStaticIcon", false)
            .put("defaultContainer", false),
    )

    fun split(id: String, tabs: List<String>, workspace: String, folder: String? = null) = record(
        id,
        RecordKind.SPLIT,
        JSONObject()
            .put("splitId", id)
            .put("gridType", "vsep")
            .put("pinned", true)
            .put("tabs", JSONArray(tabs))
            .put("workspaceUuid", workspace)
            .putNullable("folderId", folder),
    )

    fun layout(spaces: List<String>, essentials: Map<String, List<String>>) = record(
        LAYOUT_RECORD_ID,
        RecordKind.LAYOUT,
        JSONObject()
            .put("spaces", JSONArray(spaces))
            .put("essentials", JSONObject().apply { essentials.forEach { (key, ids) -> put(key, JSONArray(ids)) } }),
    )

    /** What a Zen profile with two spaces, folders, essentials, a split view and a normal tab uploads. */
    fun desktop(): List<SpacesRecord> = listOf(
        container(),
        space(
            SPACE_PERSONAL,
            "Personal",
            children = listOf(TAB_PINNED, FOLDER, SPLIT, TAB_NORMAL),
            icon = STAR_ICON,
            theme = theme,
        ),
        space(SPACE_WORK, "Work", children = listOf(TAB_RENAMED), icon = "💼", containerGuid = "builtin-2"),
        folder(FOLDER, "Reading", SPACE_PERSONAL, children = listOf(TAB_IN_FOLDER, SUBFOLDER)),
        folder(SUBFOLDER, "Later", SPACE_PERSONAL, children = listOf(TAB_IN_SUBFOLDER), parent = FOLDER),
        tab(TAB_PINNED, "https://example.com/", SPACE_PERSONAL),
        tab(TAB_IN_FOLDER, "https://example.org/article", SPACE_PERSONAL, folder = FOLDER),
        tab(TAB_IN_SUBFOLDER, "https://example.net/later", SPACE_PERSONAL, folder = SUBFOLDER),
        tab(TAB_ESSENTIAL, "https://mail.example/", null, essential = true),
        tab(TAB_ESSENTIAL_CONTAINER, "https://docs.example/", null, essential = true, containerGuid = CONTAINER),
        tab(TAB_SPLIT_LEFT, "https://left.example/", SPACE_PERSONAL),
        tab(TAB_SPLIT_RIGHT, "https://right.example/", SPACE_PERSONAL),
        tab(TAB_RENAMED, "https://inbox.example/", SPACE_WORK, staticLabel = "Inbox", containerGuid = "builtin-2"),
        tab(TAB_NORMAL, "https://news.example/", SPACE_PERSONAL, pinned = false),
        split(SPLIT, listOf(TAB_SPLIT_LEFT, TAB_SPLIT_RIGHT), SPACE_PERSONAL),
        layout(
            spaces = listOf(SPACE_PERSONAL, SPACE_WORK),
            essentials = mapOf("default" to listOf(TAB_ESSENTIAL), CONTAINER to listOf(TAB_ESSENTIAL_CONTAINER)),
        ),
    )

    /** The state a fresh Kaizen starts with: one untouched workspace. */
    fun freshKaizen(): WorkspaceState {
        val home = Workspace(id = "{00000000-0000-4000-8000-000000000001}", name = "Home", createdAt = 1L, updatedAt = 1L)
        return WorkspaceState(workspaces = listOf(home), pins = emptyList(), activeWorkspaceId = home.id, assignments = emptyMap())
    }

    /** The containers Kaizen has once Zen's container and the default container Zen's work space uses arrived. */
    fun kaizenContainers(): List<ContainerRecord> = listOf(
        ContainerRecord(CONTAINER, "Research", ContainerColor.CYAN, ContainerState.Icon.BRIEFCASE, 5L, 5L),
        ContainerRecord("builtin-2", "Work", ContainerColor.ORANGE, ContainerState.Icon.BRIEFCASE, 5L, 5L),
    )

    private fun record(id: String, kind: String, data: JSONObject) =
        SpacesRecord.of(id, kind, JSONObject(SyncJson.stringify(data)))
}
