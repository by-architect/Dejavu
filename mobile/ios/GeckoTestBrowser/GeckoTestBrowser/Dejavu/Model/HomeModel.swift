// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// Tabs, pinned tabs (essentials included) and folders picked in selection mode.
struct Selection: Equatable {
    var tabIds: Set<String> = []
    var pinIds: Set<String> = []
    var folderIds: Set<String> = []

    var size: Int {
        tabIds.count + pinIds.count + folderIds.count
    }

    var isEmpty: Bool {
        size == 0
    }

    func togglingTab(_ id: String) -> Selection {
        var next = self
        next.tabIds = Self.toggle(tabIds, id)
        return next
    }

    func togglingPin(_ id: String) -> Selection {
        var next = self
        next.pinIds = Self.toggle(pinIds, id)
        return next
    }

    func togglingFolder(_ id: String) -> Selection {
        var next = self
        next.folderIds = Self.toggle(folderIds, id)
        return next
    }

    static func + (left: Selection, right: Selection) -> Selection {
        Selection(
            tabIds: left.tabIds.union(right.tabIds),
            pinIds: left.pinIds.union(right.pinIds),
            folderIds: left.folderIds.union(right.folderIds))
    }

    private static func toggle(_ ids: Set<String>, _ id: String) -> Set<String> {
        var result = ids
        if result.contains(id) {
            result.remove(id)
        } else {
            result.insert(id)
        }
        return result
    }
}

/// What an action applies to.
///
/// - `tabs`: Open tabs that are not pinned.
/// - `pins`: Pinned tabs and essentials, open or closed, that are not inside one of `folders`.
/// - `pinnedTabs`: Open tabs backing `pins`.
/// - `folders`: Folders that are not inside another one of `folders`.
/// - `folderPins`: Pinned tabs anywhere inside `folders`.
/// - `folderTabs`: Open tabs backing `folderPins`.
/// - `canAddSubfolder`: Whether `folders` is a single folder that can hold one more level of folders.
/// - `splitTabIds`: Open tabs among the targets that are part of a split view.
struct ActionTargets: Equatable {
    var tabs: [TabInfo] = []
    var pins: [PinnedItem] = []
    var pinnedTabs: [TabInfo] = []
    var folders: [PinnedItem] = []
    var folderPins: [PinnedItem] = []
    var folderTabs: [TabInfo] = []
    var canAddSubfolder = false
    var splitTabIds: Set<String> = []

    /// Every pinned tab the action reaches, the ones inside `folders` included.
    var allPins: [PinnedItem] {
        pins + folderPins
    }

    var openTabs: [TabInfo] {
        tabs + pinnedTabs + folderTabs
    }

    var awakeTabs: [TabInfo] {
        openTabs.filter { $0.isAwake }
    }

    /// Pinned items that move as a whole: `pins` and `folders` with everything inside them.
    var itemIds: Set<String> {
        Set((pins + folders).map { $0.id })
    }

    /// Whether the targets are exactly one folder.
    var isSingleFolder: Bool {
        folders.count == 1 && tabs.isEmpty && pins.isEmpty
    }

    var isEmpty: Bool {
        tabs.isEmpty && pins.isEmpty && folders.isEmpty
    }

