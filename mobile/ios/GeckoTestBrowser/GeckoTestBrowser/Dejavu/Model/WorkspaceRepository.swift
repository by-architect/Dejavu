// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Combine
import Foundation

/// What is needed to pin an open tab.
struct PinSource {
    let tabId: String
    let url: String
    let title: String
    let containerId: String?
}

/// Where dropped items go in a workspace's pinned section.
enum PinPlacement {
    /// At the end of `folderId`.
    case into(folderId: String)

    /// Next to `itemId`, as its sibling.
    case next(itemId: String, after: Bool)

    /// At the start or the end of the top level.
    case edge(atEnd: Bool)
}

/// Keeps workspaces, pinned tabs, folders and tab assignments, and saves them in workspaces.json. Used from the main
/// thread only.
final class WorkspaceRepository: ObservableObject {
    static let shared = WorkspaceRepository()

    private static let fileName = "workspaces.json"

    @Published private(set) var state: WorkspaceState

    private var expectedTabs = Set<String>()

    private init() {
        state = WorkspaceSerializer.read(
            DataFiles.readObject(Self.fileName), defaultName: L10n.workspaceDefaultName, now: nowMillis())
    }

    var defaultName: String {
        L10n.workspaceDefaultName
    }

    func selectWorkspace(_ id: String) {
        mutate { state in
            guard state.workspaces.contains(where: { $0.id == id }) else { return state }
            var next = state
            next.activeWorkspaceId = id
            return next
        }
    }

    func addWorkspace(name: String, containerId: String?, icon: String?, theme: WorkspaceTheme?) {
        mutate { state in
            let now = nowMillis()
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            let workspace = Workspace(
                id: WorkspaceSerializer.newWorkspaceId(),
                name: trimmed.isEmpty ? defaultName : trimmed,
                containerId: containerId,
                icon: icon,
                theme: theme,
                createdAt: now,
                updatedAt: now)
            var next = state
            next.workspaces.append(workspace)
            next.activeWorkspaceId = workspace.id
            return next
        }
    }

    func updateWorkspace(id: String, name: String, containerId: String?, icon: String?, theme: WorkspaceTheme?) {
        mutate { state in
            var next = state
            next.workspaces = state.workspaces.map { workspace in
                guard workspace.id == id else { return workspace }
                var updated = workspace
                let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                updated.name = trimmed.isEmpty ? workspace.name : trimmed
                updated.containerId = containerId
                updated.icon = icon
                updated.theme = theme
                updated.updatedAt = nowMillis()
                return updated
            }
            return next
        }
    }

    /// Moves workspace `id` to position `index` among the workspaces.
    func moveWorkspace(_ id: String, to index: Int) {
        mutate { state in
            guard let workspace = state.workspaces.first(where: { $0.id == id }) else { return state }
            var others = state.workspaces.filter { $0.id != id }
            others.insert(workspace, at: index.clamped(0, others.count))
            var next = state
            next.workspaces = others
            return next
        }
    }

    /// Deletes a workspace, moving its tabs and pinned items to a neighbouring workspace. The last one is kept.
    func deleteWorkspace(_ id: String) {
        mutate { state in
            guard let index = state.workspaces.firstIndex(where: { $0.id == id }), state.workspaces.count > 1 else {
                return state
            }
            let target = state.workspaces[index > 0 ? index - 1 : 1].id
            let now = nowMillis()
            var next = state
            next.workspaces.remove(at: index)
            next.pins = state.pins.map { pin in
                guard pin.workspaceId == id else { return pin }
                var moved = pin
                moved.workspaceId = target
                moved.updatedAt = now
                return moved
            }
            next.assignments = state.assignments.mapValues { $0 == id ? target : $0 }
            if state.activeWorkspaceId == id {
                next.activeWorkspaceId = target
            }
            return next
        }
    }

