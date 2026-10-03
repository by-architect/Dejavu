// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI

/// Chooses the search engine of the address bar.
struct SearchEngineSettingsView: View {
    @ObservedObject private var settings = DejavuSettings.shared

    var body: some View {
        List {
            Section {
                ForEach(SearchEngine.allCases) { engine in
                    SettingsView.CheckRow(
                        engine.name, selected: engine == settings.searchEngine,
                        action: { settings.setSearchEngine(engine) })
                }
            } header: {
                SettingsView.Header(nil, hint: L10n.iosSettingsSearchEngineHint)
            }
            .dejavuSettingsCard()
        }
        .dejavuSettingsList()
        .navigationTitle(L10n.iosSettingsSearchEngine)
    }
}

/// The app's logo, version and license, with links to its source code and privacy policy.
///
/// - `onOpenUrl`: Opens a link in a new tab.
struct AboutView: View {
    let onOpenUrl: (String) -> Void

    private static let sourceUrl = "https://github.com/by-architect/Dejavu"
    private static let privacyUrl = "https://github.com/by-architect/Dejavu/blob/main/PRIVACY.md"
    private static let licenseUrl = "https://www.mozilla.org/MPL/2.0/"

    private var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "1.0"
        guard let build = info?["CFBundleVersion"] as? String, build != short else { return short }
        return "\(short) (\(build))"
    }

    var body: some View {
        List {
            Section {
                VStack(spacing: 10) {
                    Image("dejavu-logo")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 88, height: 88)
                        .accessibilityHidden(true)
                    Text("Dejavu")
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(DejavuColor.onSurface)
                    Text(L10n.iosSettingsAboutVersion(version))
                        .font(.subheadline)
                        .foregroundStyle(DejavuColor.onSurfaceVariant)
                    Text(L10n.aboutContent("Dejavu"))
                        .font(.subheadline)
                        .foregroundStyle(DejavuColor.onSurface)
                        .multilineTextAlignment(.center)
                        .padding(.top, 4)
                    Text(L10n.iosSettingsAboutIndependent)
                        .font(.footnote)
                        .foregroundStyle(DejavuColor.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
            }
            .listRowBackground(Color.clear)
            Section {
                link(L10n.iosSettingsAboutSourceCode, icon: "chevron.left.forwardslash.chevron.right", Self.sourceUrl)
                link(L10n.iosSettingsAboutPrivacy, icon: "hand.raised", Self.privacyUrl)
                link(L10n.iosSettingsAboutLicense, icon: "doc.text", Self.licenseUrl)
            }
            .dejavuSettingsCard()
        }
        .dejavuSettingsList()
        .navigationTitle(L10n.iosSettingsAbout)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func link(_ title: String, icon: String, _ url: String) -> some View {
        Button {
            onOpenUrl(url)
        } label: {
            HStack {
                SettingsView.EntryLabel(icon: .symbol(icon), title: title)
                Spacer()
                Image(systemName: "arrow.up.right")
                    .font(.footnote)
                    .foregroundStyle(DejavuColor.onSurfaceVariant)
            }
        }
    }
}
