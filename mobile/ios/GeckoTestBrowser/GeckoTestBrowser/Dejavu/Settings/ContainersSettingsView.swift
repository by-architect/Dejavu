// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI

/// The sheet of the containers settings: the editor of a container, or the question of what happens to the tabs of
/// one being deleted.
private enum ContainerSheet: Identifiable {
    case edit(ContainerRecord?)
    case delete(ContainerRecord)

    var id: String {
        switch self {
        case .edit(let record): return "edit-\(record?.contextId ?? "new")"
        case .delete(let record): return "delete-\(record.contextId)"
        }
    }
}

/// Lists, adds, edits and deletes containers, and holds the two switches about containers.
struct ContainersSettingsView: View {
    @ObservedObject private var settings = DejavuSettings.shared
    @ObservedObject private var containers = ContainerStore.shared
    @State private var sheet: ContainerSheet?

    var body: some View {
        List {
            Section {
                SettingsView.SwitchRow(
                    L10n.temporaryByDefault, summary: L10n.temporaryByDefaultSummary,
                    isOn: settings.temporaryContainersByDefault,
                    onChange: { settings.setTemporaryContainersByDefault($0) })
                SettingsView.SwitchRow(
                    L10n.essentialsPerContainer, summary: L10n.essentialsPerContainerSummary,
                    isOn: settings.essentialsPerContainer,
                    onChange: { settings.setEssentialsPerContainer($0) })
            } header: {
                SettingsView.Header(nil, hint: L10n.settingsContainersHint)
            }
            .dejavuSettingsCard()
            Section {
                ForEach(containers.permanent) { record in
                    Button {
                        sheet = .edit(record)
                    } label: {
                        HStack(spacing: 14) {
                            ContainerIconView(record: record, size: 22)
                            Text(record.name).foregroundStyle(DejavuColor.onSurface)
                        }
                    }
                }
                Button {
                    sheet = .edit(nil)
                } label: {
                    SettingsView.EntryLabel(icon: .symbol("plus"), title: L10n.containerAdd)
                }
            }
            .dejavuSettingsCard()
        }
        .dejavuSettingsList()
        .navigationTitle(L10n.settingsContainers)
        .sheet(item: $sheet) { current in
            switch current {
            case .edit(let record):
                ContainerEditorSheet(record: record, onDelete: { deleted in
                    sheet = .delete(deleted)
                })
            case .delete(let record):
                DeleteContainerSheet(record: record)
            }
        }
    }
}

/// Creates a container, or edits one.
private struct ContainerEditorSheet: View {
    let record: ContainerRecord?
    let onDelete: (ContainerRecord) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var color: ContainerColor
    @State private var icon: ContainerIcon

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: 5)

    init(record: ContainerRecord?, onDelete: @escaping (ContainerRecord) -> Void) {
        self.record = record
        self.onDelete = onDelete
        _name = State(initialValue: record?.name ?? "")
        _color = State(initialValue: record?.color ?? .blue)
        _icon = State(initialValue: record?.icon ?? .fingerprint)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L10n.containerName, text: $name)
                }
                Section(L10n.containerColor) {
                    LazyVGrid(columns: columns, spacing: 8) {
                        ForEach(ContainerColor.pickable, id: \.self) { option in
                            choice(selected: option == color) {
                                color = option
                            } content: {
                                Circle().fill(option.color).frame(width: 22, height: 22)
                            }
                        }
                    }
                    .padding(.vertical, 4)
                }
                Section(L10n.containerIcon) {
                    LazyVGrid(columns: columns, spacing: 8) {
                        ForEach(ContainerIcon.allCases, id: \.self) { option in
                            choice(selected: option == icon) {
                                icon = option
                            } content: {
                                Image(icon: option.icon)
                                    .renderingMode(.template)
                                    .resizable()
                                    .scaledToFit()
                                    .foregroundStyle(color.color)
                                    .frame(width: 22, height: 22)
                            }
                        }
                    }
                    .padding(.vertical, 4)
                }
                if let record {
                    Section {
                        Button(L10n.delete, role: .destructive) { onDelete(record) }
                    }
                }
            }
            .navigationTitle(record == nil ? L10n.containerAdd : L10n.containerEdit)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.save) {
                        ContainerStore.shared.save(
                            contextId: record?.contextId,
                            name: name.trimmingCharacters(in: .whitespaces),
                            color: color,
                            icon: icon)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .tint(DejavuColor.primary)
    }

    private func choice<Content: View>(
        selected: Bool, action: @escaping () -> Void, @ViewBuilder content: () -> Content
    ) -> some View {
        Button(action: action) {
            content()
                .frame(width: 44, height: 44)
                .overlay(Circle().strokeBorder(selected ? DejavuColor.onSurface : Color.clear, lineWidth: 2))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
    }
}

/// Asks what happens to the tabs of a container before deleting it: close them, pinned tabs included, or reopen them
/// in another container or without one. Its cookies and site data are always deleted.
private struct DeleteContainerSheet: View {
    let record: ContainerRecord

    @ObservedObject private var containers = ContainerStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var removal = ContainerRemoval.closeTabs

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SettingsView.CheckRow(
                        L10n.containerDeleteCloseTabs, selected: removal == .closeTabs,
                        action: { removal = .closeTabs })
                } header: {
                    SettingsView.Header(nil, hint: L10n.containerDeleteMessage)
                }
                Section {
                    SettingsView.CheckRow(
                        L10n.workspaceNoContainer, selected: removal == .moveTabs(nil),
                        action: { removal = .moveTabs(nil) }
                    ) {
                        NoContainerIconView()
                    }
                    ForEach(containers.permanent.filter { $0.contextId != record.contextId }) { other in
                        SettingsView.CheckRow(
                            other.name, selected: removal == .moveTabs(other.contextId),
                            action: { removal = .moveTabs(other.contextId) }
                        ) {
                            ContainerIconView(record: other)
                        }
                    }
                } header: {
                    SettingsView.Header(L10n.containerDeleteMoveTabs)
                }
            }
            .navigationTitle(L10n.containerDeleteTitle(record.name))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.cancel) { dismiss() }
                }
                ToolbarItem(placement: .destructiveAction) {
                    Button(L10n.delete, role: .destructive) {
                        ContainerActions.remove(record, removal: removal)
                        dismiss()
                    }
                }
            }
        }
        .tint(DejavuColor.primary)
    }
}
