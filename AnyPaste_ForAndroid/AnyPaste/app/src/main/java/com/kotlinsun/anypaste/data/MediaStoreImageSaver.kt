package com.kotlinsun.anypaste.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

/** Saves received images as app-owned media so they are immediately visible to gallery apps. */
object MediaStoreImageSaver {
    fun save(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
    ): Uri? {
        if (!source.isFile || !mimeType.startsWith("image/")) return null

        val resolvedName = normalizedDisplayName(displayName, mimeType)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, resolvedName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/AnyPaste",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null

        return try {
            val wrote = resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
                true
            } ?: false
            if (!wrote) throw IOException("사진 저장 스트림을 열 수 없습니다.")
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            uri
        } catch (_: IOException) {
            resolver.delete(uri, null, null)
            null
        } catch (_: SecurityException) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun normalizedDisplayName(name: String, mimeType: String): String {
        val baseName = name.trim()
            .replace(UNSAFE_FILE_NAME, "_")
            .trim('_', ' ', '.')
            .take(MAX_FILE_NAME_LENGTH)
            .ifBlank { "AnyPaste_image" }
        if ('.' in baseName) return baseName
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?.takeIf(String::isNotBlank)
            ?: "jpg"
        return "$baseName.$extension"
    }

    private val UNSAFE_FILE_NAME = Regex("[^a-zA-Z0-9._가-힣-]")
    private const val MAX_FILE_NAME_LENGTH = 180
}
