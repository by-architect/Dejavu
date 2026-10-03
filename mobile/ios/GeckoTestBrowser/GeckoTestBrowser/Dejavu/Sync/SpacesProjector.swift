// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// Dejavu's synced data: workspaces with their pinned tabs, folders and essentials, the containers, and the open tabs.
///
/// - `normalTabs`: Whether tabs that are not pinned are synced too, like Zen's "Include unpinned tabs".
/// - `tabs`: The open tabs that are not private, or `nil` while the browser has not restored them yet.
struct LocalSpaces {
    var state: WorkspaceState
    var containers: [ContainerRecord]
    var normalTabs = false
    var tabs: [LocalTab]? = nil
}

/// An open tab as spaces sync sees it.
///
/// - `awake`: Whether its page is loaded. One that is not can be pointed at another address without losing anything.
struct LocalTab {
    let id: String
    let url: String
    let title: String
    let contextId: String?
    let awake: Bool

    /// What syncing the tab sends of it, to tell whether it changed here since the last sync.
    var fingerprint: String {
        "\(url)\n\(title)"
    }
}

/// Whether a tab with `url` is synced; Zen and Dejavu cannot share other pages.
func isSyncableUrl(_ url: String?) -> Bool {
    guard let url else { return false }
    return url.hasPrefix("https://") || url.hasPrefix("http://")
}

/// The records Dejavu's data maps to.
///
/// - `records`: Records by id.
/// - `held`: Ids of local items that are left out on purpose, like a split view whose tabs have not arrived yet. Their
///   server records are neither changed nor deleted.
/// - `closedTabs`: Ids of tabs that are not pinned and were closed here since the last sync.
struct Projection {
    let records: OrderedRecords
    let held: Set<String>
    var closedTabs: Set<String> = []

    /// What to upload so that `server` matches this projection: changed records, and tombstones for deleted ones.
    func changes(against server: OrderedRecords) -> [SpacesRecord] {
        let changed = records.values.filter { !$0.sameAs(server[$0.id]) }
        let deleted = server.values
            .filter { ($0.isSynced && records[$0.id] == nil && !held.contains($0.id)) || closedTabs.contains($0.id) }
            .map { SpacesRecord.tombstone($0.id) }
        return changed + deleted
    }
}

/// Maps Dejavu's data to the records of Zen's spaces collection, like Zen's ZenSpacesSyncModel does for Zen's sidebar.
///
/// Every record starts from the server's copy in `server` and only the fields that differ from what Dejavu shows for
/// that copy are rewritten. Fields Dejavu does not have, like tab icons or live folder settings, are kept, so data that
/// Dejavu received and did not change always maps back to exactly the same record and is never uploaded again.
///
/// `seen` holds the tabs that are not pinned, by sync id, as they were here at the end of the last sync
/// (`LocalTab.fingerprint`).
final class SpacesProjector {
    /// Key of the essentials without a container in the layout record.
    static let defaultEssentials = "default"
    static let blankUrl = "about:blank"

    private let local: LocalSpaces
    private let server: OrderedRecords
    private let seen: [String: String]
    private let state: WorkspaceState
    private let workspaceIds: Set<String>
    private let containerIds: Set<String>
    private let pinsById: [String: PinnedItem]
    private var records = OrderedRecords()
    private var held = Set<String>()
    private var splitMembers: [(id: String, members: [String])] = []
    private var projectedPins = Set<String>()
    private var normalTabsBySpace: [String: [String]] = [:]
    private var closedTabs = Set<String>()

    /// Tabs of Zen's split views that are not pinned; Dejavu keeps those splits as they are, in place of their tabs.
    private let normalSplitMembers: Set<String>

