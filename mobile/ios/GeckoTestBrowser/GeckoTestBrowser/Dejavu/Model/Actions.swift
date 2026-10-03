// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// An action that can be applied to a tab or a pinned tab, either from the buttons of a tab row or from the selection
/// bar. The raw value is the stable key the user's choices are saved with.
enum TabAction: String, CaseIterable {
    case close
    case pin
    case unpin
    case sleep
    case bookmark
    case share
    case copyLink = "copy_link"
    case duplicate
    case moveToWorkspace = "move_to_workspace"
    case changeContainer = "change_container"
    case splitView = "split_view"
    case unsplit
    case moveToFolder = "move_to_folder"
    case newFolder = "new_folder"
    case resetPin = "reset_pin"
    case addToEssentials = "add_to_essentials"
    case removeFromEssentials = "remove_from_essentials"
    case newSubfolder = "new_subfolder"
    case renameFolder = "rename_folder"
    case renameTab = "rename_tab"
    case unpackFolder = "unpack_folder"
    case delete

    var key: String {
        rawValue
    }

    var label: String {
        switch self {
        case .close: return L10n.actionClose
        case .pin: return L10n.actionPin
        case .unpin: return L10n.actionUnpin
        case .sleep: return L10n.actionSleep
        case .bookmark: return L10n.actionBookmark
        case .share: return L10n.actionShare
        case .copyLink: return L10n.actionCopyLink
        case .duplicate: return L10n.actionDuplicate
        case .moveToWorkspace: return L10n.actionMoveToWorkspace
        case .changeContainer: return L10n.actionChangeContainer
        case .splitView: return L10n.actionSplitView
        case .unsplit: return L10n.actionUnsplit
        case .moveToFolder: return L10n.actionMoveToFolder
        case .newFolder: return L10n.actionNewFolder
        case .resetPin: return L10n.actionResetPin
        case .addToEssentials: return L10n.actionAddToEssentials
        case .removeFromEssentials: return L10n.actionRemoveFromEssentials
        case .newSubfolder: return L10n.folderNewSubfolder
        case .renameFolder: return L10n.folderRename
        case .renameTab: return L10n.actionRenameTab
        case .unpackFolder: return L10n.folderUnpack
        case .delete: return L10n.actionDelete
        }
    }

    var icon: Icon {
        switch self {
        case .close: return .symbol("xmark")
        case .pin: return .symbol("pin")
        case .unpin: return .symbol("pin.slash")
        case .sleep: return .asset("dejavu-sleep")
        case .bookmark: return .symbol("bookmark")
        case .share: return .symbol("square.and.arrow.up")
        case .copyLink: return .symbol("link")
        case .duplicate: return .symbol("plus.square.on.square")
        case .moveToWorkspace: return .symbol("arrow.right")
        case .changeContainer: return .asset("dejavu-container")
        case .splitView: return .asset("dejavu-split")
        case .unsplit: return .asset("dejavu-unsplit")
        case .moveToFolder: return .symbol("folder")
        case .newFolder: return .symbol("folder.badge.plus")
        case .resetPin: return .symbol("arrow.counterclockwise")
        case .addToEssentials: return .symbol("square.grid.2x2")
        case .removeFromEssentials: return .symbol("square.grid.2x2.fill")
        case .newSubfolder: return .symbol("folder.badge.plus")
        case .renameFolder: return .symbol("pencil")
        case .renameTab: return .symbol("pencil")
        case .unpackFolder: return .symbol("tray.and.arrow.up")
        case .delete: return .symbol("trash")
        }
    }

    /// Actions the user can put on pinned tab rows, in display order.
    static let forPinnedRows: [TabAction] = [
        .unpin, .resetPin, .addToEssentials, .renameTab, .sleep, .bookmark, .share, .copyLink, .duplicate,
        .moveToFolder, .moveToWorkspace, .changeContainer, .close,
    ]

    /// Actions the user can put on unpinned tab rows, in display order.
    static let forUnpinnedRows: [TabAction] = [
        .pin, .addToEssentials, .renameTab, .sleep, .bookmark, .share, .copyLink, .duplicate, .moveToFolder,
        .moveToWorkspace, .changeContainer, .close,
    ]

    /// Actions of the selection bar, in display order.
    static let forSelection: [TabAction] = [
        .share, .sleep, .bookmark, .copyLink, .pin, .unpin, .resetPin, .renameTab, .addToEssentials,
        .removeFromEssentials, .splitView, .unsplit, .newFolder, .newSubfolder, .renameFolder, .moveToFolder,
        .moveToWorkspace, .changeContainer, .unpackFolder, .duplicate, .delete, .close,
    ]
}

/// An action that can run on tabs: one of Dejavu's `TabAction`s or a `CustomAction` the user defined.
enum RowAction: Equatable {
    case builtIn(TabAction)
    case custom(CustomAction)

    /// Stable identifier used to save the user's choice of row buttons.
    var key: String {
        switch self {
        case .builtIn(let action): return action.key
        case .custom(let action): return RowAction.keyOf(action.id)
        }
    }

