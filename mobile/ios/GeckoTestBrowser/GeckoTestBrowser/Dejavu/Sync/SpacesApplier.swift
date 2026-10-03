// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// The outcome of applying incoming records.
///
/// - `state`: Dejavu's workspaces with the records applied.
/// - `applied`: Records that are now reflected locally, or deliberately left alone. The server holds them as they are,
///   so they become the server's known copy.
/// - `failed`: Records that could not be applied yet, usually because a space, folder or container they need has not
///   arrived. They are applied again on the next sync.
/// - `tabOps`: What to do with open tabs so that they match the records.
struct ApplyResult {
    let state: WorkspaceState
    let applied: [SpacesRecord]
    let failed: [SpacesRecord]
    var tabOps: [TabOp] = []
}

/// A change to the open tabs that applying records asks for.
enum TabOp {
    /// Opens `url` as tab `id` of workspace `workspaceId` in container `contextId`, without loading it.
    case open(id: String, url: String, title: String, contextId: String?, workspaceId: String)

    /// Points tab `id`, whose page is not loaded, at `url`.
    case retarget(id: String, url: String, title: String)

    /// Closes tab `id`.
    case close(id: String)
}

/// Applies incoming records, except containers, to Dejavu's workspaces, like Zen's ZenSpacesSyncApplier does to its
/// sidebar: removed tabs first, then spaces, folders (parents first) and tabs, then removed folders and spaces, then the
/// order of everything.
///
/// Incoming records always win over local changes. Anything that then differs locally is uploaded afterwards.
///
/// - `server`: The records known to be on the server before this batch.
/// - `containerIds`: The permanent containers that exist locally, already including the incoming ones.
/// - `firstSync`: Whether nothing was synced before, so a pristine default workspace can make way for synced ones.
/// - `defaultName`: Name of a new default workspace.
/// - `now`: Time used for the changes, in milliseconds.
/// - `normalTabs`: Whether tabs that are not pinned are synced too. Otherwise their records only unpin tabs.
/// - `tabs`: The open tabs that are not private by id, or `nil` while the browser has not restored them. Records of tabs
///   that are not pinned then wait.
struct SpacesApplier {
    let server: OrderedRecords
    let containerIds: Set<String>
    let firstSync: Bool
    let defaultName: String
    let now: Int64
    var normalTabs = false
    var tabs: [String: LocalTab]? = nil

    /// Applies `incoming` to `state`. Pure: the same input always gives the same result.
    func apply(_ state: WorkspaceState, _ incoming: [SpacesRecord]) -> ApplyResult {
        ApplyPass(self, original: state, incoming: incoming).run()
    }
}

/// One application of a batch of records, with the workspaces it changes.
private final class ApplyPass {
    private enum Outcome {
        case applied
        case failed
        case ignored
    }

    /// A container a record refers to; `contextId` is `nil` for no container.
    private struct ContainerRef {
        let contextId: String?
    }

    private let applier: SpacesApplier
    private let original: WorkspaceState
    private let incoming: [SpacesRecord]
    private var workspaces: [Workspace]
    private var pins: [PinnedItem]
    private var assignments: [String: String]
    private var tabTitles: [String: String]
    private var tabSyncIds: [String: String]
    private var tabOps: [TabOp] = []
    private let openTabs: [String: LocalTab]

    /// Open tabs by the id they sync under.
    private let tabIdsBySyncId: [String: String]
    private var activeId: String
    private var applied: [SpacesRecord] = []
    private var appliedIds = Set<String>()
    private var failed: [SpacesRecord] = []
    private let incomingIds: Set<String>
    private let splitMembers: [String: [String]]

    init(_ applier: SpacesApplier, original: WorkspaceState, incoming: [SpacesRecord]) {
        self.applier = applier
        self.original = original
        self.incoming = incoming
        workspaces = original.workspaces
        pins = original.pins
        assignments = original.assignments
        tabTitles = original.tabTitles
        tabSyncIds = original.tabSyncIds
        let open = applier.tabs ?? [:]
        openTabs = open
        tabIdsBySyncId = Dictionary(open.keys.map { (original.syncIdOf($0), $0) }, uniquingKeysWith: { _, last in last })
        activeId = original.activeWorkspaceId
        incomingIds = Set(incoming.map { $0.id })
        var members: [String: [String]] = [:]
        for split in applier.server.values + incoming where !split.deleted && split.kind == RecordKind.split {
            if let tabs = split.data?.strings("tabs") {
                members[split.id] = tabs
            }
        }
        splitMembers = members
    }

