// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// A workspace groups tabs. Every normal tab belongs to exactly one workspace.
///
/// - `id`: Stable identifier, shared with other devices when syncing.
/// - `containerId`: Container new tabs of this workspace open in, or `nil`.
/// - `icon`: Emoji shown with the name, or the file name of one of Zen's built-in icons as Zen stores it.
/// - `theme`: Background of the workspace, or `nil` for the plain background.
/// - `updatedAt`: Last change time in milliseconds, used to resolve sync conflicts.
struct Workspace: Equatable, Identifiable {
    var id: String
    var name: String
    var containerId: String? = nil
    var icon: String? = nil
    var theme: WorkspaceTheme? = nil
    var createdAt: Int64
    var updatedAt: Int64
}

/// A workspace background, like Zen's space themes: a gradient of one to three colors.
///
/// - `colors`: ARGB colors of the gradient, as Android keeps them.
/// - `opacity`: How strongly the gradient covers the background, from 0 to 1.
/// - `texture`: Strength of the grain drawn over the gradient, from 0 to 1.
struct WorkspaceTheme: Equatable {
    var colors: [Int]
    var opacity: Double = WorkspaceTheme.defaultOpacity
    var texture: Double = 0

    static let defaultOpacity = 0.35
}

/// Deepest folder nesting allowed, matching Zen's default `zen.folders.max-subfolders`.
let maxFolderDepth = 5

/// Most essentials allowed, matching Zen's default `zen.tabs.essentials.max`.
let maxEssentials = 12

/// Kind of a `PinnedItem`.
enum PinKind: String {
    case tab
    case folder
}

/// An entry of a workspace's pinned section: a pinned tab or a folder. Items are stored as a flat list where each item
/// points to its workspace and parent folder, so folders can be nested and the list maps one to one to sync records.
/// The order of the list is the display order among siblings.
///
/// - `workspaceId`: Workspace the item belongs to, or `nil` for essentials, which every workspace shows.
/// - `parentId`: Folder containing the item, or `nil` at the top of the pinned section.
/// - `title`: Folder name, or the pinned tab's title.
/// - `url`: URL a pinned tab reopens; `nil` for folders.
/// - `containerId`: Container a pinned tab reopens in.
/// - `collapsed`: Whether a folder is collapsed. Local to this device, like in Zen.
/// - `icon`: Folder icon name, as Zen stores it. Not shown yet.
/// - `essential`: Whether this pinned tab is one of the essentials shared by all workspaces, like in Zen.
/// - `staticLabel`: Whether `title` was given by the user and is shown instead of the page title.
/// - `tabId`: Open browser tab currently backing a pinned tab. Local to this device and never synced.
struct PinnedItem: Equatable, Identifiable {
    var id: String
    var workspaceId: String?
    var parentId: String?
    var kind: PinKind
    var title: String
    var url: String? = nil
    var containerId: String? = nil
    var collapsed: Bool = false
    var icon: String? = nil
    var essential: Bool = false
    var staticLabel: Bool = false
    var tabId: String? = nil
    var createdAt: Int64
    var updatedAt: Int64

    var isFolder: Bool {
        kind == .folder
    }
}

/// Two tabs shown together in the browser, like Zen's split views. Local to this device, like tab assignments.
struct SplitView: Equatable {
    var id: String
    var tabIds: [String]
}

/// Everything Dejavu keeps about workspaces.
///
/// - `workspaces`: Workspaces in display order. Never empty.
/// - `pins`: Pinned tabs and folders of all workspaces.
/// - `activeWorkspaceId`: The workspace shown on the home screen. New tabs are assigned to it.
/// - `assignments`: Tab id to workspace id. Local to this device.
/// - `splits`: Tabs shown together in the browser. Local to this device.
/// - `tabTitles`: Titles the user gave to tabs that are not pinned, by tab id. Local to this device.
/// - `tabSyncIds`: Sync ids of open tabs that sync under another id than their own: the id of the pin they were the
///   tab of, once it was unpinned, so the tab stays the same tab on other devices. Local to this device.
struct WorkspaceState: Equatable {
    var workspaces: [Workspace]
    var pins: [PinnedItem]
    var activeWorkspaceId: String
    var assignments: [String: String]
    var splits: [SplitView] = []
    var tabTitles: [String: String] = [:]
    var tabSyncIds: [String: String] = [:]

    var activeIndex: Int {
        workspaces.firstIndex { $0.id == activeWorkspaceId } ?? 0
    }

    var activeWorkspace: Workspace? {
        workspaces.first { $0.id == activeWorkspaceId }
    }

    /// Essentials in display order.
    var essentials: [PinnedItem] {
        pins.filter { $0.essential }
    }

