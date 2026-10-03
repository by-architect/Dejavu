// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// Texts of the browser that only the iOS app has, in English, where Android uses Firefox's own texts. The Android
/// app's Dejavu texts are in L10n+Generated.swift.
extension L10n {
    // MARK: Address bar

    /// Menu
    static var iosMenu: String { tr("dejavu_ios_menu", "Menu") }
    /// Copy address
    static var iosCopyAddress: String { tr("dejavu_ios_copy_address", "Copy address") }
    /// Paste
    static var iosPaste: String { tr("dejavu_ios_paste", "Paste") }
    /// Paste and go
    static var iosPasteAndGo: String { tr("dejavu_ios_paste_and_go", "Paste and go") }
    /// Clear
    static var iosClear: String { tr("dejavu_ios_clear", "Clear") }
    /// Done
    static var iosDone: String { tr("dejavu_ios_done", "Done") }

    // MARK: Find in page

    /// Find in page
    static var iosFindInPagePlaceholder: String { tr("dejavu_ios_find_in_page_placeholder", "Find in page") }
    /// Previous match
    static var iosPreviousMatch: String { tr("dejavu_ios_previous_match", "Previous match") }
    /// Next match
    static var iosNextMatch: String { tr("dejavu_ios_next_match", "Next match") }

    // MARK: Links

    /// Open in new tab
    static var iosOpenInNewTab: String { tr("dejavu_ios_open_in_new_tab", "Open in new tab") }
    /// Open in private tab
    static var iosOpenInPrivateTab: String { tr("dejavu_ios_open_in_private_tab", "Open in private tab") }
    /// Open image in new tab
    static var iosOpenImageInNewTab: String { tr("dejavu_ios_open_image_in_new_tab", "Open image in new tab") }
    /// Copy image link
    static var iosCopyImageLink: String { tr("dejavu_ios_copy_image_link", "Copy image link") }
    /// New tab opened
    static var iosTabOpened: String { tr("dejavu_ios_tab_opened", "New tab opened") }
    /// Switch
    static var iosSwitch: String { tr("dejavu_ios_switch", "Switch") }

    // MARK: Other apps

    /// Open in another app?
    static var iosOpenInAppTitle: String { tr("dejavu_ios_open_in_app_title", "Open in another app?") }
    /// This link wants to open an app on your phone.
    static var iosOpenInAppMessage: String {
        tr("dejavu_ios_open_in_app_message", "This link wants to open an app on your phone.")
    }
    /// Open
    static var iosOpen: String { tr("dejavu_ios_open", "Open") }
    /// No app on this phone opens this link
    static var iosNoAppForLink: String { tr("dejavu_ios_no_app_for_link", "No app on this phone opens this link") }

    // MARK: Bookmarks and history

    /// Remove bookmark
    static var iosRemoveBookmark: String { tr("dejavu_ios_remove_bookmark", "Remove bookmark") }
    /// Bookmark removed
    static var iosBookmarkRemoved: String { tr("dejavu_ios_bookmark_removed", "Bookmark removed") }
    /// No bookmarks yet
    static var iosNoBookmarks: String { tr("dejavu_ios_no_bookmarks", "No bookmarks yet") }
    /// No history yet
    static var iosNoHistory: String { tr("dejavu_ios_no_history", "No history yet") }
    /// Clear browsing history?
    static var iosClearHistoryTitle: String { tr("dejavu_ios_clear_history_title", "Clear browsing history?") }
    /// Clear history
    static var iosClearHistory: String { tr("dejavu_ios_clear_history", "Clear history") }
}
