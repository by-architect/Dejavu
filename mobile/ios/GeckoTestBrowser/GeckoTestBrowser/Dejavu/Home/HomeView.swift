// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

/// Key of the private workspace page. Workspace ids are braced UUIDs, so it cannot clash with one.
let privatePageId = "private"

/// Sheets of the home screen.
enum HomeSheet: Identifiable {
    case editWorkspace(String?)
    case moveToFolder(workspaceId: String, targets: ActionTargets)
    case moveToWorkspace(workspaceId: String, targets: ActionTargets)
    case changeContainer(ActionTargets)

    var id: String {
        switch self {
        case .editWorkspace(let id): return "edit-\(id ?? "new")"
        case .moveToFolder: return "folder"
        case .moveToWorkspace: return "workspace"
        case .changeContainer: return "container"
        }
    }
}

/// Questions of the home screen, asked in alerts.
enum HomeAlert {
    case deleteWorkspace(String)
    case newFolder(workspaceId: String, parentId: String?, targets: ActionTargets)
    case renameFolder(PinnedItem)

    /// Renames pinned tab `pinId`, or unpinned tab `tabId`, currently titled `title`.
    case renameTab(pinId: String?, tabId: String?, title: String)
    case deleteItems(ActionTargets)
}

/// Whether a drag on a workspace page can be dropped into the essentials, and whether it would be now.
enum EssentialsDrop {
    case none
    case available
    case active
}

/// Workspace based home screen. The essentials sit on top of every workspace. Each workspace is a page with its pinned
/// tabs and folders, then its other tabs; swiping horizontally switches workspace, blending their themes. Long-pressing
/// a tab or folder starts selection mode; keeping the finger down and moving drags the selection.
struct HomeView: View {
    @ObservedObject private var app = AppModel.shared
    @ObservedObject private var store = BrowserStore.shared
    @ObservedObject private var repository = WorkspaceRepository.shared
    @ObservedObject private var settings = DejavuSettings.shared
    @ObservedObject private var containers = ContainerStore.shared

    @State private var selection: Selection?
    @State private var sheet: HomeSheet?
    @State private var alert: HomeAlert?
    @State private var alertText = ""
    @State private var pageId: String?
    @State private var pagePosition: Double = 0
    @State private var essentialsDrop = EssentialsDrop.none

    var body: some View {
        let pages = pageIds
        VStack(spacing: 0) {
            topArea
            essentialsArea
            pager(pages)
            bottomArea
        }
        .background(background(pages))
        .onAppear { showInitialPage() }
        .onChange(of: pageId) { _, id in
            if let id, id != privatePageId {
                repository.selectWorkspace(id)
            }
        }
        .onChange(of: repository.state.activeWorkspaceId) { _, id in
            // Follows workspace changes made elsewhere, like a new workspace or a link opened in another one.
            if pageId != id && pageId != privatePageId {
                withAnimation { pageId = id }
            }
        }
        .onChange(of: pages) { _, newPages in
            if let pageId, !newPages.contains(pageId) {
                self.pageId = repository.state.activeWorkspaceId
            }
        }
        .onChange(of: selectionInputs) { _, _ in cleanSelection() }
        .sheet(item: $sheet) { sheet in
            HomeSheetContent(sheet: sheet)
        }
        .alert(alertTitle, isPresented: alertShown) {
            alertActions
        } message: {
            alertMessage
        }
    }

    // MARK: - Pages

    private var pageIds: [String] {
        repository.state.workspaces.map { $0.id } + (store.privateTabs.isEmpty ? [] : [privatePageId])
    }

    private var currentWorkspace: Workspace {
        let state = repository.state
        if let pageId, let workspace = state.workspace(pageId) {
            return workspace
        }
        return state.activeWorkspace ?? state.workspaces[0]
    }

    private var onPrivatePage: Bool {
        pageId == privatePageId
    }

    private func showInitialPage() {
        let selectedIsPrivate = store.selectedTab?.isPrivate == true
        pageId = selectedIsPrivate && !store.privateTabs.isEmpty ? privatePageId : repository.state.activeWorkspaceId
    }

    private func theme(at index: Int, pages: [String]) -> WorkspaceTheme? {
        guard index >= 0, index < pages.count else { return nil }
        if pages[index] == privatePageId {
            return privateWorkspaceTheme
        }
        return repository.state.workspace(pages[index])?.theme
    }

