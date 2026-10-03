// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI

/// Chooses the workspace and container that links from other apps open in, or a private tab. Links reach Dejavu when
/// it is the default browser of the phone.
struct ExternalLinksSettingsView: View {
    @ObservedObject private var settings = DejavuSettings.shared
    @ObservedObject private var repository = WorkspaceRepository.shared
    @ObservedObject private var containers = ContainerStore.shared

    var body: some View {
        List {
            Section {
                SettingsView.SwitchRow(
                    L10n.externalLinksPrivate, summary: L10n.externalLinksPrivateSummary,
                    isOn: settings.externalLinksPrivate,
                    onChange: { settings.setExternalLinksPrivate($0) })
            } header: {
                SettingsView.Header(nil, hint: L10n.externalLinksHint)
            }
            .dejavuSettingsCard()
            workspaceSection
            containerSection
        }
        .dejavuSettingsList()
        .navigationTitle(L10n.settingsExternalLinks)
    }

    private var chosenWorkspaceId: String? {
        settings.externalLinkWorkspaceId.flatMap { repository.state.workspace($0)?.id }
    }

    private var workspaceSection: some View {
        Section {
            SettingsView.CheckRow(
                L10n.externalLinksLastWorkspace, selected: chosenWorkspaceId == nil,
                action: { settings.setExternalLinkWorkspace(nil) })
            ForEach(repository.state.workspaces) { workspace in
                SettingsView.CheckRow(
                    [workspaceIconText(workspace.icon), workspace.name].compactMap { $0 }.joined(separator: "  "),
                    selected: workspace.id == chosenWorkspaceId,
                    action: { settings.setExternalLinkWorkspace(workspace.id) })
            }
        } header: {
            SettingsView.Header(L10n.externalLinksWorkspace)
        }
        .dejavuSettingsCard()
        .disabled(settings.externalLinksPrivate)
        .opacity(settings.externalLinksPrivate ? 0.4 : 1)
    }

    private var containerSection: some View {
        let pick = settings.externalLinkContainer
        return Section {
            SettingsView.CheckRow(
                L10n.externalLinksWorkspaceContainer, selected: pick == nil,
                action: { settings.setExternalLinkContainer(nil) })
            SettingsView.CheckRow(
                L10n.workspaceNoContainer, selected: pick == .noContainer,
                action: { settings.setExternalLinkContainer(.noContainer) }
            ) {
                NoContainerIconView()
            }
            SettingsView.CheckRow(
                L10n.newTemporaryContainer, selected: pick == .temporary,
                action: { settings.setExternalLinkContainer(.temporary) }
            ) {
                TemporaryContainerIconView()
            }
            ForEach(containers.permanent) { record in
                SettingsView.CheckRow(
                    record.name, selected: pick == .container(record.contextId),
                    action: { settings.setExternalLinkContainer(.container(record.contextId)) }
                ) {
                    ContainerIconView(record: record)
                }
            }
        } header: {
            SettingsView.Header(L10n.externalLinksContainer)
        }
        .dejavuSettingsCard()
        .disabled(settings.externalLinksPrivate)
        .opacity(settings.externalLinksPrivate ? 0.4 : 1)
    }
}