    init(local: LocalSpaces, server: OrderedRecords, seen: [String: String] = [:]) {
        self.local = local
        self.server = server
        self.seen = seen
        state = local.state
        workspaceIds = Set(local.state.workspaces.map { $0.id })
        containerIds = Set(local.containers.filter { !$0.temporary }.map { $0.contextId })
        pinsById = Dictionary(local.state.pins.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        normalSplitMembers = Set(
            server.values
                .filter { !$0.deleted && $0.kind == RecordKind.split && isJSONFalse($0.data?["pinned"]) }
                .flatMap { $0.data?.strings("tabs") ?? [] })
    }

    func project() -> Projection {
        projectContainers()
        findProjectedPins()
        projectTabs()
        projectNormalTabs()
        projectSplits()
        projectFolders()
        projectSpaces()
        projectLayout()
        return Projection(records: records, held: held, closedTabs: closedTabs)
    }

    private func projectContainers() {
        for container in local.containers where !container.temporary {
            let id = container.contextId
            let previous = self.previous(id, RecordKind.container)
            // Zen leaves out its default containers until they are renamed; Dejavu's copies of them follow that.
            if previous == nil && BuiltinContainer.of(id) != nil && container.updatedAt == container.createdAt {
                held.insert(id)
                continue
            }
            var data = previous ?? [:]
            data["guid"] = id
            data["name"] = container.name
            if previous == nil || containerIconOf(previous?["icon"]) != container.icon {
                data["icon"] = container.icon.rawValue
            }
            if previous == nil || containerColorOf(previous?["color"]) != syncedColor(container.color) {
                data["color"] = syncedColor(container.color).rawValue
            }
            records[id] = .of(id, kind: RecordKind.container, data: data)
        }
    }

    private func findProjectedPins() {
        for pin in state.pins {
            let inWorkspace = pin.workspaceId.map { workspaceIds.contains($0) } ?? false
            let projected: Bool
            if pin.isFolder {
                projected = inWorkspace
            } else {
                let url = pin.url ?? ""
                projected = !url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && url != Self.blankUrl
                    && (pin.essential || inWorkspace)
            }
            if projected {
                projectedPins.insert(pin.id)
            } else {
                held.insert(pin.id)
            }
        }
    }

    private func projectTabs() {
        for pin in state.pins where !pin.isFolder && projectedPins.contains(pin.id) {
            let previous = self.previous(pin.id, RecordKind.tab).flatMap { isJSONFalse($0["pinned"]) ? nil : $0 }
            var data = previous ?? [:]
            data["tabId"] = pin.id
            data["url"] = pin.url ?? ""
            if previous.map({ labelOf($0) != (pin.title, pin.staticLabel) }) ?? true {
                putLabel(&data, pin, previous)
            }
            if previous == nil {
                data["icon"] = ""
                data["hasStaticIcon"] = false
                data["defaultContainer"] = false
            }
            data.putNullable("containerGuid", guidOf(pin.containerId))
            data["essential"] = pin.essential
            data["pinned"] = true
            data.putNullable("workspaceUuid", pin.essential ? nil : pin.workspaceId)
            data.putNullable("folderId", pin.essential ? nil : parentOf(pin))
            records[pin.id] = .of(pin.id, kind: RecordKind.tab, data: data)
        }
    }

    /// Open tabs that are not pinned, like Zen's "Include unpinned tabs": each is a tab record that is not pinned, in its
    /// workspace's children after the pinned items. A tab's address and title go out only when they changed here since
    /// the last sync, so a page loaded on two devices is not sent back and forth. Without them, and before the browser
    /// restored its tabs, the server's unpinned tabs are held. Those that never were open here are held too; the engine
    /// opens them.
    private func projectNormalTabs() {
        guard local.normalTabs, let tabs = local.tabs else {
            for record in server.values where record.isNormalTab {
                held.insert(record.id)
            }
            return
        }
        let pinnedTabs = Set(state.pins.compactMap { $0.tabId })
        for tab in tabs where !pinnedTabs.contains(tab.id) {
            let syncId = state.syncIdOf(tab.id)
            let workspaceId = state.workspaceOf(tab.id)
            let inSyncedContainer = tab.contextId.map { containerIds.contains($0) } ?? true
            if !isSyncableUrl(tab.url) || !workspaceIds.contains(workspaceId) || !inSyncedContainer {
                held.insert(syncId)
                continue
            }
            let previous = self.previous(syncId, RecordKind.tab)
            var data = previous ?? [:]
            data["tabId"] = syncId
            if !isJSONFalse(previous?["pinned"]) || seen[syncId] != tab.fingerprint {
                data["url"] = tab.url
                data["title"] = tab.title
            }
            if previous == nil {
                data["icon"] = ""
                data["hasStaticIcon"] = false
                data["defaultContainer"] = false
            }
            data.putNullable("containerGuid", guidOf(tab.contextId))
            data["essential"] = false
            data["pinned"] = false
            data["workspaceUuid"] = workspaceId
            data["folderId"] = NSNull()
            data.putNullable("staticLabel", state.tabTitles[tab.id])
            records[syncId] = .of(syncId, kind: RecordKind.tab, data: data)
            normalTabsBySpace[workspaceId, default: []].append(syncId)
        }
        for record in server.values {
            if !record.isNormalTab || records[record.id] != nil || held.contains(record.id) {
                continue
            }
            if seen[record.id] != nil {
                closedTabs.insert(record.id)
            } else {
                held.insert(record.id)
            }
        }
    }

    /// Zen syncs the title a tab had when it was pinned, so the server's title stays while the pinned address does, even
    /// though Dejavu shows the title of the page open in the pin. A renamed tab keeps its page title too.
    private func putLabel(_ data: inout [String: Any], _ pin: PinnedItem, _ previous: [String: Any]?) {
        let pinnedTitle = previous.flatMap { $0.string("url") == pin.url ? $0.string("title") : nil }
        if pin.staticLabel {
            data["staticLabel"] = pin.title
            data["title"] = previous?.string("title") ?? pin.title
        } else {
            data["staticLabel"] = NSNull()
            data["title"] = pinnedTitle ?? pin.title
        }
    }

    /// Dejavu has no pinned split views, so the server's are kept as long as their tabs stay together, and follow them
    /// when they move. A split whose tabs were separated or unpinned here is deleted, like Zen does.
    private func projectSplits() {
        for split in server.values {
            guard split.kind == RecordKind.split, let data = split.data, split.isSynced else { continue }
            let members = data.strings("tabs") ?? []
            let pins = members.map { pinsById[$0] }
            if members.count < 2 {
                held.insert(split.id)
            } else if pins.contains(where: { $0 == nil }) {
                if members.contains(where: { pinsById[$0] == nil && !isGone($0) }) {
                    held.insert(split.id)
                }
            } else {
                let present = pins.compactMap { $0 }
                if present.contains(where: { $0.isFolder || !projectedPins.contains($0.id) }) {
                    held.insert(split.id)
                    continue
                }
                let first = present[0]
                let together = present.allSatisfy {
                    $0.essential == first.essential && $0.workspaceId == first.workspaceId
                        && parentOf($0) == parentOf(first)
                }
                guard together else { continue }
                var out = data
                out["pinned"] = true
                if !first.essential {
                    out.putNullable("workspaceUuid", first.workspaceId)
                    out.putNullable("folderId", parentOf(first))
                }
                records[split.id] = .of(split.id, kind: RecordKind.split, data: out)
                splitMembers.append((split.id, members))
            }
        }
    }

    private func projectFolders() {
        for pin in state.pins where pin.isFolder && projectedPins.contains(pin.id) {
            guard let workspaceId = pin.workspaceId else { continue }
            let previous = self.previous(pin.id, RecordKind.folder)
            var data = previous ?? [:]
            data["folderId"] = pin.id
            data["name"] = pin.title
            if previous == nil || iconOf(previous?["icon"]) != pin.icon {
                data.putNullable("icon", pin.icon)
            }
            if previous == nil {
                data["live"] = NSNull()
            }
            data["workspaceUuid"] = workspaceId
            data.putNullable("parentFolderId", parentOf(pin))
            data["children"] = children(workspaceId, parentId: pin.id, previous: previous?.strings("children"))
            records[pin.id] = .of(pin.id, kind: RecordKind.folder, data: data)
        }
    }

    private func projectSpaces() {
        for workspace in state.workspaces {
            let previous = self.previous(workspace.id, RecordKind.space)
            var data = previous ?? [:]
            data["uuid"] = workspace.id
            data["name"] = workspace.name
            if previous == nil || iconOf(previous?["icon"]) != workspace.icon {
                data.putNullable("icon", workspace.icon)
            }
            if previous == nil || ZenThemes.toDejavu(previous?["theme"]) != workspace.theme {
                data["theme"] = ZenThemes.fromDejavu(workspace.theme, previous: previous?["theme"])
            }
            data.putNullable("containerGuid", guidOf(workspace.containerId))
            data["children"] = children(workspace.id, parentId: nil, previous: previous?.strings("children"))
            records[workspace.id] = .of(workspace.id, kind: RecordKind.space, data: data)
        }
    }

    private func projectLayout() {
        let previous = self.previous(layoutRecordId, RecordKind.layout)
        var groups: [String: [String]] = [:]
        for pin in state.pins where pin.essential && records[pin.id] != nil {
            groups[guidOf(pin.containerId) ?? Self.defaultEssentials, default: []].append(pin.id)
        }
        let previousGroups = previous?.object("essentials")
        var essentials: [String: Any] = [:]
        var keys = Set(groups.keys)
        if let previousGroups {
            keys.formUnion(previousGroups.keys)
        }
        for key in keys {
            let order = mergeOrder(groups[key] ?? [], previous: previousGroups?.strings(key), isOpaque: isOpaque)
            if !order.isEmpty {
                essentials[key] = order
            }
        }
        let spaces = mergeOrder(state.workspaces.map { $0.id }, previous: previous?.strings("spaces"), isOpaque: isOpaque)
        var data = previous ?? [:]
        data["spaces"] = spaces
        data["essentials"] = essentials
        records[layoutRecordId] = .of(layoutRecordId, kind: RecordKind.layout, data: data)
    }

    /// The children of a space's pinned section or of a folder, with split views in place of their tabs.
    private func children(_ workspaceId: String, parentId: String?, previous: [String]?) -> [String] {
        var siblings = state.pins
            .filter {
                !$0.essential && $0.workspaceId == workspaceId && parentOf($0) == parentId
                    && projectedPins.contains($0.id)
            }
            .map { $0.id }
        if parentId == nil {
            siblings += (normalTabsBySpace[workspaceId] ?? []).filter { !normalSplitMembers.contains($0) }
        }
        for (splitId, members) in splitMembers where members.allSatisfy({ siblings.contains($0) }) {
            let first = members.compactMap { siblings.firstIndex(of: $0) }.min() ?? 0
            siblings.removeAll { members.contains($0) }
            siblings.insert(splitId, at: min(first, siblings.count))
        }
        // A split that no longer holds here leaves its tabs where it was.
        let expanded = previous?.flatMap { id -> [String] in
            if let split = server[id], split.kind == RecordKind.split, split.isSynced, records[id] == nil,
                !held.contains(id)
            {
                return split.data?.strings("tabs") ?? [id]
            }
            return [id]
        }
        return mergeOrder(siblings, previous: expanded, isOpaque: isOpaque)
    }

    /// Whether an id in the server's order of some children stays there although it is not a local child: ids Dejavu
    /// does not know, like normal tabs that Zen syncs, keep their place. Items deleted or moved here do not.
    private func isOpaque(_ id: String) -> Bool {
        if records[id] != nil {
            return false
        }
        if held.contains(id) {
            return true
        }
        if closedTabs.contains(id) {
            return false
        }
        guard let known = server[id] else { return true }
        return !known.deleted && !known.isSynced
    }

    /// Whether `id` is known to be gone: deleted on the server, or synced before and no longer here.
    private func isGone(_ id: String) -> Bool {
        server[id].map { $0.deleted || $0.isSynced } ?? false
    }

    private func parentOf(_ pin: PinnedItem) -> String? {
        guard let parentId = pin.parentId, let parent = pinsById[parentId], parent.isFolder,
            parent.workspaceId == pin.workspaceId
        else {
            return nil
        }
        return parentId
    }

    private func guidOf(_ contextId: String?) -> String? {
        guard let contextId, containerIds.contains(contextId) else { return nil }
        return contextId
    }

    private func previous(_ id: String, _ kind: String) -> [String: Any]? {
        guard let record = server[id], !record.deleted, record.kind == kind else { return nil }
        return record.data
    }
}

/// A tab record's title as Dejavu shows it, and whether it is a name the user gave the tab.
func labelOf(_ data: [String: Any]) -> (String, Bool) {
    if let label = data.string("staticLabel"), !label.isEmpty {
        return (label, true)
    }
    return (data.string("title") ?? "", false)
}

/// Puts the ids of `previous`, an order from the server, in the order of `local`, keeping the ids of `previous` that are
/// not local and `isOpaque` right after the local id they followed. The result equals `previous` when `local` lists its
/// local ids in the same order, so an unchanged order is never uploaded again.
func mergeOrder(_ local: [String], previous: [String]?, isOpaque: (String) -> Bool) -> [String] {
    guard let previous, !previous.isEmpty else { return local }
    let localIds = Set(local)
    var leading: [String] = []
    var anchored: [String: [String]] = [:]
    var anchor: String?
    var seen = Set<String>()
    for id in previous where seen.insert(id).inserted {
        if localIds.contains(id) {
            anchor = id
        } else if isOpaque(id) {
            if let anchor {
                anchored[anchor, default: []].append(id)
            } else {
                leading.append(id)
            }
        }
    }
    var result = leading
    for id in local {
        result.append(id)
        result += anchored[id] ?? []
    }
    return result
}

/// Orders `current` like `desired`. Ids of `current` missing from `desired` stay right after the id they followed.
func reorder(_ current: [String], _ desired: [String]) -> [String] {
    let currentIds = Set(current)
    var result = desired.filter { currentIds.contains($0) }.uniqued { $0 }
    var placed = Set(result)
    for (index, id) in current.enumerated() where !placed.contains(id) {
        let before = current[..<index].reversed().first { placed.contains($0) }
        let position = before.flatMap { anchor in result.firstIndex(of: anchor).map { $0 + 1 } } ?? 0
        result.insert(id, at: position)
        placed.insert(id)
    }
    return result
}
