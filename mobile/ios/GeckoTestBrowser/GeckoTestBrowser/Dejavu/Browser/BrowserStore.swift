// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Combine
import Foundation
import GeckoView
import UIKit

/// What the browser screen does for the tabs: things that need a screen, like the menu of a long-pressed link.
protocol BrowserStoreUI: AnyObject {
    /// The session of tab `tabId` is about to close or has ended; a view showing it lets go of it.
    func sessionWillClose(tabId: String)

    /// A page asked for the menu of a long-pressed link, image or media element.
    func showContextMenu(tabId: String, screenX: Int, screenY: Int, element: ContextElement)

    /// The page of tab `tabId` entered or left full screen.
    func fullScreenChanged(tabId: String, fullScreen: Bool)

    /// A link of tab `tabId` leads to another app; the screen asks before opening it.
    func openExternally(_ url: URL, tabId: String)
}

/// A tab closed with a way to bring it back.
private struct ClosedTab {
    let tab: TabInfo
    let state: GeckoSessionState?
    let index: Int
    let workspaceId: String?
}

/// Dejavu's open tabs and the Gecko sessions behind them, like android-components' BrowserStore with Fenix's session
/// storage. Tabs that are not private are saved, and come back on the next launch without loading: a tab's page loads
/// when it is shown, with its back and forward history. Used from the main thread only.
final class BrowserStore: ObservableObject {
    static let shared = BrowserStore()

    private static let fileName = "tabs.json"

    /// Most tabs kept loaded in memory at once; the ones used longest ago go to sleep.
    private static let maxAwakeTabs = 8

    /// How long closed tabs can be brought back, and so how long their temporary containers are kept.
    static let undoWindow: TimeInterval = 20

    @Published private(set) var tabs: [TabInfo] = []
    @Published private(set) var selectedTabId: String?

    /// Whether the tabs saved at the last run are back.
    @Published private(set) var restoreComplete = false

    weak var ui: BrowserStoreUI?

    private var sessions: [String: GeckoSession] = [:]
    private var delegates: [String: TabSessionDelegate] = [:]
    private var states: [String: GeckoSessionState] = [:]
    private var closedBatches: [(closedAt: Date, tabs: [ClosedTab])] = []
    private var saveScheduled = false
    private var cleanup: DispatchWorkItem?

    private init() {
        restore()
        restoreComplete = true
        scheduleTemporaryContainerCleanup(after: 2)
    }

    var normalTabs: [TabInfo] {
        tabs.filter { !$0.isPrivate }
    }

    var privateTabs: [TabInfo] {
        tabs.filter { $0.isPrivate }
    }

    var selectedTab: TabInfo? {
        selectedTabId.flatMap { tab($0) }
    }

    func tab(_ id: String) -> TabInfo? {
        tabs.first { $0.id == id }
    }

    /// The open session of tab `id`, if its page is loaded.
    func session(for id: String) -> GeckoSession? {
        sessions[id]
    }

    // MARK: - Opening and closing

    /// Opens a new tab and returns its id.
    ///
    /// - Parameters:
    ///   - select: Whether the new tab becomes the selected tab.
    ///   - load: Whether its page loads right away rather than when it is first shown.
    ///   - id: The id of the new tab, for tabs that sync under a known id.
    ///   - anchorId: A tab the new one is put right after; without it, it goes last.
    @discardableResult
    func addTab(
        url: String,
        title: String = "",
        contextId: String? = nil,
        isPrivate: Bool = false,
        parentId: String? = nil,
        source: TabSource = .app,
        select: Bool = false,
        load: Bool = true,
        id: String? = nil,
        after anchorId: String? = nil,
        state: GeckoSessionState? = nil
    ) -> String {
        let now = nowMillis()
        let tab = TabInfo(
            id: id ?? newId(),
            url: url,
            title: title,
            contextId: contextId,
            isPrivate: isPrivate,
            parentId: parentId,
            source: source,
            createdAt: now,
            lastAccess: now)
        if let state {
            states[tab.id] = state
        }
        if let anchorId, let index = tabs.firstIndex(where: { $0.id == anchorId }) {
            tabs.insert(tab, at: index + 1)
        } else {
            tabs.append(tab)
        }
        if select {
            selectTab(tab.id)
        } else if load {
            wake(tab.id)
        }
        scheduleSave()
        return tab.id
    }

    /// Makes tab `id` the selected tab and loads its page.
    func selectTab(_ id: String) {
        guard tab(id) != nil else { return }
        selectedTabId = id
        update(id) { $0.lastAccess = nowMillis() }
        wake(id)
        scheduleSave()
    }

