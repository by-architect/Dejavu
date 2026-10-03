// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import UIKit

/// What the home screen asks for, like Android's `DejavuHomeInteractor`.
extension AppModel {
    func saveWorkspace(id: String?, name: String, containerId: String?, icon: String?, theme: WorkspaceTheme?) {
        if let id {
            repository.updateWorkspace(id: id, name: name, containerId: containerId, icon: icon, theme: theme)
        } else {
            repository.addWorkspace(name: name, containerId: containerId, icon: icon, theme: theme)
        }
    }

    /// Runs a built-in action that needs no further input from the user, from the page of `workspaceId`.
    func runTabAction(_ action: TabAction, targets: ActionTargets, workspaceId: String) {
        switch action {
        case .close:
            closeTabs(targets.openTabs.map { $0.id })
        case .pin:
            repository.pinTabs(targets.tabs.map { $0.pinSource })
        case .unpin:
            // Like dragging a pin out: unpinned tabs stay as tabs, the closed ones reopened without loading.
            let pins = targets.pins.filter { !$0.essential }
            reopenClosed(pins)
            repository.unpin(Set(pins.map { $0.id }))
        case .sleep:
            targets.awakeTabs.forEach { store.suspend($0.id) }
        case .bookmark:
            bookmark(targets.links)
        case .share:
            share(targets.links.filter { !$0.url.isEmpty })
        case .copyLink:
            copyLinks(targets.links)
        case .duplicate:
            duplicate(targets)
        case .resetPin:
            for changed in targets.changedPins {
                if let url = changed.pin.url {
                    store.load(url, in: changed.tab.id)
                }
            }
        case .addToEssentials:
            addToEssentials(targets)
        case .removeFromEssentials:
            removeFromEssentials(targets.pins.filter { $0.essential }, workspaceId: workspaceId)
        case .unpackFolder:
            targets.folders.forEach { repository.unpackFolder($0.id) }
        case .delete:
            deleteItems(targets)
        case .splitView:
            split(targets)
        case .unsplit:
            repository.unsplit(targets.splitTabIds)
        case .moveToWorkspace, .moveToFolder, .newFolder, .newSubfolder, .renameFolder, .renameTab, .changeContainer:
            break
        }
    }

    /// Sends the requests of a custom action for `targets`, and tells how it went.
    func runCustomAction(_ action: CustomAction, targets: ActionTargets) {
        let contexts = actionContexts(targets)
        guard !contexts.isEmpty else { return }
        Task {
            let result = await CustomActionRunner.shared.run(action, contexts: contexts)
            await MainActor.run {
                self.show(Toast(message: result.message(action: action, total: contexts.count), long: true))
            }
        }
    }

    /// Moves dragged tabs, pinned items and folders to `target` in `workspaceId`.
    func drop(_ selection: Selection, on target: DropTarget, workspaceId: String) {
        let state = repository.state
        let targets = ActionTargets.of(state, tabs: store.normalTabs, selection: selection)
        let placement: PinPlacement?
        switch target {
        case .intoFolder(let folderId):
            placement = .into(folderId: folderId)
        case .nextToPin(let pinId, let after):
            placement = .next(itemId: pinId, after: after)
        case .pinnedEdge(let atEnd):
            placement = .edge(atEnd: atEnd)
        case .essentials:
            addToEssentials(targets)
            return
        case .nextToTab, .unpinnedStart:
            placement = nil
        }
        if let placement {
            repository.placePins(
                workspaceId: workspaceId, itemIds: targets.itemIds, newPins: targets.tabs.map { $0.pinSource },
                placement: placement)
            return
        }
        if !targets.folders.isEmpty {
            return
        }

        // Unpinning: open pinned tabs become normal tabs, closed ones are reopened without loading.
        let moving = targets.tabs.map { $0.id } + targets.pinnedTabs.map { $0.id } + reopenClosed(targets.pins)
        repository.unpin(Set(targets.pins.map { $0.id }))
        repository.moveToWorkspace(tabIds: Set(moving), itemIds: [], workspaceId: workspaceId)
        let movingIds = Set(moving)
        let others = store.normalTabs.filter {
            !movingIds.contains($0.id) && state.workspaceOf($0.id) == workspaceId && state.pinOf($0.id) == nil
        }
        var anchor: String?
        var after = false
        if case .nextToTab(let tabId, let placeAfter) = target {
            anchor = tabId
            after = placeAfter
        } else {
            anchor = others.first?.id
        }
        if let anchor, !moving.isEmpty {
            store.moveTabs(moving, nextTo: anchor, after: after)
        }
    }

