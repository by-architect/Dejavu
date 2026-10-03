// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

/// An essential being reordered.
///
/// - `start`: Where the finger was when the drag began.
/// - `moved`: Whether the finger moved enough to be a drag rather than a long press.
/// - `index`: Position the essential would be dropped at.
private struct EssentialDrag {
    let pinId: String
    var start: CGPoint?
    var moved = false
    var index: Int
}

/// The essentials every workspace shares, as a grid of icons like in Zen. Long-pressing one selects it; keeping the
/// finger down and moving reorders it.
struct EssentialsGridView: View {
    let essentials: [PinnedItem]
    @Binding var selection: Selection?

    /// Whether tabs dragged on the workspace page would be dropped here.
    let isDropTarget: Bool
    let onTap: (PinnedItem) -> Void
    let onMove: (String, Int) -> Void

    @ObservedObject private var store = BrowserStore.shared
    @ObservedObject private var containers = ContainerStore.shared
    @State private var drag: EssentialDrag?
    @State private var width: CGFloat = 0

    private static let tileHeight: CGFloat = 52
    private static let gap: CGFloat = 8
    private static let minTileWidth: CGFloat = 64
    private static let minColumns = 3
    private static let space = "essentials"

    var body: some View {
        let columns = columnCount
        let tileWidth = max((width - Self.gap * CGFloat(columns - 1)) / CGFloat(columns), 0)
        VStack(alignment: .leading, spacing: Self.gap) {
            ForEach(Array(rows(columns).enumerated()), id: \.offset) { _, row in
                HStack(spacing: Self.gap) {
                    ForEach(row) { item in
                        tile(item, columns: columns, tileWidth: tileWidth)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .coordinateSpace(.named(Self.space))
        .onGeometryChange(for: CGFloat.self) { geometry in
            geometry.size.width
        } action: { newWidth in
            width = newWidth
        }
        .padding(4)
        .overlay(
            RoundedRectangle(cornerRadius: 18)
                .strokeBorder(isDropTarget ? DejavuColor.primary : Color.clear, lineWidth: 2))
        .padding(.horizontal, 8)
    }

    private var columnCount: Int {
        guard width > 0 else { return Self.minColumns }
        return max(Int((width + Self.gap) / (Self.minTileWidth + Self.gap)), Self.minColumns)
    }

    /// The essentials in rows, with the dragged one where it would be dropped.
    private func rows(_ columns: Int) -> [[PinnedItem]] {
        var ordered = essentials
        if let drag, drag.moved, let dragged = essentials.first(where: { $0.id == drag.pinId }) {
            ordered.removeAll { $0.id == dragged.id }
            ordered.insert(dragged, at: drag.index.clamped(0, ordered.count))
        }
        return stride(from: 0, to: ordered.count, by: columns).map { start in
            Array(ordered[start..<min(start + columns, ordered.count)])
        }
    }

    private func tile(_ item: PinnedItem, columns: Int, tileWidth: CGFloat) -> some View {
        let tab = item.tabId.flatMap { store.tab($0) }
        return EssentialTile(
            item: item,
            tab: tab,
            container: containers.record(tab?.contextId ?? item.containerId),
            isCurrent: tab != nil && tab?.id == store.selectedTabId,
            selected: selection.map { $0.pinIds.contains(item.id) },
            isDragged: drag?.moved == true && drag?.pinId == item.id)
        .frame(width: tileWidth)
        .onTapGesture {
            if drag == nil {
                onTap(item)
            }
        }
        .gesture(reorderGesture(item, columns: columns, tileWidth: tileWidth))
    }

    private func reorderGesture(_ item: PinnedItem, columns: Int, tileWidth: CGFloat) -> some Gesture {
        LongPressGesture(minimumDuration: 0.4)
            .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .named(Self.space)))
            .onChanged { value in
                guard case .second(true, let dragValue) = value else { return }
                if drag == nil, let index = essentials.firstIndex(where: { $0.id == item.id }) {
                    drag = EssentialDrag(pinId: item.id, index: index)
                    selection = (selection ?? Selection()) + Selection(pinIds: [item.id])
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                }
                guard let dragValue, var current = drag else { return }
                if current.start == nil {
                    current.start = dragValue.location
                }
                if !current.moved, let start = current.start,
                    hypot(dragValue.location.x - start.x, dragValue.location.y - start.y) > 10
                {
                    current.moved = true
                }
                if current.moved {
                    let column = Int(dragValue.location.x / (tileWidth + Self.gap)).clamped(0, columns - 1)
                    let row = max(Int(dragValue.location.y / (Self.tileHeight + Self.gap)), 0)
                    current.index = (row * columns + column).clamped(0, max(essentials.count - 1, 0))
                }
                drag = current
            }
            .onEnded { _ in
                if let current = drag, current.moved {
                    onMove(current.pinId, current.index)
                }
                drag = nil
            }
    }
}

/// An essential: the icon of its page on a tile, with a mark in its container's color.
private struct EssentialTile: View {
    let item: PinnedItem
    let tab: TabInfo?
    let container: ContainerRecord?
    let isCurrent: Bool
    let selected: Bool?
    let isDragged: Bool

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 14)
                .fill(background)
            FaviconView(url: tab?.url ?? item.url ?? "", size: 22)
                .opacity(tab?.isAwake == true ? 1 : 0.55)
            if let container {
                VStack {
                    Spacer()
                    RoundedRectangle(cornerRadius: 2)
                        .fill(container.color.color)
                        .frame(width: 16, height: 3)
                        .padding(.bottom, 5)
                }
            }
            if selected == true {
                VStack {
                    HStack {
                        Spacer()
                        Image(systemName: "checkmark")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(DejavuColor.onPrimary)
                            .frame(width: 16, height: 16)
                            .background(Circle().fill(DejavuColor.primary))
                            .padding(4)
                    }
                    Spacer()
                }
            }
        }
        .frame(height: 52)
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .strokeBorder(isDragged ? DejavuColor.primary : Color.clear, lineWidth: 2))
        .contentShape(RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(item.label(tab))
        .accessibilityAddTraits(.isButton)
    }

    private var background: Color {
        if selected == true {
            return DejavuColor.secondaryContainer
        }
        if isCurrent && selected == nil {
            return DejavuColor.surfaceContainerHighest
        }
        return DejavuColor.surfaceContainerHigh
    }
}
