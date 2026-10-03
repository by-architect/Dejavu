// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// Texts of syncing that only the iOS app has, in English. The Android app's texts are in L10n+Generated.swift.
extension L10n {
    /// Mozilla account
    static var iosSyncSignInTitle: String { tr("dejavu_ios_sync_sign_in_title", "Mozilla account") }
    /// Signing in…
    static var iosSyncSigningIn: String { tr("dejavu_ios_sync_signing_in", "Signing in…") }
    /// Could not sign in. Check your connection and try again.
    static var iosSyncSignInFailed: String {
        tr("dejavu_ios_sync_sign_in_failed", "Could not sign in. Check your connection and try again.")
    }
    /// Try again
    static var iosSyncTryAgain: String { tr("dejavu_ios_sync_try_again", "Try again") }
    /// Signed in as %1$@
    static func iosSyncSignedInAs(_ p1: String) -> String { tr("dejavu_ios_sync_signed_in_as", "Signed in as %1$@", p1) }
    /// Signed in
    static var iosSyncSignedIn: String { tr("dejavu_ios_sync_signed_in", "Signed in") }
    /// Sign out
    static var iosSyncSignOut: String { tr("dejavu_ios_sync_sign_out", "Sign out") }
    /// Sign out of your Mozilla account? Your workspaces stay on this device and on your other devices.
    static var iosSyncSignOutQuestion: String {
        tr(
            "dejavu_ios_sync_sign_out_question",
            "Sign out of your Mozilla account? Your workspaces stay on this device and on your other devices.")
    }
}