    /// Moves essential `pinId` to position `index` among the essentials shown with it.
    func moveEssential(_ pinId: String, to index: Int) {
        repository.moveEssential(pinId, to: index, perContainer: settings.essentialsPerContainer)
    }

    /// Closes the unpinned tabs of `workspaceId`, with a way to undo.
    func clearUnpinned(_ workspaceId: String) {
        let state = repository.state
        let tabIds = store.normalTabs
            .filter { state.workspaceOf($0.id) == workspaceId && state.pinOf($0.id) == nil }
            .map { $0.id }
        guard !tabIds.isEmpty else { return }
        store.removeTabs(tabIds)
        show(
            Toast(
                message: L10n.tabsClosed(tabIds.count), actionTitle: L10n.undo,
                action: { [weak self] in self?.store.undoClose() }, long: true))
    }

    func createFolder(workspaceId: String, parentId: String?, name: String, targets: ActionTargets) {
        repository.createFolder(
            workspaceId: workspaceId, parentId: parentId, name: name, itemIds: targets.itemIds,
            newPins: targets.tabs.map { $0.pinSource })
    }

    /// Gives pinned tab `pinId` or unpinned tab `tabId` the title `name`; a blank name shows the page title again.
    func renameTab(pinId: String?, tabId: String?, name: String) {
        if let pinId {
            repository.renamePin(pinId, name: name)
        } else if let tabId {
            repository.renameTab(tabId, name: name)
        }
    }

    /// Pins `targets` in `workspaceId`, inside `folderId` or at the end of the top level when it is `nil`.
    func moveToFolder(workspaceId: String, targets: ActionTargets, folderId: String?) {
        repository.placePins(
            workspaceId: workspaceId,
            itemIds: targets.itemIds,
            newPins: targets.tabs.map { $0.pinSource },
            placement: folderId.map { PinPlacement.into(folderId: $0) } ?? .edge(atEnd: true))
    }

    /// Removes pinned tabs, essentials and folders with everything inside them, and closes every tab of `targets`.
    func deleteItems(_ targets: ActionTargets) {
        let tabIds = targets.openTabs.map { $0.id }
        repository.deleteItems(targets.itemIds)
        closeTabs(tabIds)
    }

    func moveToWorkspace(_ targets: ActionTargets, workspaceId: String) {
        repository.moveToWorkspace(
            tabIds: Set(targets.tabs.map { $0.id }), itemIds: targets.itemIds, workspaceId: workspaceId)
    }

    func closePrivateTabs() {
        store.removePrivateTabs()
    }

    func closeTab(_ id: String) {
        store.removeTab(id)
    }

    private func closeTabs(_ ids: [String]) {
        let open = ids.filter { store.tab($0) != nil }
        if !open.isEmpty {
            store.removeTabs(open)
        }
    }

    /// Shows the two open tabs of `targets` together, next to each other when they are both unpinned.
    private func split(_ targets: ActionTargets) {
        let tabs = targets.tabs + targets.pinnedTabs
        guard tabs.count == 2 else { return }
        repository.createSplit(tabs[0].id, tabs[1].id)
        if targets.pinnedTabs.isEmpty {
            store.moveTabs([tabs[1].id], nextTo: tabs[0].id, after: true)
        }
        openTab(tabs[0].id)
    }

