// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// A ready-made `CustomAction` for a known service. Hosts use example.com and secrets are written in capitals, for the
/// user to replace with their own.
struct CustomActionExample {
    let name: String
    let description: String
    let method: HttpMethod
    let url: String
    let headers: [CustomHeader]
    let body: String
}

private let pageUrl = ActionVariable.websiteUrl.token
private let pageTitle = ActionVariable.websiteTitle.token
private let jsonHeader = CustomHeader(name: "Content-Type", value: "application/json")

private func bearer(_ token: String) -> CustomHeader {
    CustomHeader(name: "Authorization", value: "Bearer \(token)")
}

/// Examples offered when adding a custom action. Services whose tokens expire within hours are left out.
var customActionExamples: [CustomActionExample] {
    [
        CustomActionExample(
            name: "Karakeep",
            description: L10n.exampleBookmark,
            method: .POST,
            url: "https://karakeep.example.com/api/v1/bookmarks",
            headers: [bearer("YOUR_API_KEY"), jsonHeader],
            body: """
                {
                  "type": "link",
                  "url": "\(pageUrl)",
                  "title": "\(pageTitle)"
                }
                """),
        CustomActionExample(
            name: "Linkwarden",
            description: L10n.exampleBookmark,
            method: .POST,
            url: "https://linkwarden.example.com/api/v1/links",
            headers: [bearer("YOUR_ACCESS_TOKEN"), jsonHeader],
            body: """
                {
                  "url": "\(pageUrl)",
                  "name": "\(pageTitle)"
                }
                """),
        CustomActionExample(
            name: "linkding",
            description: L10n.exampleBookmark,
            method: .POST,
            url: "https://linkding.example.com/api/bookmarks/",
            headers: [CustomHeader(name: "Authorization", value: "Token YOUR_API_TOKEN"), jsonHeader],
            body: """
                {
                  "url": "\(pageUrl)",
                  "title": "\(pageTitle)"
                }
                """),
        CustomActionExample(
            name: "Readeck",
            description: L10n.exampleReadLater,
            method: .POST,
            url: "https://readeck.example.com/api/bookmarks",
            headers: [bearer("YOUR_API_TOKEN"), jsonHeader],
            body: """
                {
                  "url": "\(pageUrl)",
                  "title": "\(pageTitle)"
                }
                """),
        CustomActionExample(
            name: "Raindrop.io",
            description: L10n.exampleBookmark,
            method: .POST,
            url: "https://api.raindrop.io/rest/v1/raindrop",
            headers: [bearer("YOUR_TEST_TOKEN"), jsonHeader],
            body: """
                {
                  "link": "\(pageUrl)",
                  "title": "\(pageTitle)",
                  "pleaseParse": {}
                }
                """),
        CustomActionExample(
            name: "Memos",
            description: L10n.exampleNote,
            method: .POST,
            url: "https://memos.example.com/api/v1/memos",
            headers: [bearer("YOUR_ACCESS_TOKEN"), jsonHeader],
            body: """
                {
                  "content": "[\(pageTitle)](\(pageUrl))",
                  "visibility": "PRIVATE"
                }
                """),
        CustomActionExample(
            name: "ntfy",
            description: L10n.exampleNotification,
            method: .POST,
            url: "https://ntfy.sh/",
            headers: [jsonHeader],
            body: """
                {
                  "topic": "YOUR_TOPIC",
                  "title": "\(pageTitle)",
                  "message": "\(pageUrl)",
                  "click": "\(pageUrl)"
                }
                """),
        CustomActionExample(
            name: "Gotify",
            description: L10n.exampleNotification,
            method: .POST,
            url: "https://gotify.example.com/message",
            headers: [CustomHeader(name: "X-Gotify-Key", value: "YOUR_APP_TOKEN"), jsonHeader],
            body: """
                {
                  "title": "\(pageTitle)",
                  "message": "\(pageUrl)",
                  "extras": {
                    "client::notification": { "click": { "url": "\(pageUrl)" } }
                  }
                }
                """),
        CustomActionExample(
            name: "Discord",
            description: L10n.exampleChat,
            method: .POST,
            url: "https://discord.com/api/webhooks/YOUR_WEBHOOK_ID/YOUR_WEBHOOK_TOKEN",
            headers: [jsonHeader],
            body: """
                {
                  "content": "\(pageTitle)\\n\(pageUrl)",
                  "allowed_mentions": { "parse": [] }
                }
                """),
        CustomActionExample(
            name: "Slack",
            description: L10n.exampleChat,
            method: .POST,
            url: "https://hooks.slack.com/services/YOUR/WEBHOOK/PATH",
            headers: [jsonHeader],
            body: """
                {
                  "text": "\(pageTitle)\\n\(pageUrl)"
                }
                """),
        CustomActionExample(
            name: "Telegram",
            description: L10n.exampleChat,
            method: .POST,
            url: "https://api.telegram.org/botYOUR_BOT_TOKEN/sendMessage",
            headers: [jsonHeader],
            body: """
                {
                  "chat_id": "YOUR_CHAT_ID",
                  "text": "\(pageTitle)\\n\(pageUrl)"
                }
                """),
        CustomActionExample(
            name: "Home Assistant",
            description: L10n.exampleAutomation,
            method: .POST,
            url: "https://homeassistant.example.com:8123/api/webhook/YOUR_WEBHOOK_ID",
            headers: [jsonHeader],
            body: """
                {
                  "url": "\(pageUrl)",
                  "title": "\(pageTitle)"
                }
                """),
        CustomActionExample(
            name: "Webhook",
            description: L10n.exampleWebhook,
            method: .POST,
            url: "https://example.com/webhook",
            headers: [jsonHeader],
            body: "{\n" + ActionVariable.allCases.map { "  \"\($0.key)\": \"\($0.token)\"" }.joined(separator: ",\n")
                + "\n}"),
    ]
}
