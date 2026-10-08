/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.normalTabs
import mozilla.components.browser.state.state.ContainerState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.sync.AccountObserver
import mozilla.components.concept.sync.AuthType
import mozilla.components.concept.sync.OAuthAccount
import mozilla.components.lib.state.ext.flow
import mozilla.components.service.fxa.manager.SCOPE_SYNC
import mozilla.components.service.fxa.sync.SyncStatusObserver
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.fenix.R
import org.mozilla.fenix.dejavu.SilentlyClosedTabs
import org.mozilla.fenix.dejavu.containers.ContainerColor
import org.mozilla.fenix.dejavu.containers.ContainerRemoval
import org.mozilla.fenix.dejavu.containers.ContainerRemover
import org.mozilla.fenix.dejavu.containers.DejavuContainerStorage
import org.mozilla.fenix.dejavu.settings.DejavuSettings
import org.mozilla.fenix.dejavu.workspaces.WorkspaceRepository
import org.mozilla.fenix.dejavu.workspaces.WorkspaceState
import org.mozilla.fenix.ext.components

/** Why spaces sync is not working, as shown in its settings. */
enum class DejavuSyncProblem {
    /** Firefox Sync has not set up the account's storage yet. It is done by the first sync of the browser. */
    NOT_SET_UP,

    /** Spaces sync is not turned on for the account, see [DejavuSync.turnOn]. */
    NOT_TURNED_ON,

    /** Another device uses a newer version of spaces sync. */
    NEEDS_UPDATE,

    /** The account has to be signed in again. */
    SIGN_IN_AGAIN,

    /** The servers could not be reached. */
    OFFLINE,

    /** The servers failed or asked to wait. */
    SERVER,
}

/**
 * State of spaces sync.
 *
 * @property enabled Whether spaces sync is turned on in Dejavu.
 * @property normalTabs Whether tabs that are not pinned are synced too, like Zen's "Include unpinned tabs".
 * @property signedIn Whether a Mozilla account is signed in.
 * @property syncing Whether a sync is running.
 * @property lastSynced Time of the last complete sync in milliseconds, or 0.
 * @property problem What stopped the last sync, if anything.
 */
data class DejavuSyncStatus(
    val enabled: Boolean = true,
    val normalTabs: Boolean = true,
    val signedIn: Boolean = false,
    val syncing: Boolean = false,
    val lastSynced: Long = 0L,
    val problem: DejavuSyncProblem? = null,
)

/**
 * Syncs Dejavu's workspaces with Zen through the Mozilla account, see [SpacesSyncEngine].
 *
 * Syncs run one at a time: after Firefox's own syncs, when the app comes to the foreground and every few minutes
 * while it is shown, a few seconds after local changes, and when the app goes to the background with changes not
 * uploaded yet.
 */
object DejavuSync {
    // Kept from before the rename to Dejavu, like STORE_FILE, so saved data still loads.
    private const val PREFS_NAME = "kaizen_sync"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_NORMAL_TABS = "normal_tabs"
    private const val STORE_FILE = "kaizen_spaces_sync.json"
    private const val LOCAL_CHANGE_DELAY_MS = 3_000L
    private const val FOREGROUND_POLL_MS = 5 * 60_000L
    private const val MIN_INTERVAL_MS = 10_000L
    private const val CONFLICT_RETRY_MS = 5_000L

    private val logger = Logger("DejavuSync")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val requests = Channel<Boolean>(Channel.CONFLATED)
    private val _status = MutableStateFlow(DejavuSyncStatus())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var engine: SpacesSyncEngine? = null
    private var pollJob: Job? = null

    @Volatile
    private var turnOnRequested = false

    @Volatile
    private var accountManagerReady = false
    private var lastRun = 0L

    /** State of spaces sync, for its settings. */
    val status: StateFlow<DejavuSyncStatus> = _status.asStateFlow()

