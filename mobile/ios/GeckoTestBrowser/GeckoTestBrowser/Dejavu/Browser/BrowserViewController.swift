// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Combine
import GeckoView
import SwiftUI
import UIKit

/// What Dejavu's bars on the browser screen need to know about the screen itself.
final class BrowserScreenState: ObservableObject {
    /// The tab shown in the split pane below the page, if any.
    @Published var splitTabId: String?

    /// Whether the keyboard is shown, which hides the actions bar.
    @Published var keyboardVisible = false
}

/// The browser screen: the page of the selected tab between Dejavu's top bar and actions bar, like Android's
/// one-row toolbar with the actions bar below pages. The other tab of a split view shows in a pane below the page,
/// like Zen's split views; the pane's bar shows its tab on top, closes the split, and resizes the pane when dragged.
/// Swiping in from the left edge goes back, or home on a tab's first page; from the right edge it goes forward.
final class BrowserViewController: UIViewController, BrowserStoreUI {
    private static let topBarHeight: CGFloat = 56
    private static let actionsBarHeight: CGFloat = 56
    private static let findBarHeight: CGFloat = 52
    private static let splitBarHeight: CGFloat = 44
    private static let minMainFraction: CGFloat = 0.25
    private static let maxMainFraction: CGFloat = 0.8

    private let app = AppModel.shared
    private let store = BrowserStore.shared
    private let repository = WorkspaceRepository.shared
    private let screenState = BrowserScreenState()

    private let mainView = GeckoView()
    private let splitPane = UIView()
    private let splitView = GeckoView()
    private let pageBackground = UIView()

    private lazy var topBarHost = UIHostingController(rootView: BrowserTopBar())
    private lazy var actionsBarHost = UIHostingController(rootView: ActionsBar(screenState: screenState))
    private lazy var findBarHost = UIHostingController(rootView: FindInPageBar())
    private lazy var splitBarHost = UIHostingController(
        rootView: SplitPaneBar(
            screenState: screenState,
            onDrag: { [weak self] y in self?.resizeSplit(barTop: y) }))

    private var mainTabId: String?
    private var splitTabId: String?
    private var keyboardHeight: CGFloat = 0
    private var cancellables = Set<AnyCancellable>()

    /// Share of the page area the tab on top gets in a split view, kept while the app runs.
    private var mainFraction: CGFloat = 0.5

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = DejavuColors.surface
        pageBackground.backgroundColor = DejavuColors.surface
        view.addSubview(pageBackground)
        view.addSubview(mainView)
        splitPane.backgroundColor = DejavuColors.surfaceContainerHigh
        splitPane.clipsToBounds = true
        splitPane.addSubview(splitView)
        view.addSubview(splitPane)
        for host in [splitBarHost as UIViewController, topBarHost, actionsBarHost, findBarHost] {
            addChild(host)
            host.view.backgroundColor = .clear
            view.addSubview(host.view)
            host.didMove(toParent: self)
        }
        splitPane.addSubview(splitBarHost.view)