    private func background(_ pages: [String]) -> some View {
        let page = Int(pagePosition.rounded(.down))
        return ZStack {
            DejavuColor.surface
            WorkspaceBackground(
                theme: theme(at: page, pages: pages), next: theme(at: page + 1, pages: pages),
                fraction: pagePosition - Double(page))
        }
        .ignoresSafeArea()
    }

    private func pager(_ pages: [String]) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 0) {
                ForEach(pages, id: \.self) { id in
                    page(id)
                        .containerRelativeFrame(.horizontal)
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: $pageId)
        .scrollDisabled(selection != nil)
        .onScrollGeometryChange(for: Double.self) { geometry in
            geometry.contentOffset.x / max(geometry.containerSize.width, 1)
        } action: { _, position in
            pagePosition = position
        }
    }

    @ViewBuilder
    private func page(_ id: String) -> some View {
        let state = repository.state
        if id == privatePageId {
            PrivateWorkspacePage()
        } else if let index = state.workspaces.firstIndex(where: { $0.id == id }) {
            WorkspacePageView(
                workspace: state.workspaces[index],
                canMoveLeft: index > 0,
                canMoveRight: index < state.workspaces.count - 1,
                canDelete: state.workspaces.count > 1,
                selection: $selection,
                essentialsDrop: $essentialsDrop,
                onRowAction: { action, targets in runAction(action, targets: targets, workspaceId: id) },
                onSheet: { sheet = $0 },
                onAlert: { show($0) })
        }
    }

    // MARK: - Bars

    @ViewBuilder
    private var topArea: some View {
        if let selection {
            if essentialsDrop != .none {
                EssentialsDropBar(active: essentialsDrop == .active)
            } else {
                SelectionTopBar(count: selection.size, onClose: { self.selection = nil }, onSelectAll: selectAll)
            }
        } else {
            HomeTopBar(onSearch: { onPrivatePage ? app.newPrivateTab() : app.startSearch() })
        }
    }

    @ViewBuilder
    private var essentialsArea: some View {
        let essentials = onPrivatePage
            ? []
            : repository.state.essentialsShown(
                containerId: currentWorkspace.containerId, perContainer: settings.essentialsPerContainer)
        if !essentials.isEmpty {
            EssentialsGridView(
                essentials: essentials,
                selection: $selection,
                isDropTarget: essentialsDrop == .active,
                onTap: tapPin,
                onMove: { pinId, index in
                    app.moveEssential(pinId, to: index)
                    selection = nil
                })
            .padding(.bottom, 4)
        }
    }

    @ViewBuilder
    private var bottomArea: some View {
        if let selection {
            let targets = ActionTargets.of(repository.state, tabs: store.normalTabs, selection: selection)
            SelectionBar(actions: settings.selectionActions.filter { $0.applies(to: targets) }) { action in
                runAction(action, targets: targets, workspaceId: currentWorkspace.id)
            }
        } else {
            WorkspaceBar(
                pageId: pageId,
                hasPrivate: !store.privateTabs.isEmpty,
                onSelect: { id in withAnimation { pageId = id } })
        }
    }

    // MARK: - Selection

    private func tapPin(_ pin: PinnedItem) {
        if let current = selection {
            let next = current.togglingPin(pin.id)
            selection = next.isEmpty ? nil : next
        } else {
            app.openPin(pin)
        }
    }

    private func selectAll() {
        let state = repository.state
        let workspaceId = currentWorkspace.id
        selection = Selection(
            tabIds: Set(
                store.normalTabs.filter { state.workspaceOf($0.id) == workspaceId && state.pinOf($0.id) == nil }
                    .map { $0.id }),
            pinIds: Set(state.pins.filter { $0.workspaceId == workspaceId && !$0.isFolder }.map { $0.id }))
    }

    /// What the selection is checked against: the open tabs and the pinned items.
    private var selectionInputs: [String] {
        store.tabs.map { $0.id } + repository.state.pins.map { $0.id }
    }

    /// Drops what no longer exists from the selection, and leaves selection mode when nothing is left.
    private func cleanSelection() {
        guard let current = selection else { return }
        let tabIds = Set(store.tabs.map { $0.id })
        let pinIds = Set(repository.state.pins.filter { !$0.isFolder }.map { $0.id })
        let folderIds = Set(repository.state.pins.filter { $0.isFolder }.map { $0.id })
        let cleaned = Selection(
            tabIds: current.tabIds.intersection(tabIds),
            pinIds: current.pinIds.intersection(pinIds),
            folderIds: current.folderIds.intersection(folderIds))
        if cleaned != current {
            selection = cleaned.isEmpty ? nil : cleaned
        }
    }

    // MARK: - Actions

    private func runAction(_ action: RowAction, targets: ActionTargets, workspaceId: String) {
        switch action {
        case .custom(let custom):
            app.runCustomAction(custom, targets: targets)
        case .builtIn(let builtIn):
            runBuiltIn(builtIn, targets: targets, workspaceId: workspaceId)
        }
        selection = nil
    }

    private func runBuiltIn(_ action: TabAction, targets: ActionTargets, workspaceId: String) {
        let state = repository.state
        switch action {
        case .moveToWorkspace:
            sheet = .moveToWorkspace(workspaceId: workspaceId, targets: targets)
        case .moveToFolder:
            sheet = .moveToFolder(workspaceId: workspaceId, targets: targets)
        case .newFolder:
            show(.newFolder(workspaceId: workspaceId, parentId: state.newFolderParent(targets), targets: targets))
        case .newSubfolder:
            if targets.folders.count == 1 {
                show(.newFolder(workspaceId: workspaceId, parentId: targets.folders[0].id, targets: ActionTargets()))
            }
        case .renameFolder:
            if targets.folders.count == 1 {
                show(.renameFolder(targets.folders[0]))
            }
        case .renameTab:
            if targets.pins.count == 1 {
                let pin = targets.pins[0]
                show(.renameTab(pinId: pin.id, tabId: nil, title: pin.label(targets.pinnedTabs.first)))
            } else if targets.tabs.count == 1 {
                let tab = targets.tabs[0]
                show(.renameTab(pinId: nil, tabId: tab.id, title: state.titleOf(tab)))
            }
        case .delete:
            show(.deleteItems(targets))
        case .changeContainer:
            sheet = .changeContainer(targets)
        default:
            app.runTabAction(action, targets: targets, workspaceId: workspaceId)
        }
    }

    // MARK: - Alerts

    private func show(_ newAlert: HomeAlert) {
        switch newAlert {
        case .renameFolder(let folder):
            alertText = folder.title
        case .renameTab(_, _, let title):
            alertText = title
        default:
            alertText = ""
        }
        alert = newAlert
    }

    private var alertShown: Binding<Bool> {
        Binding(get: { alert != nil }, set: { shown in if !shown { alert = nil } })
    }

    private var alertTitle: String {
        switch alert {
        case .deleteWorkspace(let id):
            return L10n.workspaceDeleteTitle(repository.state.workspace(id)?.name ?? "")
        case .newFolder:
            return L10n.actionNewFolder
        case .renameFolder:
            return L10n.folderRename
        case .renameTab:
            return L10n.actionRenameTab
        case .deleteItems(let targets):
            let items = targets.pins + targets.folders
            if items.count == 1 && targets.tabs.isEmpty {
                return L10n.deleteItemTitle(items[0].title)
            }
            return L10n.deleteItemsTitle(items.count + targets.tabs.count)
        case nil:
            return ""
        }
    }

    @ViewBuilder
    private var alertActions: some View {
        switch alert {
        case .deleteWorkspace(let id):
            Button(L10n.delete, role: .destructive) { repository.deleteWorkspace(id) }
            Button(L10n.cancel, role: .cancel) {}
        case .newFolder(let workspaceId, let parentId, let targets):
            TextField(L10n.workspaceName, text: $alertText)
            Button(L10n.save) {
                app.createFolder(workspaceId: workspaceId, parentId: parentId, name: alertText, targets: targets)
            }
            .disabled(alertText.trimmingCharacters(in: .whitespaces).isEmpty)
            Button(L10n.cancel, role: .cancel) {}
        case .renameFolder(let folder):
            TextField(L10n.workspaceName, text: $alertText)
            Button(L10n.save) { repository.renameFolder(folder.id, name: alertText) }
                .disabled(alertText.trimmingCharacters(in: .whitespaces).isEmpty)
            Button(L10n.cancel, role: .cancel) {}
        case .renameTab(let pinId, let tabId, _):
            TextField(L10n.renameTabHint, text: $alertText)
            Button(L10n.save) { app.renameTab(pinId: pinId, tabId: tabId, name: alertText) }
            Button(L10n.cancel, role: .cancel) {}
        case .deleteItems(let targets):
            Button(L10n.delete, role: .destructive) { app.deleteItems(targets) }
            Button(L10n.cancel, role: .cancel) {}
        case nil:
            Button(L10n.cancel, role: .cancel) {}
        }
    }

    @ViewBuilder
    private var alertMessage: some View {
        switch alert {
        case .deleteWorkspace:
            Text(L10n.workspaceDeleteMessage)
        case .deleteItems:
            Text(L10n.deleteItemsMessage)
        default:
            EmptyView()
        }
    }
}

