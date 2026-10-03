// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = (scene as? UIWindowScene) else { return }
        let controller = UIHostingController(rootView: RootView())
        AppModel.shared.presenter = controller
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = controller
        window.tintColor = DejavuColors.primary
        self.window = window
        window.makeKeyAndVisible()

        DejavuSync.shared.start()
        if let url = connectionOptions.urlContexts.first?.url {
            // A link from another app, with Dejavu as the default browser.
            AppModel.shared.openExternalLink(url)
        } else if let testUrl = ProcessInfo.processInfo.environment["MOZ_TEST_URL"] {
            AppModel.shared.openNewTab(url: testUrl, source: .app)
        }
    }

    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        for context in URLContexts {
            AppModel.shared.openExternalLink(context.url)
        }
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        DejavuSync.shared.appBecameActive()
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        BrowserStore.shared.save()
        DejavuSync.shared.appWentToBackground()
    }
}
