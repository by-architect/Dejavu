// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import GeckoView
import SwiftUI
import UIKit

/// The background of the bars of the browser: the surface with the theme of the shown tab's workspace, or the purple of
/// private browsing for a private tab.
struct ThemedBarBackground: View {
    let tab: TabInfo?

    @ObservedObject private var repository = WorkspaceRepository.shared

    var body: some View {
        ZStack {
            DejavuColor.surface.opacity(0.94)
            WorkspaceBackground(theme: theme)
        }
        .ignoresSafeArea()
    }

    private var theme: WorkspaceTheme? {
        guard let tab else { return nil }
        if tab.isPrivate {
            return privateWorkspaceTheme
        }
        let state = repository.state
        return state.workspace(state.workspaceOf(tab.id))?.theme
    }
}

/// Dejavu's address bar while a page is shown: only its state, like Safari's. The page title sits in the middle of a
/// rounded glass field, after the icons of the tab's container and of the site's security, with no buttons. Tapping
/// the bar edits the address, long-pressing it copies the address or pastes one, and swiping it down opens the menu.
struct BrowserTopBar: View {
    @ObservedObject private var app = AppModel.shared
    @ObservedObject private var store = BrowserStore.shared
    @ObservedObject private var repository = WorkspaceRepository.shared
    @ObservedObject private var containers = ContainerStore.shared

    var body: some View {
        let tab = store.selectedTab
        ZStack(alignment: .bottom) {
            ThemedBarBackground(tab: tab)
            field(tab)
                .padding(.horizontal, 16)
                .padding(.bottom, 6)
            if let tab, tab.isLoading {
                ProgressLine(progress: tab.progress)
            }
        }
    }

    private func field(_ tab: TabInfo?) -> some View {
        HStack(spacing: 8) {
            if let tab, let record = containers.record(tab.contextId) {
                ContainerSwitchMenu(tab: tab, record: record)
            }
            if tab?.isPrivate == true {
                Image(systemName: "eyeglasses")
                    .foregroundStyle(DejavuColor.privateAccent)
                    .accessibilityLabel(L10n.privateWorkspace)
            }
            if let tab, tab.isWebPage {
                Image(systemName: tab.isSecure ? "lock.fill" : "lock.open")
                    .font(.footnote)
                    .foregroundStyle(DejavuColor.onSurfaceVariant)
            }
            Text(title(tab))
                .font(.body.weight(.medium))
                .foregroundStyle(tab == nil ? DejavuColor.onSurfaceVariant : DejavuColor.onSurface)
                .lineLimit(1)
                .accessibilityHidden(true)
        }
        .padding(.horizontal, 12)
        .frame(maxWidth: .infinity)
        .frame(height: 44)
        .glass(Capsule())
        .contentShape(Capsule())
        .onTapGesture { app.startSearch(editing: tab?.id) }
        .contextMenu { longPressMenu(tab) }
        .gesture(
            DragGesture(minimumDistance: 20).onEnded { value in
                if value.translation.height > 30 {
                    app.showMenu()
                }
            }
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title(tab))
        .accessibilityHint(L10n.searchHint)
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(named: Text(L10n.iosMenu)) { app.showMenu() }
    }

    private func title(_ tab: TabInfo?) -> String {
        guard let tab else { return L10n.searchHint }
        return repository.state.titleOf(tab)
    }

    @ViewBuilder
    private func longPressMenu(_ tab: TabInfo?) -> some View {
        if let tab, tab.isWebPage {
            Button {
                UIPasteboard.general.string = tab.url
            } label: {
                Label(L10n.iosCopyAddress, systemImage: "doc.on.doc")
            }
        }
        // Only asking whether there is text keeps iOS from telling the user the clipboard was read.
        if UIPasteboard.general.hasStrings {
            Button {
                let text = UIPasteboard.general.string ?? ""
                app.search = SearchRequest(text: text, tabId: tab?.id)
            } label: {
                Label(L10n.iosPaste, systemImage: "doc.on.clipboard")
            }
            Button {
                let text = UIPasteboard.general.string ?? ""
                app.submitSearch(text, request: SearchRequest(text: text, tabId: tab?.id))
            } label: {
                Label(L10n.iosPasteAndGo, systemImage: "arrow.right.circle")
            }
        }
    }
}

/// The icon of the shown tab's container in its color. Tapping it lists the other containers to reopen the tab in.
private struct ContainerSwitchMenu: View {
    let tab: TabInfo
    let record: ContainerRecord

    @ObservedObject private var containers = ContainerStore.shared

