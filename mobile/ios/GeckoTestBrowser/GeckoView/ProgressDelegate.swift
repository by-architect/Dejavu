// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

/// How the page of a session was loaded, from `ProgressDelegate.onSecurityChange`.
public struct SecurityInformation {
    /// Whether the page was loaded over a secure connection.
    public let isSecure: Bool

    /// Whether the user allowed the page although its certificate is not trusted.
    public let isException: Bool

    /// The host of the page.
    public let host: String?

    /// The origin of the page.
    public let origin: String?
}

public protocol ProgressDelegate {
    /// A View has started loading content from the network.
    func onPageStart(session: GeckoSession, url: String)

    /// A View has finished loading content from the network.
    func onPageStop(session: GeckoSession, success: Bool)

    /// Page loading has progressed.
    func onProgressChange(session: GeckoSession, progress: Int)

    /// The security status has been updated.
    func onSecurityChange(session: GeckoSession, securityInfo: SecurityInformation)

    /// The browser session state has changed. This can happen in response to navigation, scrolling,
    /// or form data changes; the session state passed includes the most up to date information on
    /// all of these.
    func onSessionStateChange(session: GeckoSession, sessionState: GeckoSessionState)
}

// All methods on ProgressDelegate are optional, provide default implementations.
extension ProgressDelegate {
    public func onPageStart(session: GeckoSession, url: String) {}
    public func onPageStop(session: GeckoSession, success: Bool) {}
    public func onProgressChange(session: GeckoSession, progress: Int) {}
    public func onSecurityChange(session: GeckoSession, securityInfo: SecurityInformation) {}
    public func onSessionStateChange(session: GeckoSession, sessionState: GeckoSessionState) {}
}

enum ProgressEvents: String, CaseIterable {
    case pageStart = "GeckoView:PageStart"
    case pageStop = "GeckoView:PageStop"
    case progressChanged = "GeckoView:ProgressChanged"
    case securityChanged = "GeckoView:SecurityChanged"
    case stateUpdated = "GeckoView:StateUpdated"
}

func newProgressHandler(_ session: GeckoSession) -> GeckoSessionHandler<
    ProgressDelegate, ProgressEvents
> {
    GeckoSessionHandler(moduleName: "GeckoViewProgress", session: session) {
        @MainActor session, delegate, event, message in
        switch event {
        case .pageStart:
            delegate?.onPageStart(session: session, url: message!["uri"] as! String)
            return nil
        case .pageStop:
            delegate?.onPageStop(session: session, success: message!["success"] as! Bool)
            return nil
        case .progressChanged:
            delegate?.onProgressChange(session: session, progress: message!["progress"] as! Int)
            return nil
        case .securityChanged:
            let identity = message?["identity"] as? [String: Any]
            let info = SecurityInformation(
                isSecure: identity?["secure"] as? Bool ?? false,
                isException: identity?["securityException"] as? Bool ?? false,
                host: identity?["host"] as? String,
                origin: identity?["origin"] as? String)
            delegate?.onSecurityChange(session: session, securityInfo: info)
            return nil
        case .stateUpdated:
            guard let update = message?["data"] as? [String: Any?] else { return nil }
            session.sessionStateCache.update(with: update)
            delegate?.onSessionStateChange(session: session, sessionState: session.sessionStateCache)
            return nil
        }
    }
}
