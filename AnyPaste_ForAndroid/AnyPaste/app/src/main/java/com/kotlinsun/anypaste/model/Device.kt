package com.kotlinsun.anypaste.model

import com.google.firebase.Timestamp

enum class DevicePlatform(val value: String) {
    ANDROID("android"),
    MACOS("macos");

    companion object {
        fun fromValue(value: String): DevicePlatform =
            entries.firstOrNull { it.value == value } ?: ANDROID
    }
}

data class Device(
    val id: String = "",
    val platform: String = DevicePlatform.ANDROID.value,
    val deviceName: String = "",
    val fcmToken: String = "",
    val lastSeenAt: Timestamp? = null,
    val isOnline: Boolean = false,
) {
    fun resolvedPlatform(): DevicePlatform = DevicePlatform.fromValue(platform)

    fun isRecentlyOnline(
        now: Timestamp = Timestamp.now(),
        timeoutSeconds: Long = 120L,
    ): Boolean = isOnline && lastSeenAt?.let {
        now.seconds - it.seconds <= timeoutSeconds
    } == true
}
