// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import os
import SwiftUI
import UIKit

/// The current time in milliseconds since 1970, the unit Dejavu keeps times in, like on Android.
func nowMillis() -> Int64 {
    Int64((Date().timeIntervalSince1970 * 1000).rounded())
}

/// A new random id in the form Android's `UUID.randomUUID().toString()` gives.
func newId() -> String {
    UUID().uuidString.lowercased()
}

/// Dejavu's log, readable with Console.app on a Mac connected to the phone.
enum Log {
    private static let logger = Logger(subsystem: "com.byarchitect.dejavu", category: "Dejavu")
    private static let syncLogger = Logger(subsystem: "com.byarchitect.dejavu", category: "DejavuSync")

    static func info(_ message: String) {
        logger.info("\(message, privacy: .public)")
    }

    static func error(_ message: String) {
        logger.error("\(message, privacy: .public)")
    }

    /// What spaces sync did, like Android's `DejavuSync` log tag.
    static func sync(_ message: String) {
        syncLogger.info("\(message, privacy: .public)")
    }
}

/// The files Dejavu keeps its data in, in Application Support/Dejavu.
enum DataFiles {
    static let directory: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let url = base.appendingPathComponent("Dejavu", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()

    static func url(_ name: String) -> URL {
        directory.appendingPathComponent(name)
    }

    static func read(_ name: String) -> Data? {
        try? Data(contentsOf: url(name))
    }

    /// Reads a file holding one JSON object.
    static func readObject(_ name: String) -> [String: Any]? {
        guard let data = read(name) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    static func write(_ data: Data, to name: String) {
        do {
            try data.write(to: url(name), options: [.atomic])
        } catch {
            Log.error("Could not save \(name): \(error)")
        }
    }

    /// Writes one JSON object to a file.
    static func writeObject(_ object: [String: Any], to name: String) {
        guard JSONSerialization.isValidJSONObject(object),
            let data = try? JSONSerialization.data(withJSONObject: object)
        else {
            Log.error("Could not write \(name) as JSON")
            return
        }
        write(data, to: name)
    }

    static func remove(_ name: String) {
        try? FileManager.default.removeItem(at: url(name))
    }
}

/// Typed reads of JSON objects, like org.json's `opt` methods on Android: a missing or mistyped value gives `nil`.
extension Dictionary where Key == String, Value == Any {
    func string(_ key: String) -> String? {
        self[key] as? String
    }

    /// The string at `key`, or `nil` when it is missing, JSON null or empty, like Android's `optStringOrNull`.
    func nonEmptyString(_ key: String) -> String? {
        guard let value = self[key] as? String, !value.isEmpty else { return nil }
        return value
    }

    func int64(_ key: String) -> Int64? {
        (self[key] as? NSNumber)?.int64Value
    }

    func int(_ key: String) -> Int? {
        (self[key] as? NSNumber)?.intValue
    }

    func double(_ key: String) -> Double? {
        (self[key] as? NSNumber)?.doubleValue
    }

    func bool(_ key: String) -> Bool? {
        (self[key] as? NSNumber)?.boolValue
    }

    func object(_ key: String) -> [String: Any]? {
        self[key] as? [String: Any]
    }

    /// The objects of the array at `key`; other values are skipped.
    func objects(_ key: String) -> [[String: Any]] {
        (self[key] as? [Any])?.compactMap { $0 as? [String: Any] } ?? []
    }

    /// The strings of the array at `key`, or `nil` when there is no array. Other values are skipped.
    func strings(_ key: String) -> [String]? {
        (self[key] as? [Any])?.compactMap { $0 as? String }
    }

    /// The string values of the object at `key`.
    func stringMap(_ key: String) -> [String: String] {
        (self[key] as? [String: Any])?.compactMapValues { $0 as? String } ?? [:]
    }
}

extension Array {
    /// The elements in order, without the later ones whose `key` was already seen, like Kotlin's `distinctBy`.
    func uniqued<Key: Hashable>(by key: (Element) -> Key) -> [Element] {
        var seen = Set<Key>()
        return filter { seen.insert(key($0)).inserted }
    }
}

extension Comparable {
    func clamped(_ lower: Self, _ upper: Self) -> Self {
        Swift.min(Swift.max(self, lower), upper)
    }
}

/// An icon of Dejavu's interface: an SF Symbol, or an image of the asset catalog.
enum Icon: Equatable {
    case symbol(String)
    case asset(String)

    var uiImage: UIImage? {
        switch self {
        case .symbol(let name):
            return UIImage(systemName: name)
        case .asset(let name):
            return UIImage(named: name)?.withRenderingMode(.alwaysTemplate)
        }
    }
}

extension Image {
    init(icon: Icon) {
        switch icon {
        case .symbol(let name):
            self.init(systemName: name)
        case .asset(let name):
            self.init(name)
        }
    }
}

extension UIColor {
    /// A color from an ARGB value as Android keeps colors, `0xAARRGGBB`, also when stored as a negative number.
    convenience init(argb: Int) {
        let value = UInt32(truncatingIfNeeded: argb)
        self.init(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: CGFloat((value >> 24) & 0xFF) / 255)
    }

    /// A color that follows light and dark mode.
    static func dynamic(light: UIColor, dark: UIColor) -> UIColor {
        UIColor { traits in traits.userInterfaceStyle == .dark ? dark : light }
    }
}

extension Color {
    /// A color from an ARGB value as Android keeps colors, `0xAARRGGBB`.
    init(argb: Int) {
        self.init(uiColor: UIColor(argb: argb))
    }
}

/// `0xAARRGGBB` as the signed 32-bit number Android keeps colors in.
func argbInt(_ value: UInt32) -> Int {
    Int(Int32(bitPattern: value))
}
