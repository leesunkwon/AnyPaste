package com.kotlinsun.anypaste.model

data class StorageUploadResult(
    val storagePath: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
)

data class StorageTransferProgress(
    val bytesTransferred: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else bytesTransferred.toFloat() / totalBytes

    val percentage: Int
        get() = (fraction * 100).toInt().coerceIn(0, 100)
}
