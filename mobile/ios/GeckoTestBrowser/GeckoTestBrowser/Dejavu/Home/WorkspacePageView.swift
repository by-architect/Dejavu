// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

private let headerKey = "header"
private let dividerKey = "divider"
private let newTabKey = "new_tab"
private let pinPrefix = "pin:"
private let tabPrefix = "tab:"

/// Frames of the rows of a workspace page, by row key, in the coordinates of the page.
private struct RowFramesKey: PreferenceKey {
    static let defaultValue: [String: CGRect] = [:]

    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, new in new })
    }
}

/// What is being dragged on a workspace page and where it would land.
///
/// - `label`: Text of the floating row that follows the finger.
/// - `start`: Where the drag started, once the finger moved after the long press.
/// - `moved`: Whether the finger moved enough to be a drag rather than a long press.
/// - `target`: Where the items would be dropped, or `nil` when that position is not allowed.
private struct PageDrag {
    var selection: Selection
    var label: String
    var isFolder: Bool
    var location: CGPoint
    var start: CGPoint?
    var moved = false
    var target: DropTarget?
}

/// Where a drop line is drawn on a row.
private enum DropLine {
    case top
    case bottom
    case inside
}

/// A workspace of the home screen: its header, its pinned tabs and folders, the line with Clear, New Tab, then its
/// other tabs. Long-pressing a row selects it; keeping the finger down and moving drags the selection to a folder,
/// between pinned items, to the unpinned tabs, or up into the essentials.
struct WorkspacePageView: View {
    let workspace: Workspace
    let canMoveLeft: Bool
    let canMoveRight: Bool
    let canDelete: Bool
    @Binding var selection: Selection?
    @Binding var essentialsDrop: EssentialsDrop
    let onRowAction: (RowAction, ActionTargets) -> Void
    let onSheet: (HomeSheet) -> Void
    let onAlert: (HomeAlert) -> Void

    @ObservedObject private var app = AppModel.shared
    @ObservedObject private var store = BrowserStore.shared
    @ObservedObject private var repository = WorkspaceRepository.shared
    @ObservedObject private var settings = DejavuSettings.shared
    @ObservedObject private var containers = ContainerStore.shared

    @State private var drag: PageDrag?
    @State private var rowFrames: [String: CGRect] = [:]

