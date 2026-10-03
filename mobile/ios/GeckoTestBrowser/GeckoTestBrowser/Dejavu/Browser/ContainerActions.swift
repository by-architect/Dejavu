// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import GeckoView

/// What happens to the tabs of a container that is deleted.
enum ContainerRemoval: Equatable {
    /// Close its tabs, pinned tabs included.
    case closeTabs

    /// Reopen its tabs in the given container, or without a container when it is `nil`.
    case moveTabs(String?)
}

/// Changes to containers that also reach their tabs and workspaces.
enum ContainerActions {
    /// Deletes a container: handles its tabs as the user chose, points workspaces and pinned tabs away from it, clears
    /// its cookies and site data, and removes it from storage.
    static func remove(_ record: ContainerRecord, removal: ContainerRemoval) {
        let store = BrowserStore.shared
        let repository = WorkspaceRepository.shared
        let tabs = store.tabs.filter { $0.contextId == record.contextId }
        switch removal {
        case .closeTabs:
            repository.removePins(ofContainer: record.contextId, tabIds: Set(tabs.map { $0.id }))
            repository.replaceContainer(record.contextId, with: nil)
            store.removeTabs(tabs.map { $0.id })
        case .moveTabs(let contextId):
            let selectedTabId = store.selectedTabId
            for tab in tabs {
                let selected = tab.id == selectedTabId
                store.reopen(tab.id, inContainer: contextId, select: selected, load: selected)
            }
            repository.replaceContainer(record.contextId, with: contextId)
        }
        GeckoRuntime.clearDataForSessionContext(record.contextId)
        ContainerStore.shared.remove(record.contextId)
    }

    /// The container `pick` stands for: none, a new temporary container, or the picked one.
    static func contextId(for pick: ContainerPick) -> String? {
        switch pick {
        case .noContainer: return nil
        case .temporary: return ContainerStore.shared.createTemporary()
        case .container(let contextId): return contextId
        }
    }

    /// Moves the tabs and pinned tabs of `targets` to the container `pick`; all of them share one new temporary
    /// container. Open tabs are reopened in it, and pinned tabs reopen in it from now on.
    static func change(_ targets: ActionTargets, to pick: ContainerPick) {
        let contextId = contextId(for: pick)
        WorkspaceRepository.shared.setPinContainer(Set(targets.allPins.map { $0.id }), containerId: contextId)
        let store = BrowserStore.shared
        let selectedTabId = store.selectedTabId
        for tab in targets.openTabs where tab.contextId != contextId {
            let selected = tab.id == selectedTabId
            store.reopen(tab.id, inContainer: contextId, select: selected, load: selected || tab.isAwake)
        }
    }
}