        store.ui = self
        observe()
        observeKeyboard()
        addEdgeSwipes()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        layout()
    }

    // MARK: - Layout

    private func layout() {
        let bounds = view.bounds
        let safe = view.safeAreaInsets
        let fullScreen = app.isFullScreen
        let findShown = app.findInPageTabId != nil && app.findInPageTabId == mainTabId && !fullScreen

        let topHeight = fullScreen ? 0 : safe.top + Self.topBarHeight
        topBarHost.view.isHidden = fullScreen
        topBarHost.view.frame = CGRect(x: 0, y: 0, width: bounds.width, height: safe.top + Self.topBarHeight)

        var bottom = bounds.height
        let actionsShown = !fullScreen && keyboardHeight == 0
        actionsBarHost.view.isHidden = !actionsShown
        let actionsHeight = Self.actionsBarHeight + safe.bottom
        actionsBarHost.view.frame = CGRect(
            x: 0, y: bounds.height - actionsHeight, width: bounds.width, height: actionsHeight)
        if actionsShown {
            bottom -= actionsHeight
        } else if keyboardHeight > 0 {
            bottom -= keyboardHeight
        }

        findBarHost.view.isHidden = !findShown
        if findShown {
            findBarHost.view.frame = CGRect(
                x: 0, y: bottom - Self.findBarHeight, width: bounds.width, height: Self.findBarHeight)
            bottom -= Self.findBarHeight
        }

        let page = CGRect(x: 0, y: topHeight, width: bounds.width, height: max(bottom - topHeight, 0))
        pageBackground.frame = page
        if splitTabId != nil && !fullScreen {
            let mainHeight = (page.height * mainFraction).rounded()
            mainView.frame = CGRect(x: 0, y: page.minY, width: page.width, height: mainHeight)
            splitPane.isHidden = false
            splitPane.frame = CGRect(x: 0, y: page.minY + mainHeight, width: page.width, height: page.height - mainHeight)
            splitBarHost.view.frame = CGRect(x: 0, y: 0, width: page.width, height: Self.splitBarHeight)
            splitView.frame = CGRect(
                x: 0, y: Self.splitBarHeight, width: page.width,
                height: max(splitPane.bounds.height - Self.splitBarHeight, 0))
        } else {
            mainView.frame = page
            splitPane.isHidden = true
        }
    }

    /// Resizes the split pane so that its bar starts at `barTop`, in the coordinates of the screen.
    private func resizeSplit(barTop: CGFloat) {
        let top = view.convert(CGPoint(x: 0, y: barTop), from: nil).y
        let page = pageBackground.frame
        guard page.height > 0 else { return }
        mainFraction = ((top - page.minY) / page.height).clamped(Self.minMainFraction, Self.maxMainFraction)
        layout()
    }

    // MARK: - Shown tabs

    private func observe() {
        let tabs = store.$tabs
            .map { tabs in tabs.map { "\($0.id)|\($0.isAwake)" } }
            .removeDuplicates()
        let splits = repository.$state.map { $0.splits }.removeDuplicates()
        Publishers.CombineLatest4(store.$selectedTabId, tabs, splits, app.$screen)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.updateShownTabs() }
            .store(in: &cancellables)
        Publishers.CombineLatest(app.$isFullScreen, app.$findInPageTabId)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.setNeedsStatusBarAppearanceUpdate()
                self?.view.setNeedsLayout()
            }
            .store(in: &cancellables)
    }

    /// Shows the selected tab, and the other tab of its split view in the pane, while the browser is shown.
    private func updateShownTabs() {
        guard app.screen == .browser, let tabId = store.selectedTabId, let tab = store.tab(tabId) else {
            setSessionsActive(false)
            return
        }
        var partnerId: String?
        if !tab.isPrivate, let split = repository.state.splitOf(tabId) {
            partnerId = split.tabIds.first { $0 != tabId && store.tab($0)?.isPrivate == false }
        }
        let mainSession = store.wake(tabId)
        let partnerSession = partnerId.flatMap { store.wake($0) }

        // A session shows in one view at a time, so the other view lets go of it first.
        if let mainSession, splitView.session === mainSession {
            splitView.session = nil
        }
        if let partnerSession, mainView.session === partnerSession {
            mainView.session = nil
        }
        if mainView.session !== mainSession {
            deactivate(mainView.session)
            mainView.session = mainSession
        }
        if splitView.session !== partnerSession {
            deactivate(splitView.session)
            splitView.session = partnerSession
        }
        mainTabId = mainSession == nil ? nil : tabId
        splitTabId = partnerSession == nil ? nil : partnerId
        screenState.splitTabId = splitTabId
        setSessionsActive(true)
        if app.findInPageTabId != nil && app.findInPageTabId != mainTabId {
            app.findInPageTabId = nil
        }
        view.setNeedsLayout()
    }

    private func setSessionsActive(_ active: Bool) {
        for session in [mainView.session, splitView.session] {
            if let session, session.isOpen() {
                session.setActive(active)
            }
        }
    }

    private func deactivate(_ session: GeckoSession?) {
        if let session, session.isOpen() {
            session.setActive(false)
        }
    }

    // MARK: - BrowserStoreUI

    func sessionWillClose(tabId: String) {
        if tabId == mainTabId {
            mainView.session = nil
            mainTabId = nil
        }
        if tabId == splitTabId {
            splitView.session = nil
            splitTabId = nil
            screenState.splitTabId = nil
        }
        view.setNeedsLayout()
    }

    func showContextMenu(tabId: String, screenX: Int, screenY: Int, element: ContextElement) {
        let source = tabId == splitTabId ? splitView : mainView
        LinkMenu.present(
            from: self, sourceView: source, point: CGPoint(x: screenX, y: screenY), tabId: tabId, element: element)
    }

    func fullScreenChanged(tabId: String, fullScreen: Bool) {
        guard tabId == mainTabId || !fullScreen else { return }
        app.isFullScreen = fullScreen
    }

    func openExternally(_ url: URL, tabId: String) {
        app.confirmOpenExternally(url)
    }

    override var prefersStatusBarHidden: Bool {
        app.isFullScreen
    }

    // MARK: - Keyboard

    private func observeKeyboard() {
        let center = NotificationCenter.default
        center.publisher(for: UIResponder.keyboardWillChangeFrameNotification)
            .merge(with: center.publisher(for: UIResponder.keyboardWillHideNotification))
            .receive(on: DispatchQueue.main)
            .sink { [weak self] notification in self?.keyboardChanged(notification) }
            .store(in: &cancellables)
    }

    private func keyboardChanged(_ notification: Notification) {
        var height: CGFloat = 0
        if notification.name != UIResponder.keyboardWillHideNotification,
            let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect
        {
            let local = view.convert(frame, from: nil)
            height = max(view.bounds.maxY - local.minY, 0)
        }
        guard height != keyboardHeight else { return }
        keyboardHeight = height
        screenState.keyboardVisible = height > 0
        let duration = notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double ?? 0.25
        UIView.animate(withDuration: duration) {
            self.layout()
        }
    }

    // MARK: - Edge swipes

    private func addEdgeSwipes() {
        let back = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(swipedFromLeftEdge(_:)))
        back.edges = .left
        view.addGestureRecognizer(back)
        let forward = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(swipedFromRightEdge(_:)))
        forward.edges = .right
        view.addGestureRecognizer(forward)
    }

    @objc private func swipedFromLeftEdge(_ recognizer: UIScreenEdgePanGestureRecognizer) {
        guard recognizer.state == .ended, recognizer.translation(in: view).x > 60,
            let tabId = store.selectedTabId, let tab = store.tab(tabId)
        else {
            return
        }
        if tab.canGoBack {
            store.goBack(tabId)
        } else {
            // Like Back on a tab's first page in Dejavu for Android: go home and leave the tab as it is.
            app.showHome()
        }
    }

    @objc private func swipedFromRightEdge(_ recognizer: UIScreenEdgePanGestureRecognizer) {
        guard recognizer.state == .ended, recognizer.translation(in: view).x < -60,
            let tabId = store.selectedTabId, store.tab(tabId)?.canGoForward == true
        else {
            return
        }
        store.goForward(tabId)
    }
}

/// The browser screen in SwiftUI.
struct BrowserScreen: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> BrowserViewController {
        BrowserViewController()
    }

    func updateUIViewController(_ controller: BrowserViewController, context: Context) {}
}