    /// Opens the closed ones of `pins` again without loading them, and returns the new tabs.
    @discardableResult
    private func reopenClosed(_ pins: [PinnedItem]) -> [String] {
        pins.filter { pin in pin.tabId.map { store.tab($0) == nil } ?? true }.compactMap { pin in
            guard let url = pin.url else { return nil }
            let tabId = newId()
            repository.expectTab(tabId)
            store.addTab(
                url: url, title: pin.title, contextId: existingContainer(pin.containerId), source: .app, select: false,
                load: false, id: tabId)
            repository.attachPinned(pin.id, tabId: tabId)
            return tabId
        }
    }

    private func addToEssentials(_ targets: ActionTargets) {
        let candidates = targets.tabs.count + targets.pins.filter { !$0.essential }.count
        let before = repository.state.essentials.count
        repository.addToEssentials(
            sources: targets.tabs.map { $0.pinSource },
            pinIds: Set(targets.pins.map { $0.id }),
            perContainer: settings.essentialsPerContainer)
        if repository.state.essentials.count - before < candidates {
            showMessage(L10n.essentialsFull(maxEssentials))
        }
    }

    /// Turns essentials back into normal tabs of `workspaceId`; closed ones are opened again without loading.
    private func removeFromEssentials(_ essentials: [PinnedItem], workspaceId: String) {
        reopenClosed(essentials)
        repository.removeFromEssentials(Set(essentials.map { $0.id }), workspaceId: workspaceId)
    }

    private func bookmark(_ links: [(url: String, title: String)]) {
        let valid = links.filter { !$0.url.isEmpty }
        guard !valid.isEmpty else { return }
        for link in valid {
            BookmarkStore.shared.add(url: link.url, title: link.title)
        }
        showMessage(L10n.bookmarked(valid.count))
    }

    private func copyLinks(_ links: [(url: String, title: String)]) {
        let urls = links.map { $0.url }.filter { !$0.isEmpty }
        guard !urls.isEmpty else { return }
        UIPasteboard.general.string = urls.joined(separator: "\n")
        showMessage(L10n.linksCopied(urls.count))
    }

    private func duplicate(_ targets: ActionTargets) {
        for tab in targets.tabs + targets.pinnedTabs {
            store.duplicate(tab.id)
        }
        let openPinTabs = Set(targets.pinnedTabs.map { $0.id })
        for pin in targets.pins where !(pin.tabId.map { openPinTabs.contains($0) } ?? false) {
            guard let url = pin.url else { continue }
            store.addTab(
                url: url, title: pin.title, contextId: existingContainer(pin.containerId), source: .app,
                select: false, load: false)
        }
    }

    /// The values of the custom action variables for every target.
    private func actionContexts(_ targets: ActionTargets) -> [ActionContext] {
        let state = repository.state
        let names = Dictionary(containers.records.map { ($0.contextId, $0.name) }, uniquingKeysWith: { first, _ in first })
        let date = ActionContext.currentDate()
        func workspaceName(_ id: String) -> String {
            state.workspace(id)?.name ?? ""
        }
        let tabContexts = targets.tabs.map { tab in
            ActionContext(
                url: tab.url,
                title: tab.title,
                container: tab.contextId.flatMap { names[$0] } ?? "",
                workspace: workspaceName(state.workspaceOf(tab.id)),
                folderPath: "",
                date: date)
        }
        let pinnedTabs = targets.pinnedTabs + targets.folderTabs
        let pinContexts = targets.allPins.map { pin -> ActionContext in
            let tab = pinnedTabs.first { $0.id == pin.tabId }
            let title = tab.map { $0.title.isEmpty ? pin.title : $0.title } ?? pin.title
            return ActionContext(
                url: tab?.url ?? pin.url ?? "",
                title: title,
                container: (tab?.contextId ?? pin.containerId).flatMap { names[$0] } ?? "",
                workspace: workspaceName(pin.workspaceId ?? state.activeWorkspaceId),
                folderPath: state.folderPathOf(pin),
                date: date)
        }
        return tabContexts + pinContexts
    }
}

