// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Combine
import Foundation
import UIKit

/// Why spaces sync is not working, as shown in its settings.
enum DejavuSyncProblem {
    /// Firefox Sync has not set up the account's storage yet. It is done by the first sync of a Firefox browser.
    case notSetUp

    /// Spaces sync is not turned on for the account, see `DejavuSync.turnOn`.
    case notTurnedOn

    /// Another device uses a newer version of spaces sync.
    case needsUpdate

    /// The account has to be signed in again.
    case signInAgain

    /// The servers could not be reached.
    case offline

    /// The servers failed or asked to wait.
    case server
}

/// State of spaces sync.
///
/// - `enabled`: Whether spaces sync is turned on in Dejavu.
/// - `normalTabs`: Whether tabs that are not pinned are synced too, like Zen's "Include unpinned tabs".
/// - `signedIn`: Whether a Mozilla account is signed in.
/// - `syncing`: Whether a sync is running.
/// - `lastSynced`: Time of the last complete sync in milliseconds, or 0.
/// - `problem`: What stopped the last sync, if anything.
struct DejavuSyncStatus: Equatable {
    var enabled = true
    var normalTabs = true
    var signedIn = false
    var syncing = false
    var lastSynced: Int64 = 0
    var problem: DejavuSyncProblem?
}

/// Syncs Dejavu's workspaces with Zen through the Mozilla account, see `SpacesSyncEngine`, like the Android app's
/// `DejavuSync`.
///
/// Syncs run one at a time: when the app comes to the foreground and every few minutes while it is shown, a few seconds
/// after local changes, after signing in, and when the app goes to the background with changes not uploaded yet. Used
/// from the main thread only.
final class DejavuSync: ObservableObject {
    static let shared = DejavuSync()

    private enum Key {
        static let enabled = "dejavu.sync_enabled"
        static let normalTabs = "dejavu.sync_normal_tabs"
    }

    private static let localChangeDelay: TimeInterval = 3
    private static let foregroundPoll: TimeInterval = 5 * 60
    private static let minIntervalMillis: Int64 = 10_000
    private static let conflictRetry: TimeInterval = 5

    @Published private(set) var status = DejavuSyncStatus()

    private let engine: SpacesSyncEngine
    private var started = false
    private var cancellables = Set<AnyCancellable>()
    private var pollTimer: Timer?

    /// The request waiting to run: `true` when the user asked for it, `false` for an automatic one.
    private var requested: Bool?
    private var draining = false

    /// Whether the next automatic sync runs without waiting, as the app is about to be suspended.
    private var hurry = false
    private var turnOnRequested = false
    private var lastRun: Int64 = 0

    /// The engine work started last; the next one waits for it, so the engine runs one call at a time.
    private var engineTail: Task<Void, Never>?
    private var backgroundTask = UIBackgroundTaskIdentifier.invalid

    private init() {
        engine = SpacesSyncEngine(
            http: URLSessionSyncHttp(), store: SpacesSyncStore(), local: DejavuSpacesData(), log: { Log.sync($0) })
    }

    /// Starts spaces sync. Only the first call does anything.
    func start() {
        guard !started else { return }
        started = true
        let defaults = UserDefaults.standard
        status.enabled = defaults.object(forKey: Key.enabled) as? Bool ?? true
        status.normalTabs = defaults.object(forKey: Key.normalTabs) as? Bool ?? true
        status.signedIn = MozillaAccount.shared.isSignedIn
        status.lastSynced = engine.lastSynced
        MozillaAccount.shared.$isSignedIn
            .dropFirst()
            .removeDuplicates()
            .sink { [weak self] signedIn in self?.accountChanged(signedIn) }
            .store(in: &cancellables)
        watchLocalChanges()
        if status.signedIn {
            request()
        }
    }

    /// Syncs now, as asked by the user.
    func syncNow() {
        request(manual: true)
    }

    /// Turns spaces sync on for the account, which also turns it on in Zen, and syncs.
    func turnOn() {
        turnOnRequested = true
        syncNow()
    }

