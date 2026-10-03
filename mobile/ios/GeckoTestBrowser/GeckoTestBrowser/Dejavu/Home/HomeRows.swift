// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI

/// Indent of each level of folders.
let homeIndentPerLevel: CGFloat = 18

/// A tab of the home screen: its container's color, its icon, its title and its buttons, or a check mark while
/// selecting.
struct TabRowView: View {
    let title: String
    let url: String
    var depth: Int = 0
    let container: ContainerRecord?
    let isAwake: Bool
    let isCurrent: Bool
    var isSplit = false

    /// Whether the row is selected, or `nil` outside selection mode.
    let selected: Bool?
    let actions: [RowAction]

    /// Resets a pinned tab that left its pinned page, from its icon; `nil` when there is nothing to reset.
    var onIconTap: (() -> Void)? = nil
    let onAction: (RowAction) -> Void

    var body: some View {
        HStack(spacing: 0) {
            RoundedRectangle(cornerRadius: 2)
                .fill(container?.color.color ?? Color.clear)
                .frame(width: 4, height: 26)
                .padding(.leading, 2)
            Spacer()
                .frame(width: 2 + homeIndentPerLevel * CGFloat(depth))
            iconSquare
            Spacer().frame(width: 8)
            Text(title)
                .font(.body)
                .foregroundStyle(isAwake ? DejavuColor.onSurface : DejavuColor.onSurfaceVariant)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            if isSplit {
                Image(icon: .asset("dejavu-split"))
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 16, height: 16)
                    .foregroundStyle(DejavuColor.primary)
                    .padding(.horizontal, 6)
                    .accessibilityLabel(L10n.splitView)
            }
            if let selected {
                SelectionMark(selected: selected)
            } else {
                ForEach(actions, id: \.key) { action in
                    IconButton(
                        icon: action.icon, label: action.label, tint: DejavuColor.onSurfaceVariant, size: 18
                    ) {
                        onAction(action)
                    }
                }
            }
        }
        .frame(height: 48)
        .padding(.vertical, 2)
        .background(RoundedRectangle(cornerRadius: 16).fill(background))
        .contentShape(RoundedRectangle(cornerRadius: 16))
    }

    private var background: Color {
        if selected == true {
            return DejavuColor.secondaryContainer
        }
        if isCurrent && selected == nil {
            return DejavuColor.surfaceContainerHighest
        }
        return .clear
    }

    /// Like Zen, a pinned tab that left its pinned page shows its icon on a brighter square; tapping it goes back.
    /// Tabs loaded in the browser are shown bright, the others dimmed.
    private var iconSquare: some View {
        let square = ZStack {
            RoundedRectangle(cornerRadius: 10)
                .fill(onIconTap != nil ? DejavuColor.onSurface.opacity(0.14) : Color.clear)
            FaviconView(url: url, size: 20)
                .opacity(isAwake ? 1 : 0.55)
        }
        .frame(width: 36, height: 36)
        return Group {
            if let onIconTap, selected == nil {
                Button(action: onIconTap) {
                    square.frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .frame(width: 36, height: 36)
                .accessibilityLabel(L10n.actionResetPin)
            } else {
                square
            }
        }
    }
}

/// A folder of the pinned section. Tapping it folds it, or selects it in selection mode.
struct FolderRowView: View {
    let folder: PinnedItem
    let depth: Int
    let childCount: Int
    let selected: Bool?

    var body: some View {
        HStack(spacing: 0) {
            Spacer().frame(width: 12 + homeIndentPerLevel * CGFloat(depth))
            Image(systemName: folder.collapsed ? "chevron.right" : "chevron.down")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(DejavuColor.onSurfaceVariant)
                .frame(width: 16)
            Spacer().frame(width: 6)
            Image(systemName: "folder")
                .foregroundStyle(DejavuColor.onSurfaceVariant)
                .frame(width: 20)
            Spacer().frame(width: 12)
            Text(folder.title)
                .font(.body)
                .foregroundStyle(DejavuColor.onSurface)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let selected {
                SelectionMark(selected: selected)
            } else if folder.collapsed && childCount > 0 {
                Text("\(childCount)")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(DejavuColor.onSurfaceVariant)
                    .padding(.trailing, 14)
            }
        }
        .frame(height: 48)
        .padding(.vertical, 2)
        .background(
            RoundedRectangle(cornerRadius: 16).fill(selected == true ? DejavuColor.secondaryContainer : Color.clear))
        .contentShape(RoundedRectangle(cornerRadius: 16))
    }
}

/// The round mark of a row in selection mode.
struct SelectionMark: View {
    let selected: Bool

    var body: some View {
        ZStack {
            Circle()
                .fill(selected ? DejavuColor.primary : Color.clear)
            Circle()
                .strokeBorder(selected ? DejavuColor.primary : DejavuColor.outline, lineWidth: 1.5)
            if selected {
                Image(systemName: "checkmark")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(DejavuColor.onPrimary)
            }
        }
        .frame(width: 22, height: 22)
        .padding(.trailing, 14)
    }
}