    /** Starts spaces sync. Only the first call does anything. */
    fun install(context: Context) {
        synchronized(this) {
            if (appContext != null) return
            appContext = context.applicationContext
        }
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val store = SpacesSyncStore(File(app.filesDir, STORE_FILE))
        _status.update {
            it.copy(
                enabled = prefs.getBoolean(KEY_ENABLED, true),
                normalTabs = prefs.getBoolean(KEY_NORMAL_TABS, true),
                lastSynced = store.load().lastSynced,
            )
        }

        scope.launch(Dispatchers.Main) {
            // The fetch client comes with the Gecko runtime, which is created on the main thread.
            engine = SpacesSyncEngine(
                http = FetchSyncHttp(app.components.core.client),
                store = store,
                local = DejavuSpacesData(app),
                log = { logger.info(it) },
            )
            scope.launch { for (manual in requests) runRequest(manual) }
            ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
            val services = app.components.backgroundServices
            services.accountManagerAvailableQueue.runIfReadyOrQueue {
                val manager = services.accountManager
                manager.register(accountObserver)
                manager.registerForSyncEvents(syncObserver, ProcessLifecycleOwner.get(), autoPause = false)
                val signedIn = manager.authenticatedAccount() != null
                accountManagerReady = true
                _status.update { it.copy(signedIn = signedIn) }
                if (signedIn) request()
            }
        }
        watchLocalChanges(app)
    }

    /** Syncs now, as asked by the user. */
    fun syncNow() {
        requests.trySend(true)
    }

    /** Turns spaces sync on for the account, which also turns it on in Zen, and syncs. */
    fun turnOn() {
        turnOnRequested = true
        syncNow()
    }

