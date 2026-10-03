// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// Texts of the settings that only the iOS app has, in English. The Android app's texts are in L10n+Generated.swift.
extension L10n {
    /// Done
    static var iosSettingsDone: String { tr("dejavu_ios_settings_done", "Done") }
    /// General
    static var iosSettingsGeneral: String { tr("dejavu_ios_settings_general", "General") }
    /// Search engine
    static var iosSettingsSearchEngine: String { tr("dejavu_ios_settings_search_engine", "Search engine") }
    /// Searches from the address bar use this search engine.
    static var iosSettingsSearchEngineHint: String {
        tr("dejavu_ios_settings_search_engine_hint", "Searches from the address bar use this search engine.")
    }
    /// About Dejavu
    static var iosSettingsAbout: String { tr("dejavu_ios_settings_about", "About Dejavu") }
    /// Version %1$@
    static func iosSettingsAboutVersion(_ p1: String) -> String {
        tr("dejavu_ios_settings_about_version", "Version %1$@", p1)
    }
    /// Dejavu is an independent project, not made or endorsed by Mozilla or the Zen Browser team.
    static var iosSettingsAboutIndependent: String {
        tr(
            "dejavu_ios_settings_about_independent",
            "Dejavu is an independent project, not made or endorsed by Mozilla or the Zen Browser team.")
    }
    /// Source code on GitHub
    static var iosSettingsAboutSourceCode: String { tr("dejavu_ios_settings_about_source_code", "Source code on GitHub") }
    /// Privacy policy
    static var iosSettingsAboutPrivacy: String { tr("dejavu_ios_settings_about_privacy", "Privacy policy") }
    /// License: Mozilla Public License 2.0
    static var iosSettingsAboutLicense: String {
        tr("dejavu_ios_settings_about_license", "License: Mozilla Public License 2.0")
    }
}
