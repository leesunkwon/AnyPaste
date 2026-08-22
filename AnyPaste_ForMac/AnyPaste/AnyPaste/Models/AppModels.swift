import Foundation

enum AppPhase: Equatable, Sendable {
    case configurationRequired([String])
    case signedOut
    case authenticated
}

enum AppRoute: String, Codable, CaseIterable, Identifiable, Sendable {
    case home
    case history
    case send
    case devices
    case settings

    var id: String { rawValue }
}

enum SyncStatus: String, Codable, CaseIterable, Identifiable, Sendable {
    case stopped
    case syncing
    case upToDate
    case offline
    case failed

    var id: String { rawValue }
}

enum ClipboardKind: String, Codable, CaseIterable, Identifiable, Sendable {
    case text
    case image
    case file

    var id: String { rawValue }
}

enum DevicePlatform: String, Codable, CaseIterable, Identifiable, Sendable {
    case android
    case macOS = "macos"

    var id: String { rawValue }
}

enum TransferState: String, Codable, CaseIterable, Identifiable, Sendable {
    case idle
    case preparing
    case uploading
    case downloading
    case succeeded
    case failed

    var id: String { rawValue }
}

struct AppUser: Codable, Identifiable, Equatable, Sendable {
    let id: String
    var email: String
    var displayName: String
    var photoUrl: String
    var isEmailVerified: Bool
    var providers: [String]
    var createdAt: Date?
    var lastActiveAt: Date?

    init(
        id: String,
        email: String = "",
        displayName: String = "",
        photoUrl: String = "",
        isEmailVerified: Bool = false,
        providers: [String] = [],
        createdAt: Date? = nil,
        lastActiveAt: Date? = nil
    ) {
        self.id = id
        self.email = email
        self.displayName = displayName
        self.photoUrl = photoUrl
        self.isEmailVerified = isEmailVerified
        self.providers = providers
        self.createdAt = createdAt
        self.lastActiveAt = lastActiveAt
    }

    enum CodingKeys: String, CodingKey {
        case id
        case email
        case displayName
        case photoUrl
        case isEmailVerified
        case providers
        case createdAt
        case lastActiveAt
    }
}

struct AuthSession: Codable, Identifiable, Equatable, Sendable {
    var id: String { user.id }

    var user: AppUser
    var idToken: String
    var refreshToken: String
    var expiresAt: Date

    func isExpired(at date: Date = Date(), leeway: TimeInterval = 60) -> Bool {
        expiresAt.timeIntervalSince(date) <= leeway
    }
}

struct ClipboardRecord: Codable, Identifiable, Equatable, Sendable {
    let id: String
    var kind: ClipboardKind
    var content: String
    var storagePath: String
    var fileName: String
    var fileSize: Int64
    var mimeType: String
    var sourceDeviceId: String
    var targetDeviceId: String
    var createdAt: Date?
    var expiresAt: Date?
    var readBy: [String]

    init(
        id: String,
        kind: ClipboardKind,
        content: String = "",
        storagePath: String = "",
        fileName: String = "",
        fileSize: Int64 = 0,
        mimeType: String = "",
        sourceDeviceId: String,
        targetDeviceId: String = "",
        createdAt: Date? = nil,
        expiresAt: Date? = nil,
        readBy: [String] = []
    ) {
        self.id = id
        self.kind = kind
        self.content = content
        self.storagePath = storagePath
        self.fileName = fileName
        self.fileSize = fileSize
        self.mimeType = mimeType
        self.sourceDeviceId = sourceDeviceId
        self.targetDeviceId = targetDeviceId
        self.createdAt = createdAt
        self.expiresAt = expiresAt
        self.readBy = readBy
    }

    var isExpired: Bool {
        expiresAt.map { $0 <= Date() } ?? false
    }

    func isRead(by deviceID: String) -> Bool {
        readBy.contains(deviceID)
    }

    var summary: String {
        switch kind {
        case .text:
            let normalized = content
                .replacingOccurrences(of: "\n", with: " ")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return normalized.isEmpty ? "빈 텍스트" : String(normalized.prefix(120))
        case .image:
            return fileName.isEmpty ? "이미지" : fileName
        case .file:
            return fileName.isEmpty ? "파일" : fileName
        }
    }

    enum CodingKeys: String, CodingKey {
        case id
        case kind = "type"
        case content
        case storagePath
        case fileName
        case fileSize
        case mimeType
        case sourceDeviceId
        case targetDeviceId
        case createdAt
        case expiresAt
        case readBy
    }
}

struct DeviceRecord: Codable, Identifiable, Equatable, Sendable {
    let id: String
    var platform: String
    var deviceName: String
    var fcmToken: String
    var lastSeenAt: Date?
    var isOnline: Bool

    init(
        id: String,
        platform: String = DevicePlatform.macOS.rawValue,
        deviceName: String,
        fcmToken: String = "",
        lastSeenAt: Date? = nil,
        isOnline: Bool = false
    ) {
        self.id = id
        self.platform = platform
        self.deviceName = deviceName
        self.fcmToken = fcmToken
        self.lastSeenAt = lastSeenAt
        self.isOnline = isOnline
    }

    func isRecentlyOnline(at date: Date = Date(), timeout: TimeInterval = 120) -> Bool {
        guard isOnline, let lastSeenAt else { return false }
        return date.timeIntervalSince(lastSeenAt) <= timeout
    }

    var resolvedPlatform: DevicePlatform? {
        DevicePlatform(rawValue: platform)
    }
}

struct StorageUploadResult: Codable, Identifiable, Equatable, Sendable {
    var id: String { storagePath }

    let storagePath: String
    let fileName: String
    let fileSize: Int64
    let mimeType: String
}

struct TransferProgress: Codable, Identifiable, Equatable, Sendable {
    let id: UUID
    var state: TransferState
    var bytesTransferred: Int64
    var totalBytes: Int64
    var message: String

    init(
        id: UUID = UUID(),
        state: TransferState = .idle,
        bytesTransferred: Int64 = 0,
        totalBytes: Int64 = 0,
        message: String = ""
    ) {
        self.id = id
        self.state = state
        self.bytesTransferred = bytesTransferred
        self.totalBytes = totalBytes
        self.message = message
    }

    var fractionCompleted: Double {
        guard totalBytes > 0 else { return 0 }
        return min(max(Double(bytesTransferred) / Double(totalBytes), 0), 1)
    }
}
