// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI

/// A list of tab actions to choose from in the tab actions settings, like Android's `ActionList`.
enum ActionListKind: Hashable {
    /// The buttons of pinned tab rows.
    case pinned

    /// The buttons of unpinned tab rows.
    case unpinned

    /// The actions of the selection bar.
    case all

    var title: String {
        switch self {
        case .pinned: return L10n.settingsPinnedTabs
        case .unpinned: return L10n.settingsUnpinnedTabs
        case .all: return L10n.settingsAllActions
        }
    }
}

/// A screen of the settings, as it is pushed on their navigation stack.
enum SettingsScreen: Hashable {
    /// Leads to the three lists of tab actions.
    case tabActions

    /// One list of tab actions.
    case actionList(ActionListKind)

    /// Edits the custom action with this id, or adds a new one when it is `nil`.
    case customAction(String?)

    /// Adds, edits and deletes containers.
    case containers

    /// Where links from other apps open.
    case externalLinks

    /// The rows of the browser's "More" menu.
    case moreMenu

    /// Sync of workspaces with Zen.
    case sync

    /// The search engine of the address bar.
    case searchEngine

    /// The app's version, license and links.
    case about
}

/// Dejavu's settings, meant to be shown in a sheet: the screens of the Android app's Dejavu settings, the search engine
/// and an about screen, on their own navigation stack.
///
/// - `initial`: Screen to show first, on top of the list of settings. Back leads to the list.
/// - `onOpenUrl`: Opens a page, like the privacy policy, in a new tab. The settings close after calling it.
struct SettingsView: View {
    private let initial: SettingsScreen?
    private let onOpenUrl: (String) -> Void

    @ObservedObject private var settings = DejavuSettings.shared
    @Environment(\.dismiss) private var dismiss
    @State private var path: [SettingsScreen]

    init(initial: SettingsScreen? = nil, onOpenUrl: @escaping (String) -> Void = { _ in }) {
        self.initial = initial
        self.onOpenUrl = onOpenUrl
        _path = State(initialValue: initial.map { [$0] } ?? [])
    }

    var body: some View {
        NavigationStack(path: $path) {
            root
                .navigationTitle(L10n.settings)
                .modifier(DoneButton(shown: true, done: { dismiss() }))
                .navigationDestination(for: SettingsScreen.self) { screen in
                    destination(screen)
                        .modifier(DoneButton(shown: showsDone(screen), done: { dismiss() }))
                }
        }
        .tint(DejavuColor.primary)
    }

    private var root: some View {
        List {
            dejavuSection
            generalSection
            aboutSection
        }
        .dejavuSettingsList()
    }

    private var dejavuSection: some View {
        Section {
            entry(
                .tabActions, icon: .symbol("hand.tap"), title: L10n.settingsTabActions,
                summary: L10n.settingsTabActionsEntrySummary)
            entry(
                .containers, icon: .asset("dejavu-container"), title: L10n.settingsContainers,
                summary: L10n.settingsContainersEntrySummary)
            entry(
                .externalLinks, icon: .symbol("arrow.up.forward.app"), title: L10n.settingsExternalLinks,
                summary: L10n.settingsExternalLinksSummary)
            entry(
                .moreMenu, icon: .symbol("ellipsis.circle"), title: L10n.settingsMoreMenu,
                summary: L10n.settingsMoreMenuSummary)
            entry(
                .sync, icon: .symbol("arrow.triangle.2.circlepath"), title: L10n.settingsSync,
                summary: L10n.settingsSyncSummary)
        } header: {
            SettingsView.Header(L10n.settingsTitle)
        }
        .dejavuSettingsCard()
    }

    private var generalSection: some View {
        Section {
            entry(
                .searchEngine, icon: .symbol("magnifyingglass"), title: L10n.iosSettingsSearchEngine,
                summary: settings.searchEngine.name)
        } header: {
            SettingsView.Header(L10n.iosSettingsGeneral)
        }
        .dejavuSettingsCard()
    }

