// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// Kinds of records in Zen's "spaces" sync collection, as written by Zen's ZenSpacesSyncModel.
enum RecordKind {
    static let container = "container"
    static let space = "space"
    static let tab = "tab"
    static let folder = "folder"
    static let split = "split"
    static let layout = "layout"
}

/// Id of the single record holding the order of the spaces and of the essentials.
let layoutRecordId = "layout"

/// One decrypted record of the spaces collection: a `kind` with its `data` as Zen writes them, or a tombstone. Records
/// are values; changing the data of one makes a new record.
struct SpacesRecord {
    let id: String
    let kind: String?
    let data: [String: Any]?
    let deleted: Bool

    /// Text that two records share exactly when they have the same content.
    let fingerprint: String

    private static let tombstoneFingerprint = "deleted"

    private init(id: String, kind: String?, data: [String: Any]?, deleted: Bool) {
        self.id = id
        self.kind = kind
        self.data = data
        self.deleted = deleted
        if deleted {
            fingerprint = Self.tombstoneFingerprint
        } else {
            fingerprint = SyncJSON.canonical(["kind": kind ?? NSNull(), "data": data ?? NSNull()] as [String: Any])
        }
    }

    static func of(_ id: String, kind: String, data: [String: Any]) -> SpacesRecord {
        SpacesRecord(id: id, kind: kind, data: data, deleted: false)
    }

    static func tombstone(_ id: String) -> SpacesRecord {
        SpacesRecord(id: id, kind: nil, data: nil, deleted: true)
    }

    /// Whether this is a pinned tab, essential, folder, space, container, pinned split or the layout, which are synced.
    var isSynced: Bool {
        guard !deleted else { return false }
        switch kind {
        case RecordKind.tab, RecordKind.split:
            return !isJSONFalse(data?["pinned"])
        case RecordKind.container, RecordKind.space, RecordKind.folder, RecordKind.layout:
            return true
        default:
            return false
        }
    }

    /// Whether this is a tab that is not pinned, which Zen syncs when "Include unpinned tabs" is on.
    var isNormalTab: Bool {
        !deleted && kind == RecordKind.tab && isJSONFalse(data?["pinned"]) && !isJSONTrue(data?["essential"])
    }

    func sameAs(_ other: SpacesRecord?) -> Bool {
        guard let other else { return false }
        return fingerprint == other.fingerprint
    }

    func toCleartext() -> [String: Any] {
        if deleted {
            return ["id": id, "deleted": true]
        }
        return ["id": id, "kind": kind ?? NSNull(), "data": data ?? NSNull()]
    }

    /// Reads a decrypted cleartext, or returns `nil` when it is not a record of a kind this version knows.
    static func fromCleartext(_ id: String, _ cleartext: [String: Any]) -> SpacesRecord? {
        if isJSONTrue(cleartext["deleted"]) {
            return tombstone(id)
        }
        guard let kind = cleartext.string("kind"), let data = cleartext.object("data") else { return nil }
        return of(id, kind: kind, data: data)
    }

    /// Reads a record written by `toCleartext`.
    static func fromCleartext(_ cleartext: [String: Any]) -> SpacesRecord? {
        guard let id = cleartext.string("id") else { return nil }
        return fromCleartext(id, cleartext)
    }
}

/// Records by id, in the order they were first added, like Kotlin's `LinkedHashMap`: replacing a record keeps its place.
struct OrderedRecords {
    private(set) var ids: [String] = []
    private var byId: [String: SpacesRecord] = [:]

    var count: Int {
        ids.count
    }

    var isEmpty: Bool {
        ids.isEmpty
    }

    var values: [SpacesRecord] {
        ids.compactMap { byId[$0] }
    }

    subscript(id: String) -> SpacesRecord? {
        get { byId[id] }
        set {
            if let newValue {
                if byId.updateValue(newValue, forKey: id) == nil {
                    ids.append(id)
                }
            } else {
                remove(id)
            }
        }
    }

    mutating func remove(_ id: String) {
        if byId.removeValue(forKey: id) != nil {
            ids.removeAll { $0 == id }
        }
    }

    mutating func removeAll() {
        ids.removeAll()
        byId.removeAll()
    }
}

/// A space or folder icon as Dejavu shows it: `nil` for none, Zen also writes "" for none.
func iconOf(_ value: Any?) -> String? {
    guard let icon = value as? String, !icon.isEmpty else { return nil }
    return icon
}

/// The container color Dejavu shows for a synced color name.
func containerColorOf(_ value: Any?) -> ContainerColor {
    syncedColor(ContainerColor.fromKey(value as? String))
}

/// `color` as it is synced: the color of temporary containers is Dejavu's own, so it goes out as gray.
func syncedColor(_ color: ContainerColor?) -> ContainerColor {
    guard let color, color != .white else { return .gray }
    return color
}

/// The container icon Dejavu shows for a synced icon name.
func containerIconOf(_ value: Any?) -> ContainerIcon {
    (value as? String).flatMap { ContainerIcon(rawValue: $0) } ?? .circle
}

/// Firefox's four default containers, which Zen syncs under well-known ids instead of per-profile ones. Dejavu creates
/// them when a synced space or tab uses one.
enum BuiltinContainer: CaseIterable {
    case personal
    case work
    case banking
    case shopping

    var guid: String {
        switch self {
        case .personal: return "builtin-1"
        case .work: return "builtin-2"
        case .banking: return "builtin-3"
        case .shopping: return "builtin-4"
        }
    }

