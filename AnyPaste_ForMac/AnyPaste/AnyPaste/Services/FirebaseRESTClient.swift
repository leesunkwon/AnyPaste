import Foundation

actor FirebaseRESTClient {
    private let configuration: AppConfiguration
    private let session: URLSession

    private static let defaultClipboardTTL: TimeInterval = 24 * 60 * 60
    private static let maximumTextLength = 100_000
    private static let maximumTransferBytes: Int64 = 50 * 1024 * 1024
    private static let maximumAllowedDownloadBytes: Int64 = 100 * 1024 * 1024
    private static let maximumFileNameLength = 180

    init(configuration: AppConfiguration, session: URLSession = .shared) {
        self.configuration = configuration
        self.session = session
    }

    // MARK: - Authentication

    func signUp(email: String, password: String, displayName: String) async throws -> AuthSession {
        let email = try normalizedEmail(email)
        let password = try validatedPassword(password)
        let displayName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !displayName.isEmpty, displayName.count <= 100 else {
            throw FirebaseRESTClientError.invalidInput("이름을 입력해 주세요.")
        }

        let response: IdentityTokenResponse = try await identityRequest(
            action: "accounts:signUp",
            body: [
                "email": email,
                "password": password,
                "returnSecureToken": true
            ]
        )
        let tokens = try response.validatedTokens()
        _ = try await updateAuthProfile(displayName: displayName, idToken: tokens.idToken)
        let user = try await fetchAuthProfile(idToken: tokens.idToken)
        return AuthSession(
            user: user,
            idToken: tokens.idToken,
            refreshToken: tokens.refreshToken,
            expiresAt: Date().addingTimeInterval(tokens.expiresIn)
        )
    }

    func signIn(email: String, password: String) async throws -> AuthSession {
        let email = try normalizedEmail(email)
        guard !password.isEmpty else {
            throw FirebaseRESTClientError.invalidInput("비밀번호를 입력해 주세요.")
        }

        let response: IdentityTokenResponse = try await identityRequest(
            action: "accounts:signInWithPassword",
            body: [
                "email": email,
                "password": password,
                "returnSecureToken": true
            ]
        )
        let tokens = try response.validatedTokens()
        let user = try await fetchAuthProfile(idToken: tokens.idToken)
        return AuthSession(
            user: user,
            idToken: tokens.idToken,
            refreshToken: tokens.refreshToken,
            expiresAt: Date().addingTimeInterval(tokens.expiresIn)
        )
    }

    func sendPasswordReset(email: String) async throws {
        let email = try normalizedEmail(email)
        let _: EmptyResponse = try await identityRequest(
            action: "accounts:sendOobCode",
            body: [
                "requestType": "PASSWORD_RESET",
                "email": email
            ]
        )
    }

    func refresh(session currentSession: AuthSession) async throws -> AuthSession {
        guard !currentSession.refreshToken.isEmpty else {
            throw FirebaseRESTClientError.invalidSession
        }

        let url = try secureTokenURL()
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = formEncoded([
            "grant_type": "refresh_token",
            "refresh_token": currentSession.refreshToken
        ])

        let data = try await perform(request)
        let response: RefreshTokenResponse = try decode(RefreshTokenResponse.self, from: data)
        guard let expiresIn = TimeInterval(response.expiresIn),
              !response.idToken.isEmpty,
              !response.refreshToken.isEmpty else {
            throw FirebaseRESTClientError.invalidServerResponse
        }

        var user = try await fetchAuthProfile(idToken: response.idToken)
        user.createdAt = currentSession.user.createdAt
        user.lastActiveAt = currentSession.user.lastActiveAt
        return AuthSession(
            user: user,
            idToken: response.idToken,
            refreshToken: response.refreshToken,
            expiresAt: Date().addingTimeInterval(expiresIn)
        )
    }

    func fetchAuthProfile(idToken: String) async throws -> AppUser {
        try requireToken(idToken)
        let response: AccountLookupResponse = try await identityRequest(
            action: "accounts:lookup",
            body: ["idToken": idToken]
        )
        guard let profile = response.users.first else {
            throw FirebaseRESTClientError.invalidSession
        }
        return profile.appUser
    }

    func updateAuthProfile(displayName: String, idToken: String) async throws -> AppUser {
        try requireToken(idToken)
        let displayName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !displayName.isEmpty, displayName.count <= 100 else {
            throw FirebaseRESTClientError.invalidInput("이름을 입력해 주세요.")
        }
        let _: IdentityTokenResponse = try await identityRequest(
            action: "accounts:update",
            body: [
                "idToken": idToken,
                "displayName": displayName,
                "returnSecureToken": true
            ]
        )
        return try await fetchAuthProfile(idToken: idToken)
    }

    // MARK: - User profile

    func upsertUser(_ user: AppUser, isNew: Bool, idToken: String) async throws -> AppUser {
        try requireToken(idToken)
        try requireDocumentID(user.id, label: "사용자 ID")
        guard user.displayName.count <= 100,
              user.email.count <= 320,
              user.photoUrl.count <= 2_048 else {
            throw FirebaseRESTClientError.invalidInput("사용자 프로필 정보가 허용 길이를 초과했습니다.")
        }

        let name = documentName(["users", user.id])
        var fields: FirestoreFields = [
            "displayName": stringValue(user.displayName),
            "photoUrl": stringValue(user.photoUrl)
        ]
        if isNew {
            fields["email"] = stringValue(user.email)
        }
        var transforms = [serverTimestampTransform("lastActiveAt")]
        if isNew {
            transforms.append(serverTimestampTransform("createdAt"))
        }

        let write = updateWrite(
            name: name,
            fields: fields,
            updateMask: Array(fields.keys),
            transforms: transforms
        )
        let commitTime = try await commit(writes: [write], idToken: idToken)

        var updated = user
        updated.lastActiveAt = commitTime
        if isNew {
            updated.createdAt = commitTime
        }
        return updated
    }

    func fetchUser(userId: String, idToken: String) async throws -> AppUser? {
        try requireToken(idToken)
        try requireDocumentID(userId, label: "사용자 ID")

        let request = authorizedRequest(
            url: try firestoreDocumentURL(["users", userId]),
            method: "GET",
            idToken: idToken
        )
        do {
            let data = try await perform(request)
            let document = try jsonObject(from: data)
            return appUser(from: document)
        } catch FirebaseRESTClientError.notFound {
            return nil
        }
    }

    // MARK: - Devices

    func registerDevice(
        userId: String,
        device: DeviceRecord,
        idToken: String
    ) async throws -> DeviceRecord {
        try requireToken(idToken)
        try requireDocumentID(userId, label: "사용자 ID")
        try requireDocumentID(device.id, label: "기기 ID")
        let deviceName = device.deviceName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !deviceName.isEmpty, deviceName.count <= 100 else {
            throw FirebaseRESTClientError.invalidInput("기기 이름을 입력해 주세요.")
        }
        guard DevicePlatform(rawValue: device.platform) != nil else {
            throw FirebaseRESTClientError.invalidInput("기기 플랫폼 형식이 올바르지 않습니다.")
        }

        var fields: FirestoreFields = [
            "platform": stringValue(device.platform),
            "deviceName": stringValue(deviceName),
            "isOnline": booleanValue(true)
        ]
        let fcmToken = device.fcmToken.trimmingCharacters(in: .whitespacesAndNewlines)
        guard fcmToken.count <= 4_096 else {
            throw FirebaseRESTClientError.invalidInput("알림 토큰이 허용 길이를 초과했습니다.")
        }
        if !fcmToken.isEmpty {
            fields["fcmToken"] = stringValue(fcmToken)
        }

        let deviceWrite = updateWrite(
            name: documentName(["users", userId, "devices", device.id]),
            fields: fields,
            updateMask: Array(fields.keys),
            transforms: [serverTimestampTransform("lastSeenAt")]
        )
        let userTransform = transformWrite(
            name: documentName(["users", userId]),
            transforms: [serverTimestampTransform("lastActiveAt")]
        )
        let commitTime = try await commit(
            writes: [deviceWrite, userTransform],
            idToken: idToken
        )

        return DeviceRecord(
            id: device.id,
            platform: device.platform,
            deviceName: deviceName,
            fcmToken: fcmToken,
            lastSeenAt: commitTime,
            isOnline: true
        )
    }

    func listDevices(userId: String, idToken: String) async throws -> [DeviceRecord] {
        try requireToken(idToken)
        try requireDocumentID(userId, label: "사용자 ID")
        let documents = try await listDocuments(
            collection: ["users", userId, "devices"],
            pageSize: 100,
            orderBy: "lastSeenAt desc",
            idToken: idToken
        )
        return documents.compactMap(deviceRecord(from:))
    }

    func isDeviceSessionRevoked(
        userId: String,
        deviceId: String,
        idToken: String
    ) async throws -> Bool {
        try requireToken(idToken)
        try requireDocumentID(userId, label: "사용자 ID")
        try requireDocumentID(deviceId, label: "기기 ID")

        let request = authorizedRequest(
            url: try firestoreDocumentURL(["users", userId, "revokedDevices", deviceId]),
            method: "GET",
            idToken: idToken
        )
        do {
            _ = try await perform(request)
            return true
        } catch FirebaseRESTClientError.notFound {
            return false
        }
    }

    func heartbeatDevice(userId: String, deviceId: String, idToken: String) async throws {
        try requireToken(idToken)
        try requireDocumentID(userId, label: "사용자 ID")
        try requireDocumentID(deviceId, label: "기기 ID")

        let deviceWrite = updateWrite(
            name: documentName(["users", userId, "devices", deviceId]),
            fields: ["isOnline": booleanValue(true)],
            updateMask: ["isOnline"],
            transforms: [serverTimestampTransform("lastSeenAt")]
        )
        let userTransform = transformWrite(
            name: documentName(["users", userId]),
            transforms: [serverTimestampTransform("lastActiveAt")]
        )
        _ = try await commit(writes: [deviceWrite, userTransform], idToken: idToken)
    }

    func updateDeviceToken(
        userId: String,
        deviceId: String,
        fcmToken: String,
        idToken: String
    ) async throws {
        try requireToken(idToken)
        try requireDocumentID(userId, label: "사용자 ID")
        try requireDocumentID(deviceId, label: "기기 ID")
        let token = fcmToken.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty, token.count <= 4_096 else {
            throw FirebaseRESTClientError.invalidInput("알림 토큰이 비어 있습니다.")
        }

        let write = updateWrite(
            name: documentName(["users", userId, "devices", deviceId]),
            fields: ["fcmToken": stringValue(token)],
            updateMask: ["fcmToken"],
            transforms: [serverTimestampTransform("lastSeenAt")]
        )
        _ = try await commit(writes: [write], idToken: idToken)
    }

    func renameDevice(
        userId: String,
        deviceId: String,
        deviceName: String,
        idToken: String
    ) async throws {
        try requireToken(idToken)
        try requireDocumentID(userId, label: "사용자 ID")
        try requireDocumentID(deviceId, label: "기기 ID")
        let normalizedName = deviceName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedName.isEmpty, normalizedName.count <= 100 else {
            throw FirebaseRESTClientError.invalidInput("기기 이름은 1~100자로 입력해 주세요.")
        }
        let write = updateWrite(
            name: documentName(["users", userId, "devices", deviceId]),
            fields: ["deviceName": stringValue(normalizedName)],
            updateMask: ["deviceName"],
            transforms: [serverTimestampTransform("lastSeenAt")]
        )
        _ = try await commit(writes: [write], idToken: idToken)
    }

    func markDeviceOffline(userId: String, deviceId: String, idToken: String) async throws {
        try requireToken(idToken)
        try requireDocumentID(userId, label: "사용자 ID")
        try requireDocumentID(deviceId, label: "기기 ID")

        let write = updateWrite(
            name: documentName(["users", userId, "devices", deviceId]),
            fields: ["isOnline": booleanValue(false)],
            updateMask: ["isOnline"],
            transforms: [serverTimestampTransform("lastSeenAt")]
        )
        _ = try await commit(writes: [write], idToken: idToken)
    }

    func revokeDeviceSession(userId: String, deviceId: String, idToken: String) async throws {
        try requireToken(idToken)
        try requireDocumentID(userId, label: "사용자 ID")
        try requireDocumentID(deviceId, label: "기기 ID")

        var request = authorizedRequest(
            url: try callableFunctionURL(name: "revokeDeviceSession"),
            method: "POST",
            idToken: idToken
        )
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(
            withJSONObject: ["data": ["deviceId": deviceId]]
        )
        _ = try await perform(request)
    }

    // MARK: - Clipboard

    func createClipboard(
        userId: String,
        record: ClipboardRecord,
        idToken: String,
        createOnly: Bool = false
    ) async throws -> ClipboardRecord {
        try requireToken(idToken)
        try requireDocumentID(userId, label: "사용자 ID")
        try requireDocumentID(record.sourceDeviceId, label: "기기 ID")
        if !record.targetDeviceId.isEmpty {
            try requireDocumentID(record.targetDeviceId, label: "대상 기기 ID")
            guard record.targetDeviceId != record.sourceDeviceId else {
                throw FirebaseRESTClientError.invalidInput("현재 기기를 대상 기기로 선택할 수 없습니다.")
            }
        }

        let documentID = record.id.isEmpty ? UUID().uuidString.lowercased() : record.id
        try requireDocumentID(documentID, label: "클립보드 항목 ID")

        switch record.kind {
        case .text:
            guard !record.content.isEmpty else {
                throw FirebaseRESTClientError.invalidInput("전송할 텍스트가 비어 있습니다.")
            }
            guard record.content.count <= Self.maximumTextLength else {
                throw FirebaseRESTClientError.invalidInput("텍스트는 \(Self.maximumTextLength)자 이하여야 합니다.")
            }
        case .image, .file:
            guard !record.storagePath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw FirebaseRESTClientError.invalidInput("Storage 경로가 비어 있습니다.")
            }
        }

        guard record.receivedBy.isEmpty, record.readBy.isEmpty else {
            throw FirebaseRESTClientError.invalidInput("새 클립보드 항목의 전달 상태는 비어 있어야 합니다.")
        }

        let now = Date()
        let expiry = record.expiresAt ?? now.addingTimeInterval(Self.defaultClipboardTTL)
        guard expiry > now,
              expiry <= now.addingTimeInterval(30 * 24 * 60 * 60) else {
            throw FirebaseRESTClientError.invalidInput("클립보드 만료 시간은 30일 이내여야 합니다.")
        }

        switch record.kind {
        case .text:
            guard record.storagePath.isEmpty,
                  record.fileName.isEmpty,
                  record.fileSize == 0,
                  record.mimeType.isEmpty else {
                throw FirebaseRESTClientError.invalidInput("텍스트 항목에 파일 정보를 포함할 수 없습니다.")
            }
        case .image, .file:
            let fileName = try normalizedFileName(record.fileName)
            guard fileName == record.fileName,
                  record.content.isEmpty,
                  record.fileSize > 0,
                  record.fileSize <= Self.maximumTransferBytes else {
                throw FirebaseRESTClientError.invalidInput("파일 정보가 올바르지 않습니다.")
            }
            let mimeType = try normalizedMimeType(record.mimeType)
            guard mimeType == record.mimeType else {
                throw FirebaseRESTClientError.invalidInput("MIME 타입 형식이 올바르지 않습니다.")
            }
            if record.kind == .image, !mimeType.hasPrefix("image/") {
                throw FirebaseRESTClientError.invalidInput("이미지 항목에는 이미지 MIME 타입이 필요합니다.")
            }
            let expectedPath = "users/\(userId)/clipboard/\(documentID)/\(fileName)"
            guard record.storagePath == expectedPath else {
                throw FirebaseRESTClientError.invalidInput("Storage 경로가 클립보드 항목과 일치하지 않습니다.")
            }
        }

        let fields: FirestoreFields = [
            "type": stringValue(record.kind.rawValue),
            "content": stringValue(record.content),
            "storagePath": stringValue(record.storagePath),
            "fileName": stringValue(record.fileName),
            "fileSize": integerValue(record.fileSize),
            "mimeType": stringValue(record.mimeType),
            "sourceDeviceId": stringValue(record.sourceDeviceId),
            "targetDeviceId": stringValue(record.targetDeviceId),
            "expiresAt": timestampValue(expiry),
            "receivedBy": stringArrayValue(record.receivedBy),
            "readBy": stringArrayValue(record.readBy)
        ]
        let write = updateWrite(
            name: documentName(["users", userId, "clipboard", documentID]),
            fields: fields,
            updateMask: nil,
            transforms: [serverTimestampTransform("createdAt")],
            currentDocument: createOnly ? ["exists": false] : nil
        )
        let commitTime = try await commit(writes: [write], idToken: idToken)

        return ClipboardRecord(
            id: documentID,
            kind: record.kind,
            content: record.content,
            storagePath: record.storagePath,
            fileName: record.fileName,
            fileSize: record.fileSize,
            mimeType: record.mimeType,
            sourceDeviceId: record.sourceDeviceId,
            targetDeviceId: record.targetDeviceId,
            createdAt: commitTime,
            expiresAt: expiry,
            receivedBy: record.receivedBy,
            readBy: record.readBy
        )
    }

    func listClipboard(
        userId: String,
        limit: Int = 50,
        idToken: String
    ) async throws -> [ClipboardRecord] {
        try requireToken(idToken)
        try requireDocumentID(userId, label: "사용자 ID")
        guard (1...100).contains(limit) else {
            throw FirebaseRESTClientError.invalidInput("클립보드 조회 개수는 1개에서 100개 사이여야 합니다.")
        }

        let documents = try await listDocuments(
            collection: ["users", userId, "clipboard"],
            pageSize: limit,
            orderBy: "createdAt desc",
            idToken: idToken
        )
        return documents.compactMap(clipboardRecord(from:))
    }

    func fetchClipboard(
        userId: String,
        itemId: String,
        idToken: String
    ) async throws -> ClipboardRecord? {
        try requireToken(idToken)
        try requireDocumentID(userId, label: "사용자 ID")
        try requireDocumentID(itemId, label: "클립보드 항목 ID")

        let request = authorizedRequest(
            url: try firestoreDocumentURL(["users", userId, "clipboard", itemId]),
            method: "GET",
            idToken: idToken
        )
        do {
            let data = try await perform(request)
            return clipboardRecord(from: try jsonObject(from: data))
        } catch FirebaseRESTClientError.notFound {
            return nil
        }
    }

    func deleteClipboard(userId: String, itemId: String, idToken: String) async throws {
        try requireToken(idToken)
        try requireDocumentID(userId, label: "사용자 ID")
        try requireDocumentID(itemId, label: "클립보드 항목 ID")
        try await deleteDocument(
            ["users", userId, "clipboard", itemId],
            idToken: idToken
        )
    }

    func markClipboardRead(
        userId: String,
        itemId: String,
        deviceId: String,
        idToken: String
    ) async throws {
        try requireToken(idToken)
        try requireDocumentID(userId, label: "사용자 ID")
        try requireDocumentID(itemId, label: "클립보드 항목 ID")
        try requireDocumentID(deviceId, label: "기기 ID")

        let transform: JSONObject = [
            "fieldPath": "readBy",
            "appendMissingElements": [
                "values": [stringValue(deviceId)]
            ]
        ]
        let write = transformWrite(
            name: documentName(["users", userId, "clipboard", itemId]),
            transforms: [transform]
        )
        _ = try await commit(writes: [write], idToken: idToken)
    }

    func markClipboardReceived(
        userId: String,
        itemId: String,
        deviceId: String,
        idToken: String
    ) async throws {
        try requireToken(idToken)
        try requireDocumentID(userId, label: "사용자 ID")
        try requireDocumentID(itemId, label: "클립보드 항목 ID")
        try requireDocumentID(deviceId, label: "기기 ID")

        let transform: JSONObject = [
            "fieldPath": "receivedBy",
            "appendMissingElements": [
                "values": [stringValue(deviceId)]
            ]
        ]
        let write = transformWrite(
            name: documentName(["users", userId, "clipboard", itemId]),
            transforms: [transform]
        )
        _ = try await commit(writes: [write], idToken: idToken)
    }

    // MARK: - Storage

    func uploadClipboardData(
        userId: String,
        itemId: String,
        data: Data,
        fileName: String,
        mimeType: String,
        idToken: String
    ) async throws -> StorageUploadResult {
        try requireToken(idToken)
        try requireStoragePathComponent(userId, label: "사용자 ID")
        try requireStoragePathComponent(itemId, label: "클립보드 항목 ID")
        guard !data.isEmpty else {
            throw FirebaseRESTClientError.invalidInput("업로드할 데이터가 비어 있습니다.")
        }
        guard Int64(data.count) <= Self.maximumTransferBytes else {
            throw FirebaseRESTClientError.invalidInput("파일은 50MB 이하여야 합니다.")
        }

        let fileName = try normalizedFileName(fileName)
        let contentType = try normalizedMimeType(
            mimeType.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? "application/octet-stream"
                : mimeType
        )
        let storagePath = "users/\(userId)/clipboard/\(itemId)/\(fileName)"
        let url = try storageUploadURL(objectName: storagePath)
        let boundary = "AnyPaste-\(UUID().uuidString)"

        var body = Data()
        body.appendUTF8("--\(boundary)\r\n")
        body.appendUTF8("Content-Type: application/json; charset=UTF-8\r\n\r\n")
        let metadata: JSONObject = [
            "name": storagePath,
            "contentType": contentType,
            "metadata": ["originalFileName": fileName]
        ]
        body.append(try JSONSerialization.data(withJSONObject: metadata))
        body.appendUTF8("\r\n--\(boundary)\r\n")
        body.appendUTF8("Content-Type: \(contentType)\r\n\r\n")
        body.append(data)
        body.appendUTF8("\r\n--\(boundary)--\r\n")

        var request = authorizedRequest(url: url, method: "POST", idToken: idToken)
        request.setValue("multipart/related; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue("multipart", forHTTPHeaderField: "X-Goog-Upload-Protocol")
        request.httpBody = body

        let responseData = try await perform(request)
        let response = try jsonObject(from: responseData)
        let responsePath = response["name"] as? String ?? storagePath
        let responseSize = int64(from: response["size"]) ?? Int64(data.count)
        let responseType = response["contentType"] as? String ?? contentType
        return StorageUploadResult(
            storagePath: responsePath,
            fileName: fileName,
            fileSize: responseSize,
            mimeType: responseType
        )
    }

    func findUploadedClipboardData(
        userId: String,
        itemId: String,
        fileName: String,
        idToken: String
    ) async throws -> StorageUploadResult? {
        try requireToken(idToken)
        try requireStoragePathComponent(userId, label: "사용자 ID")
        try requireStoragePathComponent(itemId, label: "클립보드 항목 ID")

        let fileName = try normalizedFileName(fileName)
        let storagePath = "users/\(userId)/clipboard/\(itemId)/\(fileName)"
        let request = authorizedRequest(
            url: try storageObjectURL(objectName: storagePath),
            method: "GET",
            idToken: idToken
        )

        do {
            let data = try await perform(request)
            let response = try jsonObject(from: data)
            let responsePath = response["name"] as? String ?? ""
            guard responsePath == storagePath,
                  let responseSize = int64(from: response["size"]),
                  responseSize > 0,
                  responseSize <= Self.maximumTransferBytes,
                  let responseType = response["contentType"] as? String,
                  !responseType.isEmpty else {
                throw FirebaseRESTClientError.invalidServerResponse
            }
            return StorageUploadResult(
                storagePath: responsePath,
                fileName: fileName,
                fileSize: responseSize,
                mimeType: responseType
            )
        } catch FirebaseRESTClientError.notFound {
            return nil
        }
    }

    func downloadStorageObject(
        path: String,
        maxBytes: Int64 = 50 * 1024 * 1024,
        idToken: String
    ) async throws -> Data {
        try requireToken(idToken)
        let path = try normalizedStoragePath(path)
        guard (1...Self.maximumAllowedDownloadBytes).contains(maxBytes) else {
            throw FirebaseRESTClientError.invalidInput("다운로드 크기 제한이 올바르지 않습니다.")
        }

        let request = authorizedRequest(
            url: try storageDownloadURL(objectName: path),
            method: "GET",
            idToken: idToken
        )
        return try await performDownload(request, maxBytes: maxBytes)
    }

    func deleteStorageObject(path: String, idToken: String) async throws {
        try requireToken(idToken)
        let path = try normalizedStoragePath(path)
        let request = authorizedRequest(
            url: try storageObjectURL(objectName: path),
            method: "DELETE",
            idToken: idToken
        )
        do {
            _ = try await perform(request)
        } catch FirebaseRESTClientError.notFound {
            return
        }
    }

    // MARK: - Authentication helpers

    private func identityRequest<Response: Decodable>(
        action: String,
        body: JSONObject
    ) async throws -> Response {
        let url = try identityURL(action: action)
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let data = try await perform(request)
        let responseData = data.isEmpty ? Data("{}".utf8) : data
        return try decode(Response.self, from: responseData)
    }

    private func normalizedEmail(_ rawValue: String) throws -> String {
        let email = rawValue.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard email.count <= 320,
              email.contains("@"),
              !email.hasPrefix("@"),
              !email.hasSuffix("@") else {
            throw FirebaseRESTClientError.invalidInput("올바른 이메일 주소를 입력해 주세요.")
        }
        return email
    }

    private func validatedPassword(_ password: String) throws -> String {
        guard password.count >= 6 else {
            throw FirebaseRESTClientError.invalidInput("비밀번호는 6자 이상이어야 합니다.")
        }
        return password
    }

    // MARK: - Firestore helpers

    private func listDocuments(
        collection: [String],
        pageSize: Int,
        orderBy: String,
        idToken: String
    ) async throws -> [JSONObject] {
        var components = URLComponents(url: try firestoreDocumentURL(collection), resolvingAgainstBaseURL: false)
        components?.queryItems = [
            URLQueryItem(name: "pageSize", value: String(pageSize)),
            URLQueryItem(name: "orderBy", value: orderBy)
        ]
        guard let url = components?.url else {
            throw FirebaseRESTClientError.invalidRequest
        }
        let request = authorizedRequest(url: url, method: "GET", idToken: idToken)
        let data = try await perform(request)
        let response = try jsonObject(from: data)
        return response["documents"] as? [JSONObject] ?? []
    }

    private func deleteDocument(_ path: [String], idToken: String) async throws {
        let request = authorizedRequest(
            url: try firestoreDocumentURL(path),
            method: "DELETE",
            idToken: idToken
        )
        _ = try await perform(request)
    }

    @discardableResult
    private func commit(writes: [JSONObject], idToken: String) async throws -> Date {
        let url = try firestoreCommitURL()
        var request = authorizedRequest(url: url, method: "POST", idToken: idToken)
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["writes": writes])
        let data = try await perform(request)
        let response = try jsonObject(from: data)
        if let value = response["commitTime"] as? String,
           let date = parseTimestamp(value) {
            return date
        }
        return Date()
    }

    private func updateWrite(
        name: String,
        fields: FirestoreFields,
        updateMask: [String]?,
        transforms: [JSONObject],
        currentDocument: JSONObject? = nil
    ) -> JSONObject {
        var write: JSONObject = [
            "update": [
                "name": name,
                "fields": fields
            ]
        ]
        if let updateMask {
            write["updateMask"] = ["fieldPaths": updateMask.sorted()]
        }
        if !transforms.isEmpty {
            write["updateTransforms"] = transforms
        }
        if let currentDocument {
            write["currentDocument"] = currentDocument
        }
        return write
    }

    private func transformWrite(name: String, transforms: [JSONObject]) -> JSONObject {
        [
            "transform": [
                "document": name,
                "fieldTransforms": transforms
            ]
        ]
    }

    private func serverTimestampTransform(_ fieldPath: String) -> JSONObject {
        [
            "fieldPath": fieldPath,
            "setToServerValue": "REQUEST_TIME"
        ]
    }

    private func appUser(from document: JSONObject) -> AppUser? {
        guard let fields = firestoreFields(from: document) else { return nil }
        let id = documentID(from: document)
        guard !id.isEmpty else { return nil }
        return AppUser(
            id: id,
            email: string(from: fields["email"]),
            displayName: string(from: fields["displayName"]),
            photoUrl: string(from: fields["photoUrl"]),
            createdAt: timestamp(from: fields["createdAt"]),
            lastActiveAt: timestamp(from: fields["lastActiveAt"])
        )
    }

    private func deviceRecord(from document: JSONObject) -> DeviceRecord? {
        guard let fields = firestoreFields(from: document) else { return nil }
        let id = documentID(from: document)
        guard !id.isEmpty else { return nil }
        return DeviceRecord(
            id: id,
            platform: string(from: fields["platform"]),
            deviceName: string(from: fields["deviceName"]),
            fcmToken: string(from: fields["fcmToken"]),
            lastSeenAt: timestamp(from: fields["lastSeenAt"]),
            isOnline: bool(from: fields["isOnline"])
        )
    }

    private func clipboardRecord(from document: JSONObject) -> ClipboardRecord? {
        guard let fields = firestoreFields(from: document) else { return nil }
        let id = documentID(from: document)
        guard !id.isEmpty else { return nil }
        guard let kind = ClipboardKind(rawValue: string(from: fields["type"])) else {
            return nil
        }
        return ClipboardRecord(
            id: id,
            kind: kind,
            content: string(from: fields["content"]),
            storagePath: string(from: fields["storagePath"]),
            fileName: string(from: fields["fileName"]),
            fileSize: integer(from: fields["fileSize"]),
            mimeType: string(from: fields["mimeType"]),
            sourceDeviceId: string(from: fields["sourceDeviceId"]),
            targetDeviceId: string(from: fields["targetDeviceId"]),
            createdAt: timestamp(from: fields["createdAt"]),
            expiresAt: timestamp(from: fields["expiresAt"]),
            receivedBy: stringArray(from: fields["receivedBy"]),
            readBy: stringArray(from: fields["readBy"])
        )
    }

    private func firestoreFields(from document: JSONObject) -> FirestoreFields? {
        document["fields"] as? FirestoreFields
    }

    private func documentID(from document: JSONObject) -> String {
        guard let name = document["name"] as? String else { return "" }
        return name.split(separator: "/").last.map(String.init) ?? ""
    }

    private func stringValue(_ value: String) -> JSONObject {
        ["stringValue": value]
    }

    private func booleanValue(_ value: Bool) -> JSONObject {
        ["booleanValue": value]
    }

    private func integerValue(_ value: Int64) -> JSONObject {
        ["integerValue": String(value)]
    }

    private func timestampValue(_ value: Date) -> JSONObject {
        ["timestampValue": timestampString(from: value)]
    }

    private func stringArrayValue(_ values: [String]) -> JSONObject {
        [
            "arrayValue": [
                "values": values.map(stringValue)
            ]
        ]
    }

    private func string(from value: JSONObject?) -> String {
        value?["stringValue"] as? String ?? ""
    }

    private func bool(from value: JSONObject?) -> Bool {
        value?["booleanValue"] as? Bool ?? false
    }

    private func integer(from value: JSONObject?) -> Int64 {
        int64(from: value?["integerValue"]) ?? 0
    }

    private func timestamp(from value: JSONObject?) -> Date? {
        guard let rawValue = value?["timestampValue"] as? String else { return nil }
        return parseTimestamp(rawValue)
    }

    private func stringArray(from value: JSONObject?) -> [String] {
        guard let arrayValue = value?["arrayValue"] as? JSONObject,
              let values = arrayValue["values"] as? [JSONObject] else {
            return []
        }
        return values.compactMap { $0["stringValue"] as? String }
    }

    // MARK: - Storage helpers

    private func normalizedFileName(_ rawValue: String) throws -> String {
        let name = String(
            rawValue
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "\\", with: "_")
                .prefix(Self.maximumFileNameLength)
        )
        guard !name.isEmpty, name != ".", name != ".." else {
            throw FirebaseRESTClientError.invalidInput("파일 이름이 올바르지 않습니다.")
        }
        return name
    }

    private func normalizedStoragePath(_ rawValue: String) throws -> String {
        let path = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let components = path.split(separator: "/", omittingEmptySubsequences: false)
        guard !path.isEmpty,
              !path.contains("//"),
              !components.contains(where: { $0 == "." || $0 == ".." }) else {
            throw FirebaseRESTClientError.invalidInput("Storage 경로가 올바르지 않습니다.")
        }
        return path
    }

    private func normalizedMimeType(_ rawValue: String) throws -> String {
        let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty,
              value.count <= 255,
              !value.contains(where: { $0.isWhitespace || $0.isNewline }) else {
            throw FirebaseRESTClientError.invalidInput("MIME 타입 형식이 올바르지 않습니다.")
        }

        let components = value.split(separator: "/", omittingEmptySubsequences: false)
        guard components.count == 2,
              isValidMimeComponent(components[0], allowsWildcardFirstCharacter: false),
              isValidMimeComponent(components[1], allowsWildcardFirstCharacter: true) else {
            throw FirebaseRESTClientError.invalidInput("MIME 타입 형식이 올바르지 않습니다.")
        }
        return value
    }

    private func isValidMimeComponent(
        _ component: Substring,
        allowsWildcardFirstCharacter: Bool
    ) -> Bool {
        guard let first = component.utf8.first else { return false }
        let digits = UInt8(ascii: "0")...UInt8(ascii: "9")
        let uppercase = UInt8(ascii: "A")...UInt8(ascii: "Z")
        let lowercase = UInt8(ascii: "a")...UInt8(ascii: "z")
        let isAlphaNumeric: (UInt8) -> Bool = { byte in
            digits.contains(byte) || uppercase.contains(byte) || lowercase.contains(byte)
        }
        guard isAlphaNumeric(first) || (allowsWildcardFirstCharacter && first == UInt8(ascii: "*")) else {
            return false
        }
        let punctuation = Set("!#$&^_.+*-".utf8)
        return component.utf8.allSatisfy { isAlphaNumeric($0) || punctuation.contains($0) }
    }

    // MARK: - Request helpers

    private func authorizedRequest(
        url: URL,
        method: String,
        idToken: String
    ) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        return request
    }

    private func perform(_ request: URLRequest) async throws -> Data {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw FirebaseRESTClientError.networkUnavailable
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw FirebaseRESTClientError.invalidServerResponse
        }
        guard (200..<300).contains(httpResponse.statusCode) else {
            throw serverError(statusCode: httpResponse.statusCode, data: data)
        }
        return data
    }

    private func performDownload(_ request: URLRequest, maxBytes: Int64) async throws -> Data {
        let temporaryURL: URL
        let response: URLResponse
        do {
            (temporaryURL, response) = try await session.download(for: request)
        } catch {
            throw FirebaseRESTClientError.networkUnavailable
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw FirebaseRESTClientError.invalidServerResponse
        }
        guard (200..<300).contains(httpResponse.statusCode) else {
            let errorData = (try? Data(contentsOf: temporaryURL)) ?? Data()
            throw serverError(statusCode: httpResponse.statusCode, data: Data(errorData.prefix(64 * 1024)))
        }
        if httpResponse.expectedContentLength > maxBytes {
            throw FirebaseRESTClientError.invalidInput("다운로드 파일이 허용 크기를 초과했습니다.")
        }

        let fileSize = try? temporaryURL.resourceValues(forKeys: [.fileSizeKey]).fileSize
        guard let fileSize, Int64(fileSize) <= maxBytes else {
            throw FirebaseRESTClientError.invalidInput("다운로드 파일이 허용 크기를 초과했습니다.")
        }
        do {
            return try Data(contentsOf: temporaryURL, options: .mappedIfSafe)
        } catch {
            throw FirebaseRESTClientError.invalidServerResponse
        }
    }

    private func serverError(statusCode: Int, data: Data) -> FirebaseRESTClientError {
        let response = (try? JSONSerialization.jsonObject(with: data)) as? JSONObject
        let error = response?["error"] as? JSONObject
        let rawMessage = error?["message"] as? String ?? ""
        let status = (error?["status"] as? String)?.uppercased() ?? ""
        let code = rawMessage
            .components(separatedBy: CharacterSet(charactersIn: " :\n"))
            .first?
            .uppercased() ?? ""

        if status == "ALREADY_EXISTS"
            || status == "FAILED_PRECONDITION"
            || code == "ALREADY_EXISTS"
            || code == "FAILED_PRECONDITION"
            || statusCode == 409 {
            return .alreadyExists
        }

        switch code {
        case "EMAIL_EXISTS":
            return .serverMessage("이미 사용 중인 이메일입니다.")
        case "INVALID_EMAIL":
            return .serverMessage("올바른 이메일 주소를 입력해 주세요.")
        case "MISSING_EMAIL":
            return .serverMessage("이메일을 입력해 주세요.")
        case "MISSING_PASSWORD":
            return .serverMessage("비밀번호를 입력해 주세요.")
        case "WEAK_PASSWORD":
            return .serverMessage("비밀번호는 6자 이상이어야 합니다.")
        case "INVALID_PASSWORD", "EMAIL_NOT_FOUND", "INVALID_LOGIN_CREDENTIALS":
            return .serverMessage("이메일 또는 비밀번호가 올바르지 않습니다.")
        case "USER_DISABLED":
            return .serverMessage("사용이 중지된 계정입니다.")
        case "TOO_MANY_ATTEMPTS_TRY_LATER":
            return .serverMessage("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.")
        case "INVALID_ID_TOKEN", "TOKEN_EXPIRED", "USER_NOT_FOUND":
            return .invalidSession
        case "PERMISSION_DENIED":
            return .serverMessage("이 작업을 수행할 권한이 없습니다.")
        case "RESOURCE_EXHAUSTED":
            return .serverMessage("서비스 사용량 한도를 초과했습니다.")
        case "NOT_FOUND":
            return .notFound
        default:
            if statusCode == 401 {
                return .invalidSession
            }
            if statusCode == 403 {
                return .serverMessage("이 작업을 수행할 권한이 없습니다.")
            }
            if statusCode == 404 {
                return .notFound
            }
            if statusCode == 429 {
                return .serverMessage("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.")
            }
            return .serverMessage("요청 처리에 실패했습니다. (오류 코드: \(statusCode))")
        }
    }

    private func identityURL(action: String) throws -> URL {
        var components = URLComponents(string: "https://identitytoolkit.googleapis.com/v1/\(action)")
        components?.queryItems = [URLQueryItem(name: "key", value: configuration.firebaseAPIKey)]
        guard let url = components?.url else {
            throw FirebaseRESTClientError.invalidRequest
        }
        return url
    }

    private func secureTokenURL() throws -> URL {
        var components = URLComponents(string: "https://securetoken.googleapis.com/v1/token")
        components?.queryItems = [URLQueryItem(name: "key", value: configuration.firebaseAPIKey)]
        guard let url = components?.url else {
            throw FirebaseRESTClientError.invalidRequest
        }
        return url
    }

    private func firestoreDocumentURL(_ path: [String]) throws -> URL {
        var url = try firestoreBaseURL()
        for component in path {
            url.appendPathComponent(component)
        }
        return url
    }

    private func firestoreBaseURL() throws -> URL {
        guard let project = encodedPathComponent(configuration.firebaseProjectID),
              let url = URL(
                string: "https://firestore.googleapis.com/v1/projects/\(project)/databases/(default)/documents"
              ) else {
            throw FirebaseRESTClientError.invalidRequest
        }
        return url
    }

    private func callableFunctionURL(name: String) throws -> URL {
        guard let project = encodedPathComponent(configuration.firebaseProjectID),
              let url = URL(
                string: "https://asia-northeast3-\(project).cloudfunctions.net/\(name)"
              ) else {
            throw FirebaseRESTClientError.invalidRequest
        }
        return url
    }

    private func firestoreCommitURL() throws -> URL {
        let baseURL = try firestoreBaseURL()
        guard let url = URL(string: baseURL.absoluteString + ":commit") else {
            throw FirebaseRESTClientError.invalidRequest
        }
        return url
    }

    private func documentName(_ path: [String]) -> String {
        "projects/\(configuration.firebaseProjectID)/databases/(default)/documents/\(path.joined(separator: "/"))"
    }

    private func storageUploadURL(objectName: String) throws -> URL {
        var components = URLComponents(
            string: "https://firebasestorage.googleapis.com/v0/b/\(configuration.firebaseStorageBucket)/o"
        )
        components?.queryItems = [
            URLQueryItem(name: "name", value: objectName)
        ]
        guard let url = components?.url else {
            throw FirebaseRESTClientError.invalidRequest
        }
        return url
    }

    private func storageDownloadURL(objectName: String) throws -> URL {
        var components = URLComponents(url: try storageObjectURL(objectName: objectName), resolvingAgainstBaseURL: false)
        components?.queryItems = [URLQueryItem(name: "alt", value: "media")]
        guard let url = components?.url else {
            throw FirebaseRESTClientError.invalidRequest
        }
        return url
    }

    private func storageObjectURL(objectName: String) throws -> URL {
        guard let bucket = encodedPathComponent(configuration.firebaseStorageBucket),
              let object = encodedPathComponent(objectName),
              let url = URL(string: "https://firebasestorage.googleapis.com/v0/b/\(bucket)/o/\(object)") else {
            throw FirebaseRESTClientError.invalidRequest
        }
        return url
    }

    private func requireToken(_ idToken: String) throws {
        guard !idToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw FirebaseRESTClientError.invalidSession
        }
    }

    private func requireDocumentID(_ value: String, label: String) throws {
        guard !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              value.count <= 128,
              !value.contains("/") else {
            throw FirebaseRESTClientError.invalidInput("\(label) 형식이 올바르지 않습니다.")
        }
    }

    private func requireStoragePathComponent(_ value: String, label: String) throws {
        guard !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              value.count <= 128,
              !value.contains("/"),
              !value.contains("\\"),
              value != ".",
              value != ".." else {
            throw FirebaseRESTClientError.invalidInput("\(label) 형식이 올바르지 않습니다.")
        }
    }

    private func formEncoded(_ values: [String: String]) -> Data {
        var components = URLComponents()
        components.queryItems = values.map { URLQueryItem(name: $0.key, value: $0.value) }
        return Data((components.percentEncodedQuery ?? "").utf8)
    }

    private func encodedPathComponent(_ value: String) -> String? {
        let allowedCharacters = CharacterSet(
            charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"
        )
        return value.addingPercentEncoding(withAllowedCharacters: allowedCharacters)
    }

    private func decode<Value: Decodable>(_ type: Value.Type, from data: Data) throws -> Value {
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            throw FirebaseRESTClientError.invalidServerResponse
        }
    }

    private func jsonObject(from data: Data) throws -> JSONObject {
        do {
            return try JSONSerialization.jsonObject(with: data) as? JSONObject ?? [:]
        } catch {
            throw FirebaseRESTClientError.invalidServerResponse
        }
    }

    private func int64(from value: Any?) -> Int64? {
        if let value = value as? Int64 { return value }
        if let value = value as? Int { return Int64(value) }
        if let value = value as? NSNumber { return value.int64Value }
        if let value = value as? String { return Int64(value) }
        return nil
    }

    private func parseTimestamp(_ value: String) -> Date? {
        let formatterWithFraction = ISO8601DateFormatter()
        formatterWithFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatterWithFraction.date(from: value) {
            return date
        }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: value)
    }

    private func timestampString(from value: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: value)
    }

}

