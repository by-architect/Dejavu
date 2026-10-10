/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import android.content.Context
import android.os.SystemClock
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
import mozilla.components.concept.sync.DeviceCommandOutgoing
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

/** Why workspace sync is not working, as shown in its settings. */
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

    /** Sync waits until the user chooses which browser workspaces follow, see [DejavuSync.chooseSource]. */
    CHOOSE_SOURCE,

    /** Open tabs are not synced for the account, so Firefox's tabs cannot be shown. */
    TABS_OFF,

    /** No Firefox on a computer syncs its open tabs to the account. */
    NO_FIREFOX,
}

/** Which browser Dejavu's workspaces sync with. */
enum class DejavuSyncSource(internal val key: String) {
    /** Zen's spaces, both ways, see [SpacesSyncEngine]. Dejavu on other devices uses them too. */
    ZEN("zen"),

    /** The open tabs of Firefox on the account's computers, see [FirefoxTabsEngine]. */
    FIREFOX("firefox"),
    ;

    internal companion object {
        fun fromKey(key: String?): DejavuSyncSource? = entries.firstOrNull { it.key == key }
    }
}

/**
 * State of workspace sync.
 *
 * @property enabled Whether workspace sync is turned on in Dejavu.
 * @property normalTabs Whether tabs that are not pinned are synced too, like Zen's "Include unpinned tabs".
 * @property signedIn Whether a Mozilla account is signed in.
 * @property syncing Whether a sync is running.
 * @property lastSynced Time of the last complete sync in milliseconds, or 0.
 * @property problem What stopped the last sync, if anything.
 * @property source The browser workspaces sync with, or `null` until the user chose one. Sync waits until then.
 * @property found What the account syncs from Firefox and Zen, once Dejavu looked.
 * @property askSource Whether to ask the user which browser to sync with: none is chosen, something was found, and the
 *   user did not put the question off.
 */
data class DejavuSyncStatus(
    val enabled: Boolean = true,
    val normalTabs: Boolean = true,
    val signedIn: Boolean = false,
    val syncing: Boolean = false,
    val lastSynced: Long = 0L,
    val problem: DejavuSyncProblem? = null,
    val source: DejavuSyncSource? = null,
    val found: FoundSyncData? = null,
    val askSource: Boolean = false,
)

