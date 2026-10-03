// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import CommonCrypto
import CryptoKit
import Foundation
import Security

/// A sync record or key that could not be decrypted, usually because it was encrypted with other keys.
struct SyncCryptoError: Error, CustomStringConvertible {
    let description: String

    init(_ description: String) {
        self.description = description
    }
}

/// `count` random bytes from the system's secure random generator.
func secureRandomBytes(_ count: Int) -> Data {
    var bytes = [UInt8](repeating: 0, count: count)
    if SecRandomCopyBytes(kSecRandomDefault, count, &bytes) != errSecSuccess {
        var generator = SystemRandomNumberGenerator()
        bytes = (0..<count).map { _ in UInt8.random(in: .min ... .max, using: &generator) }
    }
    return Data(bytes)
}

extension Data {
    /// Reads base64url, with or without padding.
    init?(base64URLEncoded text: String) {
        var base64 = text.trimmingCharacters(in: CharacterSet(charactersIn: "="))
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 {
            base64 += "="
        }
        self.init(base64Encoded: base64)
    }

    /// The bytes in base64url without padding.
    var base64URLEncoded: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .trimmingCharacters(in: CharacterSet(charactersIn: "="))
    }

    var hex: String {
        map { String(format: "%02x", $0) }.joined()
    }
}

/// An AES-256 key and an HMAC-SHA256 key that encrypt and authenticate Firefox Sync records, like Firefox's
/// `BulkKeyBundle`. A payload is `{ciphertext, IV, hmac}`: base64 AES-CBC ciphertext and IV, and the hex HMAC of the
/// base64 ciphertext text.
struct KeyBundle {
    private static let keySize = 32
    private static let ivSize = kCCBlockSizeAES128

    let encryptionKey: Data
    let hmacKey: Data

    func encrypt(_ cleartext: String) throws -> [String: Any] {
        let iv = secureRandomBytes(Self.ivSize)
        let ciphertext = try aes(CCOperation(kCCEncrypt), Data(cleartext.utf8), iv: iv).base64EncodedString()
        return ["ciphertext": ciphertext, "IV": iv.base64EncodedString(), "hmac": hmac(ciphertext)]
    }

    func decrypt(_ payload: [String: Any]) throws -> String {
        guard let ciphertext = payload.string("ciphertext") else { throw SyncCryptoError("No ciphertext") }
        guard let iv = payload.string("IV") else { throw SyncCryptoError("No IV") }
        guard let expected = payload.string("hmac")?.lowercased() else { throw SyncCryptoError("No HMAC") }
        guard constantTimeEqual(Data(hmac(ciphertext).utf8), Data(expected.utf8)) else {
            throw SyncCryptoError("HMAC mismatch")
        }
        guard let ivData = Data(base64Encoded: iv), let cipherData = Data(base64Encoded: ciphertext) else {
            throw SyncCryptoError("Malformed payload")
        }
        let cleartext = try aes(CCOperation(kCCDecrypt), cipherData, iv: ivData)
        guard let text = String(data: cleartext, encoding: .utf8) else { throw SyncCryptoError("Cleartext is not UTF-8") }
        return text
    }

    private func hmac(_ ciphertext: String) -> String {
        Data(HMAC<SHA256>.authenticationCode(for: Data(ciphertext.utf8), using: SymmetricKey(data: hmacKey))).hex
    }

