// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI

/// The browser's "More" menu, opened by swiping the actions bar up or the address bar down: rows of buttons the user
/// arranges in settings, the first of which is the actions bar, and an Edit button that opens those settings.
struct MoreMenuSheet: View {
    let tabId: String

    @ObservedObject private var app = AppModel.shared
    @ObservedObject private var store = BrowserStore.shared
    @ObservedObject private var settings = DejavuSettings.shared
    @ObservedObject private var repository = WorkspaceRepository.shared
    @ObservedObject private var bookmarks = BookmarkStore.shared

    var body: some View {
        let tab = store.tab(tabId)
        let state = tab.map { app.menuState(for: $0) } ?? MenuState()
        ScrollView {
            VStack(spacing: 6) {
                MoreMenuGrid(
                    rows: MoreMenuLayout.resolve(settings.moreMenuRows, customActions: settings.customActions),
                    state: state
                ) { entry in
                    handle(entry, state: state)
                }
                if !settings.moreMenuEditHidden {
                    HStack {
                        Spacer()
                        Button {
                            after { app.showSettings(.moreMenu) }
                        } label: {
                            Label(L10n.menuEdit, systemImage: "pencil")
                                .font(.callout)
                                .foregroundStyle(DejavuColor.onSurfaceVariant)
                        }
                        .buttonStyle(.plain)
                        .padding(8)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.top, 20)
        }
        .background(ThemedBarBackground(tab: tab))
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func handle(_ entry: MoreMenuEntry, state: MenuState) {
        if entry == .builtIn(.splitView) && !state.isSplit {
            app.sheet = .splitPicker(tabId: tabId)
            return
        }
        let tabId = tabId
        after { app.run(entry, tabId: tabId) }
    }

    /// Closes the menu, then does `action`, so that what it shows does not collide with the closing sheet.
    private func after(_ action: @escaping () -> Void) {
        app.sheet = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: action)
    }
}

/// The rows of buttons of the "More" menu.
struct MoreMenuGrid: View {
    let rows: [[MoreMenuEntry]]
    let state: MenuState
    let onTap: (MoreMenuEntry) -> Void

    @ObservedObject private var app = AppModel.shared

    var body: some View {
        VStack(spacing: 6) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                HStack(spacing: 0) {
                    ForEach(row, id: \.key) { entry in
                        MenuTile(look: app.look(of: entry, state: state)) { onTap(entry) }
                            .frame(maxWidth: .infinity)
                    }
                    ForEach(0..<max(MoreMenuLayout.maxPerRow - row.count, 0), id: \.self) { _ in
                        Color.clear.frame(maxWidth: .infinity, maxHeight: 1)
                    }
                }
            }
        }
    }
}

/// A button of the "More" menu: an icon on a rounded square with its label below.
struct MenuTile: View {
    let look: EntryLook
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(icon: look.icon)
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 22, height: 22)
                    .foregroundStyle(look.active ? DejavuColor.onPrimaryContainer : DejavuColor.onSurface)
                    .frame(width: 48, height: 48)
                    .background(
                        RoundedRectangle(cornerRadius: 16)
                            .fill(look.active ? DejavuColor.primaryContainer : DejavuColor.surfaceContainerHigh))
                Text(look.label)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(DejavuColor.onSurface)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
            }
            .padding(.vertical, 8)
            .padding(.horizontal, 2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!look.enabled)
        .opacity(look.enabled ? 1 : 0.38)
    }
}

/// Lists the tabs the shown tab can be put in a split view with.
struct SplitPickerView: View {
    let tabId: String

    @ObservedObject private var app = AppModel.shared
    @ObservedObject private var repository = WorkspaceRepository.shared

    var body: some View {
        NavigationStack {
            List {
                let candidates = app.splitCandidates(for: tabId)
                if candidates.isEmpty {
                    Text(L10n.menuSplitNoTabs)
                        .foregroundStyle(DejavuColor.onSurfaceVariant)
                }
                ForEach(candidates) { tab in
                    Button {
                        app.sheet = nil
                        app.split(tabId, with: tab.id)
                    } label: {
                        HStack(spacing: 14) {
                            FaviconView(url: tab.url, size: 24)
                            Text(repository.state.titleOf(tab))
                                .foregroundStyle(DejavuColor.onSurface)
                                .lineLimit(1)
                        }
                    }
                }
            }
            .navigationTitle(L10n.menuSplitWith)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.cancel) { app.sheet = nil }
                }
            }
        }
        .tint(DejavuColor.primary)
    }
}

/// Picks a container: none, a new temporary one, or one of the containers. Tapping one picks it and closes the sheet.
struct ContainerPickerView: View {
    let title: String
    let current: String?
    let onPick: (ContainerPick) -> Void

    @ObservedObject private var containers = ContainerStore.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    row(L10n.workspaceNoContainer, selected: false, pick: .noContainer) { NoContainerIconView() }
                    row(L10n.newTemporaryContainer, selected: false, pick: .temporary) { TemporaryContainerIconView() }
                }
                if !containers.records.isEmpty {
                    Section {
                        ForEach(containers.pickerOrder) { record in
                            row(record.name, selected: record.contextId == current, pick: .container(record.contextId)) {
                                ContainerIconView(record: record)
                            }
                        }
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.cancel) { dismiss() }
                }
            }
        }
        .tint(DejavuColor.primary)
    }

    private func row<Leading: View>(
        _ label: String, selected: Bool, pick: ContainerPick, @ViewBuilder leading: () -> Leading
    ) -> some View {
        Button {
            dismiss()
            onPick(pick)
        } label: {
            HStack(spacing: 12) {
                leading()
                Text(label).foregroundStyle(DejavuColor.onSurface)
                Spacer()
                if selected {
                    Image(systemName: "checkmark").foregroundStyle(DejavuColor.primary)
                }
            }
        }
    }
}

/// Picks a workspace, leaving out `excluding`. Tapping one picks it and closes the sheet.
struct WorkspacePickerView: View {
    let title: String
    let excluding: String?
    let onPick: (String) -> Void

    @ObservedObject private var repository = WorkspaceRepository.shared
    @ObservedObject private var containers = ContainerStore.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(repository.state.workspaces.filter { $0.id != excluding }) { workspace in
                    Button {
                        dismiss()
                        onPick(workspace.id)
                    } label: {
                        WorkspaceLabel(workspace: workspace, container: containers.record(workspace.containerId))
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.cancel) { dismiss() }
                }
            }
        }
        .tint(DejavuColor.primary)
    }
}

/// A workspace in a list: its container's icon (or the no container icon), its emoji and its name.
struct WorkspaceLabel: View {
    let workspace: Workspace
    let container: ContainerRecord?

    var body: some View {
        HStack(spacing: 12) {
            if let container {
                ContainerIconView(record: container)
            } else {
                NoContainerIconView()
            }
            if let icon = workspaceIconText(workspace.icon) {
                Text(icon)
            }
            Text(workspace.name)
                .foregroundStyle(DejavuColor.onSurface)
                .lineLimit(1)
        }
    }
}
