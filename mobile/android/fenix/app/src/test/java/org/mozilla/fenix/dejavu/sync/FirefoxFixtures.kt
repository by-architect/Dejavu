/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Records shaped exactly like the ones Firefox and Zen write to the clients collection, and application-services'
 * tabs component to the tabs collection, which leaves out fields with their default value.
 */
internal object FirefoxFixtures {
    const val LAPTOP = "laptopClient"
    const val LAPTOP_DEVICE = "fxa-device-laptop"
    const val DESKTOP = "desktopClient"
    const val DESKTOP_DEVICE = "fxa-device-desktop"
    const val ZEN = "zenClient001"
    const val ZEN_DEVICE = "fxa-device-zen"

    /** Phones sync through application-services, whose records use the device's account id as their id. */
    const val PHONE = "fxa-device-phone"
    const val GROUP = "1727000000000-12"
    const val MAIL = "https://mail.example/"
    const val ARTICLE = "https://example.org/article"
    const val LATER = "https://example.net/later"
    const val NEWS = "https://news.example/"

    fun client(
        name: String,
        application: String? = "Firefox",
        type: String = "desktop",
        fxaDeviceId: String? = null,
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("type", type)
        .put("version", "152.0")
        .put("protocols", JSONArray().put("1.5"))
        .putNullable("os", if (type == "desktop") "Linux" else null)
        .putNullable("application", application)
        .putNullable("fxaDeviceId", fxaDeviceId)

    fun tab(
        url: String,
        pinned: Boolean = false,
        group: String? = null,
        window: String = "window-0",
        index: Int = 0,
        title: String = "Title of $url",
    ): JSONObject = JSONObject()
        .put("title", title)
        .put("urlHistory", JSONArray().put(url))
        .put("icon", JSONObject.NULL)
        .put("lastUsed", LAST_USED)
        .apply {
            if (pinned) put("pinned", true)
            if (index != 0) put("index", index)
            group?.let { put("tabGroupId", it) }
            if (window.isNotEmpty()) put("windowId", window)
        }

    fun tabsRecord(
        clientName: String,
        tabs: List<JSONObject>,
        groups: Map<String, String> = emptyMap(),
        collapsed: Set<String> = emptySet(),
        windows: List<String> = listOf("window-0"),
    ): JSONObject = JSONObject()
        .put("clientName", clientName)
        .put("tabs", JSONArray().apply { tabs.forEach { put(it) } })
        .apply {
            if (groups.isNotEmpty()) {
                val records = JSONObject()
                groups.forEach { (id, name) ->
                    records.put(
                        id,
                        JSONObject().put("id", id).put("name", name).put("color", "blue").put("collapsed", id in collapsed),
                    )
                }
                put("tabGroups", records)
            }
            if (windows.isNotEmpty()) {
                val records = JSONObject()
                windows.forEachIndexed { index, id ->
                    records.put(id, JSONObject().put("id", id).put("lastUsed", 0).put("index", index + 1))
                }
                put("windows", records)
            }
        }

    /** A Firefox with a pinned tab, a tab group of two tabs and a tab outside groups, in one window. */
    fun laptopTabs(
        mail: String = MAIL,
        article: String = ARTICLE,
        news: String = NEWS,
        groupName: String = "Reading",
    ): JSONObject = tabsRecord(
        clientName = "Laptop",
        tabs = listOf(
            tab(news, index = 3),
            tab(mail, pinned = true),
            tab(LATER, group = GROUP, index = 2),
            tab(article, group = GROUP, index = 1),
        ),
        groups = mapOf(GROUP to groupName),
    )

    private const val LAST_USED = 1_727_000_000L
}
