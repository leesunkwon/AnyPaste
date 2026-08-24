package com.kotlinsun.anypaste.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kotlinsun.anypaste.core.AppPreferences
import com.kotlinsun.anypaste.data.FirebaseAuthRepository
import com.kotlinsun.anypaste.data.FirestoreClipboardRepository
import com.kotlinsun.anypaste.data.FirestoreDeviceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Handles data-only FCM messages without putting clipboard contents in the push payload. */
class AnyPasteMessagingService : FirebaseMessagingService() {
    private val authRepository by lazy { FirebaseAuthRepository() }
    private val clipboardRepository by lazy { FirestoreClipboardRepository() }
    private val deviceRepository by lazy { FirestoreDeviceRepository() }
    private val notificationManager by lazy { AnyPasteNotificationManager(this) }
    private val appPreferences by lazy { AppPreferences(this) }

    override fun onMessageReceived(message: RemoteMessage) {
        val localDeviceId = ClipboardSyncService.getOrCreateDeviceId(this)
        if (message.data[KEY_EVENT] == EVENT_SESSION_REVOKED &&
            message.data[KEY_DEVICE_ID] == localDeviceId
        ) {
            appPreferences.autoSyncEnabled = false
            ClipboardSyncService.stop(this)
            authRepository.signOut()
            return
        }
        if (!appPreferences.incomingNotificationsEnabled) return

        val itemId = message.data[KEY_ITEM_ID]?.takeIf(String::isNotBlank) ?: return
        val expiresAtEpochMillis = message.data[KEY_EXPIRES_AT_EPOCH_MS]?.toLongOrNull()
        if (expiresAtEpochMillis != null && expiresAtEpochMillis <= System.currentTimeMillis()) return
        val payloadSourceDeviceId = message.data[KEY_SOURCE_DEVICE_ID]
        if (payloadSourceDeviceId == localDeviceId) return

        val userId = authRepository.currentUser?.uid ?: return
        notificationManager.createChannels()

        val item = runBlocking(Dispatchers.IO) {
            try {
                withTimeoutOrNull(FIREBASE_LOOKUP_TIMEOUT_MILLIS) {
                    clipboardRepository.getItem(userId, itemId)
                }
            } catch (_: Exception) {
                null
            }
        }

        // Do not create a content-free notification when the document was deleted, expired, or
        // could not be fetched in time. The realtime listener will surface it if it still exists.
        if (item == null) return

        if (item.sourceDeviceId == localDeviceId ||
            (item.targetDeviceId.isNotBlank() && item.targetDeviceId != localDeviceId) ||
            item.isExpired() ||
            item.isReadBy(localDeviceId)
        ) return
        val sourceDeviceId = item.sourceDeviceId.ifBlank { payloadSourceDeviceId.orEmpty() }
        when (item.type.lowercase()) {
            AnyPasteNotificationManager.TYPE_TEXT -> {
                if (item.content.isBlank()) {
                    notificationManager.notifyGenericReceived(
                        itemId = item.id,
                        itemType = item.type,
                        fileName = null,
                        sourceDeviceId = sourceDeviceId,
                    )
                } else {
                    notificationManager.notifyTextReceived(
                        itemId = item.id,
                        text = item.content,
                        sourceDeviceId = sourceDeviceId,
                    )
                }
            }

            AnyPasteNotificationManager.TYPE_IMAGE -> notificationManager.notifyImageReceived(
                itemId = item.id,
                fileName = item.fileName,
                sourceDeviceId = sourceDeviceId,
            )

            AnyPasteNotificationManager.TYPE_FILE -> notificationManager.notifyFileReceived(
                itemId = item.id,
                fileName = item.fileName,
                sourceDeviceId = sourceDeviceId,
            )

            else -> notificationManager.notifyGenericReceived(
                itemId = item.id,
                itemType = message.data[KEY_TYPE],
                fileName = message.data[KEY_FILE_NAME],
                sourceDeviceId = sourceDeviceId,
            )
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (token.isBlank()) return
        val userId = authRepository.currentUser?.uid ?: return
        val deviceId = ClipboardSyncService.getOrCreateDeviceId(this)

        runBlocking(Dispatchers.IO) {
            try {
                withTimeoutOrNull(FIREBASE_TOKEN_TIMEOUT_MILLIS) {
                    deviceRepository.updateFcmToken(
                        userId = userId,
                        deviceId = deviceId,
                        fcmToken = token,
                    )
                }
            } catch (_: Exception) {
                // MainViewModel uploads the current registration again on the next signed-in launch.
            }
        }
    }

    private companion object {
        const val KEY_ITEM_ID = "itemId"
        const val KEY_TYPE = "type"
        const val KEY_SOURCE_DEVICE_ID = "sourceDeviceId"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_EXPIRES_AT_EPOCH_MS = "expiresAtEpochMs"
        const val KEY_EVENT = "event"
        const val KEY_DEVICE_ID = "deviceId"

        const val EVENT_SESSION_REVOKED = "sessionRevoked"

        const val FIREBASE_LOOKUP_TIMEOUT_MILLIS = 8_000L
        const val FIREBASE_TOKEN_TIMEOUT_MILLIS = 8_000L
    }
}