    /// Points every workspace and pinned tab using container `oldId` to `newId`, or to no container.
    func replaceContainer(_ oldId: String, with newId: String?) {
        mutate { state in
            let now = nowMillis()
            var next = state
            next.workspaces = state.workspaces.map { workspace in
                guard workspace.containerId == oldId else { return workspace }
                var updated = workspace
                updated.containerId = newId
                updated.updatedAt = now
                return updated
            }
            next.pins = state.pins.map { pin in
                guard pin.containerId == oldId else { return pin }
                var updated = pin
                updated.containerId = newId
                updated.updatedAt = now
                return updated
            }
            return next
        }
    }

    /// Makes pinned tabs `pinIds` reopen in container `containerId`, or without a container when it is `nil`.
    func setPinContainer(_ pinIds: Set<String>, containerId: String?) {
        mutate { state in
            let now = nowMillis()
            var next = state
            next.pins = state.pins.map { pin in
                guard pinIds.contains(pin.id), !pin.isFolder, pin.containerId != containerId else { return pin }
                var updated = pin
                updated.containerId = containerId
                updated.updatedAt = now
                return updated
            }
            return next
        }
    }

    /// Removes the pinned tabs of container `containerId` or backed by one of `tabIds`.
    func removePins(ofContainer containerId: String, tabIds: Set<String>) {
        mutate { state in
            var next = state
            next.pins = state.pins.filter { pin in
                pin.isFolder || !(pin.containerId == containerId || (pin.tabId.map { tabIds.contains($0) } ?? false))
            }
            return next
        }
    }

    /// Keeps what Dejavu knows of tab `tabId`, which is about to be opened or replaced, until the browser shows it:
    /// `syncWithTabs` would otherwise forget it in between.
    func expectTab(_ tabId: String) {
        expectedTabs.insert(tabId)
    }

    /// Puts tab `tabId`, open or about to be opened, in workspace `workspaceId`.
    func assignTab(_ tabId: String, to workspaceId: String) {
        mutate { state in
            guard state.workspaces.contains(where: { $0.id == workspaceId }) else { return state }
            var next = state
            next.assignments[tabId] = workspaceId
            return next
        }
    }

    /// Hands the workspace, pin and split view of tab `oldTabId` over to `newTabId`, which replaces it.
    func replaceTab(_ oldTabId: String, with newTabId: String) {
        mutate { state in
            let workspaceId = state.assignments[oldTabId] ?? state.pinOf(oldTabId)?.workspaceId
            var next = state
            next.pins = state.pins.map { pin in
                guard pin.tabId == oldTabId else { return pin }
                var updated = pin
                updated.tabId = newTabId
                return updated
            }
            next.assignments.removeValue(forKey: oldTabId)
            if let workspaceId {
                next.assignments[newTabId] = workspaceId
            }
            next.splits = state.splits.map { split in
                SplitView(id: split.id, tabIds: split.tabIds.map { $0 == oldTabId ? newTabId : $0 })
            }
            if let title = state.tabTitles[oldTabId] {
                next.tabTitles.removeValue(forKey: oldTabId)
                next.tabTitles[newTabId] = title
            }
            next.tabSyncIds.removeValue(forKey: oldTabId)
            return next
        }
    }

    /// Shows tabs `first` and `second` together in the browser. They leave any split view they were in.
    func createSplit(_ first: String, _ second: String) {
        mutate { state in
            guard first != second else { return state }
            var next = state
            next.splits = state.splits.filter { !$0.tabIds.contains(first) && !$0.tabIds.contains(second) }
            next.splits.append(SplitView(id: newId(), tabIds: [first, second]))
            return next
        }
    }

    /// Ends the split views that any of `tabIds` is part of.
    func unsplit(_ tabIds: Set<String>) {
        mutate { state in
            var next = state
            next.splits = state.splits.filter { split in !split.tabIds.contains { tabIds.contains($0) } }
            return next
        }
    }

