/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

/**
 * Emoji close to Zen's built-in space icons, by file name. Zen stores these icons as the chrome:// URL of an SVG file,
 * like `chrome://browser/skin/zen-icons/selectable/star.svg`, which Dejavu cannot show.
 */
@Suppress("MagicNumber")
private val zenIconEmoji: Map<String, IntArray> = mapOf(
    "airplane" to intArrayOf(0x2708, 0xFE0F),
    "american-football" to intArrayOf(0x1F3C8),
    "baseball" to intArrayOf(0x26BE),
    "basket" to intArrayOf(0x1F9FA),
    "bed" to intArrayOf(0x1F6CF, 0xFE0F),
    "bell" to intArrayOf(0x1F514),
    "book" to intArrayOf(0x1F4D6),
    "bookmark" to intArrayOf(0x1F516),
    "briefcase" to intArrayOf(0x1F4BC),
    "brush" to intArrayOf(0x1F58C, 0xFE0F),
    "bug" to intArrayOf(0x1F41B),
    "build" to intArrayOf(0x1F528),
    "cafe" to intArrayOf(0x2615),
    "call" to intArrayOf(0x1F4DE),
    "card" to intArrayOf(0x1F4B3),
    "chat" to intArrayOf(0x1F4AC),
    "checkbox" to intArrayOf(0x2611, 0xFE0F),
    "circle" to intArrayOf(0x26AA),
    "cloud" to intArrayOf(0x2601, 0xFE0F),
    "code" to intArrayOf(0x1F4BB),
    "coins" to intArrayOf(0x1FA99),
    "construct" to intArrayOf(0x1F6E0, 0xFE0F),
    "cutlery" to intArrayOf(0x1F374),
    "egg" to intArrayOf(0x1F95A),
    "extension-puzzle" to intArrayOf(0x1F9E9),
    "eye" to intArrayOf(0x1F441, 0xFE0F),
    "fast-food" to intArrayOf(0x1F354),
    "fish" to intArrayOf(0x1F41F),
    "flag" to intArrayOf(0x1F6A9),
    "flame" to intArrayOf(0x1F525),
    "flask" to intArrayOf(0x1F9EA),
    "folder" to intArrayOf(0x1F4C1),
    "game-controller" to intArrayOf(0x1F3AE),
    "globe" to intArrayOf(0x1F310),
    "globe-1" to intArrayOf(0x1F30D),
    "grid-2x2" to intArrayOf(0x1F533),
    "grid-3x3" to intArrayOf(0x1F533),
    "heart" to intArrayOf(0x2764, 0xFE0F),
    "ice-cream" to intArrayOf(0x1F366),
    "image" to intArrayOf(0x1F5BC, 0xFE0F),
    "inbox" to intArrayOf(0x1F4E5),
    "key" to intArrayOf(0x1F511),
    "layers" to intArrayOf(0x1F5C2, 0xFE0F),
    "leaf" to intArrayOf(0x1F343),
    "lightning" to intArrayOf(0x26A1),
    "location" to intArrayOf(0x1F4CD),
    "lock-closed" to intArrayOf(0x1F512),
    "logo-github" to intArrayOf(0x1F419),
    "logo-rss" to intArrayOf(0x1F4E1),
    "logo-usd" to intArrayOf(0x1F4B2),
    "mail" to intArrayOf(0x2709, 0xFE0F),
    "map" to intArrayOf(0x1F5FA, 0xFE0F),
    "megaphone" to intArrayOf(0x1F4E3),
    "moon" to intArrayOf(0x1F319),
    "music" to intArrayOf(0x1F3B5),
    "navigate" to intArrayOf(0x1F9ED),
    "nuclear" to intArrayOf(0x2622, 0xFE0F),
    "page" to intArrayOf(0x1F4C4),
    "palette" to intArrayOf(0x1F3A8),
    "paw" to intArrayOf(0x1F43E),
    "people" to intArrayOf(0x1F465),
    "pizza" to intArrayOf(0x1F355),
    "planet" to intArrayOf(0x1FA90),
    "present" to intArrayOf(0x1F381),
    "rocket" to intArrayOf(0x1F680),
    "school" to intArrayOf(0x1F3EB),
    "shapes" to intArrayOf(0x1F537),
    "shirt" to intArrayOf(0x1F455),
    "skull" to intArrayOf(0x1F480),
    "square" to intArrayOf(0x2B1C),
    "squares" to intArrayOf(0x1F533),
    "star" to intArrayOf(0x2B50),
    "star-1" to intArrayOf(0x1F31F),
    "stats-chart" to intArrayOf(0x1F4CA),
    "sun" to intArrayOf(0x2600, 0xFE0F),
    "tada" to intArrayOf(0x1F389),
    "terminal" to intArrayOf(0x1F5A5, 0xFE0F),
    "ticket" to intArrayOf(0x1F3AB),
    "time" to intArrayOf(0x23F0),
    "trash" to intArrayOf(0x1F5D1, 0xFE0F),
    "triangle" to intArrayOf(0x1F53A),
    "video" to intArrayOf(0x1F3AC),
    "volume-high" to intArrayOf(0x1F50A),
    "wallet" to intArrayOf(0x1F45B),
    "warning" to intArrayOf(0x26A0, 0xFE0F),
    "water" to intArrayOf(0x1F4A7),
    "weight" to intArrayOf(0x1F3CB, 0xFE0F),
)

/**
 * The text to show for a workspace [icon]: the emoji itself, an emoji close to one of Zen's built-in icons, or `null`
 * for no icon. The icon is stored unchanged, so Zen keeps showing its own.
 */
fun workspaceIconText(icon: String?): String? {
    if (icon.isNullOrEmpty()) return null
    if (!icon.contains("://")) return icon
    val name = icon.substringAfterLast('/').substringBeforeLast('.')
    return zenIconEmoji[name]?.let { codePoints -> buildString { codePoints.forEach { appendCodePoint(it) } } }
}
