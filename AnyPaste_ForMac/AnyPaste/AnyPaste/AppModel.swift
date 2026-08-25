import AppKit
import Combine
import CryptoKit
import Foundation
import UniformTypeIdentifiers

struct FailedTransferRecord: Identifiable, Equatable {
    let id: UUID
    let title: String
    let meta: String
    let reason: String
}

@MainActor
final class AppModel: ObservableObject {
    private struct PreparedFile: Sendable {
        let data: Data
        let fileName: String
        let mimeType: String
        let kind: ClipboardKind
    }

    private struct AutomaticSendContext: Sendable {
        let userID: String
        let fingerprint: String
        let inFlightKey: String
        let observationSequence: UInt64

        var recordID: String {
            "auto-\(fingerprint.prefix(32))"
        }
    }

    private struct AutomaticObservation: Sendable {
        let sequence: UInt64
        var fingerprint: String?
    }

    private enum RetryableTransfer {
        case text(value: String, targetDeviceID: String?, retention: ClipboardRetention)
        case files(urls: [URL], targetDeviceID: String?, retention: ClipboardRetention)

        var title: String {
            switch self {
            case let .text(value, _, _):
                return String(value.split(separator: "\n", maxSplits: 1).first ?? "텍스트").prefix(60).description
            case let .files(urls, _, _):
                return urls.count == 1 ? (urls.first?.lastPathComponent ?? "파일") : "파일 \(urls.count)개"
            }
        }

        var meta: String {
            switch self {
            case let .text(value, _, _):
                return "텍스트 · \(value.lengthOfBytes(using: .utf8)) B"
            case .files:
                return "파일"
            }
        }
    }

    @Published private(set) var authPhase: AppPhase
    @Published private(set) var currentUser: AppUser?
    @Published var selectedRoute: AppRoute = .home
    @Published private(set) var clipboardItems: [ClipboardRecord] = []
    @Published private(set) var devices: [DeviceRecord] = []
    @Published var selectedItem: ClipboardRecord?
    @Published private(set) var isLoading = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var syncStatus: SyncStatus = .stopped
    @Published private(set) var transferState: TransferState = .idle
    @Published private(set) var failedTransfers: [FailedTransferRecord] = []
    @Published private(set) var deviceDisplayName = ""

    @Published var autoSync: Bool {
        didSet {
            guard autoSync != oldValue else { return }
            defaults.set(autoSync, forKey: PreferenceKey.autoSync)
            updateClipboardMonitoring()
            if autoSync, authPhase == .authenticated {
                Task { [weak self] in
                    await self?.refreshContent(reportErrors: false)
                }
            }
        }
    }

    @Published var notificationsEnabled: Bool {
        didSet {
            guard notificationsEnabled != oldValue else { return }
            defaults.set(notificationsEnabled, forKey: PreferenceKey.notificationsEnabled)
            guard notificationsEnabled else { return }
            Task { [weak self] in
                await self?.ensureNotificationAuthorization(reportErrors: true)
            }
        }
    }

    @Published var wifiOnlyTransfers: Bool {
        didSet {
            guard wifiOnlyTransfers != oldValue else { return }
            defaults.set(wifiOnlyTransfers, forKey: PreferenceKey.wifiOnlyTransfers)
        }
    }

    let currentDeviceID: String

    var storageUsageBytes: Int64 {
        clipboardItems.lazy
            .filter { !$0.isExpired && $0.fileSize > 0 }
            .reduce(Int64(0)) { $0 + $1.fileSize }
    }

    var storageLimitBytes: Int64 { Self.maximumStorageBytes }

    var onlineDevices: [DeviceRecord] {
        devices.filter { $0.isRecentlyOnline() }
    }

    var unreadCount: Int {
        clipboardItems.lazy.filter(isUnreadIncomingRecord).count
    }

    private var pendingIncomingCount: Int {
        clipboardItems.lazy.filter(isPendingIncomingRecord).count
    }

    var isConnected: Bool {
        networkMonitor.isConnected
    }

    private let defaults: UserDefaults
    private let sessionStore: KeychainSessionStore
    private let clipboardMonitor: ClipboardMonitor
    private let notificationService: NotificationService
    private let networkMonitor: NetworkMonitor

    private var configuration: AppConfiguration?
    private var firebaseClient: FirebaseRESTClient?
    private var authSession: AuthSession?
    private var refreshTask: Task<AuthSession, Error>?
    private var pollingTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?
    private var networkCancellable: AnyCancellable?
    private var terminationObserver: NSObjectProtocol?
    private var automaticSendKeysInFlight: Set<String> = []
    private var automaticObservationSequenceByUser: [String: UInt64] = [:]
    private var latestAutomaticObservationByUser: [String: AutomaticObservation] = [:]
    private var refreshIsRunning = false
    private var didStart = false
    private var retryableTransfers: [UUID: RetryableTransfer] = [:]

    private static let maximumTextLength = 100_000
    private static let maximumTransferBytes: Int64 = 50 * 1024 * 1024
    private static let maximumStorageBytes: Int64 = 1024 * 1024 * 1024
    private static let clipboardTTL: TimeInterval = 24 * 60 * 60
    private static let pollingNanoseconds: UInt64 = 2_000_000_000
    private static let heartbeatNanoseconds: UInt64 = 60_000_000_000
    private static let maximumFailedTransfers = 20

