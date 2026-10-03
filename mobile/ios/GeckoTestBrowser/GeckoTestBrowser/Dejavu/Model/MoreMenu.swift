// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// A built-in item of the browser's "More" menu. The raw value is the stable key the menu layout is saved with, the
/// same as in the Android app. The Android items the iOS engine cannot do yet (extensions, passwords, downloads,
/// translations, summaries, site issue reports, printing and PDFs) are left out.
enum MoreMenuItem: String, CaseIterable {
    case back
    case forward
    case share
    case refresh
    case findInPage = "find_in_page"
    case bookmarkPage = "bookmark_page"
    case desktopSite = "desktop_site"
    case bookmarks
    case history
    case pinTab = "pin_tab"
    case essentialTab = "essential_tab"
    case openInApp = "open_in_app"
    case resetPinnedUrl = "reset_pinned_url"
    case replacePinnedUrl = "replace_pinned_url"
    case splitView = "split_view"
    case settings

    var key: String {
        rawValue
    }

    var label: String {
        switch self {
        case .back: return L10n.menuBack
        case .forward: return L10n.menuForward
        case .share: return L10n.menuShare
        case .refresh: return L10n.menuRefresh
        case .findInPage: return L10n.menuFindInPage
        case .bookmarkPage: return L10n.menuBookmarkPage
        case .desktopSite: return L10n.menuDesktopSite
        case .bookmarks: return L10n.menuBookmarks
        case .history: return L10n.menuHistory
        case .pinTab: return L10n.menuPinTab
        case .essentialTab: return L10n.menuEssentialTab
        case .openInApp: return L10n.menuOpenInApp
        case .resetPinnedUrl: return L10n.menuResetPinnedUrl
        case .replacePinnedUrl: return L10n.menuReplacePinnedUrl
        case .splitView: return L10n.menuSplitView
        case .settings: return L10n.menuSettings
        }
    }

    var icon: Icon {
        switch self {
        case .back: return .symbol("chevron.backward")
        case .forward: return .symbol("chevron.forward")
        case .share: return .symbol("square.and.arrow.up")
        case .refresh: return .symbol("arrow.clockwise")
        case .findInPage: return .symbol("doc.text.magnifyingglass")
        case .bookmarkPage: return .symbol("bookmark")
        case .desktopSite: return .symbol("desktopcomputer")
        case .bookmarks: return .symbol("book")
        case .history: return .symbol("clock.arrow.circlepath")
        case .pinTab: return .symbol("pin")
        case .essentialTab: return .symbol("square.grid.2x2")
        case .openInApp: return .symbol("arrow.up.forward.app")
        case .resetPinnedUrl: return .symbol("arrow.counterclockwise")
        case .replacePinnedUrl: return .symbol("pin.fill")
        case .splitView: return .asset("dejavu-split")
        case .settings: return .symbol("gearshape")
        }
    }
}

/// An item of the "More" menu: a built-in one or one of the user's custom actions.
enum MoreMenuEntry: Equatable {
    case builtIn(MoreMenuItem)
    case custom(CustomAction)

    var key: String {
        switch self {
        case .builtIn(let item): return item.key
        case .custom(let action): return RowAction.keyOf(action.id)
        }
    }

    var label: String {
        switch self {
        case .builtIn(let item): return item.label
        case .custom(let action): return action.name
        }
    }

    var icon: Icon {
        switch self {
        case .builtIn(let item): return item.icon
        case .custom: return .symbol("bolt")
        }
    }
}

/// The rows of the "More" menu, as lists of entry keys. A row holds up to `maxPerRow` items. The first row is the
/// actions bar below pages.
enum MoreMenuLayout {
    static let maxPerRow = 4

    static let defaultRows: [[String]] = [
        [MoreMenuItem.back, .forward, .share, .refresh],
        [MoreMenuItem.findInPage, .desktopSite, .pinTab, .openInApp],
        [MoreMenuItem.history, .bookmarks, .splitView, .settings],
    ].map { row in row.map { $0.key } }

    /// Whether the entry with `key` fills a row on its own. On Android only the extensions row does, which iOS does
    /// not have yet.
    static func isFullRow(_ key: String) -> Bool {
        false
    }

    /// The entry with `key`, or `nil` if it does not exist (anymore).
    static func entry(of key: String, customActions: [CustomAction]) -> MoreMenuEntry? {
        if let item = MoreMenuItem(rawValue: key) {
            return .builtIn(item)
        }
        return customActions.first { RowAction.keyOf($0.id) == key }.map { MoreMenuEntry.custom($0) }
    }

    /// Every entry that can be put in the menu.
    static func available(customActions: [CustomAction]) -> [MoreMenuEntry] {
        MoreMenuItem.allCases.map { MoreMenuEntry.builtIn($0) } + customActions.map { MoreMenuEntry.custom($0) }
    }

    /// The entries of `rows`, leaving out the ones that no longer exist and rows left empty.
    static func resolve(_ rows: [[String]], customActions: [CustomAction]) -> [[MoreMenuEntry]] {
        normalized(rows).map { row in row.compactMap { entry(of: $0, customActions: customActions) } }
            .filter { !$0.isEmpty }
    }

    /// `rows` without duplicates or empty rows, with full row entries on rows of their own and at most `maxPerRow`
    /// entries per row.
    static func normalized(_ rows: [[String]]) -> [[String]] {
        var seen = Set<String>()
        var result: [[String]] = []
        for row in rows {
            let keys = row.filter { seen.insert($0).inserted }
            let full = keys.filter { isFullRow($0) }
            let others = keys.filter { !isFullRow($0) }
            result += full.map { [$0] }
            var index = 0
            while index < others.count {
                result.append(Array(others[index..<min(index + maxPerRow, others.count)]))
                index += maxPerRow
            }
        }
        return result.filter { !$0.isEmpty }
    }

    static func toJson(_ rows: [[String]]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: rows) else { return "[]" }
        return String(data: data, encoding: .utf8) ?? "[]"
    }

    static func fromJson(_ json: String?) -> [[String]]? {
        guard let data = json?.data(using: .utf8),
            let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
        else {
            return nil
        }
        return array.map { row in
            ((row as? [Any]) ?? []).compactMap { $0 as? String }.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        }
    }
}