    /// Turns spaces sync on or off on this device only; other devices keep syncing.
    func setEnabled(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: Key.enabled)
        status.enabled = enabled
        status.problem = nil
        if enabled {
            syncNow()
        }
    }

    /// Syncs tabs that are not pinned too, or only pinned tabs, folders and essentials.
    func setNormalTabs(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: Key.normalTabs)
        status.normalTabs = enabled
        syncNow()
    }

    /// Signs the Mozilla account out; sync forgets what it knew about the account's servers.
    @MainActor
    func signOut() {
        MozillaAccount.shared.signOut()
    }

    func appBecameActive() {
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: Self.foregroundPoll, repeats: true) { [weak self] _ in
            self?.request()
        }
        request()
    }

    /// Uploads changes not sent yet, in the short time iOS gives an app that goes to the background.
    func appWentToBackground() {
        pollTimer?.invalidate()
        pollTimer = nil
        guard status.enabled, status.signedIn, backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "DejavuSync") { [weak self] in
            self?.endBackgroundTask()
        }
        Task { @MainActor in
            if await self.hasLocalChanges() {
                // The running or next round of syncs ends the background task.
                self.hurry = true
                self.request()
            } else {
                self.endBackgroundTask()
            }
        }
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    private func accountChanged(_ signedIn: Bool) {
        if signedIn {
            status.signedIn = true
            status.problem = nil
            request(manual: true)
        } else {
            status.signedIn = false
            status.problem = nil
            status.lastSynced = 0
            Task { @MainActor in
                await self.withEngine { engine in engine.reset() }
            }
        }
    }

    private func watchLocalChanges() {
        // Tabs that are not pinned count by what their records hold: address, title and container.
        let tabs = BrowserStore.shared.$tabs
            .map { tabs in tabs.filter { !$0.isPrivate }.map { [$0.id, $0.url, $0.title, $0.contextId ?? ""] } }
            .removeDuplicates()
        Publishers.CombineLatest3(
            WorkspaceRepository.shared.$state.removeDuplicates(), ContainerStore.shared.$records.removeDuplicates(), tabs
        )
        .dropFirst()
        .debounce(for: .seconds(Self.localChangeDelay), scheduler: RunLoop.main)
        .sink { [weak self] _ in
            Task { @MainActor in
                guard let self, await self.hasLocalChanges() else { return }
                self.request()
            }
        }
        .store(in: &cancellables)
    }

    @MainActor
    private func hasLocalChanges() async -> Bool {
        guard status.enabled, status.signedIn else { return false }
        return await withEngine { engine in await engine.hasLocalChanges() }
    }

    // MARK: Running syncs

    /// Asks for a sync. Requests made while one runs are merged into one sync after it.
    private func request(manual: Bool = false) {
        requested = (requested ?? false) || manual
        guard !draining else { return }
        Task { @MainActor in await self.drain() }
    }

    /// Runs the requested syncs one after the other. Automatic ones wait until a while after the previous sync.
    @MainActor
    private func drain() async {
        guard !draining else { return }
        draining = true
        while let manual = requested {
            requested = nil
            let wait = lastRun + Self.minIntervalMillis - nowMillis()
            if !manual && !hurry && wait > 0 {
                try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000)
            }
            hurry = false
            await runSync(manual: manual)
            lastRun = nowMillis()
        }
        draining = false
        endBackgroundTask()
    }

    @MainActor
    private func runSync(manual: Bool) async {
        guard status.enabled else { return }
        guard MozillaAccount.shared.isSignedIn else {
            status.signedIn = false
            return
        }
        let turnOn = turnOnRequested && manual
        turnOnRequested = false
        await withEngine { (engine: SpacesSyncEngine) -> Void in
            if !manual && engine.backoffUntil > nowMillis() {
                return
            }
            self.status.signedIn = true
            self.status.syncing = true
            let problem = await self.sync(engine, turnOn: turnOn)
            self.status.syncing = false
            self.status.problem = problem
            if problem == nil {
                self.status.lastSynced = engine.lastSynced
            }
        }
    }

    /// Syncs once and returns what stopped the sync, if anything.
    @MainActor
    private func sync(_ engine: SpacesSyncEngine, turnOn: Bool) async -> DejavuSyncProblem? {
        do {
            return problemOf(try await syncRefreshingToken(engine, turnOn: turnOn))
        } catch is SyncConflictError {
            Log.sync("Changed elsewhere during the sync, syncing again")
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.conflictRetry) { [weak self] in
                self?.request()
            }
            return nil
        } catch let error as SyncAuthError {
            Log.sync("Sync refused the account: \(error)")
            return .signInAgain
        } catch let error as SyncServerError {
            Log.sync("Sync server error \(error.status): \(error)")
            return .server
        } catch let error as URLError {
            Log.sync("Sync servers not reachable: \(error)")
            return .offline
        } catch {
            Log.sync("Spaces sync failed: \(error)")
            return .server
        }
    }

    /// Syncs, asking the account for a new access token once when the token server refuses the current one.
    @MainActor
    private func syncRefreshingToken(_ engine: SpacesSyncEngine, turnOn: Bool) async throws -> SpacesSyncResult {
        do {
            return try await engine.sync(try await MozillaAccount.shared.syncAuth(), turnOn: turnOn)
        } catch is SyncAuthError {
            MozillaAccount.shared.invalidateAccessToken()
            return try await engine.sync(try await MozillaAccount.shared.syncAuth(), turnOn: turnOn)
        }
    }

    private func problemOf(_ result: SpacesSyncResult) -> DejavuSyncProblem? {
        switch result {
        case .synced(let received, let sent, let pending):
            Log.sync("Synced: \(received) in, \(sent) out, \(pending) waiting")
            return nil
        case .notSetUp:
            return .notSetUp
        case .notTurnedOn:
            return .notTurnedOn
        case .needsUpdate:
            return .needsUpdate
        }
    }

    /// Runs `work` with the engine once the engine work started before it has ended.
    @MainActor
    private func withEngine<T: Sendable>(_ work: @escaping @MainActor (SpacesSyncEngine) async -> T) async -> T {
        let previous = engineTail
        let engine = self.engine
        let task = Task { @MainActor () -> T in
            await previous?.value
            return await work(engine)
        }
        engineTail = Task { @MainActor in _ = await task.value }
        return await task.value
    }
}