enum FirebaseRESTClientError: LocalizedError, Equatable {
    case invalidInput(String)
    case invalidRequest
    case invalidSession
    case invalidServerResponse
    case networkUnavailable
    case notFound
    case alreadyExists
    case serverMessage(String)

    var errorDescription: String? {
        switch self {
        case let .invalidInput(message), let .serverMessage(message):
            return message
        case .invalidRequest:
            return "요청을 만들 수 없습니다. 앱 설정을 확인해 주세요."
        case .invalidSession:
            return "로그인 정보가 만료되었습니다. 다시 로그인해 주세요."
        case .invalidServerResponse:
            return "서버 응답을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."
        case .networkUnavailable:
            return "네트워크 연결을 확인해 주세요."
        case .notFound:
            return "요청한 데이터를 찾을 수 없습니다."
        case .alreadyExists:
            return "같은 항목이 이미 존재합니다."
        }
    }
}

private typealias JSONObject = [String: Any]
private typealias FirestoreFields = [String: JSONObject]

private struct EmptyResponse: Decodable {
    init(from decoder: Decoder) throws {}
}

private struct IdentityTokenResponse: Decodable {
    let localID: String?
    let email: String?
    let displayName: String?
    let idToken: String?
    let refreshToken: String?
    let expiresIn: String?