/// The bar on top of the home screen: the account, the search field and the settings.
private struct HomeTopBar: View {
    let onSearch: () -> Void

    @ObservedObject private var app = AppModel.shared

    var body: some View {
        HStack(spacing: 4) {
            IconButton(icon: .symbol("person.crop.circle"), label: L10n.account) {
                app.showSettings(.sync)
            }
            Button(action: onSearch) {
                HStack(spacing: 10) {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(DejavuColor.onSurfaceVariant)
                    Text(L10n.searchHint)
                        .foregroundStyle(DejavuColor.onSurfaceVariant)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 16)
                .frame(height: 46)
                .glass(Capsule())
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            IconButton(icon: .symbol("slider.horizontal.3"), label: L10n.settings) {
                app.showSettings()
            }
        }
        .padding(.horizontal, 4)
        .frame(height: 64)
    }
}

/// Replaces the selection bar on top while dragged tabs can be dropped into the essentials.
private struct EssentialsDropBar: View {
    let active: Bool

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "square.grid.2x2")
            Text(L10n.essentialsDrop)
                .font(.subheadline.weight(.medium))
        }
        .foregroundStyle(DejavuColor.onSurface)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(active ? DejavuColor.primaryContainer : DejavuColor.surfaceContainerHigh))
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(height: 64)
    }
}