    /// Moves pinned items `itemIds` of `workspaceId` and pins the open tabs `newPins` at `placement`. Essentials among
    /// `itemIds` leave the essentials and become pinned tabs of `workspaceId`, like in Zen. Folders that would end up
    /// inside themselves or deeper than `maxFolderDepth` stay where they are.
    func placePins(workspaceId: String, itemIds: Set<String>, newPins: [PinSource], placement: PinPlacement) {
        mutate { state in
            let parentId: String?
            switch placement {
            case .into(let folderId):
                parentId = folderId
            case .next(let itemId, _):
                parentId = state.pins.first { $0.id == itemId }?.parentId
            case .edge:
                parentId = nil
            }
            if let parentId,
                !state.pins.contains(where: { $0.id == parentId && $0.isFolder && $0.workspaceId == workspaceId })
            {
                return state
            }
            func isBlocked(_ item: PinnedItem) -> Bool {
                guard item.isFolder, let parentId else { return false }
                return parentId == item.id || state.descendantIds(item.id).contains(parentId)
                    || state.folderDepth(parentId) + state.folderHeight(item.id) > maxFolderDepth
            }
            let moving = state.pins.filter { item in
                itemIds.contains(item.id) && (item.workspaceId == workspaceId || item.essential) && !isBlocked(item)
            }
            let now = nowMillis()
            let alreadyPinned = Set(state.pins.compactMap { $0.tabId })
            let created = newPins.filter { !alreadyPinned.contains($0.tabId) }.uniqued { $0.tabId }.map { source in
                state.newPin(source, workspaceId: workspaceId, parentId: parentId, essential: false, now: now)
            }
            let placed =
                moving.map { item -> PinnedItem in
                    var updated = item
                    updated.parentId = parentId
                    updated.workspaceId = workspaceId
                    updated.essential = false
                    updated.updatedAt = now
                    return updated
                } + created
            if placed.isEmpty {
                return state
            }

            let movingIds = Set(moving.map { $0.id })
            var rest = state.pins.filter { !movingIds.contains($0.id) }
            let index: Int
            switch placement {
            case .next(let itemId, let after):
                if let found = rest.firstIndex(where: { $0.id == itemId }) {
                    index = after ? found + 1 : found
                } else {
                    index = rest.count
                }
            case .edge(let atEnd):
                if atEnd {
                    index = rest.count
                } else {
                    index = rest.firstIndex { $0.workspaceId == workspaceId && $0.parentId == nil } ?? rest.count
                }
            case .into:
                index = rest.count
            }
            rest.insert(contentsOf: placed, at: index)
            var next = state
            next.pins = rest
            for tabId in placed.compactMap({ $0.tabId }) {
                next.assignments[tabId] = workspaceId
                next.tabTitles.removeValue(forKey: tabId)
            }
            return next
        }
    }

    /// Makes pinned tabs `pinIds` and the open tabs `sources` essentials, shown in every workspace, as long as there is
    /// room for them below `maxEssentials`. With `perContainer` every container has its own essentials and its own
    /// limit.
    func addToEssentials(sources: [PinSource], pinIds: Set<String>, perContainer: Bool) {
        mutate { state in
            let now = nowMillis()
            var counts: [String?: Int] = [:]
            for essential in state.essentials {
                counts[perContainer ? essential.containerId : nil, default: 0] += 1
            }
            func takeRoom(_ containerId: String?) -> Bool {
                let group = perContainer ? containerId : nil
                let count = counts[group] ?? 0
                if count >= maxEssentials {
                    return false
                }
                counts[group] = count + 1
                return true
            }
            let converted = state.pins
                .filter { pinIds.contains($0.id) && !$0.isFolder && !$0.essential && takeRoom($0.containerId) }
                .map { pin -> PinnedItem in
                    var updated = pin
                    updated.essential = true
                    updated.workspaceId = nil
                    updated.parentId = nil
                    updated.updatedAt = now
                    return updated
                }
            let alreadyPinned = Set(state.pins.compactMap { $0.tabId })
            let fresh = sources.filter { !alreadyPinned.contains($0.tabId) }.uniqued { $0.tabId }
            let created = fresh.filter { takeRoom($0.containerId) }.map { source in
                state.newPin(source, workspaceId: nil, parentId: nil, essential: true, now: now)
            }
            if converted.isEmpty && created.isEmpty {
                return state
            }
            let convertedIds = Set(converted.map { $0.id })
            var next = state
            next.pins = state.pins.filter { !convertedIds.contains($0.id) } + converted + created
            for tabId in created.compactMap({ $0.tabId }) {
                next.tabTitles.removeValue(forKey: tabId)
                next.tabSyncIds.removeValue(forKey: tabId)
            }
            return next
        }
    }

