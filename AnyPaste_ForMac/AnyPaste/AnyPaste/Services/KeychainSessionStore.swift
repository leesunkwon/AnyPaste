import Foundation
import Security

protocol SessionStoring: Sendable {
    func save(_ session: AuthSession) throws
    func load() throws -> AuthSession?
    func delete() throws
}

final class KeychainSessionStore: SessionStoring, @unchecked Sendable {
    private let service: String
    private let account: String

    init(
        service: String? = nil,
        account: String = "firebase-auth-session"
    ) {
        let bundleIdentifier = Bundle.main.bundleIdentifier ?? "com.anypaste.AnyPaste"
        self.service = service ?? "\(bundleIdentifier).session"
        self.account = account
    }

    func save(_ session: AuthSession) throws {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601

        let encoded: Data
        do {
            encoded = try encoder.encode(session)
        } catch {
            throw KeychainSessionStoreError.encodingFailed
        }

        let lookup = baseQuery
        let attributes: [String: Any] = [
            kSecValueData as String: encoded
        ]
        let updateStatus = SecItemUpdate(lookup as CFDictionary, attributes as CFDictionary)

        switch updateStatus {
        case errSecSuccess:
            return
        case errSecItemNotFound:
            var insert = lookup
            insert[kSecValueData as String] = encoded
            insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

            let addStatus = SecItemAdd(insert as CFDictionary, nil)
            guard addStatus == errSecSuccess else {
                throw KeychainSessionStoreError.keychainFailure(addStatus)
            }
        default:
            throw KeychainSessionStoreError.keychainFailure(updateStatus)
        }
    }

    func load() throws -> AuthSession? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess else {
            throw KeychainSessionStoreError.keychainFailure(status)
        }
        guard let data = item as? Data else {
            throw KeychainSessionStoreError.invalidStoredValue
        }

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        do {
            return try decoder.decode(AuthSession.self, from: data)
        } catch {
            throw KeychainSessionStoreError.invalidStoredValue
        }
    }

    func delete() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainSessionStoreError.keychainFailure(status)
        }
    }

    func clear() throws {
        try delete()
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }
}

enum KeychainSessionStoreError: LocalizedError {
    case encodingFailed
    case invalidStoredValue
    case keychainFailure(OSStatus)

    var errorDescription: String? {
        switch self {
        case .encodingFailed:
            return "로그인 정보를 안전하게 저장할 수 없습니다."
        case .invalidStoredValue:
            return "저장된 로그인 정보가 손상되었습니다. 다시 로그인해 주세요."
        case let .keychainFailure(status):
            return "키체인 작업에 실패했습니다. (오류 코드: \(status))"
        }
    }
}
