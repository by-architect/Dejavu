// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI

/// Leads to the three lists of actions: the buttons of pinned tab rows, of unpinned tab rows, and the selection bar.
struct TabActionsSettingsView: View {
    @ObservedObject private var settings = DejavuSettings.shared

    var body: some View {
        List {
            Section {
                NavigationLink(value: SettingsScreen.actionList(.pinned)) {
                    SettingsView.EntryLabel(
                        icon: .symbol("pin"), title: L10n.settingsPinnedTabs,
                        summary: summary(settings.rowActions(pinned: true)))
                }
                NavigationLink(value: SettingsScreen.actionList(.unpinned)) {
                    SettingsView.EntryLabel(
                        icon: .symbol("square.on.square"), title: L10n.settingsUnpinnedTabs,
                        summary: summary(settings.rowActions(pinned: false)))
                }
                NavigationLink(value: SettingsScreen.actionList(.all)) {
                    SettingsView.EntryLabel(
                        icon: .symbol("checklist"), title: L10n.settingsAllActions, summary: allActionsSummary)
                }
            } header: {
                SettingsView.Header(nil, hint: L10n.settingsTabActionsHint)
            }
            .dejavuSettingsCard()
        }
        .dejavuSettingsList()
        .navigationTitle(L10n.settingsTabActions)
    }

    private func summary(_ actions: [RowAction]) -> String {
        actions.isEmpty ? L10n.settingsNoButtons : actions.map { $0.label }.joined(separator: ", ")
    }

    private var allActionsSummary: String {
        let all = RowAction.selectionBar(customActions: settings.customActions)
        let shown = all.filter { !settings.hiddenSelectionKeys.contains($0.key) }.count
        return L10n.settingsAllActionsSummary(shown, all.count)
    }
}

/// Chooses the actions of one list, below the custom actions and the button that adds one.
struct ActionListSettingsView: View {
    let kind: ActionListKind

    @ObservedObject private var settings = DejavuSettings.shared

    var body: some View {
        List {
            customActionsSection
            switchesSection
        }
        .dejavuSettingsList()
        .navigationTitle(kind.title)
    }

    private var customActionsSection: some View {
        Section {
            NavigationLink(value: SettingsScreen.customAction(nil)) {
                SettingsView.EntryLabel(icon: .symbol("plus"), title: L10n.customActionAdd)
            }
            ForEach(settings.customActions) { action in
                NavigationLink(value: SettingsScreen.customAction(action.id)) {
                    SettingsView.EntryLabel(
                        icon: .symbol("bolt"), title: action.name, summary: "\(action.method.rawValue) \(action.url)",
                        summaryLines: 1)
                }
            }
        } header: {
            if !settings.customActions.isEmpty {
                SettingsView.Header(L10n.customActions)
            }
        }
        .dejavuSettingsCard()
    }

    private var switchesSection: some View {
        Section {
            ForEach(actions, id: \.key) { action in
                actionSwitch(action)
            }
        } header: {
            SettingsView.Header(kind == .all ? L10n.settingsActions : L10n.settingsButtons, hint: hint)
        }
        .dejavuSettingsCard()
    }

    private func actionSwitch(_ action: RowAction) -> some View {
        let checked = isChecked(action)
        return Toggle(
            isOn: Binding(get: { checked }, set: { enabled in change(action.key, enabled: enabled) })
        ) {
            HStack(spacing: 14) {
                SettingsView.IconImage(icon: action.icon, size: 20, color: DejavuColor.onSurfaceVariant)
                Text(action.label).foregroundStyle(DejavuColor.onSurface)
            }
        }
        .disabled(!checked && !canCheckMore)
    }

    private var actions: [RowAction] {
        switch kind {
        case .pinned: return RowAction.available(pinned: true, customActions: settings.customActions)
        case .unpinned: return RowAction.available(pinned: false, customActions: settings.customActions)
        case .all: return RowAction.selectionBar(customActions: settings.customActions)
        }
    }

    private var hint: String {
        switch kind {
        case .pinned: return L10n.settingsPinnedActionsHint(DejavuSettings.maxRowActions)
        case .unpinned: return L10n.settingsUnpinnedActionsHint(DejavuSettings.maxRowActions)
        case .all: return L10n.settingsAllActionsHint
        }
    }

    private func isChecked(_ action: RowAction) -> Bool {
        switch kind {
        case .pinned: return settings.pinnedRowKeys.contains(action.key)
        case .unpinned: return settings.unpinnedRowKeys.contains(action.key)
        case .all: return !settings.hiddenSelectionKeys.contains(action.key)
        }
    }

    /// Whether one more action can be switched on: rows show at most `DejavuSettings.maxRowActions` buttons.
    private var canCheckMore: Bool {
        switch kind {
        case .pinned, .unpinned:
            return actions.filter { isChecked($0) }.count < DejavuSettings.maxRowActions
        case .all:
            return true
        }
    }

    private func change(_ key: String, enabled: Bool) {
        switch kind {
        case .pinned: settings.setRowAction(pinned: true, key: key, enabled: enabled)
        case .unpinned: settings.setRowAction(pinned: false, key: key, enabled: enabled)
        case .all: settings.setSelectionAction(key, enabled: enabled)
        }
    }
}
