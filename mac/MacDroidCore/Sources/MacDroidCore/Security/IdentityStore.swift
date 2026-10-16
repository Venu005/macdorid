import Crypto
import Foundation
import NIOConcurrencyHelpers
import Security

public protocol IdentityStore: Sendable {
    func load() throws -> Identity?
    func save(_ identity: Identity) throws
}

extension IdentityStore {
    public func loadOrCreate(commonName: String) throws -> Identity {
        if let existing = try load() { return existing }
        let identity = try Identity.generate(commonName: commonName)
        try save(identity)
        return identity
    }
}

public final class InMemoryIdentityStore: IdentityStore {
    private let box = NIOLockedValueBox<Identity?>(nil)

    public init() {}
    public func load() throws -> Identity? { box.withLockedValue { $0 } }
    public func save(_ identity: Identity) throws { box.withLockedValue { $0 = identity } }
}

public enum KeychainError: Error, Equatable {
    case unexpectedStatus(OSStatus)
}

/// Stores the identity as two generic-password items. Uses the file-based login keychain so an
/// ad-hoc signed development build works; after a rebuild macOS may ask once to allow access.
public final class KeychainIdentityStore: IdentityStore {
    private static let keyAccount = "private-key"
    private static let certificateAccount = "certificate"
    private let service: String

    public init(service: String = "dev.macdroid.identity") {
        self.service = service
    }

    public func load() throws -> Identity? {
        guard let keyData = try read(account: Self.keyAccount),
              let certificateData = try read(account: Self.certificateAccount) else { return nil }
        return Identity(
            privateKey: try P256.Signing.PrivateKey(rawRepresentation: keyData),
            certificateDER: Array(certificateData)
        )
    }

    public func save(_ identity: Identity) throws {
        try write(account: Self.keyAccount, data: identity.privateKey.rawRepresentation)
        try write(account: Self.certificateAccount, data: Data(identity.certificateDER))
    }

    public func delete() {
        for account in [Self.keyAccount, Self.certificateAccount] {
            SecItemDelete(baseQuery(account: account) as CFDictionary)
        }
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private func read(account: String) throws -> Data? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainError.unexpectedStatus(status) }
        return result as? Data
    }

    /// Updates in place, adding only when missing: never a delete-then-add window in which a crash loses the item
    /// and the next launch silently mints a new identity, breaking pairing (Plan 11).
    private func write(account: String, data: Data) throws {
        let update = SecItemUpdate(baseQuery(account: account) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return }
        guard update == errSecItemNotFound else { throw KeychainError.unexpectedStatus(update) }
        var query = baseQuery(account: account)
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.unexpectedStatus(status) }
    }
}