    private var openPinnedTabs: [String: TabInfo] {
        Dictionary((pinnedTabs + folderTabs).map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    }

    /// URL and title of every target, preferring the live page of open pinned tabs.
    var links: [(url: String, title: String)] {
        let open = openPinnedTabs
        return tabs.map { (url: $0.url, title: $0.title) }
            + allPins.map { pin in
                if let tabId = pin.tabId, let tab = open[tabId] {
                    return (url: tab.url, title: tab.title)
                }
                return (url: pin.url ?? "", title: pin.title)
            }
    }

    /// Containers the targets are in; `nil` stands for no container.
    var containerIds: Set<String?> {
        let open = openPinnedTabs
        var result = Set<String?>(tabs.map { $0.contextId })
        for pin in allPins {
            if let tabId = pin.tabId, let tab = open[tabId] {
                result.insert(tab.contextId)
            } else {
                result.insert(pin.containerId)
            }
        }
        return result
    }

    /// Pinned tabs whose open tab has left the pinned page, with that tab.
    var changedPins: [(pin: PinnedItem, tab: TabInfo)] {
        let open = openPinnedTabs
        return allPins.compactMap { pin in
            guard let tabId = pin.tabId, let tab = open[tabId], let url = pin.url else { return nil }
            return Self.comparable(tab.url) != Self.comparable(url) ? (pin: pin, tab: tab) : nil
        }
    }

    static func of(_ state: WorkspaceState, tabs: [TabInfo], selection: Selection) -> ActionTargets {
        let selectedFolders = state.pins.filter { $0.isFolder && selection.folderIds.contains($0.id) }
        var nested = Set<String>()
        for folder in selectedFolders {
            nested.formUnion(state.descendantIds(folder.id))
        }
        let folders = selectedFolders.filter { !nested.contains($0.id) }
        let pins = state.pins.filter { !$0.isFolder && selection.pinIds.contains($0.id) && !nested.contains($0.id) }
        let folderPins = state.pins.filter { !$0.isFolder && nested.contains($0.id) }
        var targets = ActionTargets(
            tabs: tabs.filter { selection.tabIds.contains($0.id) },
            pins: pins,
            pinnedTabs: backing(tabs, pins),
            folders: folders,
            folderPins: folderPins,
            folderTabs: backing(tabs, folderPins),
            canAddSubfolder: folders.count == 1 && state.folderDepth(folders[0].id) < maxFolderDepth)
        targets.splitTabIds = Set(targets.openTabs.map { $0.id }.filter { state.splitOf($0) != nil })
        return targets
    }

    /// A pinned tab or essential with its open tab, if any.
    static func ofPin(_ pin: PinnedItem, tab: TabInfo?) -> ActionTargets {
        ActionTargets(pins: [pin], pinnedTabs: tab.map { [$0] } ?? [])
    }

    private static func backing(_ tabs: [TabInfo], _ pins: [PinnedItem]) -> [TabInfo] {
        let tabIds = Set(pins.compactMap { $0.tabId })
        return tabs.filter { tabIds.contains($0.id) }
    }

    private static func comparable(_ url: String) -> String {
        var text = url.components(separatedBy: "#")[0]
        while text.hasSuffix("/") {
            text.removeLast()
        }
        return text
    }
}

extension WorkspaceState {
    /// The title shown for open tab `tab`: the one the user gave it, or its page's.
    func titleOf(_ tab: TabInfo) -> String {
        if let title = tabTitles[tab.id] {
            return title
        }
        if let pin = pinOf(tab.id), pin.staticLabel {
            return pin.title
        }
        return tab.displayTitle
    }

    /// Folder that a new folder grouping `targets` goes in: their common parent, while it can hold one more level.
    func newFolderParent(_ targets: ActionTargets) -> String? {
        let parents = Array(Set((targets.pins + targets.folders).map { $0.parentId }))
        guard parents.count == 1, let parent = parents[0] else { return nil }
        return folderDepth(parent) < maxFolderDepth ? parent : nil
    }

    /// Names of the folders around `item`, outermost first, separated by "/".
    func folderPathOf(_ item: PinnedItem) -> String {
        var names: [String] = []
        var seen = Set<String>()
        var parentId = item.parentId
        while let id = parentId, seen.insert(id).inserted {
            guard let parent = pins.first(where: { $0.id == id }) else { break }
            names.insert(parent.title, at: 0)
            parentId = parent.parentId
        }
        return names.joined(separator: "/")
    }