    var icon: ContainerIcon {
        switch self {
        case .personal: return .fingerprint
        case .work: return .briefcase
        case .banking: return .dollar
        case .shopping: return .cart
        }
    }

    var color: ContainerColor {
        switch self {
        case .personal: return .blue
        case .work: return .orange
        case .banking: return .green
        case .shopping: return .pink
        }
    }

    static func of(_ guid: String?) -> BuiltinContainer? {
        allCases.first { $0.guid == guid }
    }
}

/// Converts space themes. Zen's theme is `{type: "gradient", gradientColors, opacity, texture}` where each gradient color
/// is a dot of its color picker with the color in `c`. Dejavu keeps up to three colors, the opacity and the grain.
enum ZenThemes {
    private static let maxColors = 3
    private static let zenDefaultOpacity = 0.5
    private static let freeHarmony = "floating"
    private static let explicitLightness = "explicit-lightness"
    private static let channelMax = 255

    /// The theme Dejavu shows for a synced theme; `nil` for none, including Zen's default theme without colors.
    static func toDejavu(_ value: Any?) -> WorkspaceTheme? {
        guard let theme = value as? [String: Any], let dots = theme["gradientColors"] as? [Any] else { return nil }
        let colors = dots.compactMap { ($0 as? [String: Any]).flatMap(dotColor) }.prefix(maxColors)
        if colors.isEmpty {
            return nil
        }
        return WorkspaceTheme(
            colors: Array(colors),
            opacity: unit(jsonDouble(theme["opacity"], zenDefaultOpacity)),
            texture: unit(jsonDouble(theme["texture"], 0)))
    }

    /// The synced form of `theme`. Dots of `previous` that still have the same color are kept as they are, so Zen keeps
    /// their place on its color picker.
    static func fromDejavu(_ theme: WorkspaceTheme?, previous: Any?) -> Any {
        guard let theme else { return NSNull() }
        let before = previous as? [String: Any]
        let previousDots = before?["gradientColors"] as? [Any] ?? []
        var dots: [Any] = []
        for (index, color) in theme.colors.enumerated() {
            if index < previousDots.count, let kept = previousDots[index] as? [String: Any], dotColor(kept) == color {
                dots.append(kept)
            } else {
                dots.append(newDot(color, primary: index == 0))
            }
        }
        var result = before ?? [:]
        result["type"] = "gradient"
        result["gradientColors"] = dots
        result["opacity"] = theme.opacity
        result["texture"] = theme.texture
        return result
    }

    private static func newDot(_ color: Int, primary: Bool) -> [String: Any] {
        let (red, green, blue) = channels(color)
        return [
            "c": [red, green, blue],
            "isCustom": false,
            "algorithm": freeHarmony,
            "isPrimary": primary,
            "lightness": lightness(red, green, blue),
            "type": explicitLightness,
        ]
    }

    private static func dotColor(_ dot: [String: Any]) -> Int? {
        let color = dot["c"]
        if let text = color as? String {
            return CssColors.parse(text)
        }
        guard let values = color as? [Any], values.count >= 3 else { return nil }
        return argb(jsonDouble(values[0], .nan), jsonDouble(values[1], .nan), jsonDouble(values[2], .nan))
    }

    private static func channels(_ color: Int) -> (Int, Int, Int) {
        ((color >> 16) & channelMax, (color >> 8) & channelMax, color & channelMax)
    }

    private static func argb(_ red: Double, _ green: Double, _ blue: Double) -> Int? {
        if red.isNaN || green.isNaN || blue.isNaN {
            return nil
        }
        func channel(_ value: Double) -> UInt32 {
            UInt32(value.clamped(0, Double(channelMax)).rounded())
        }
        return argbInt(0xFF00_0000 | (channel(red) << 16) | (channel(green) << 8) | channel(blue))
    }

    private static func lightness(_ red: Int, _ green: Int, _ blue: Int) -> Int {
        let high = max(red, green, blue)
        let low = min(red, green, blue)
        return Int((Double(high + low) * 100 / (2.0 * Double(channelMax))).rounded())
    }

    private static func unit(_ value: Double) -> Double {
        value.isNaN ? 0 : value.clamped(0, 1)
    }
}

/// Reads the CSS colors Zen allows as custom theme colors: `#rgb`, `#rrggbb` (with alpha) and `rgb()` or `rgba()`.
enum CssColors {
    private static let rgbFunction = try? NSRegularExpression(
        pattern: #"^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+).*\)$"#, options: [.caseInsensitive])

    static func parse(_ text: String) -> Int? {
        let value = text.trimmingCharacters(in: .whitespaces)
        if value.hasPrefix("#") {
            let hex = String(value.dropFirst())
            let full: String
            switch hex.count {
            case 3, 4: full = hex.prefix(3).map { "\($0)\($0)" }.joined()
            case 6, 8: full = String(hex.prefix(6))
            default: return nil
            }
            guard let rgb = UInt32(full, radix: 16) else { return nil }
            return argbInt(0xFF00_0000 | rgb)
        }
        let range = NSRange(value.startIndex..., in: value)
        guard let match = rgbFunction?.firstMatch(in: value, range: range), match.numberOfRanges == 4 else {
            return nil
        }
        var channels: [UInt32] = []
        for index in 1...3 {
            guard let group = Range(match.range(at: index), in: value), let number = Double(value[group]) else {
                return nil
            }
            channels.append(UInt32(number.clamped(0, 255).rounded()))
        }
        return argbInt(0xFF00_0000 | (channels[0] << 16) | (channels[1] << 8) | channels[2])
    }
}
