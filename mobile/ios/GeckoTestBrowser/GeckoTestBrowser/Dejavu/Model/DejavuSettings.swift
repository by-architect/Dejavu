// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Combine
import Foundation

/// A search engine the address bar searches with.
enum SearchEngine: String, CaseIterable, Identifiable {
    case google
    case duckDuckGo = "duckduckgo"
    case startpage
    case bing
    case ecosia
    case qwant

    var id: String {
        rawValue
    }

    var name: String {
        switch self {
        case .google: return "Google"
        case .duckDuckGo: return "DuckDuckGo"
        case .startpage: return "Startpage"
        case .bing: return "Bing"
        case .ecosia: return "Ecosia"
        case .qwant: return "Qwant"
        }
    }

    /// The address that searches for `terms`.
    func searchUrl(for terms: String) -> String {
        let query = terms.addingPercentEncoding(withAllowedCharacters: Self.queryAllowed) ?? terms
        switch self {
        case .google: return "https://www.google.com/search?q=\(query)"
        case .duckDuckGo: return "https://duckduckgo.com/?q=\(query)"
        case .startpage: return "https://www.startpage.com/do/search?q=\(query)"
        case .bing: return "https://www.bing.com/search?q=\(query)"
        case .ecosia: return "https://www.ecosia.org/search?q=\(query)"
        case .qwant: return "https://www.qwant.com/?q=\(query)"
        }
    }

    private static let queryAllowed: CharacterSet = {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&+=?#")
        return allowed
    }()
}

/// Device local Dejavu preferences: the buttons of tab rows, the custom actions, the "More" menu and where links from
/// other apps open. They are not synced. Used from the main thread only.
final class DejavuSettings: ObservableObject {
    static let shared = DejavuSettings()

    /// The most buttons a tab row can show next to its title.
    static let maxRowActions = 3

    private static let defaultPinnedRowActions: [TabAction] = [.close]
    private static let defaultUnpinnedRowActions: [TabAction] = [.pin, .close]

    private enum Key {
        static let pinnedRowActions = "dejavu.pinned_row_actions"
        static let unpinnedRowActions = "dejavu.unpinned_row_actions"
        static let customActions = "dejavu.custom_actions"
        static let hiddenSelectionActions = "dejavu.hidden_selection_actions"
        static let essentialsPerContainer = "dejavu.essentials_per_container"
        static let temporaryContainersByDefault = "dejavu.temporary_containers_by_default"
        static let externalLinkWorkspace = "dejavu.external_link_workspace"
        static let externalLinkContainer = "dejavu.external_link_container"
        static let externalLinksPrivate = "dejavu.external_links_private"
        static let moreMenuRows = "dejavu.more_menu_rows"
        static let moreMenuEditHidden = "dejavu.more_menu_edit_hidden"
        static let searchEngine = "dejavu.search_engine"
    }

    private let defaults = UserDefaults.standard

    /// Actions the user defined, in creation order.
    @Published private(set) var customActions: [CustomAction] = []

    /// Keys of the `RowAction`s shown on pinned tab rows.
    @Published private(set) var pinnedRowKeys: [String] = []

    /// Keys of the `RowAction`s shown on unpinned tab rows.
    @Published private(set) var unpinnedRowKeys: [String] = []

    /// Whether every container has its own essentials, shown in the workspaces using that container.
    @Published private(set) var essentialsPerContainer = false

    /// Whether new tabs of workspaces without a default container open in new temporary containers.
    @Published private(set) var temporaryContainersByDefault = false

    /// Workspace links from other apps open in, or `nil` for the workspace shown last.
    @Published private(set) var externalLinkWorkspaceId: String?

    /// Container links from other apps open in, or `nil` for the default of their workspace.
    @Published private(set) var externalLinkContainer: ContainerPick?

    /// Whether links from other apps open in a private tab, without a container.
    @Published private(set) var externalLinksPrivate = false

    /// Rows of the browser's "More" menu, as keys of its entries. See `MoreMenuLayout`.
    @Published private(set) var moreMenuRows: [[String]] = MoreMenuLayout.defaultRows

    /// Whether the "More" menu hides its button that opens the menu's settings.
    @Published private(set) var moreMenuEditHidden = false

    /// Keys of the `RowAction`s left out of the selection bar. Every other action, new ones included, is shown.
    @Published private(set) var hiddenSelectionKeys: Set<String> = []

    /// The search engine of the address bar.
    @Published private(set) var searchEngine: SearchEngine = .google

    private init() {
        customActions = readCustomActions()
        pinnedRowKeys = readKeys(Key.pinnedRowActions, default: Self.defaultPinnedRowActions)
        unpinnedRowKeys = readKeys(Key.unpinnedRowActions, default: Self.defaultUnpinnedRowActions)
        essentialsPerContainer = defaults.bool(forKey: Key.essentialsPerContainer)
        temporaryContainersByDefault = defaults.bool(forKey: Key.temporaryContainersByDefault)
        externalLinkWorkspaceId = defaults.string(forKey: Key.externalLinkWorkspace)
        externalLinkContainer = ContainerPick.fromKey(defaults.string(forKey: Key.externalLinkContainer))
        externalLinksPrivate = defaults.bool(forKey: Key.externalLinksPrivate)
        moreMenuRows = MoreMenuLayout.normalized(
            MoreMenuLayout.fromJson(defaults.string(forKey: Key.moreMenuRows)) ?? MoreMenuLayout.defaultRows)
        moreMenuEditHidden = defaults.bool(forKey: Key.moreMenuEditHidden)
        hiddenSelectionKeys = Set(
            (defaults.string(forKey: Key.hiddenSelectionActions) ?? "").split(separator: ",").map { String($0) }
                .filter { !$0.isEmpty })
        searchEngine = SearchEngine(rawValue: defaults.string(forKey: Key.searchEngine) ?? "") ?? .google
    }