    /// The essentials shown in a workspace with default container `containerId`: all of them, or with `perContainer`
    /// only those of that container, like Zen's container-specific essentials.
    func essentialsShown(containerId: String?, perContainer: Bool) -> [PinnedItem] {
        perContainer ? essentials.filter { $0.containerId == containerId } : essentials
    }

    func workspace(_ id: String?) -> Workspace? {
        guard let id else { return nil }
        return workspaces.first { $0.id == id }
    }

    /// Returns the workspace id of `tabId`, falling back to the active workspace for unassigned tabs.
    func workspaceOf(_ tabId: String) -> String {
        assignments[tabId] ?? activeWorkspaceId
    }

    /// Returns the id the open tab `tabId` syncs under when it is not pinned.
    func syncIdOf(_ tabId: String) -> String {
        tabSyncIds[tabId] ?? tabId
    }

    /// Returns the pin backed by the open tab `tabId`, if any.
    func pinOf(_ tabId: String) -> PinnedItem? {
        pins.first { $0.tabId == tabId }
    }

    /// Returns the split view tab `tabId` is part of, if any.
    func splitOf(_ tabId: String) -> SplitView? {
        splits.first { $0.tabIds.contains(tabId) }
    }

    /// Returns the direct children of `parentId` in `workspaceId`, in display order.
    func children(_ workspaceId: String, _ parentId: String?) -> [PinnedItem] {
        pins.filter { $0.workspaceId == workspaceId && $0.parentId == parentId }
    }

    /// Returns how many folders `folderId` is nested in, counting itself: 1 for a top level folder.
    func folderDepth(_ folderId: String?) -> Int {
        var depth = 0
        var current = folderId.flatMap { id in pins.first { $0.id == id && $0.isFolder } }
        var seen = Set<String>()
        while let folder = current, seen.insert(folder.id).inserted {
            depth += 1
            current = folder.parentId.flatMap { id in pins.first { $0.id == id && $0.isFolder } }
        }
        return depth
    }

    /// Returns how many folder levels `folderId` spans, counting itself: 1 for a folder without subfolders.
    func folderHeight(_ folderId: String, seen: Set<String> = []) -> Int {
        if seen.contains(folderId) {
            return 0
        }
        let subfolders = pins.filter { $0.parentId == folderId && $0.isFolder }
        let deepest = subfolders.map { folderHeight($0.id, seen: seen.union([folderId])) }.max() ?? 0
        return 1 + deepest
    }

    /// Returns the ids of every item nested below `folderId`.
    func descendantIds(_ folderId: String) -> Set<String> {
        var result = Set<String>()
        var level: Set<String> = [folderId]
        while !level.isEmpty {
            let parents = level
            level = Set(
                pins.filter { pin in
                    guard let parentId = pin.parentId else { return false }
                    return parents.contains(parentId) && !result.contains(pin.id)
                }.map { $0.id })
            result.formUnion(level)
        }
        return result
    }
}

/// Reads and writes `WorkspaceState` as JSON, in the same form as the Android app. Version 1 (workspaces with nested
/// pinned tabs, no folders) is migrated to version 2 (flat pinned items with parent folders) on read.
enum WorkspaceSerializer {
    private static let version: Int64 = 2
    private static let maxThemeColors = 3
    private static let splitSize = 2

    static func read(_ root: [String: Any]?, defaultName: String, now: Int64) -> WorkspaceState {
        let workspaceObjects = root?.objects("workspaces") ?? []
        var workspaces: [Workspace] = workspaceObjects.compactMap { item in
            guard let id = item.string("id"), let name = item.string("name") else { return nil }
            return Workspace(
                id: id,
                name: name,
                containerId: item.nonEmptyString("containerId"),
                icon: item.nonEmptyString("icon"),
                theme: item.object("theme").flatMap(theme(from:)),
                createdAt: item.int64("createdAt") ?? now,
                updatedAt: item.int64("updatedAt") ?? now)
        }
        if workspaces.isEmpty {
            workspaces = [Workspace(id: newWorkspaceId(), name: defaultName, createdAt: now, updatedAt: now)]
        }
        let workspaceIds = Set(workspaces.map { $0.id })

        let pins: [PinnedItem]
        if root?.int64("version") == version {
            pins = (root?.objects("pins") ?? []).compactMap { pin(from: $0, now: now) }
        } else {
            pins = workspaceObjects.flatMap { workspace -> [PinnedItem] in
                guard let workspaceId = workspace.string("id") else { return [] }
                return workspace.objects("pinned").compactMap { v1Pin(from: $0, workspaceId: workspaceId, now: now) }
            }
        }
        let keptPins = pins.filter { pin in
            pin.essential || (pin.workspaceId.map { workspaceIds.contains($0) } ?? false)
        }

        let assignments = (root?.stringMap("assignments") ?? [:]).filter { workspaceIds.contains($0.value) }
        let activeId = root?.nonEmptyString("active").flatMap { workspaceIds.contains($0) ? $0 : nil }
            ?? workspaces[0].id

        let splits: [SplitView] = (root?.objects("splits") ?? []).compactMap { item in
            guard let id = item.string("id"), let tabIds = item.strings("tabIds"), tabIds.count == splitSize else {
                return nil
            }
            return SplitView(id: id, tabIds: tabIds)
        }

        return WorkspaceState(
            workspaces: workspaces,
            pins: keptPins,
            activeWorkspaceId: activeId,
            assignments: assignments,
            splits: splits,
            tabTitles: root?.stringMap("tabTitles") ?? [:],
            tabSyncIds: root?.stringMap("tabSyncIds") ?? [:])
    }

