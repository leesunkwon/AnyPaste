package com.kotlinsun.anypaste.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.kotlinsun.anypaste.model.ClipboardItem
import com.kotlinsun.anypaste.model.ClipboardType
import com.kotlinsun.anypaste.model.StorageUploadResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Date

interface ClipboardRepository {
    fun newItemId(userId: String): String
    fun observeClipboard(userId: String, limit: Long = DEFAULT_ITEM_LIMIT): Flow<List<ClipboardItem>>

    suspend fun createText(
        userId: String,
        content: String,
        sourceDeviceId: String,
        targetDeviceId: String = "",
        expiresAt: Timestamp? = null,
    ): ClipboardItem

    suspend fun createBinary(
        userId: String,
        itemId: String,
        type: ClipboardType,
        upload: StorageUploadResult,
        sourceDeviceId: String,
        targetDeviceId: String = "",
        expiresAt: Timestamp? = null,
    ): ClipboardItem

    suspend fun getItem(userId: String, itemId: String): ClipboardItem?
    suspend fun markAsRead(userId: String, itemId: String, deviceId: String)
    suspend fun deleteItem(userId: String, itemId: String)
    suspend fun deleteExpiredItems(userId: String, batchLimit: Long = 100): Int

    companion object {
        const val DEFAULT_ITEM_LIMIT = 50L
    }
}

class FirestoreClipboardRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : ClipboardRepository {
    override fun newItemId(userId: String): String {
        requireValidDocumentId(userId, "사용자 ID")
        return clipboardCollection(userId).document().id
    }

    override fun observeClipboard(userId: String, limit: Long): Flow<List<ClipboardItem>> =
        callbackFlow {
            requireValidDocumentId(userId, "사용자 ID")
            val safeLimit = limit.coerceIn(1L, MAX_ITEM_LIMIT)
            val registration = clipboardCollection(userId)
                .orderBy(Fields.CREATED_AT, Query.Direction.DESCENDING)
                .limit(safeLimit)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        close(error)
                        return@addSnapshotListener
                    }

