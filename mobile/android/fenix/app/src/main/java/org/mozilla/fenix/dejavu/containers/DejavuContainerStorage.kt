/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.containers

import android.content.Context
import androidx.core.content.edit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
import org.mozilla.fenix.dejavu.features.wasUpdated
import org.mozilla.fenix.dejavu.sync.BuiltinContainer

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

    /** Dejavu's color for temporary containers. It is never offered for other containers, nor synced. */
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
 * A container as Dejavu stores it. The fields mirror a Firefox desktop contextual identity so containers can be synced
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

    /**
     * Whether it is shown and offered as a temporary container: one of Dejavu's own ([temporary]), or one that Firefox's
     * Temporary Containers add-on made in Zen, named like "tmp4". Zen's sync like any container and Zen removes them,
     * so only Dejavu's own are kept out of sync and destroyed here once unused.
     */
    val showsAsTemporary: Boolean
        get() = temporary || TEMPORARY_NAME.matches(name)
}

private val TEMPORARY_NAME = Regex("tmp\\d+")

/**
 * These containers as the lists offering them show them: the permanent ones in their order, then the temporary ones at
 * the bottom, tmp2 before tmp10.
 */
fun Collection<ContainerRecord>.splitTemporary(): Pair<List<ContainerRecord>, List<ContainerRecord>> {
    val (temporary, permanent) = partition { it.showsAsTemporary }
    return permanent to temporary.sortedWith(compareBy({ it.name.length }, { it.name }))
}

/**
 * Backs android-components' [ContainerMiddleware] with Dejavu's own storage so containers can also be edited and keep
 * the desktop color names. Every change is emitted through [getContainers], which the middleware forwards to the
 * browser store. All disk access happens on [Dispatchers.IO].
 */
class DejavuContainerStorage private constructor(private val context: Context) : ContainerMiddleware.Storage {
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

    /** Whether [contextId] is shown as a temporary container, see [ContainerRecord.showsAsTemporary]. */
    fun showsAsTemporary(contextId: String): Boolean =
        isTemporary(contextId) || _records.value?.any { it.contextId == contextId && it.showsAsTemporary } == true

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
        val saved = prefs().getString(KEY_CONTAINERS, null)
            ?: return if (context.wasUpdated()) emptyList() else defaultContainers().also(::save)
        val array = runCatching { JSONArray(saved) }.getOrNull() ?: return emptyList()
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

    /**
     * The containers a fresh install starts with: Zen's four default ones, under the ids Zen syncs them with, so they
     * become Zen's once synced and are not sent to Zen while they stay as they are.
     */
    private fun defaultContainers(): List<ContainerRecord> {
        val now = System.currentTimeMillis()
        return BuiltinContainer.entries.map {
            ContainerRecord(it.guid, context.getString(it.label), it.color, it.icon, now, now)
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
        // Kept from before the rename to Dejavu, so saved data still loads.
        private const val PREFS_NAME = "kaizen_containers"
        private const val KEY_CONTAINERS = "containers"

        @Volatile
        private var instance: DejavuContainerStorage? = null

        /** Returns the process wide [DejavuContainerStorage]. Does not touch the disk. */
        fun get(context: Context): DejavuContainerStorage =
            instance ?: synchronized(this) {
                instance ?: DejavuContainerStorage(context.applicationContext).also { instance = it }
            }
    }
}
