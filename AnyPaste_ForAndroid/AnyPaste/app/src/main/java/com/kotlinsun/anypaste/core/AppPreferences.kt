package com.kotlinsun.anypaste.core

import android.content.Context

class AppPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    var hasSeenOnboarding: Boolean
        get() = preferences.getBoolean(KEY_ONBOARDING_SEEN, false)
        set(value) = preferences.edit().putBoolean(KEY_ONBOARDING_SEEN, value).apply()

    var autoSyncEnabled: Boolean
        get() = preferences.getBoolean(KEY_AUTO_SYNC, false)
        set(value) = preferences.edit().putBoolean(KEY_AUTO_SYNC, value).apply()

    var permissionsConfigured: Boolean
        get() = preferences.getBoolean(KEY_PERMISSIONS_CONFIGURED, false)
        set(value) = preferences.edit().putBoolean(KEY_PERMISSIONS_CONFIGURED, value).apply()

    var wifiOnlyFiles: Boolean
        get() = preferences.getBoolean(KEY_WIFI_ONLY_FILES, false)
        set(value) = preferences.edit().putBoolean(KEY_WIFI_ONLY_FILES, value).apply()

    var incomingNotificationsEnabled: Boolean
        get() = preferences.getBoolean(KEY_INCOMING_NOTIFICATIONS, true)
        set(value) = preferences.edit().putBoolean(KEY_INCOMING_NOTIFICATIONS, value).apply()

    var backgroundSyncNotice: String
        get() = preferences.getString(KEY_BACKGROUND_SYNC_NOTICE, "").orEmpty()
        set(value) = preferences.edit().putString(KEY_BACKGROUND_SYNC_NOTICE, value).apply()

    companion object {
        const val PREFERENCES_NAME = "anypaste_settings"
        const val KEY_INCOMING_NOTIFICATIONS = "incoming_notifications"

        private const val KEY_ONBOARDING_SEEN = "onboarding_seen"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_PERMISSIONS_CONFIGURED = "permissions_configured"
        private const val KEY_WIFI_ONLY_FILES = "wifi_only_files"
        private const val KEY_BACKGROUND_SYNC_NOTICE = "background_sync_notice"
    }
}