/**
 * Syncs Dejavu's workspaces through the Mozilla account, with Zen ([SpacesSyncEngine]) or with Firefox
 * ([FirefoxTabsEngine]), whichever the user chose after Dejavu showed what the account has of each
 * ([SyncSourceScanner]). Until the user chooses, workspaces are not synced.
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
    private const val KEY_SOURCE = "source"
    private const val KEY_SOURCE_PUT_OFF = "source_put_off"
    private const val STORE_FILE = "kaizen_spaces_sync.json"
    private const val FIREFOX_STORE_FILE = "dejavu_firefox_sync.json"
    private const val LOCAL_CHANGE_DELAY_MS = 3_000L
    private const val FOREGROUND_POLL_MS = 5 * 60_000L
    private const val MIN_INTERVAL_MS = 10_000L
    private const val CONFLICT_RETRY_MS = 5_000L
    private const val DEVICES_REFRESH_MS = 60_000L
    private const val UNREACHABLE_RETRY_MS = 30_000L
    private const val MAX_RETRY_DOUBLINGS = 4

    private val logger = Logger("DejavuSync")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val requests = Channel<Boolean>(Channel.CONFLATED)
    private val _status = MutableStateFlow(DejavuSyncStatus())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var engine: SpacesSyncEngine? = null

    @Volatile
    private var firefoxEngine: FirefoxTabsEngine? = null

    @Volatile
    private var scanner: SyncSourceScanner? = null
    private var pollJob: Job? = null

    @Volatile
    private var turnOnRequested = false

    /** When the account's devices were last asked for, in [SystemClock.elapsedRealtime] milliseconds, or 0. */
    @Volatile
    private var devicesRefreshedAt = 0L

    @Volatile
    private var scanRequested = false

    @Volatile
    private var sourcePutOff = false

    @Volatile
    private var accountManagerReady = false
    private var lastRun = 0L

    /** Syncs in a row that could not reach the servers, which space out the next tries. */
    private var failedToReach = 0

    /** State of workspace sync, for its settings. */
    val status: StateFlow<DejavuSyncStatus> = _status.asStateFlow()

    /** Starts workspace sync. Only the first call does anything. */
    fun install(context: Context) {
        synchronized(this) {
            if (appContext != null) return
            appContext = context.applicationContext
        }
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val store = SpacesSyncStore(File(app.filesDir, STORE_FILE))
        val firefoxStore = FirefoxSyncStore(File(app.filesDir, FIREFOX_STORE_FILE))
        // Dejavu synced with Zen before it could sync with Firefox; those who did keep doing it without being asked.
        val source = DejavuSyncSource.fromKey(prefs.getString(KEY_SOURCE, null))
            ?: DejavuSyncSource.ZEN.takeIf { store.load().syncId != null }?.also { zen ->
                prefs.edit { putString(KEY_SOURCE, zen.key) }
            }
        sourcePutOff = prefs.getBoolean(KEY_SOURCE_PUT_OFF, false)
        _status.update {
            it.copy(
                enabled = prefs.getBoolean(KEY_ENABLED, true),
                normalTabs = prefs.getBoolean(KEY_NORMAL_TABS, true),
                source = source,
                lastSynced = when (source) {
                    DejavuSyncSource.ZEN -> store.load().lastSynced
                    DejavuSyncSource.FIREFOX -> firefoxStore.load().lastSynced
                    null -> 0L
                },
            )
        }

        scope.launch(Dispatchers.Main) {
            // The fetch client comes with the Gecko runtime, which is created on the main thread.
            val http = FetchSyncHttp(app.components.core.client)
            val data = DejavuSpacesData(app)
            engine = SpacesSyncEngine(http = http, store = store, local = data, log = { logger.info(it) })
            firefoxEngine = FirefoxTabsEngine(http = http, store = firefoxStore, local = data, log = { logger.info(it) })
            scanner = SyncSourceScanner(http)
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

    /** Turns workspace sync on or off on this device only; other devices keep syncing. */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
        _status.update { it.copy(enabled = enabled, problem = null) }
        if (enabled) syncNow()
    }

    /** Syncs tabs that are not pinned too, or only pinned tabs, folders and essentials. */
    fun setNormalTabs(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_NORMAL_TABS, enabled) }
        _status.update { it.copy(normalTabs = enabled) }
        syncNow()
    }

    /**
     * Makes workspaces sync with [source] from now on, and syncs. Leaving Firefox takes the workspaces and tabs of its
     * computers out of Dejavu, as Firefox keeps them; leaving Zen keeps its workspaces here, no longer synced.
     */
    fun chooseSource(context: Context, source: DejavuSyncSource) {
        val previous = _status.value.source
        prefs(context).edit {
            putString(KEY_SOURCE, source.key)
            remove(KEY_SOURCE_PUT_OFF)
        }
        sourcePutOff = false
        _status.update { it.copy(source = source, askSource = false, problem = null) }
        scope.launch {
            if (previous == DejavuSyncSource.FIREFOX && source != DejavuSyncSource.FIREFOX) {
                mutex.withLock {
                    runCatching { firefoxEngine?.removeAll() }.onFailure { logger.warn("Could not remove Firefox", it) }
                }
            }
            val lastSynced = when (source) {
                DejavuSyncSource.ZEN -> engine?.lastSynced
                DejavuSyncSource.FIREFOX -> firefoxEngine?.lastSynced
            } ?: 0L
            _status.update { if (it.source == source) it.copy(lastSynced = lastSynced) else it }
            syncNow()
        }
    }

    /**
     * Leaves the choice of a browser for later: it waits in the sync settings, and workspaces are not synced until the
     * user chooses.
     */
    fun putOffSourceChoice(context: Context) {
        prefs(context).edit { putBoolean(KEY_SOURCE_PUT_OFF, true) }
        sourcePutOff = true
        _status.update { it.copy(askSource = false) }
    }

    /** Looks again at what the account syncs from Firefox and Zen, to choose between them. */
    fun findSources() {
        scanRequested = true
        syncNow()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
        return mutex.withLock {
            runCatching {
                when (current.source) {
                    DejavuSyncSource.ZEN -> engine?.hasLocalChanges() == true
                    DejavuSyncSource.FIREFOX -> firefoxEngine?.hasLocalChanges() == true
                    null -> false
                }
            }.getOrDefault(false)
        }
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
        val engines = Engines(engine ?: return, firefoxEngine ?: return, scanner ?: return)
        if (!accountManagerReady || !_status.value.enabled) return
        if (!manual && engines.backoffUntil > System.currentTimeMillis()) return
        val account = context.components.backgroundServices.accountManager.authenticatedAccount()
        if (account == null) {
            _status.update { it.copy(signedIn = false) }
            return
        }
        val turnOn = turnOnRequested && manual
        turnOnRequested = false
        val scan = scanRequested
        scanRequested = false
        mutex.withLock {
            val source = _status.value.source
            _status.update { it.copy(signedIn = true, syncing = true) }
            val outcome = sync(engines, account, source, scan = scan || source == null, turnOn = turnOn)
            retrySoonIfUnreachable(outcome.problem)
            _status.update { current ->
                val found = outcome.found ?: current.found
                current.copy(
                    syncing = false,
                    // The user may have chosen a browser during the sync, which syncs again with it next.
                    problem = if (current.source == source) outcome.problem else null,
                    lastSynced = if (outcome.problem == null) System.currentTimeMillis() else current.lastSynced,
                    found = found,
                    askSource = current.source == null && !sourcePutOff && found?.isEmpty == false,
                )
            }
        }
    }

    /**
     * Tries again soon when the servers could not be reached while the app is shown, so that a short loss of network
     * does not leave the problem showing until the next regular sync: after 30 seconds, then twice as long each time.
     */
    private fun retrySoonIfUnreachable(problem: DejavuSyncProblem?) {
        if (problem != DejavuSyncProblem.OFFLINE) {
            failedToReach = 0
            return
        }
        if (pollJob?.isActive != true) return
        val wait = minOf(UNREACHABLE_RETRY_MS shl minOf(failedToReach, MAX_RETRY_DOUBLINGS), FOREGROUND_POLL_MS)
        failedToReach++
        scope.launch {
            delay(wait)
            if (pollJob?.isActive == true) request()
        }
    }

    /** The engines of each browser, and the scanner that finds what the account has of them. */
    private class Engines(val zen: SpacesSyncEngine, val firefox: FirefoxTabsEngine, val scanner: SyncSourceScanner) {
        val backoffUntil: Long
            get() = maxOf(zen.backoffUntil, firefox.backoffUntil, scanner.backoffUntil)
    }

    /** How a sync went: what stopped it, if anything, and what the account has of Firefox and Zen when it looked. */
    private class Outcome(val problem: DejavuSyncProblem?, val found: FoundSyncData? = null)

    /**
     * Syncs once with [source], after looking at what the account has of Firefox and Zen when [scan] is set. Without a
     * source, it only looks.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun sync(
        engines: Engines,
        account: OAuthAccount,
        source: DejavuSyncSource?,
        scan: Boolean,
        turnOn: Boolean,
    ): Outcome {
        return try {
            val auth = authOf(account) ?: throw SyncAuthException("The account has no sync key")
            val connected = connectedDevices(account)
            val refreshDevices: suspend () -> Set<String>? = { refreshedDevices(account) }
            val found = if (scan) {
                when (val result = engines.scanner.scan(auth, connected, refreshDevices)) {
                    ScanResult.NotSetUp -> return Outcome(DejavuSyncProblem.NOT_SET_UP)
                    is ScanResult.Found -> result.data.also { logScan(it) }
                }
            } else {
                null
            }
            val problem = when (source) {
                null -> DejavuSyncProblem.CHOOSE_SOURCE
                DejavuSyncSource.ZEN -> problemOf(engines.zen.sync(auth, turnOn))
                DejavuSyncSource.FIREFOX -> problemOf(engines.firefox.sync(auth, connected, refreshDevices))
            }
            Outcome(problem, found)
        } catch (e: SyncConflictException) {
            logger.info("Changed elsewhere during the sync, syncing again", e)
            scope.launch {
                delay(CONFLICT_RETRY_MS)
                request()
            }
            Outcome(null)
        } catch (e: SyncAuthException) {
            logger.warn("Sync refused the account", e)
            Outcome(DejavuSyncProblem.SIGN_IN_AGAIN)
        } catch (e: SyncServerException) {
            logger.warn("Sync server error ${e.status}", e)
            Outcome(DejavuSyncProblem.SERVER)
        } catch (e: IOException) {
            logger.warn("Sync servers not reachable", e)
            Outcome(DejavuSyncProblem.OFFLINE)
        } catch (e: Exception) {
            logger.error("Workspace sync failed", e)
            Outcome(DejavuSyncProblem.SERVER)
        }
    }

    private fun logScan(found: FoundSyncData) {
        val zen = found.zen?.let { "${it.spaces} spaces, sidebar sync ${if (it.spacesSyncOn) "on" else "off"}" } ?: "none"
        logger.info("Found ${found.firefox.size} Firefox computers, Zen: $zen")
    }

    /** Ids of the account's devices, or `null` while they are not known. */
    private fun connectedDevices(account: OAuthAccount): Set<String>? =
        account.deviceConstellation().state()?.otherDevices?.map { it.id }?.toSet()?.takeIf { it.isNotEmpty() }

    /**
     * Asks the account for its devices again and returns their ids, or `null` when it could not, at most once a minute.
     * The account tells about a device that joined it only by push messages, which Dejavu may not get.
     */
    private suspend fun refreshedDevices(account: OAuthAccount): Set<String>? {
        val now = SystemClock.elapsedRealtime()
        if (devicesRefreshedAt != 0L && now - devicesRefreshedAt < DEVICES_REFRESH_MS) return null
        devicesRefreshedAt = now
        logger.info("A device is not among the account's devices, asking the account again")
        return if (account.deviceConstellation().refreshDevices()) connectedDevices(account) else null
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

    private fun problemOf(result: FirefoxSyncResult): DejavuSyncProblem? = when (result) {
        is FirefoxSyncResult.Synced -> DejavuSyncProblem.NO_FIREFOX.takeIf { result.computers.isEmpty() }
        FirefoxSyncResult.NotSetUp -> DejavuSyncProblem.NOT_SET_UP
        FirefoxSyncResult.TabsOff -> DejavuSyncProblem.TABS_OFF
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
            // The next account may sync other browsers, so it is asked again which one to follow.
            appContext?.let { context ->
                prefs(context).edit {
                    remove(KEY_SOURCE)
                    remove(KEY_SOURCE_PUT_OFF)
                }
            }
            sourcePutOff = false
            _status.update {
                it.copy(signedIn = false, problem = null, lastSynced = 0L, source = null, found = null, askSource = false)
            }
            scope.launch {
                mutex.withLock {
                    engine?.reset()
                    firefoxEngine?.let { firefox ->
                        runCatching { firefox.removeAll() }.onFailure { logger.warn("Could not remove Firefox", it) }
                        firefox.reset()
                    }
                    scanner?.reset()
                }
            }
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

/** Dejavu's workspaces, containers and open tabs, as [SpacesSyncEngine] and [FirefoxTabsEngine] read and change them. */
private class DejavuSpacesData(private val context: Context) : SpacesLocalData, FirefoxLocalData {
    private val repository: WorkspaceRepository
        get() = WorkspaceRepository.get(context)

    private val containers: DejavuContainerStorage
        get() = DejavuContainerStorage.get(context)

    override val defaultWorkspaceName: String = context.getString(R.string.dejavu_workspace_default_name)

    override val unnamedGroup: String = context.getString(R.string.dejavu_sync_unnamed_group)

    override suspend fun closeRemoteTabs(deviceId: String, urls: List<String>) {
        context.components.backgroundServices.syncedTabsCommands.add(deviceId, DeviceCommandOutgoing.CloseTab(urls))
    }

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
