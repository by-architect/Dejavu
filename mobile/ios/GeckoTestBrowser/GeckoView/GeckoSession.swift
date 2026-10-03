// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

/// Settings a `GeckoSession` opens with, like a part of GeckoView Android's `GeckoSessionSettings`.
public struct GeckoSessionSettings {
    /// Container (contextual identity) whose cookies and site data the session uses, or `nil` for none.
    public var contextId: String?

    /// Whether the session browses privately. It cannot change once the session is open.
    public var usePrivateMode: Bool

    /// Whether pages are asked for their desktop site.
    public var desktopMode: Bool

    /// Whether tracking protection is on.
    public var useTrackingProtection: Bool

    public init(
        contextId: String? = nil,
        usePrivateMode: Bool = false,
        desktopMode: Bool = false,
        useTrackingProtection: Bool = true
    ) {
        self.contextId = contextId
        self.usePrivateMode = usePrivateMode
        self.desktopMode = desktopMode
        self.useTrackingProtection = useTrackingProtection
    }
}

/// Result of a find in page, like GeckoView Android's `FinderResult`.
public struct FinderResult {
    /// Whether a match was found.
    public let found: Bool

    /// Whether the search wrapped around the end or the start of the page.
    public let wrapped: Bool

    /// Position of the current match, starting at 1, or 0 when nothing was found.
    public let current: Int

    /// Number of matches, or -1 when not counted.
    public let total: Int
}

public class GeckoSession {
    let dispatcher: EventDispatcher = EventDispatcher()
    var window: GeckoViewWindow?
    public private(set) var id: String?

    /// The settings the session was opened with.
    public private(set) var settings: GeckoSessionSettings

    /// The session's history and page state, kept up to date from Gecko's updates.
    var sessionStateCache = GeckoSessionState()

    /// Whether the session browses privately.
    public var isPrivate: Bool { settings.usePrivateMode }

    /// The container of the session, or `nil` for none.
    public var contextId: String? { settings.contextId }

    lazy var contentHandler = newContentHandler(self)
    lazy var processHangHandler = newProcessHangHandler(self)
    public var contentDelegate: ContentDelegate? {
        get { contentHandler.delegate }
        set {
            contentHandler.delegate = newValue
            processHangHandler.delegate = newValue
        }
    }

    lazy var navigationHandler = newNavigationHandler(self)
    public var navigationDelegate: NavigationDelegate? {
        get { navigationHandler.delegate }
        set { navigationHandler.delegate = newValue }
    }

    lazy var progressHandler = newProgressHandler(self)
    public var progressDelegate: ProgressDelegate? {
        get { progressHandler.delegate }
        set { progressHandler.delegate = newValue }
    }

    lazy var permissionHandler = newPermissionHandler(self)
    public var permissionDelegate: PermissionDelegate? {
        get { permissionHandler.delegate }
        set { permissionHandler.delegate = newValue }
    }

    lazy var sessionHandlers: [GeckoSessionHandlerCommon] = [
        contentHandler,
        processHangHandler,
        navigationHandler,
        progressHandler,
        permissionHandler,
    ]

    public init(settings: GeckoSessionSettings = GeckoSessionSettings()) {
        self.settings = settings

        // Register handlers for all listeners.
        for sessionHandler in sessionHandlers {
            for type in sessionHandler.events {
                dispatcher.addListener(type: type, listener: sessionHandler)
            }
        }
    }

    public func open(windowId: String? = nil) {
        if isOpen() {
            fatalError("cannot open a GeckoSession twice")
        }

        id = windowId ?? UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let modules: [String: Bool] = Dictionary(
            uniqueKeysWithValues: sessionHandlers.map {
                ($0.moduleName, $0.enabled)
            })

        window = GeckoViewOpenWindow(
            id, dispatcher,
            [
                "settings": settingsMessage(),
                "modules": modules,
            ],
            settings.usePrivateMode)
    }

    public func isOpen() -> Bool { window != nil }

    public func close() {
        window?.close()
        window = nil
        id = nil
    }

    public func load(_ url: String) {
        dispatcher.dispatch(
            type: "GeckoView:LoadUri",
            message: [
                "uri": url,
                "flags": 0,
                "headerFilter": /* HEADER_FILTER_CORS_SAFELISTED */ 1,
            ])
    }

    public func reload() {
        dispatcher.dispatch(
            type: "GeckoView:Reload",
            message: [
                "flags": 0
            ])
    }

    public func stop() {
        dispatcher.dispatch(type: "GeckoView:Stop")
    }

