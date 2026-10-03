// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// An HTTP response of a sync server.
struct SyncResponse {
    let status: Int
    let body: String
    private let headers: [String: String]

    init(status: Int, headers: [String: String], body: String) {
        self.status = status
        self.headers = Dictionary(headers.map { ($0.key.lowercased(), $0.value) }, uniquingKeysWith: { _, last in last })
        self.body = body
    }

    var isSuccess: Bool {
        (200...299).contains(status)
    }

    func header(_ name: String) -> String? {
        headers[name.lowercased()]
    }
}

/// Sends HTTP requests to the sync servers. Network failures are thrown as `URLError`.
protocol SyncHttp {
    func send(method: String, url: String, headers: [String: String], body: String?) async throws -> SyncResponse
}

/// `SyncHttp` over an ephemeral `URLSession`: no cookies, no cache, nothing kept on disk.
final class URLSessionSyncHttp: SyncHttp {
    private let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 60
        configuration.timeoutIntervalForResource = 120
        return URLSession(configuration: configuration)
    }()

    func send(method: String, url: String, headers: [String: String], body: String?) async throws -> SyncResponse {
        guard let target = URL(string: url) else { throw URLError(.badURL) }
        var request = URLRequest(url: target)
        request.httpMethod = method
        for (name, value) in headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        request.httpBody = body.map { Data($0.utf8) }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        var responseHeaders: [String: String] = [:]
        for (name, value) in http.allHeaderFields {
            if let name = name as? String {
                responseHeaders[name] = "\(value)"
            }
        }
        return SyncResponse(status: http.statusCode, headers: responseHeaders, body: String(decoding: data, as: UTF8.self))
    }
}

/// What the Mozilla account gives to reach the sync servers: an OAuth token for the sync scope with the scope's key.
///
/// - `kid`: Id of the sync key, sent to the token server.
/// - `accessToken`: OAuth access token for the sync scope.
/// - `syncKey`: The sync key, base64url; decrypts the collection keys.
/// - `tokenServerUrl`: The token server of the account's servers.
struct SyncAuth {
    let kid: String
    let accessToken: String
    let syncKey: String
    let tokenServerUrl: String
}

/// Hawk credentials for the account's storage, from the token server.
///
/// - `endpoint`: Base URL of the account's storage.
/// - `uid`: The storage user, which changes when the account's storage is reset or moved.
/// - `expiresAt`: When to ask for a new token, in local milliseconds.
struct SyncToken {
    let id: String
    let key: String
    let endpoint: String
    let uid: String
    let expiresAt: Int64
}

/// A sync server refused or failed a request. `retryAfterSeconds` is set when the server asked to wait.
struct SyncServerError: Error, CustomStringConvertible {
    let status: Int
    let message: String
    var retryAfterSeconds: Int64? = nil

    var description: String {
        message
    }
}

/// The account's token was refused; syncing needs the account to be signed in again.
struct SyncAuthError: Error, CustomStringConvertible {
    let description: String
}

/// The collection changed on the server while syncing; syncing again downloads the change first.
struct SyncConflictError: Error {}

/// Keeps the difference between the local clock and the servers' clock, which Hawk signatures must follow.
final class SyncClock {
    private let lock = NSLock()
    private var offset: Int64 = 0

    func millis() -> Int64 {
        nowMillis()
    }

    func serverSeconds() -> Int64 {
        let offset = lock.withLock { self.offset }
        return (nowMillis() + offset) / 1000
    }

    /// Updates the offset from a server time header, in decimal seconds.
    func update(_ serverSeconds: String?) {
        guard let seconds = serverSeconds.flatMap({ Double($0) }), seconds.isFinite else { return }
        let offset = Int64(seconds * 1000) - nowMillis()
        lock.withLock { self.offset = offset }
    }
}

/// Exchanges the account's OAuth token for storage credentials.
struct TokenServerClient {
    private static let syncPath = "1.0/sync/1.5"
    private static let defaultDurationSeconds: Int64 = 3600

    /// Renews tokens after 80% of their lifetime, in milliseconds per second of lifetime.
    private static let tokenLifetimeUsedPermille: Int64 = 800

    let http: SyncHttp
    let clock: SyncClock