    var body: some View {
        Menu {
            Button {
                reopen(in: .noContainer)
            } label: {
                Label(L10n.workspaceNoContainer, image: "dejavu-no-container")
            }
            Button {
                reopen(in: .temporary)
            } label: {
                Label(L10n.newTemporaryContainer, image: "dejavu-temporary-container")
            }
            Divider()
            ForEach(containers.pickerOrder.filter { $0.contextId != record.contextId }) { other in
                Button {
                    reopen(in: .container(other.contextId))
                } label: {
                    Label(other.name, image: other.icon.assetName)
                }
            }
        } label: {
            ContainerIconView(record: record, size: 18)
                .frame(width: 28, height: 28)
        }
        .accessibilityLabel(L10n.toolbarContainer(record.name))
    }

    /// Reopens the shown tab in the picked container, keeping its place, pin and workspace.
    private func reopen(in pick: ContainerPick) {
        let contextId = ContainerActions.contextId(for: pick)
        guard contextId != tab.contextId else { return }
        let repository = WorkspaceRepository.shared
        if let pin = repository.state.pinOf(tab.id) {
            repository.setPinContainer([pin.id], containerId: contextId)
        }
        BrowserStore.shared.reopen(tab.id, inContainer: contextId, select: true, load: true)
    }
}

extension ContainerIcon {
    /// The name of the icon in the asset catalog.
    var assetName: String {
        "container-\(rawValue)"
    }
}

/// The page loading progress, as a thin golden line at the bottom of the top bar.
struct ProgressLine: View {
    let progress: Double

    var body: some View {
        GeometryReader { proxy in
            Rectangle()
                .fill(DejavuColor.primary)
                .frame(width: proxy.size.width * CGFloat(progress.clamped(0.05, 1)), height: 2)
                .animation(.easeOut(duration: 0.2), value: progress)
        }
        .frame(height: 2)
    }
}

/// A button of the actions bar.
private struct BarItem: Identifiable {
    let id: String
    let look: EntryLook
    let action: () -> Void
}

/// Dejavu's bar below web pages: the first row of the "More" menu, then a button that edits the address, with Home in
/// the middle. Swiping the bar up opens the whole menu.
struct ActionsBar: View {
    @ObservedObject var screenState: BrowserScreenState

    @ObservedObject private var app = AppModel.shared
    @ObservedObject private var store = BrowserStore.shared
    @ObservedObject private var repository = WorkspaceRepository.shared
    @ObservedObject private var settings = DejavuSettings.shared
    @ObservedObject private var bookmarks = BookmarkStore.shared

    var body: some View {
        let tab = store.selectedTab
        let items = barItems(tab)
        let half = items.count / 2
        ZStack(alignment: .top) {
            ThemedBarBackground(tab: tab)
            Capsule()
                .fill(DejavuColor.onSurfaceVariant.opacity(0.3))
                .frame(width: 28, height: 3)
                .padding(.top, 4)
            HStack(spacing: 0) {
                HStack(spacing: 0) {
                    ForEach(items.prefix(half)) { item in
                        BarButton(item: item).frame(maxWidth: .infinity)
                    }
                }
                .frame(maxWidth: .infinity)
                BarButton(item: homeItem).frame(width: 64)
                HStack(spacing: 0) {
                    ForEach(items.dropFirst(half)) { item in
                        BarButton(item: item).frame(maxWidth: .infinity)
                    }
                }
                .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 8)
            .frame(height: 56)
        }
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 15).onEnded { value in
                if value.translation.height < -25 {
                    app.showMenu()
                }
            }
        )
        .accessibilityAction(named: Text(L10n.iosMenu)) { app.showMenu() }
    }

    private var homeItem: BarItem {
        BarItem(id: "home", look: EntryLook(label: L10n.actionsBarHome, icon: .symbol("house"))) {
            app.showHome()
        }
    }

    private func barItems(_ tab: TabInfo?) -> [BarItem] {
        let state = tab.map { app.menuState(for: $0) } ?? MenuState()
        let entries = MoreMenuLayout.resolve(settings.moreMenuRows, customActions: settings.customActions).first ?? []
        let search = BarItem(
            id: "search", look: EntryLook(label: L10n.actionsBarSearch, icon: .symbol("magnifyingglass"))
        ) {
            app.startSearch(editing: tab?.id)
        }
        let entryItems = entries.map { entry in
            BarItem(id: entry.key, look: app.look(of: entry, state: state)) {
                if let tab {
                    app.run(entry, tabId: tab.id)
                }
            }
        }
        return entryItems + [search]
    }
}

private struct BarButton: View {
    let item: BarItem

