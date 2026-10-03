// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Combine
import Foundation
import GeckoView
import SwiftUI
import UIKit

/// The search screen being shown: editing the address of a tab, or starting a new tab.
struct SearchRequest: Identifiable, Equatable {
    let id = UUID()

    /// The text the field starts with.
    var text: String

    /// The tab whose address is edited; `nil` opens a new tab.
    var tabId: String?

    /// Whether the new tab is private.
    var isPrivate = false
}

/// A short message at the bottom of the screen, with an optional button, like Android's snackbars.
struct Toast: Identifiable {
    let id = UUID()
    let message: String
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil
    var long = false
}

/// The screens shown in a sheet over the browser or the home screen.
enum AppSheet: Identifiable {
    case settings(SettingsScreen?)
    case library(LibraryKind)
    case moreMenu(tabId: String)
    case splitPicker(tabId: String)
    case containerPicker(title: String, current: String?, onPick: (ContainerPick) -> Void)
    case workspacePicker(title: String, excluding: String?, onPick: (String) -> Void)

    var id: String {
        switch self {
        case .settings: return "settings"
        case .library(let kind): return "library-\(kind)"
        case .moreMenu(let tabId): return "menu-\(tabId)"
        case .splitPicker(let tabId): return "split-\(tabId)"
        case .containerPicker(let title, _, _): return "containers-\(title)"
        case .workspacePicker(let title, _, _): return "workspaces-\(title)"
        }
    }
}

/// The lists of the library.
enum LibraryKind: String {
    case bookmarks
    case history
}

/// What the app shows, and what the browser and the home screen do together: opening tabs in the right workspace and
/// container, the search screen, sheets and messages. Used from the main thread only.
final class AppModel: ObservableObject {
    static let shared = AppModel()

    enum Screen: Equatable {
        case home
        case browser
    }

    @Published var screen: Screen = .home
    @Published var search: SearchRequest?
    @Published var sheet: AppSheet?
    @Published private(set) var toast: Toast?

    /// The tab whose page is being searched with find in page, if any.
    @Published var findInPageTabId: String?

    /// Whether the shown page is in full screen, without the bars.
    @Published var isFullScreen = false

    let store = BrowserStore.shared
    let repository = WorkspaceRepository.shared
    let containers = ContainerStore.shared
    let settings = DejavuSettings.shared

    /// The view controller sheets and alerts of UIKit are shown from.
    weak var presenter: UIViewController?

    private var cancellables = Set<AnyCancellable>()
    private var newTabChoice: (pick: ContainerPick?, isPrivate: Bool, expiresAt: Date)?
    private var toastTimer: Timer?

    /// How long a container picked for a new tab is kept for the search that opens it.
    private static let newTabChoiceLifetime: TimeInterval = 10 * 60

    private init() {
        // Workspaces follow the tabs: new tabs join the shown workspace, closed ones are forgotten, and pinned tabs
        // take the titles of their pages, like the Android home screen does.
        store.$tabs
            .map { tabs in tabs.filter { !$0.isPrivate }.map { TabPage(id: $0.id, url: $0.url, title: $0.title) } }
            .removeDuplicates()
            .sink { [weak self] pages in self?.tabsChanged(pages) }
            .store(in: &cancellables)
    }

    private struct TabPage: Equatable {
        let id: String
        let url: String
        let title: String
    }

    private func tabsChanged(_ pages: [TabPage]) {
        repository.syncWithTabs(Set(pages.map { $0.id }), restoreComplete: store.restoreComplete)
        var titles: [String: (url: String, title: String)] = [:]
        for page in pages {
            titles[page.id] = (url: page.url, title: page.title)
        }
        repository.refreshPinTitles(titles)
    }

    // MARK: - Screens

    /// Shows the home screen, like Fenix's home button.
    func showHome() {
        findInPageTabId = nil
        isFullScreen = false
        screen = .home
    }