    public func goBack(userInteraction: Bool = true) {
        dispatcher.dispatch(
            type: "GeckoView:GoBack",
            message: [
                "userInteraction": userInteraction
            ])
    }

    public func goForward(userInteraction: Bool = true) {
        dispatcher.dispatch(
            type: "GeckoView:GoForward",
            message: [
                "userInteraction": userInteraction
            ])
    }

    public func getUserAgent() async -> String? {
        do {
            let result = try await dispatcher.query(type: "GeckoView:GetUserAgent")
            return result as? String
        } catch {
            return nil
        }
    }

    public func setActive(_ active: Bool) {
        dispatcher.dispatch(type: "GeckoView:SetActive", message: ["active": active])
    }

    /// Asks pages for their desktop or mobile site. The page has to be reloaded to follow.
    public func setDesktopMode(_ enabled: Bool) {
        settings.desktopMode = enabled
        guard isOpen() else { return }
        dispatcher.dispatch(type: "GeckoView:UpdateSettings", message: settingsMessage())
    }

    /// Brings back the history and page state of `state`, as saved from `ProgressDelegate.onSessionStateChange`.
    public func restoreState(_ state: GeckoSessionState) {
        sessionStateCache = state
        dispatcher.dispatch(type: "GeckoView:RestoreState", message: state.message)
    }

    /// Finds `searchString` in the page, or the next match of the last search when it is `nil`.
    public func find(
        _ searchString: String?, backwards: Bool = false, matchCase: Bool = false, wholeWord: Bool = false
    ) async -> FinderResult? {
        let message: [String: Any?] = [
            "searchString": searchString,
            "backwards": backwards,
            "linksOnly": false,
            "matchCase": matchCase,
            "wholeWord": wholeWord,
        ]
        guard let result = try? await dispatcher.query(type: "GeckoView:FindInPage", message: message),
            let fields = result as? [String: Any]
        else {
            return nil
        }
        return FinderResult(
            found: fields["found"] as? Bool ?? false,
            wrapped: fields["wrapped"] as? Bool ?? false,
            current: fields["current"] as? Int ?? 0,
            total: fields["total"] as? Int ?? -1)
    }

    /// Highlights every match of the last find in page.
    public func displayFindMatches() {
        dispatcher.dispatch(
            type: "GeckoView:DisplayMatches",
            message: ["highlightAll": true, "dimPage": false, "drawOutline": true])
    }

    /// Ends a find in page and removes its highlights.
    public func clearFindMatches() {
        dispatcher.dispatch(type: "GeckoView:ClearMatches")
    }

    private func settingsMessage() -> [String: Any?] {
        let mode = settings.desktopMode ? /* USER_AGENT_MODE_DESKTOP */ 1 : /* USER_AGENT_MODE_MOBILE */ 0
        return [
            "chromeUri": nil,
            "screenId": 0,
            "useTrackingProtection": settings.useTrackingProtection,
            "usePrivateMode": settings.usePrivateMode,
            "userAgentMode": mode,
            "userAgentOverride": nil,
            "viewportMode": mode,  // VIEWPORT_MODE_MOBILE is 0, VIEWPORT_MODE_DESKTOP is 1
            "displayMode": /* DISPLAY_MODE_BROWSER */ 0,
            "suspendMediaWhenInactive": false,
            "allowJavascript": true,
            "fullAccessibilityTree": false,
            "isPopup": false,
            "sessionContextId": GeckoSession.safeContextId(settings.contextId),
            "unsafeSessionContextId": settings.contextId,
        ]
    }

    /// The form of a container id Gecko accepts, like GeckoView Android's `createSafeSessionContextId`: "gvctx"
    /// followed by the bytes of the id in UTF-8 read as one signed big-endian number, in lowercase hex.
    public static func safeContextId(_ contextId: String?) -> String? {
        guard let contextId else { return nil }
        if contextId.isEmpty {
            return "gvctxempty"
        }
        var bytes = Array(contextId.utf8)
        var sign = ""
        if bytes[0] >= 0x80 {
            // A negative number: write its magnitude, the two's complement of the bytes.
            sign = "-"
            bytes = bytes.map { ~$0 }
            var index = bytes.count - 1
            while index >= 0 {
                let (sum, overflow) = bytes[index].addingReportingOverflow(1)
                bytes[index] = sum
                if !overflow {
                    break
                }
                index -= 1
            }
        }
        var hex = bytes.map { String(format: "%02x", $0) }.joined()
        while hex.count > 1 && hex.hasPrefix("0") {
            hex.removeFirst()
        }
        return "gvctx" + sign + hex
    }
}