    /// Forgets the selected tab, like when its page asked to close.
    func clearSelection() {
        selectedTabId = nil
    }

    /// Closes tab `id`. Closed tabs that are not private can be brought back with `undoClose` for a while.
    func removeTab(_ id: String, undoable: Bool = true) {
        removeTabs([id], undoable: undoable)
    }

    func removeTabs(_ ids: [String], undoable: Bool = true) {
        let removing = Set(ids)
        guard tabs.contains(where: { removing.contains($0.id) }) else { return }
        let workspaces = WorkspaceRepository.shared.state
        var closed: [ClosedTab] = []
        for (index, tab) in tabs.enumerated() where removing.contains(tab.id) {
            if undoable && !tab.isPrivate {
                closed.append(
                    ClosedTab(tab: tab, state: states[tab.id], index: index, workspaceId: workspaces.assignments[tab.id]))
            }
            closeSession(tab.id)
            states.removeValue(forKey: tab.id)
        }
        if let selectedTabId, removing.contains(selectedTabId) {
            let parent = tab(selectedTabId)?.parentId.flatMap { tab($0) }
            self.selectedTabId = parent.flatMap { removing.contains($0.id) ? nil : $0.id }
        }
        tabs.removeAll { removing.contains($0.id) }
        if !closed.isEmpty {
            closedBatches.append((closedAt: Date(), tabs: closed))
        }
        scheduleSave()
        scheduleTemporaryContainerCleanup(after: Self.undoWindow + 1)
    }

    func removePrivateTabs() {
        removeTabs(privateTabs.map { $0.id }, undoable: false)
    }

    /// Brings back the tabs closed last, if that was a short while ago. They come back without loading.
    func undoClose() {
        guard let batch = closedBatches.popLast(), Date().timeIntervalSince(batch.closedAt) < Self.undoWindow else {
            return
        }
        for closed in batch.tabs.sorted(by: { $0.index < $1.index }) {
            guard tab(closed.tab.id) == nil else { continue }
            var restored = closed.tab
            restored.isAwake = false
            restored.isLoading = false
            tabs.insert(restored, at: min(closed.index, tabs.count))
            if let state = closed.state {
                states[restored.id] = state
            }
            if let workspaceId = closed.workspaceId {
                WorkspaceRepository.shared.expectTab(restored.id)
                WorkspaceRepository.shared.assignTab(restored.id, to: workspaceId)
            }
        }
        scheduleSave()
    }

    /// Opens a copy of tab `id` next to it, with the same history, and returns the copy's id.
    @discardableResult
    func duplicate(_ id: String) -> String? {
        guard let original = tab(id) else { return nil }
        return addTab(
            url: original.url, title: original.title, contextId: original.contextId, isPrivate: original.isPrivate,
            source: .app, select: false, load: false, after: id, state: states[id])
    }

    /// Moves tab `tab` to container `contextId`, or out of any container when it is `nil`. A tab cannot change its
    /// container, so it is reopened next to itself in `contextId` and the old one is closed; its history is lost.
    ///
    /// - Returns: The id of the new tab.
    @discardableResult
    func reopen(_ id: String, inContainer contextId: String?, select: Bool, load: Bool) -> String? {
        guard let old = tab(id) else { return nil }
        let newTabId = addTab(
            url: old.url, title: old.title, contextId: contextId, isPrivate: old.isPrivate, source: .app,
            select: false, load: load, after: id)
        WorkspaceRepository.shared.replaceTab(id, with: newTabId)
        if select {
            selectTab(newTabId)
        }
        removeTab(id, undoable: false)
        return newTabId
    }

    /// Opens tab `id` again at `url`, for a tab whose page is not loaded, keeping its place, like spaces sync does when
    /// another device moved the tab on.
    func retarget(_ id: String, url: String, title: String) {
        guard sessions[id] == nil else { return }
        states.removeValue(forKey: id)
        update(id) { tab in
            tab.url = url
            tab.title = title
        }
        scheduleSave()
    }

    /// Moves tabs `ids` right after or before `anchorId`, in their current order.
    func moveTabs(_ ids: [String], nextTo anchorId: String, after: Bool) {
        let moving = Set(ids)
        guard !moving.contains(anchorId) else { return }
        let movingTabs = tabs.filter { moving.contains($0.id) }
        guard !movingTabs.isEmpty else { return }
        var rest = tabs.filter { !moving.contains($0.id) }
        guard let anchor = rest.firstIndex(where: { $0.id == anchorId }) else { return }
        rest.insert(contentsOf: movingTabs, at: after ? anchor + 1 : anchor)
        tabs = rest
        scheduleSave()
    }