/// How an entry of the "More" menu looks for the shown tab.
struct EntryLook {
    let label: String
    let icon: Icon
    var enabled = true
    var active = false
}

/// What the "More" menu and the actions bar show about a tab, like Android's `MoreMenuState`.
struct MenuState {
    var canGoBack = false
    var canGoForward = false
    var isLoading = false
    var isBookmarked = false
    var isDesktopMode = false
    var isPinned = false
    var isEssential = false
    var isPinChanged = false
    var isSplit = false
    var isPrivate = false
    var isWebPage = false

    /// A state where every entry is enabled, for the preview in the menu's settings.
    static let preview = MenuState(canGoBack: true, canGoForward: true, isWebPage: true)
}

/// The entries of the browser's "More" menu and actions bar, carried out on the shown tab, like Android's
/// `DejavuTabCommands` and `MenuActions`.
extension AppModel {
    func menuState(for tab: TabInfo) -> MenuState {
        let state = repository.state
        let pin = state.pinOf(tab.id)
        return MenuState(
            canGoBack: tab.canGoBack,
            canGoForward: tab.canGoForward,
            isLoading: tab.isLoading,
            isBookmarked: BookmarkStore.shared.contains(tab.url),
            isDesktopMode: tab.desktopMode,
            isPinned: pin != nil && pin?.essential == false,
            isEssential: pin?.essential == true,
            isPinChanged: pin?.url != nil && pin?.url != tab.url,
            isSplit: state.splitOf(tab.id) != nil,
            isPrivate: tab.isPrivate,
            isWebPage: tab.isWebPage)
    }

    func look(of entry: MoreMenuEntry, state: MenuState) -> EntryLook {
        guard case .builtIn(let item) = entry else {
            return EntryLook(label: entry.label, icon: entry.icon, enabled: state.isWebPage)
        }
        let plain = EntryLook(label: item.label, icon: item.icon)
        switch item {
        case .back:
            return EntryLook(label: item.label, icon: item.icon, enabled: state.canGoBack)
        case .forward:
            return EntryLook(label: item.label, icon: item.icon, enabled: state.canGoForward)
        case .refresh:
            return state.isLoading ? EntryLook(label: L10n.menuStop, icon: .symbol("xmark")) : plain
        case .bookmarkPage:
            if state.isBookmarked {
                return EntryLook(label: L10n.iosRemoveBookmark, icon: .symbol("bookmark.fill"), active: true)
            }
            return EntryLook(label: item.label, icon: item.icon, enabled: state.isWebPage)
        case .desktopSite:
            return EntryLook(label: item.label, icon: item.icon, active: state.isDesktopMode)
        case .pinTab:
            if state.isPinned {
                return EntryLook(label: L10n.menuUnpinTab, icon: .symbol("pin.slash"), active: true)
            }
            return EntryLook(label: item.label, icon: item.icon, enabled: !state.isPrivate && state.isWebPage)
        case .essentialTab:
            if state.isEssential {
                return EntryLook(label: L10n.menuRemoveEssential, icon: .symbol("square.grid.2x2.fill"), active: true)
            }
            return EntryLook(label: item.label, icon: item.icon, enabled: !state.isPrivate && state.isWebPage)
        case .splitView:
            if state.isSplit {
                return EntryLook(label: L10n.menuUnsplit, icon: .asset("dejavu-unsplit"), active: true)
            }
            return EntryLook(label: item.label, icon: item.icon, enabled: !state.isPrivate)
        case .resetPinnedUrl, .replacePinnedUrl:
            return EntryLook(label: item.label, icon: item.icon, enabled: state.isPinChanged)
        case .share, .findInPage, .openInApp:
            return EntryLook(label: item.label, icon: item.icon, enabled: state.isWebPage)
        case .bookmarks, .history, .settings:
            return plain
        }
    }