    /// Removes essentials `pinIds`; their open tabs stay open as normal tabs of `workspaceId`.
    func removeFromEssentials(_ pinIds: Set<String>, workspaceId: String) {
        mutate { state in
            let removed = state.pins.filter { pinIds.contains($0.id) && $0.essential }
            if removed.isEmpty {
                return state
            }
            let removedIds = Set(removed.map { $0.id })
            var next = state
            next.pins = state.pins.filter { !removedIds.contains($0.id) }
            for tabId in removed.compactMap({ $0.tabId }) {
                next.assignments[tabId] = workspaceId
            }
            next.tabTitles = state.titlesKept(from: removed)
            next.tabSyncIds = state.syncIdsKept(from: removed)
            return next
        }
    }

    /// Moves essential `pinId` to position `index` among the essentials, or with `perContainer` among the essentials of
    /// its container. The other essentials keep their places.
    func moveEssential(_ pinId: String, to index: Int, perContainer: Bool) {
        mutate { state in
            guard let item = state.essentials.first(where: { $0.id == pinId }) else { return state }
            let inGroup: (PinnedItem) -> Bool = { other in !perContainer || other.containerId == item.containerId }
            var group = state.essentials.filter(inGroup)
            group.removeAll { $0.id == item.id }
            group.insert(item, at: index.clamped(0, group.count))
            var reordered = group.makeIterator()
            let essentials = state.essentials.map { other -> PinnedItem in
                inGroup(other) ? (reordered.next() ?? other) : other
            }
            var next = state
            next.pins = state.pins.filter { !$0.essential } + essentials
            return next
        }
    }

    /// Removes pinned items: folders with everything inside them, pinned tabs and essentials. The caller closes the
    /// open tabs of the removed pins.
    func deleteItems(_ itemIds: Set<String>) {
        mutate { state in
            var removed = itemIds
            for id in itemIds {
                removed.formUnion(state.descendantIds(id))
            }
            var next = state
            next.pins = state.pins.filter { !removed.contains($0.id) }
            return next
        }
    }

    /// Pins open tabs in their workspaces, optionally inside `parentId`. Tabs that are already pinned are skipped.
    func pinTabs(_ sources: [PinSource], parentId: String? = nil) {
        mutate { state in state.withPins(sources, parentId: parentId) }
    }

    /// Removes pinned tabs. Their open tabs stay open as normal tabs.
    func unpin(_ pinIds: Set<String>) {
        mutate { state in
            let removed = state.pins.filter { pinIds.contains($0.id) && !$0.isFolder }
            let removedIds = Set(removed.map { $0.id })
            var next = state
            next.pins = state.pins.filter { !removedIds.contains($0.id) }
            next.tabTitles = state.titlesKept(from: removed)
            next.tabSyncIds = state.syncIdsKept(from: removed)
            return next
        }
    }

    /// Gives pinned tab `pinId` the title `name`, or shows its page title again when `name` is blank.
    func renamePin(_ pinId: String, name: String) {
        mutate { state in
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            var next = state
            next.pins = state.pins.map { pin in
                guard pin.id == pinId, !pin.isFolder else { return pin }
                var updated = pin
                if trimmed.isEmpty {
                    updated.staticLabel = false
                } else {
                    updated.title = trimmed
                    updated.staticLabel = true
                }
                updated.updatedAt = nowMillis()
                return updated
            }
            return next
        }
    }

