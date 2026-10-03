// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// The history and page state of a `GeckoSession`, like GeckoView Android's `GeckoSession.SessionState`. It comes
/// from `ProgressDelegate.onSessionStateChange`, can be saved as JSON, and is given back to `GeckoSession.restoreState`.
public struct GeckoSessionState {
    private var fields: [String: Any]

    public init() {
        fields = [:]
    }

    /// Reads a state saved with `json`, or returns `nil` when the text is not one.
    public init?(json: String) {
        guard let data = json.data(using: .utf8),
            let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else {
            return nil
        }
        fields = object
    }

    /// The state as JSON text, or `nil` when it holds something JSON cannot store.
    public var json: String? {
        guard JSONSerialization.isValidJSONObject(fields),
            let data = try? JSONSerialization.data(withJSONObject: fields)
        else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    /// Whether the state has history entries to restore.
    public var hasHistory: Bool {
        !historyEntries.isEmpty
    }

    /// The address of the current history entry, if the state has one.
    public var currentUrl: String? {
        let entries = historyEntries
        guard !entries.isEmpty, let history = fields["history"] as? [String: Any] else { return nil }
        // Session history counts its entries from 1.
        let index = (history["index"] as? NSNumber)?.intValue ?? entries.count
        let entry = entries[min(max(index - 1, 0), entries.count - 1)]
        return entry["url"] as? String
    }

    var message: [String: Any?] {
        var result: [String: Any?] = [:]
        for (key, value) in fields {
            result[key] = value
        }
        return result
    }

    private var historyEntries: [[String: Any]] {
        guard let history = fields["history"] as? [String: Any],
            let entries = history["entries"] as? [[String: Any]]
        else {
            return []
        }
        return entries
    }

    /// Merges an update from Gecko, like GeckoView Android's `SessionState.updateSessionState`.
    mutating func update(with data: [String: Any?]) {
        if let change = Self.plain(data["historychange"] ?? nil) as? [String: Any] {
            let fromIndex = (change["fromIdx"] as? NSNumber)?.intValue ?? -1
            var history = change
            history.removeValue(forKey: "fromIdx")
            if fromIndex != -1 {
                // Only the entries after `fromIndex` changed; the ones up to it are kept. Gecko sends a huge index
                // when no entry changed at all.
                let previous = ((fields["history"] as? [String: Any])?["entries"] as? [Any]) ?? []
                let changed = (change["entries"] as? [Any]) ?? []
                if fromIndex >= Int(Int32.max) - 1 {
                    history["entries"] = previous
                } else {
                    let kept = min(max(fromIndex + 1, 0), previous.count)
                    history["entries"] = Array(previous.prefix(kept)) + changed
                }
            }
            fields["history"] = history
        }
        if let scroll = Self.plain(data["scroll"] ?? nil) as? [String: Any] {
            fields["scrolldata"] = scroll
        }
        if let formdata = Self.plain(data["formdata"] ?? nil) as? [String: Any] {
            fields["formdata"] = formdata
        }
    }

    /// `value` with every `nil` turned into `NSNull` and every container into a plain dictionary or array, so JSON
    /// can store it.
    static func plain(_ value: Any?) -> Any {
        guard let value else { return NSNull() }
        let mirror = Mirror(reflecting: value)
        if mirror.displayStyle == .optional {
            guard let wrapped = mirror.children.first?.value else { return NSNull() }
            return plain(wrapped)
        }
        if let dictionary = value as? [String: Any] {
            var result: [String: Any] = [:]
            for (key, item) in dictionary {
                result[key] = plain(item)
            }
            return result
        }
        if let array = value as? [Any] {
            return array.map { plain($0) }
        }
        return value
    }
}