    /// Carries out `entry` on tab `tabId`.
    func run(_ entry: MoreMenuEntry, tabId: String) {
        guard let tab = store.tab(tabId) else { return }
        let state = menuState(for: tab)
        let pin = repository.state.pinOf(tabId)
        guard case .builtIn(let item) = entry else {
            if case .custom(let action) = entry {
                runCustomAction(action, targets: ActionTargets(tabs: [tab]))
            }
            return
        }
        switch item {
        case .back:
            store.goBack(tabId)
        case .forward:
            store.goForward(tabId)
        case .refresh:
            if state.isLoading {
                store.stop(tabId)
            } else {
                store.reload(tabId)
            }
        case .share:
            share([(url: tab.url, title: tab.title)])
        case .findInPage:
            findInPageTabId = tabId
        case .bookmarkPage:
            if state.isBookmarked {
                BookmarkStore.shared.remove(url: tab.url)
                showMessage(L10n.iosBookmarkRemoved)
            } else {
                BookmarkStore.shared.add(url: tab.url, title: tab.title)
                showMessage(L10n.bookmarked(1))
            }
        case .desktopSite:
            store.setDesktopMode(tabId, !tab.desktopMode)
        case .bookmarks:
            showLibrary(.bookmarks)
        case .history:
            showLibrary(.history)
        case .pinTab:
            if let pin, !pin.essential {
                repository.unpin([pin.id])
            } else {
                repository.pinTabs([tab.pinSource])
            }
        case .essentialTab:
            toggleEssential(tab)
        case .openInApp:
            openInApp(tab.url)
        case .resetPinnedUrl:
            if let url = pin?.url {
                store.load(url, in: tabId)
            }
        case .replacePinnedUrl:
            if let pin {
                repository.replacePinUrl(pin.id, url: tab.url, title: tab.title)
            }
        case .splitView:
            if state.isSplit {
                repository.unsplit([tabId])
            } else {
                sheet = .splitPicker(tabId: tabId)
            }
        case .settings:
            showSettings()
        }
    }

    /// The tabs `tabId` can be shown in a split view with: the other tabs of its workspace that are not split yet.
    func splitCandidates(for tabId: String) -> [TabInfo] {
        let state = repository.state
        let workspaceId = state.workspaceOf(tabId)
        return store.normalTabs.filter { other in
            other.id != tabId && state.workspaceOf(other.id) == workspaceId && state.splitOf(other.id) == nil
        }
    }

    /// Shows tab `tabId` and `otherId` side by side, next to each other in the tab list when neither is pinned.
    func split(_ tabId: String, with otherId: String) {
        let state = repository.state
        repository.createSplit(tabId, otherId)
        if state.pinOf(tabId) == nil && state.pinOf(otherId) == nil {
            store.moveTabs([otherId], nextTo: tabId, after: true)
        }
        openTab(tabId)
    }

    private func toggleEssential(_ tab: TabInfo) {
        let state = repository.state
        let pin = state.pinOf(tab.id)
        if let pin, pin.essential {
            repository.removeFromEssentials([pin.id], workspaceId: state.workspaceOf(tab.id))
            return
        }
        let before = state.essentials.count
        repository.addToEssentials(
            sources: pin == nil ? [tab.pinSource] : [],
            pinIds: pin.map { [$0.id] } ?? [],
            perContainer: settings.essentialsPerContainer)
        if repository.state.essentials.count == before {
            showMessage(L10n.essentialsFull(maxEssentials))
        }
    }

    /// Opens `url` in the app it belongs to, when one is installed that handles its universal link.
    private func openInApp(_ url: String) {
        guard let link = URL(string: url) else { return }
        UIApplication.shared.open(link, options: [.universalLinksOnly: true]) { [weak self] opened in
            if !opened {
                self?.showMessage(L10n.iosNoAppForLink)
            }
        }
    }
}
