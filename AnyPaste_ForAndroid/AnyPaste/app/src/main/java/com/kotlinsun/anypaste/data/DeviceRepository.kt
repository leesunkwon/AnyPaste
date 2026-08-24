package com.kotlinsun.anypaste.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.kotlinsun.anypaste.model.Device
import com.kotlinsun.anypaste.model.DevicePlatform
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

interface DeviceRepository {
    fun observeDevices(userId: String): Flow<List<Device>>

    suspend fun registerDevice(
        userId: String,
        deviceId: String,
        deviceName: String,
        platform: DevicePlatform = DevicePlatform.ANDROID,
        fcmToken: String = "",
    ): Device

    suspend fun getDevice(userId: String, deviceId: String): Device?
    suspend fun heartbeat(userId: String, deviceId: String)
    suspend fun updateFcmToken(userId: String, deviceId: String, fcmToken: String)
    suspend fun markOffline(userId: String, deviceId: String)
    suspend fun removeDevice(userId: String, deviceId: String)
}

/** Raised when this installation has been disconnected from the account elsewhere. */
class DeviceSessionRevokedException : IllegalStateException(
    "이 기기는 연결이 해제되었습니다. 다시 사용하려면 새 기기로 연결해 주세요.",
)

class FirestoreDeviceRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(Fields.REGION),
) : DeviceRepository {
    override fun observeDevices(userId: String): Flow<List<Device>> = callbackFlow {
        requireValidDocumentId(userId, "사용자 ID")
        val registration = devicesCollection(userId)
            .orderBy(Fields.LAST_SEEN_AT, Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents.orEmpty().map { it.toDevice() })
            }
        awaitClose { registration.remove() }
    }

    override suspend fun registerDevice(
        userId: String,
        deviceId: String,
        deviceName: String,
        platform: DevicePlatform,
        fcmToken: String,
    ): Device {
        requireValidDocumentId(userId, "사용자 ID")
        requireValidDocumentId(deviceId, "기기 ID")
        ensureDeviceSessionActive(userId, deviceId)
        val normalizedName = deviceName.trim()
        require(normalizedName.isNotEmpty()) { "기기 이름을 입력해 주세요." }

        val values = mutableMapOf<String, Any>(
            Fields.PLATFORM to platform.value,
            Fields.DEVICE_NAME to normalizedName,
            Fields.LAST_SEEN_AT to FieldValue.serverTimestamp(),
            Fields.IS_ONLINE to true,
        )
        fcmToken.trim().takeIf(String::isNotBlank)?.let { token ->
            values[Fields.FCM_TOKEN] = token
        }
        devicesCollection(userId).document(deviceId)
            .set(values, SetOptions.merge())
            .awaitResult()
        touchUser(userId)

        return Device(
            id = deviceId,
            platform = platform.value,
            deviceName = normalizedName,
            fcmToken = fcmToken.trim(),
            lastSeenAt = Timestamp.now(),
            isOnline = true,
        )
    }

    override suspend fun getDevice(userId: String, deviceId: String): Device? {
        requireValidDocumentId(userId, "사용자 ID")
        requireValidDocumentId(deviceId, "기기 ID")
        val snapshot = devicesCollection(userId).document(deviceId).get().awaitResult()
        return if (snapshot.exists()) snapshot.toDevice() else null
    }

    override suspend fun heartbeat(userId: String, deviceId: String) {
        requireValidDocumentId(userId, "사용자 ID")
        requireValidDocumentId(deviceId, "기기 ID")
        ensureDeviceSessionActive(userId, deviceId)
        devicesCollection(userId).document(deviceId)
            .set(
                mapOf(
                    Fields.LAST_SEEN_AT to FieldValue.serverTimestamp(),
                    Fields.IS_ONLINE to true,
                ),
                SetOptions.merge(),
            )
            .awaitResult()
        touchUser(userId)
    }

    override suspend fun updateFcmToken(userId: String, deviceId: String, fcmToken: String) {
        requireValidDocumentId(userId, "사용자 ID")
        requireValidDocumentId(deviceId, "기기 ID")
        ensureDeviceSessionActive(userId, deviceId)
        require(fcmToken.isNotBlank()) { "FCM 토큰이 비어 있습니다." }
        devicesCollection(userId).document(deviceId)
            .update(
                mapOf(
                    Fields.FCM_TOKEN to fcmToken.trim(),
                    Fields.LAST_SEEN_AT to FieldValue.serverTimestamp(),
                ),
            )
            .awaitResult()
    }

    override suspend fun markOffline(userId: String, deviceId: String) {
        requireValidDocumentId(userId, "사용자 ID")
        requireValidDocumentId(deviceId, "기기 ID")
        ensureDeviceSessionActive(userId, deviceId)
        devicesCollection(userId).document(deviceId)
            .set(
                mapOf(
                    Fields.IS_ONLINE to false,
                    Fields.LAST_SEEN_AT to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            )
            .awaitResult()
    }

    override suspend fun removeDevice(userId: String, deviceId: String) {
        requireValidDocumentId(userId, "사용자 ID")
        requireValidDocumentId(deviceId, "기기 ID")
        functions
            .getHttpsCallable(Fields.FUNCTION_REVOKE_DEVICE_SESSION)
            .call(mapOf("deviceId" to deviceId))
            .awaitResult()
    }

    private suspend fun ensureDeviceSessionActive(userId: String, deviceId: String) {
        val isRevoked = firestore.collection(Collections.USERS)
            .document(userId)
            .collection(Collections.REVOKED_DEVICES)
            .document(deviceId)
            .get()
            .awaitResult()
            .exists()
        if (isRevoked) throw DeviceSessionRevokedException()
    }

    private suspend fun touchUser(userId: String) {
        firestore.collection(Collections.USERS).document(userId)
            .set(
                mapOf(Fields.LAST_ACTIVE_AT to FieldValue.serverTimestamp()),
                SetOptions.merge(),
            )
            .awaitResult()
    }

    private fun devicesCollection(userId: String) = firestore
        .collection(Collections.USERS)
        .document(userId)
        .collection(Collections.DEVICES)

    private fun DocumentSnapshot.toDevice(): Device = Device(
        id = id,
        platform = getString(Fields.PLATFORM).orEmpty(),
        deviceName = getString(Fields.DEVICE_NAME).orEmpty(),
        fcmToken = getString(Fields.FCM_TOKEN).orEmpty(),
        lastSeenAt = getTimestamp(Fields.LAST_SEEN_AT),
        isOnline = getBoolean(Fields.IS_ONLINE) ?: false,
    )

    private fun requireValidDocumentId(value: String, label: String) {
        require(value.isNotBlank() && '/' !in value) { "$label 형식이 올바르지 않습니다." }
    }

    private object Collections {
        const val USERS = "users"
        const val DEVICES = "devices"
        const val REVOKED_DEVICES = "revokedDevices"
    }

    private object Fields {
        const val PLATFORM = "platform"
        const val DEVICE_NAME = "deviceName"
        const val FCM_TOKEN = "fcmToken"
        const val LAST_SEEN_AT = "lastSeenAt"
        const val IS_ONLINE = "isOnline"
        const val LAST_ACTIVE_AT = "lastActiveAt"
        const val FUNCTION_REVOKE_DEVICE_SESSION = "revokeDeviceSession"
        const val REGION = "asia-northeast3"
    }
}
