// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI

/// Changes to the rows of the "More" menu, saved with `save`, like Android's `RowsEditor`.
private struct RowsEditor {
    let rows: [[String]]
    let save: ([[String]]) -> Void

    var size: Int {
        rows.count
    }

    func hasRoom(_ row: Int) -> Bool {
        guard row >= 0, row < rows.count else { return false }
        let keys = rows[row]
        return keys.count < MoreMenuLayout.maxPerRow && !keys.contains { MoreMenuLayout.isFullRow($0) }
    }

    func add(_ key: String, to row: Int) {
        var updated = rows
        if MoreMenuLayout.isFullRow(key) || !hasRoom(row) {
            updated.insert([key], at: min(row + 1, updated.count))
        } else {
            updated[row].append(key)
        }
        save(updated)
    }

    func addRow(_ key: String) {
        save(rows + [[key]])
    }

    func remove(row: Int, item: Int) {
        var updated = rows
        updated[row].remove(at: item)
        save(updated)
    }

    func moveItem(row: Int, item: Int, by delta: Int) {
        let target = item + delta
        guard rows[row].indices.contains(target) else { return }
        var updated = rows
        let key = updated[row].remove(at: item)
        updated[row].insert(key, at: target)
        save(updated)
    }

    /// Moves an item to the end of the row `delta` rows away, if that row has room for it.
    func moveToRow(row: Int, item: Int, by delta: Int) {
        let target = row + delta
        let key = rows[row][item]
        guard hasRoom(target), !MoreMenuLayout.isFullRow(key) else { return }
        var updated = rows
        updated[row].remove(at: item)
        updated[target].append(key)
        save(updated)
    }

    func moveRow(_ row: Int, by delta: Int) {
        let target = row + delta
        guard rows.indices.contains(target) else { return }
        var updated = rows
        let keys = updated.remove(at: row)
        updated.insert(keys, at: target)
        save(updated)
    }

    func deleteRow(_ row: Int) {
        var updated = rows
        updated.remove(at: row)
        save(updated)
    }
}

/// Where a new button goes.
private enum AddTarget: Identifiable {
    case intoRow(Int)
    case newRow

    var id: String {
        switch self {
        case .intoRow(let row): return "row-\(row)"
        case .newRow: return "new"
        }
    }
}

/// Arranges the buttons of the browser's "More" menu in rows. The first row is the actions bar below pages.
struct MoreMenuEditorView: View {
    @ObservedObject private var settings = DejavuSettings.shared
    @State private var adding: AddTarget?

    /// The saved rows as the menu shows them: without entries that no longer exist, duplicates and empty rows.
    private var rows: [[String]] {
        MoreMenuLayout.normalized(
            settings.moreMenuRows.map { row in
                row.filter { MoreMenuLayout.entry(of: $0, customActions: settings.customActions) != nil }
            })
    }

    private var editor: RowsEditor {
        RowsEditor(rows: rows, save: { settings.setMoreMenuRows($0) })
    }