/// Workspace name with its default container and icon on the left; tapping the name edits the workspace.
struct WorkspaceHeaderView: View {
    let workspace: Workspace
    let container: ContainerRecord?
    let canDelete: Bool
    let canMoveLeft: Bool
    let canMoveRight: Bool
    let onNewFolder: () -> Void
    let onNewWorkspace: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void
    let onMove: (Int) -> Void

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onEdit) {
                HStack(spacing: 6) {
                    if let container {
                        ContainerIconView(record: container, size: 18)
                            .padding(.trailing, 2)
                    }
                    if let icon = workspaceIconText(workspace.icon) {
                        Text(icon).font(.subheadline)
                    }
                    Text(workspace.name)
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(container?.color.color ?? DejavuColor.onSurfaceVariant)
                        .lineLimit(1)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            IconButton(
                icon: .symbol("folder.badge.plus"), label: L10n.actionNewFolder, tint: DejavuColor.onSurfaceVariant,
                size: 18, action: onNewFolder)
            Menu {
                Button(L10n.addWorkspace, action: onNewWorkspace)
                Button(L10n.workspaceEdit, action: onEdit)
                Button(L10n.workspaceMoveLeft) { onMove(-1) }
                    .disabled(!canMoveLeft)
                Button(L10n.workspaceMoveRight) { onMove(1) }
                    .disabled(!canMoveRight)
                Button(L10n.workspaceDelete, role: .destructive, action: onDelete)
                    .disabled(!canDelete)
            } label: {
                Image(systemName: "ellipsis")
                    .foregroundStyle(DejavuColor.onSurfaceVariant)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(L10n.workspaceMenu)
        }
        .padding(.vertical, 4)
    }
}

/// The line between pinned and unpinned tabs, with Zen's "Clear" button for the unpinned ones.
struct SectionDividerView: View {
    let showClear: Bool
    let onClear: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            Rectangle()
                .fill(DejavuColor.outlineVariant)
                .frame(height: 1)
            if showClear {
                Button(action: onClear) {
                    HStack(spacing: 4) {
                        Image(systemName: "chevron.down").font(.system(size: 10, weight: .semibold))
                        Text(L10n.clearUnpinned).font(.subheadline.weight(.medium))
                    }
                    .foregroundStyle(DejavuColor.onSurfaceVariant)
                    .padding(.horizontal, 8)
                    .frame(height: 44)
                }
                .buttonStyle(.plain)
            } else {
                Spacer().frame(width: 12)
            }
        }
        .padding(.leading, 12)
        .frame(height: 44)
    }
}

/// "New Tab" opens the search. Long-pressing it lists the containers to open the new tab in, a private tab, and a
/// shortcut to the container settings.
struct NewTabRowView: View {
    let enabled: Bool

    @ObservedObject private var app = AppModel.shared
    @ObservedObject private var containers = ContainerStore.shared

    var body: some View {
        Button {
            app.startSearch()
        } label: {
            HStack(spacing: 0) {
                Image(systemName: "plus")
                    .foregroundStyle(DejavuColor.onSurfaceVariant)
                    .frame(width: 20)
                    .padding(.leading, 15)
                Spacer().frame(width: 19)
                Text(L10n.newTab)
                    .foregroundStyle(DejavuColor.onSurfaceVariant)
                Spacer(minLength: 0)
            }
            .frame(height: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .contextMenu { menu }
    }

    @ViewBuilder
    private var menu: some View {
        Section(L10n.newTabInContainer) {
            Button {
                app.newTab(in: .noContainer)
            } label: {
                Label(L10n.newTabNoContainer, image: "dejavu-no-container")
            }
            Button {
                app.newTab(in: .temporary)
            } label: {
                Label(L10n.newTabTemporaryContainer, image: "dejavu-temporary-container")
            }
            Button {
                app.newPrivateTab()
            } label: {
                Label(L10n.newPrivateTab, systemImage: "eyeglasses")
            }
        }
        if !containers.permanent.isEmpty {
            Section {
                ForEach(containers.permanent) { record in
                    Button {
                        app.newTab(in: .container(record.contextId))
                    } label: {
                        Label(record.name, image: record.icon.assetName)
                    }
                }
            }
        }
        if !containers.temporaryRecords.isEmpty {
            Section(L10n.openTemporaryContainers) {
                ForEach(containers.temporaryRecords) { record in
                    Button {
                        app.newTab(in: .container(record.contextId))
                    } label: {
                        Label(record.name, image: record.icon.assetName)
                    }
                }
            }
        }
        Section {
            Button {
                app.showSettings(.containers)
            } label: {
                Label(L10n.containerAdd, systemImage: "plus")
            }
        }
    }
}
