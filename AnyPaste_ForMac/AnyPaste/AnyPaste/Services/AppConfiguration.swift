import Foundation

struct AppConfiguration: Equatable, Sendable {
    let firebaseAPIKey: String
    let firebaseProjectID: String
    let firebaseStorageBucket: String

    init(
        firebaseAPIKey: String,
        firebaseProjectID: String,
        firebaseStorageBucket: String
    ) throws {
        self.firebaseAPIKey = try Self.validated(
            firebaseAPIKey,
            key: "FIREBASE_API_KEY",
            label: "Firebase API 키"
        )
        let projectID = try Self.validated(
            firebaseProjectID,
            key: "FIREBASE_PROJECT_ID",
            label: "Firebase 프로젝트 ID"
        )
        guard !projectID.contains("/") else {
            throw AppConfigurationError.invalidValue(
                key: "FIREBASE_PROJECT_ID",
                label: "Firebase 프로젝트 ID"
            )
        }
        self.firebaseProjectID = projectID

        let bucket = try Self.validated(
            firebaseStorageBucket,
            key: "FIREBASE_STORAGE_BUCKET",
            label: "Firebase Storage 버킷"
        )
        let normalizedBucket = bucket.hasPrefix("gs://")
            ? String(bucket.dropFirst("gs://".count))
            : bucket
        guard !normalizedBucket.isEmpty, !normalizedBucket.contains("/") else {
            throw AppConfigurationError.invalidValue(
                key: "FIREBASE_STORAGE_BUCKET",
                label: "Firebase Storage 버킷"
            )
        }
        self.firebaseStorageBucket = normalizedBucket
    }

    init(bundle: Bundle = .main) throws {
        try self.init(
            firebaseAPIKey: Self.bundleString("FIREBASE_API_KEY", bundle: bundle),
            firebaseProjectID: Self.bundleString("FIREBASE_PROJECT_ID", bundle: bundle),
            firebaseStorageBucket: Self.bundleString("FIREBASE_STORAGE_BUCKET", bundle: bundle)
        )
    }

    static func load(bundle: Bundle = .main) throws -> AppConfiguration {
        try AppConfiguration(bundle: bundle)
    }

    private static func bundleString(_ key: String, bundle: Bundle) -> String {
        bundle.object(forInfoDictionaryKey: key) as? String ?? ""
    }

    private static func validated(_ rawValue: String, key: String, label: String) throws -> String {
        let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else {
            throw AppConfigurationError.missingValue(key: key, label: label)
        }
        guard !value.contains("$("),
              !value.contains("${"),
              !value.lowercased().hasPrefix("your_") else {
            throw AppConfigurationError.unresolvedValue(key: key, label: label)
        }
        guard !value.contains(where: { $0.isWhitespace }) else {
            throw AppConfigurationError.invalidValue(key: key, label: label)
        }
        return value
    }
}

enum AppConfigurationError: LocalizedError, Equatable {
    case missingValue(key: String, label: String)
    case unresolvedValue(key: String, label: String)
    case invalidValue(key: String, label: String)

    var errorDescription: String? {
        switch self {
        case let .missingValue(key, label):
            return "\(label)이 설정되지 않았습니다. Info.plist의 \(key) 값을 확인해 주세요."
        case let .unresolvedValue(key, label):
            return "\(label)이 실제 값으로 치환되지 않았습니다. 빌드 설정의 \(key) 값을 확인해 주세요."
        case let .invalidValue(key, label):
            return "\(label) 형식이 올바르지 않습니다. \(key) 값을 확인해 주세요."
        }
    }
}
