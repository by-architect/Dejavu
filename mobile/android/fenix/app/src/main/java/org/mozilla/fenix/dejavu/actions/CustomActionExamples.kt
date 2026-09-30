/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.dejavu.actions

import androidx.annotation.StringRes
import org.mozilla.fenix.R

/**
 * A ready-made [CustomAction] for a known service. Hosts use example.com and secrets are written in capitals, for the
 * user to replace with their own.
 */
data class CustomActionExample(
    val name: String,
    @param:StringRes val description: Int,
    val method: HttpMethod,
    val url: String,
    val headers: List<CustomHeader>,
    val body: String,
)

private val pageUrl = ActionVariable.WEBSITE_URL.token
private val pageTitle = ActionVariable.WEBSITE_TITLE.token
private val json = CustomHeader("Content-Type", "application/json")

private fun bearer(token: String) = CustomHeader("Authorization", "Bearer $token")

/** Examples offered when adding a custom action. Services whose tokens expire within hours are left out. */
val customActionExamples: List<CustomActionExample> = listOf(
    CustomActionExample(
        name = "Karakeep",
        description = R.string.dejavu_example_bookmark,
        method = HttpMethod.POST,
        url = "https://karakeep.example.com/api/v1/bookmarks",
        headers = listOf(bearer("YOUR_API_KEY"), json),
        body = """
            {
              "type": "link",
              "url": "$pageUrl",
              "title": "$pageTitle"
            }
        """.trimIndent(),
    ),
    CustomActionExample(
        name = "Linkwarden",
        description = R.string.dejavu_example_bookmark,
        method = HttpMethod.POST,
        url = "https://linkwarden.example.com/api/v1/links",
        headers = listOf(bearer("YOUR_ACCESS_TOKEN"), json),
        body = """
            {
              "url": "$pageUrl",
              "name": "$pageTitle"
            }
        """.trimIndent(),
    ),
    CustomActionExample(
        name = "linkding",
        description = R.string.dejavu_example_bookmark,
        method = HttpMethod.POST,
        url = "https://linkding.example.com/api/bookmarks/",
        headers = listOf(CustomHeader("Authorization", "Token YOUR_API_TOKEN"), json),
        body = """
            {
              "url": "$pageUrl",
              "title": "$pageTitle"
            }
        """.trimIndent(),
    ),
    CustomActionExample(
        name = "Readeck",
        description = R.string.dejavu_example_read_later,
        method = HttpMethod.POST,
        url = "https://readeck.example.com/api/bookmarks",
        headers = listOf(bearer("YOUR_API_TOKEN"), json),
        body = """
            {
              "url": "$pageUrl",
              "title": "$pageTitle"
            }
        """.trimIndent(),
    ),
    CustomActionExample(
        name = "Raindrop.io",
        description = R.string.dejavu_example_bookmark,
        method = HttpMethod.POST,
        url = "https://api.raindrop.io/rest/v1/raindrop",
        headers = listOf(bearer("YOUR_TEST_TOKEN"), json),
        body = """
            {
              "link": "$pageUrl",
              "title": "$pageTitle",
              "pleaseParse": {}
            }
        """.trimIndent(),
    ),
    CustomActionExample(
        name = "Memos",
        description = R.string.dejavu_example_note,
        method = HttpMethod.POST,
        url = "https://memos.example.com/api/v1/memos",
        headers = listOf(bearer("YOUR_ACCESS_TOKEN"), json),
        body = """
            {
              "content": "[$pageTitle]($pageUrl)",
              "visibility": "PRIVATE"
            }
        """.trimIndent(),
    ),
    CustomActionExample(
        name = "ntfy",
        description = R.string.dejavu_example_notification,
        method = HttpMethod.POST,
        url = "https://ntfy.sh/",
        headers = listOf(json),
        body = """
            {
              "topic": "YOUR_TOPIC",
              "title": "$pageTitle",
              "message": "$pageUrl",
              "click": "$pageUrl"
            }
        """.trimIndent(),
    ),
    CustomActionExample(
        name = "Gotify",
        description = R.string.dejavu_example_notification,
        method = HttpMethod.POST,
        url = "https://gotify.example.com/message",
        headers = listOf(CustomHeader("X-Gotify-Key", "YOUR_APP_TOKEN"), json),
        body = """
            {
              "title": "$pageTitle",
              "message": "$pageUrl",
              "extras": {
                "client::notification": { "click": { "url": "$pageUrl" } }
              }
            }
        """.trimIndent(),
    ),
    CustomActionExample(
        name = "Discord",
        description = R.string.dejavu_example_chat,
        method = HttpMethod.POST,
        url = "https://discord.com/api/webhooks/YOUR_WEBHOOK_ID/YOUR_WEBHOOK_TOKEN",
        headers = listOf(json),
        body = """
            {
              "content": "$pageTitle\n$pageUrl",
              "allowed_mentions": { "parse": [] }
            }
        """.trimIndent(),
    ),
    CustomActionExample(
        name = "Slack",
        description = R.string.dejavu_example_chat,
        method = HttpMethod.POST,
        url = "https://hooks.slack.com/services/YOUR/WEBHOOK/PATH",
        headers = listOf(json),
        body = """
            {
              "text": "$pageTitle\n$pageUrl"
            }
        """.trimIndent(),
    ),
    CustomActionExample(
        name = "Telegram",
        description = R.string.dejavu_example_chat,
        method = HttpMethod.POST,
        url = "https://api.telegram.org/botYOUR_BOT_TOKEN/sendMessage",
        headers = listOf(json),
        body = """
            {
              "chat_id": "YOUR_CHAT_ID",
              "text": "$pageTitle\n$pageUrl"
            }
        """.trimIndent(),
    ),
    CustomActionExample(
        name = "Home Assistant",
        description = R.string.dejavu_example_automation,
        method = HttpMethod.POST,
        url = "https://homeassistant.example.com:8123/api/webhook/YOUR_WEBHOOK_ID",
        headers = listOf(json),
        body = """
            {
              "url": "$pageUrl",
              "title": "$pageTitle"
            }
        """.trimIndent(),
    ),
    CustomActionExample(
        name = "Webhook",
        description = R.string.dejavu_example_webhook,
        method = HttpMethod.POST,
        url = "https://example.com/webhook",
        headers = listOf(json),
        body = ActionVariable.entries.joinToString(",\n", prefix = "{\n", postfix = "\n}") {
            "  \"${it.key}\": \"${it.token}\""
        },
    ),
)