    /// Keeps the titles of pinned tabs up to date with their pinned pages, so closed pins show their last title. Only a
    /// tab showing its pinned address updates the title, not the pages it went on to. Titles the user gave are kept, as
    /// are titles that are only an address.
    ///
    /// - Parameter pages: The address and title of every open tab, by tab id.
    func refreshPinTitles(_ pages: [String: (url: String, title: String)]) {
        mutate { state in
            var changed = false
            let pins = state.pins.map { pin -> PinnedItem in
                guard let tabId = pin.tabId, let page = pages[tabId], let pinUrl = pin.url else { return pin }
                let title = page.title.trimmingCharacters(in: .whitespacesAndNewlines)
                let onPinnedPage = trimmingSlashes(page.url) == trimmingSlashes(pinUrl)
                if pin.staticLabel || !onPinnedPage || title.isEmpty || title == pin.title || title.hasPrefix("http") {
                    return pin
                }
                changed = true
                var updated = pin
                updated.title = title
                updated.updatedAt = nowMillis()
                return updated
            }
            guard changed else { return state }
            var next = state
            next.pins = pins
            return next
        }
    }

    /// Makes `url` the address pinned tab `pinId` resets to, like Zen's "Replace pinned URL with current".
    func replacePinUrl(_ pinId: String, url: String, title: String) {
        mutate { state in
            var next = state
            next.pins = state.pins.map { pin in
                guard pin.id == pinId, !pin.isFolder else { return pin }
                var updated = pin
                updated.url = url
                if !pin.staticLabel {
                    let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
                    updated.title = trimmed.isEmpty ? url : title
                }
                updated.updatedAt = nowMillis()
                return updated
            }
            return next
        }
    }

    /// Gives unpinned tab `tabId` the title `name`, or shows its page title again when `name` is blank.
    func renameTab(_ tabId: String, name: String) {
        mutate { state in
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            var next = state
            if trimmed.isEmpty {
                next.tabTitles.removeValue(forKey: tabId)
            } else {
                next.tabTitles[tabId] = trimmed
            }
            return next
        }
    }

    /// Links a reopened browser tab to its pin and keeps it in the pin's workspace.
    func attachPinned(_ pinId: String, tabId: String) {
        mutate { state in
            guard let pin = state.pins.first(where: { $0.id == pinId }) else { return state }
            var next = state
            next.pins = state.pins.map { item in
                guard item.id == pinId else { return item }
                var updated = item
                updated.tabId = tabId
                return updated
            }
            next.assignments[tabId] = pin.workspaceId ?? state.activeWorkspaceId
            return next
        }
    }

    /// Creates a folder in `workspaceId` below `parentId`, then moves the pinned items `itemIds` into it and pins
    /// `newPins` inside it.
    func createFolder(
        workspaceId: String, parentId: String?, name: String, itemIds: Set<String> = [], newPins: [PinSource] = []
    ) {
        mutate { state in
            if state.folderDepth(parentId) >= maxFolderDepth {
                return state
            }
            let now = nowMillis()
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            let folder = PinnedItem(
                id: newId(),
                workspaceId: workspaceId,
                parentId: parentId,
                kind: .folder,
                title: trimmed.isEmpty ? defaultName : trimmed,
                createdAt: now,
                updatedAt: now)
            var next = state
            next.pins.append(folder)
            return next.moved(itemIds, into: folder.id).withPins(newPins, parentId: folder.id)
        }
    }

