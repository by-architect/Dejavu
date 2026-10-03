// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Combine
import SwiftUI
import UIKit

/// Container colors as Firefox desktop (and so Zen) names them today. The raw value is the name used for storage and
/// sync.
enum ContainerColor: String, CaseIterable {
    case gray
    case yellow
    case orange
    case red
    case pink
    case purple
    case violet
    case blue
    case cyan
    case green

    /// Dejavu's color for temporary containers. It is never offered for other containers, nor synced.
    case white

    /// The desktop "nova" color value.
    var argb: UInt32 {
        switch self {
        case .gray: return 0xFF94_9297
        case .yellow: return 0xFFDB_820E
        case .orange: return 0xFFF4_682C
        case .red: return 0xFFED_566E
        case .pink: return 0xFFDB_54BF
        case .purple: return 0xFFB8_64EE
        case .violet: return 0xFF98_71FF
        case .blue: return 0xFF5A_87FD
        case .cyan: return 0xFF10_A4CA
        case .green: return 0xFF11_AE84
        case .white: return 0xFFF4_F4F6
        }
    }

    var uiColor: UIColor {
        UIColor(argb: argbInt(argb))
    }

    var color: Color {
        Color(uiColor: uiColor)
    }

    /// The colors a container the user makes can have.
    static var pickable: [ContainerColor] {
        allCases.filter { $0 != .white }
    }

    /// Resolves a desktop color name, including the legacy "turquoise" and "toolbar" aliases.
    static func fromKey(_ key: String?) -> ContainerColor? {
        switch key {
        case "turquoise": return .cyan
        case "toolbar": return .gray
        default: return key.flatMap { ContainerColor(rawValue: $0) }
        }
    }
}

/// Container icons as Firefox desktop names them, in the order Firefox offers them.
enum ContainerIcon: String, CaseIterable {
    case fingerprint
    case briefcase
    case dollar
    case cart
    case circle
    case gift
    case vacation
    case food
    case fruit
    case pet
    case tree
    case chill
    case fence

    /// The icon, drawn from Firefox desktop's own container icons in the asset catalog.
    var icon: Icon {
        .asset("container-\(rawValue)")
    }
}

/// A container as Dejavu stores it. The fields mirror a Firefox desktop contextual identity so containers can be synced
/// with Zen; `contextId` is the GeckoView context the container's tabs are isolated in.
struct ContainerRecord: Equatable, Identifiable {
    var contextId: String
    var name: String
    var color: ContainerColor
    var icon: ContainerIcon
    var createdAt: Int64
    var updatedAt: Int64
    var temporary: Bool = false

    var id: String {
        contextId
    }
}

/// Which container a new tab opens in.
enum ContainerPick: Equatable, Hashable {
    case noContainer

    /// A new temporary container, see `ContainerStore.createTemporary`.
    case temporary

    case container(String)

    private static let containerPrefix = "container:"

    /// Stable form used to save the pick.
    var key: String {
        switch self {
        case .noContainer: return "none"
        case .temporary: return "temporary"
        case .container(let contextId): return Self.containerPrefix + contextId
        }
    }

    static func fromKey(_ key: String?) -> ContainerPick? {
        guard let key else { return nil }
        if key == "none" {
            return .noContainer
        }
        if key == "temporary" {
            return .temporary
        }
        if key.hasPrefix(containerPrefix) {
            return .container(String(key.dropFirst(containerPrefix.count)))
        }
        return nil
    }
}

/// Dejavu's containers, saved in containers.json. Temporary containers, like Firefox's Temporary Containers add-on,
/// are made on demand for new tabs, named "tmp1", "tmp2" and so on with the lowest free number, and destroyed with
/// their cookies and site data once their last tab is closed. Used from the main thread only.
final class ContainerStore: ObservableObject {
    static let shared = ContainerStore()

    private static let fileName = "containers.json"
    private static let temporaryPrefix = "tmp"

    /// All containers, in the order they were made.
    @Published private(set) var records: [ContainerRecord]

    private init() {
        records = Self.read()
    }

    /// Containers the user made, without the temporary ones.
    var permanent: [ContainerRecord] {
        records.filter { !$0.temporary }
    }

    /// Temporary containers, tmp1 before tmp2 before tmp10.
    var temporaryRecords: [ContainerRecord] {
        records.filter { $0.temporary }.sorted { ($0.name.count, $0.name) < ($1.name.count, $1.name) }
    }

    /// Permanent containers, then temporary ones, as pickers list them.
    var pickerOrder: [ContainerRecord] {
        permanent + temporaryRecords
    }

    var byId: [String: ContainerRecord] {
        Dictionary(records.map { ($0.contextId, $0) }, uniquingKeysWith: { first, _ in first })
    }

    func record(_ contextId: String?) -> ContainerRecord? {
        guard let contextId else { return nil }
        return records.first { $0.contextId == contextId }
    }

    func isTemporary(_ contextId: String) -> Bool {
        records.contains { $0.contextId == contextId && $0.temporary }
    }

    /// Adds a new container, or updates the name, color and icon of the container with the same `contextId`, and
    /// returns its id.
    @discardableResult
    func save(contextId: String?, name: String, color: ContainerColor, icon: ContainerIcon) -> String {
        let now = nowMillis()
        if let contextId, let index = records.firstIndex(where: { $0.contextId == contextId }) {
            var updated = records[index]
            updated.name = name
            updated.color = color
            updated.icon = icon
            updated.updatedAt = now
            records[index] = updated
            write()
            return contextId
        }
        let id = contextId ?? newId()
        records.append(
            ContainerRecord(contextId: id, name: name, color: color, icon: icon, createdAt: now, updatedAt: now))
        write()
        return id
    }

    /// Starts a new temporary container and returns its id.
    func createTemporary() -> String {
        let taken = Set(records.filter { $0.temporary }.map { $0.name })
        var number = 1
        while taken.contains("\(Self.temporaryPrefix)\(number)") {
            number += 1
        }
        let now = nowMillis()
        let id = newId()
        records.append(
            ContainerRecord(
                contextId: id, name: "\(Self.temporaryPrefix)\(number)", color: .white, icon: .circle,
                createdAt: now, updatedAt: now, temporary: true))
        write()
        return id
    }

    /// Removes container `contextId` from storage. Its tabs and data are handled by `ContainerActions.remove`.
    func remove(_ contextId: String) {
        records.removeAll { $0.contextId == contextId }
        write()
    }

    private static func read() -> [ContainerRecord] {
        guard let data = DataFiles.read(fileName),
            let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
        else {
            return []
        }
        return array.compactMap { value in
            guard let item = value as? [String: Any],
                let contextId = item.string("contextId"),
                let name = item.string("name"),
                let color = ContainerColor.fromKey(item.string("color")),
                let icon = ContainerIcon(rawValue: item.string("icon") ?? "")
            else {
                return nil
            }
            return ContainerRecord(
                contextId: contextId,
                name: name,
                color: color,
                icon: icon,
                createdAt: item.int64("createdAt") ?? 0,
                updatedAt: item.int64("updatedAt") ?? 0,
                temporary: item.bool("temporary") ?? false)
        }
    }

    private func write() {
        let array: [[String: Any]] = records.map { record in
            [
                "contextId": record.contextId,
                "name": record.name,
                "color": record.color.rawValue,
                "icon": record.icon.rawValue,
                "createdAt": record.createdAt,
                "updatedAt": record.updatedAt,
                "temporary": record.temporary,
            ]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: array) else { return }
        DataFiles.write(data, to: Self.fileName)
    }
}