    init(
        defaults: UserDefaults = .standard,
        sessionStore: KeychainSessionStore? = nil,
        clipboardMonitor: ClipboardMonitor? = nil,
        notificationService: NotificationService? = nil,
        networkMonitor: NetworkMonitor? = nil
    ) {
        self.defaults = defaults
        self.sessionStore = sessionStore ?? KeychainSessionStore()
        self.clipboardMonitor = clipboardMonitor ?? ClipboardMonitor()
        self.notificationService = notificationService ?? NotificationService()
        self.networkMonitor = networkMonitor ?? NetworkMonitor()

        autoSync = Self.storedBoolean(
            in: defaults,
            key: PreferenceKey.autoSync,
            defaultValue: true
        )
        notificationsEnabled = Self.storedBoolean(
            in: defaults,
            key: PreferenceKey.notificationsEnabled,
            defaultValue: true
        )
        wifiOnlyTransfers = Self.storedBoolean(
            in: defaults,
            key: PreferenceKey.wifiOnlyTransfers,
            defaultValue: false
        )
        currentDeviceID = Self.resolveDeviceID(defaults: defaults)
        deviceDisplayName = defaults.string(forKey: PreferenceKey.deviceDisplayName) ?? ""

        do {
            let configuration = try AppConfiguration.load()
            self.configuration = configuration
            firebaseClient = FirebaseRESTClient(configuration: configuration)
            authPhase = .signedOut
        } catch {
            configuration = nil
            firebaseClient = nil
            authPhase = .configurationRequired([Self.message(for: error)])
        }

        networkCancellable = self.networkMonitor.$isConnected
            .removeDuplicates()
            .sink { [weak self] isConnected in
                Task { @MainActor [weak self] in
                    self?.networkConnectionDidChange(isConnected)
                }
            }

        terminationObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.willTerminateNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.stop()
            }
        }
    }

    func start() async {
        guard !didStart else { return }
        didStart = true
        Self.cleanupExpiredCache()

        guard firebaseClient != nil else { return }
        await restoreSession()
    }

    func retryConfiguration() {
        Task { [weak self] in
            await self?.reloadConfiguration()
        }
    }

    func stop() {
        stopAuthenticatedServices()
        guard let client = firebaseClient, let session = authSession else { return }
        let userID = session.user.id
        let deviceID = currentDeviceID
        Task {
            try? await client.markDeviceOffline(
                userId: userID,
                deviceId: deviceID,
                idToken: session.idToken
            )
        }
    }

    func signIn(email: String, password: String) async {
        guard !isLoading else { return }
        guard let client = firebaseClient else {
            showConfigurationError()
            return
        }

        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            let session = try await client.signIn(email: email, password: password)
            try activate(session)
            await finishAuthentication(isNewUser: false)
        } catch {
            handle(error, invalidatesExpiredSession: false)
        }
    }

    func signUp(
        email: String,
        name: String,
        password: String,
        confirmation: String
    ) async {
        guard !isLoading else { return }
        guard password == confirmation else {
            errorMessage = "비밀번호가 일치하지 않습니다."
            return
        }
        guard password.count >= 8 else {
            errorMessage = "비밀번호는 8자 이상이어야 합니다."
            return
        }
        guard let client = firebaseClient else {
            showConfigurationError()
            return
        }

        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            let session = try await client.signUp(
                email: email,
                password: password,
                displayName: name
            )
            try activate(session)
            await finishAuthentication(isNewUser: true)
        } catch {
            handle(error, invalidatesExpiredSession: false)
        }
    }

    func sendPasswordReset(email: String) async {
        guard !isLoading else { return }
        guard let client = firebaseClient else {
            showConfigurationError()
            return
        }

        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            try await client.sendPasswordReset(email: email)
        } catch {
            handle(error, invalidatesExpiredSession: false)
        }
    }

    func savedTransferTargetID(validDeviceIDs: Set<String>) -> String {
        let saved = defaults.string(forKey: PreferenceKey.lastTransferTargetDeviceID) ?? ""
        return saved.isEmpty || validDeviceIDs.contains(saved) ? saved : ""
    }

    func rememberTransferTarget(_ deviceID: String?) {
        defaults.set(deviceID ?? "", forKey: PreferenceKey.lastTransferTargetDeviceID)
    }

    func renameDevice(_ device: DeviceRecord, to name: String) async {
        let normalizedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedName.isEmpty, normalizedName.count <= 100 else {
            errorMessage = "기기 이름은 1~100자로 입력해 주세요."
            return
        }
        guard !isLoading else { return }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            if device.id == currentDeviceID {
                deviceDisplayName = normalizedName
                defaults.set(normalizedName, forKey: PreferenceKey.deviceDisplayName)
                await registerCurrentDevice(reportErrors: true)
            } else {
                try await withAuthenticatedSession { client, session in
                    try await client.renameDevice(
                        userId: session.user.id,
                        deviceId: device.id,
                        deviceName: normalizedName,
                        idToken: session.idToken
                    )
                }
                if let index = devices.firstIndex(where: { $0.id == device.id }) {
                    devices[index].deviceName = normalizedName
                    devices[index].lastSeenAt = Date()
                }
            }
        } catch {
            handle(error)
        }
    }

    func sendText(
        _ text: String,
        targetDeviceID: String? = nil,
        retention: ClipboardRetention = .oneDay
    ) async {
        await sendText(
            text,
            targetDeviceID: targetDeviceID,
            retention: retention,
            retryID: UUID()
        )
    }

    func retryFailedTransfer(_ id: UUID) async {
        guard !isLoading, let transfer = retryableTransfers[id] else { return }
        switch transfer {
        case let .text(value, targetDeviceID, retention):
            await sendText(value, targetDeviceID: targetDeviceID, retention: retention, retryID: id)
        case let .files(urls, targetDeviceID, retention):
            await sendFiles(urls, targetDeviceID: targetDeviceID, retention: retention, retryID: id)
        }
    }

    private func sendText(
        _ text: String,
        targetDeviceID: String?,
        retention: ClipboardRetention,
        retryID: UUID
    ) async {
        guard !isLoading else { return }
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            errorMessage = "전송할 텍스트를 입력해 주세요."
            return
        }
        guard text.count <= Self.maximumTextLength else {
            errorMessage = "텍스트는 10만 자 이하여야 합니다."
            return
        }
        guard validateTargetDevice(targetDeviceID) else { return }

        isLoading = true
        errorMessage = nil
        transferState = .preparing
        defer { isLoading = false }

        do {
            let previousRecords = clipboardItems
            let record = ClipboardRecord(
                id: UUID().uuidString.lowercased(),
                kind: .text,
                content: text,
                sourceDeviceId: currentDeviceID,
                targetDeviceId: targetDeviceID ?? "",
                expiresAt: Date().addingTimeInterval(retention.duration),
                readBy: []
            )
            let created: ClipboardRecord = try await withAuthenticatedSession { client, session in
                try await client.createClipboard(
                    userId: session.user.id,
                    record: record,
                    idToken: session.idToken
                )
            }
            await removeReplacedStorageObjects(previousRecords, keeping: created.storagePath)
            insertOrReplace(created)
            transferState = .succeeded
            syncStatus = .upToDate
            resolveFailedTransfer(retryID)
        } catch {
            transferState = .failed
            recordFailedTransfer(
                id: retryID,
                transfer: .text(value: text, targetDeviceID: targetDeviceID, retention: retention),
                error: error
            )
            handle(error)
        }
    }

    func sendFiles(
        _ urls: [URL],
        targetDeviceID: String? = nil,
        retention: ClipboardRetention = .oneDay
    ) async {
        await sendFiles(
            urls,
            targetDeviceID: targetDeviceID,
            retention: retention,
            retryID: UUID()
        )
    }

    private func sendFiles(
        _ urls: [URL],
        targetDeviceID: String?,
        retention: ClipboardRetention,
        retryID: UUID
    ) async {
        guard !isLoading else { return }
        guard !urls.isEmpty else {
            errorMessage = "전송할 파일을 선택해 주세요."
            return
        }
        guard validateTargetDevice(targetDeviceID) else { return }

        isLoading = true
        errorMessage = nil
        transferState = .preparing
        defer { isLoading = false }

        var activeURL: URL?
        do {
            for url in urls {
                activeURL = url
                try await sendFile(url, targetDeviceID: targetDeviceID, retention: retention)
            }
            transferState = .succeeded
            syncStatus = .upToDate
            resolveFailedTransfer(retryID)
        } catch {
            transferState = .failed
            let retryURLs = activeURL.map { [$0] } ?? urls
            recordFailedTransfer(
                id: retryID,
                transfer: .files(urls: retryURLs, targetDeviceID: targetDeviceID, retention: retention),
                error: error
            )
            handle(error)
        }
    }

    func delete(_ record: ClipboardRecord) async {
        guard !isLoading else { return }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            try await withAuthenticatedSession { client, session in
                try await client.deleteClipboard(
                    userId: session.user.id,
                    itemId: record.id,
                    idToken: session.idToken
                )
            }

            clipboardItems.removeAll { $0.id == record.id }
            if selectedItem?.id == record.id {
                selectedItem = nil
            }

            if !record.storagePath.isEmpty {
                do {
                    try await withAuthenticatedSession { client, session in
                        try await client.deleteStorageObject(
                            path: record.storagePath,
                            idToken: session.idToken
                        )
                    }
                } catch {
                    errorMessage = "항목은 삭제했지만 첨부 파일을 정리하지 못했습니다."
                }
            }
        } catch {
            handle(error)
        }
    }

    func removeDevice(_ device: DeviceRecord) async {
        guard !isLoading else { return }
        guard device.id != currentDeviceID else {
            errorMessage = "현재 사용 중인 기기는 여기에서 삭제할 수 없습니다."
            return
        }

        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            try await withAuthenticatedSession { client, session in
                try await client.revokeDeviceSession(
                    userId: session.user.id,
                    deviceId: device.id,
                    idToken: session.idToken
                )
            }
            devices.removeAll { $0.id == device.id }
        } catch {
            handle(error)
        }
    }

    func refresh() async {
        guard authPhase == .authenticated else { return }
        guard !isLoading else { return }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        await refreshContent(reportErrors: true)
    }

    func signOut() {
        let session = authSession
        let client = firebaseClient
        let deviceID = currentDeviceID

        stopAuthenticatedServices()
        refreshTask?.cancel()
        refreshTask = nil
        authSession = nil
        currentUser = nil
        clipboardItems = []
        devices = []
        selectedItem = nil
        failedTransfers = []
        retryableTransfers = [:]
        selectedRoute = .home
        transferState = .idle
        syncStatus = .stopped
        errorMessage = nil
        authPhase = .signedOut
        Self.clearReceivedCache()

        do {
            try sessionStore.delete()
        } catch {
            errorMessage = Self.message(for: error)
        }

        if let session, let client {
            Task {
                try? await client.markDeviceOffline(
                    userId: session.user.id,
                    deviceId: deviceID,
                    idToken: session.idToken
                )
            }
        }
    }

    func copy(_ record: ClipboardRecord) {
        errorMessage = nil
        if record.kind == .text {
            guard clipboardMonitor.writeRemotePayload(.text(record.content)) else {
                errorMessage = "클립보드에 복사하지 못했습니다."
                return
            }
            markReadIfNeeded(record)
            return
        }

        Task { [weak self] in
            guard let self else { return }
            await self.copyBinaryRecord(record)
        }
    }

    func open(_ record: ClipboardRecord) {
        errorMessage = nil

        if record.kind == .text {
            let value = record.content.trimmingCharacters(in: .whitespacesAndNewlines)
            if let url = URL(string: value),
               let scheme = url.scheme?.lowercased(),
               scheme == "http" || scheme == "https" {
                NSWorkspace.shared.open(url)
            } else {
                copy(record)
            }
            return
        }

        Task { [weak self] in
            guard let self else { return }
            await self.openBinaryRecord(record)
        }
    }

    func revealInDownloads(_ record: ClipboardRecord) {
        guard record.kind != .text else { return }
        errorMessage = nil
        Task { [weak self] in
            guard let self else { return }
            await self.revealBinaryRecordInDownloads(record)
        }
    }

    func imagePreview(for record: ClipboardRecord) async -> NSImage? {
        guard record.kind == .image, !record.storagePath.isEmpty else { return nil }
        do {
            return NSImage(data: try await download(record))
        } catch {
            return nil
        }
    }

    func dismissError() {
        errorMessage = nil
    }

    // MARK: - Authentication lifecycle

    private func reloadConfiguration() async {
        do {
            let configuration = try AppConfiguration.load()
            self.configuration = configuration
            firebaseClient = FirebaseRESTClient(configuration: configuration)
            authPhase = .signedOut
            errorMessage = nil
            await restoreSession()
        } catch {
            configuration = nil
            firebaseClient = nil
            authPhase = .configurationRequired([Self.message(for: error)])
        }
    }

    private func restoreSession() async {
        let storedSession: AuthSession
        do {
            guard let value = try sessionStore.load() else {
                authPhase = .signedOut
                return
            }
            storedSession = value
        } catch {
            try? sessionStore.delete()
            authPhase = .signedOut
            errorMessage = Self.message(for: error)
            return
        }

        authSession = storedSession
        currentUser = storedSession.user
        authPhase = .authenticated
        startAuthenticatedServices()

        if storedSession.isExpired() {
            do {
                _ = try await ensureSession(forceRefresh: true)
            } catch FirebaseRESTClientError.invalidSession {
                invalidateSession(message: nil)
                return
            } catch {
                syncStatus = .offline
                errorMessage = Self.message(for: error)
                return
            }
        }

        if notificationsEnabled {
            await ensureNotificationAuthorization(reportErrors: false)
        }
        await registerCurrentDevice(reportErrors: false)
        await refreshContent(reportErrors: false)
    }

    private func activate(_ session: AuthSession) throws {
        try sessionStore.save(session)
        authSession = session
        currentUser = session.user
        authPhase = .authenticated
        syncStatus = networkMonitor.isConnected ? .syncing : .offline
        startAuthenticatedServices()
    }

    private func finishAuthentication(isNewUser: Bool) async {
        do {
            let user: AppUser = try await withAuthenticatedSession { client, session in
                let existingUser = isNewUser
                    ? nil
                    : try await client.fetchUser(
                        userId: session.user.id,
                        idToken: session.idToken
                    )
                let shouldCreate: Bool
                if let _ = existingUser {
                    shouldCreate = false
                } else {
                    shouldCreate = true
                }
                return try await client.upsertUser(
                    session.user,
                    isNew: isNewUser || shouldCreate,
                    idToken: session.idToken
                )
            }
            updateCurrentUser(user)
        } catch {
            handle(error)
        }

        if notificationsEnabled {
            await ensureNotificationAuthorization(reportErrors: false)
        }
        await registerCurrentDevice(reportErrors: true)
        await refreshContent(reportErrors: true)
    }

    private func updateCurrentUser(_ user: AppUser) {
        currentUser = user
        guard var session = authSession else { return }
        session.user = user
        authSession = session
        try? sessionStore.save(session)
    }

    private func invalidateSession(message: String?) {
        stopAuthenticatedServices()
        refreshTask?.cancel()
        refreshTask = nil
        authSession = nil
        currentUser = nil
        clipboardItems = []
        devices = []
        selectedItem = nil
        failedTransfers = []
        retryableTransfers = [:]
        syncStatus = .stopped
        transferState = .idle
        authPhase = .signedOut
        try? sessionStore.delete()
        errorMessage = message
    }

    // MARK: - Background synchronization

    private func startAuthenticatedServices() {
        updateClipboardMonitoring()

        pollingTask?.cancel()
        pollingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.pollingNanoseconds)
                guard !Task.isCancelled, let self else { return }
                await self.pollForRemoteChanges()
            }
        }

        heartbeatTask?.cancel()
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.heartbeatNanoseconds)
                guard !Task.isCancelled, let self else { return }
                await self.sendHeartbeat()
            }
        }
    }

    private func stopAuthenticatedServices() {
        pollingTask?.cancel()
        pollingTask = nil
        heartbeatTask?.cancel()
        heartbeatTask = nil
        clipboardMonitor.stop()
    }

    private func updateClipboardMonitoring() {
        guard authPhase == .authenticated, autoSync else {
            clipboardMonitor.stop()
            return
        }
        guard !clipboardMonitor.isMonitoring else { return }

        clipboardMonitor.start { [weak self] payload in
            Task { @MainActor [weak self] in
                await self?.handleLocalClipboardChange(payload)
            }
        }
    }

    private func refreshContent(reportErrors: Bool) async {
        guard authPhase == .authenticated else { return }
        guard !refreshIsRunning else { return }
        guard networkMonitor.isConnected else {
            syncStatus = .offline
            return
        }

        refreshIsRunning = true
        syncStatus = .syncing
        defer { refreshIsRunning = false }

        do {
            let result: ([ClipboardRecord], [DeviceRecord]) = try await withAuthenticatedSession {
                client,
                session in
                async let clipboard = client.listClipboard(
                    userId: session.user.id,
                    limit: 100,
                    idToken: session.idToken
                )
                async let devices = client.listDevices(
                    userId: session.user.id,
                    idToken: session.idToken
                )
                return try await (clipboard, devices)
            }

            clipboardItems = Array(
                result.0
                    .filter { !$0.isExpired }
                    .sorted(by: Self.newestFirst)
                    .prefix(1)
            )
            updateDevices(result.1)
            if let selectedID = selectedItem?.id {
                selectedItem = clipboardItems.first { $0.id == selectedID }
            }

            if autoSync {
                await receiveUnreadItems()
            }
            syncStatus = .upToDate
        } catch {
            syncStatus = networkMonitor.isConnected ? .failed : .offline
            if reportErrors {
                handle(error)
            } else if case FirebaseRESTClientError.invalidSession = error {
                invalidateSession(message: "로그인 정보가 만료되었습니다. 다시 로그인해 주세요.")
            }
        }
    }

    private func registerCurrentDevice(reportErrors: Bool) async {
        guard networkMonitor.isConnected else {
            syncStatus = .offline
            return
        }

        let device = DeviceRecord(
            id: currentDeviceID,
            platform: DevicePlatform.macOS.rawValue,
            deviceName: resolvedCurrentDeviceName,
            isOnline: true
        )
        do {
            let registered: DeviceRecord = try await withAuthenticatedSession { client, session in
                if try await client.isDeviceSessionRevoked(
                    userId: session.user.id,
                    deviceId: currentDeviceID,
                    idToken: session.idToken
                ) {
                    throw AppModelError.deviceSessionRevoked
                }
                return try await client.registerDevice(
                    userId: session.user.id,
                    device: device,
                    idToken: session.idToken
                )
            }
            insertOrReplace(registered)
        } catch AppModelError.deviceSessionRevoked {
            invalidateSession(message: AppModelError.deviceSessionRevoked.localizedDescription)
        } catch {
            if reportErrors {
                handle(error)
            }
        }
    }

    private func sendHeartbeat() async {
        guard authPhase == .authenticated, networkMonitor.isConnected else { return }
        let deviceID = currentDeviceID
        do {
            let refreshedDevices: [DeviceRecord] = try await withAuthenticatedSession { client, session in
                if try await client.isDeviceSessionRevoked(
                    userId: session.user.id,
                    deviceId: deviceID,
                    idToken: session.idToken
                ) {
                    throw AppModelError.deviceSessionRevoked
                }
                try await client.heartbeatDevice(
                    userId: session.user.id,
                    deviceId: deviceID,
                    idToken: session.idToken
                )
                return try await client.listDevices(
                    userId: session.user.id,
                    idToken: session.idToken
                )
            }
            updateDevices(refreshedDevices)
        } catch AppModelError.deviceSessionRevoked {
            invalidateSession(message: AppModelError.deviceSessionRevoked.localizedDescription)
        } catch FirebaseRESTClientError.invalidSession {
            invalidateSession(message: "로그인 정보가 만료되었습니다. 다시 로그인해 주세요.")
        } catch {
            syncStatus = networkMonitor.isConnected ? .failed : .offline
        }
    }

    private func pollForRemoteChanges() async {
        guard authPhase == .authenticated,
              !refreshIsRunning,
              networkMonitor.isConnected else { return }

        do {
            let latest: [ClipboardRecord] = try await withAuthenticatedSession { client, session in
                try await client.listClipboard(
                    userId: session.user.id,
                    limit: 1,
                    idToken: session.idToken
                )
            }
            let remoteHeadID = latest.first(where: { !$0.isExpired })?.id
            let localHeadID = clipboardItems.first?.id
            if remoteHeadID != localHeadID {
                await refreshContent(reportErrors: false)
            } else if autoSync, pendingIncomingCount > 0 {
                await receiveUnreadItems()
            }
        } catch FirebaseRESTClientError.invalidSession {
            invalidateSession(message: "로그인 정보가 만료되었습니다. 다시 로그인해 주세요.")
        } catch {
            syncStatus = networkMonitor.isConnected ? .failed : .offline
        }
    }

    private func networkConnectionDidChange(_ isConnected: Bool) {
        guard authPhase == .authenticated else { return }
        if isConnected {
            Task { [weak self] in
                await self?.registerCurrentDevice(reportErrors: false)
                await self?.refreshContent(reportErrors: false)
            }
        } else {
            syncStatus = .offline
        }
    }

    // MARK: - Clipboard sending

    private func handleLocalClipboardChange(_ payload: ClipboardPayload) async {
        guard authPhase == .authenticated,
              autoSync,
              let observedUserID = authSession?.user.id else { return }
        let observationSequence = beginAutomaticObservation(for: observedUserID)
        switch payload {
        case let .text(text):
            guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            guard text.count <= Self.maximumTextLength else {
                errorMessage = "클립보드 텍스트가 10만 자를 넘어 자동 전송하지 않았습니다."
                return
            }
            await performAutomaticSend(
                fingerprint: payload.fingerprint,
                observedUserID: observedUserID,
                observationSequence: observationSequence
            ) { context in
                try await self.sendTextInBackground(
                    text,
                    itemID: context.recordID,
                    userID: context.userID
                )
            }

        case let .image(data, format):
            do {
                let normalized = try Self.normalizedImageData(data, format: format)
                let fingerprint = await Task.detached(priority: .utility) {
                    Self.automaticFingerprint(namespace: "image", components: [normalized])
                }.value
                let fileName = "clipboard_image"
                await performAutomaticSend(
                    fingerprint: fingerprint,
                    observedUserID: observedUserID,
                    observationSequence: observationSequence,
                    updatesTransferState: true
                ) { context in
                    try await self.sendBinaryData(
                        normalized,
                        fileName: fileName,
                        mimeType: "image/png",
                        kind: .image,
                        targetDeviceID: nil,
                        automaticContext: context
                    )
                }
            } catch {
                if authSession?.user.id == observedUserID {
                    transferState = .failed
                    handle(error)
                }
            }

        case let .file(url):
            do {
                let prepared = try await prepareFile(url)
                let fingerprint = await Task.detached(priority: .utility) {
                    Self.automaticFingerprint(
                        namespace: "file",
                        components: [
                            Data(prepared.kind.rawValue.utf8),
                            Data(prepared.fileName.utf8),
                            Data(prepared.mimeType.utf8),
                            prepared.data
                        ]
                    )
                }.value
                await performAutomaticSend(
                    fingerprint: fingerprint,
                    observedUserID: observedUserID,
                    observationSequence: observationSequence,
                    updatesTransferState: true
                ) { context in
                    try await self.sendBinaryData(
                        prepared.data,
                        fileName: prepared.fileName,
                        mimeType: prepared.mimeType,
                        kind: prepared.kind,
                        targetDeviceID: nil,
                        automaticContext: context
                    )
                }
            } catch {
                if authSession?.user.id == observedUserID {
                    transferState = .failed
                    handle(error)
                }
            }
        }
    }

    private func sendTextInBackground(
        _ text: String,
        itemID: String,
        userID: String
    ) async throws -> ClipboardRecord {
        if let existing = try await fetchClipboardRecord(itemID: itemID, userID: userID) {
            try validateAutomaticTextRecord(existing, text: text)
            return existing
        }

        let record = ClipboardRecord(
            id: itemID,
            kind: .text,
            content: text,
            sourceDeviceId: currentDeviceID,
            expiresAt: Date().addingTimeInterval(Self.clipboardTTL),
            readBy: []
        )

        do {
            return try await withAuthenticatedSession(forUserID: userID) { client, session in
                try await client.createClipboard(
                    userId: session.user.id,
                    record: record,
                    idToken: session.idToken,
                    createOnly: true
                )
            }
        } catch FirebaseRESTClientError.alreadyExists {
            guard let existing = try await fetchClipboardRecord(itemID: itemID, userID: userID) else {
                throw FirebaseRESTClientError.invalidServerResponse
            }
            try validateAutomaticTextRecord(existing, text: text)
            return existing
        }
    }

    private func sendFile(
        _ url: URL,
        targetDeviceID: String?,
        retention: ClipboardRetention
    ) async throws {
        try ensureTransferNetworkAvailable()
        transferState = .preparing
        let prepared = try await prepareFile(url)
        _ = try await sendBinaryData(
            prepared.data,
            fileName: prepared.fileName,
            mimeType: prepared.mimeType,
            kind: prepared.kind,
            targetDeviceID: targetDeviceID,
            retention: retention
        )
    }

    private func prepareFile(_ url: URL) async throws -> PreparedFile {
        guard url.isFileURL else {
            throw AppModelError.invalidFile
        }

        let didAccess = url.startAccessingSecurityScopedResource()
        defer {
            if didAccess {
                url.stopAccessingSecurityScopedResource()
            }
        }

        let values = try url.resourceValues(forKeys: [
            .isRegularFileKey,
            .isDirectoryKey,
            .fileSizeKey,
            .nameKey,
            .contentTypeKey
        ])
        guard values.isRegularFile == true, values.isDirectory != true else {
            throw AppModelError.invalidFile
        }

        let knownSize = Int64(values.fileSize ?? 0)
        guard knownSize <= Self.maximumTransferBytes else {
            throw AppModelError.fileTooLarge
        }

        let data = try await Task.detached(priority: .userInitiated) {
            try Data(contentsOf: url, options: [.mappedIfSafe])
        }.value
        guard !data.isEmpty else {
            throw AppModelError.emptyFile
        }
        guard Int64(data.count) <= Self.maximumTransferBytes else {
            throw AppModelError.fileTooLarge
        }

        let fileName = try Self.normalizedUploadFileName(values.name ?? url.lastPathComponent)
        let contentType = values.contentType ?? UTType(filenameExtension: url.pathExtension)
        let mimeType = contentType?.preferredMIMEType ?? "application/octet-stream"
        let kind: ClipboardKind = contentType?.conforms(to: .image) == true ? .image : .file
        return PreparedFile(
            data: data,
            fileName: fileName,
            mimeType: mimeType,
            kind: kind
        )
    }

    private func sendBinaryData(
        _ data: Data,
        fileName: String,
        mimeType: String,
        kind: ClipboardKind,
        targetDeviceID: String?,
        retention: ClipboardRetention = .oneDay,
        automaticContext: AutomaticSendContext? = nil
    ) async throws -> ClipboardRecord {
        try ensureTransferNetworkAvailable()
        guard !data.isEmpty else { throw AppModelError.emptyFile }
        guard Int64(data.count) <= Self.maximumTransferBytes else {
            throw AppModelError.fileTooLarge
        }
        guard automaticContext != nil || Int64(data.count) <= Self.maximumStorageBytes else {
            throw AppModelError.storageLimitReached
        }

        let previousRecords = clipboardItems
        let itemID = automaticContext?.recordID ?? UUID().uuidString.lowercased()
        if let automaticContext,
           let existing = try await fetchClipboardRecord(
               itemID: itemID,
               userID: automaticContext.userID
           ) {
            try await validateAutomaticBinaryRecord(
                existing,
                data: data,
                fileName: fileName,
                mimeType: mimeType,
                kind: kind,
                userID: automaticContext.userID
            )
            return existing
        }

        if let automaticContext,
           authSession?.user.id != automaticContext.userID {
            throw CancellationError()
        }
        transferState = .uploading
        let upload: StorageUploadResult
        do {
            upload = try await withAuthenticatedSession(
                forUserID: automaticContext?.userID
            ) { client, session in
                try await client.uploadClipboardData(
                    userId: session.user.id,
                    itemId: itemID,
                    data: data,
                    fileName: fileName,
                    mimeType: mimeType,
                    idToken: session.idToken
                )
            }
        } catch let uploadError {
            guard let automaticContext else { throw uploadError }
            guard let recovered = try await findUploadedClipboardData(
                itemID: itemID,
                fileName: fileName,
                userID: automaticContext.userID
            ) else {
                throw uploadError
            }
            try await validateRecoveredAutomaticUpload(
                recovered,
                data: data,
                fileName: fileName,
                mimeType: mimeType,
                userID: automaticContext.userID
            )
            upload = recovered
        }

        let record = ClipboardRecord(
            id: itemID,
            kind: kind,
            storagePath: upload.storagePath,
            fileName: upload.fileName,
            fileSize: upload.fileSize,
            mimeType: upload.mimeType,
            sourceDeviceId: currentDeviceID,
            targetDeviceId: targetDeviceID ?? "",
            expiresAt: Date().addingTimeInterval(
                automaticContext == nil ? retention.duration : Self.clipboardTTL,
            ),
            readBy: []
        )

        do {
            let created: ClipboardRecord = try await withAuthenticatedSession(
                forUserID: automaticContext?.userID
            ) { client, session in
                try await client.createClipboard(
                    userId: session.user.id,
                    record: record,
                    idToken: session.idToken,
                    createOnly: automaticContext != nil
                )
            }
            if automaticContext == nil || authSession?.user.id == automaticContext?.userID {
                await removeReplacedStorageObjects(previousRecords, keeping: created.storagePath)
                insertOrReplace(created)
            }
            return created
        } catch FirebaseRESTClientError.alreadyExists where automaticContext != nil {
            guard let automaticContext,
                  let existing = try await fetchClipboardRecord(
                      itemID: itemID,
                      userID: automaticContext.userID
                  ) else {
                throw FirebaseRESTClientError.invalidServerResponse
            }
            try await validateAutomaticBinaryRecord(
                existing,
                data: data,
                fileName: fileName,
                mimeType: mimeType,
                kind: kind,
                userID: automaticContext.userID
            )
            if authSession?.user.id == automaticContext.userID {
                insertOrReplace(existing)
            }
            return existing
        } catch {
            if automaticContext == nil {
                try? await withAuthenticatedSession { client, session in
                    try await client.deleteStorageObject(
                        path: upload.storagePath,
                        idToken: session.idToken
                    )
                }
            }
            throw error
        }
    }

    private func performAutomaticSend(
        fingerprint: String,
        observedUserID: String,
        observationSequence: UInt64,
        updatesTransferState: Bool = false,
        operation: (AutomaticSendContext) async throws -> ClipboardRecord
    ) async {
        registerAutomaticFingerprint(
            fingerprint,
            for: observedUserID,
            observationSequence: observationSequence
        )
        guard let context = beginAutomaticSend(
            fingerprint: fingerprint,
            observedUserID: observedUserID,
            observationSequence: observationSequence
        ) else { return }
        defer {
            automaticSendKeysInFlight.remove(context.inFlightKey)
        }

        if updatesTransferState {
            transferState = .preparing
        }

        do {
            let record = try await operation(context)
            if latestAutomaticObservationByUser[context.userID]?.fingerprint == context.fingerprint {
                persistAutomaticFingerprint(context.fingerprint, for: context.userID)
            }
            if authSession?.user.id == context.userID {
                if !record.isExpired {
                    insertOrReplace(record)
                }
                syncStatus = .upToDate
                if updatesTransferState {
                    transferState = .succeeded
                }
            }
        } catch {
            if authSession?.user.id == context.userID {
                if updatesTransferState {
                    transferState = .failed
                }
                handle(error)
            }
        }
    }

    private func beginAutomaticSend(
        fingerprint: String,
        observedUserID: String,
        observationSequence: UInt64
    ) -> AutomaticSendContext? {
        guard authSession?.user.id == observedUserID, !observedUserID.isEmpty else { return nil }
        let userID = observedUserID
        guard storedAutomaticFingerprint(for: userID) != fingerprint else { return nil }

        let inFlightKey = "\(userID)\u{0}\(fingerprint)"
        guard automaticSendKeysInFlight.insert(inFlightKey).inserted else { return nil }
        return AutomaticSendContext(
            userID: userID,
            fingerprint: fingerprint,
            inFlightKey: inFlightKey,
            observationSequence: observationSequence
        )
    }

    private func beginAutomaticObservation(for userID: String) -> UInt64 {
        let sequence = (automaticObservationSequenceByUser[userID] ?? 0) &+ 1
        automaticObservationSequenceByUser[userID] = sequence
        latestAutomaticObservationByUser[userID] = AutomaticObservation(
            sequence: sequence,
            fingerprint: nil
        )
        return sequence
    }

    private func registerAutomaticFingerprint(
        _ fingerprint: String,
        for userID: String,
        observationSequence: UInt64
    ) {
        guard var observation = latestAutomaticObservationByUser[userID],
              observation.sequence == observationSequence else {
            return
        }
        observation.fingerprint = fingerprint
        latestAutomaticObservationByUser[userID] = observation
    }

    private func storedAutomaticFingerprint(for userID: String) -> String? {
        defaults.dictionary(forKey: PreferenceKey.automaticFingerprintsByUser)?[userID] as? String
    }

    private func persistAutomaticFingerprint(_ fingerprint: String, for userID: String) {
        var values = defaults.dictionary(forKey: PreferenceKey.automaticFingerprintsByUser) ?? [:]
        values[userID] = fingerprint
        defaults.set(values, forKey: PreferenceKey.automaticFingerprintsByUser)
    }

    private func fetchClipboardRecord(
        itemID: String,
        userID: String
    ) async throws -> ClipboardRecord? {
        try await withAuthenticatedSession(forUserID: userID) { client, session in
            try await client.fetchClipboard(
                userId: session.user.id,
                itemId: itemID,
                idToken: session.idToken
            )
        }
    }

    private func findUploadedClipboardData(
        itemID: String,
        fileName: String,
        userID: String
    ) async throws -> StorageUploadResult? {
        try await withAuthenticatedSession(forUserID: userID) { client, session in
            try await client.findUploadedClipboardData(
                userId: session.user.id,
                itemId: itemID,
                fileName: fileName,
                idToken: session.idToken
            )
        }
    }

    private func validateRecoveredAutomaticUpload(
        _ upload: StorageUploadResult,
        data: Data,
        fileName: String,
        mimeType: String,
        userID: String
    ) async throws {
        guard upload.fileName == fileName,
              upload.fileSize == Int64(data.count),
              upload.mimeType == mimeType else {
            throw AppModelError.automaticRecordConflict
        }

        let storedData: Data = try await withAuthenticatedSession(
            forUserID: userID
        ) { client, session in
            try await client.downloadStorageObject(
                path: upload.storagePath,
                maxBytes: Self.maximumTransferBytes,
                idToken: session.idToken
            )
        }
        guard storedData == data else {
            throw AppModelError.automaticRecordConflict
        }
    }

    private func validateAutomaticTextRecord(
        _ record: ClipboardRecord,
        text: String
    ) throws {
        guard record.kind == .text,
              record.content == text,
              record.storagePath.isEmpty,
              record.fileName.isEmpty,
              record.fileSize == 0,
              record.mimeType.isEmpty else {
            throw AppModelError.automaticRecordConflict
        }
    }

    private func validateAutomaticBinaryRecord(
        _ record: ClipboardRecord,
        data: Data,
        fileName: String,
        mimeType: String,
        kind: ClipboardKind,
        userID: String
    ) async throws {
        guard record.kind == kind,
              record.content.isEmpty,
              record.fileName == fileName,
              record.fileSize == Int64(data.count),
              record.mimeType == mimeType,
              !record.storagePath.isEmpty else {
            throw AppModelError.automaticRecordConflict
        }

        let storedData = try await download(record, userID: userID)
        guard storedData == data else {
            throw AppModelError.automaticRecordConflict
        }
    }

    // MARK: - Clipboard receiving

    private func receiveUnreadItems() async {
        let incoming = clipboardItems
            .filter(isPendingIncomingRecord)
            .sorted(by: Self.oldestFirst)

        for record in incoming {
            guard autoSync, authPhase == .authenticated else { return }
            do {
                transferState = record.kind == .text ? .preparing : .downloading
                let payload = try await payload(for: record)
                guard clipboardMonitor.writeRemotePayload(payload) else {
                    throw AppModelError.clipboardWriteFailed
                }

                if notificationsEnabled {
                    let sourceName = devices.first { $0.id == record.sourceDeviceId }?.deviceName
                    _ = try? await notificationService.sendReceivedNotification(
                        for: payload,
                        sourceDeviceName: sourceName
                    )
                }
                try await markReceived(record)
                transferState = .succeeded
            } catch FirebaseRESTClientError.invalidSession {
                invalidateSession(message: "로그인 정보가 만료되었습니다. 다시 로그인해 주세요.")
                return
            } catch {
                transferState = .failed
                errorMessage = Self.message(for: error)
            }
        }
    }

    private func payload(for record: ClipboardRecord) async throws -> ClipboardPayload {
        switch record.kind {
        case .text:
            return .text(record.content)

        case .image:
            let data = try await download(record)
            let png = try Self.normalizedRemoteImageData(data)
            return .image(data: png, format: .png)

        case .file:
            let data = try await download(record)
            let url = try cache(data: data, record: record)
            return .file(url)
        }
    }

    private func download(
        _ record: ClipboardRecord,
        userID: String? = nil
    ) async throws -> Data {
        try ensureTransferNetworkAvailable()
        guard !record.storagePath.isEmpty else { throw AppModelError.missingStoragePath }
        let path = record.storagePath
        return try await withAuthenticatedSession(forUserID: userID) { client, session in
            try await client.downloadStorageObject(
                path: path,
                maxBytes: Self.maximumTransferBytes,
                idToken: session.idToken
            )
        }
    }

    private func markRead(_ record: ClipboardRecord) async throws {
        let itemID = record.id
        let deviceID = currentDeviceID
        try await withAuthenticatedSession { client, session in
            try await client.markClipboardRead(
                userId: session.user.id,
                itemId: itemID,
                deviceId: deviceID,
                idToken: session.idToken
            )
        }

        guard let index = clipboardItems.firstIndex(where: { $0.id == itemID }) else { return }
        if !clipboardItems[index].readBy.contains(deviceID) {
            clipboardItems[index].readBy.append(deviceID)
        }
        if selectedItem?.id == itemID {
            selectedItem = clipboardItems[index]
        }
    }

    func markReadFromUser(_ record: ClipboardRecord) {
        guard record.sourceDeviceId != currentDeviceID, !record.isRead(by: currentDeviceID) else { return }
        Task { [weak self] in
            do {
                try await self?.markRead(record)
            } catch {
                self?.handle(error)
            }
        }
    }

    private func markReceived(_ record: ClipboardRecord) async throws {
        let itemID = record.id
        let deviceID = currentDeviceID
        try await withAuthenticatedSession { client, session in
            try await client.markClipboardReceived(
                userId: session.user.id,
                itemId: itemID,
                deviceId: deviceID,
                idToken: session.idToken
            )
        }

        guard let index = clipboardItems.firstIndex(where: { $0.id == itemID }) else { return }
        if !clipboardItems[index].receivedBy.contains(deviceID) {
            clipboardItems[index].receivedBy.append(deviceID)
        }
        if selectedItem?.id == itemID {
            selectedItem = clipboardItems[index]
        }
    }

    private func markReadIfNeeded(_ record: ClipboardRecord) {
        guard isUnreadIncomingRecord(record) else { return }
        Task { [weak self] in
            do {
                try await self?.markRead(record)
            } catch {
                self?.handle(error)
            }
        }
    }

    private func copyBinaryRecord(_ record: ClipboardRecord) async {
        transferState = .downloading
        do {
            let payload = try await payload(for: record)
            guard clipboardMonitor.writeRemotePayload(payload) else {
                throw AppModelError.clipboardWriteFailed
            }
            if isUnreadIncomingRecord(record) {
                try await markRead(record)
            }
            transferState = .succeeded
        } catch {
            transferState = .failed
            handle(error)
        }
    }

    private func openBinaryRecord(_ record: ClipboardRecord) async {
        transferState = .downloading
        do {
            let data = try await download(record)
            let url = try cache(data: data, record: record)
            guard NSWorkspace.shared.open(url) else {
                throw AppModelError.fileOpenFailed
            }
            if isUnreadIncomingRecord(record) {
                try await markRead(record)
            }
            transferState = .succeeded
        } catch {
            transferState = .failed
            handle(error)
        }
    }

    private func revealBinaryRecordInDownloads(_ record: ClipboardRecord) async {
        transferState = .downloading
        do {
            let data = try await download(record)
            let fileURL = try saveToDownloads(data: data, record: record)
            NSWorkspace.shared.activateFileViewerSelecting([fileURL])
            if isUnreadIncomingRecord(record) {
                try await markRead(record)
            }
            transferState = .succeeded
        } catch {
            transferState = .failed
            handle(error)
        }
    }

    private func saveToDownloads(data: Data, record: ClipboardRecord) throws -> URL {
        let fileManager = FileManager.default
        guard let downloadsDirectory = fileManager.urls(
            for: .downloadsDirectory,
            in: .userDomainMask
        ).first else {
            throw AppModelError.cacheUnavailable
        }

        let directory = downloadsDirectory.appendingPathComponent("AnyPaste", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)

        let rawName = record.fileName.isEmpty ? "\(record.id).bin" : record.fileName
        let safeName = rawName
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "\\", with: "_")
        let destination = directory.appendingPathComponent(
            "\(record.id.prefix(8))-\(safeName)",
            isDirectory: false
        )
        try data.write(to: destination, options: .atomic)
        return destination
    }

    private func cache(data: Data, record: ClipboardRecord) throws -> URL {
        let fileManager = FileManager.default
        guard let applicationSupport = fileManager.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first else {
            throw AppModelError.cacheUnavailable
        }

        let directory = applicationSupport
            .appendingPathComponent("AnyPaste", isDirectory: true)
            .appendingPathComponent("Received", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)

        let rawName = record.fileName.isEmpty ? "\(record.id).bin" : record.fileName
        let safeName = rawName
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "\\", with: "_")
        let fileName = "\(record.id.prefix(8))-\(safeName)"
        let destination = directory.appendingPathComponent(fileName, isDirectory: false)
        try data.write(to: destination, options: .atomic)
        return destination
    }

    private func removeReplacedStorageObjects(
        _ records: [ClipboardRecord],
        keeping storagePath: String
    ) async {
        let obsoletePaths = Set(
            records.map(\.storagePath).filter { !$0.isEmpty && $0 != storagePath }
        )
        for path in obsoletePaths {
            do {
                try await withAuthenticatedSession { client, session in
                    try await client.deleteStorageObject(path: path, idToken: session.idToken)
                }
            } catch {
                // The new record is already available. A failed cleanup must not roll it back.
            }
        }
    }

    // MARK: - Session helpers

    private func withAuthenticatedSession<Value: Sendable>(
        forUserID expectedUserID: String?,
        _ operation: @Sendable (FirebaseRESTClient, AuthSession) async throws -> Value
    ) async throws -> Value {
        try await withAuthenticatedSession { client, session in
            if let expectedUserID, session.user.id != expectedUserID {
                throw CancellationError()
            }
            return try await operation(client, session)
        }
    }

    private func withAuthenticatedSession<Value: Sendable>(
        _ operation: @Sendable (FirebaseRESTClient, AuthSession) async throws -> Value
    ) async throws -> Value {
        guard networkMonitor.isConnected else {
            throw FirebaseRESTClientError.networkUnavailable
        }
        guard let client = firebaseClient else {
            throw AppModelError.configurationMissing
        }

        let session = try await ensureSession(forceRefresh: false)
        do {
            return try await operation(client, session)
        } catch FirebaseRESTClientError.invalidSession {
            let refreshed = try await ensureSession(forceRefresh: true)
            return try await operation(client, refreshed)
        }
    }

    private func ensureSession(forceRefresh: Bool) async throws -> AuthSession {
        guard let current = authSession, let client = firebaseClient else {
            throw FirebaseRESTClientError.invalidSession
        }
        guard forceRefresh || current.isExpired() else { return current }

        if let refreshTask {
            return try await refreshTask.value
        }

        let task = Task<AuthSession, Error> {
            try await client.refresh(session: current)
        }
        refreshTask = task
        do {
            let refreshed = try await task.value
            refreshTask = nil
            authSession = refreshed
            currentUser = refreshed.user
            try sessionStore.save(refreshed)
            return refreshed
        } catch {
            refreshTask = nil
            throw error
        }
    }

    // MARK: - Settings and errors

    private func ensureNotificationAuthorization(reportErrors: Bool) async {
        do {
            let allowed = try await notificationService.requestAuthorization()
            if !allowed {
                notificationsEnabled = false
                if reportErrors {
                    errorMessage = "알림 권한이 꺼져 있습니다. 시스템 설정에서 권한을 허용해 주세요."
                }
            }
        } catch {
            notificationsEnabled = false
            if reportErrors {
                errorMessage = Self.message(for: error)
            }
        }
    }

    private func ensureTransferNetworkAvailable() throws {
        guard networkMonitor.isConnected else {
            throw FirebaseRESTClientError.networkUnavailable
        }
        if wifiOnlyTransfers, !networkMonitor.allowsLargeTransfers {
            throw AppModelError.wifiRequired
        }
    }

    private func recordFailedTransfer(
        id: UUID,
        transfer: RetryableTransfer,
        error: Error
    ) {
        guard !(error is CancellationError) else { return }
        retryableTransfers[id] = transfer
        let record = FailedTransferRecord(
            id: id,
            title: transfer.title,
            meta: transfer.meta,
            reason: Self.message(for: error)
        )
        failedTransfers.removeAll { $0.id == id }
        failedTransfers.append(record)
        if failedTransfers.count > Self.maximumFailedTransfers {
            let expired = failedTransfers.removeFirst()
            retryableTransfers.removeValue(forKey: expired.id)
        }
    }

    private func resolveFailedTransfer(_ id: UUID) {
        retryableTransfers.removeValue(forKey: id)
        failedTransfers.removeAll { $0.id == id }
    }

    private func validateTargetDevice(_ deviceID: String?) -> Bool {
        guard let deviceID, !deviceID.isEmpty else { return true }
        guard deviceID != currentDeviceID else {
            errorMessage = "현재 기기에는 직접 전송할 수 없습니다."
            return false
        }
        guard devices.contains(where: { $0.id == deviceID }) else {
            errorMessage = "선택한 기기를 찾을 수 없습니다. 목록을 새로고침해 주세요."
            return false
        }
        return true
    }

    private func showConfigurationError() {
        authPhase = .configurationRequired(["서비스 연결 설정을 확인해 주세요."])
        errorMessage = "서비스 연결 설정이 필요합니다."
    }

    private func handle(_ error: Error, invalidatesExpiredSession: Bool = true) {
        if error is CancellationError { return }
        if invalidatesExpiredSession,
           case FirebaseRESTClientError.invalidSession = error {
            invalidateSession(message: "로그인 정보가 만료되었습니다. 다시 로그인해 주세요.")
            return
        }
        if case .some(.deviceSessionRevoked) = error as? AppModelError {
            invalidateSession(message: AppModelError.deviceSessionRevoked.localizedDescription)
            return
        }
        errorMessage = Self.message(for: error)
        if case FirebaseRESTClientError.networkUnavailable = error {
            syncStatus = .offline
        }
    }

    private func insertOrReplace(_ record: ClipboardRecord) {
        clipboardItems = [record]
        if selectedItem != nil {
            selectedItem = record
        }
    }

    private func insertOrReplace(_ device: DeviceRecord) {
        devices.removeAll { $0.id == device.id }
        devices.append(device)
        devices.sort {
            ($0.lastSeenAt ?? .distantPast) > ($1.lastSeenAt ?? .distantPast)
        }
    }

    private func updateDevices(_ records: [DeviceRecord]) {
        devices = records.map { device in
            var normalized = device
            normalized.isOnline = device.isRecentlyOnline()
            return normalized
        }
    }

    private func isUnreadIncomingRecord(_ record: ClipboardRecord) -> Bool {
        record.sourceDeviceId != currentDeviceID
            && (record.targetDeviceId.isEmpty || record.targetDeviceId == currentDeviceID)
            && !record.isExpired
            && !record.isRead(by: currentDeviceID)
    }

    private func isPendingIncomingRecord(_ record: ClipboardRecord) -> Bool {
        record.sourceDeviceId != currentDeviceID
            && (record.targetDeviceId.isEmpty || record.targetDeviceId == currentDeviceID)
            && !record.isExpired
            && !record.isReceived(by: currentDeviceID)
    }

    private static func newestFirst(_ lhs: ClipboardRecord, _ rhs: ClipboardRecord) -> Bool {
        (lhs.createdAt ?? .distantPast) > (rhs.createdAt ?? .distantPast)
    }

    private static func oldestFirst(_ lhs: ClipboardRecord, _ rhs: ClipboardRecord) -> Bool {
        (lhs.createdAt ?? .distantPast) < (rhs.createdAt ?? .distantPast)
    }

    private static func normalizedImageData(
        _ data: Data,
        format: ClipboardPayload.ImageFormat
    ) throws -> Data {
        if format == .png, NSBitmapImageRep(data: data) != nil {
            return data
        }
        return try normalizedRemoteImageData(data)
    }

    private static func normalizedRemoteImageData(_ data: Data) throws -> Data {
        guard let image = NSImage(data: data),
              let tiff = image.tiffRepresentation,
              let bitmap = NSBitmapImageRep(data: tiff),
              let png = bitmap.representation(using: .png, properties: [:]) else {
            throw AppModelError.invalidImage
        }
        return png
    }

    nonisolated private static func automaticFingerprint(
        namespace: String,
        components: [Data]
    ) -> String {
        var hasher = SHA256()
        hasher.update(data: Data(namespace.utf8))
        hasher.update(data: Data([0]))
        for component in components {
            hasher.update(data: component)
            hasher.update(data: Data([0]))
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func normalizedUploadFileName(_ rawValue: String) throws -> String {
        let value = String(
            rawValue
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "\\", with: "_")
                .prefix(180)
        )
        guard !value.isEmpty, value != ".", value != ".." else {
            throw AppModelError.invalidFile
        }
        return value
    }

    private static func storedBoolean(
        in defaults: UserDefaults,
        key: String,
        defaultValue: Bool
    ) -> Bool {
        guard defaults.object(forKey: key) != nil else { return defaultValue }
        return defaults.bool(forKey: key)
    }

    private static func resolveDeviceID(defaults: UserDefaults) -> String {
        if let stored = defaults.string(forKey: PreferenceKey.deviceID), !stored.isEmpty {
            return stored
        }
        let generated = UUID().uuidString.lowercased()
        defaults.set(generated, forKey: PreferenceKey.deviceID)
        return generated
    }

    private var resolvedCurrentDeviceName: String {
        let saved = deviceDisplayName.trimmingCharacters(in: .whitespacesAndNewlines)
        return saved.isEmpty ? Self.defaultCurrentDeviceName : saved
    }

    private static var defaultCurrentDeviceName: String {
        let hostName = ProcessInfo.processInfo.hostName
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return hostName.isEmpty ? "Mac" : hostName
    }

    private static func cleanupExpiredCache() {
        guard let directory = receivedCacheDirectory(),
              let files = try? FileManager.default.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: [.contentModificationDateKey, .isRegularFileKey],
                options: [.skipsHiddenFiles]
              ) else { return }

        let cutoff = Date().addingTimeInterval(-clipboardTTL)
        for file in files {
            guard let values = try? file.resourceValues(forKeys: [
                .contentModificationDateKey,
                .isRegularFileKey
            ]),
            values.isRegularFile == true,
            let modifiedAt = values.contentModificationDate,
            modifiedAt < cutoff else { continue }
            try? FileManager.default.removeItem(at: file)
        }
    }

    private static func clearReceivedCache() {
        guard let directory = receivedCacheDirectory() else { return }
        try? FileManager.default.removeItem(at: directory)
    }

    private static func receivedCacheDirectory() -> URL? {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first?
            .appendingPathComponent("AnyPaste", isDirectory: true)
            .appendingPathComponent("Received", isDirectory: true)
    }

    private static func message(for error: Error) -> String {
        if let localized = error as? LocalizedError,
           let description = localized.errorDescription,
           !description.isEmpty {
            return description
        }
        let description = (error as NSError).localizedDescription
        return description.isEmpty ? "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요." : description
    }
}