    private var space: String {
        "page-\(workspace.id)"
    }

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                headerRow
                ForEach(repository.state.pinnedTree(workspace.id), id: \.item.id) { entry in
                    pinnedRow(entry)
                }
                dividerRow
                newTabRow
                ForEach(otherTabs) { tab in
                    tabRow(tab)
                }
            }
            .padding(.horizontal, 8)
            .padding(.bottom, 24)
        }
        .scrollDisabled(drag != nil)
        .coordinateSpace(.named(space))
        .onPreferenceChange(RowFramesKey.self) { frames in
            rowFrames = frames
        }
        .overlay(alignment: .topLeading) {
            ghost
        }
    }

    // MARK: - Content

    private var tabsById: [String: TabInfo] {
        Dictionary(store.normalTabs.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    }

    /// The tabs of this workspace that are not pinned, in the order of the tab list.
    private var otherTabs: [TabInfo] {
        let state = repository.state
        let pinnedTabIds = Set(state.pins.compactMap { $0.tabId })
        return store.normalTabs.filter { state.workspaceOf($0.id) == workspace.id && !pinnedTabIds.contains($0.id) }
    }

    private var splitTabIds: Set<String> {
        Set(repository.state.splits.flatMap { $0.tabIds })
    }

    private var headerRow: some View {
        let index = repository.state.workspaces.firstIndex { $0.id == workspace.id } ?? 0
        return WorkspaceHeaderView(
            workspace: workspace,
            container: containers.record(workspace.containerId),
            canDelete: canDelete,
            canMoveLeft: canMoveLeft,
            canMoveRight: canMoveRight,
            onNewFolder: {
                onAlert(.newFolder(workspaceId: workspace.id, parentId: nil, targets: ActionTargets()))
            },
            onNewWorkspace: { onSheet(.editWorkspace(nil)) },
            onEdit: { onSheet(.editWorkspace(workspace.id)) },
            onDelete: { onAlert(.deleteWorkspace(workspace.id)) },
            onMove: { delta in repository.moveWorkspace(workspace.id, to: index + delta) })
        .rowFrame(headerKey, space: space)
        .dropLine(lineFor(headerKey))
    }

    @ViewBuilder
    private func pinnedRow(_ entry: PinnedEntry) -> some View {
        let item = entry.item
        let key = pinPrefix + item.id
        if item.isFolder {
            FolderRowView(
                folder: item,
                depth: entry.depth,
                childCount: repository.state.pins.filter { $0.parentId == item.id }.count,
                selected: selection.map { $0.folderIds.contains(item.id) })
            .onTapGesture { tapFolder(item) }
            .gesture(dragGesture(key: key, picked: Selection(folderIds: [item.id]), label: item.title, isFolder: true))
            .rowFrame(key, space: space)
            .dropLine(lineFor(key))
            .opacity(isDragged(key) ? 0.35 : 1)
        } else {
            pinRow(item, depth: entry.depth, key: key)
        }
    }

    private func pinRow(_ item: PinnedItem, depth: Int, key: String) -> some View {
        let tab = item.tabId.flatMap { tabsById[$0] }
        let targets = ActionTargets.ofPin(item, tab: tab)
        let canReset = TabAction.resetPin.applies(to: targets)
        return TabRowView(
            title: item.label(tab),
            url: tab?.url ?? item.url ?? "",
            depth: depth,
            container: containers.record(tab?.contextId ?? item.containerId),
            isAwake: tab?.isAwake == true,
            isCurrent: tab != nil && tab?.id == store.selectedTabId,
            isSplit: tab.map { splitTabIds.contains($0.id) } ?? false,
            selected: selection.map { $0.pinIds.contains(item.id) },
            actions: rowActions(settings.rowActions(pinned: true), targets: targets, isPinned: true),
            onIconTap: canReset ? { onRowAction(.builtIn(.resetPin), targets) } : nil,
            onAction: { action in onRowAction(action, targets) })
        .onTapGesture { tapPin(item) }
        .gesture(dragGesture(key: key, picked: Selection(pinIds: [item.id]), label: item.title, isFolder: false))
        .rowFrame(key, space: space)
        .dropLine(lineFor(key))
        .opacity(isDragged(key) ? 0.35 : 1)
    }

    private var dividerRow: some View {
        SectionDividerView(
            showClear: !otherTabs.isEmpty && selection == nil,
            onClear: { app.clearUnpinned(workspace.id) })
        .rowFrame(dividerKey, space: space)
        .dropLine(lineFor(dividerKey))
    }

    private var newTabRow: some View {
        NewTabRowView(enabled: selection == nil)
            .rowFrame(newTabKey, space: space)
            .dropLine(lineFor(newTabKey))
    }

    private func tabRow(_ tab: TabInfo) -> some View {
        let key = tabPrefix + tab.id
        let targets = ActionTargets(tabs: [tab])
        return TabRowView(
            title: repository.state.titleOf(tab),
            url: tab.url,
            container: containers.record(tab.contextId),
            isAwake: tab.isAwake,
            isCurrent: tab.id == store.selectedTabId,
            isSplit: splitTabIds.contains(tab.id),
            selected: selection.map { $0.tabIds.contains(tab.id) },
            actions: rowActions(settings.rowActions(pinned: false), targets: targets, isPinned: false),
            onAction: { action in onRowAction(action, targets) })
        .onTapGesture { tapTab(tab) }
        .gesture(dragGesture(key: key, picked: Selection(tabIds: [tab.id]), label: tab.displayTitle, isFolder: false))
        .rowFrame(key, space: space)
        .dropLine(lineFor(key))
        .opacity(isDragged(key) ? 0.35 : 1)
    }

    // MARK: - Taps

    private func tapTab(_ tab: TabInfo) {
        guard drag == nil else { return }
        if let current = selection {
            let next = current.togglingTab(tab.id)
            selection = next.isEmpty ? nil : next
        } else {
            app.openTab(tab.id)
        }
    }

    private func tapPin(_ pin: PinnedItem) {
        guard drag == nil else { return }
        if let current = selection {
            let next = current.togglingPin(pin.id)
            selection = next.isEmpty ? nil : next
        } else {
            app.openPin(pin)
        }
    }

    private func tapFolder(_ folder: PinnedItem) {
        guard drag == nil else { return }
        if let current = selection {
            let next = current.togglingFolder(folder.id)
            selection = next.isEmpty ? nil : next
        } else {
            repository.toggleFolder(folder.id)
        }
    }

    // MARK: - Dragging

    /// A long press selects the row; keeping the finger down and moving drags it with the rest of the selection.
    private func dragGesture(key: String, picked: Selection, label: String, isFolder: Bool) -> some Gesture {
        LongPressGesture(minimumDuration: 0.35)
            .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .named(space)))
            .onChanged { value in
                guard case .second(true, let dragValue) = value else { return }
                if drag == nil {
                    startDrag(key: key, picked: picked, label: label, isFolder: isFolder)
                }
                if let dragValue {
                    moveDrag(to: dragValue.location)
                }
            }
            .onEnded { _ in
                endDrag()
            }
    }

    private func startDrag(key: String, picked: Selection, label: String, isFolder: Bool) {
        let started = (selection ?? Selection()) + picked
        selection = started
        let location = rowFrames[key].map { CGPoint(x: $0.midX, y: $0.midY) } ?? .zero
        let text = started.size > 1 ? "\(label)  +\(started.size - 1)" : label
        drag = PageDrag(selection: started, label: text, isFolder: isFolder, location: location)
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }

    private func moveDrag(to location: CGPoint) {
        guard var current = drag else { return }
        current.location = location
        if current.start == nil {
            current.start = location
        }
        if !current.moved, let start = current.start, hypot(location.x - start.x, location.y - start.y) > 10 {
            current.moved = true
        }
        current.target = current.moved ? target(at: location, drag: current) : nil
        drag = current
        if !current.moved || !current.selection.folderIds.isEmpty {
            essentialsDrop = .none
        } else {
            essentialsDrop = current.target == .essentials ? .active : .available
        }
    }

    private func endDrag() {
        if let current = drag, current.moved, let target = current.target {
            app.drop(current.selection, on: target, workspaceId: workspace.id)
            selection = nil
        }
        drag = nil
        essentialsDrop = .none
    }

    /// Where the dragged items would land at `point`, or `nil` when they cannot go there. Above the page lie the
    /// essentials, which take tabs but no folders.
    private func target(at point: CGPoint, drag: PageDrag) -> DropTarget? {
        let movingFolders = drag.selection.folderIds
        if point.y < 0 {
            return movingFolders.isEmpty ? .essentials : nil
        }
        let rows = rowFrames.sorted { $0.value.minY < $1.value.minY }
        guard let first = rows.first, let last = rows.last else { return nil }
        let hit = rows.first { point.y >= $0.value.minY && point.y < $0.value.maxY }
            ?? (point.y < first.value.minY ? first : last)
        let frame = hit.value
        let fraction = Double((point.y - frame.minY) / max(frame.height, 1)).clamped(0, 1)
        let key = hit.key
        let state = repository.state
        var blocked = drag.selection.pinIds.union(movingFolders)
        for folderId in movingFolders {
            blocked.formUnion(state.descendantIds(folderId))
        }

        let target: DropTarget
        if key == headerKey {
            target = .pinnedEdge(atEnd: false)
        } else if key == dividerKey {
            target = .pinnedEdge(atEnd: true)
        } else if key == newTabKey {
            target = .unpinnedStart
        } else if key.hasPrefix(pinPrefix) {
            let id = String(key.dropFirst(pinPrefix.count))
            guard let item = state.pins.first(where: { $0.id == id }), !blocked.contains(id) else { return nil }
            if item.isFolder && fraction >= 0.25 && fraction <= 0.75 {
                target = .intoFolder(id)
            } else {
                target = .nextToPin(id, after: fraction >= 0.5)
            }
        } else if key.hasPrefix(tabPrefix) {
            let id = String(key.dropFirst(tabPrefix.count))
            if drag.selection.tabIds.contains(id) {
                return nil
            }
            target = .nextToTab(id, after: fraction >= 0.5)
        } else {
            return nil
        }
        // Folders cannot be unpinned.
        if !movingFolders.isEmpty {
            switch target {
            case .nextToTab, .unpinnedStart:
                return nil
            default:
                break
            }
        }
        return target
    }

    private func lineFor(_ key: String) -> DropLine? {
        guard let drag, drag.moved, let target = drag.target else { return nil }
        switch target {
        case .intoFolder(let id):
            return key == pinPrefix + id ? .inside : nil
        case .nextToPin(let id, let after):
            return key == pinPrefix + id ? (after ? .bottom : .top) : nil
        case .pinnedEdge(let atEnd):
            if atEnd {
                return key == dividerKey ? .top : nil
            }
            return key == headerKey ? .bottom : nil
        case .nextToTab(let id, let after):
            return key == tabPrefix + id ? (after ? .bottom : .top) : nil
        case .unpinnedStart:
            return key == newTabKey ? .bottom : nil
        case .essentials:
            return nil
        }
    }

    /// Whether the row `key` is among what is being dragged, folders with everything inside them.
    private func isDragged(_ key: String) -> Bool {
        guard let drag, drag.moved else { return false }
        if key.hasPrefix(tabPrefix) {
            return drag.selection.tabIds.contains(String(key.dropFirst(tabPrefix.count)))
        }
        guard key.hasPrefix(pinPrefix) else { return false }
        let id = String(key.dropFirst(pinPrefix.count))
        if drag.selection.pinIds.contains(id) || drag.selection.folderIds.contains(id) {
            return true
        }
        return drag.selection.folderIds.contains { repository.state.descendantIds($0).contains(id) }
    }

    @ViewBuilder
    private var ghost: some View {
        if let drag, drag.moved {
            HStack(spacing: 10) {
                Image(systemName: drag.isFolder ? "folder" : "square.on.square")
                    .font(.system(size: 15))
                Text(drag.label)
                    .font(.subheadline)
                    .lineLimit(1)
            }
            .foregroundStyle(DejavuColor.onSurface)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 14)
                    .fill(DejavuColor.surfaceContainerHighest)
                    .shadow(color: .black.opacity(0.25), radius: 8, y: 2))
            .frame(maxWidth: 280, alignment: .leading)
            .offset(x: 32, y: drag.location.y - 22)
            .allowsHitTesting(false)
        }
    }
}