    private var aboutSection: some View {
        Section {
            entry(.about, icon: .symbol("info.circle"), title: L10n.iosSettingsAbout, summary: nil)
        }
        .dejavuSettingsCard()
    }

    private func entry(_ screen: SettingsScreen, icon: Icon, title: String, summary: String?) -> some View {
        NavigationLink(value: screen) {
            SettingsView.EntryLabel(icon: icon, title: title, summary: summary)
        }
    }

    @ViewBuilder
    private func destination(_ screen: SettingsScreen) -> some View {
        switch screen {
        case .tabActions:
            TabActionsSettingsView()
        case .actionList(let kind):
            ActionListSettingsView(kind: kind)
        case .customAction(let actionId):
            CustomActionEditorView(actionId: actionId)
        case .containers:
            ContainersSettingsView()
        case .externalLinks:
            ExternalLinksSettingsView()
        case .moreMenu:
            MoreMenuEditorView()
        case .sync:
            SyncSettingsView()
        case .searchEngine:
            SearchEngineSettingsView()
        case .about:
            AboutView(onOpenUrl: { url in open(url) })
        }
    }

    /// Whether `screen` gets its own Done button: the screen the settings were opened at has no list of settings
    /// below it to go back to first. The custom action editor has Save instead.
    private func showsDone(_ screen: SettingsScreen) -> Bool {
        guard screen == initial else { return false }
        if case .customAction = screen {
            return false
        }
        return true
    }

    private func open(_ url: String) {
        onOpenUrl(url)
        dismiss()
    }

    /// Adds the Done button that closes the settings.
    private struct DoneButton: ViewModifier {
        let shown: Bool
        let done: () -> Void

        @ViewBuilder
        func body(content: Content) -> some View {
            if shown {
                content.toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L10n.iosSettingsDone, action: done)
                    }
                }
            } else {
                content
            }
        }
    }
}

// MARK: - Shared parts of the settings screens

extension SettingsView {
    /// The title of a settings section, in golden yellow like the section headers of Android's settings, with an
    /// optional hint below it. Without a title it shows only the hint, like the text at the top of Android's screens.
    struct Header: View {
        let title: String?
        let hint: String?

        init(_ title: String?, hint: String? = nil) {
            self.title = title
            self.hint = hint
        }

        var body: some View {
            VStack(alignment: .leading, spacing: 6) {
                if let title {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(DejavuColor.primary)
                }
                if let hint {
                    Text(hint)
                        .font(.subheadline)
                        .foregroundStyle(DejavuColor.onSurfaceVariant)
                }
            }
            .textCase(nil)
        }
    }

    /// An icon of the settings, drawn in one color.
    struct IconImage: View {
        let icon: Icon
        var size: CGFloat = 22
        var color: Color = DejavuColor.primary

        var body: some View {
            Image(icon: icon)
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .frame(width: size, height: size)
                .foregroundStyle(color)
        }
    }

    /// A row that leads somewhere: an icon, a title, and a summary below the title when there is one.
    struct EntryLabel: View {
        let icon: Icon
        let title: String
        var summary: String? = nil
        var summaryLines: Int? = nil
        var iconColor: Color = DejavuColor.primary

        var body: some View {
            HStack(spacing: 14) {
                SettingsView.IconImage(icon: icon, color: iconColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .foregroundStyle(DejavuColor.onSurface)
                    if let summary {
                        Text(summary)
                            .font(.footnote)
                            .foregroundStyle(DejavuColor.onSurfaceVariant)
                            .lineLimit(summaryLines)
                    }
                }
            }
            .padding(.vertical, 2)
        }
    }

    /// A switch with a title, an optional icon before it and an optional summary below it, like Android's
    /// `SwitchListItem`. `onChange` gets the new state when the user flips it.
    struct SwitchRow: View {
        let title: String
        let summary: String?
        let icon: Icon?
        let isOn: Bool
        let onChange: (Bool) -> Void