    func run() -> ApplyResult {
        let upserts = incoming.filter { !$0.deleted }
        let removals = incoming.filter { $0.deleted }
        let spaces = upserts.filter { $0.kind == RecordKind.space }
        let folders = upserts.filter { $0.kind == RecordKind.folder }
        let layout = upserts.last { $0.kind == RecordKind.layout && $0.id == layoutRecordId }

        removals.forEach { removeTab($0.id) }
        var createdSpaces: [String] = []
        for record in spaces {
            let isNew = !workspaces.contains { $0.id == record.id }
            note(record, upsertSpace(record))
            if isNew && workspaces.contains(where: { $0.id == record.id }) {
                createdSpaces.append(record.id)
            }
        }
        dropPristineWorkspace(createdSpaces, layout: layout)
        sortParentsFirst(folders).forEach { note($0, upsertFolder($0)) }
        upserts.filter { $0.kind == RecordKind.tab }.forEach { note($0, upsertTab($0)) }
        upserts.filter { $0.kind == RecordKind.split }.forEach {
            note($0, $0.data?.strings("tabs") != nil ? .applied : .ignored)
        }
        for record in removals {
            if let folder = pins.first(where: { $0.id == record.id && $0.isFolder }) {
                deleteFolder(folder)
            }
        }
        removals.forEach { deleteWorkspace($0.id) }
        removals.forEach { markApplied($0) }
        applyOrder(spaces: spaces, folders: folders, layout: layout)

        var state = original
        state.workspaces = workspaces
        state.pins = pins
        state.activeWorkspaceId = workspaces.contains { $0.id == activeId } ? activeId : workspaces[0].id
        state.assignments = assignments
        state.tabTitles = tabTitles
        state.tabSyncIds = tabSyncIds
        return ApplyResult(state: state, applied: applied, failed: failed, tabOps: tabOps)
    }

    private func note(_ record: SpacesRecord, _ outcome: Outcome) {
        switch outcome {
        case .applied: markApplied(record)
        case .failed: failed.append(record)
        case .ignored: break
        }
    }

    private func markApplied(_ record: SpacesRecord) {
        applied.append(record)
        appliedIds.insert(record.id)
    }

    private func upsertSpace(_ record: SpacesRecord) -> Outcome {
        guard let data = record.data else { return .ignored }
        guard let container = containerOf(data) else { return .failed }
        let name = data.string("name") ?? ""
        let icon = iconOf(data["icon"])
        let theme = ZenThemes.toDejavu(data["theme"])
        guard let index = workspaces.firstIndex(where: { $0.id == record.id }) else {
            workspaces.append(
                Workspace(
                    id: record.id, name: name, containerId: container.contextId, icon: icon, theme: theme,
                    createdAt: applier.now, updatedAt: applier.now))
            return .applied
        }
        let current = workspaces[index]
        var updated = current
        updated.name = name
        updated.icon = icon
        updated.theme = theme
        updated.containerId = keepLocalContainer(current.containerId, container.contextId)
        if updated != current {
            updated.updatedAt = applier.now
            workspaces[index] = updated
        }
        return .applied
    }

    /// On the first sync, the untouched workspace a fresh Dejavu starts with is replaced by the synced ones instead of
    /// being uploaded as an empty space.
    private func dropPristineWorkspace(_ created: [String], layout: SpacesRecord?) {
        guard applier.firstSync, !created.isEmpty, original.workspaces.count == 1 else { return }
        let initial = original.workspaces[0]
        let pristine = initial.name == applier.defaultName && initial.icon == nil && initial.theme == nil
            && initial.containerId == nil && !pins.contains { $0.workspaceId == initial.id }
        guard pristine, !created.contains(initial.id) else { return }
        let target = layout?.data?.strings("spaces")?.first { created.contains($0) } ?? created[0]
        workspaces.removeAll { $0.id == initial.id }
        assignments = assignments.mapValues { $0 == initial.id ? target : $0 }
        if activeId == initial.id {
            activeId = target
        }
    }