extension View {
    /// Reports the frame of a row of a workspace page, by its key, in the coordinates of the page.
    fileprivate func rowFrame(_ key: String, space: String) -> some View {
        background(
            GeometryReader { proxy in
                Color.clear.preference(key: RowFramesKey.self, value: [key: proxy.frame(in: .named(space))])
            })
    }

    /// Shows where dragged items would be dropped: a line above or below the row, or the row lit up for a folder.
    fileprivate func dropLine(_ line: DropLine?) -> some View {
        self
            .background(
                RoundedRectangle(cornerRadius: 16)
                    .fill(line == .inside ? DejavuColor.primaryContainer : Color.clear))
            .overlay(alignment: line == .top ? .top : .bottom) {
                if line == .top || line == .bottom {
                    Rectangle()
                        .fill(DejavuColor.primary)
                        .frame(height: 3)
                }
            }
    }
}

/// The private tabs, shown as a workspace of their own while any are open. It cannot hold pinned tabs, folders or
/// essentials, and is not saved: it goes away with its last tab.
struct PrivateWorkspacePage: View {
    @ObservedObject private var app = AppModel.shared
    @ObservedObject private var store = BrowserStore.shared

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                header
                Rectangle()
                    .fill(DejavuColor.outlineVariant)
                    .frame(height: 1)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 4)
                Button {
                    app.newPrivateTab()
                } label: {
                    HStack(spacing: 0) {
                        Image(systemName: "plus")
                            .frame(width: 20)
                            .padding(.leading, 15)
                        Spacer().frame(width: 19)
                        Text(L10n.newPrivateTab)
                        Spacer(minLength: 0)
                    }
                    .foregroundStyle(DejavuColor.onSurfaceVariant)
                    .frame(height: 48)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                ForEach(store.privateTabs) { tab in
                    TabRowView(
                        title: tab.displayTitle,
                        url: tab.url,
                        container: nil,
                        isAwake: tab.isAwake,
                        isCurrent: tab.id == store.selectedTabId,
                        selected: nil,
                        actions: [.builtIn(.close)],
                        onAction: { _ in app.closeTab(tab.id) })
                    .onTapGesture { app.openTab(tab.id) }
                }
            }
            .padding(.horizontal, 8)
            .padding(.bottom, 24)
        }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Image(systemName: "eyeglasses")
                .foregroundStyle(DejavuColor.onSurfaceVariant)
            Text(L10n.privateWorkspace)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(DejavuColor.onSurfaceVariant)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(role: .destructive) {
                app.closePrivateTabs()
            } label: {
                Label(L10n.privateCloseAll, systemImage: "trash")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(DejavuColor.error)
            }
            .buttonStyle(.plain)
            .padding(.trailing, 8)
        }
        .padding(.leading, 12)
        .frame(height: 52)
    }
}