    // MARK: - Sessions

    /// Loads the page of tab `id`: opens its session and brings back its history, or loads its address.
    @discardableResult
    func wake(_ id: String) -> GeckoSession? {
        if let session = sessions[id] {
            return session
        }
        guard let tab = tab(id) else { return nil }
        let session = makeSession(for: tab)
        session.open()
        sessions[id] = session
        update(id) { $0.isAwake = true }
        if let state = states[id], state.hasHistory, let current = state.currentUrl, current.hasPrefix("http") {
            session.restoreState(state)
        } else if !tab.url.isEmpty && tab.url != "about:blank" {
            session.load(tab.url)
        }
        limitAwakeTabs(keeping: id)
        return session
    }

    /// Puts tab `id` to sleep: closes its session but keeps the tab and its history.
    func suspend(_ id: String) {
        guard sessions[id] != nil else { return }
        closeSession(id)
        update(id) { tab in
            tab.isAwake = false
            tab.isLoading = false
            tab.progress = 0
        }
    }

    func load(_ url: String, in id: String) {
        update(id) { $0.url = url }
        if let session = sessions[id] {
            session.load(url)
        } else {
            states.removeValue(forKey: id)
            wake(id)
        }
        scheduleSave()
    }

    func reload(_ id: String) {
        sessions[id]?.reload()
    }

    func stop(_ id: String) {
        sessions[id]?.stop()
    }

    func goBack(_ id: String) {
        sessions[id]?.goBack()
    }

    func goForward(_ id: String) {
        sessions[id]?.goForward()
    }

    /// Asks the page of tab `id` for its desktop or mobile site, and reloads it.
    func setDesktopMode(_ id: String, _ enabled: Bool) {
        update(id) { $0.desktopMode = enabled }
        if let session = sessions[id] {
            session.setDesktopMode(enabled)
            session.reload()
        }
        scheduleSave()
    }

    /// Sleeps the tabs used longest ago while more than `maxAwakeTabs` are loaded, except `keeping` and the tabs shown.
    private func limitAwakeTabs(keeping id: String) {
        let awake = tabs.filter { sessions[$0.id] != nil }
        guard awake.count > Self.maxAwakeTabs else { return }
        var protected: Set<String> = [id]
        if let selectedTabId {
            protected.insert(selectedTabId)
            if let split = WorkspaceRepository.shared.state.splitOf(selectedTabId) {
                protected.formUnion(split.tabIds)
            }
        }
        let candidates = awake.filter { !protected.contains($0.id) }.sorted { $0.lastAccess < $1.lastAccess }
        for tab in candidates.prefix(awake.count - Self.maxAwakeTabs) {
            suspend(tab.id)
        }
    }

    private func makeSession(for tab: TabInfo) -> GeckoSession {
        let session = GeckoSession(
            settings: GeckoSessionSettings(
                contextId: tab.contextId, usePrivateMode: tab.isPrivate, desktopMode: tab.desktopMode))
        let delegate = TabSessionDelegate(tabId: tab.id, store: self)
        session.contentDelegate = delegate
        session.navigationDelegate = delegate
        session.progressDelegate = delegate
        delegates[tab.id] = delegate
        return session
    }

    private func closeSession(_ id: String) {
        guard let session = sessions.removeValue(forKey: id) else { return }
        ui?.sessionWillClose(tabId: id)
        session.close()
        delegates.removeValue(forKey: id)
    }

    // MARK: - Events of the sessions

    fileprivate func update(_ id: String, _ change: (inout TabInfo) -> Void) {
        guard let index = tabs.firstIndex(where: { $0.id == id }) else { return }
        var tab = tabs[index]
        change(&tab)
        if tab != tabs[index] {
            tabs[index] = tab
        }
    }

    fileprivate func locationChanged(_ id: String, url: String) {
        guard let current = tab(id) else { return }
        update(id) { $0.url = url }
        if !current.isPrivate {
            HistoryStore.shared.record(url: url, title: current.url == url ? current.title : "")
        }
        scheduleSave()
    }

    fileprivate func titleChanged(_ id: String, title: String) {
        guard let current = tab(id) else { return }
        update(id) { $0.title = title }
        if !current.isPrivate {
            HistoryStore.shared.updateTitle(url: current.url, title: title)
        }
        scheduleSave()
    }

    fileprivate func stateChanged(_ id: String, state: GeckoSessionState) {
        guard tab(id) != nil else { return }
        states[id] = state
        scheduleSave()
    }