    private func upsertFolder(_ record: SpacesRecord) -> Outcome {
        guard let data = record.data else { return .ignored }
        let parentId = data.string("parentFolderId").flatMap { $0 == record.id ? nil : $0 }
        let parent = parentId.flatMap { id in pins.first { $0.id == id && $0.isFolder } }
        if parentId != nil {
            guard let parent, !isInside(parent.id, record.id) else { return .failed }
        }
        guard let workspaceId = parent?.workspaceId ?? data.string("workspaceUuid"),
            workspaces.contains(where: { $0.id == workspaceId })
        else {
            return .failed
        }
        let name = data.string("name") ?? ""
        let icon = iconOf(data["icon"])
        guard let index = pins.firstIndex(where: { $0.id == record.id }) else {
            pins.append(
                PinnedItem(
                    id: record.id, workspaceId: workspaceId, parentId: parent?.id, kind: .folder, title: name,
                    collapsed: true, icon: icon, createdAt: applier.now, updatedAt: applier.now))
            return .applied
        }
        let current = pins[index]
        if !current.isFolder {
            return .failed
        }
        var updated = current
        updated.title = name
        updated.icon = icon
        updated.workspaceId = workspaceId
        updated.parentId = parent?.id
        if updated != current {
            updated.updatedAt = applier.now
            pins[index] = updated
            if current.workspaceId != workspaceId {
                moveNested(record.id, to: workspaceId)
            }
        }
        return .applied
    }

    private func upsertTab(_ record: SpacesRecord) -> Outcome {
        guard let data = record.data else { return .ignored }
        if record.isNormalTab {
            return upsertNormalTab(record, data)
        }
        guard let url = data.string("url"), !url.isEmpty, url != SpacesProjector.blankUrl else { return .ignored }
        guard let container = containerOf(data) else { return .failed }
        let essential = isJSONTrue(data["essential"])
        let existing = pins.first { $0.id == record.id }
        if existing?.isFolder == true {
            return .failed
        }
        guard let placement = placementOf(data, essential: essential, existing: existing) else { return .failed }
        let (workspaceId, parentId) = placement
        let (title, staticLabel) = labelOf(data)
        guard let existing, let index = pins.firstIndex(where: { $0.id == existing.id }) else {
            // A tab open here without a pin, pinned elsewhere, becomes the pinned tab.
            let openTab = tabIdsBySyncId[record.id].flatMap { tabId in pins.contains { $0.tabId == tabId } ? nil : tabId }
            pins.append(
                PinnedItem(
                    id: record.id, workspaceId: workspaceId, parentId: parentId, kind: .tab, title: title, url: url,
                    containerId: container.contextId, essential: essential, staticLabel: staticLabel, tabId: openTab,
                    createdAt: applier.now, updatedAt: applier.now))
            if let openTab {
                tabSyncIds.removeValue(forKey: openTab)
                tabTitles.removeValue(forKey: openTab)
                if let workspaceId {
                    assignments[openTab] = workspaceId
                }
            }
            return .applied
        }
        var updated = existing
        updated.title = title
        updated.staticLabel = staticLabel
        updated.url = url
        updated.containerId = keepLocalContainer(existing.containerId, container.contextId)
        updated.essential = essential
        updated.workspaceId = workspaceId
        updated.parentId = parentId
        if updated != existing {
            updated.updatedAt = applier.now
            pins[index] = updated
            if let tabId = existing.tabId, let workspaceId, workspaceId != existing.workspaceId {
                assignments[tabId] = workspaceId
            }
        }
        return .applied
    }

    /// The workspace and folder a tab record puts its pin in: none for essentials, the folder's workspace for a tab in a
    /// folder. `nil` when the space or folder has not arrived yet.
    private func placementOf(
        _ data: [String: Any], essential: Bool, existing: PinnedItem?
    ) -> (workspaceId: String?, parentId: String?)? {
        if essential {
            return (nil, nil)
        }
        if let folderId = data.string("folderId") {
            guard let folder = pins.first(where: { $0.id == folderId && $0.isFolder }) else { return nil }
            return (folder.workspaceId, folder.id)
        }
        let workspaceId = data.string("workspaceUuid") ?? existing?.workspaceId ?? activeId
        return workspaces.contains { $0.id == workspaceId } ? (workspaceId, nil) : nil
    }

    /// A tab that is not pinned: a pinned tab unpinned elsewhere, or one of Zen's unpinned tabs. Without unpinned tabs
    /// synced it only unpins. With them, like Zen, the tab is opened here without loading it, or moved to its workspace
    /// and, when its page is not loaded, pointed at its new address.
    private func upsertNormalTab(_ record: SpacesRecord, _ data: [String: Any]) -> Outcome {
        let pin = pins.first { $0.id == record.id && !$0.isFolder }
        guard applier.normalTabs else {
            if let pin {
                unpin(pin.id)
            }
            return .applied
        }
        guard applier.tabs != nil else { return .failed }
        guard let url = data.string("url"), isSyncableUrl(url) else {
            if let pin {
                unpin(pin.id)
            }
            return .ignored
        }
        guard let container = containerOf(data) else { return .failed }
        guard let workspaceId = data.string("workspaceUuid"), workspaces.contains(where: { $0.id == workspaceId }) else {
            return .failed
        }
        let title = data.string("title") ?? ""
        if let pin {
            unpin(pin.id)
        }
        let pinnedTab = (pin?.tabId).flatMap { openTabs[$0] != nil ? $0 : nil }
        let syncedTab = tabIdsBySyncId[record.id].flatMap { tabId in pins.contains { $0.tabId == tabId } ? nil : tabId }
        guard let tabId = pinnedTab ?? syncedTab else {
            tabOps.append(
                .open(id: record.id, url: url, title: title, contextId: container.contextId, workspaceId: workspaceId))
            assignments[record.id] = workspaceId
            applyLabel(record.id, data)
            return .applied
        }
        assignments[tabId] = workspaceId
        applyLabel(tabId, data)
        if let tab = openTabs[tabId], !tab.awake, tab.url != url {
            tabOps.append(.retarget(id: tabId, url: url, title: title))
        }
        return .applied
    }