    func renameFolder(_ folderId: String, name: String) {
        mutate { state in
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return state }
            var next = state
            next.pins = state.pins.map { pin in
                guard pin.id == folderId else { return pin }
                var updated = pin
                updated.title = trimmed
                updated.updatedAt = nowMillis()
                return updated
            }
            return next
        }
    }

    func toggleFolder(_ folderId: String) {
        mutate { state in
            var next = state
            next.pins = state.pins.map { pin in
                guard pin.id == folderId else { return pin }
                var updated = pin
                updated.collapsed.toggle()
                return updated
            }
            return next
        }
    }

    /// Removes a folder and moves its content one level up, to where the folder was, like Zen's "Unpack Folder".
    func unpackFolder(_ folderId: String) {
        mutate { state in
            guard let folder = state.pins.first(where: { $0.id == folderId && $0.isFolder }) else { return state }
            let now = nowMillis()
            let children = state.pins.filter { $0.parentId == folderId }.map { child -> PinnedItem in
                var updated = child
                updated.parentId = folder.parentId
                updated.updatedAt = now
                return updated
            }
            let childIds = Set(children.map { $0.id })
            var next = state
            next.pins = state.pins.filter { !childIds.contains($0.id) }.flatMap { pin in
                pin.id == folderId ? children : [pin]
            }
            return next
        }
    }

    /// Moves unpinned tabs and pinned items (with everything inside folders) to another workspace.
    func moveToWorkspace(tabIds: Set<String>, itemIds: Set<String>, workspaceId: String) {
        mutate { state in
            guard state.workspaces.contains(where: { $0.id == workspaceId }) else { return state }
            var nested = Set<String>()
            for id in itemIds {
                nested.formUnion(state.descendantIds(id))
            }
            let movedPins = itemIds.union(nested)
            let movedTabs = tabIds.union(state.pins.filter { movedPins.contains($0.id) }.compactMap { $0.tabId })
            let now = nowMillis()
            var next = state
            next.pins = state.pins.map { pin in
                var updated = pin
                if itemIds.contains(pin.id) {
                    updated.workspaceId = workspaceId
                    updated.parentId = nil
                    updated.essential = false
                    updated.updatedAt = now
                } else if nested.contains(pin.id) {
                    updated.workspaceId = workspaceId
                    updated.updatedAt = now
                }
                return updated
            }
            for tabId in movedTabs {
                next.assignments[tabId] = workspaceId
            }
            return next
        }
    }

    /// Assigns tabs that have no workspace yet to the active workspace, and forgets tabs that no longer exist once
    /// the browser state has been restored. Pins whose tab was closed stay pinned without a tab.
    func syncWithTabs(_ tabIds: Set<String>, restoreComplete: Bool) {
        expectedTabs.subtract(tabIds)
        let present = tabIds.union(expectedTabs)
        mutate { state in state.withTabs(present, restoreComplete: restoreComplete) }
    }

    /// Applies `transform` as one change and returns the second value of its result.
    func update<T>(_ transform: (WorkspaceState) -> (WorkspaceState, T)) -> T {
        let (next, result) = transform(state)
        if next != state {
            state = next
            save()
        }
        return result
    }

    private func mutate(_ transform: (WorkspaceState) -> WorkspaceState) {
        let next = transform(state)
        if next != state {
            state = next
            save()
        }
    }

    private func save() {
        DataFiles.writeObject(WorkspaceSerializer.write(state), to: Self.fileName)
    }

    private func trimmingSlashes(_ url: String) -> String {
        var result = url
        while result.hasSuffix("/") {
            result.removeLast()
        }
        return result
    }
}

extension WorkspaceState {
    fileprivate func withTabs(_ tabIds: Set<String>, restoreComplete: Bool) -> WorkspaceState {
        var next = self
        if restoreComplete {
            next.assignments = assignments.filter { tabIds.contains($0.key) }
            next.pins = pins.map { pin in
                guard let tabId = pin.tabId, !tabIds.contains(tabId) else { return pin }
                var updated = pin
                updated.tabId = nil
                return updated
            }
            next.splits = splits.filter { split in split.tabIds.allSatisfy { tabIds.contains($0) } }
            next.tabTitles = tabTitles.filter { tabIds.contains($0.key) }
            next.tabSyncIds = tabSyncIds.filter { tabIds.contains($0.key) }
        }
        for tabId in tabIds where next.assignments[tabId] == nil {
            next.assignments[tabId] = activeWorkspaceId
        }
        return next
    }

