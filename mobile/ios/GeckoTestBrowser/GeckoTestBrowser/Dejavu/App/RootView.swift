// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI

/// Dejavu's screens: the browser, the home screen with the workspaces over it, the search screen, messages and sheets.
/// The app starts on the home screen, like Dejavu for Android.
struct RootView: View {
    @ObservedObject private var app = AppModel.shared

    var body: some View {
        ZStack {
            BrowserScreen()
                .ignoresSafeArea()
            if app.screen == .home {
                HomeView()
                    .transition(.opacity)
            }
            if let request = app.search {
                SearchView(request: request)
                    .id(request.id)
                    .transition(.move(edge: .bottom))
            }
            ToastView()
        }
        .animation(.easeInOut(duration: 0.2), value: app.screen)
        .animation(.easeOut(duration: 0.25), value: app.search?.id)
        .statusBarHidden(app.isFullScreen)
        .sheet(item: $app.sheet) { sheet in
            AppSheetContent(sheet: sheet)
        }
        .tint(DejavuColor.primary)
    }
}

/// The content of the sheet the app shows.
private struct AppSheetContent: View {
    let sheet: AppSheet

    var body: some View {
        switch sheet {
        case .settings(let screen):
            SettingsView(initial: screen, onOpenUrl: { url in
                AppModel.shared.sheet = nil
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                    AppModel.shared.openNewTab(url: url, source: .app)
                }
            })
        case .library(let kind):
            LibraryView(kind: kind)
        case .moreMenu(let tabId):
            MoreMenuSheet(tabId: tabId)
        case .splitPicker(let tabId):
            SplitPickerView(tabId: tabId)
        case .containerPicker(let title, let current, let onPick):
            ContainerPickerView(title: title, current: current, onPick: onPick)
        case .workspacePicker(let title, let excluding, let onPick):
            WorkspacePickerView(title: title, excluding: excluding, onPick: onPick)
        }
    }
}

/// The message at the bottom of the screen, like Android's snackbars.
private struct ToastView: View {
    @ObservedObject private var app = AppModel.shared

    var body: some View {
        VStack {
            Spacer()
            if let toast = app.toast {
                HStack(spacing: 12) {
                    Text(toast.message)
                        .font(.subheadline)
                        .foregroundStyle(Color(uiColor: .systemBackground))
                        .lineLimit(3)
                    Spacer(minLength: 0)
                    if let title = toast.actionTitle, let action = toast.action {
                        Button(title) {
                            app.dismissToast()
                            action()
                        }
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(DejavuColor.primaryContainer)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 14).fill(Color(uiColor: .label).opacity(0.9)))
                .padding(.horizontal, 16)
                .padding(.bottom, 72)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .id(toast.id)
            }
        }
        .animation(.easeOut(duration: 0.2), value: app.toast?.id)
        .allowsHitTesting(app.toast != nil)
    }
}
