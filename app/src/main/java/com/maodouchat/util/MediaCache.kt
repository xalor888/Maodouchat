package com.maodouchat.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.maodouchat.data.model.MessageType
import androidx.core.net.toUri
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 媒体消息缓存与轻量转换工具。
 */
object MediaCache {
    private const val MAX_INLINE_MEDIA_BYTES = 1_200_000L
    private const val MAX_INLINE_MEDIA_BASE64_CHARS = 1_600_000
    private const val MAX_CACHE_BYTES = 360L * 1024L * 1024L
    private const val MAX_CACHE_AGE_MS = 21L * 24L * 60L * 60L * 1000L
    private const val CACHE_DIR = "maodouchat_media"
    private const val SECRET_CACHE_DIR = "maodouchat_media_secret"
    private const val FILE_PAYLOAD_KIND = "maodouchat-file-v1"
    private const val ATTACHMENT_PAYLOAD_KIND = "maodouchat-attachment-v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class LocalFileMetadata(
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long
    )

    data class RestoredMedia(
        val uri: String,
        val fileMetadata: LocalFileMetadata? = null,
        val attachmentReference: EncryptedAttachmentReference? = null
    )

    @Serializable
    data class EncryptedAttachmentReference(
        val kind: String = ATTACHMENT_PAYLOAD_KIND,
        val attachmentId: String,
        val keyBase64: String,
        val ivBase64: String,
        val cipherSha256: String,
        val plainSha256: String,
        val cipherSize: Long,
        val fileName: String,
        val mimeType: String,
        val plainSize: Long,
        val durationMs: Long? = null
    )

    @Serializable
    private data class EncryptedFilePayload(
        val kind: String = FILE_PAYLOAD_KIND,
        val dataBase64: String,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long
    )

    fun uriToRawBase64(context: Context, uri: Uri, maxBytes: Long = MAX_INLINE_MEDIA_BYTES): String? {
        return runCatching {
            val resolver = context.contentResolver
            val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (size > maxBytes) return null
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val data = input.readBytes()
                if (data.size.toLong() > maxBytes) return null
                data
            } ?: return null
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.onFailure { Log.w(TAG, "uriToRawBase64 failed", it) }.getOrNull()
    }

    fun describeFile(context: Context, uri: Uri): LocalFileMetadata {
        var displayName: String? = null
        var reportedSize = -1L
        runCatching {
            // Assign before use so lint sees Cursor#close (safe-call ?.use is opaque to Recycle).
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) displayName = it.getString(nameIndex)
                    if (sizeIndex >= 0 && !it.isNull(sizeIndex)) reportedSize = it.getLong(sizeIndex)
                }
            }
        }.onFailure { Log.w(TAG, "Failed to read file metadata", it) }
        if (reportedSize < 0) {
            reportedSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }
        val fallbackName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "file"
        return LocalFileMetadata(
            fileName = sanitizeFileName(displayName ?: fallbackName),
            mimeType = context.contentResolver.getType(uri)?.lowercase()?.take(100) ?: "application/octet-stream",
            sizeBytes = reportedSize.coerceAtLeast(0L)
        )
    }

    fun restoreDecryptedMedia(
        context: Context,
        plaintext: String,
        messageId: String,
        type: MessageType,
        secretChatId: String? = null
    ): RestoredMedia? {
        if (type in setOf(MessageType.FILE, MessageType.IMAGE, MessageType.GIF, MessageType.VIDEO, MessageType.VOICE)) {
            decodeEncryptedAttachmentReference(plaintext)?.let { reference ->
                return RestoredMedia(
                    uri = attachmentUri(reference.attachmentId),
                    fileMetadata = LocalFileMetadata(reference.fileName, reference.mimeType, reference.plainSize),
                    attachmentReference = reference
                )
            }
        }
        val payload = if (type == MessageType.FILE) decodeEncryptedFilePayload(plaintext) else null
        val base64 = payload?.dataBase64 ?: plaintext
        val metadata = payload?.let {
            LocalFileMetadata(sanitizeFileName(it.fileName), it.mimeType.lowercase().take(100), it.sizeBytes)
        }
        val uri = writeBase64ToCache(context, base64, messageId, type, metadata?.fileName, secretChatId) ?: return null
        return RestoredMedia(uri, metadata)
    }

    fun encodeEncryptedAttachmentReference(reference: EncryptedAttachmentReference): String {
        if (!isValidAttachmentReference(reference)) {
            throw AttachmentCryptoException(AttachmentCryptoFailure.INVALID_REFERENCE)
        }
        return json.encodeToString(EncryptedAttachmentReference.serializer(), reference.copy(
            fileName = sanitizeFileName(reference.fileName),
            mimeType = reference.mimeType.lowercase().take(100)
        ))
    }

    fun decodeEncryptedAttachmentReference(text: String): EncryptedAttachmentReference? {
        if (!text.startsWith('{') || text.length > 2_048) return null
        return runCatching { json.decodeFromString(EncryptedAttachmentReference.serializer(), text) }
            .getOrNull()
            ?.takeIf(::isValidAttachmentReference)
            ?.let { it.copy(fileName = sanitizeFileName(it.fileName), mimeType = it.mimeType.lowercase().take(100)) }
    }

    fun attachmentUri(attachmentId: String): String = "maodou-attachment://$attachmentId"

    fun isRemoteAttachmentUri(value: String): Boolean = value.startsWith("maodou-attachment://")

    fun isReadableLocalUri(context: Context, value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        when (uri.scheme?.lowercase()) {
            "file" -> File(uri.path ?: return@runCatching false).let { it.isFile && it.length() > 0L }
            "content", "android.resource" -> context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                it.length != 0L
            } == true
            else -> false
        }
    }.getOrDefault(false)

    fun releasePersistableReadPermission(context: Context, value: String) {
        runCatching {
            val uri = Uri.parse(value)
            if (uri.scheme == "content") {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    fun writeBase64ToCache(
        context: Context,
        base64: String,
        messageId: String,
        type: MessageType,
        originalFileName: String? = null,
        secretChatId: String? = null
    ): String? {
        return runCatching {
            if (base64.length > MAX_INLINE_MEDIA_BASE64_CHARS) {
                Log.w(TAG, "Base64 media too large: ${base64.length} chars")
                return null
            }
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            if (bytes.size.toLong() > MAX_INLINE_MEDIA_BYTES) {
                Log.w(TAG, "Decoded media too large: ${bytes.size} bytes")
                return null
            }
            val dir = if (!secretChatId.isNullOrBlank()) {
                secretChatDir(context, secretChatId)?.apply { mkdirs() } ?: return@runCatching null
            } else {
                File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
            }
            cleanup(context)
            val safeId = messageId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = File(dir, "$safeId${type.extension(originalFileName)}")
            file.writeBytes(bytes)
            cleanup(context)
            file.toUri().toString()
        }.onFailure { Log.w(TAG, "writeBase64ToCache failed", it) }.getOrNull()
    }

    fun copyFileToCache(
        context: Context,
        source: Uri,
        messageId: String,
        metadata: LocalFileMetadata
    ): String? = runCatching {
        require(metadata.sizeBytes in 1L..MAX_ATTACHMENT_PLAIN_BYTES)
        val target = createAttachmentCacheFile(context, messageId, metadata.fileName)
        if (source.scheme == "file") {
            val sourceFile = source.path?.let(::File)
            if (
                sourceFile != null && sourceFile.isFile &&
                sourceFile.canonicalPath == target.canonicalPath &&
                sourceFile.length() == metadata.sizeBytes
            ) {
                return@runCatching target.toUri().toString()
            }
        }
        var copied = 0L
        context.contentResolver.openInputStream(source)?.use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    require(copied <= MAX_ATTACHMENT_PLAIN_BYTES)
                    output.write(buffer, 0, read)
                }
            }
        } ?: return null
        require(copied == metadata.sizeBytes)
        cleanup(context)
        target.toUri().toString()
    }.onFailure { Log.w(TAG, "copyFileToCache failed", it) }.getOrNull()

    fun createAttachmentCacheFile(context: Context, messageId: String, fileName: String, secretChatId: String? = null): File {
        val dir = if (!secretChatId.isNullOrBlank()) {
            secretChatDir(context, secretChatId)?.apply { mkdirs() }
                ?: throw IllegalArgumentException("invalid secret chat id")
        } else {
            File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        }
        val safeId = messageId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val extension = MessageType.FILE.extension(fileName)
        val target = File(dir, "$safeId$extension")
        require(target.canonicalPath.startsWith(dir.canonicalPath + File.separator))
        return target
    }

    fun createEncryptedDownloadFile(context: Context, attachmentId: String, discriminator: String = ""): File {
        val dir = File(context.cacheDir, "attachment-downloads").apply { mkdirs() }
        val safeId = attachmentId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        // 8.60：discriminator（messageId）避免同 attachmentId 的并发下载共用 .part 互相截断/删除损坏
        val safeDisc = discriminator.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val name = if (safeDisc.isBlank()) "$safeId.part" else "${safeId}_$safeDisc.part"
        val target = File(dir, name)
        require(target.canonicalPath.startsWith(dir.canonicalPath + File.separator))
        return target
    }

    fun createPreparedAttachmentSource(context: Context, messageId: String, extension: String): File {
        require(extension.matches(Regex("^\\.[a-z0-9]{1,10}$")))
        val dir = File(context.cacheDir, "attachment-sources").apply { mkdirs() }
        val safeId = messageId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val target = File(dir, "$safeId$extension")
        require(target.canonicalPath.startsWith(dir.canonicalPath + File.separator))
        return target
    }

    fun deletePreparedAttachmentSource(context: Context, sourceUri: String): Boolean = runCatching {
        val file = preparedAttachmentSourceFile(context, sourceUri) ?: return@runCatching false
        file.delete() || !file.exists()
    }.getOrDefault(false)

    fun preparedAttachmentSourceFile(context: Context, sourceUri: String): File? = runCatching {
        val uri = Uri.parse(sourceUri)
        if (uri.scheme != "file") return@runCatching null
        val file = File(uri.path ?: return@runCatching null)
        val root = File(context.cacheDir, "attachment-sources").canonicalPath + File.separator
        file.takeIf { it.canonicalPath.startsWith(root) }
    }.getOrNull()

    fun deleteCachedMediaForMessage(context: Context, messageId: String): Boolean = runCatching {
        val dir = File(context.cacheDir, CACHE_DIR)
        val safeId = messageId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val files = dir.listFiles()?.filter { file ->
            file.isFile && file.name.substringBeforeLast('.', file.name) == safeId
        }.orEmpty()
        files.all { it.delete() || !it.exists() }
    }.onFailure { Log.w(TAG, "deleteCachedMediaForMessage failed", it) }.getOrDefault(false)

    /**
     * 擦除某个密聊会话的全部解密明文缓存。密聊媒体解密码后写入隔离目录
     * [SECRET_CACHE_DIR]/<chatId>/，退出/锁定密聊时整目录删除，避免明文在共享
     * [CACHE_DIR] 中长期留存（F5 修复）。
     */
    fun deleteSecretChatMedia(context: Context, chatId: String): Boolean = runCatching {
        val dir = secretChatDir(context, chatId) ?: return@runCatching true
        if (!dir.exists()) return@runCatching true
        dir.deleteRecursively()
    }.onFailure { Log.w(TAG, "deleteSecretChatMedia failed", it) }.getOrDefault(false)

    /**
     * 8.40：整目录擦除全部密聊明文缓存（登出/删号/重建存储兜底）。
     * 此前只按「当前活跃 surface」删单会话，进程被杀后 activeSurfaceChatIds 为空、
     * 残留明文媒体既不被登出清理也无年龄兜底，可无限期留在磁盘。
     */
    fun deleteAllSecretChatMedia(context: Context): Boolean = runCatching {
        val root = File(context.cacheDir, SECRET_CACHE_DIR)
        if (root.exists()) root.deleteRecursively()
        true
    }.onFailure { Log.w(TAG, "deleteAllSecretChatMedia failed", it) }.getOrDefault(false)

    /** 解析密聊缓存会话目录，拒绝路径分隔符 / 空白 / . / ..，并做 canonical 越界校验。 */
    private fun secretChatDir(context: Context, chatId: String): File? {
        if (chatId.isBlank() || chatId == "." || chatId == ".." ||
            chatId.any { it.isWhitespace() || it.isISOControl() || it == '/' || it == '\\' }
        ) {
            return null
        }
        val root = File(context.cacheDir, SECRET_CACHE_DIR).canonicalFile
        val dir = File(root, chatId).canonicalFile
        return dir.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    fun cleanup(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, CACHE_DIR)
            val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
            val now = System.currentTimeMillis()
            files.filter { now - it.lastModified() > MAX_CACHE_AGE_MS }.forEach { it.delete() }

            val remaining = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
            var totalBytes = 0L
            remaining.forEach { file ->
                totalBytes += file.length()
                if (totalBytes > MAX_CACHE_BYTES) file.delete()
            }
            cleanupTransferDirectory(File(context.cacheDir, "attachment-downloads"), now)
            // 上传源文件/密文暂存也做年龄兜底：reconcile 之外的孤儿（崩溃残留、未完成的
            // 多设备上传）不应长期占用空间，48h 后由这里清掉。
            cleanupTransferDirectory(File(context.cacheDir, "attachment-uploads"), now)
            cleanupTransferDirectory(File(context.cacheDir, "attachment-sources"), now)
        }.onFailure { Log.w(TAG, "cleanup failed", it) }
    }

    private fun cleanupTransferDirectory(directory: File, now: Long) {
        directory.listFiles()
            ?.filter { it.isFile && now - it.lastModified() > MAX_TRANSFER_CACHE_AGE_MS }
            ?.forEach { it.delete() }
    }

    /** 仅返回当前缓存总字节，不删除任何文件 */
    fun currentCacheBytes(context: Context): Long = runCatching {
        cacheDirectories(context).sumOf { directory ->
            directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }
    }.getOrDefault(0L)

    /** 清空应用生成的媒体和附件传输缓存，返回实际删除的字节数。 */
    fun cleanupReturningBytes(context: Context): Long = runCatching {
        var removedBytes = 0L
        cacheDirectories(context).forEach { directory ->
            directory.listFiles()?.filter { it.isFile }?.forEach { file ->
                val size = file.length()
                if (file.delete() || !file.exists()) removedBytes += size
            }
        }
        removedBytes
    }.getOrDefault(0L)

    private fun cacheDirectories(context: Context): List<File> = listOf(
        File(context.cacheDir, CACHE_DIR),
        File(context.cacheDir, "attachment-downloads"),
        File(context.cacheDir, "attachment-uploads"),
        File(context.cacheDir, "attachment-sources")
    )

    private const val TAG = "MediaCache"

    private fun decodeEncryptedFilePayload(text: String): EncryptedFilePayload? {
        if (!text.startsWith('{') || text.length > MAX_INLINE_MEDIA_BASE64_CHARS + 1_024) return null
        return runCatching { json.decodeFromString(EncryptedFilePayload.serializer(), text) }
            .getOrNull()
            ?.takeIf {
                it.kind == FILE_PAYLOAD_KIND &&
                    it.dataBase64.isNotBlank() &&
                    it.dataBase64.length <= MAX_INLINE_MEDIA_BASE64_CHARS &&
                    it.sizeBytes in 0L..MAX_INLINE_MEDIA_BYTES
            }
    }

    private fun isValidAttachmentReference(reference: EncryptedAttachmentReference): Boolean {
        return reference.kind == ATTACHMENT_PAYLOAD_KIND &&
            reference.attachmentId.matches(Regex("^att_[A-Za-z0-9_-]{20,100}$")) &&
            reference.keyBase64.length in 40..48 &&
            reference.ivBase64.length in 16..24 &&
            reference.cipherSha256.matches(Regex("^[a-f0-9]{64}$")) &&
            reference.plainSha256.matches(Regex("^[a-f0-9]{64}$")) &&
            reference.cipherSize in 17L..MAX_ATTACHMENT_CIPHER_BYTES &&
            reference.plainSize in 1L..MAX_ATTACHMENT_PLAIN_BYTES &&
            (reference.durationMs == null || reference.durationMs in 500L..MAX_VOICE_DURATION_MS) &&
            reference.fileName.isNotBlank() && reference.fileName.length <= 120 &&
            reference.mimeType.isNotBlank() && reference.mimeType.length <= 100
    }

    private fun sanitizeFileName(value: String): String {
        return value
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .trim('.')
            .take(120)
            .ifBlank { "file" }
    }

    private fun MessageType.extension(originalFileName: String?): String = when (this) {
        MessageType.IMAGE -> ".jpg"
        MessageType.GIF -> ".gif"
        MessageType.VIDEO -> ".mp4"
        MessageType.VOICE -> ".m4a"
        MessageType.FILE -> originalFileName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
            ?.let { ".$it" }
            ?: ".bin"
        else -> ".bin"
    }

    const val MAX_ATTACHMENT_PLAIN_BYTES = 100L * 1024L * 1024L
    const val MAX_ATTACHMENT_CIPHER_BYTES = MAX_ATTACHMENT_PLAIN_BYTES + 64L
    const val MAX_VOICE_DURATION_MS = 60L * 60L * 1_000L
    private const val MAX_TRANSFER_CACHE_AGE_MS = 48L * 60L * 60L * 1_000L
}