/// Dejavu's workspaces, containers and tabs, as `SpacesSyncEngine` reads and changes them.
private final class DejavuSpacesData: SpacesLocalData {
    @MainActor var defaultWorkspaceName: String {
        WorkspaceRepository.shared.defaultName
    }

    @MainActor func read() -> LocalSpaces {
        let browser = BrowserStore.shared
        var tabs: [LocalTab]?
        if browser.restoreComplete {
            tabs = browser.tabs.filter { !$0.isPrivate }.map { tab in
                LocalTab(id: tab.id, url: tab.url, title: tab.title, contextId: tab.contextId, awake: tab.isAwake)
            }
        }
        return LocalSpaces(
            state: WorkspaceRepository.shared.state,
            containers: ContainerStore.shared.records,
            normalTabs: DejavuSync.shared.status.normalTabs,
            tabs: tabs)
    }

    @MainActor func update<T>(_ transform: (WorkspaceState) -> (WorkspaceState, T)) -> T {
        WorkspaceRepository.shared.update(transform)
    }

    @MainActor func saveContainer(contextId: String, name: String, color: ContainerColor, icon: ContainerIcon) {
        ContainerStore.shared.save(contextId: contextId, name: name, color: color, icon: icon)
    }

    @MainActor func removeContainer(_ contextId: String) {
        guard let record = ContainerStore.shared.record(contextId) else { return }
        ContainerActions.remove(record, removal: .moveTabs(nil))
    }

    @MainActor func builtinName(_ container: BuiltinContainer) -> String {
        switch container {
        case .personal: return L10n.containerPersonal
        case .work: return L10n.containerWork
        case .banking: return L10n.containerBanking
        case .shopping: return L10n.containerShopping
        }
    }

    @MainActor func runTabOps(_ ops: [TabOp]) {
        let store = BrowserStore.shared
        let repository = WorkspaceRepository.shared
        for op in ops {
            switch op {
            case .open(let id, let url, let title, let contextId, let workspaceId):
                guard store.tab(id) == nil else { continue }
                repository.expectTab(id)
                store.addTab(url: url, title: title, contextId: contextId, source: .sync, load: false, id: id)
                repository.assignTab(id, to: workspaceId)
            case .retarget(let id, let url, let title):
                // A tab that is shown or in a split view keeps its page.
                guard let tab = store.tab(id), !tab.isAwake, store.selectedTabId != id,
                    repository.state.splitOf(id) == nil
                else {
                    continue
                }
                store.retarget(id, url: url, title: title)
            case .close(let id):
                if store.tab(id) != nil {
                    store.removeTab(id, undoable: false)
                }
            }
        }
    }
}
