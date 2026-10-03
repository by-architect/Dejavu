// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Combine
import CryptoKit
import Foundation
import Security

/// A problem signing in to the Mozilla account.
enum MozillaAccountError: Error, CustomStringConvertible {
    /// The sign-in page answered with something else than this sign-in asked for.
    case unexpectedRedirect

    /// The account servers answered in a way Dejavu does not understand.
    case malformed(String)

    /// The account servers refused; signing in again fixes it.
    case refused(Int)

    var description: String {
        switch self {
        case .unexpectedRedirect: return "Unexpected sign-in redirect"
        case .malformed(let what): return "Malformed \(what)"
        case .refused(let status): return "Refused with \(status)"
        }
    }
}

/// The Mozilla account spaces sync uses, signed in with OAuth like Firefox for Android does: the sign-in page runs in a
/// private Gecko tab, and the account servers then give a refresh token and, encrypted for this device only, the key of
/// the account's sync data (scoped keys, `keys_jwk`). Both are kept in the Keychain.
///
/// It uses the OAuth client of Firefox for Android, whose sign-in ends on `redirectUri`. The browser tab sees that
/// address and hands it to `completeSignIn`.
final class MozillaAccount: ObservableObject {
    static let shared = MozillaAccount()

    static let clientId = "a2270f727f45f648"
    static let redirectUri = "https://accounts.firefox.com/oauth/success/a2270f727f45f648"
    static let syncScope = "https://identity.mozilla.com/apps/oldsync"
    private static let profileScope = "profile"
    private static let authorizationEndpoint = "https://accounts.firefox.com/authorization"
    private static let tokenEndpoint = "https://oauth.accounts.firefox.com/v1/token"
    private static let destroyEndpoint = "https://oauth.accounts.firefox.com/v1/destroy"
    private static let profileEndpoint = "https://profile.accounts.firefox.com/v1/profile"
    private static let tokenServerUrl = "https://token.services.mozilla.com/1.0/sync/1.5"

    /// Renews access tokens a minute before they expire.
    private static let expiryMarginMillis: Int64 = 60_000

    /// What is kept in the Keychain.
    private struct StoredAccount: Codable {
        var refreshToken: String
        var kid: String
        var syncKey: String
        var email: String?
    }

    /// A sign-in that was started and waits for its redirect.
    private struct PendingSignIn {
        let state: String
        let codeVerifier: String
        let privateKey: P256.KeyAgreement.PrivateKey
    }

    @Published private(set) var isSignedIn: Bool
    @Published private(set) var email: String?