    /// The session of tab `id` crashed or was killed; the tab sleeps and loads again when it is shown.
    fileprivate func sessionEnded(_ id: String) {
        ui?.sessionWillClose(tabId: id)
        sessions.removeValue(forKey: id)
        delegates.removeValue(forKey: id)
        update(id) { tab in
            tab.isAwake = false
            tab.isLoading = false
        }
    }

    /// A page of tab `id` closed its own window.
    fileprivate func pageClosed(_ id: String) {
        removeTab(id, undoable: false)
    }

    /// Whether a page of tab `id` may load `request`. Links to other apps are handed to them, after asking.
    fileprivate func shouldLoad(_ id: String, request: LoadRequest) -> AllowOrDeny {
        guard let url = URL(string: request.uri), let scheme = url.scheme?.lowercased() else { return .allow }
        let browserSchemes: Set<String> = [
            "http", "https", "about", "data", "blob", "file", "javascript", "view-source", "resource", "chrome",
            "moz-extension",
        ]
        if browserSchemes.contains(scheme) {
            return .allow
        }
        if request.hasUserGesture {
            ui?.openExternally(url, tabId: id)
        }
        return .deny
    }

    /// A page of tab `parentId` opens a new window: a new tab next to it, in its container and workspace, shown at
    /// once. The returned session is opened by Gecko.
    fileprivate func sessionForNewWindow(parentId: String, uri: String) -> GeckoSession? {
        guard let parent = tab(parentId) else { return nil }
        let now = nowMillis()
        let tab = TabInfo(
            id: newId(),
            url: uri,
            title: "",
            contextId: parent.contextId,
            isPrivate: parent.isPrivate,
            parentId: parentId,
            source: .page,
            createdAt: now,
            lastAccess: now,
            isAwake: true)
        if let index = tabs.firstIndex(where: { $0.id == parentId }) {
            tabs.insert(tab, at: index + 1)
        } else {
            tabs.append(tab)
        }
        if !tab.isPrivate {
            let repository = WorkspaceRepository.shared
            repository.expectTab(tab.id)
            repository.assignTab(tab.id, to: repository.state.workspaceOf(parentId))
        }
        let session = makeSession(for: tab)
        sessions[tab.id] = session
        selectedTabId = tab.id
        scheduleSave()
        return session
    }

    // MARK: - Temporary containers

    /// Destroys temporary containers once no tab uses them: a while after their last tab is closed, so that the close
    /// can still be undone, and at startup for the ones left without tabs.
    private func scheduleTemporaryContainerCleanup(after delay: TimeInterval) {
        cleanup?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.destroyUnusedTemporaryContainers()
        }
        cleanup = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func destroyUnusedTemporaryContainers() {
        closedBatches.removeAll { Date().timeIntervalSince($0.closedAt) >= Self.undoWindow }
        var used = Set(tabs.compactMap { $0.contextId })
        for batch in closedBatches {
            used.formUnion(batch.tabs.compactMap { $0.tab.contextId })
        }
        let containers = ContainerStore.shared
        for record in containers.records where record.temporary && !used.contains(record.contextId) {
            GeckoRuntime.clearDataForSessionContext(record.contextId)
            containers.remove(record.contextId)
            WorkspaceRepository.shared.replaceContainer(record.contextId, with: nil)
        }
    }

    // MARK: - Saving

    /// Saves the tabs soon, once the changes coming in now are done.
    func scheduleSave() {
        guard !saveScheduled else { return }
        saveScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            self?.save()
        }
    }

    /// Saves the tabs that are not private now, like when the app goes to the background.
    func save() {
        saveScheduled = false
        let items: [[String: Any]] = normalTabs.map { tab in
            var item: [String: Any] = [
                "id": tab.id,
                "url": tab.url,
                "title": tab.title,
                "source": tab.source.rawValue,
                "createdAt": tab.createdAt,
                "lastAccess": tab.lastAccess,
                "desktopMode": tab.desktopMode,
            ]
            item["contextId"] = tab.contextId
            item["parentId"] = tab.parentId
            item["state"] = states[tab.id]?.json
            return item
        }
        var root: [String: Any] = ["version": 1, "tabs": items]
        if let selected = selectedTab, !selected.isPrivate {
            root["selected"] = selected.id
        }
        DataFiles.writeObject(root, to: Self.fileName)
    }

    private func restore() {
        guard let root = DataFiles.readObject(Self.fileName) else { return }
        for item in root.objects("tabs") {
            guard let id = item.string("id"), let url = item.string("url") else { continue }
            let now = nowMillis()
            var tab = TabInfo(
                id: id,
                url: url,
                title: item.string("title") ?? "",
                contextId: item.nonEmptyString("contextId"),
                isPrivate: false,
                parentId: item.nonEmptyString("parentId"),
                source: .restored,
                createdAt: item.int64("createdAt") ?? now,
                lastAccess: item.int64("lastAccess") ?? now)
            tab.desktopMode = item.bool("desktopMode") ?? false
            tabs.append(tab)
            if let json = item.string("state"), let state = GeckoSessionState(json: json) {
                states[id] = state
            }
        }
        if let selected = root.string("selected"), tabs.contains(where: { $0.id == selected }) {
            selectedTabId = selected
        }
    }
}

