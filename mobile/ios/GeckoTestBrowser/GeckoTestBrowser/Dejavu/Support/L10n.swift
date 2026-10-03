// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// Dejavu's texts in the user's language. Most come from the Android app's translations through
/// `tools/dejavu_l10n.py` (see L10n+Generated.swift); the few only iOS needs are in L10n+iOS.swift.
enum L10n {
    /// The text of `key` in the user's language, or `english` when there is no translation, with `arguments` put in
    /// its placeholders. Plural forms are chosen by the numbers among `arguments`.
    static func tr(_ key: String, _ english: String, _ arguments: CVarArg...) -> String {
        let format = Bundle.main.localizedString(forKey: key, value: english, table: nil)
        if arguments.isEmpty {
            return format
        }
        return String(format: format, locale: Locale.current, arguments: arguments)
    }
}
