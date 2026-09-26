/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.workspaces

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.fenix.R
import java.util.UUID

/**
 * A tab pinned to the top of a workspace. It survives closing its browser tab.
 *
 * @property id Stable identifier of the pin.
 * @property url URL the pin reopens when it has no open tab.
 * @property title Title shown while the pin has no open tab.
 * @property tabId The open browser tab backing this pin, or `null` when it is closed.
 */
data class PinnedTab(
    val id: String,
    val url: String,
    val title: String,
    val tabId: String?,
)

/**
 * A workspace groups normal tabs. Every tab belongs to exactly one workspace.
 *
 * @property id Stable identifier of the workspace.
 * @property name User visible name of the workspace.
 * @property pinned Pinned tabs, shown above the other tabs.
 */
data class Workspace(
    val id: String,
    val name: String,
    val pinned: List<PinnedTab> = emptyList(),
)

/**
 * @property workspaces Workspaces in display order. Never empty.
 * @property activeWorkspaceId The workspace currently shown on the home screen. New tabs are assigned to it.
 * @property assignments Tab ID to workspace ID.
 */
data class WorkspaceState(
    val workspaces: List<Workspace>,
    val activeWorkspaceId: String,
    val assignments: Map<String, String>,
) {
    val activeIndex: Int
        get() = workspaces.indexOfFirst { it.id == activeWorkspaceId }.coerceAtLeast(0)

    /** Returns the workspace ID for [tabId], falling back to the active workspace for unassigned tabs. */
    fun workspaceOf(tabId: String): String = assignments[tabId] ?: activeWorkspaceId
}

/**
 * Persists workspaces, pinned tabs and tab assignments in [SharedPreferences].
 */
class WorkspaceRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val defaultName = context.getString(R.string.kaizen_workspace_default_name)
    private val _state = MutableStateFlow(load())

    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    fun selectWorkspace(id: String) = mutate { state ->
        if (state.activeWorkspaceId == id || state.workspaces.none { it.id == id }) {
            state
        } else {
            state.copy(activeWorkspaceId = id)
        }
    }

    fun addWorkspace(name: String) = mutate { state ->
        val workspace = Workspace(UUID.randomUUID().toString(), name.trim().ifEmpty { defaultName })
        state.copy(workspaces = state.workspaces + workspace, activeWorkspaceId = workspace.id)
    }

    fun pinTab(tabId: String, url: String, title: String) = mutate { state ->
        val workspaceId = state.workspaceOf(tabId)
        if (state.workspaces.any { ws -> ws.pinned.any { it.tabId == tabId } }) {
            state
        } else {
            state.updateWorkspace(workspaceId) { ws ->
                ws.copy(pinned = ws.pinned + PinnedTab(UUID.randomUUID().toString(), url, title, tabId))
            }
        }
    }

    fun unpin(pinnedId: String) = mutate { state ->
        state.copy(workspaces = state.workspaces.map { ws -> ws.copy(pinned = ws.pinned.filterNot { it.id == pinnedId }) })
    }

    /** Links a reopened browser tab to its pin and keeps it in the pin's workspace. */
    fun attachPinned(pinnedId: String, tabId: String) = mutate { state ->
        val workspace = state.workspaces.firstOrNull { ws -> ws.pinned.any { it.id == pinnedId } } ?: return@mutate state
        state.updateWorkspace(workspace.id) { ws ->
            ws.copy(pinned = ws.pinned.map { if (it.id == pinnedId) it.copy(tabId = tabId) else it })
        }.copy(assignments = state.assignments + (tabId to workspace.id))
    }

    /**
     * Assigns tabs that have no workspace yet to the active workspace, and forgets tabs that no longer exist once
     * the browser state has been restored. Pins whose tab was closed stay pinned without a tab.
     */
    fun syncWithTabs(tabIds: Set<String>, restoreComplete: Boolean) = mutate { state ->
        val kept = if (restoreComplete) state.assignments.filterKeys { it in tabIds } else state.assignments
        val added = tabIds.filterNot { it in kept }.associateWith { state.activeWorkspaceId }
        val workspaces = if (restoreComplete) {
            state.workspaces.map { ws ->
                ws.copy(pinned = ws.pinned.map { if (it.tabId != null && it.tabId !in tabIds) it.copy(tabId = null) else it })
            }
        } else {
            state.workspaces
        }
        state.copy(workspaces = workspaces, assignments = kept + added)
    }

    private fun WorkspaceState.updateWorkspace(id: String, transform: (Workspace) -> Workspace) =
        copy(workspaces = workspaces.map { if (it.id == id) transform(it) else it })

    private fun mutate(transform: (WorkspaceState) -> WorkspaceState) {
        var changed: WorkspaceState? = null
        _state.update { old -> transform(old).also { if (it != old) changed = it } }
        changed?.let(::save)
    }

    private fun load(): WorkspaceState {
        val json = prefs.getString(KEY_STATE, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val workspaces = json?.optJSONArray("workspaces")?.let { array ->
            (0 until array.length()).map { i -> array.getJSONObject(i).toWorkspace() }
        }.orEmpty().ifEmpty { listOf(Workspace(UUID.randomUUID().toString(), defaultName)) }

        val assignments = json?.optJSONObject("assignments")?.let { obj ->
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }.orEmpty().filterValues { id -> workspaces.any { it.id == id } }

        val activeId = json?.optString("active")?.takeIf { id -> workspaces.any { it.id == id } }
            ?: workspaces.first().id

        return WorkspaceState(workspaces, activeId, assignments)
    }

    private fun save(state: WorkspaceState) {
        val json = JSONObject().apply {
            put("active", state.activeWorkspaceId)
            put("workspaces", JSONArray().apply { state.workspaces.forEach { put(it.toJson()) } })
            put("assignments", JSONObject(state.assignments))
        }
        prefs.edit { putString(KEY_STATE, json.toString()) }
    }

    private fun JSONObject.toWorkspace(): Workspace {
        val pinned = optJSONArray("pinned")?.let { array ->
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                PinnedTab(
                    id = item.getString("id"),
                    url = item.getString("url"),
                    title = item.optString("title"),
                    tabId = item.optString("tabId").ifEmpty { null },
                )
            }
        }.orEmpty()
        return Workspace(getString("id"), getString("name"), pinned)
    }

    private fun Workspace.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put(
            "pinned",
            JSONArray().apply {
                pinned.forEach { pin ->
                    put(
                        JSONObject()
                            .put("id", pin.id)
                            .put("url", pin.url)
                            .put("title", pin.title)
                            .put("tabId", pin.tabId ?: ""),
                    )
                }
            },
        )
    }

    companion object {
        private const val PREFS_NAME = "kaizen_workspaces"
        private const val KEY_STATE = "state"

        @Volatile
        private var instance: WorkspaceRepository? = null

        /** Returns the process wide [WorkspaceRepository]. */
        fun get(context: Context): WorkspaceRepository =
            instance ?: synchronized(this) {
                instance ?: WorkspaceRepository(context.applicationContext).also { instance = it }
            }
    }
}