    var body: some View {
        List {
            Section {
                SettingsView.SwitchRow(
                    L10n.moreMenuHideEdit, summary: L10n.moreMenuHideEditSummary, isOn: settings.moreMenuEditHidden,
                    onChange: { settings.setMoreMenuEditHidden($0) })
            } header: {
                SettingsView.Header(nil, hint: L10n.moreMenuHint)
            }
            .dejavuSettingsCard()
            Section {
                preview
            } header: {
                SettingsView.Header(L10n.moreMenuPreview)
            }
            .listRowBackground(DejavuColor.surfaceContainerLow)
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                rowSection(index, keys: row)
            }
            Section {
                Button(L10n.moreMenuAddRow) { adding = .newRow }
                Button(L10n.moreMenuReset) { settings.resetMoreMenu() }
            }
            .dejavuSettingsCard()
        }
        .dejavuSettingsList()
        .navigationTitle(L10n.settingsMoreMenu)
        .sheet(item: $adding) { target in
            EntryPicker(entries: availableEntries) { entry in
                switch target {
                case .intoRow(let row):
                    editor.add(entry.key, to: row)
                case .newRow:
                    editor.addRow(entry.key)
                }
                adding = nil
            }
        }
    }

    private var preview: some View {
        VStack(spacing: 4) {
            MoreMenuGrid(
                rows: MoreMenuLayout.resolve(rows, customActions: settings.customActions), state: .preview,
                onTap: { _ in })
            if !settings.moreMenuEditHidden {
                HStack {
                    Spacer()
                    Label(L10n.menuEdit, systemImage: "pencil")
                        .font(.callout)
                        .foregroundStyle(DejavuColor.onSurfaceVariant)
                }
            }
        }
        .padding(.vertical, 8)
        .allowsHitTesting(false)
    }

    private var availableEntries: [MoreMenuEntry] {
        let used = Set(rows.flatMap { $0 })
        return MoreMenuLayout.available(customActions: settings.customActions).filter { !used.contains($0.key) }
    }

    private func rowSection(_ index: Int, keys: [String]) -> some View {
        let entries = keys.compactMap { MoreMenuLayout.entry(of: $0, customActions: settings.customActions) }
        return Section {
            SettingsView.FlowLayout(spacing: 8) {
                ForEach(Array(entries.enumerated()), id: \.element.key) { item, entry in
                    chip(entry, row: index, item: item, count: entries.count)
                }
                if editor.hasRoom(index) {
                    Button {
                        adding = .intoRow(index)
                    } label: {
                        Label(L10n.moreMenuAddItem, systemImage: "plus")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(DejavuColor.primary)
                            .padding(.horizontal, 10)
                            .frame(minHeight: 40)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, 6)
        } header: {
            rowHeader(index)
        }
        .dejavuSettingsCard()
    }

    private func rowHeader(_ index: Int) -> some View {
        HStack {
            Text(L10n.moreMenuRow(index + 1))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(DejavuColor.primary)
            Spacer()
            Button {
                editor.moveRow(index, by: -1)
            } label: {
                Image(systemName: "chevron.up")
            }
            .disabled(index == 0)
            .accessibilityLabel(L10n.moreMenuMoveUp)
            Button {
                editor.moveRow(index, by: 1)
            } label: {
                Image(systemName: "chevron.down")
            }
            .disabled(index >= editor.size - 1)
            .accessibilityLabel(L10n.moreMenuMoveDown)
            Button {
                editor.deleteRow(index)
            } label: {
                Image(systemName: "trash")
            }
            .accessibilityLabel(L10n.moreMenuDeleteRow)
        }
        .buttonStyle(.borderless)
        .textCase(nil)
    }

    private func chip(_ entry: MoreMenuEntry, row: Int, item: Int, count: Int) -> some View {
        let fullRow = MoreMenuLayout.isFullRow(entry.key)
        return Menu {
            Button(L10n.moreMenuMoveLeft) { editor.moveItem(row: row, item: item, by: -1) }
                .disabled(item == 0)
            Button(L10n.moreMenuMoveRight) { editor.moveItem(row: row, item: item, by: 1) }
                .disabled(item >= count - 1)
            Button(L10n.moreMenuMoveUp) { editor.moveToRow(row: row, item: item, by: -1) }
                .disabled(!editor.hasRoom(row - 1) || fullRow)
            Button(L10n.moreMenuMoveDown) { editor.moveToRow(row: row, item: item, by: 1) }
                .disabled(!editor.hasRoom(row + 1) || fullRow)
            Button(L10n.moreMenuRemove, role: .destructive) { editor.remove(row: row, item: item) }
        } label: {
            HStack(spacing: 6) {
                Image(icon: entry.icon)
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 18, height: 18)
                Text(entry.label)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
            }
            .foregroundStyle(DejavuColor.onSurface)
            .padding(.horizontal, 10)
            .frame(minHeight: 40)
            .background(RoundedRectangle(cornerRadius: 12).fill(DejavuColor.surfaceContainerHighest))
        }
    }
}

/// Picks a button to add to the menu.
private struct EntryPicker: View {
    let entries: [MoreMenuEntry]
    let onPick: (MoreMenuEntry) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if entries.isEmpty {
                    Text(L10n.moreMenuAllAdded)
                        .foregroundStyle(DejavuColor.onSurfaceVariant)
                }
                ForEach(entries, id: \.key) { entry in
                    Button {
                        onPick(entry)
                    } label: {
                        HStack(spacing: 16) {
                            Image(icon: entry.icon)
                                .renderingMode(.template)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 22, height: 22)
                                .foregroundStyle(DejavuColor.onSurface)
                            Text(entry.label)
                                .foregroundStyle(DejavuColor.onSurface)
                        }
                    }
                }
            }
            .navigationTitle(L10n.moreMenuAddItem)
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