        init(
            _ title: String, summary: String? = nil, icon: Icon? = nil, isOn: Bool,
            onChange: @escaping (Bool) -> Void
        ) {
            self.title = title
            self.summary = summary
            self.icon = icon
            self.isOn = isOn
            self.onChange = onChange
        }

        var body: some View {
            Toggle(isOn: Binding<Bool>(get: { isOn }, set: onChange)) {
                HStack(spacing: 14) {
                    if let icon {
                        SettingsView.IconImage(icon: icon, size: 20, color: DejavuColor.onSurfaceVariant)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .foregroundStyle(DejavuColor.onSurface)
                        if let summary {
                            Text(summary)
                                .font(.footnote)
                                .foregroundStyle(DejavuColor.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    /// One choice among several, checked when it is the chosen one, like the radio buttons of Android's settings.
    /// `leading` is drawn before the label, like the icon of a container.
    struct CheckRow<Leading: View>: View {
        let label: String
        let selected: Bool
        let action: () -> Void
        let leading: Leading

        init(_ label: String, selected: Bool, action: @escaping () -> Void, @ViewBuilder leading: () -> Leading) {
            self.label = label
            self.selected = selected
            self.action = action
            self.leading = leading()
        }

        var body: some View {
            Button(action: action) {
                HStack(spacing: 12) {
                    leading
                    Text(label)
                        .foregroundStyle(DejavuColor.onSurface)
                    Spacer(minLength: 8)
                    Image(systemName: "checkmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(DejavuColor.primary)
                        .opacity(selected ? 1 : 0)
                }
                .contentShape(Rectangle())
            }
            .accessibilityAddTraits(selected ? .isSelected : [])
        }
    }

    /// Lays its views out in lines, starting a new line when the next view does not fit, like Compose's `FlowRow`.
    struct FlowLayout: Layout {
        var spacing: CGFloat = 8

        func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
            arrange(subviews, width: proposal.width ?? .infinity).size
        }

        func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
            let frames = arrange(subviews, width: bounds.width).frames
            for (index, subview) in subviews.enumerated() where index < frames.count {
                let frame = frames[index]
                subview.place(
                    at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY),
                    proposal: ProposedViewSize(frame.size))
            }
        }

        /// Where each of `subviews` goes in lines at most `width` wide, and the size of all the lines.
        private func arrange(_ subviews: Subviews, width: CGFloat) -> (frames: [CGRect], size: CGSize) {
            var frames: [CGRect] = []
            var x: CGFloat = 0
            var y: CGFloat = 0
            var lineHeight: CGFloat = 0
            var usedWidth: CGFloat = 0
            for subview in subviews {
                var size = subview.sizeThatFits(.unspecified)
                if size.width > width {
                    size = subview.sizeThatFits(ProposedViewSize(width: width, height: nil))
                }
                if x > 0 && x + size.width > width {
                    x = 0
                    y += lineHeight + spacing
                    lineHeight = 0
                }
                frames.append(CGRect(x: x, y: y, width: size.width, height: size.height))
                usedWidth = max(usedWidth, x + size.width)
                x += size.width + spacing
                lineHeight = max(lineHeight, size.height)
            }
            return (frames, CGSize(width: usedWidth, height: y + lineHeight))
        }
    }
}

extension SettingsView.CheckRow where Leading == EmptyView {
    init(_ label: String, selected: Bool, action: @escaping () -> Void) {
        self.init(label, selected: selected, action: action, leading: { EmptyView() })
    }
}

extension View {
    /// The look of Dejavu's settings lists: rounded cards on warm paper or charcoal, with golden yellow controls, like
    /// the settings of the Android app.
    func dejavuSettingsList() -> some View {
        listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(DejavuColor.surface)
            .tint(DejavuColor.primary)
    }

    /// Gives the rows of a settings section the color of the Android app's settings cards.
    func dejavuSettingsCard() -> some View {
        listRowBackground(DejavuColor.surfaceContainerHigh)
    }
}
