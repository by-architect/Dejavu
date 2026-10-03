// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

/// The search screen: the address bar sits right above the keyboard at full width, like on Android, with suggestions
/// from the bookmarks and the history above it.
struct SearchView: View {
    let request: SearchRequest

    @ObservedObject private var app = AppModel.shared
    @ObservedObject private var history = HistoryStore.shared
    @ObservedObject private var bookmarks = BookmarkStore.shared
    @ObservedObject private var settings = DejavuSettings.shared
    @State private var text: String
    @FocusState private var focused: Bool

    init(request: SearchRequest) {
        self.request = request
        _text = State(initialValue: request.text)
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    suggestions
                }
                .padding(.horizontal, 8)
                .padding(.top, 8)
            }
            .scrollDismissesKeyboard(.never)
            .defaultScrollAnchor(.bottom)
            field
        }
        .background(DejavuColor.surface.ignoresSafeArea())
        .onAppear { focused = true }
    }

    private var accent: Color {
        request.isPrivate ? DejavuColor.privateAccent : DejavuColor.primary
    }

    private var field: some View {
        HStack(spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: request.isPrivate ? "eyeglasses" : "magnifyingglass")
                    .foregroundStyle(request.isPrivate ? DejavuColor.privateAccent : DejavuColor.onSurfaceVariant)
                TextField(L10n.searchHint, text: $text)
                    .focused($focused)
                    .keyboardType(.webSearch)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.go)
                    .onSubmit { submit(text) }
                if !text.isEmpty {
                    Button {
                        text = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(DejavuColor.onSurfaceVariant)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L10n.iosClear)
                }
            }
            .padding(.horizontal, 14)
            .frame(height: 46)
            .glass(Capsule())
            Button(L10n.cancel) { app.cancelSearch() }
                .foregroundStyle(accent)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(DejavuColor.surfaceContainerHigh.ignoresSafeArea(edges: .bottom))
    }

    @ViewBuilder
    private var suggestions: some View {
        let query = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if query.isEmpty {
            PasteButton(payloadType: String.self) { strings in
                if let pasted = strings.first {
                    text = pasted
                }
            }
            .buttonBorderShape(.capsule)
            .tint(accent)
            .padding(12)
        } else {
            let bookmarkMatches = bookmarks.bookmarks.filter {
                $0.url.localizedCaseInsensitiveContains(query) || $0.title.localizedCaseInsensitiveContains(query)
            }
            ForEach(bookmarkMatches.prefix(3)) { bookmark in
                SuggestionRow(icon: "bookmark", title: bookmark.title, detail: displayUrl(bookmark.url)) {
                    submit(bookmark.url)
                }
            }
            if !request.isPrivate {
                ForEach(history.matches(query, limit: 6)) { entry in
                    SuggestionRow(
                        icon: "clock", title: entry.title.isEmpty ? displayUrl(entry.url) : entry.title,
                        detail: displayUrl(entry.url)
                    ) {
                        submit(entry.url)
                    }
                }
            }
            SuggestionRow(icon: "magnifyingglass", title: query, detail: settings.searchEngine.name) {
                submit(query)
            }
        }
    }

    private func submit(_ value: String) {
        focused = false
        app.submitSearch(value, request: request)
    }
}

/// A suggestion of the search screen.
private struct SuggestionRow: View {
    let icon: String
    let title: String
    let detail: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .frame(width: 24)
                    .foregroundStyle(DejavuColor.onSurfaceVariant)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .foregroundStyle(DejavuColor.onSurface)
                        .lineLimit(1)
                    Text(detail)
                        .font(.footnote)
                        .foregroundStyle(DejavuColor.onSurfaceVariant)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// The bookmarks and the history.
struct LibraryView: View {
    @State var kind: LibraryKind

    @ObservedObject private var bookmarks = BookmarkStore.shared
    @ObservedObject private var history = HistoryStore.shared
    @State private var query = ""
    @State private var confirmingClear = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if kind == .bookmarks {
                    bookmarkRows
                } else {
                    historyRows
                }
            }
            .listStyle(.plain)
            .searchable(text: $query)
            .navigationTitle(kind == .bookmarks ? L10n.bookmarks : L10n.history)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar }
            .confirmationDialog(L10n.iosClearHistoryTitle, isPresented: $confirmingClear, titleVisibility: .visible) {
                Button(L10n.iosClearHistory, role: .destructive) { history.clear() }
                Button(L10n.cancel, role: .cancel) {}
            }
        }
        .tint(DejavuColor.primary)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            Picker("", selection: $kind) {
                Text(L10n.bookmarks).tag(LibraryKind.bookmarks)
                Text(L10n.history).tag(LibraryKind.history)
            }
            .pickerStyle(.segmented)
            .frame(maxWidth: 260)
        }
        ToolbarItem(placement: .confirmationAction) {
            Button(L10n.iosDone) { dismiss() }
        }
        if kind == .history && !history.entries.isEmpty {
            ToolbarItem(placement: .bottomBar) {
                Button(L10n.iosClearHistory, role: .destructive) { confirmingClear = true }
            }
        }
    }

    @ViewBuilder
    private var bookmarkRows: some View {
        let shown = bookmarks.bookmarks.filter(matches)
        if shown.isEmpty {
            Text(L10n.iosNoBookmarks).foregroundStyle(DejavuColor.onSurfaceVariant)
        }
        ForEach(shown) { bookmark in
            LibraryRow(url: bookmark.url, title: bookmark.title) { open(bookmark.url) }
                .swipeActions {
                    Button(L10n.delete, role: .destructive) { bookmarks.remove(bookmark.id) }
                }
        }
    }

    @ViewBuilder
    private var historyRows: some View {
        let shown = history.entries.filter { matches(url: $0.url, title: $0.title) }
        if shown.isEmpty {
            Text(L10n.iosNoHistory).foregroundStyle(DejavuColor.onSurfaceVariant)
        }
        ForEach(shown.prefix(500)) { entry in
            LibraryRow(url: entry.url, title: entry.title) { open(entry.url) }
                .swipeActions {
                    Button(L10n.delete, role: .destructive) { history.remove(entry.id) }
                }
        }
    }

    private func matches(_ bookmark: Bookmark) -> Bool {
        matches(url: bookmark.url, title: bookmark.title)
    }

    private func matches(url: String, title: String) -> Bool {
        let text = query.trimmingCharacters(in: .whitespaces)
        return text.isEmpty || url.localizedCaseInsensitiveContains(text) || title.localizedCaseInsensitiveContains(text)
    }

    /// Opens `url` in a new tab of the shown workspace.
    private func open(_ url: String) {
        dismiss()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            AppModel.shared.openNewTab(url: url, source: .userEntered)
        }
    }
}

/// A bookmark or a visited page in the library.
private struct LibraryRow: View {
    let url: String
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                FaviconView(url: url, size: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title.isEmpty ? displayUrl(url) : title)
                        .foregroundStyle(DejavuColor.onSurface)
                        .lineLimit(1)
                    Text(displayUrl(url))
                        .font(.footnote)
                        .foregroundStyle(DejavuColor.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
        }
        .contextMenu {
            Button {
                UIPasteboard.general.string = url
            } label: {
                Label(L10n.actionCopyLink, systemImage: "link")
            }
        }
    }
}
