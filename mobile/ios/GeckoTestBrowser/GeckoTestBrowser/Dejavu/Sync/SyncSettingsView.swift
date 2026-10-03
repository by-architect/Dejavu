// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI

/// Signs in to the Mozilla account, turns syncing workspaces with Zen on and off, shows how the last sync went, and
/// syncs on demand, like the Android app's sync settings.
struct SyncSettingsView: View {
    @ObservedObject private var sync = DejavuSync.shared
    @ObservedObject private var account = MozillaAccount.shared
    @State private var signingIn = false
    @State private var confirmingSignOut = false

    var body: some View {
        List {
            if account.isSignedIn {
                accountSection
                syncSection
            } else {
                Section {
                    Button {
                        signingIn = true
                    } label: {
                        SettingsView.EntryLabel(icon: .symbol("person.crop.circle"), title: L10n.syncSignIn)
                    }
                } header: {
                    SettingsView.Header(nil, hint: L10n.syncHint + "\n\n" + L10n.syncSignedOut)
                } footer: {
                    zenHint
                }
                .dejavuSettingsCard()
            }
        }
        .dejavuSettingsList()
        .navigationTitle(L10n.settingsSync)
        .sheet(isPresented: $signingIn) {
            MozillaSignInView()
        }
        .confirmationDialog(L10n.iosSyncSignOutQuestion, isPresented: $confirmingSignOut, titleVisibility: .visible) {
            Button(L10n.iosSyncSignOut, role: .destructive) { sync.signOut() }
            Button(L10n.cancel, role: .cancel) {}
        }
    }

    private var zenHint: some View {
        Text(L10n.syncZenHint)
            .font(.footnote)
            .foregroundStyle(DejavuColor.onSurfaceVariant)
    }

    private var accountSection: some View {
        Section {
            SettingsView.EntryLabel(
                icon: .symbol("person.crop.circle.fill"), title: L10n.account,
                summary: account.email.map { L10n.iosSyncSignedInAs($0) } ?? L10n.iosSyncSignedIn)
            if sync.status.problem == .signInAgain {
                Button(L10n.syncSignIn) { signingIn = true }
            }
            Button(L10n.iosSyncSignOut, role: .destructive) { confirmingSignOut = true }
        } header: {
            SettingsView.Header(nil, hint: L10n.syncHint)
        }
        .dejavuSettingsCard()
    }

    private var syncSection: some View {
        let status = sync.status
        return Section {
            SettingsView.SwitchRow(L10n.syncEnabled, isOn: status.enabled, onChange: { sync.setEnabled($0) })
            SettingsView.SwitchRow(
                L10n.syncNormalTabs, summary: L10n.syncNormalTabsSummary, isOn: status.normalTabs,
                onChange: { sync.setNormalTabs($0) }
            )
            .disabled(!status.enabled)
            Button {
                sync.syncNow()
            } label: {
                TimelineView(.periodic(from: .now, by: 30)) { _ in
                    SettingsView.EntryLabel(
                        icon: .symbol("arrow.triangle.2.circlepath"),
                        title: status.syncing ? L10n.syncSyncing : L10n.syncNow,
                        summary: statusText(status))
                }
            }
            .disabled(!status.enabled || status.syncing)
            if status.enabled && status.problem == .notTurnedOn {
                Button {
                    sync.turnOn()
                } label: {
                    Text(L10n.syncTurnOn)
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(status.syncing)
            }
        } footer: {
            zenHint
        }
        .dejavuSettingsCard()
    }

    private func statusText(_ status: DejavuSyncStatus) -> String {
        if !status.enabled {
            return L10n.syncOff
        }
        if let problem = status.problem {
            switch problem {
            case .notSetUp: return L10n.syncProblemNotSetUp
            case .notTurnedOn: return L10n.syncProblemNotTurnedOn
            case .needsUpdate: return L10n.syncProblemNeedsUpdate
            case .signInAgain: return L10n.syncProblemSignIn
            case .offline: return L10n.syncProblemOffline
            case .server: return L10n.syncProblemServer
            }
        }
        if status.lastSynced > 0 {
            let date = Date(timeIntervalSince1970: TimeInterval(status.lastSynced) / 1000)
            let formatter = RelativeDateTimeFormatter()
            formatter.dateTimeStyle = .named
            return L10n.syncLastSynced(formatter.localizedString(for: min(date, Date()), relativeTo: Date()))
        }
        return L10n.syncNeverSynced
    }
}
