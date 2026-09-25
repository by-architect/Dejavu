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
 * A workspace groups normal tabs. Every tab belongs to exactly one workspace.
 *
 * @property id Stable identifier of the workspace.
 * @property name User visible name of the workspace.
 */
data class Workspace(
    val id: String,
    val name: String,
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
 * Persists workspaces and tab assignments in [SharedPreferences].
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

    /**
     * Assigns tabs that have no workspace yet to the active workspace, and forgets tabs that no longer exist once
     * the browser state has been restored.
     */
    fun syncWithTabs(tabIds: Set<String>, restoreComplete: Boolean) = mutate { state ->
        val kept = if (restoreComplete) state.assignments.filterKeys { it in tabIds } else state.assignments
        val added = tabIds.filterNot { it in kept }.associateWith { state.activeWorkspaceId }
        if (added.isEmpty() && kept.size == state.assignments.size) {
            state
        } else {
            state.copy(assignments = kept + added)
        }
    }

    private fun mutate(transform: (WorkspaceState) -> WorkspaceState) {
        var changed: WorkspaceState? = null
        _state.update { old -> transform(old).also { if (it != old) changed = it } }
        changed?.let(::save)
    }

    private fun load(): WorkspaceState {
        val json = prefs.getString(KEY_STATE, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val workspaces = json?.optJSONArray("workspaces")?.let { array ->
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                Workspace(item.getString("id"), item.getString("name"))
            }
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
            put(
                "workspaces",
                JSONArray().apply {
                    state.workspaces.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
                },
            )
            put("assignments", JSONObject(state.assignments))
        }
        prefs.edit { putString(KEY_STATE, json.toString()) }
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
