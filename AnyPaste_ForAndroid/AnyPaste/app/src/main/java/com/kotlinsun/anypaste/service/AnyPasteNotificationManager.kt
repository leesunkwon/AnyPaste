package com.kotlinsun.anypaste.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kotlinsun.anypaste.MainActivity
import com.kotlinsun.anypaste.R
import com.kotlinsun.anypaste.core.AppPreferences

/**
 * Owns all notification channels and notification construction used by clipboard sync.
 *
 * Clipboard contents can contain private data, so incoming notifications use private lock-screen
 * visibility and only expose a generic public version while the device is locked.
 */
class AnyPasteNotificationManager(context: Context) {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)
    private val appPreferences = AppPreferences(appContext)

    fun createChannels() {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_SYNC,
                    "클립보드 동기화",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "AnyPaste 클립보드 동기화 실행 상태"
                    setShowBadge(false)
                    setSound(null, null)
                },
                NotificationChannel(
                    CHANNEL_CLIPBOARD_RECEIVED,
                    "클립보드 수신",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "다른 기기에서 텍스트나 이미지를 받았을 때 알림"
                },
                NotificationChannel(
                    CHANNEL_FILE_RECEIVED,
                    "파일 수신",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "다른 기기에서 파일을 받았을 때 알림"
                },
            ),
        )
    }

    fun buildSyncNotification(status: String): Notification {
        val stopIntent = Intent(appContext, ClipboardSyncService::class.java).apply {
            action = ClipboardSyncService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            appContext,
            REQUEST_STOP_SERVICE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(appContext, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_copy)
            .setContentTitle("AnyPaste 동기화 중")
            .setContentText(status)
            .setContentIntent(mainPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_check, "동기화 중지", stopPendingIntent)
            .build()
    }

    fun updateSyncNotification(status: String) {
        notificationManager.notify(NOTIFICATION_ID_SYNC, buildSyncNotification(status))
    }

    fun notifyTextReceived(itemId: String, text: String, sourceDeviceId: String?) {
        val preview = text.toNotificationPreview()
        notifyIncoming(
            id = itemId,
            channelId = CHANNEL_CLIPBOARD_RECEIVED,
            smallIcon = R.drawable.ic_text,
            title = "새 텍스트를 받았습니다",
            content = preview.ifBlank { "클립보드에 복사했습니다" },
            sourceDeviceId = sourceDeviceId,
            itemType = TYPE_TEXT,
            useBigText = true,
        )
    }

    fun notifyImageReceived(itemId: String, fileName: String?, sourceDeviceId: String?) {
        notifyIncoming(
            id = itemId,
            channelId = CHANNEL_CLIPBOARD_RECEIVED,
            smallIcon = R.drawable.ic_image,
            title = "새 이미지를 받았습니다",
            content = fileName?.takeIf(String::isNotBlank) ?: "AnyPaste에서 이미지를 확인하세요",
            sourceDeviceId = sourceDeviceId,
            itemType = TYPE_IMAGE,
        )
    }

    fun notifyFileReceived(itemId: String, fileName: String?, sourceDeviceId: String?) {
        notifyIncoming(
            id = itemId,
            channelId = CHANNEL_FILE_RECEIVED,
            smallIcon = R.drawable.ic_file,
            title = "새 파일을 받았습니다",
            content = fileName?.takeIf(String::isNotBlank) ?: "AnyPaste에서 파일을 확인하세요",
            sourceDeviceId = sourceDeviceId,
            itemType = TYPE_FILE,
        )
    }

    fun notifyGenericReceived(
        itemId: String,
        itemType: String?,
        fileName: String?,
        sourceDeviceId: String?,
    ) {
        when (itemType?.lowercase()) {
            TYPE_IMAGE -> notifyImageReceived(itemId, fileName, sourceDeviceId)
            TYPE_FILE -> notifyFileReceived(itemId, fileName, sourceDeviceId)
            else -> notifyIncoming(
                id = itemId,
                channelId = CHANNEL_CLIPBOARD_RECEIVED,
                smallIcon = R.drawable.ic_text,
                title = "새 텍스트를 받았습니다",
                content = "AnyPaste에서 받은 내용을 확인하세요",
                sourceDeviceId = sourceDeviceId,
                itemType = TYPE_TEXT,
            )
        }
    }

    fun cancelSyncNotification() {
        notificationManager.cancel(NOTIFICATION_ID_SYNC)
    }

    private fun notifyIncoming(
        id: String,
        channelId: String,
        smallIcon: Int,
        title: String,
        content: String,
        sourceDeviceId: String?,
        itemType: String,
        useBigText: Boolean = false,
    ) {
        if (!appPreferences.incomingNotificationsEnabled || !canPostNotifications()) return

        val privateBuilder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(mainPendingIntent(id, itemType))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(GROUP_INCOMING)
            .setSubText(sourceDeviceId?.takeIf(String::isNotBlank)?.let { "기기 $it" })

        if (useBigText) {
            privateBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(content))
        }

        val publicNotification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle("AnyPaste")
            .setContentText("새 항목을 받았습니다")
            .build()

        privateBuilder.setPublicVersion(publicNotification)
        notificationManager.notify(notificationIdFor(id), privateBuilder.build())
    }

    private fun mainPendingIntent(itemId: String? = null, itemType: String? = null): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            itemId?.let { putExtra(EXTRA_ITEM_ID, it) }
            itemType?.let { putExtra(EXTRA_ITEM_TYPE, it) }
        }
        val requestCode = itemId?.hashCode()?.and(Int.MAX_VALUE) ?: REQUEST_OPEN_APP
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(): Boolean {
        val runtimePermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return runtimePermissionGranted && notificationManager.areNotificationsEnabled()
    }

    private fun notificationIdFor(itemId: String): Int {
        return NOTIFICATION_ID_INCOMING_BASE + (itemId.hashCode() and NOTIFICATION_ID_MASK)
    }

    private fun String.toNotificationPreview(): String {
        val singleLine = trim().replace(WHITESPACE, " ")
        return if (singleLine.length <= MAX_PREVIEW_LENGTH) {
            singleLine
        } else {
            singleLine.take(MAX_PREVIEW_LENGTH - 1) + "…"
        }
    }

    companion object {
        const val NOTIFICATION_ID_SYNC = 1001

        const val EXTRA_ITEM_ID = "com.kotlinsun.anypaste.extra.ITEM_ID"
        const val EXTRA_ITEM_TYPE = "com.kotlinsun.anypaste.extra.ITEM_TYPE"

        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
        const val TYPE_FILE = "file"

        private const val CHANNEL_SYNC = "clipboard_sync"
        private const val CHANNEL_CLIPBOARD_RECEIVED = "clipboard_received"
        private const val CHANNEL_FILE_RECEIVED = "file_received"
        private const val GROUP_INCOMING = "anypaste_incoming"

        private const val REQUEST_OPEN_APP = 2001
        private const val REQUEST_STOP_SERVICE = 2002
        private const val NOTIFICATION_ID_INCOMING_BASE = 10_000
        private const val NOTIFICATION_ID_MASK = 0x000F_FFFF
        private const val MAX_PREVIEW_LENGTH = 160
        private val WHITESPACE = Regex("\\s+")
    }
}