    private var stored: StoredAccount?
    private var accessToken: (token: String, expiresAt: Int64)?
    private var pending: PendingSignIn?
    private let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 60
        return URLSession(configuration: configuration)
    }()

    private init() {
        let stored = Keychain.read().flatMap { try? JSONDecoder().decode(StoredAccount.self, from: $0) }
        self.stored = stored
        isSignedIn = stored != nil
        email = stored?.email
    }

    // MARK: Signing in

    /// Starts a sign-in and returns the address of the sign-in page.
    func beginSignIn() -> URL? {
        let privateKey = P256.KeyAgreement.PrivateKey()
        let point = privateKey.publicKey.x963Representation
        let jwk: [String: Any] = [
            "crv": "P-256",
            "kty": "EC",
            "x": Data(point.dropFirst().prefix(32)).base64URLEncoded,
            "y": Data(point.suffix(32)).base64URLEncoded,
        ]
        let codeVerifier = secureRandomBytes(32).base64URLEncoded
        let challenge = Data(SHA256.hash(data: Data(codeVerifier.utf8))).base64URLEncoded
        let state = secureRandomBytes(16).base64URLEncoded
        pending = PendingSignIn(state: state, codeVerifier: codeVerifier, privateKey: privateKey)

        var components = URLComponents(string: Self.authorizationEndpoint)
        components?.queryItems = [
            URLQueryItem(name: "action", value: "email"),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "access_type", value: "offline"),
            URLQueryItem(name: "client_id", value: Self.clientId),
            URLQueryItem(name: "scope", value: "\(Self.profileScope) \(Self.syncScope)"),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "keys_jwk", value: Data(SyncJSON.canonical(jwk).utf8).base64URLEncoded),
            URLQueryItem(name: "redirect_uri", value: Self.redirectUri),
        ]
        return components?.url
    }

    /// Whether `url` is where the sign-in page sends the browser once the user signed in.
    func isRedirect(_ url: String) -> Bool {
        url.hasPrefix(Self.redirectUri)
    }

    /// Finishes the sign-in started by `beginSignIn` with the address the sign-in page ended on: trades its code for
    /// tokens and decrypts the sync key.
    @MainActor
    func completeSignIn(redirect: String) async throws {
        guard let pending, let items = URLComponents(string: redirect)?.queryItems,
            let code = items.first(where: { $0.name == "code" })?.value,
            items.first(where: { $0.name == "state" })?.value == pending.state
        else {
            throw MozillaAccountError.unexpectedRedirect
        }
        self.pending = nil
        let response = try await post(
            Self.tokenEndpoint,
            [
                "client_id": Self.clientId,
                "grant_type": "authorization_code",
                "code": code,
                "code_verifier": pending.codeVerifier,
            ])
        guard let refreshToken = response.string("refresh_token"), let jwe = response.string("keys_jwe"),
            let token = response.string("access_token")
        else {
            throw MozillaAccountError.malformed("token response")
        }
        let keys = try Self.decryptKeys(jwe, with: pending.privateKey)
        guard let syncKey = keys.object(Self.syncScope), let kid = syncKey.string("kid"), let k = syncKey.string("k") else {
            throw MozillaAccountError.malformed("scoped keys")
        }
        let expiresIn = response.int64("expires_in") ?? 3600
        accessToken = (token, nowMillis() + expiresIn * 1000)
        let email = await profileEmail(token)
        let account = StoredAccount(refreshToken: refreshToken, kid: kid, syncKey: k, email: email)
        if let data = try? JSONEncoder().encode(account) {
            Keychain.write(data)
        }
        stored = account
        self.email = email
        isSignedIn = true
    }

    /// Forgets the account on this device and asks the servers to end its session.
    @MainActor
    func signOut() {
        let refreshToken = stored?.refreshToken
        stored = nil
        accessToken = nil
        pending = nil
        Keychain.delete()
        email = nil
        isSignedIn = false
        if let refreshToken {
            Task {
                _ = try? await post(Self.destroyEndpoint, ["refresh_token": refreshToken])
            }
        }
    }

    // MARK: Sync

    /// What the sync servers need, with an access token renewed when it is about to expire. Throws `SyncAuthError`
    /// when the account has to be signed in again.
    @MainActor
    func syncAuth() async throws -> SyncAuth {
        guard let account = stored else { throw SyncAuthError(description: "Not signed in") }
        let token: String
        if let current = accessToken, current.expiresAt - Self.expiryMarginMillis > nowMillis() {
            token = current.token
        } else {
            let response: [String: Any]
            do {
                response = try await post(
                    Self.tokenEndpoint,
                    [
                        "client_id": Self.clientId,
                        "grant_type": "refresh_token",
                        "refresh_token": account.refreshToken,
                        "scope": Self.syncScope,
                    ])
            } catch MozillaAccountError.refused(let status) {
                throw SyncAuthError(description: "The account servers refused the refresh token (\(status))")
            }
            guard let fresh = response.string("access_token") else {
                throw MozillaAccountError.malformed("token response")
            }
            accessToken = (fresh, nowMillis() + (response.int64("expires_in") ?? 3600) * 1000)
            token = fresh
        }
        return SyncAuth(kid: account.kid, accessToken: token, syncKey: account.syncKey, tokenServerUrl: Self.tokenServerUrl)
    }

    /// Forgets the access token, so the next sync asks for a new one, like when a server refused it.
    @MainActor
    func invalidateAccessToken() {
        accessToken = nil
    }

    // MARK: Requests

    /// Posts `body` as JSON and returns the JSON answer. Answers 400 and 401 are thrown as `refused`.
    private func post(_ url: String, _ body: [String: Any]) async throws -> [String: Any] {
        guard let target = URL(string: url) else { throw URLError(.badURL) }
        var request = URLRequest(url: target)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = Data(SyncJSON.canonical(body).utf8)
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 400 || status == 401 {
            throw MozillaAccountError.refused(status)
        }
        guard (200...299).contains(status) else {
            throw SyncServerError(status: status, message: "Account server error \(status)")
        }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    /// The email address of the account, shown in the sync settings; `nil` when the profile server does not answer.
    private func profileEmail(_ accessToken: String) async -> String? {
        guard let url = URL(string: Self.profileEndpoint) else { return nil }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let answer = try? await session.data(for: request),
            (answer.1 as? HTTPURLResponse)?.statusCode == 200,
            let profile = (try? JSONSerialization.jsonObject(with: answer.0)) as? [String: Any]
        else {
            return nil
        }
        return profile.string("email")
    }

    /// Decrypts the scoped keys the token server sent for this device: a compact JWE with ECDH-ES key agreement on P-256
    /// and A256GCM, as RFC 7516 and RFC 7518 define them.
    private static func decryptKeys(
        _ jwe: String, with privateKey: P256.KeyAgreement.PrivateKey
    ) throws -> [String: Any] {
        let parts = jwe.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 5, let headerData = Data(base64URLEncoded: parts[0]),
            let header = (try? JSONSerialization.jsonObject(with: headerData)) as? [String: Any],
            header.string("alg") == "ECDH-ES", header.string("enc") == "A256GCM",
            let epk = header.object("epk"), epk.string("crv") == "P-256",
            let x = epk.string("x").flatMap({ Data(base64URLEncoded: $0) }),
            let y = epk.string("y").flatMap({ Data(base64URLEncoded: $0) }),
            let iv = Data(base64URLEncoded: parts[2]), let ciphertext = Data(base64URLEncoded: parts[3]),
            let tag = Data(base64URLEncoded: parts[4])
        else {
            throw MozillaAccountError.malformed("keys_jwe")
        }
        let sender = try P256.KeyAgreement.PublicKey(x963Representation: Data([0x04]) + x + y)
        let shared = try privateKey.sharedSecretFromKeyAgreement(with: sender).withUnsafeBytes { Data($0) }

        // Concat KDF (RFC 7518, section 4.6.2) for a 256 bit key: one round of SHA-256.
        func lengthPrefixed(_ data: Data) -> Data {
            bigEndian(UInt32(data.count)) + data
        }
        func bigEndian(_ value: UInt32) -> Data {
            withUnsafeBytes(of: value.bigEndian) { Data($0) }
        }
        let apu = header.string("apu").flatMap { Data(base64URLEncoded: $0) } ?? Data()
        let apv = header.string("apv").flatMap { Data(base64URLEncoded: $0) } ?? Data()
        let otherInfo = lengthPrefixed(Data("A256GCM".utf8)) + lengthPrefixed(apu) + lengthPrefixed(apv) + bigEndian(256)
        let contentKey = SymmetricKey(data: Data(SHA256.hash(data: bigEndian(1) + shared + otherInfo)))

        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: iv), ciphertext: ciphertext, tag: tag)
        let cleartext = try AES.GCM.open(box, using: contentKey, authenticating: Data(parts[0].utf8))
        guard let keys = (try? JSONSerialization.jsonObject(with: cleartext)) as? [String: Any] else {
            throw MozillaAccountError.malformed("scoped keys")
        }
        return keys
    }
}

/// The one Keychain item holding the signed in account, readable after the first unlock and never leaving the device.
private enum Keychain {
    private static let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "com.byarchitect.dejavu.mozilla-account",
        kSecAttrAccount as String: "default",
    ]

    static func read() -> Data? {
        var search = query
        search[kSecReturnData as String] = true
        search[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(search as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    static func write(_ data: Data) {
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            let item = query.merging(attributes) { _, new in new }
            let added = SecItemAdd(item as CFDictionary, nil)
            if added != errSecSuccess {
                Log.error("Could not keep the Mozilla account in the Keychain: \(added)")
            }
        } else if status != errSecSuccess {
            Log.error("Could not update the Mozilla account in the Keychain: \(status)")
        }
    }

    static func delete() {
        SecItemDelete(query as CFDictionary)
    }
}