    /// Shows tab `id` in the browser.
    func openTab(_ id: String) {
        guard store.tab(id) != nil else { return }
        store.selectTab(id)
        screen = .browser
    }

    /// Opens the page of a pinned tab or essential: its open tab, or a new tab at its pinned address.
    func openPin(_ pin: PinnedItem) {
        if let tabId = pin.tabId, store.tab(tabId) != nil {
            openTab(tabId)
            return
        }
        guard let url = pin.url else { return }
        let tabId = newId()
        repository.expectTab(tabId)
        store.addTab(
            url: url, title: pin.title, contextId: existingContainer(pin.containerId), source: .app, select: false,
            load: false, id: tabId)
        repository.attachPinned(pin.id, tabId: tabId)
        openTab(tabId)
    }

    func showSettings(_ screen: SettingsScreen? = nil) {
        sheet = .settings(screen)
    }

    func showLibrary(_ kind: LibraryKind) {
        sheet = .library(kind)
    }

    func showMenu() {
        guard let tabId = store.selectedTabId else { return }
        sheet = .moreMenu(tabId: tabId)
    }

    // MARK: - Search and new tabs

    /// Starts a search: for a new tab, or to edit the address of tab `tabId`.
    func startSearch(editing tabId: String? = nil) {
        let text = tabId.flatMap { store.tab($0) }.map { $0.isWebPage ? $0.url : "" } ?? ""
        search = SearchRequest(text: text, tabId: tabId, isPrivate: newTabChoice?.isPrivate ?? false)
    }

    /// The search screen was closed without searching.
    func cancelSearch() {
        search = nil
        newTabChoice = nil
    }

    /// Loads what was typed in the search screen: an address, or a search with the chosen engine.
    func submitSearch(_ text: String, request: SearchRequest) {
        search = nil
        guard let url = urlOrSearch(text) else { return }
        if let tabId = request.tabId, store.tab(tabId) != nil {
            store.load(url, in: tabId)
            openTab(tabId)
        } else {
            openNewTab(url: url, source: .userEntered)
        }
    }

    /// Opens the next new tab started by the user in the container `pick`, then starts the search for it.
    func newTab(in pick: ContainerPick) {
        newTabChoice = (pick: pick, isPrivate: false, expiresAt: Date().addingTimeInterval(Self.newTabChoiceLifetime))
        startSearch()
    }

    /// Starts the search for a new private tab.
    func newPrivateTab() {
        newTabChoice = (pick: nil, isPrivate: true, expiresAt: Date().addingTimeInterval(Self.newTabChoiceLifetime))
        startSearch()
    }

    /// Opens `url` in a new tab, in the workspace and container it belongs to, like Android's
    /// `WorkspaceContainerMiddleware`: tabs the user starts open in the container picked for them or in their
    /// workspace's container, or a new temporary container when that is the default; links from other apps open where
    /// the settings say.
    ///
    /// - Returns: The id of the new tab.
    @discardableResult
    func openNewTab(url: String, source: TabSource, select: Bool = true) -> String {
        let choice = source.isUserStarted ? consumeNewTabChoice() : nil
        var isPrivate = choice?.isPrivate ?? false
        var pick = choice?.pick
        var workspace = repository.state.activeWorkspace
        if source == .external {
            if settings.externalLinksPrivate {
                isPrivate = true
            } else {
                if let id = settings.externalLinkWorkspaceId, let target = repository.state.workspace(id) {
                    workspace = target
                    repository.selectWorkspace(target.id)
                }
                pick = settings.externalLinkContainer
            }
        }
        var contextId: String?
        if !isPrivate {
            switch pick {
            case .some(.noContainer):
                contextId = nil
            case .some(.temporary):
                contextId = containers.createTemporary()
            case .some(.container(let id)):
                contextId = existingContainer(id)
            case .none:
                if let id = existingContainer(workspace?.containerId) {
                    contextId = id
                } else if settings.temporaryContainersByDefault {
                    contextId = containers.createTemporary()
                }
            }
        }
        let tabId = newId()
        if !isPrivate, let workspace {
            repository.expectTab(tabId)
            repository.assignTab(tabId, to: workspace.id)
        }
        store.addTab(
            url: url, contextId: contextId, isPrivate: isPrivate, source: source, select: false, load: !select,
            id: tabId)
        if select {
            openTab(tabId)
        }
        return tabId
    }