private enum PreferenceKey {
    static let autoSync = "settings.autoSync"
    static let automaticFingerprintsByUser = "clipboard.automaticFingerprintsByUser"
    static let notificationsEnabled = "settings.notificationsEnabled"
    static let wifiOnlyTransfers = "settings.wifiOnlyTransfers"
    static let deviceID = "device.id"
    static let deviceDisplayName = "device.displayName"
    static let lastTransferTargetDeviceID = "send.lastTargetDeviceID"
}

private enum AppModelError: LocalizedError {
    case configurationMissing
    case invalidFile
    case emptyFile
    case fileTooLarge
    case storageLimitReached
    case invalidImage
    case missingStoragePath
    case clipboardWriteFailed
    case cacheUnavailable
    case fileOpenFailed
    case wifiRequired
    case automaticRecordConflict
    case deviceSessionRevoked

    var errorDescription: String? {
        switch self {
        case .configurationMissing:
            return "서비스 연결 설정이 필요합니다."
        case .invalidFile:
            return "전송할 수 있는 일반 파일을 선택해 주세요."
        case .emptyFile:
            return "빈 파일은 전송할 수 없습니다."
        case .fileTooLarge:
            return "파일은 50MB 이하여야 합니다."
        case .storageLimitReached:
            return "저장 공간 한도를 초과했습니다. 불필요한 파일을 삭제한 뒤 다시 시도해 주세요."
        case .invalidImage:
            return "이미지 데이터를 처리할 수 없습니다."
        case .missingStoragePath:
            return "첨부 파일의 저장 경로가 없습니다."
        case .clipboardWriteFailed:
            return "클립보드에 받은 내용을 기록하지 못했습니다."
        case .cacheUnavailable:
            return "받은 파일을 저장할 위치를 준비하지 못했습니다."
        case .fileOpenFailed:
            return "파일을 열 수 없습니다."
        case .wifiRequired:
            return "Wi-Fi 전용 전송이 켜져 있습니다. Wi-Fi에 연결한 뒤 다시 시도해 주세요."
        case .automaticRecordConflict:
            return "같은 자동 전송 ID에 다른 내용이 있어 전송을 중단했습니다."
        case .deviceSessionRevoked:
            return "이 기기의 연결이 해제되어 로그아웃되었습니다. 다시 사용하려면 새 기기로 연결해 주세요."
        }
    }
}