/// The bar on top while selecting: leave selection mode, the count, select all.
private struct SelectionTopBar: View {
    let count: Int
    let onClose: () -> Void
    let onSelectAll: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            IconButton(icon: .symbol("xmark"), label: L10n.selectionExit, action: onClose)
            Text(L10n.selectionCount(count))
                .font(.headline)
                .foregroundStyle(DejavuColor.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, 8)
            IconButton(icon: .symbol("checklist"), label: L10n.selectionSelectAll, action: onSelectAll)
        }
        .padding(.horizontal, 4)
        .frame(height: 64)
    }
}

/// The actions for the selection, at the bottom of the home screen, in rows of six.
private struct SelectionBar: View {
    let actions: [RowAction]
    let onAction: (RowAction) -> Void

    private static let perRow = 6

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(spacing: 0) {
                    ForEach(row, id: \.key) { action in
                        actionButton(action).frame(maxWidth: .infinity)
                    }
                    ForEach(0..<(Self.perRow - row.count), id: \.self) { _ in
                        Color.clear.frame(maxWidth: .infinity, maxHeight: 1)
                    }
                }
            }
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 6)
        .background(DejavuColor.surfaceContainer.ignoresSafeArea(edges: .bottom))
    }

    private var rows: [[RowAction]] {
        stride(from: 0, to: actions.count, by: Self.perRow).map { start in
            Array(actions[start..<min(start + Self.perRow, actions.count)])
        }
    }

    private func actionButton(_ action: RowAction) -> some View {
        let color = action.isDestructive ? DejavuColor.error : DejavuColor.onSurface
        return Button {
            onAction(action)
        } label: {
            VStack(spacing: 4) {
                Image(icon: action.icon)
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 22, height: 22)
                Text(action.label)
                    .font(.caption2)
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
            }
            .foregroundStyle(color)
            .padding(.vertical, 8)
            .padding(.horizontal, 2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Bookmarks, one dot or icon per workspace, the private workspace while it exists, and history. Tapping a workspace
/// shows it; long-pressing and dragging one sideways moves it.
private struct WorkspaceBar: View {
    let pageId: String?
    let hasPrivate: Bool
    let onSelect: (String) -> Void

    @ObservedObject private var app = AppModel.shared
    @ObservedObject private var repository = WorkspaceRepository.shared
    @ObservedObject private var containers = ContainerStore.shared
    @State private var drag: (id: String, x: CGFloat, index: Int)?

    private static let cellSize: CGFloat = 44

    var body: some View {
        HStack(spacing: 0) {
            IconButton(icon: .symbol("book"), label: L10n.bookmarks) { app.showLibrary(.bookmarks) }
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 0) {
                        ForEach(ordered) { workspace in
                            WorkspaceDot(
                                workspace: workspace,
                                container: containers.record(workspace.containerId),
                                isActive: workspace.id == pageId,
                                isDragged: drag?.id == workspace.id)
                            .id(workspace.id)
                            .onTapGesture { onSelect(workspace.id) }
                            .gesture(reorderGesture(workspace))
                        }
                        if hasPrivate {
                            privateDot.id(privatePageId)
                        }
                    }
                    .frame(minWidth: 0)
                    .coordinateSpace(.named("workspaceBar"))
                }
                .scrollDisabled(drag != nil)
                .frame(maxWidth: .infinity)
                .mask(fadingEdges)
                .onChange(of: pageId) { _, id in
                    if let id {
                        withAnimation { proxy.scrollTo(id, anchor: .center) }
                    }
                }
            }
            IconButton(icon: .symbol("clock.arrow.circlepath"), label: L10n.history) { app.showLibrary(.history) }
        }
        .padding(.horizontal, 4)
        .frame(height: 56)
    }

    private var privateDot: some View {
        let active = pageId == privatePageId
        return Image(systemName: "eyeglasses")
            .font(.system(size: 16))
            .foregroundStyle(DejavuColor.onSurface)
            .opacity(active ? 1 : 0.55)
            .frame(width: 36, height: 36)
            .background(Circle().fill(active ? DejavuColor.surfaceContainerHighest : Color.clear))
            .frame(width: Self.cellSize, height: Self.cellSize)
            .contentShape(Circle())
            .onTapGesture { onSelect(privatePageId) }
            .accessibilityLabel(L10n.privateWorkspace)
            .accessibilityAddTraits(.isButton)
    }

    private var fadingEdges: some View {
        LinearGradient(
            stops: [
                .init(color: .clear, location: 0), .init(color: .black, location: 0.06),
                .init(color: .black, location: 0.94), .init(color: .clear, location: 1),
            ],
            startPoint: .leading, endPoint: .trailing)
    }

    /// The workspaces in display order, with the dragged one where it would be dropped.
    private var ordered: [Workspace] {
        let workspaces = repository.state.workspaces
        guard let drag, let dragged = workspaces.first(where: { $0.id == drag.id }) else { return workspaces }
        var others = workspaces.filter { $0.id != drag.id }
        others.insert(dragged, at: drag.index.clamped(0, others.count))
        return others
    }

    private func reorderGesture(_ workspace: Workspace) -> some Gesture {
        LongPressGesture(minimumDuration: 0.4)
            .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .named("workspaceBar")))
            .onChanged { value in
                guard case .second(true, let dragValue) = value else { return }
                let workspaces = repository.state.workspaces
                if drag == nil, let index = workspaces.firstIndex(where: { $0.id == workspace.id }) {
                    drag = (id: workspace.id, x: 0, index: index)
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                }
                if let dragValue, let current = drag {
                    let index = Int(dragValue.location.x / Self.cellSize).clamped(0, max(workspaces.count - 1, 0))
                    drag = (id: current.id, x: dragValue.location.x, index: index)
                }
            }
            .onEnded { _ in
                if let current = drag,
                    let from = repository.state.workspaces.firstIndex(where: { $0.id == current.id }),
                    from != current.index
                {
                    repository.moveWorkspace(current.id, to: current.index)
                }
                drag = nil
            }
    }
}

/// A workspace in the workspace bar: its icon, or a dot in its container's color.
private struct WorkspaceDot: View {
    let workspace: Workspace
    let container: ContainerRecord?
    let isActive: Bool
    let isDragged: Bool

    var body: some View {
        let base = container?.color.color ?? DejavuColor.onSurface
        ZStack {
            Circle()
                .fill(background)
                .frame(width: 36, height: 36)
            if let icon = workspaceIconText(workspace.icon) {
                Text(icon)
                    .font(.system(size: 17))
                    .opacity(isActive || isDragged ? 1 : 0.55)
            } else {
                Circle()
                    .fill(isActive ? base : base.opacity(0.45))
                    .frame(width: isActive ? 10 : 8, height: isActive ? 10 : 8)
            }
        }
        .frame(width: 44, height: 44)
        .contentShape(Circle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(workspace.name)
        .accessibilityAddTraits(isActive ? [.isButton, .isSelected] : .isButton)
    }

    private var background: Color {
        if isDragged {
            return DejavuColor.primaryContainer
        }
        if isActive && workspaceIconText(workspace.icon) != nil {
            return DejavuColor.surfaceContainerHighest
        }
        return .clear
    }
}
