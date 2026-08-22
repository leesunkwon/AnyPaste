package com.kotlinsun.anypaste.model

import com.google.firebase.Timestamp

enum class ClipboardType(val value: String) {
    TEXT("text"),
    IMAGE("image"),
    FILE("file");

    companion object {
        fun fromValue(value: String): ClipboardType =
            entries.firstOrNull { it.value == value } ?: FILE
    }
}

data class ClipboardItem(
    val id: String = "",
    val type: String = ClipboardType.TEXT.value,
    val content: String = "",
    val storagePath: String = "",
    val fileName: String = "",
    val fileSize: Long = 0L,
    val mimeType: String = "",
    val sourceDeviceId: String = "",
    val targetDeviceId: String = "",
    val createdAt: Timestamp? = null,
    val expiresAt: Timestamp? = null,
    val readBy: List<String> = emptyList(),
) {
    fun resolvedType(): ClipboardType = ClipboardType.fromValue(type)

    fun isExpired(now: Timestamp = Timestamp.now()): Boolean =
        expiresAt?.let { it <= now } ?: false

    fun isReadBy(deviceId: String): Boolean = deviceId in readBy
}
