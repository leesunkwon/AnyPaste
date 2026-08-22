//
//  NotificationService.swift
//  AnyPaste
//

import Foundation
import UserNotifications

final class NotificationService {
    private let center: UNUserNotificationCenter

    init(center: UNUserNotificationCenter = .current()) {
        self.center = center
    }

    func requestAuthorization(
        options: UNAuthorizationOptions = [.alert, .sound]
    ) async throws -> Bool {
        try await center.requestAuthorization(options: options)
    }

    func authorizationStatus() async -> UNAuthorizationStatus {
        await center.notificationSettings().authorizationStatus
    }

    /// Schedules a local notification for an item received from another device.
    /// Returns `false` when notifications are not currently allowed.
    @discardableResult
    func sendReceivedNotification(
        for payload: ClipboardPayload,
        sourceDeviceName: String? = nil
    ) async throws -> Bool {
        let status = await authorizationStatus()
        guard status == .authorized || status == .provisional else {
            return false
        }

        let content = UNMutableNotificationContent()
        content.title = notificationTitle(sourceDeviceName: sourceDeviceName)
        content.body = notificationBody(for: payload)
        content.sound = .default
        content.threadIdentifier = "anypaste.received"

        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: nil
        )
        try await center.add(request)
        return true
    }

    private func notificationTitle(sourceDeviceName: String?) -> String {
        guard let sourceDeviceName, !sourceDeviceName.isEmpty else {
            return "AnyPaste"
        }
        return "AnyPaste · \(sourceDeviceName)"
    }

    private func notificationBody(for payload: ClipboardPayload) -> String {
        switch payload {
        case .text:
            return "새 텍스트가 클립보드에 도착했습니다."
        case .image:
            return "새 이미지가 클립보드에 도착했습니다."
        case .file:
            return "새 파일이 도착했습니다."
        }
    }
}