    var label: String {
        switch self {
        case .builtIn(let action): return action.label
        case .custom(let action): return action.name
        }
    }

    var icon: Icon {
        switch self {
        case .builtIn(let action): return action.icon
        case .custom: return .symbol("bolt")
        }
    }

    /// Whether the action closes or deletes something, which its button shows in red.
    var isDestructive: Bool {
        self == .builtIn(.close) || self == .builtIn(.delete)
    }

    static func keyOf(_ customActionId: String) -> String {
        "custom:\(customActionId)"
    }

    /// Everything that can be put on pinned or unpinned rows, in display order: Dejavu's actions, then the custom
    /// ones, then Close so it stays the last button.
    static func available(pinned: Bool, customActions: [CustomAction]) -> [RowAction] {
        let builtIns = pinned ? TabAction.forPinnedRows : TabAction.forUnpinnedRows
        return builtIns.filter { $0 != .close }.map { RowAction.builtIn($0) }
            + customActions.map { RowAction.custom($0) }
            + [RowAction.builtIn(.close)]
    }

    /// Everything the selection bar can show, in display order: Dejavu's actions, the custom ones, then Delete and
    /// Close.
    static func selectionBar(customActions: [CustomAction]) -> [RowAction] {
        let last = TabAction.forSelection.filter { $0 == .delete || $0 == .close }
        let first = TabAction.forSelection.filter { $0 != .delete && $0 != .close }
        return first.map { RowAction.builtIn($0) } + customActions.map { RowAction.custom($0) }
            + last.map { RowAction.builtIn($0) }
    }
}

/// HTTP methods a custom action can send with. The response is never shown, so these only push data out.
enum HttpMethod: String, CaseIterable {
    case GET
    case POST
    case PUT
    case DELETE

    /// Whether the request carries the action's body.
    var hasBody: Bool {
        self == .POST || self == .PUT
    }
}

/// A request header of a `CustomAction`. Both parts may contain `ActionVariable` placeholders.
struct CustomHeader: Equatable {
    var name: String
    var value: String
}

/// A user defined tab action that sends an HTTP request for every tab it runs on, like a `curl` command.
///
/// - `name`: Label of the action's button.
/// - `url`: Request URL. May contain `ActionVariable` placeholders, which are URL encoded.
/// - `body`: Request body for methods that have one. Placeholders are JSON escaped when the Content-Type is JSON.
struct CustomAction: Equatable, Identifiable {
    var id: String
    var name: String
    var method: HttpMethod
    var url: String
    var headers: [CustomHeader]
    var body: String

    func toJson() -> [String: Any] {
        [
            "id": id,
            "name": name,
            "method": method.rawValue,
            "url": url,
            "headers": headers.map { ["name": $0.name, "value": $0.value] },
            "body": body,
        ]
    }

    static func fromJson(_ json: [String: Any]) -> CustomAction? {
        guard let id = json.string("id"),
            let name = json.string("name"),
            let method = HttpMethod(rawValue: json.string("method") ?? ""),
            let url = json.string("url")
        else {
            return nil
        }
        let headers = json.objects("headers").compactMap { header -> CustomHeader? in
            guard let name = header.string("name"), let value = header.string("value") else { return nil }
            return CustomHeader(name: name, value: value)
        }
        return CustomAction(id: id, name: name, method: method, url: url, headers: headers, body: json.string("body") ?? "")
    }
}

/// Values a `CustomAction` can use through `${key}` placeholders.
enum ActionVariable: String, CaseIterable {
    case websiteUrl = "websiteurl"
    case websiteTitle = "websitetitle"
    case websiteHost = "websitehost"
    case container
    case workspace
    case folderPath = "folderpath"
    case date

    var key: String {
        rawValue
    }

    var token: String {
        "${\(rawValue)}"
    }
}

/// The values of the `ActionVariable`s for one tab.
///
/// - `container`: Container name, empty without a container.
/// - `folderPath`: Folders of a pinned tab separated by "/", empty when it is not in a folder.
/// - `date`: Time the action runs, ISO 8601.
struct ActionContext {
    let url: String
    let title: String
    let container: String
    let workspace: String
    let folderPath: String
    let date: String

    func value(of variable: ActionVariable) -> String {
        switch variable {
        case .websiteUrl: return url
        case .websiteTitle: return title
        case .websiteHost: return URL(string: url)?.host ?? ""
        case .container: return container
        case .workspace: return workspace
        case .folderPath: return folderPath
        case .date: return date
        }
    }

    /// The time now as custom actions write it, like `2026-10-03T14:05:00+03:00`.
    static func currentDate() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ssXXX"
        return formatter.string(from: Date())
    }
}

/// Replaces every `ActionVariable` placeholder of `template` with its value in `context`, passed through `encode`.
func renderTemplate(_ template: String, context: ActionContext, encode: (String) -> String) -> String {
    ActionVariable.allCases.reduce(template) { text, variable in
        text.contains(variable.token)
            ? text.replacingOccurrences(of: variable.token, with: encode(context.value(of: variable))) : text
    }
}