    /// Opens `url`, a link from another app.
    func openExternalLink(_ url: URL) {
        sheet = nil
        search = nil
        openNewTab(url: url.absoluteString, source: .external)
    }

    /// The address for `text`: itself when it looks like one, otherwise a search for it.
    func urlOrSearch(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if Self.hasScheme(trimmed) {
            return trimmed
        }
        if (try? Self.lenientUrl.wholeMatch(in: trimmed)) != nil {
            return "https://" + trimmed
        }
        return settings.searchEngine.searchUrl(for: trimmed)
    }

    private func consumeNewTabChoice() -> (pick: ContainerPick?, isPrivate: Bool)? {
        guard let choice = newTabChoice else { return nil }
        newTabChoice = nil
        return choice.expiresAt > Date() ? (pick: choice.pick, isPrivate: choice.isPrivate) : nil
    }

    /// `contextId` when that container still exists.
    func existingContainer(_ contextId: String?) -> String? {
        guard let contextId, containers.record(contextId) != nil else { return nil }
        return contextId
    }

    private static func hasScheme(_ text: String) -> Bool {
        if text.range(of: "^[a-zA-Z][a-zA-Z0-9+.-]*://", options: .regularExpression) != nil {
            return true
        }
        let lowered = text.lowercased()
        return ["about:", "data:", "view-source:", "javascript:", "blob:", "mailto:", "tel:"].contains {
            lowered.hasPrefix($0)
        }
    }

    // Be lenient about what is classified as potentially a URL, like GeckoTestBrowser was: "w", "w-w", "w-w-w" and
    // so on, around ":", "://" or ".", optionally followed by a path.
    private static let lenientUrl =
        try! Regex("^\\s*(\\w+-+)*[\\w\\[]+(://[/]*|:|\\.)(\\w+-+)*[\\w\\[:]+([\\S&&[^\\w-]]\\S*)?\\s*$")

    // MARK: - Messages

    /// Shows `toast` at the bottom of the screen for a few seconds.
    func show(_ toast: Toast) {
        toastTimer?.invalidate()
        self.toast = toast
        toastTimer = Timer.scheduledTimer(withTimeInterval: toast.long ? 8 : 4, repeats: false) { [weak self] _ in
            self?.toast = nil
        }
    }

    func showMessage(_ message: String) {
        show(Toast(message: message))
    }

    func dismissToast() {
        toastTimer?.invalidate()
        toast = nil
    }

    // MARK: - UIKit

    /// The view controller to present UIKit sheets and alerts from: the top of what is shown.
    var topViewController: UIViewController? {
        var top = presenter
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }

    /// Shows the system share sheet for `urls`.
    func share(_ links: [(url: String, title: String)]) {
        let items: [Any] = links.compactMap { URL(string: $0.url) }
        guard !items.isEmpty, let top = topViewController else { return }
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        if let popover = controller.popoverPresentationController {
            popover.sourceView = top.view
            popover.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.maxY - 80, width: 1, height: 1)
        }
        top.present(controller, animated: true)
    }

    /// Asks before opening `url` in another app, like Fenix's "Open in app?" prompt.
    func confirmOpenExternally(_ url: URL) {
        guard let top = topViewController else { return }
        let alert = UIAlertController(
            title: L10n.iosOpenInAppTitle, message: L10n.iosOpenInAppMessage, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: L10n.cancel, style: .cancel))
        alert.addAction(
            UIAlertAction(title: L10n.iosOpen, style: .default) { _ in
                UIApplication.shared.open(url)
            })
        top.present(alert, animated: true)
    }
}