    fileprivate func withPins(_ sources: [PinSource], parentId: String?) -> WorkspaceState {
        let now = nowMillis()
        let alreadyPinned = Set(pins.compactMap { $0.tabId })
        let parent = parentId.flatMap { id in pins.first { $0.id == id && $0.isFolder } }
        let added = sources.filter { !alreadyPinned.contains($0.tabId) }.uniqued { $0.tabId }.map { source in
            newPin(
                source, workspaceId: parent?.workspaceId ?? workspaceOf(source.tabId), parentId: parent?.id,
                essential: false, now: now)
        }
        var next = self
        next.pins = pins + added
        for pin in added {
            guard let tabId = pin.tabId else { continue }
            next.assignments[tabId] = pin.workspaceId ?? activeWorkspaceId
            next.tabTitles.removeValue(forKey: tabId)
            next.tabSyncIds.removeValue(forKey: tabId)
        }
        return next
    }

    /// A pinned tab for `source`. A title the user gave the tab becomes the pin's static label.
    fileprivate func newPin(
        _ source: PinSource, workspaceId: String?, parentId: String?, essential: Bool, now: Int64
    ) -> PinnedItem {
        let customTitle = tabTitles[source.tabId]
        // A pin takes over the id its tab synced under, so other devices pin that tab instead of opening another.
        let syncId = syncIdOf(source.tabId)
        let id = pins.contains { $0.id == syncId } ? newId() : syncId
        let pageTitle = source.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? source.url : source.title
        return PinnedItem(
            id: id,
            workspaceId: workspaceId,
            parentId: parentId,
            kind: .tab,
            title: customTitle ?? pageTitle,
            url: source.url,
            containerId: source.containerId,
            essential: essential,
            staticLabel: customTitle != nil,
            tabId: source.tabId,
            createdAt: now,
            updatedAt: now)
    }

    /// Titles the user gave to `removed` pins, kept for their open tabs.
    fileprivate func titlesKept(from removed: [PinnedItem]) -> [String: String] {
        var titles = tabTitles
        for pin in removed where pin.staticLabel {
            if let tabId = pin.tabId {
                titles[tabId] = pin.title
            }
        }
        return titles
    }

    /// The ids of `removed` pins, which their open tabs sync under from now on.
    fileprivate func syncIdsKept(from removed: [PinnedItem]) -> [String: String] {
        var syncIds = tabSyncIds
        for pin in removed {
            if let tabId = pin.tabId, tabId != pin.id {
                syncIds[tabId] = pin.id
            }
        }
        return syncIds
    }

    fileprivate func moved(_ itemIds: Set<String>, into folderId: String?) -> WorkspaceState {
        let folder = folderId.flatMap { id in pins.first { $0.id == id && $0.isFolder } }
        if folderId != nil && folder == nil {
            return self
        }
        var blocked = Set<String>()
        if let folder {
            for id in itemIds {
                let item = pins.first { $0.id == id }
                if folder.id == id || descendantIds(id).contains(folder.id)
                    || (item?.isFolder == true && folderDepth(folder.id) + folderHeight(id) > maxFolderDepth)
                {
                    blocked.insert(id)
                }
            }
        }
        let moving = pins.filter { itemIds.contains($0.id) && !blocked.contains($0.id) }
        if moving.isEmpty {
            return self
        }
        let now = nowMillis()
        let workspaceId = folder?.workspaceId
        let movingIds = Set(moving.map { $0.id })
        var nested = Set<String>()
        for id in movingIds {
            nested.formUnion(descendantIds(id))
        }
        let updated = moving.map { item -> PinnedItem in
            var copy = item
            copy.parentId = folderId
            copy.workspaceId = workspaceId ?? item.workspaceId
            copy.essential = false
            copy.updatedAt = now
            return copy
        }
        let rest = pins.filter { !movingIds.contains($0.id) }.map { item -> PinnedItem in
            guard let workspaceId, nested.contains(item.id), item.workspaceId != workspaceId else { return item }
            var copy = item
            copy.workspaceId = workspaceId
            return copy
        }
        var next = self
        next.pins = rest + updated
        if let workspaceId {
            let movedTabs = (updated + rest.filter { nested.contains($0.id) }).compactMap { $0.tabId }
            for tabId in movedTabs {
                next.assignments[tabId] = workspaceId
            }
        }
        return next
    }
}