    enum CodingKeys: String, CodingKey {
        case localID = "localId"
        case email
        case displayName
        case idToken
        case refreshToken
        case expiresIn
    }

    func validatedTokens() throws -> (idToken: String, refreshToken: String, expiresIn: TimeInterval) {
        guard let idToken,
              let refreshToken,
              let expiresIn,
              let lifetime = TimeInterval(expiresIn),
              !idToken.isEmpty,
              !refreshToken.isEmpty else {
            throw FirebaseRESTClientError.invalidServerResponse
        }
        return (idToken, refreshToken, lifetime)
    }
}

private struct RefreshTokenResponse: Decodable {
    let idToken: String
    let refreshToken: String
    let expiresIn: String

    enum CodingKeys: String, CodingKey {
        case idToken = "id_token"
        case refreshToken = "refresh_token"
        case expiresIn = "expires_in"
    }
}

private struct AccountLookupResponse: Decodable {
    let users: [AccountLookupUser]

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        users = try container.decodeIfPresent([AccountLookupUser].self, forKey: .users) ?? []
    }

    private enum CodingKeys: String, CodingKey {
        case users
    }
}

private struct AccountLookupUser: Decodable {
    let localID: String
    let email: String?
    let displayName: String?
    let photoURL: String?
    let emailVerified: Bool?
    let providerUserInfo: [ProviderInfo]?

    enum CodingKeys: String, CodingKey {
        case localID = "localId"
        case email
        case displayName
        case photoURL = "photoUrl"
        case emailVerified
        case providerUserInfo
    }

    var appUser: AppUser {
        AppUser(
            id: localID,
            email: email ?? "",
            displayName: displayName ?? "",
            photoUrl: photoURL ?? "",
            isEmailVerified: emailVerified ?? false,
            providers: providerUserInfo?.map(\.providerID) ?? []
        )
    }
}

private struct ProviderInfo: Decodable {
    let providerID: String

    enum CodingKeys: String, CodingKey {
        case providerID = "providerId"
    }
}

private extension Data {
    mutating func appendUTF8(_ value: String) {
        append(Data(value.utf8))
    }
}
