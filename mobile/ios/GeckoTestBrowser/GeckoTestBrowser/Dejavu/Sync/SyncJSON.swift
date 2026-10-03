// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// JSON helpers for sync records, like Android's `SyncJson`. Values are those `JSONSerialization` reads: dictionaries,
/// arrays, strings, numbers, booleans and `NSNull`, plus Swift's own `Bool`, `Int` and `Double`.
///
/// `canonical` writes object keys in sorted order at every level, so two records with the same content always give the
/// same text. That is how a local record is compared with the server's copy. Uploads use it too, since the order of keys
/// means nothing to other devices.
enum SyncJSON {
    private static let maxExactInteger = 1e15

    static func canonical(_ value: Any?) -> String {
        var out = ""
        write(&out, value)
        return out
    }

    static func parseObject(_ text: String?) -> [String: Any]? {
        parse(text) as? [String: Any]
    }

    static func parseArray(_ text: String?) -> [Any]? {
        parse(text) as? [Any]
    }

    private static func parse(_ text: String?) -> Any? {
        guard let data = text?.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }

    private static func write(_ out: inout String, _ value: Any?) {
        guard let value, !(value is NSNull) else {
            out += "null"
            return
        }
        if let string = value as? String {
            quote(&out, string)
        } else if let number = value as? NSNumber {
            out += self.number(number)
        } else if let object = value as? [String: Any] {
            out += "{"
            for (index, key) in object.keys.sorted().enumerated() {
                if index > 0 {
                    out += ","
                }
                quote(&out, key)
                out += ":"
                write(&out, object[key] ?? nil)
            }
            out += "}"
        } else if let array = value as? [Any] {
            out += "["
            for (index, item) in array.enumerated() {
                if index > 0 {
                    out += ","
                }
                write(&out, item)
            }
            out += "]"
        } else {
            quote(&out, "\(value)")
        }
    }

    /// Integral numbers are written without a fraction, like JavaScript does, so 1.0 and 1 compare equal.
    private static func number(_ number: NSNumber) -> String {
        if isBoolean(number) {
            return number.boolValue ? "true" : "false"
        }
        let type = String(cString: number.objCType)
        guard type == "d" || type == "f" else { return number.stringValue }
        let double = number.doubleValue
        if double.isNaN || double.isInfinite {
            return "null"
        }
        if double == double.rounded() && abs(double) < maxExactInteger {
            return String(Int64(double))
        }
        return "\(double)"
    }

    private static func quote(_ out: inout String, _ text: String) {
        out += "\""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            default:
                if scalar.value < 0x20 || scalar.value == 0x2028 || scalar.value == 0x2029 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        out += "\""
    }

    fileprivate static func isBoolean(_ number: NSNumber) -> Bool {
        CFGetTypeID(number) == CFBooleanGetTypeID()
    }
}

/// The boolean `value` holds, or `nil` when it is not a boolean. Numbers are not booleans, like on Android where
/// `opt(key) == true` only holds for JSON `true`.
func jsonBool(_ value: Any?) -> Bool? {
    if let number = value as? NSNumber {
        return SyncJSON.isBoolean(number) ? number.boolValue : nil
    }
    return value as? Bool
}

/// Whether `value` is JSON `true`.
func isJSONTrue(_ value: Any?) -> Bool {
    jsonBool(value) == true
}

/// Whether `value` is JSON `false`; a missing value is not.
func isJSONFalse(_ value: Any?) -> Bool {
    jsonBool(value) == false
}

/// The number `value` holds as an integer, or a number written as text, or 0, like org.json's `optInt`.
func jsonInt(_ value: Any?) -> Int {
    if let number = value as? NSNumber, !SyncJSON.isBoolean(number) {
        return number.intValue
    }
    if let text = value as? String, let number = Double(text.trimmingCharacters(in: .whitespaces)) {
        return Int(number)
    }
    return 0
}

/// The number `value` holds, or a number written as text, or `fallback`, like org.json's `optDouble`.
func jsonDouble(_ value: Any?, _ fallback: Double) -> Double {
    if let number = value as? NSNumber, !SyncJSON.isBoolean(number) {
        return number.doubleValue
    }
    if let text = value as? String, let number = Double(text.trimmingCharacters(in: .whitespaces)) {
        return number
    }
    return fallback
}

extension Dictionary where Key == String, Value == Any {
    /// Puts `value`, writing JSON null instead of removing `key` when it is `nil`, as sync records keep null fields.
    mutating func putNullable(_ key: String, _ value: Any?) {
        self[key] = value ?? NSNull()
    }
}