    /** Turns spaces sync on or off on this device only; other devices keep syncing. */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, enabled) }
        _status.update { it.copy(enabled = enabled, problem = null) }
        if (enabled) syncNow()
    }

    /** Syncs tabs that are not pinned too, or only pinned tabs, folders and essentials. */
    fun setNormalTabs(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_NORMAL_TABS, enabled) }
        _status.update { it.copy(normalTabs = enabled) }
        syncNow()
    }

    private fun request() {
        requests.trySend(false)
    }

    @OptIn(FlowPreview::class)
    private fun watchLocalChanges(context: Context) = scope.launch {
        val repository = WorkspaceRepository.get(context)
        val containers = DejavuContainerStorage.get(context)
        // Only while this device syncs: looking at every tab on every change of the browser costs work and memory.
        _status.map { it.enabled && it.signedIn }.distinctUntilChanged().collectLatest { syncing ->
            if (!syncing) return@collectLatest
            // Tabs that are not pinned count by what their records hold: address, title and container.
            val tabs = context.components.core.store.flow()
                .map { state ->
                    state.normalTabs.map { Triple(it.id, it.content.url to it.content.title, it.contextId) }
                }
                .distinctUntilChanged()
            combine(repository.state, containers.records, tabs) { _, _, _ -> }
                .drop(1)
                .debounce(LOCAL_CHANGE_DELAY_MS)
                .collect { if (hasLocalChanges()) request() }
        }
    }

    private suspend fun hasLocalChanges(): Boolean {
        val current = _status.value
        if (!current.enabled || !current.signedIn) return false
        val engine = engine ?: return false
        return mutex.withLock { runCatching { engine.hasLocalChanges() }.getOrDefault(false) }
    }

    private suspend fun runRequest(manual: Boolean) {
        if (!manual) {
            val wait = lastRun + MIN_INTERVAL_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
        }
        runSync(manual)
        lastRun = System.currentTimeMillis()
    }

    private suspend fun runSync(manual: Boolean) {
        val context = appContext ?: return
        val engine = engine ?: return
        if (!accountManagerReady || !_status.value.enabled) return
        if (!manual && engine.backoffUntil > System.currentTimeMillis()) return
        val account = context.components.backgroundServices.accountManager.authenticatedAccount()
        if (account == null) {
            _status.update { it.copy(signedIn = false) }
            return
        }
        val turnOn = turnOnRequested && manual
        turnOnRequested = false
        mutex.withLock {
            _status.update { it.copy(signedIn = true, syncing = true) }
            val problem = sync(engine, account, turnOn)
            val lastSynced = if (problem == null) System.currentTimeMillis() else _status.value.lastSynced
            _status.update { it.copy(syncing = false, problem = problem, lastSynced = lastSynced) }
        }
    }

    /** Syncs once and returns what stopped the sync, if anything. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun sync(engine: SpacesSyncEngine, account: OAuthAccount, turnOn: Boolean): DejavuSyncProblem? =
        try {
            val auth = authOf(account) ?: throw SyncAuthException("The account has no sync key")
            problemOf(engine.sync(auth, turnOn))
        } catch (e: SyncConflictException) {
            logger.info("Changed elsewhere during the sync, syncing again", e)
            scope.launch {
                delay(CONFLICT_RETRY_MS)
                request()
            }
            null
        } catch (e: SyncAuthException) {
            logger.warn("Sync refused the account", e)
            DejavuSyncProblem.SIGN_IN_AGAIN
        } catch (e: SyncServerException) {
            logger.warn("Sync server error ${e.status}", e)
            DejavuSyncProblem.SERVER
        } catch (e: IOException) {
            logger.warn("Sync servers not reachable", e)
            DejavuSyncProblem.OFFLINE
        } catch (e: Exception) {
            logger.error("Spaces sync failed", e)
            DejavuSyncProblem.SERVER
        }

    private fun problemOf(result: SpacesSyncResult): DejavuSyncProblem? = when (result) {
        is SpacesSyncResult.Synced -> {
            logger.info("Synced: ${result.received} in, ${result.sent} out, ${result.pending} waiting")
            null
        }
        SpacesSyncResult.NotSetUp -> DejavuSyncProblem.NOT_SET_UP
        SpacesSyncResult.NotTurnedOn -> DejavuSyncProblem.NOT_TURNED_ON
        is SpacesSyncResult.NeedsUpdate -> DejavuSyncProblem.NEEDS_UPDATE
    }

    private suspend fun authOf(account: OAuthAccount): SyncAuth? {
        val token = account.getAccessToken(SCOPE_SYNC) ?: return null
        val key = token.key ?: return null
        val tokenServer = account.getTokenServerEndpointURL() ?: return null
        return SyncAuth(kid = key.kid, accessToken = token.token, syncKey = key.k, tokenServerUrl = tokenServer)
    }

    private val accountObserver = object : AccountObserver {
        override fun onAuthenticated(account: OAuthAccount, authType: AuthType) {
            _status.update { it.copy(signedIn = true, problem = null) }
            request()
        }

        override fun onLoggedOut() {
            _status.update { it.copy(signedIn = false, problem = null, lastSynced = 0L) }
            scope.launch { mutex.withLock { engine?.reset() } }
        }

        override fun onAuthenticationProblems() {
            _status.update { it.copy(problem = DejavuSyncProblem.SIGN_IN_AGAIN) }
        }
    }

    private val syncObserver = object : SyncStatusObserver {
        override fun onStarted() = Unit

        override fun onIdle() = request()

        override fun onError(error: Exception?) = request()
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            pollJob?.cancel()
            pollJob = scope.launch {
                while (isActive) {
                    request()
                    delay(FOREGROUND_POLL_MS)
                }
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            pollJob?.cancel()
            pollJob = null
            scope.launch { if (hasLocalChanges()) request() }
        }
    }
}

/** Dejavu's workspaces and containers, as [SpacesSyncEngine] reads and changes them. */
private class DejavuSpacesData(private val context: Context) : SpacesLocalData {
    private val repository: WorkspaceRepository
        get() = WorkspaceRepository.get(context)

    private val containers: DejavuContainerStorage
        get() = DejavuContainerStorage.get(context)

    override val defaultWorkspaceName: String = context.getString(R.string.dejavu_workspace_default_name)

