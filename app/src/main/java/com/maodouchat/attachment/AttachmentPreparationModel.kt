package com.maodouchat.attachment

import com.maodouchat.data.model.MessageType
import com.maodouchat.util.MediaCache

internal fun normalizeAttachmentMetadata(
    type: MessageType,
    metadata: MediaCache.LocalFileMetadata
): MediaCache.LocalFileMetadata {
    val safeName = sanitizeAttachmentFileName(metadata.fileName)
    val safeSize = metadata.sizeBytes.coerceAtLeast(0L)
    return when (type) {
        MessageType.IMAGE -> typedMetadata(safeName, "jpg", "image/jpeg", safeSize, "image")
        MessageType.GIF -> typedMetadata(safeName, "gif", "image/gif", safeSize, "animation")
        MessageType.VOICE -> MediaCache.LocalFileMetadata("voice.m4a", "audio/mp4", safeSize)
        MessageType.VIDEO -> normalizeVideoMetadata(safeName, metadata.mimeType, safeSize)
        MessageType.FILE -> MediaCache.LocalFileMetadata(
            fileName = safeName,
            mimeType = normalizeMimeType(metadata.mimeType),
            sizeBytes = safeSize
        )
        else -> MediaCache.LocalFileMetadata(safeName, normalizeMimeType(metadata.mimeType), safeSize)
    }
}

private fun normalizeVideoMetadata(fileName: String, declaredMimeType: String, sizeBytes: Long): MediaCache.LocalFileMetadata {
    val declaredMime = normalizeMimeType(declaredMimeType)
    val declaredExtension = VIDEO_MIME_TO_EXTENSION[declaredMime]
    val existingExtension = fileName.substringAfterLast('.', "").lowercase()
        .takeIf(VIDEO_EXTENSION_TO_MIME::containsKey)
    val extension = declaredExtension ?: existingExtension ?: "mp4"
    val mimeType = VIDEO_EXTENSION_TO_MIME.getValue(extension)
    return typedMetadata(fileName, extension, mimeType, sizeBytes, "video")
}

private fun typedMetadata(
    fileName: String,
    extension: String,
    mimeType: String,
    sizeBytes: Long,
    fallbackBaseName: String
): MediaCache.LocalFileMetadata {
    val baseName = fileName.substringBeforeLast('.', fileName).trim().trim('.')
        .ifBlank { fallbackBaseName }
        .take(119 - extension.length)
    return MediaCache.LocalFileMetadata("$baseName.$extension".take(120), mimeType, sizeBytes)
}

private fun sanitizeAttachmentFileName(value: String): String = value
    .replace(UNSAFE_FILE_NAME_CHARS, "_")
    .trim()
    .trim('.')
    .take(120)
    .ifBlank { "file" }

private fun normalizeMimeType(value: String): String {
    val normalized = value.trim().lowercase().take(100)
    return normalized.takeIf { MIME_TYPE.matches(it) } ?: "application/octet-stream"
}

private val VIDEO_EXTENSION_TO_MIME = mapOf(
    "mp4" to "video/mp4",
    "webm" to "video/webm",
    "mov" to "video/quicktime",
    "3gp" to "video/3gpp",
    "mkv" to "video/x-matroska"
)
private val VIDEO_MIME_TO_EXTENSION = VIDEO_EXTENSION_TO_MIME.entries.associate { (extension, mime) -> mime to extension }
private val MIME_TYPE = Regex("^[a-z0-9][a-z0-9!#$&^_.+\\-]*/[a-z0-9][a-z0-9!#$&^_.+\\-]*$")
private val UNSAFE_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