    private func aes(_ operation: CCOperation, _ input: Data, iv: Data) throws -> Data {
        guard iv.count == Self.ivSize else { throw SyncCryptoError("Unexpected IV length \(iv.count)") }
        var output = Data(count: input.count + kCCBlockSizeAES128)
        let capacity = output.count
        var written = 0
        let status = output.withUnsafeMutableBytes { outBytes in
            input.withUnsafeBytes { inBytes in
                iv.withUnsafeBytes { ivBytes in
                    encryptionKey.withUnsafeBytes { keyBytes in
                        CCCrypt(
                            operation, CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionPKCS7Padding),
                            keyBytes.baseAddress, encryptionKey.count, ivBytes.baseAddress,
                            inBytes.baseAddress, input.count, outBytes.baseAddress, capacity, &written)
                    }
                }
            }
        }
        guard status == kCCSuccess else { throw SyncCryptoError("AES failed with \(status)") }
        return output.prefix(written)
    }

    private func constantTimeEqual(_ first: Data, _ second: Data) -> Bool {
        guard first.count == second.count else { return false }
        return zip(first, second).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    /// The bundle of the account's sync key: the `k` of the sync scope's key, 64 bytes in base64url.
    static func fromSyncKey(_ key: String) throws -> KeyBundle {
        guard let bytes = Data(base64URLEncoded: key) else { throw SyncCryptoError("Malformed sync key") }
        guard bytes.count == 2 * keySize else { throw SyncCryptoError("Unexpected sync key length \(bytes.count)") }
        return KeyBundle(encryptionKey: Data(bytes.prefix(keySize)), hmacKey: Data(bytes.suffix(keySize)))
    }

    /// A bundle as crypto/keys stores it: `[encryption key, HMAC key]`, both in base64.
    static func fromPair(_ pair: [Any]?) -> KeyBundle? {
        guard let pair, pair.count >= 2, let encryption = (pair[0] as? String).flatMap({ Data(base64Encoded: $0) }),
            let hmac = (pair[1] as? String).flatMap({ Data(base64Encoded: $0) }),
            encryption.count == keySize, hmac.count == keySize
        else {
            return nil
        }
        return KeyBundle(encryptionKey: encryption, hmacKey: hmac)
    }
}

/// Signs requests to the sync storage server with Hawk, like Firefox's `CryptoUtils.computeHAWK`.
enum Hawk {
    private static let nonceSize = 8

    /// The `Authorization` header for a request.
    ///
    /// - Parameters:
    ///   - id: Hawk id, from the token server.
    ///   - key: Hawk key, from the token server.
    ///   - method: HTTP method of the request.
    ///   - url: Full URL of the request, exactly as it is sent.
    ///   - seconds: Current time on the server, in seconds.
    ///   - nonce: Random text that makes the signature unique.
    ///   - payloadHash: See `payloadHash`; storage servers do not require it.
    ///   - ext: Application data covered by the signature; not used by sync.
    static func header(
        id: String,
        key: String,
        method: String,
        url: String,
        seconds: Int64,
        nonce: String = newNonce(),
        payloadHash: String? = nil,
        ext: String? = nil
    ) -> String {
        let components = URLComponents(string: url)
        let path = components?.percentEncodedPath ?? ""
        let resource = (path.isEmpty ? "/" : path) + (components?.percentEncodedQuery.map { "?\($0)" } ?? "")
        let scheme = components?.scheme?.lowercased()
        let port = components?.port ?? (scheme == "http" ? 80 : 443)
        var normalized = "hawk.1.header\n"
        normalized += "\(seconds)\n"
        normalized += "\(nonce)\n"
        normalized += "\(method.uppercased())\n"
        normalized += "\(resource)\n"
        normalized += "\(components?.host?.lowercased() ?? "")\n"
        normalized += "\(port)\n"
        normalized += "\(payloadHash ?? "")\n"
        if let ext {
            normalized += ext.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\n", with: "\\n")
        }
        normalized += "\n"
        let mac = HMAC<SHA256>.authenticationCode(for: Data(normalized.utf8), using: SymmetricKey(data: Data(key.utf8)))
        let signature = Data(mac).base64EncodedString()
        var header = "Hawk id=\"\(id)\", ts=\"\(seconds)\", nonce=\"\(nonce)\", "
        if let payloadHash {
            header += "hash=\"\(payloadHash)\", "
        }
        if let ext {
            header += "ext=\"\(ext.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\""))\", "
        }
        header += "mac=\"\(signature)\""
        return header
    }

    /// The Hawk hash of a request body with the given MIME type (without parameters).
    static func payloadHash(contentType: String, payload: String) -> String {
        Data(SHA256.hash(data: Data("hawk.1.payload\n\(contentType)\n\(payload)\n".utf8))).base64EncodedString()
    }

    static func newNonce() -> String {
        secureRandomBytes(nonceSize).base64EncodedString()
    }
}