    /// Gives tab `tabId` the name of the record's static label, or its page title when it has none.
    private func applyLabel(_ tabId: String, _ data: [String: Any]) {
        if let label = data.string("staticLabel"), !label.isEmpty {
            tabTitles[tabId] = label
        } else {
            tabTitles.removeValue(forKey: tabId)
        }
    }

    /// A tab removed elsewhere. A pinned tab loses its pin; with unpinned tabs synced its open tab closes too, like in
    /// Zen, and so does an unpinned tab.
    private func removeTab(_ id: String) {
        let syncsTabs = applier.normalTabs && applier.tabs != nil
        if let index = pins.firstIndex(where: { $0.id == id && !$0.isFolder }) {
            let pin = pins[index]
            if syncsTabs, let tabId = pin.tabId, openTabs[tabId] != nil {
                pins.remove(at: index)
                tabOps.append(.close(id: tabId))
            } else {
                unpin(pin.id)
            }
            return
        }
        guard syncsTabs, let tabId = tabIdsBySyncId[id], !pins.contains(where: { $0.tabId == tabId }) else { return }
        tabOps.append(.close(id: tabId))
    }

    /// Removes a pinned tab; its open tab stays open as a normal tab, keeping a name the user gave it and syncing under
    /// the pin's id.
    private func unpin(_ pinId: String) {
        guard let index = pins.firstIndex(where: { $0.id == pinId && !$0.isFolder }) else { return }
        let pin = pins.remove(at: index)
        guard let tabId = pin.tabId else { return }
        assignments[tabId] = pin.workspaceId ?? activeId
        if pin.staticLabel {
            tabTitles[tabId] = pin.title
        }
        if tabId != pin.id {
            tabSyncIds[tabId] = pin.id
        }
    }

    /// Removes a folder deleted elsewhere with the synced items inside it, like Zen does. Items that never reached the
    /// server move up to where the folder was instead of being lost.
    private func deleteFolder(_ folder: PinnedItem) {
        let removed = descendants(folder.id).filter { id in
            incomingIds.contains(id) || (applier.server[id].map { $0.isSynced || $0.deleted } ?? false)
        }
        for id in removed {
            guard let item = pins.first(where: { $0.id == id }) else { continue }
            if item.isFolder {
                pins.removeAll { $0.id == id }
            } else {
                removeTab(id)
            }
        }
        let gone = Set(removed).union([folder.id])
        pins = pins.map { item in
            guard let parentId = item.parentId, gone.contains(parentId) else { return item }
            var moved = item
            moved.parentId = folder.parentId
            moved.updatedAt = applier.now
            return moved
        }
        pins.removeAll { $0.id == folder.id }
    }

    /// Removes a space deleted elsewhere. What is left in it moves to a neighbouring workspace, like when deleting a
    /// workspace in Dejavu. The last workspace is kept, which uploads it again.
    private func deleteWorkspace(_ id: String) {
        guard let index = workspaces.firstIndex(where: { $0.id == id }), workspaces.count > 1 else { return }
        let target = workspaces[index > 0 ? index - 1 : 1].id
        workspaces.remove(at: index)
        pins = pins.map { item in
            guard item.workspaceId == id else { return item }
            var moved = item
            moved.workspaceId = target
            moved.updatedAt = applier.now
            return moved
        }
        assignments = assignments.mapValues { $0 == id ? target : $0 }
        if activeId == id {
            activeId = target
        }
    }

    /// Orders the children of the applied spaces and folders, then the spaces and essentials of the layout.
    private func applyOrder(spaces: [SpacesRecord], folders: [SpacesRecord], layout: SpacesRecord?) {
        for record in spaces where appliedIds.contains(record.id) {
            reorderChildren(record.id, parentId: nil, children: record.data?.strings("children"))
        }
        for record in folders where appliedIds.contains(record.id) {
            if let workspaceId = pins.first(where: { $0.id == record.id })?.workspaceId {
                reorderChildren(workspaceId, parentId: record.id, children: record.data?.strings("children"))
            }
        }
        if let layout {
            applyLayout(layout.data)
            markApplied(layout)
        }
    }

