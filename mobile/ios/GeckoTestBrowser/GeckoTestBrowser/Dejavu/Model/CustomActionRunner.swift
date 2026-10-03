// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// Outcome of running a custom action on some tabs.
///
/// - `sent`: Requests answered with a 2xx status.
/// - `failed`: Requests that got no answer or another status, redirects included.
/// - `failedStatus`: Status of the last failed request that was answered, if any.
struct ActionRunResult {
    let sent: Int
    let failed: Int
    let failedStatus: Int?

    /// Tells how running `action` on `total` tabs went, like "Sent: Karakeep" or "Failed: code 302 (Karakeep)".
    func message(action: CustomAction, total: Int) -> String {
        if failed == 0 {
            return L10n.customActionSent(action.name)
        }
        let reason = failedStatus.map { L10n.customActionFailedCode($0, action.name) }
            ?? L10n.customActionFailedNoAnswer(action.name)
        return total > 1 ? L10n.customActionFailedSome(reason, failed, total) : reason
    }
}

/// Sends `CustomAction` requests. Cookies are never sent, nothing is cached, redirects are not followed, and the
/// response body is ignored.
final class CustomActionRunner: NSObject, URLSessionTaskDelegate {
    static let shared = CustomActionRunner()

    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 45
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    /// Characters a URL keeps as they are, like Android's `Uri.encode`.
    private static let unreserved = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()")

    func run(_ action: CustomAction, contexts: [ActionContext]) async -> ActionRunResult {
        var sent = 0
        var failed = 0
        var failedStatus: Int?
        for context in contexts {
            let status = await send(action, context: context)
            if let status, (200...299).contains(status) {
                sent += 1
            } else {
                failed += 1
                if let status {
                    failedStatus = status
                }
            }
        }
        return ActionRunResult(sent: sent, failed: failed, failedStatus: failedStatus)
    }

    /// Sends the request of `action` for one tab and returns the status of the answer, or `nil` without one.
    private func send(_ action: CustomAction, context: ActionContext) async -> Int? {
        let address = renderTemplate(action.url, context: context) { value in
            value.addingPercentEncoding(withAllowedCharacters: Self.unreserved) ?? value
        }.trimmingCharacters(in: .whitespacesAndNewlines)
        guard address.hasPrefix("https://") || address.hasPrefix("http://"), let url = URL(string: address) else {
            return nil
        }
        var request = URLRequest(url: url)
        request.httpMethod = action.method.rawValue
        request.httpShouldHandleCookies = false
        var isJson = false
        for header in action.headers where !header.name.trimmingCharacters(in: .whitespaces).isEmpty {
            let name = header.name.trimmingCharacters(in: .whitespaces)
            let value = renderTemplate(header.value, context: context) { value in
                value.components(separatedBy: CharacterSet.newlines).filter { !$0.isEmpty }.joined(separator: " ")
            }
            request.setValue(value, forHTTPHeaderField: name)
            if name.caseInsensitiveCompare("Content-Type") == .orderedSame && value.lowercased().contains("json") {
                isJson = true
            }
        }
        if action.method.hasBody && !action.body.isEmpty {
            let json = isJson
            let body = renderTemplate(action.body, context: context) { value in
                json ? Self.jsonEscaped(value) : value
            }
            request.httpBody = body.data(using: .utf8)
        }
        do {
            let (_, response) = try await session.data(for: request)
            return (response as? HTTPURLResponse)?.statusCode
        } catch {
            return nil
        }
    }

    /// `value` as the inside of a JSON string, so a title with quotes keeps a JSON body valid.
    private static func jsonEscaped(_ value: String) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: [value], options: [.withoutEscapingSlashes]),
            let text = String(data: data, encoding: .utf8), text.count >= 4
        else {
            return value
        }
        // The array is written as ["…"]; keep what is between the quotes.
        return String(text.dropFirst(2).dropLast(2))
    }

    // MARK: - URLSessionTaskDelegate

    func urlSession(
        _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void
    ) {
        // A redirect is answered as it is, and counts as a failure.
        completionHandler(nil)
    }
}
