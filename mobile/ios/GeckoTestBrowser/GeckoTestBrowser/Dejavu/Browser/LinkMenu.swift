// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import GeckoView
import UIKit

/// The menu of a long-pressed link, image or media element of a page, with Dejavu's entries next to opening the link in
/// a new tab: open it in a split view, in a container or in a workspace, like Android's `DejavuLinkMenu`.
enum LinkMenu {
    static func present(
        from controller: UIViewController, sourceView: UIView, point: CGPoint, tabId: String, element: ContextElement
    ) {
        let app = AppModel.shared
        let store = BrowserStore.shared
        guard let parent = store.tab(tabId) else { return }
        let link = element.linkUri
        let media = element.type == .none ? nil : element.srcUri
        guard let target = link ?? media else { return }

        let sheet = UIAlertController(title: nil, message: target, preferredStyle: .actionSheet)
        if let link {
            sheet.addAction(
                UIAlertAction(title: L10n.iosOpenInNewTab, style: .default) { _ in
                    openInBackground(link, parent: parent, contextId: parent.contextId, isPrivate: parent.isPrivate)
                })
            if !parent.isPrivate {
                sheet.addAction(
                    UIAlertAction(title: L10n.iosOpenInPrivateTab, style: .default) { _ in
                        openInBackground(link, parent: parent, contextId: nil, isPrivate: true)
                    })
                sheet.addAction(
                    UIAlertAction(title: L10n.linkOpenInSplitView, style: .default) { _ in
                        openInSplitView(link, parent: parent)
                    })
                sheet.addAction(
                    UIAlertAction(title: L10n.linkOpenInContainer, style: .default) { _ in
                        app.sheet = .containerPicker(
                            title: L10n.linkOpenInContainer, current: nil,
                            onPick: { pick in openInContainer(link, parent: parent, pick: pick) })
                    })
                if !otherWorkspaces(of: parent).isEmpty {
                    sheet.addAction(
                        UIAlertAction(title: L10n.linkOpenInWorkspace, style: .default) { _ in
                            let own = WorkspaceRepository.shared.state.workspaceOf(parent.id)
                            app.sheet = .workspacePicker(
                                title: L10n.linkOpenInWorkspace, excluding: own,
                                onPick: { id in openInWorkspace(link, workspaceId: id) })
                        })
                }
            }
            sheet.addAction(
                UIAlertAction(title: L10n.actionCopyLink, style: .default) { _ in
                    UIPasteboard.general.string = link
                })
            sheet.addAction(
                UIAlertAction(title: L10n.actionShare, style: .default) { _ in
                    app.share([(url: link, title: element.title ?? "")])
                })
        }
        if let media, media != link {
            sheet.addAction(
                UIAlertAction(title: L10n.iosOpenImageInNewTab, style: .default) { _ in
                    openInBackground(media, parent: parent, contextId: parent.contextId, isPrivate: parent.isPrivate)
                })
            sheet.addAction(
                UIAlertAction(title: L10n.iosCopyImageLink, style: .default) { _ in
                    UIPasteboard.general.string = media
                })
        }
        sheet.addAction(UIAlertAction(title: L10n.cancel, style: .cancel))
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = sourceView
            let x = min(max(point.x, 0), sourceView.bounds.width)
            let y = min(max(point.y, 0), sourceView.bounds.height)
            popover.sourceRect = CGRect(x: x, y: y, width: 1, height: 1)
        }
        (AppModel.shared.topViewController ?? controller).present(sheet, animated: true)
    }

    /// The workspaces a link in `tab` can be opened in: every one but the tab's own.
    private static func otherWorkspaces(of tab: TabInfo) -> [Workspace] {
        let state = WorkspaceRepository.shared.state
        let own = state.workspaceOf(tab.id)
        return state.workspaces.filter { $0.id != own }
    }

    /// Opens `url` in a new tab next to `parent`, in its workspace, without showing it, and offers to switch to it.
    private static func openInBackground(_ url: String, parent: TabInfo, contextId: String?, isPrivate: Bool) {
        let repository = WorkspaceRepository.shared
        let tabId = newId()
        if !isPrivate {
            repository.expectTab(tabId)
            repository.assignTab(tabId, to: repository.state.workspaceOf(parent.id))
        }
        BrowserStore.shared.addTab(
            url: url, contextId: contextId, isPrivate: isPrivate, parentId: parent.id, source: .page, select: false,
            load: true, id: tabId, after: parent.id)
        showOpened(L10n.iosTabOpened, tabId: tabId)
    }

    /// Opens `url` in a new tab shown next to `parent`, in its container and workspace.
    private static func openInSplitView(_ url: String, parent: TabInfo) {
        let repository = WorkspaceRepository.shared
        let tabId = newId()
        repository.expectTab(tabId)
        repository.assignTab(tabId, to: repository.state.workspaceOf(parent.id))
        BrowserStore.shared.addTab(
            url: url, contextId: parent.contextId, parentId: parent.id, source: .page, select: false, load: true,
            id: tabId, after: parent.id)
        repository.createSplit(parent.id, tabId)
    }

    private static func openInContainer(_ url: String, parent: TabInfo, pick: ContainerPick) {
        let repository = WorkspaceRepository.shared
        let tabId = newId()
        repository.expectTab(tabId)
        repository.assignTab(tabId, to: repository.state.workspaceOf(parent.id))
        BrowserStore.shared.addTab(
            url: url, contextId: ContainerActions.contextId(for: pick), source: .page, select: false, load: true,
            id: tabId)
        showOpened(L10n.iosTabOpened, tabId: tabId)
    }

    /// Opens `url` in workspace `workspaceId`, in the container a new tab of that workspace opens in.
    private static func openInWorkspace(_ url: String, workspaceId: String) {
        let repository = WorkspaceRepository.shared
        guard let workspace = repository.state.workspace(workspaceId) else { return }
        let pick: ContainerPick
        if let containerId = AppModel.shared.existingContainer(workspace.containerId) {
            pick = .container(containerId)
        } else if DejavuSettings.shared.temporaryContainersByDefault {
            pick = .temporary
        } else {
            pick = .noContainer
        }
        let tabId = newId()
        repository.expectTab(tabId)
        repository.assignTab(tabId, to: workspace.id)
        BrowserStore.shared.addTab(
            url: url, contextId: ContainerActions.contextId(for: pick), source: .page, select: false, load: true,
            id: tabId)
        showOpened(L10n.linkOpenedIn(workspace.name), tabId: tabId) {
            repository.selectWorkspace(workspace.id)
        }
    }

    private static func showOpened(_ message: String, tabId: String, beforeSwitch: (() -> Void)? = nil) {
        AppModel.shared.show(
            Toast(
                message: message, actionTitle: L10n.iosSwitch,
                action: {
                    beforeSwitch?()
                    AppModel.shared.openTab(tabId)
                }))
    }
}
