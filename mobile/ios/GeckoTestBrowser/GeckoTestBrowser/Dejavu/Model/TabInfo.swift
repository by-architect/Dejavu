// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// Where a tab came from, which decides the container it opens in, like android-components' `SessionState.Source`.
enum TabSource: String {
    /// Typed or searched in the address bar.
    case userEntered = "user"

    /// Started with New Tab on the home screen.
    case newTab = "new_tab"

    /// A link from another app.
    case external

    /// Opened by a page, like `window.open` or a link to a new window.
    case page

    /// Brought back from the tabs saved at the last run.
    case restored

    /// Opened by spaces sync.
    case sync

    /// Opened by Dejavu itself, like a pinned tab reopened or a tab moved to another container.
    case app

    /// Whether the user started the tab, so it may open in the container picked for new tabs.
    var isUserStarted: Bool {
        self == .userEntered || self == .newTab
    }
}

/// An open tab: its page and where it is in the browser. Its Gecko session lives in `BrowserStore`.
///
/// - `contextId`: Container the tab's cookies and site data are kept in, or `nil` for none. It never changes.
/// - `isAwake`: Whether the tab holds a loaded page in memory, as opposed to sleeping or not restored yet.
struct TabInfo: Identifiable, Equatable {
    let id: String
    var url: String
    var title: String
    let contextId: String?
    let isPrivate: Bool
    var parentId: String?
    var source: TabSource
    var createdAt: Int64
    var lastAccess: Int64
    var isAwake = false
    var isLoading = false
    var progress: Double = 0
    var canGoBack = false
    var canGoForward = false
    var isSecure = false
    var desktopMode = false

    /// The page's title, or the name of its site when the page has no title of its own.
    var displayTitle: String {
        pageLabel(title: title, url: url)
    }

    /// Whether the tab shows a web page, which can be shared, bookmarked or sent with custom actions.
    var isWebPage: Bool {
        url.hasPrefix("http://") || url.hasPrefix("https://")
    }

    var pinSource: PinSource {
        PinSource(tabId: id, url: url, title: title, containerId: contextId)
    }
}

/// The page's `title`, like browsers show on tabs, or its address when the page has no title.
func pageLabel(title: String?, url: String) -> String {
    let text = (title ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    if text.isEmpty {
        return displayUrl(url)
    }
    if text.hasPrefix("http://") || text.hasPrefix("https://") {
        return displayUrl(text)
    }
    return text
}

/// `url` without its scheme and "www.", the way Firefox shows addresses.
func displayUrl(_ url: String) -> String {
    var text = url
    for prefix in ["https://", "http://"] where text.hasPrefix(prefix) {
        text.removeFirst(prefix.count)
    }
    if text.hasPrefix("www.") {
        text.removeFirst(4)
    }
    if text.hasSuffix("/") && text.filter({ $0 == "/" }).count == 1 {
        text.removeLast()
    }
    return text
}
