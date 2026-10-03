// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// Emoji close to Zen's built-in space icons, by file name. Zen stores these icons as the chrome:// URL of an SVG file,
/// like `chrome://browser/skin/zen-icons/selectable/star.svg`, which Dejavu cannot show.
private let zenIconEmoji: [String: String] = [
    "airplane": "\u{2708}\u{FE0F}",
    "american-football": "\u{1F3C8}",
    "baseball": "\u{26BE}",
    "basket": "\u{1F9FA}",
    "bed": "\u{1F6CF}\u{FE0F}",
    "bell": "\u{1F514}",
    "book": "\u{1F4D6}",
    "bookmark": "\u{1F516}",
    "briefcase": "\u{1F4BC}",
    "brush": "\u{1F58C}\u{FE0F}",
    "bug": "\u{1F41B}",
    "build": "\u{1F528}",
    "cafe": "\u{2615}",
    "call": "\u{1F4DE}",
    "card": "\u{1F4B3}",
    "chat": "\u{1F4AC}",
    "checkbox": "\u{2611}\u{FE0F}",
    "circle": "\u{26AA}",
    "cloud": "\u{2601}\u{FE0F}",
    "code": "\u{1F4BB}",
    "coins": "\u{1FA99}",
    "construct": "\u{1F6E0}\u{FE0F}",
    "cutlery": "\u{1F374}",
    "egg": "\u{1F95A}",
    "extension-puzzle": "\u{1F9E9}",
    "eye": "\u{1F441}\u{FE0F}",
    "fast-food": "\u{1F354}",
    "fish": "\u{1F41F}",
    "flag": "\u{1F6A9}",
    "flame": "\u{1F525}",
    "flask": "\u{1F9EA}",
    "folder": "\u{1F4C1}",
    "game-controller": "\u{1F3AE}",
    "globe": "\u{1F310}",
    "globe-1": "\u{1F30D}",
    "grid-2x2": "\u{1F533}",
    "grid-3x3": "\u{1F533}",
    "heart": "\u{2764}\u{FE0F}",
    "ice-cream": "\u{1F366}",
    "image": "\u{1F5BC}\u{FE0F}",
    "inbox": "\u{1F4E5}",
    "key": "\u{1F511}",
    "layers": "\u{1F5C2}\u{FE0F}",
    "leaf": "\u{1F343}",
    "lightning": "\u{26A1}",
    "location": "\u{1F4CD}",
    "lock-closed": "\u{1F512}",
    "logo-github": "\u{1F419}",
    "logo-rss": "\u{1F4E1}",
    "logo-usd": "\u{1F4B2}",
    "mail": "\u{2709}\u{FE0F}",
    "map": "\u{1F5FA}\u{FE0F}",
    "megaphone": "\u{1F4E3}",
    "moon": "\u{1F319}",
    "music": "\u{1F3B5}",
    "navigate": "\u{1F9ED}",
    "nuclear": "\u{2622}\u{FE0F}",
    "page": "\u{1F4C4}",
    "palette": "\u{1F3A8}",
    "paw": "\u{1F43E}",
    "people": "\u{1F465}",
    "pizza": "\u{1F355}",
    "planet": "\u{1FA90}",
    "present": "\u{1F381}",
    "rocket": "\u{1F680}",
    "school": "\u{1F3EB}",
    "shapes": "\u{1F537}",
    "shirt": "\u{1F455}",
    "skull": "\u{1F480}",
    "square": "\u{2B1C}",
    "squares": "\u{1F533}",
    "star": "\u{2B50}",
    "star-1": "\u{1F31F}",
    "stats-chart": "\u{1F4CA}",
    "sun": "\u{2600}\u{FE0F}",
    "tada": "\u{1F389}",
    "terminal": "\u{1F5A5}\u{FE0F}",
    "ticket": "\u{1F3AB}",
    "time": "\u{23F0}",
    "trash": "\u{1F5D1}\u{FE0F}",
    "triangle": "\u{1F53A}",
    "video": "\u{1F3AC}",
    "volume-high": "\u{1F50A}",
    "wallet": "\u{1F45B}",
    "warning": "\u{26A0}\u{FE0F}",
    "water": "\u{1F4A7}",
    "weight": "\u{1F3CB}\u{FE0F}",
]

/// The text to show for a workspace `icon`: the emoji itself, an emoji close to one of Zen's built-in icons, or `nil`
/// for no icon. The icon is stored unchanged, so Zen keeps showing its own.
func workspaceIconText(_ icon: String?) -> String? {
    guard let icon, !icon.isEmpty else { return nil }
    guard icon.contains("://") else { return icon }
    let file = icon.split(separator: "/").last.map(String.init) ?? icon
    let name = file.split(separator: ".").first.map(String.init) ?? file
    return zenIconEmoji[name]
}

/// Emojis offered as workspace icons; any other one can be typed.
let workspaceIconSuggestions: [String] = [
    "\u{1F3E0}", "\u{1F4BC}", "\u{1F393}", "\u{1F4BB}", "\u{1F3AE}", "\u{1F3B5}", "\u{1F3AC}", "\u{1F4DA}",
    "\u{1F6D2}", "\u{2708}\u{FE0F}", "\u{1F354}", "\u{2615}", "\u{2764}\u{FE0F}", "\u{2B50}", "\u{1F525}",
    "\u{1F331}", "\u{1F319}", "\u{2600}\u{FE0F}", "\u{1F3A8}", "\u{1F4F7}", "\u{1F3CB}\u{FE0F}", "\u{26BD}",
    "\u{1F43E}", "\u{1F4A1}", "\u{1F52C}", "\u{1F4F0}", "\u{1F4AC}", "\u{1F4E7}", "\u{1F5C2}\u{FE0F}",
    "\u{1F9EA}", "\u{1F6E0}\u{FE0F}", "\u{1F4B0}", "\u{1F9D8}", "\u{1F389}", "\u{1F30D}", "\u{1F680}",
    "\u{1F512}", "\u{1F4CC}", "\u{1F9E9}", "\u{1F3A7}",
]

private let themePresetValues: [[UInt32]] = [
    [0xFFFF_7E5F, 0xFFFE_B47B],
    [0xFFF8_57A6, 0xFFFF_5858],
    [0xFFF1_2711, 0xFFF5_AF19],
    [0xFFFF_B88C, 0xFFDE_6262],
    [0xFFA1_8CD1, 0xFFFB_C2EB],
    [0xFF8E_2DE2, 0xFF4A_00E0],
    [0xFF21_93B0, 0xFF6D_D5ED],
    [0xFF89_F7FE, 0xFF66_A6FF],
    [0xFF11_998E, 0xFF38_EF7D],
    [0xFFA8_E6CF, 0xFFDC_EDC1],
    [0xFF00_C9FF, 0xFF92_FE9D, 0xFFFC_466B],
    [0xFF75_7F9A, 0xFFD7_DDE8],
    [0xFF14_1E30, 0xFF24_3B55],
]

/// Gradients offered when editing a workspace, each a list of ARGB colors as Android keeps them.
let workspaceThemePresets: [[Int]] = themePresetValues.map { colors in colors.map { argbInt($0) } }
