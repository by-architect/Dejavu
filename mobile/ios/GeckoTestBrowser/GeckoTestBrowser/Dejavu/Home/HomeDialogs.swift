// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI

/// The sheet the home screen shows.
struct HomeSheetContent: View {
    let sheet: HomeSheet

    var body: some View {
        switch sheet {
        case .editWorkspace(let workspaceId):
            WorkspaceEditorView(workspaceId: workspaceId)
        case .moveToFolder(let workspaceId, let targets):
            FolderPickerView(workspaceId: workspaceId, targets: targets)
        case .moveToWorkspace(let workspaceId, let targets):
            MoveToWorkspaceView(workspaceId: workspaceId, targets: targets)
        case .changeContainer(let targets):
            ChangeContainerView(targets: targets)
        }
    }
}

/// Creates or edits a workspace: its icon, name, theme and default container.
struct WorkspaceEditorView: View {
    let workspaceId: String?

    @ObservedObject private var app = AppModel.shared
    @ObservedObject private var containers = ContainerStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var containerId: String?
    @State private var icon: String?
    @State private var iconText: String
    @State private var theme: WorkspaceTheme?

    private static let minThemeOpacity = 0.1

    init(workspaceId: String?) {
        self.workspaceId = workspaceId
        let workspace = WorkspaceRepository.shared.state.workspace(workspaceId)
        _name = State(initialValue: workspace?.name ?? "")
        _containerId = State(initialValue: workspace?.containerId)
        _icon = State(initialValue: workspace?.icon)
        _iconText = State(initialValue: workspaceIconText(workspace?.icon) ?? "")
        _theme = State(initialValue: workspace?.theme)
    }

    var body: some View {
        NavigationStack {
            Form {
                nameSection
                themeSection
                containerSection
            }
            .navigationTitle(workspaceId == nil ? L10n.addWorkspace : L10n.workspaceEdit)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(workspaceId == nil ? L10n.create : L10n.save) {
                        app.saveWorkspace(id: workspaceId, name: name, containerId: containerId, icon: icon, theme: theme)
                        dismiss()
                    }
                }
            }
        }
        .tint(DejavuColor.primary)
    }

    private var nameSection: some View {
        Section {
            HStack(spacing: 12) {
                TextField(L10n.workspaceIcon, text: $iconText)
                    .font(.title2)
                    .multilineTextAlignment(.center)
                    .frame(width: 56)
                    .onChange(of: iconText) { _, text in iconTyped(text) }
                TextField(L10n.workspaceName, text: $name)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    iconChoice(nil)
                    ForEach(workspaceIconSuggestions, id: \.self) { suggestion in
                        iconChoice(suggestion)
                    }
                }
            }
        }
    }

    private var themeSection: some View {
        Section(L10n.workspaceTheme) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    themeChoice(nil)
                    ForEach(Array(workspaceThemePresets.enumerated()), id: \.offset) { _, colors in
                        themeChoice(colors)
                    }
                }
                .padding(.vertical, 4)
            }
            if theme != nil {
                sliderRow(
                    L10n.workspaceThemeIntensity,
                    value: Binding(
                        get: { theme?.opacity ?? WorkspaceTheme.defaultOpacity },
                        set: { theme?.opacity = $0 }),
                    range: Self.minThemeOpacity...1)
                sliderRow(
                    L10n.workspaceThemeGrain,
                    value: Binding(get: { theme?.texture ?? 0 }, set: { theme?.texture = $0 }),
                    range: 0...1)
            }
        }
    }

    private var containerSection: some View {
        Section {
            containerRow(L10n.workspaceNoContainer, selected: containerId == nil, contextId: nil) {
                NoContainerIconView()
            }
            ForEach(containers.permanent) { record in
                containerRow(record.name, selected: containerId == record.contextId, contextId: record.contextId) {
                    ContainerIconView(record: record)
                }
            }
        } header: {
            Text(L10n.workspaceContainer)
        } footer: {
            Text(L10n.workspaceContainerHint)
        }
    }

    /// Keeps the last character typed in the icon field, emoji sequences included.
    private func iconTyped(_ text: String) {
        guard text != (workspaceIconText(icon) ?? "") else { return }
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard let last = trimmed.last else {
            icon = nil
            return
        }
        let chosen = String(last)
        icon = chosen
        if text != chosen {
            iconText = chosen
        }
    }

    private func iconChoice(_ suggestion: String?) -> some View {
        let selected = icon == suggestion
        return Button {
            icon = suggestion
            iconText = suggestion ?? ""
        } label: {
            ZStack {
                Circle()
                    .fill(selected ? DejavuColor.secondaryContainer : Color.clear)
                if let suggestion {
                    Text(suggestion).font(.system(size: 20))
                } else {
                    NoContainerIconView(size: 20)
                }
            }
            .frame(width: 40, height: 40)
        }
        .buttonStyle(.plain)
    }

    private func themeChoice(_ colors: [Int]?) -> some View {
        let selected = theme?.colors == colors
        return Button {
            if let colors {
                theme = WorkspaceTheme(
                    colors: colors, opacity: theme?.opacity ?? WorkspaceTheme.defaultOpacity,
                    texture: theme?.texture ?? 0)
            } else {
                theme = nil
            }
        } label: {
            ZStack {
                if let colors {
                    Circle().fill(
                        LinearGradient(
                            colors: colors.map { Color(argb: $0) }, startPoint: .topLeading,
                            endPoint: .bottomTrailing))
                } else {
                    Circle().fill(DejavuColor.surfaceContainerHighest)
                    NoContainerIconView(size: 18)
                }
            }
            .frame(width: 32, height: 32)
            .padding(4)
            .overlay(Circle().strokeBorder(selected ? DejavuColor.primary : Color.clear, lineWidth: 2))
        }
        .buttonStyle(.plain)
    }

    private func sliderRow(_ label: String, value: Binding<Double>, range: ClosedRange<Double>) -> some View {
        HStack {
            Text(label)
                .frame(width: 96, alignment: .leading)
            Slider(value: value, in: range)
        }
    }

    private func containerRow<Leading: View>(
        _ label: String, selected: Bool, contextId: String?, @ViewBuilder leading: () -> Leading
    ) -> some View {
        Button {
            containerId = contextId
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

/// Moves the targets into a folder of the workspace, to the top of its pinned tabs, or into a new folder.
struct FolderPickerView: View {
    let workspaceId: String
    let targets: ActionTargets

    @ObservedObject private var app = AppModel.shared
    @ObservedObject private var repository = WorkspaceRepository.shared
    @Environment(\.dismiss) private var dismiss
    @State private var naming = false
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            List {
                pickerRow(L10n.folderTopLevel, icon: "pin", depth: 0) { pick(nil) }
                ForEach(folders, id: \.item.id) { entry in
                    pickerRow(entry.item.title, icon: "folder", depth: entry.depth) { pick(entry.item.id) }
                }
                pickerRow(L10n.actionNewFolder, icon: "folder.badge.plus", depth: 0) {
                    newName = ""
                    naming = true
                }
            }
            .navigationTitle(L10n.actionMoveToFolder)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.cancel) { dismiss() }
                }
            }
            .alert(L10n.actionNewFolder, isPresented: $naming) {
                TextField(L10n.workspaceName, text: $newName)
                Button(L10n.save) {
                    app.createFolder(workspaceId: workspaceId, parentId: nil, name: newName, targets: targets)
                    dismiss()
                }
                .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                Button(L10n.cancel, role: .cancel) {}
            }
        }
        .tint(DejavuColor.primary)
    }

    /// The folders the targets can go in: not one of the moving folders or inside them, and not too deep.
    private var folders: [PinnedEntry] {
        let state = repository.state
        let moving = Set(targets.folders.map { $0.id })
        var blocked = moving
        for id in moving {
            blocked.formUnion(state.descendantIds(id))
        }
        let tallest = moving.map { state.folderHeight($0) }.max() ?? 0
        return state.pinnedTree(workspaceId, expandAll: true).filter { entry in
            entry.item.isFolder && !blocked.contains(entry.item.id)
                && state.folderDepth(entry.item.id) + tallest <= maxFolderDepth
        }
    }

    private func pick(_ folderId: String?) {
        app.moveToFolder(workspaceId: workspaceId, targets: targets, folderId: folderId)
        dismiss()
    }

    private func pickerRow(_ label: String, icon: String, depth: Int, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .foregroundStyle(DejavuColor.onSurfaceVariant)
                    .frame(width: 22)
                Text(label)
                    .foregroundStyle(DejavuColor.onSurface)
                    .lineLimit(1)
            }
            .padding(.leading, 16 * CGFloat(depth))
        }
    }
}

