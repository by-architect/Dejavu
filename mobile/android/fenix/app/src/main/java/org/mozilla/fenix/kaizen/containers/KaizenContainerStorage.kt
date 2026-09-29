/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kaizen.containers

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.feature.containers.ContainerMiddleware
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Container colors as Firefox desktop (and so Zen) names them today. android-components still uses the older names
 * and has no violet, so [acColor] maps each one to the closest [ContainerState.Color].
 *
 * @property key Desktop color name, used for storage and sync.
 * @property argb Desktop "nova" color value.
 * @property acColor Closest android-components color.
 */
enum class ContainerColor(val key: String, val argb: Long, val acColor: ContainerState.Color) {
    GRAY("gray", 0xFF949297, ContainerState.Color.TOOLBAR),
    YELLOW("yellow", 0xFFDB820E, ContainerState.Color.YELLOW),
    ORANGE("orange", 0xFFF4682C, ContainerState.Color.ORANGE),
    RED("red", 0xFFED566E, ContainerState.Color.RED),
    PINK("pink", 0xFFDB54BF, ContainerState.Color.PINK),
    PURPLE("purple", 0xFFB864EE, ContainerState.Color.PURPLE),
    VIOLET("violet", 0xFF9871FF, ContainerState.Color.PURPLE),
    BLUE("blue", 0xFF5A87FD, ContainerState.Color.BLUE),
    CYAN("cyan", 0xFF10A4CA, ContainerState.Color.TURQUOISE),
    GREEN("green", 0xFF11AE84, ContainerState.Color.GREEN),

    /** Kaizen's color for temporary containers. It is never offered for other containers, nor synced. */
    WHITE("white", 0xFFF4F4F6, ContainerState.Color.TOOLBAR),
    ;

    companion object {
        /** The colors a container the user makes can have. */
        val pickable: List<ContainerColor> get() = entries - WHITE

        /** Resolves a desktop color name, including the legacy "turquoise" and "toolbar" aliases. */
        fun fromKey(key: String?): ContainerColor? = when (key) {
            "turquoise" -> CYAN
            "toolbar" -> GRAY
            else -> entries.firstOrNull { it.key == key }
        }

        fun fromAcColor(color: ContainerState.Color): ContainerColor =
            entries.firstOrNull { it.acColor == color } ?: GRAY
    }
}

/**
 * A container as Kaizen stores it. The fields mirror a Firefox desktop contextual identity so containers can be synced
 * with Zen later; [contextId] is the GeckoView context the container's tabs are isolated in.
 */
data class ContainerRecord(
    val contextId: String,
    val name: String,
    val color: ContainerColor,
    val icon: ContainerState.Icon,
    val createdAt: Long,
    val updatedAt: Long,
    val temporary: Boolean = false,
) {
    val state: ContainerState
        get() = ContainerState(contextId, name, color.acColor, icon)
}

/**
 * Backs android-components' [ContainerMiddleware] with Kaizen's own storage so containers can also be edited and keep
 * the desktop color names. Every change is emitted through [getContainers], which the middleware forwards to the
 * browser store. All disk access happens on [Dispatchers.IO].
 */
class KaizenContainerStorage private constructor(private val context: Context) : ContainerMiddleware.Storage {
    private val mutex = Mutex()
    private val _records = MutableStateFlow<List<ContainerRecord>?>(null)
    private val pendingTemporary: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** All containers, or `null` until they have been read from disk. */
    val records: StateFlow<List<ContainerRecord>?> = _records.asStateFlow()

    override fun getContainers(): Flow<List<ContainerState>> =
        flow {
            ensureLoaded()
            emitAll(_records.filterNotNull().map { list -> list.map { it.state } })
        }.flowOn(Dispatchers.IO)

    override suspend fun addContainer(
        contextId: String,
        name: String,
        color: ContainerState.Color,
        icon: ContainerState.Icon,
    ) = mutate { list ->
        if (list.any { it.contextId == contextId }) {
            list
        } else {
            val now = System.currentTimeMillis()
            val temporary = contextId in pendingTemporary
            list + ContainerRecord(
                contextId,
                name,
                if (temporary) ContainerColor.WHITE else ContainerColor.fromAcColor(color),
                icon,
                now,
                now,
                temporary = temporary,
            )
        }
    }

    /** Marks [contextId] as a temporary container before it is added. */
    fun markTemporary(contextId: String) {
        pendingTemporary.add(contextId)
    }

    fun forgetTemporary(contextId: String) {
        pendingTemporary.remove(contextId)
    }

    /** Whether [contextId] is a temporary container. Containers not read from disk yet count as permanent. */
    fun isTemporary(contextId: String): Boolean =
        contextId in pendingTemporary || _records.value?.any { it.contextId == contextId && it.temporary } == true

    override suspend fun removeContainer(container: ContainerState) = mutate { list ->
        list.filterNot { it.contextId == container.contextId }
    }

    /** Adds a new container, or updates the name, color and icon of the container with the same [contextId]. */
    suspend fun saveContainer(
        contextId: String?,
        name: String,
        color: ContainerColor,
        icon: ContainerState.Icon,
    ) = mutate { list ->
        val now = System.currentTimeMillis()
        if (contextId != null && list.any { it.contextId == contextId }) {
            list.map {
                if (it.contextId == contextId) it.copy(name = name, color = color, icon = icon, updatedAt = now) else it
            }
        } else {
            list + ContainerRecord(contextId ?: UUID.randomUUID().toString(), name, color, icon, now, now)
        }
    }

    /** Reads the containers from disk if that has not happened yet. */
    suspend fun load() = withContext(Dispatchers.IO) { ensureLoaded() }

    private suspend fun mutate(transform: (List<ContainerRecord>) -> List<ContainerRecord>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = transform(ensureLoadedLocked())
                _records.value = updated
                save(updated)
            }
        }

    private suspend fun ensureLoaded() {
        if (_records.value == null) mutex.withLock { ensureLoadedLocked() }
    }

    private fun ensureLoadedLocked(): List<ContainerRecord> =
        _records.value ?: read().also { _records.value = it }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun read(): List<ContainerRecord> {
        val array = prefs().getString(KEY_CONTAINERS, null)?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.getJSONObject(i)
            val color = ContainerColor.fromKey(item.optString("color"))
            val icon = ContainerState.Icon.entries.firstOrNull { it.icon == item.optString("icon") }
            if (color == null || icon == null) return@mapNotNull null
            ContainerRecord(
                contextId = item.getString("contextId"),
                name = item.getString("name"),
                color = color,
                icon = icon,
                createdAt = item.optLong("createdAt"),
                updatedAt = item.optLong("updatedAt"),
                temporary = item.optBoolean("temporary"),
            )
        }
    }

    private fun save(list: List<ContainerRecord>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("contextId", it.contextId)
                    .put("name", it.name)
                    .put("color", it.color.key)
                    .put("icon", it.icon.icon)
                    .put("createdAt", it.createdAt)
                    .put("updatedAt", it.updatedAt)
                    .put("temporary", it.temporary),
            )
        }
        prefs().edit { putString(KEY_CONTAINERS, array.toString()) }
    }

    companion object {
        private const val PREFS_NAME = "kaizen_containers"
        private const val KEY_CONTAINERS = "containers"

        @Volatile
        private var instance: KaizenContainerStorage? = null

        /** Returns the process wide [KaizenContainerStorage]. Does not touch the disk. */
        fun get(context: Context): KaizenContainerStorage =
            instance ?: synchronized(this) {
                instance ?: KaizenContainerStorage(context.applicationContext).also { instance = it }
            }
    }
}