    static func write(_ state: WorkspaceState) -> [String: Any] {
        let workspaces: [[String: Any]] = state.workspaces.map { workspace in
            var item: [String: Any] = [
                "id": workspace.id,
                "name": workspace.name,
                "createdAt": workspace.createdAt,
                "updatedAt": workspace.updatedAt,
            ]
            item["containerId"] = workspace.containerId
            item["icon"] = workspace.icon
            item["theme"] = workspace.theme.map(json(of:))
            return item
        }
        let pins: [[String: Any]] = state.pins.map { pin in
            var item: [String: Any] = [
                "id": pin.id,
                "kind": pin.kind.rawValue,
                "title": pin.title,
                "collapsed": pin.collapsed,
                "essential": pin.essential,
                "staticLabel": pin.staticLabel,
                "createdAt": pin.createdAt,
                "updatedAt": pin.updatedAt,
            ]
            item["workspaceId"] = pin.workspaceId
            item["parentId"] = pin.parentId
            item["url"] = pin.url
            item["containerId"] = pin.containerId
            item["icon"] = pin.icon
            item["tabId"] = pin.tabId
            return item
        }
        return [
            "version": version,
            "active": state.activeWorkspaceId,
            "workspaces": workspaces,
            "pins": pins,
            "assignments": state.assignments,
            "tabTitles": state.tabTitles,
            "tabSyncIds": state.tabSyncIds,
            "splits": state.splits.map { ["id": $0.id, "tabIds": $0.tabIds] as [String: Any] },
        ]
    }

    /// Zen identifies spaces by braced UUIDs; Dejavu uses the same form so ids can be shared when syncing.
    static func newWorkspaceId() -> String {
        "{\(newId())}"
    }

    private static func pin(from item: [String: Any], now: Int64) -> PinnedItem? {
        guard let id = item.string("id") else { return nil }
        return PinnedItem(
            id: id,
            workspaceId: item.nonEmptyString("workspaceId"),
            parentId: item.nonEmptyString("parentId"),
            kind: PinKind(rawValue: item.string("kind") ?? "") ?? .tab,
            title: item.string("title") ?? "",
            url: item.nonEmptyString("url"),
            containerId: item.nonEmptyString("containerId"),
            collapsed: item.bool("collapsed") ?? false,
            icon: item.nonEmptyString("icon"),
            essential: item.bool("essential") ?? false,
            staticLabel: item.bool("staticLabel") ?? false,
            tabId: item.nonEmptyString("tabId"),
            createdAt: item.int64("createdAt") ?? now,
            updatedAt: item.int64("updatedAt") ?? now)
    }

    private static func v1Pin(from item: [String: Any], workspaceId: String, now: Int64) -> PinnedItem? {
        guard let id = item.string("id") else { return nil }
        return PinnedItem(
            id: id,
            workspaceId: workspaceId,
            parentId: nil,
            kind: .tab,
            title: item.string("title") ?? "",
            url: item.nonEmptyString("url"),
            tabId: item.nonEmptyString("tabId"),
            createdAt: now,
            updatedAt: now)
    }

    private static func json(of theme: WorkspaceTheme) -> [String: Any] {
        ["colors": theme.colors, "opacity": theme.opacity, "texture": theme.texture]
    }

    private static func theme(from item: [String: Any]) -> WorkspaceTheme? {
        guard let values = item["colors"] as? [Any] else { return nil }
        let colors = values.compactMap { ($0 as? NSNumber)?.intValue }.prefix(maxThemeColors)
        if colors.isEmpty {
            return nil
        }
        return WorkspaceTheme(
            colors: Array(colors),
            opacity: item.double("opacity") ?? WorkspaceTheme.defaultOpacity,
            texture: item.double("texture") ?? 0)
    }
}