    private func reorderChildren(_ workspaceId: String, parentId: String?, children: [String]?) {
        guard let children, !children.isEmpty else { return }
        let desired = children.flatMap { splitMembers[$0] ?? [$0] }
        let slots = pins.indices.filter { index in
            let pin = pins[index]
            return !pin.essential && pin.workspaceId == workspaceId && parentOf(pin) == parentId
        }
        reorderSlots(slots, desired)
    }

    private func applyLayout(_ data: [String: Any]?) {
        guard let data else { return }
        if let order = data.strings("spaces") {
            let ids = reorder(workspaces.map { $0.id }, order)
            let byId = Dictionary(workspaces.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
            workspaces = ids.compactMap { byId[$0] }
        }
        guard let groups = data.object("essentials") else { return }
        for key in groups.keys.sorted() {
            guard let order = groups.strings(key) else { continue }
            let slots = pins.indices.filter { pins[$0].essential && essentialsKey(pins[$0].containerId) == key }
            reorderSlots(slots, order)
        }
    }

    private func reorderSlots(_ slots: [Int], _ desired: [String]) {
        let current = slots.map { pins[$0] }
        let ids = reorder(current.map { $0.id }, desired)
        guard ids.count == slots.count, ids != current.map({ $0.id }) else { return }
        let byId = Dictionary(current.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        for (position, slot) in slots.enumerated() {
            if let pin = byId[ids[position]] {
                pins[slot] = pin
            }
        }
    }

    private func moveNested(_ folderId: String, to workspaceId: String) {
        let nested = Set(descendants(folderId))
        pins = pins.map { item in
            guard nested.contains(item.id) else { return item }
            var moved = item
            moved.workspaceId = workspaceId
            return moved
        }
    }

    /// The ids of every item nested below `folderId`, level by level.
    private func descendants(_ folderId: String) -> [String] {
        var result: [String] = []
        var found = Set<String>()
        var level: Set<String> = [folderId]
        while !level.isEmpty {
            let next = pins.filter { pin in
                guard let parentId = pin.parentId else { return false }
                return level.contains(parentId) && !found.contains(pin.id)
            }.map { $0.id }
            level = []
            for id in next where found.insert(id).inserted {
                result.append(id)
                level.insert(id)
            }
        }
        return result
    }

    private func isInside(_ folderId: String, _ ancestorId: String) -> Bool {
        descendants(ancestorId).contains(folderId)
    }

    private func parentOf(_ pin: PinnedItem) -> String? {
        guard let parentId = pin.parentId,
            pins.contains(where: { $0.id == parentId && $0.isFolder && $0.workspaceId == pin.workspaceId })
        else {
            return nil
        }
        return parentId
    }

    private func containerOf(_ data: [String: Any]) -> ContainerRef? {
        guard let guid = data.string("containerGuid"), !guid.isEmpty else { return ContainerRef(contextId: nil) }
        return applier.containerIds.contains(guid) ? ContainerRef(contextId: guid) : nil
    }

    /// Keeps a local container the server cannot see, like a temporary one, while the synced one is unchanged.
    private func keepLocalContainer(_ local: String?, _ synced: String?) -> String? {
        let visible = local.flatMap { applier.containerIds.contains($0) ? $0 : nil }
        return visible == synced ? local : synced
    }

    private func essentialsKey(_ containerId: String?) -> String {
        containerId.flatMap { applier.containerIds.contains($0) ? $0 : nil } ?? SpacesProjector.defaultEssentials
    }

    private func sortParentsFirst(_ folders: [SpacesRecord]) -> [SpacesRecord] {
        var parents: [String: String?] = [:]
        for folder in folders {
            parents[folder.id] = .some(folder.data?.string("parentFolderId"))
        }
        func depth(_ id: String) -> Int {
            var seen: Set<String> = [id]
            var depth = 0
            var parent = parents[id] ?? nil
            while let current = parent, parents[current] != nil, seen.insert(current).inserted {
                depth += 1
                parent = parents[current] ?? nil
            }
            return depth
        }
        return folders.enumerated()
            .sorted { first, second in
                let (firstDepth, secondDepth) = (depth(first.element.id), depth(second.element.id))
                return firstDepth != secondDepth ? firstDepth < secondDepth : first.offset < second.offset
            }
            .map { $0.element }
    }
}
