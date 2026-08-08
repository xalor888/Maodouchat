package com.maodouchat.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream

/**
 * 本机媒体导出：保存到相册 / 系统分享。
 * 仅读本地 URI；失败返回 false，不抛到 UI。
 */
object MediaExport {
    fun resolveReadableUri(context: Context, raw: String): Uri? {
        if (raw.isBlank()) return null
        if (!MediaCache.isReadableLocalUri(context, raw)) return null
        return runCatching {
            val parsed = Uri.parse(raw)
            if (parsed.scheme == "file") {
                val path = parsed.path ?: return@runCatching null
                val file = File(path)
                if (!file.isFile || !file.canRead()) return@runCatching null
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } else {
                parsed
            }
        }.getOrNull()
    }

    fun share(
        context: Context,
        rawUri: String,
        mimeType: String?,
        chooserTitle: String
    ): Boolean {
        val uri = resolveReadableUri(context, rawUri) ?: return false
        val mime = mimeType?.takeIf { it.isNotBlank() }
            ?: context.contentResolver.getType(uri)
            ?: "application/octet-stream"
        return runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    /**
     * 保存图片/视频到系统相册（MediaStore）。非媒体 MIME 走 Downloads。
     */
    fun saveToGallery(
        context: Context,
        rawUri: String,
        mimeType: String?,
        displayName: String
    ): Boolean {
        val source = resolveReadableUri(context, rawUri) ?: return false
        val mime = mimeType?.takeIf { it.isNotBlank() }
            ?: context.contentResolver.getType(source)
            ?: "application/octet-stream"
        return runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                writeToMediaStore(context, input, mime, displayName)
            } == true
        }.getOrDefault(false)
    }

    /** 将内存 Bitmap 写入相册（PNG）。失败返回 false。 */
    fun saveBitmapToGallery(
        context: Context,
        bitmap: android.graphics.Bitmap,
        displayName: String
    ): Boolean {
        return runCatching {
            val stream = java.io.ByteArrayOutputStream()
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) return@runCatching false
            writeToMediaStore(
                context,
                java.io.ByteArrayInputStream(stream.toByteArray()),
                "image/png",
                displayName.ifBlank { "maodouchat-qr.png" }.let {
                    if (it.endsWith(".png", ignoreCase = true)) it else "$it.png"
                }
            )
        }.getOrDefault(false)
    }

    private fun writeToMediaStore(
        context: Context,
        input: InputStream,
        mime: String,
        displayName: String
    ): Boolean {
        val isVideo = mime.startsWith("video/", ignoreCase = true)
        val isImage = mime.startsWith("image/", ignoreCase = true)
        val collection = when {
            isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            isImage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Files.getContentUri("external")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName.replace('/', '_'))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relative = when {
                    isVideo -> Environment.DIRECTORY_MOVIES + "/Maodouchat"
                    isImage -> Environment.DIRECTORY_PICTURES + "/Maodouchat"
                    else -> Environment.DIRECTORY_DOWNLOADS + "/Maodouchat"
                }
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val outUri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(outUri)?.use { out ->
                input.copyTo(out)
            } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(outUri, values, null, null)
            }
            true
        } catch (_: Exception) {
            runCatching { resolver.delete(outUri, null, null) }
            false
        }
    }
}