    var body: some View {
        let look = item.look
        Button(action: item.action) {
            Image(icon: look.icon)
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .frame(width: 22, height: 22)
                .foregroundStyle(look.active ? DejavuColor.onSecondaryContainer : DejavuColor.onSurface)
                .frame(width: 48, height: 48)
                .background(Circle().fill(look.active ? DejavuColor.secondaryContainer : Color.clear))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!look.enabled)
        .opacity(look.enabled ? 1 : 0.38)
        .accessibilityLabel(look.label)
    }
}

/// The bar of the split pane: tapping it shows its tab on top, dragging it resizes the pane.
struct SplitPaneBar: View {
    @ObservedObject var screenState: BrowserScreenState

    /// Called with where the top of the bar should be, in the coordinates of the screen.
    let onDrag: (CGFloat) -> Void

    @ObservedObject private var store = BrowserStore.shared
    @ObservedObject private var repository = WorkspaceRepository.shared
    @State private var startTop: CGFloat?

    var body: some View {
        GeometryReader { proxy in
            if let tabId = screenState.splitTabId, let tab = store.tab(tabId) {
                content(tab)
                    .gesture(
                        DragGesture(minimumDistance: 4, coordinateSpace: .global)
                            .onChanged { value in
                                if startTop == nil {
                                    startTop = proxy.frame(in: .global).minY
                                }
                                onDrag((startTop ?? 0) + value.translation.height)
                            }
                            .onEnded { _ in startTop = nil }
                    )
            }
        }
    }

    private func content(_ tab: TabInfo) -> some View {
        ZStack(alignment: .top) {
            DejavuColor.surfaceContainerHigh
            Capsule()
                .fill(DejavuColor.onSurfaceVariant.opacity(0.4))
                .frame(width: 32, height: 4)
                .padding(.top, 4)
            HStack(spacing: 10) {
                FaviconView(url: tab.url, size: 16)
                Text(repository.state.titleOf(tab))
                    .font(.subheadline)
                    .foregroundStyle(DejavuColor.onSurface)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                IconButton(
                    icon: .symbol("chevron.up"), label: L10n.splitShowOnTop, tint: DejavuColor.onSurfaceVariant,
                    size: 16
                ) {
                    AppModel.shared.openTab(tab.id)
                }
                IconButton(
                    icon: .symbol("xmark"), label: L10n.splitClose, tint: DejavuColor.onSurfaceVariant, size: 16
                ) {
                    WorkspaceRepository.shared.unsplit([tab.id])
                }
            }
            .padding(.leading, 14)
            .frame(maxHeight: .infinity)
        }
        .contentShape(Rectangle())
        .onTapGesture { AppModel.shared.openTab(tab.id) }
        .accessibilityAction(named: Text(L10n.splitShowOnTop)) { AppModel.shared.openTab(tab.id) }
    }
}

/// Finds text in the shown page.
struct FindInPageBar: View {
    @ObservedObject private var app = AppModel.shared
    @State private var text = ""
    @State private var result: FinderResult?
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 4) {
            TextField(L10n.iosFindInPagePlaceholder, text: $text)
                .textFieldStyle(.roundedBorder)
                .focused($focused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit { find(fresh: false, backwards: false) }
            Text(countText)
                .font(.footnote.monospacedDigit())
                .foregroundStyle(DejavuColor.onSurfaceVariant)
                .frame(minWidth: 44)
            IconButton(icon: .symbol("chevron.up"), label: L10n.iosPreviousMatch, size: 16) {
                find(fresh: false, backwards: true)
            }
            IconButton(icon: .symbol("chevron.down"), label: L10n.iosNextMatch, size: 16) {
                find(fresh: false, backwards: false)
            }
            Button(L10n.iosDone) { close() }
                .padding(.trailing, 8)
        }
        .padding(.leading, 12)
        .frame(maxHeight: .infinity)
        .background(DejavuColor.surfaceContainerHigh)
        .onChange(of: text) { _, _ in find(fresh: true, backwards: false) }
        .onChange(of: app.findInPageTabId) { _, tabId in
            text = ""
            result = nil
            focused = tabId != nil
        }
    }

    private var countText: String {
        guard let result, !text.isEmpty else { return "" }
        if !result.found {
            return "0/0"
        }
        return result.total > 0 ? "\(result.current)/\(result.total)" : ""
    }

    private func find(fresh: Bool, backwards: Bool) {
        guard let tabId = app.findInPageTabId, let session = BrowserStore.shared.session(for: tabId) else { return }
        let query = text
        if query.isEmpty {
            session.clearFindMatches()
            result = nil
            return
        }
        Task {
            let found = await session.find(fresh ? query : nil, backwards: backwards)
            result = found
            session.displayFindMatches()
        }
    }

    private func close() {
        if let tabId = app.findInPageTabId {
            BrowserStore.shared.session(for: tabId)?.clearFindMatches()
        }
        focused = false
        app.findInPageTabId = nil
    }
}