/// Hears the events of one tab's Gecko session and passes them to `BrowserStore`. Gecko calls it on the main thread.
private final class TabSessionDelegate: ContentDelegate, NavigationDelegate, ProgressDelegate {
    let tabId: String
    weak var store: BrowserStore?

    init(tabId: String, store: BrowserStore) {
        self.tabId = tabId
        self.store = store
    }

    // MARK: ContentDelegate

    func onTitleChange(session: GeckoSession, title: String) {
        store?.titleChanged(tabId, title: title)
    }

    func onPreviewImage(session: GeckoSession, previewImageUrl: String) {}

    func onFocusRequest(session: GeckoSession) {}

    func onCloseRequest(session: GeckoSession) {
        store?.pageClosed(tabId)
    }

    func onFullScreen(session: GeckoSession, fullScreen: Bool) {
        store?.ui?.fullScreenChanged(tabId: tabId, fullScreen: fullScreen)
    }

    func onMetaViewportFitChange(session: GeckoSession, viewportFit: String) {}

    func onProductUrl(session: GeckoSession) {}

    func onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: ContextElement) {
        store?.ui?.showContextMenu(tabId: tabId, screenX: screenX, screenY: screenY, element: element)
    }

    func onCrash(session: GeckoSession) {
        store?.sessionEnded(tabId)
    }

    func onKill(session: GeckoSession) {
        store?.sessionEnded(tabId)
    }

    func onFirstComposite(session: GeckoSession) {}

    func onFirstContentfulPaint(session: GeckoSession) {}

    func onPaintStatusReset(session: GeckoSession) {}

    func onWebAppManifest(session: GeckoSession, manifest: Any) {}

    // The async callbacks change the tabs, so they run on the main thread like the others.
    @MainActor
    func onSlowScript(session: GeckoSession, scriptFileName: String) async -> SlowScriptResponse {
        .halt
    }

    func onShowDynamicToolbar(session: GeckoSession) {}

    // MARK: NavigationDelegate

    func onLocationChange(session: GeckoSession, url: String?, permissions: [ContentPermission]) {
        guard let url else { return }
        store?.locationChanged(tabId, url: url)
    }

    func onCanGoBack(session: GeckoSession, canGoBack: Bool) {
        store?.update(tabId) { $0.canGoBack = canGoBack }
    }

    func onCanGoForward(session: GeckoSession, canGoForward: Bool) {
        store?.update(tabId) { $0.canGoForward = canGoForward }
    }

    @MainActor
    func onLoadRequest(session: GeckoSession, request: LoadRequest) async -> AllowOrDeny {
        store?.shouldLoad(tabId, request: request) ?? .allow
    }

    @MainActor
    func onSubframeLoadRequest(session: GeckoSession, request: LoadRequest) async -> AllowOrDeny {
        .allow
    }

    @MainActor
    func onNewSession(session: GeckoSession, uri: String) async -> GeckoSession? {
        store?.sessionForNewWindow(parentId: tabId, uri: uri)
    }

    // MARK: ProgressDelegate

    func onPageStart(session: GeckoSession, url: String) {
        store?.update(tabId) { tab in
            tab.isLoading = true
            tab.progress = 0.05
        }
    }

    func onPageStop(session: GeckoSession, success: Bool) {
        store?.update(tabId) { tab in
            tab.isLoading = false
            tab.progress = 1
        }
    }

    func onProgressChange(session: GeckoSession, progress: Int) {
        store?.update(tabId) { $0.progress = Double(progress) / 100 }
    }

    func onSecurityChange(session: GeckoSession, securityInfo: SecurityInformation) {
        store?.update(tabId) { $0.isSecure = securityInfo.isSecure }
    }

    func onSessionStateChange(session: GeckoSession, sessionState: GeckoSessionState) {
        store?.stateChanged(tabId, state: sessionState)
    }
}
