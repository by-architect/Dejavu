// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import GeckoView
import SwiftUI
import UIKit

/// Signs in to the Mozilla account on the account's own sign-in page, in a private tab of its own, and finishes the
/// sign-in once the page ends on the address `MozillaAccount` waits for.
struct MozillaSignInView: View {
    private enum Phase {
        case page
        case signingIn
        case failed
    }

    @Environment(\.dismiss) private var dismiss
    @State private var phase = Phase.page
    @State private var attempt = 0
    @State private var progress = 0.0

    var body: some View {
        NavigationStack {
            ZStack {
                SignInPage(
                    onProgress: { progress = $0 },
                    onRedirect: { finish($0) }
                )
                .id(attempt)
                .opacity(phase == .page ? 1 : 0)
                switch phase {
                case .page:
                    EmptyView()
                case .signingIn:
                    ProgressView(L10n.iosSyncSigningIn)
                case .failed:
                    VStack(spacing: 16) {
                        Text(L10n.iosSyncSignInFailed)
                            .multilineTextAlignment(.center)
                            .foregroundStyle(DejavuColor.onSurface)
                        Button(L10n.iosSyncTryAgain) {
                            progress = 0
                            phase = .page
                            attempt += 1
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .padding(32)
                }
            }
            .overlay(alignment: .top) {
                if phase == .page && progress < 1 {
                    ProgressView(value: progress)
                        .progressViewStyle(.linear)
                }
            }
            .background(DejavuColor.surface)
            .navigationTitle(L10n.iosSyncSignInTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.cancel) { dismiss() }
                }
            }
        }
        .tint(DejavuColor.primary)
    }

    private func finish(_ redirect: String) {
        guard phase == .page else { return }
        phase = .signingIn
        Task { @MainActor in
            do {
                try await MozillaAccount.shared.completeSignIn(redirect: redirect)
                DejavuSync.shared.syncNow()
                dismiss()
            } catch {
                Log.error("Mozilla account sign-in failed: \(error)")
                phase = .failed
            }
        }
    }
}

/// The sign-in page in a private Gecko session that is closed with the view.
private struct SignInPage: UIViewRepresentable {
    let onProgress: (Double) -> Void
    let onRedirect: (String) -> Void

    func makeCoordinator() -> SignInSessionDelegate {
        SignInSessionDelegate()
    }

    func makeUIView(context: Context) -> GeckoView {
        let view = GeckoView()
        let delegate = context.coordinator
        delegate.onProgress = onProgress
        delegate.onRedirect = onRedirect
        let session = GeckoSession(settings: GeckoSessionSettings(usePrivateMode: true))
        session.navigationDelegate = delegate
        session.progressDelegate = delegate
        session.open()
        view.session = session
        session.setActive(true)
        delegate.session = session
        if let url = MozillaAccount.shared.beginSignIn() {
            session.load(url.absoluteString)
        }
        return view
    }

    func updateUIView(_ view: GeckoView, context: Context) {
        context.coordinator.onProgress = onProgress
        context.coordinator.onRedirect = onRedirect
    }

    static func dismantleUIView(_ view: GeckoView, coordinator: SignInSessionDelegate) {
        view.session = nil
        coordinator.session?.close()
        coordinator.session = nil
    }
}

/// Watches the sign-in page for the address it ends on, and reports its loading progress.
private final class SignInSessionDelegate: NavigationDelegate, ProgressDelegate {
    var session: GeckoSession?
    var onProgress: (Double) -> Void = { _ in }
    var onRedirect: (String) -> Void = { _ in }
    private var redirected = false

    /// Hands the sign-in's last address over once, without showing its page.
    private func check(_ url: String?) {
        guard !redirected, let url, MozillaAccount.shared.isRedirect(url) else { return }
        redirected = true
        session?.stop()
        onRedirect(url)
    }

    // MARK: NavigationDelegate

    func onLocationChange(session: GeckoSession, url: String?, permissions: [ContentPermission]) {
        check(url)
    }

    func onCanGoBack(session: GeckoSession, canGoBack: Bool) {}

    func onCanGoForward(session: GeckoSession, canGoForward: Bool) {}

    @MainActor
    func onLoadRequest(session: GeckoSession, request: LoadRequest) async -> AllowOrDeny {
        if MozillaAccount.shared.isRedirect(request.uri) {
            check(request.uri)
            return .deny
        }
        return .allow
    }

    @MainActor
    func onSubframeLoadRequest(session: GeckoSession, request: LoadRequest) async -> AllowOrDeny {
        .allow
    }

    /// Links of the sign-in page that open new windows, like its terms, do not open.
    @MainActor
    func onNewSession(session: GeckoSession, uri: String) async -> GeckoSession? {
        nil
    }

    // MARK: ProgressDelegate

    func onPageStart(session: GeckoSession, url: String) {
        check(url)
        onProgress(0.1)
    }

    func onPageStop(session: GeckoSession, success: Bool) {
        onProgress(1)
    }

    func onProgressChange(session: GeckoSession, progress: Int) {
        onProgress(Double(progress) / 100)
    }
}
