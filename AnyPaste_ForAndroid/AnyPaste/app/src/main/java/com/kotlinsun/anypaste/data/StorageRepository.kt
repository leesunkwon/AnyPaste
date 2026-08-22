package com.kotlinsun.anypaste.data

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.kotlinsun.anypaste.model.StorageTransferProgress
import com.kotlinsun.anypaste.model.StorageUploadResult
import java.io.File

interface StorageRepository {
    suspend fun uploadFile(
        userId: String,
        itemId: String,
        source: Uri,
        fileName: String,
        mimeType: String? = null,
        onProgress: ((StorageTransferProgress) -> Unit)? = null,
    ): StorageUploadResult

    suspend fun uploadBytes(
        userId: String,
        itemId: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String? = null,
        onProgress: ((StorageTransferProgress) -> Unit)? = null,
    ): StorageUploadResult

    suspend fun downloadFile(
        storagePath: String,
        destination: File,
        onProgress: ((StorageTransferProgress) -> Unit)? = null,
    ): File

    suspend fun downloadBytes(
        storagePath: String,
        maxDownloadSizeBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES,
    ): ByteArray

    suspend fun delete(storagePath: String)

    companion object {
        const val DEFAULT_MAX_DOWNLOAD_BYTES = 50L * 1024L * 1024L
    }
}

class FirebaseStorageRepository(
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) : StorageRepository {
    override suspend fun uploadFile(
        userId: String,
        itemId: String,
        source: Uri,
        fileName: String,
        mimeType: String?,
        onProgress: ((StorageTransferProgress) -> Unit)?,
    ): StorageUploadResult {
        val normalizedName = normalizeFileName(fileName)
        val reference = storage.reference.child(storagePath(userId, itemId, normalizedName))
        val uploadTask = reference.putFile(source, metadata(normalizedName, mimeType))
        uploadTask.addOnProgressListener { snapshot ->
            onProgress?.invoke(snapshot.toProgress())
        }
        val snapshot = uploadTask.awaitResult()
        return StorageUploadResult(
            storagePath = reference.path.trimStart('/'),
            fileName = normalizedName,
            fileSize = snapshot.totalByteCount,
            mimeType = snapshot.metadata?.contentType ?: mimeType.orEmpty(),
        )
    }

    override suspend fun uploadBytes(
        userId: String,
        itemId: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String?,
        onProgress: ((StorageTransferProgress) -> Unit)?,
    ): StorageUploadResult {
        require(bytes.isNotEmpty()) { "업로드할 데이터가 비어 있습니다." }
        require(bytes.size.toLong() <= StorageRepository.DEFAULT_MAX_DOWNLOAD_BYTES) {
            "파일은 ${StorageRepository.DEFAULT_MAX_DOWNLOAD_BYTES / 1024L / 1024L}MB 이하여야 합니다."
        }

        val normalizedName = normalizeFileName(fileName)
        val reference = storage.reference.child(storagePath(userId, itemId, normalizedName))
        val uploadTask = reference.putBytes(bytes, metadata(normalizedName, mimeType))
        uploadTask.addOnProgressListener { snapshot ->
            onProgress?.invoke(snapshot.toProgress())
        }
        val snapshot = uploadTask.awaitResult()
        return StorageUploadResult(
            storagePath = reference.path.trimStart('/'),
            fileName = normalizedName,
            fileSize = snapshot.totalByteCount,
            mimeType = snapshot.metadata?.contentType ?: mimeType.orEmpty(),
        )
    }

    override suspend fun downloadFile(
        storagePath: String,
        destination: File,
        onProgress: ((StorageTransferProgress) -> Unit)?,
    ): File {
        val reference = storage.reference.child(normalizeStoragePath(storagePath))
        destination.parentFile?.let { parent ->
            check(parent.exists() || parent.mkdirs()) { "다운로드 폴더를 만들 수 없습니다." }
        }
        val downloadTask = reference.getFile(destination)
        downloadTask.addOnProgressListener { snapshot ->
            onProgress?.invoke(snapshot.toProgress())
        }
        downloadTask.awaitResult()
        return destination
    }

    override suspend fun downloadBytes(
        storagePath: String,
        maxDownloadSizeBytes: Long,
    ): ByteArray {
        require(maxDownloadSizeBytes in 1L..MAX_ALLOWED_DOWNLOAD_BYTES) {
            "다운로드 크기 제한이 올바르지 않습니다."
        }
        return storage.reference
            .child(normalizeStoragePath(storagePath))
            .getBytes(maxDownloadSizeBytes)
            .awaitResult()
    }

    override suspend fun delete(storagePath: String) {
        storage.reference.child(normalizeStoragePath(storagePath)).delete().awaitResult()
    }

    private fun storagePath(userId: String, itemId: String, fileName: String): String {
        requireValidPathComponent(userId, "사용자 ID")
        requireValidPathComponent(itemId, "클립보드 항목 ID")
        return "users/$userId/clipboard/$itemId/$fileName"
    }

    private fun metadata(fileName: String, mimeType: String?): StorageMetadata =
        StorageMetadata.Builder()
            .setContentType(mimeType?.takeIf { it.isNotBlank() } ?: DEFAULT_MIME_TYPE)
            .setCustomMetadata(ORIGINAL_FILE_NAME_METADATA, fileName)
            .build()

    private fun normalizeFileName(fileName: String): String {
        val normalized = fileName.trim()
            .replace('/', '_')
            .replace('\\', '_')
            .take(MAX_FILE_NAME_LENGTH)
        require(normalized.isNotEmpty() && normalized != "." && normalized != "..") {
            "파일 이름이 올바르지 않습니다."
        }
        return normalized
    }

    private fun normalizeStoragePath(storagePath: String): String {
        val normalized = storagePath.trim().trimStart('/')
        require(
            normalized.isNotEmpty() &&
                "//" !in normalized &&
                normalized.split('/').none { it == "." || it == ".." },
        ) { "Storage 경로가 올바르지 않습니다." }
        return normalized
    }

    private fun requireValidPathComponent(value: String, label: String) {
        require(
            value.isNotBlank() && '/' !in value && '\\' !in value && value != "." && value != "..",
        ) { "$label 형식이 올바르지 않습니다." }
    }

    private fun com.google.firebase.storage.UploadTask.TaskSnapshot.toProgress() =
        StorageTransferProgress(
            bytesTransferred = bytesTransferred,
            totalBytes = totalByteCount,
        )

    private fun com.google.firebase.storage.FileDownloadTask.TaskSnapshot.toProgress() =
        StorageTransferProgress(
            bytesTransferred = bytesTransferred,
            totalBytes = totalByteCount,
        )

    private companion object {
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        const val ORIGINAL_FILE_NAME_METADATA = "originalFileName"
        const val MAX_FILE_NAME_LENGTH = 180
        const val MAX_ALLOWED_DOWNLOAD_BYTES = 100L * 1024L * 1024L
    }
}