    func fetch(_ auth: SyncAuth) async throws -> SyncToken {
        let response = try await http.send(
            method: "GET",
            url: Self.endpointOf(auth.tokenServerUrl),
            headers: [
                "Accept": "application/json",
                "Authorization": "Bearer \(auth.accessToken)",
                "X-KeyID": auth.kid,
            ],
            body: nil)
        clock.update(response.header("X-Timestamp"))
        if response.status == 401 || response.status == 403 {
            throw SyncAuthError(description: "The token server refused the account (\(response.status))")
        }
        guard response.isSuccess else {
            throw SyncServerError(
                status: response.status, message: "Token server error \(response.status)",
                retryAfterSeconds: retryAfter(response))
        }
        guard let json = SyncJSON.parseObject(response.body), let id = json.string("id"), let key = json.string("key"),
            let endpoint = json.string("api_endpoint")
        else {
            throw SyncServerError(status: response.status, message: "Malformed token server response")
        }
        let duration = json.int64("duration") ?? Self.defaultDurationSeconds
        let uid: String
        if let number = json["uid"] as? NSNumber {
            uid = number.stringValue
        } else {
            uid = json.string("uid") ?? ""
        }
        return SyncToken(
            id: id,
            key: key,
            endpoint: endpoint.hasSuffix("/") ? String(endpoint.dropLast()) : endpoint,
            uid: uid,
            expiresAt: clock.millis() + duration * Self.tokenLifetimeUsedPermille)
    }

    /// The account's token server URL may or may not end with the Sync 1.5 path, like application-services allows.
    static func endpointOf(_ url: String) -> String {
        var base = url
        while base.hasSuffix("/") {
            base.removeLast()
        }
        return base.hasSuffix(syncPath) ? base : "\(base)/\(syncPath)"
    }
}

/// Talks to the account's Sync 1.5 storage with Hawk signed requests. 401, 412 and 503 answers are thrown as
/// `SyncAuthError`, `SyncConflictError` and `SyncServerError`; other answers are returned.
///
/// - `onBackoff`: Called with the seconds the server asks clients to wait before the next sync.
struct StorageClient {
    let http: SyncHttp
    let token: SyncToken
    let clock: SyncClock
    let onBackoff: (Int64) -> Void

    func request(
        _ method: String, _ path: String, body: String? = nil, ifUnmodifiedSince: String? = nil
    ) async throws -> SyncResponse {
        let url = "\(token.endpoint)/\(path)"
        var headers = [
            "Accept": "application/json",
            "Authorization": Hawk.header(
                id: token.id, key: token.key, method: method, url: url, seconds: clock.serverSeconds()),
        ]
        if body != nil {
            headers["Content-Type"] = "application/json; charset=utf-8"
        }
        if let ifUnmodifiedSince {
            headers["X-If-Unmodified-Since"] = ifUnmodifiedSince
        }
        let response = try await http.send(method: method, url: url, headers: headers, body: body)
        clock.update(response.header("X-Weave-Timestamp"))
        if let backoff = response.header("X-Weave-Backoff").flatMap({ Int64($0) }) {
            onBackoff(backoff)
        }
        switch response.status {
        case 401:
            throw SyncAuthError(description: "The storage server refused the token")
        case 412:
            throw SyncConflictError()
        case 429, 503:
            throw SyncServerError(
                status: response.status, message: "Storage server busy (\(response.status))",
                retryAfterSeconds: retryAfter(response))
        default:
            return response
        }
    }

    /// Like `request`, and throws for any answer other than a success or `allowed`.
    func expect(
        _ method: String, _ path: String, body: String? = nil, ifUnmodifiedSince: String? = nil,
        allowed: Set<Int> = []
    ) async throws -> SyncResponse {
        let response = try await request(method, path, body: body, ifUnmodifiedSince: ifUnmodifiedSince)
        if !response.isSuccess && !allowed.contains(response.status) {
            throw SyncServerError(status: response.status, message: "\(method) \(path) failed with \(response.status)")
        }
        return response
    }
}

/// A server timestamp as a decimal number, or `nil` when `text` is not one.
private func decimalOf(_ text: String?) -> Decimal? {
    guard let text, !text.isEmpty, text.allSatisfy({ $0.isASCII && ($0.isNumber || $0 == "." || $0 == "-") }) else {
        return nil
    }
    return Decimal(string: text, locale: Locale(identifier: "en_US_POSIX"))
}

/// Compares server timestamps, which are decimal seconds like "1727600000.12".
func isNewerTimestamp(_ value: String?, than other: String?) -> Bool {
    guard let first = decimalOf(value) else { return false }
    guard let second = decimalOf(other) else { return true }
    return first > second
}

/// A server timestamp read from JSON, where it is a number, in the server's own two decimals form.
func timestampOf(_ value: Any?) -> String? {
    if let number = value as? NSNumber, jsonBool(number) == nil {
        return String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), number.doubleValue)
    }
    if let text = value as? String, decimalOf(text) != nil {
        return text
    }
    return nil
}

private func retryAfter(_ response: SyncResponse) -> Int64? {
    (response.header("Retry-After") ?? response.header("X-Weave-Backoff")).flatMap { Int64($0) }
}