                    val items = snapshot?.documents.orEmpty().map { it.toClipboardItem() }
                    trySend(items)
                }
            awaitClose { registration.remove() }
        }

    override suspend fun createText(
        userId: String,
        content: String,
        sourceDeviceId: String,
        targetDeviceId: String,
        expiresAt: Timestamp?,
    ): ClipboardItem {
        requireValidDocumentId(userId, "사용자 ID")
        requireValidDocumentId(sourceDeviceId, "기기 ID")
        if (targetDeviceId.isNotBlank()) requireValidDocumentId(targetDeviceId, "대상 기기 ID")
        require(content.isNotEmpty()) { "전송할 텍스트가 비어 있습니다." }
        require(content.length <= MAX_TEXT_CHARACTERS) {
            "텍스트는 ${MAX_TEXT_CHARACTERS}자 이하여야 합니다."
        }

        val document = clipboardCollection(userId).document()
        val createdAt = Timestamp.now()
        val item = ClipboardItem(
            id = document.id,
            type = ClipboardType.TEXT.value,
            content = content,
            sourceDeviceId = sourceDeviceId,
            targetDeviceId = targetDeviceId,
            createdAt = createdAt,
            expiresAt = expiresAt ?: defaultExpiry(),
        )
        document.set(item.toFirestoreMap()).awaitResult()
        return item
    }

    override suspend fun createBinary(
        userId: String,
        itemId: String,
        type: ClipboardType,
        upload: StorageUploadResult,
        sourceDeviceId: String,
        targetDeviceId: String,
        expiresAt: Timestamp?,
    ): ClipboardItem {
        requireValidDocumentId(userId, "사용자 ID")
        requireValidDocumentId(itemId, "클립보드 항목 ID")
        requireValidDocumentId(sourceDeviceId, "기기 ID")
        if (targetDeviceId.isNotBlank()) requireValidDocumentId(targetDeviceId, "대상 기기 ID")
        require(type == ClipboardType.IMAGE || type == ClipboardType.FILE) {
            "바이너리 항목은 image 또는 file 타입이어야 합니다."
        }
        require(upload.storagePath.isNotBlank()) { "Storage 경로가 비어 있습니다." }

        val item = ClipboardItem(
            id = itemId,
            type = type.value,
            storagePath = upload.storagePath,
            fileName = upload.fileName,
            fileSize = upload.fileSize,
            mimeType = upload.mimeType,
            sourceDeviceId = sourceDeviceId,
            targetDeviceId = targetDeviceId,
            createdAt = Timestamp.now(),
            expiresAt = expiresAt ?: defaultExpiry(),
        )
        clipboardCollection(userId).document(itemId)
            .set(item.toFirestoreMap())
            .awaitResult()
        return item
    }

    override suspend fun getItem(userId: String, itemId: String): ClipboardItem? {
        requireValidDocumentId(userId, "사용자 ID")
        requireValidDocumentId(itemId, "클립보드 항목 ID")
        val snapshot = clipboardCollection(userId).document(itemId).get().awaitResult()
        if (!snapshot.exists()) return null
        return snapshot.toClipboardItem()
    }

    override suspend fun markAsRead(userId: String, itemId: String, deviceId: String) {
        requireValidDocumentId(userId, "사용자 ID")
        requireValidDocumentId(itemId, "클립보드 항목 ID")
        requireValidDocumentId(deviceId, "기기 ID")
        clipboardCollection(userId).document(itemId)
            .update(Fields.READ_BY, FieldValue.arrayUnion(deviceId))
            .awaitResult()
    }

    override suspend fun deleteItem(userId: String, itemId: String) {
        requireValidDocumentId(userId, "사용자 ID")
        requireValidDocumentId(itemId, "클립보드 항목 ID")
        clipboardCollection(userId).document(itemId).delete().awaitResult()
    }

    override suspend fun deleteExpiredItems(userId: String, batchLimit: Long): Int {
        requireValidDocumentId(userId, "사용자 ID")
        val snapshot = clipboardCollection(userId)
            .whereLessThanOrEqualTo(Fields.EXPIRES_AT, Timestamp.now())
            .limit(batchLimit.coerceIn(1L, MAX_DELETE_BATCH_SIZE))
            .get()
            .awaitResult()
        if (snapshot.isEmpty) return 0

        val batch = firestore.batch()
        snapshot.documents.forEach { batch.delete(it.reference) }
        batch.commit().awaitResult()
        return snapshot.size()
    }

    private fun clipboardCollection(userId: String) = firestore
        .collection(Collections.USERS)
        .document(userId)
        .collection(Collections.CLIPBOARD)

    private fun com.google.firebase.firestore.DocumentSnapshot.toClipboardItem(): ClipboardItem =
        ClipboardItem(
            id = id,
            type = getString(Fields.TYPE).orEmpty(),
            content = getString(Fields.CONTENT).orEmpty(),
            storagePath = getString(Fields.STORAGE_PATH).orEmpty(),
            fileName = getString(Fields.FILE_NAME).orEmpty(),
            fileSize = getLong(Fields.FILE_SIZE) ?: 0L,
            mimeType = getString(Fields.MIME_TYPE).orEmpty(),
            sourceDeviceId = getString(Fields.SOURCE_DEVICE_ID).orEmpty(),
            targetDeviceId = getString(Fields.TARGET_DEVICE_ID).orEmpty(),
            createdAt = getTimestamp(Fields.CREATED_AT),
            expiresAt = getTimestamp(Fields.EXPIRES_AT),
            readBy = (get(Fields.READ_BY) as? List<*>)
                ?.filterIsInstance<String>()
                .orEmpty(),
        )

    private fun ClipboardItem.toFirestoreMap(): Map<String, Any?> = mapOf(
        Fields.TYPE to type,
        Fields.CONTENT to content,
        Fields.STORAGE_PATH to storagePath,
        Fields.FILE_NAME to fileName,
        Fields.FILE_SIZE to fileSize,
        Fields.MIME_TYPE to mimeType,
        Fields.SOURCE_DEVICE_ID to sourceDeviceId,
        Fields.TARGET_DEVICE_ID to targetDeviceId,
        Fields.CREATED_AT to FieldValue.serverTimestamp(),
        Fields.EXPIRES_AT to expiresAt,
        Fields.READ_BY to readBy,
    )

    private fun defaultExpiry(): Timestamp =
        Timestamp(Date(System.currentTimeMillis() + DEFAULT_TTL_MILLIS))

    private fun requireValidDocumentId(value: String, label: String) {
        require(value.isNotBlank() && '/' !in value) { "$label 형식이 올바르지 않습니다." }
    }

    private object Collections {
        const val USERS = "users"
        const val CLIPBOARD = "clipboard"
    }

    private object Fields {
        const val TYPE = "type"
        const val CONTENT = "content"
        const val STORAGE_PATH = "storagePath"
        const val FILE_NAME = "fileName"
        const val FILE_SIZE = "fileSize"
        const val MIME_TYPE = "mimeType"
        const val SOURCE_DEVICE_ID = "sourceDeviceId"
        const val TARGET_DEVICE_ID = "targetDeviceId"
        const val CREATED_AT = "createdAt"
        const val EXPIRES_AT = "expiresAt"
        const val READ_BY = "readBy"
    }

    private companion object {
        const val MAX_ITEM_LIMIT = 100L
        const val MAX_DELETE_BATCH_SIZE = 500L
        const val MAX_TEXT_CHARACTERS = 100_000
        const val DEFAULT_TTL_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