    /// Flattens the pinned tree of `workspaceId` in display order. Children of collapsed folders are left out unless
    /// `expandAll` is set. Items whose parent folder is missing are shown at the top level.
    func pinnedTree(_ workspaceId: String, expandAll: Bool = false) -> [PinnedEntry] {
        let items = pins.filter { $0.workspaceId == workspaceId }
        let folderIds = Set(items.filter { $0.isFolder }.map { $0.id })
        var byParent: [String: [PinnedItem]] = [:]
        var topLevel: [PinnedItem] = []
        for item in items {
            if let parentId = item.parentId, folderIds.contains(parentId) {
                byParent[parentId, default: []].append(item)
            } else {
                topLevel.append(item)
            }
        }
        var result: [PinnedEntry] = []
        var visited = Set<String>()
        func visit(_ children: [PinnedItem], depth: Int) {
            for item in children where visited.insert(item.id).inserted {
                result.append(PinnedEntry(item: item, depth: depth))
                if item.isFolder && (expandAll || !item.collapsed) {
                    visit(byParent[item.id] ?? [], depth: depth + 1)
                }
            }
        }
        visit(topLevel, depth: 0)
        return result
    }
}

extension PinnedItem {
    /// The title shown for this pinned tab, open in `tab` or closed.
    func label(_ tab: TabInfo?) -> String {
        if staticLabel {
            return title
        }
        if let tab {
            return tab.displayTitle
        }
        return pageLabel(title: title, url: url ?? "")
    }
}

/// A pinned item and how deep it is nested in folders.
struct PinnedEntry: Equatable {
    let item: PinnedItem
    let depth: Int
}

extension TabAction {
    /// Whether this action can do anything for `targets`.
    func applies(to targets: ActionTargets) -> Bool {
        switch self {
        case .close: return !targets.openTabs.isEmpty
        case .pin: return !targets.tabs.isEmpty
        case .unpin: return targets.pins.contains { !$0.essential }
        case .sleep: return !targets.awakeTabs.isEmpty
        case .bookmark, .share, .copyLink: return targets.links.contains { !$0.url.isEmpty }
        case .duplicate: return targets.folders.isEmpty && (!targets.tabs.isEmpty || !targets.pins.isEmpty)
        case .resetPin: return !targets.changedPins.isEmpty
        case .addToEssentials:
            return targets.folders.isEmpty && (!targets.tabs.isEmpty || targets.pins.contains { !$0.essential })
        case .removeFromEssentials: return targets.pins.contains { $0.essential }
        case .newFolder: return !targets.isEmpty && !targets.isSingleFolder
        case .newSubfolder: return targets.isSingleFolder && targets.canAddSubfolder
        case .renameFolder: return targets.isSingleFolder
        case .unpackFolder: return !targets.folders.isEmpty && targets.tabs.isEmpty && targets.pins.isEmpty
        case .delete: return !targets.pins.isEmpty || !targets.folders.isEmpty
        case .moveToFolder, .moveToWorkspace: return !targets.isEmpty
        case .changeContainer: return !targets.tabs.isEmpty || !targets.allPins.isEmpty
        case .renameTab: return targets.folders.isEmpty && targets.tabs.count + targets.pins.count == 1
        case .splitView: return targets.folders.isEmpty && (targets.tabs + targets.pinnedTabs).count == 2
        case .unsplit: return !targets.splitTabIds.isEmpty
        }
    }
}

extension RowAction {
    /// Whether this action can do anything for `targets`.
    func applies(to targets: ActionTargets) -> Bool {
        switch self {
        case .builtIn(let action): return action.applies(to: targets)
        case .custom: return targets.links.contains { !$0.url.isEmpty }
        }
    }
}

/// The buttons of one tab row: the user's choice, where Close becomes Unpin on a pinned tab that is closed, without
/// the actions that do nothing for this tab.
func rowActions(_ configured: [RowAction], targets: ActionTargets, isPinned: Bool) -> [RowAction] {
    configured
        .map { action -> RowAction in
            let closesClosedPin = action == .builtIn(.close) && isPinned && targets.openTabs.isEmpty
            return closesClosedPin ? .builtIn(.unpin) : action
        }
        .uniqued { $0.key }
        .filter { $0.applies(to: targets) }
}

/// Where dragged tabs, pinned items and folders are dropped.
enum DropTarget: Equatable {
    /// Into a folder of the pinned section.
    case intoFolder(String)

    /// Next to a pinned tab or folder, as its sibling.
    case nextToPin(String, after: Bool)

    /// At the start or the end of the pinned section's top level.
    case pinnedEdge(atEnd: Bool)

    /// Next to an unpinned tab.
    case nextToTab(String, after: Bool)

    /// At the start of the unpinned tabs, right below New Tab.
    case unpinnedStart

    /// Into the essentials shared by every workspace.
    case essentials
}