    /// Enables or disables a row button. At most `maxRowActions` can be enabled.
    func setRowAction(pinned: Bool, key: String, enabled: Bool) {
        let current = pinned ? pinnedRowKeys : unpinnedRowKeys
        var updated = enabled ? current + [key] : current.filter { $0 != key }
        updated = updated.uniqued { $0 }
        if updated.count > Self.maxRowActions {
            return
        }
        if pinned {
            pinnedRowKeys = updated
            defaults.set(updated.joined(separator: ","), forKey: Key.pinnedRowActions)
        } else {
            unpinnedRowKeys = updated
            defaults.set(updated.joined(separator: ","), forKey: Key.unpinnedRowActions)
        }
    }

    func setTemporaryContainersByDefault(_ enabled: Bool) {
        temporaryContainersByDefault = enabled
        defaults.set(enabled, forKey: Key.temporaryContainersByDefault)
    }

    func setExternalLinkWorkspace(_ workspaceId: String?) {
        externalLinkWorkspaceId = workspaceId
        defaults.set(workspaceId, forKey: Key.externalLinkWorkspace)
    }

    func setExternalLinkContainer(_ pick: ContainerPick?) {
        externalLinkContainer = pick
        defaults.set(pick?.key, forKey: Key.externalLinkContainer)
    }

    func setExternalLinksPrivate(_ enabled: Bool) {
        externalLinksPrivate = enabled
        defaults.set(enabled, forKey: Key.externalLinksPrivate)
    }

    func setEssentialsPerContainer(_ enabled: Bool) {
        essentialsPerContainer = enabled
        defaults.set(enabled, forKey: Key.essentialsPerContainer)
    }

    func setMoreMenuRows(_ rows: [[String]]) {
        moreMenuRows = MoreMenuLayout.normalized(rows)
        defaults.set(MoreMenuLayout.toJson(moreMenuRows), forKey: Key.moreMenuRows)
    }

    func setMoreMenuEditHidden(_ hidden: Bool) {
        moreMenuEditHidden = hidden
        defaults.set(hidden, forKey: Key.moreMenuEditHidden)
    }

    func resetMoreMenu() {
        moreMenuRows = MoreMenuLayout.defaultRows
        defaults.removeObject(forKey: Key.moreMenuRows)
    }

    /// Shows or hides an action of the selection bar.
    func setSelectionAction(_ key: String, enabled: Bool) {
        if enabled {
            hiddenSelectionKeys.remove(key)
        } else {
            hiddenSelectionKeys.insert(key)
        }
        defaults.set(hiddenSelectionKeys.sorted().joined(separator: ","), forKey: Key.hiddenSelectionActions)
    }

    func setSearchEngine(_ engine: SearchEngine) {
        searchEngine = engine
        defaults.set(engine.rawValue, forKey: Key.searchEngine)
    }

    /// Adds `action`, or replaces the custom action with the same id.
    func saveCustomAction(_ action: CustomAction) {
        if let index = customActions.firstIndex(where: { $0.id == action.id }) {
            customActions[index] = action
        } else {
            customActions.append(action)
        }
        writeCustomActions()
    }

    /// Deletes a custom action and forgets where it was shown.
    func deleteCustomAction(_ id: String) {
        customActions.removeAll { $0.id == id }
        writeCustomActions()
        let key = RowAction.keyOf(id)
        setRowAction(pinned: true, key: key, enabled: false)
        setRowAction(pinned: false, key: key, enabled: false)
        setSelectionAction(key, enabled: true)
        setMoreMenuRows(moreMenuRows.map { row in row.filter { $0 != key } })
    }

    /// The row buttons chosen with `keys`, in display order.
    func rowActions(pinned: Bool) -> [RowAction] {
        let keys = pinned ? pinnedRowKeys : unpinnedRowKeys
        return Array(
            RowAction.available(pinned: pinned, customActions: customActions).filter { keys.contains($0.key) }
                .prefix(Self.maxRowActions))
    }

    /// The actions of the selection bar in display order, without the ones the user hid.
    var selectionActions: [RowAction] {
        RowAction.selectionBar(customActions: customActions).filter { !hiddenSelectionKeys.contains($0.key) }
    }

    private func readKeys(_ key: String, default fallback: [TabAction]) -> [String] {
        guard let saved = defaults.string(forKey: key) else {
            return fallback.map { $0.key }
        }
        return saved.split(separator: ",").map { String($0) }.filter { !$0.isEmpty }
    }

    private func readCustomActions() -> [CustomAction] {
        guard let data = defaults.string(forKey: Key.customActions)?.data(using: .utf8),
            let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
        else {
            return []
        }
        return array.compactMap { ($0 as? [String: Any]).flatMap(CustomAction.fromJson) }
    }

    private func writeCustomActions() {
        let array = customActions.map { $0.toJson() }
        guard let data = try? JSONSerialization.data(withJSONObject: array),
            let text = String(data: data, encoding: .utf8)
        else {
            return
        }
        defaults.set(text, forKey: Key.customActions)
    }
}