/// Moves the targets to another workspace. Essentials can also go to the workspace they are looked at from, as its
/// pinned tabs.
struct MoveToWorkspaceView: View {
    let workspaceId: String
    let targets: ActionTargets

    @ObservedObject private var app = AppModel.shared
    @ObservedObject private var repository = WorkspaceRepository.shared
    @ObservedObject private var containers = ContainerStore.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let hasEssentials = targets.pins.contains { $0.essential }
        NavigationStack {
            List {
                ForEach(repository.state.workspaces.filter { hasEssentials || $0.id != workspaceId }) { workspace in
                    Button {
                        app.moveToWorkspace(targets, workspaceId: workspace.id)
                        dismiss()
                    } label: {
                        WorkspaceLabel(workspace: workspace, container: containers.record(workspace.containerId))
                    }
                }
            }
            .navigationTitle(L10n.actionMoveToWorkspace)
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

/// Moves the targets to another container, a new temporary one, or out of any container.
struct ChangeContainerView: View {
    let targets: ActionTargets

    @ObservedObject private var app = AppModel.shared
    @ObservedObject private var containers = ContainerStore.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let ids = targets.containerIds
        let current: String? = ids.count == 1 ? (ids.first ?? nil) : nil
        let allInNoContainer = ids == Set<String?>([nil])
        NavigationStack {
            List {
                Section {
                    row(L10n.workspaceNoContainer, selected: allInNoContainer, pick: .noContainer) {
                        NoContainerIconView()
                    }
                    row(L10n.newTemporaryContainer, selected: false, pick: .temporary) {
                        TemporaryContainerIconView()
                    }
                }
                if !containers.records.isEmpty {
                    Section {
                        ForEach(containers.pickerOrder) { record in
                            row(record.name, selected: current == record.contextId, pick: .container(record.contextId)) {
                                ContainerIconView(record: record)
                            }
                        }
                    }
                }
                Section {
                    Button {
                        dismiss()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                            app.showSettings(.containers)
                        }
                    } label: {
                        Label(L10n.containerAdd, systemImage: "plus")
                    }
                }
            }
            .navigationTitle(L10n.actionChangeContainer)
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
            ContainerActions.change(targets, to: pick)
            dismiss()
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