    override suspend fun read(): LocalSpaces {
        containers.load()
        val browser = context.components.core.store.state
        val tabs = browser.takeIf { it.restoreComplete }?.normalTabs?.map { tab ->
            LocalTab(
                id = tab.id,
                url = tab.content.url,
                title = tab.content.title,
                contextId = tab.contextId,
                awake = tab.engineState.engineSession != null,
            )
        }
        return LocalSpaces(
            state = repository.state.value,
            containers = containers.records.value.orEmpty(),
            normalTabs = DejavuSync.status.value.normalTabs,
            tabs = tabs,
        )
    }

    override suspend fun runTabOps(ops: List<TabOp>) = withContext(Dispatchers.Main) {
        val store = context.components.core.store
        for (op in ops) {
            when (op) {
                is TabOp.Open -> {
                    if (store.state.findTab(op.id) != null) continue
                    repository.expectTab(op.id)
                    store.dispatch(
                        TabListAction.AddTabAction(
                            createTab(url = op.url, id = op.id, title = op.title, contextId = op.contextId),
                            select = false,
                        ),
                    )
                    repository.assignTab(op.id, op.workspaceId)
                }
                is TabOp.Retarget -> retarget(store, op)
                is TabOp.Close -> if (store.state.findTab(op.id) != null) {
                    SilentlyClosedTabs.add(op.id)
                    store.dispatch(TabListAction.RemoveTabAction(op.id, selectParentIfExists = false))
                }
            }
        }
    }

    /**
     * Replaces tab [TabOp.Retarget.id], whose page is not loaded, with one at the new address under the same id and in
     * the same place, as a tab keeps the history it was restored with. A tab that is shown or in a split view stays.
     */
    private fun retarget(store: BrowserStore, op: TabOp.Retarget) {
        val state = store.state
        val tab = state.findTab(op.id) ?: return
        val splits = repository.state.value
        if (tab.engineState.engineSession != null || state.selectedTabId == op.id || splits.splitOf(op.id) != null) return
        val index = state.tabs.indexOf(tab)
        val before = state.tabs.getOrNull(index - 1)
        val after = state.tabs.getOrNull(index + 1)
        repository.expectTab(op.id)
        SilentlyClosedTabs.add(op.id)
        store.dispatch(TabListAction.RemoveTabAction(op.id, selectParentIfExists = false))
        store.dispatch(
            TabListAction.AddTabAction(
                createTab(
                    url = op.url,
                    id = op.id,
                    title = op.title,
                    contextId = tab.contextId,
                    lastAccess = tab.lastAccess,
                    createdAt = tab.createdAt,
                ),
                select = false,
            ),
        )
        when {
            before != null -> store.dispatch(TabListAction.MoveTabsAction(listOf(op.id), before.id, placeAfter = true))
            after != null -> store.dispatch(TabListAction.MoveTabsAction(listOf(op.id), after.id, placeAfter = false))
        }
    }

    override fun <T> update(transform: (WorkspaceState) -> Pair<WorkspaceState, T>): T = repository.update(transform)

    override suspend fun saveContainer(
        contextId: String,
        name: String,
        color: ContainerColor,
        icon: ContainerState.Icon,
    ) = containers.saveContainer(contextId, name, color, icon)

    override suspend fun removeContainer(contextId: String) {
        val record = containers.records.value?.firstOrNull { it.contextId == contextId } ?: return
        withContext(Dispatchers.Main) {
            ContainerRemover(context.components, repository).remove(record, ContainerRemoval.MoveTabs(null))
        }
    }

    override fun useEssentialsPerContainer(enabled: Boolean) =
        DejavuSettings.get(context).setEssentialsPerContainerFromSync(enabled)

    override fun builtinName(container: BuiltinContainer): String = context.getString(
        when (container) {
            BuiltinContainer.PERSONAL -> R.string.dejavu_container_personal
            BuiltinContainer.WORK -> R.string.dejavu_container_work
            BuiltinContainer.BANKING -> R.string.dejavu_container_banking
            BuiltinContainer.SHOPPING -> R.string.dejavu_container_shopping
        },
    )
}
