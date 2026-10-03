// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Combine
import Foundation

/// A saved page.
struct Bookmark: Identifiable, Equatable {
    let id: String
    var url: String
    var title: String
    let createdAt: Int64
}

/// The user's bookmarks, newest first, saved in bookmarks.json. Used from the main thread only.
final class BookmarkStore: ObservableObject {
    static let shared = BookmarkStore()

    private static let fileName = "bookmarks.json"

    @Published private(set) var bookmarks: [Bookmark]

    private init() {
        bookmarks = (DataFiles.readObject(Self.fileName)?.objects("bookmarks") ?? []).compactMap { item in
            guard let id = item.string("id"), let url = item.string("url") else { return nil }
            return Bookmark(id: id, url: url, title: item.string("title") ?? url, createdAt: item.int64("createdAt") ?? 0)
        }
    }

    func contains(_ url: String) -> Bool {
        bookmarks.contains { $0.url == url }
    }

    /// Saves `url` unless it is saved already.
    func add(url: String, title: String) {
        guard !url.isEmpty, !contains(url) else { return }
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        bookmarks.insert(Bookmark(id: newId(), url: url, title: trimmed.isEmpty ? url : trimmed, createdAt: nowMillis()), at: 0)
        save()
    }

    func update(_ id: String, title: String, url: String) {
        guard let index = bookmarks.firstIndex(where: { $0.id == id }) else { return }
        bookmarks[index].title = title
        bookmarks[index].url = url
        save()
    }

    func remove(_ id: String) {
        bookmarks.removeAll { $0.id == id }
        save()
    }

    /// Removes the bookmarks of `url`.
    func remove(url: String) {
        bookmarks.removeAll { $0.url == url }
        save()
    }

    private func save() {
        let items: [[String: Any]] = bookmarks.map {
            ["id": $0.id, "url": $0.url, "title": $0.title, "createdAt": $0.createdAt]
        }
        DataFiles.writeObject(["version": 1, "bookmarks": items], to: Self.fileName)
    }
}

/// A visited page.
struct HistoryEntry: Identifiable, Equatable {
    let id: String
    let url: String
    var title: String
    var visitedAt: Int64
}

/// Pages visited in tabs that are not private, newest first, saved in history.json. A page visited again moves to
/// the top instead of being listed twice. Used from the main thread only.
final class HistoryStore: ObservableObject {
    static let shared = HistoryStore()

    private static let fileName = "history.json"
    private static let maxEntries = 3000
    private static let saveDelay: TimeInterval = 2

    @Published private(set) var entries: [HistoryEntry]

    private var saveScheduled = false

    private init() {
        entries = (DataFiles.readObject(Self.fileName)?.objects("entries") ?? []).compactMap { item in
            guard let id = item.string("id"), let url = item.string("url") else { return nil }
            return HistoryEntry(
                id: id, url: url, title: item.string("title") ?? "", visitedAt: item.int64("visitedAt") ?? 0)
        }
    }

    /// Records a visit of `url`.
    func record(url: String, title: String) {
        guard url.hasPrefix("http://") || url.hasPrefix("https://") else { return }
        var entry = HistoryEntry(id: newId(), url: url, title: title, visitedAt: nowMillis())
        if let index = entries.firstIndex(where: { $0.url == url }) {
            if entry.title.isEmpty {
                entry.title = entries[index].title
            }
            entries.remove(at: index)
        }
        entries.insert(entry, at: 0)
        if entries.count > Self.maxEntries {
            entries.removeLast(entries.count - Self.maxEntries)
        }
        scheduleSave()
    }

    /// Gives the latest visit of `url` its page title, once the page told it.
    func updateTitle(url: String, title: String) {
        guard !title.isEmpty, let index = entries.firstIndex(where: { $0.url == url }),
            entries[index].title != title
        else {
            return
        }
        entries[index].title = title
        scheduleSave()
    }

    func remove(_ id: String) {
        entries.removeAll { $0.id == id }
        scheduleSave()
    }

    func clear() {
        entries.removeAll()
        scheduleSave()
    }

    /// Visited pages whose address or title contains `text`, for address bar suggestions.
    func matches(_ text: String, limit: Int) -> [HistoryEntry] {
        let query = text.trimmingCharacters(in: .whitespaces).lowercased()
        guard !query.isEmpty else { return [] }
        return Array(
            entries.lazy.filter { $0.url.lowercased().contains(query) || $0.title.lowercased().contains(query) }
                .prefix(limit))
    }

    private func scheduleSave() {
        guard !saveScheduled else { return }
        saveScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.saveDelay) { [weak self] in
            self?.save()
        }
    }

    private func save() {
        saveScheduled = false
        let items: [[String: Any]] = entries.map {
            ["id": $0.id, "url": $0.url, "title": $0.title, "visitedAt": $0.visitedAt]
        }
        DataFiles.writeObject(["version": 1, "entries": items], to: Self.fileName)
    }
}
