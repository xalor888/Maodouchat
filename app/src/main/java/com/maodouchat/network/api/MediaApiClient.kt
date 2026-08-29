package com.maodouchat.network.api

import com.maodouchat.BuildConfig
import com.maodouchat.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

internal object MediaApiClient : MediaApi {
private val json get() = ApiService.json
private val JSON_MEDIA get() = ApiService.JSON_MEDIA
private val ATTACHMENT_CHUNK_BYTES get() = ApiService.ATTACHMENT_CHUNK_BYTES
private val ATTACHMENT_CHUNK_MAX_ATTEMPTS get() = ApiService.ATTACHMENT_CHUNK_MAX_ATTEMPTS
private val ATTACHMENT_ID_REGEX get() = ApiService.ATTACHMENT_ID_REGEX
private val POST_IMAGE_FILENAME_REGEX get() = ApiService.POST_IMAGE_FILENAME_REGEX
private fun jsonBody(value: String) = value.toRequestBody(ApiService.JSON_MEDIA)
private suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = ApiService.send(request, serializer)
private suspend fun sendUnit(request: Request): Result<Unit> = ApiService.sendUnit(request)
private suspend fun executeForText(request: Request, errorPrefix: String): Result<String> = ApiService.executeForText(request, errorPrefix)
private suspend fun executeStreamingWithRefresh(request: Request): Response = ApiService.executeStreamingWithRefresh(request)
private fun parseError(body: String): String? = ApiService.parseError(body)
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private class FileChunkRequestBody(
    private val file: File,
    private val offset: Long,
    private val length: Long,
    private val onProgress: (Long, Long) -> Unit
) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var written = 0L
        RandomAccessFile(file, "r").use { input ->
            input.seek(offset)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (written < length) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - written).toInt())
                if (read < 0) break
                sink.write(buffer, 0, read)
                written += read
                onProgress(offset + written, total)
            }
            require(written == length) { "attachment_chunk_source_changed" }
        }
    }
}


override suspend fun uploadEncryptedAttachment(
    token: String,
    chatId: String,
    messageId: String,
    encryptedFile: File,
    cipherSha256: String,
    onProgress: (Long, Long) -> Unit,
    onCheckpoint: suspend (String, Long, Long) -> Unit): Result<AttachmentUploadResponse> = withContext(Dispatchers.IO) {
    try {
        val total = encryptedFile.length()
        require(total in 17L..(100L * 1024L * 1024L + 64L)) { "attachment_size_invalid" }
        require(encryptedFile.sha256Hex() == cipherSha256.lowercase()) { "attachment_source_hash_mismatch" }
        var status = createAttachmentUploadSessionWithRetry(token, chatId, messageId, cipherSha256, total)
        validateAttachmentUploadStatus(status, cipherSha256, total)
        var attachmentId = status.id
        onCheckpoint(attachmentId, status.uploadedBytes, total)
        onProgress(status.uploadedBytes, total)
        while (!status.complete) {
            currentCoroutineContext().ensureActive()
            val offset = status.uploadedBytes
            // 服务端 DB 进度可能滞后于文件写入（appendChunk 与进度更新乱序）：offset==total
            // 时重新拉取状态自愈（服务端 reconcile 会补齐 UPLOADED），不得当作非法偏移失败；
            // 但自愈轮询设上限，避免服务端异常时忙等
            if (offset >= total) {
                var revalidated: AttachmentUploadStatusResponse? = null
                for (i in 0 until 3) {
                    currentCoroutineContext().ensureActive()
                    val attempt = getAttachmentUploadStatus(token, attachmentId).getOrNull()
                    if (attempt == null) {
                        kotlinx.coroutines.delay(500L)
                        continue
                    }
                    validateAttachmentUploadStatus(attempt, cipherSha256, total)
                    if (attempt.id != attachmentId) attachmentId = attempt.id
                    status = attempt
                    if (status.complete || status.uploadedBytes < total) {
                        revalidated = attempt
                        break
                    }
                    kotlinx.coroutines.delay(500L)
                }
                if (revalidated == null) {
                    throw ApiException(ApiFailureKind.NETWORK, serverMessage = "attachment_status_unavailable")
                }
                if (status.complete) break
                continue
            }
            require(offset >= 0) { "attachment_upload_offset_invalid" }
            val length = minOf(ATTACHMENT_CHUNK_BYTES, total - offset)
            val chunkHash = encryptedFile.sha256Hex(offset, length)
            var lastError: Throwable? = null
            var advanced = false
            for (attempt in 0 until ATTACHMENT_CHUNK_MAX_ATTEMPTS) {
                val chunkResult = uploadAttachmentChunk(
                    token = token,
                    attachmentId = attachmentId,
                    encryptedFile = encryptedFile,
                    offset = offset,
                    length = length,
                    chunkSha256 = chunkHash,
                    onProgress = onProgress
                )
                if (chunkResult.isSuccess) {
                    status = chunkResult.getOrThrow()
                    validateAttachmentUploadStatus(status, cipherSha256, total)
                    // 8.33 修复：服务端按 messageId 幂等可能替换上传会话（verify 路径有
                    // 410 attachment_session_replaced 语义）。此前 require 抛 IllegalArgumentException
                    // 被兜底归为不可重试，传输被永久标记失败。改为重新锚定新会话继续上传
                    // （新会话 uploadedBytes 通常归零，从 0 续传，正确性不受影响）。
                    if (status.id != attachmentId) attachmentId = status.id
                    advanced = status.uploadedBytes > offset || status.complete
                    if (advanced) break
                }
                if (!advanced) {
                    lastError = chunkResult.exceptionOrNull()
                    val recovered = getAttachmentUploadStatus(token, attachmentId).getOrNull()
                    if (recovered != null) {
                        validateAttachmentUploadStatus(recovered, cipherSha256, total)
                        if (recovered.id != attachmentId) attachmentId = recovered.id
                        status = recovered
                        if (status.uploadedBytes > offset || status.complete) {
                            advanced = true
                            break
                        }
                    }
                }
            }
            if (!advanced) throw lastError ?: ApiException(ApiFailureKind.NETWORK, serverMessage = "attachment_chunk_failed")
            onCheckpoint(attachmentId, status.uploadedBytes, total)
            onProgress(status.uploadedBytes, total)
        }
        require(status.uploadedBytes == total) { "attachment_upload_incomplete" }
        Result.success(
            AttachmentUploadResponse(attachmentId, status.cipherSha256, status.cipherSize, status.expiresAt)
        )
    } catch (error: CancellationException) {
        // runCatching/recoverCatching would wrap cancel as Result.failure / UNEXPECTED
        throw error
    } catch (error: ApiException) {
        Result.failure(error)
    } catch (error: java.net.SocketTimeoutException) {
        Result.failure(ApiException(ApiFailureKind.TIMEOUT, cause = error))
    } catch (error: java.io.IOException) {
        Result.failure(ApiException(ApiFailureKind.NETWORK, cause = error))
    } catch (error: Exception) {
        Result.failure(ApiException(ApiFailureKind.UNEXPECTED, cause = error))
    }
}

private suspend fun createAttachmentUploadSession(
    token: String,
    chatId: String,
    messageId: String,
    cipherSha256: String,
    cipherSize: Long
): Result<AttachmentUploadStatusResponse> = send(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/attachment-uploads")
        .addHeader("Authorization", "Bearer $token")
        .post(jsonBody(json.encodeToString(
            AttachmentUploadSessionRequest.serializer(),
            AttachmentUploadSessionRequest(chatId, messageId, cipherSha256, cipherSize)
        )))
        .build(),
    AttachmentUploadStatusResponse.serializer()
)

private suspend fun createAttachmentUploadSessionWithRetry(
    token: String,
    chatId: String,
    messageId: String,
    cipherSha256: String,
    cipherSize: Long
): AttachmentUploadStatusResponse {
    var lastError: Throwable? = null
    repeat(ATTACHMENT_CHUNK_MAX_ATTEMPTS) {
        val result = createAttachmentUploadSession(token, chatId, messageId, cipherSha256, cipherSize)
        if (result.isSuccess) return result.getOrThrow()
        lastError = result.exceptionOrNull()
        val retryable = (lastError as? ApiException)?.kind in setOf(ApiFailureKind.NETWORK, ApiFailureKind.TIMEOUT)
        if (!retryable) throw lastError ?: ApiException(ApiFailureKind.UNEXPECTED)
    }
    throw lastError ?: ApiException(ApiFailureKind.NETWORK, serverMessage = "attachment_session_failed")
}

private suspend fun getAttachmentUploadStatus(token: String, attachmentId: String): Result<AttachmentUploadStatusResponse> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/attachment-uploads/$attachmentId")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        AttachmentUploadStatusResponse.serializer()
    )

override suspend fun verifyEncryptedAttachmentReady(
    token: String,
    chatId: String,
    messageId: String,
    attachmentId: String,
    expectedSha256: String,
    expectedSize: Long
): Result<AttachmentUploadStatusResponse> {
    val current = getAttachmentUploadStatus(token, attachmentId)
    if (current.isFailure) return Result.failure(current.exceptionOrNull()!!)
    return try {
        val status = current.getOrThrow()
        validateAttachmentUploadStatus(status, expectedSha256, expectedSize)
        require(status.complete && status.status in setOf("UPLOADED", "COMMITTED")) { "attachment_upload_incomplete" }
        if (status.status == "COMMITTED") return Result.success(status)

        val refreshed = createAttachmentUploadSession(
            token = token,
            chatId = chatId,
            messageId = messageId,
            cipherSha256 = expectedSha256,
            cipherSize = expectedSize
        ).getOrThrow()
        validateAttachmentUploadStatus(refreshed, expectedSha256, expectedSize)
        if (refreshed.id != attachmentId || !refreshed.complete || refreshed.status != "UPLOADED") {
            throw ApiException(ApiFailureKind.HTTP, 410, "attachment_session_replaced")
        }
        Result.success(refreshed)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
}

private suspend fun uploadAttachmentChunk(
    token: String,
    attachmentId: String,
    encryptedFile: File,
    offset: Long,
    length: Long,
    chunkSha256: String,
    onProgress: (Long, Long) -> Unit
): Result<AttachmentUploadStatusResponse> = send(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/attachment-uploads/$attachmentId?offset=$offset")
        .addHeader("Authorization", "Bearer $token")
        .addHeader("X-Chunk-SHA256", chunkSha256)
        .put(FileChunkRequestBody(encryptedFile, offset, length, onProgress))
        .build(),
    AttachmentUploadStatusResponse.serializer()
)

private fun validateAttachmentUploadStatus(
    status: AttachmentUploadStatusResponse,
    expectedSha256: String,
    expectedSize: Long
) {
    require(status.id.matches(ATTACHMENT_ID_REGEX)) { "attachment_id_invalid" }
    require(status.cipherSha256 == expectedSha256.lowercase()) { "attachment_hash_mismatch" }
    require(status.cipherSize == expectedSize) { "attachment_size_mismatch" }
    require(status.uploadedBytes in 0L..expectedSize) { "attachment_offset_invalid" }
    require(status.status in setOf("UPLOADING", "UPLOADED", "COMMITTED")) { "attachment_status_invalid" }
    require(!status.complete || status.uploadedBytes == expectedSize) { "attachment_completion_invalid" }
}

override suspend fun deleteUncommittedAttachment(token: String, attachmentId: String): Result<Unit> =
    sendUnit(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/attachments/$attachmentId")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
    )

override suspend fun downloadEncryptedAttachment(
    token: String,
    attachmentId: String,
    expectedSha256: String,
    expectedSize: Long,
    target: File,
    onProgress: (Long, Long) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        target.parentFile?.mkdirs()
        if (target.length() > expectedSize) target.delete()
        if (target.length() == expectedSize) {
            if (target.sha256Hex() == expectedSha256.lowercase()) {
                onProgress(expectedSize, expectedSize)
                return@withContext Result.success(Unit)
            }
            target.delete()
        }
        var start = target.length()
        try {
            downloadEncryptedAttachmentPass(token, attachmentId, expectedSha256, expectedSize, target, start, onProgress)
        } catch (error: ApiException) {
            if (start > 0L && error.kind == ApiFailureKind.INVALID_RESPONSE) {
                target.delete()
                start = 0L
                downloadEncryptedAttachmentPass(token, attachmentId, expectedSha256, expectedSize, target, start, onProgress)
            } else {
                throw error
            }
        }
        if (target.length() != expectedSize || target.sha256Hex() != expectedSha256.lowercase()) {
            if (start > 0L) {
                target.delete()
                start = 0L
                downloadEncryptedAttachmentPass(token, attachmentId, expectedSha256, expectedSize, target, start, onProgress)
            }
        }
        if (target.length() != expectedSize || target.sha256Hex() != expectedSha256.lowercase()) {
            target.delete()
            throw ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "attachment_integrity_failed")
        }
        onProgress(expectedSize, expectedSize)
        Result.success(Unit)
    } catch (error: CancellationException) {
        // Must rethrow: recoverCatching previously wrapped cancel as UNEXPECTED failure
        // and UI would show download-failed instead of just clearing spinner.
        // 8.33 修复：取消时清理 .part 残片，避免依赖 48h 兜底清理前滞留 100MB+ 缓存
        runCatching { target.delete() }
        throw error
    } catch (error: ApiException) {
        if (error.kind == ApiFailureKind.INVALID_RESPONSE) target.delete()
        Result.failure(error)
    } catch (error: java.net.SocketTimeoutException) {
        Result.failure(ApiException(ApiFailureKind.TIMEOUT, cause = error))
    } catch (error: java.io.IOException) {
        Result.failure(ApiException(ApiFailureKind.NETWORK, cause = error))
    } catch (error: Exception) {
        Result.failure(ApiException(ApiFailureKind.UNEXPECTED, cause = error))
    }
}

/** 1.127：下载动态图片（认证路由 /api/files/post-image/...）到本地文件。 */

override suspend fun downloadPostImage(token: String, imageUrl: String, target: java.io.File): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        target.parentFile?.mkdirs()
        target.delete()
        val request = Request.Builder()
            .url(imageUrl)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        executeStreamingWithRefresh(request).use { response ->
            if (!response.isSuccessful) {
                throw ApiException(ApiFailureKind.HTTP, response.code, parseError(response.body?.string().orEmpty()))
            }
            val body = response.body ?: throw ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "post_image_body_missing")
            body.byteStream().use { input ->
                java.io.FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        }
        if (!target.exists() || target.length() == 0L) {
            target.delete()
            throw ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "post_image_empty")
        }
        Result.success(Unit)
    } catch (error: CancellationException) {
        runCatching { target.delete() }
        throw error
    } catch (error: Exception) {
        runCatching { target.delete() }
        Result.failure(error)
    }
}

private suspend fun downloadEncryptedAttachmentPass(
    token: String,
    attachmentId: String,
    expectedSha256: String,
    expectedSize: Long,
    target: File,
    requestedStart: Long,
    onProgress: (Long, Long) -> Unit
) {
    val requestBuilder = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/attachments/$attachmentId")
        .addHeader("Authorization", "Bearer $token")
        .get()
    if (requestedStart > 0L) requestBuilder.addHeader("Range", "bytes=$requestedStart-")
    executeStreamingWithRefresh(requestBuilder.build()).use { response ->
        if (!response.isSuccessful) {
            val body = response.body?.string().orEmpty()
            throw ApiException(ApiFailureKind.HTTP, response.code, parseError(body))
        }
        if (response.header("X-Content-SHA256")?.lowercase() != expectedSha256.lowercase()) {
            throw ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "attachment_hash_header_mismatch")
        }
        val append = requestedStart > 0L && response.code == 206
        val actualStart = if (append) requestedStart else 0L
        if (append) {
            val expectedRange = "bytes $requestedStart-${expectedSize - 1}/$expectedSize"
            if (response.header("Content-Range") != expectedRange) {
                throw ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "attachment_range_mismatch")
            }
        }
        val body = response.body ?: throw ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "attachment_body_missing")
        val expectedBodySize = expectedSize - actualStart
        if (body.contentLength() >= 0L && body.contentLength() != expectedBodySize) {
            throw ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "attachment_size_header_mismatch")
        }
        var copied = 0L
        body.byteStream().use { input ->
            FileOutputStream(target, append).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > expectedBodySize) {
                        target.delete()
                        throw ApiException(ApiFailureKind.INVALID_RESPONSE, serverMessage = "attachment_size_exceeded")
                    }
                    output.write(buffer, 0, read)
                    onProgress(actualStart + copied, expectedSize)
                }
            }
        }
        if (copied != expectedBodySize) {
            throw ApiException(ApiFailureKind.NETWORK, serverMessage = "attachment_download_incomplete")
        }
    }
}

private fun File.sha256Hex(): String = sha256Hex(0L, length())

private fun File.sha256Hex(offset: Long, length: Long): String {
    require(offset >= 0L && length >= 0L && offset + length <= this.length())
    val digest = MessageDigest.getInstance("SHA-256")
    RandomAccessFile(this, "r").use { input ->
        input.seek(offset)
        var remaining = length
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            digest.update(buffer, 0, read)
            remaining -= read
        }
        require(remaining == 0L) { "attachment_source_changed" }
    }
    return digest.digest().toHex()
}

override suspend fun uploadPostImage(token: String, base64Data: String): Result<String> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/images").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(UploadPostImageRequest.serializer(), UploadPostImageRequest(base64Data)))).build(), UploadPostImageResponse.serializer()).map { it.imageUrl }

override suspend fun discardPostImage(token: String, imageUrl: String): Result<Unit> {
    val filename = imageUrl.substringAfterLast('/').takeIf { it.matches(POST_IMAGE_FILENAME_REGEX) }
        ?: return Result.failure(IllegalArgumentException("invalid_post_image_url"))
    return sendUnit(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/posts/images/$filename")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
    )
}

// ─── 邮箱验证码 ──────────────────────────

override suspend fun uploadAvatar(token: String, base64Data: String): Result<String> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/avatar").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(UploadAvatarRequest.serializer(), UploadAvatarRequest(base64Data)))).build(), AvatarResponse.serializer()).map { it.avatarUrl }

override suspend fun removeAvatar(token: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/avatar").addHeader("Authorization", "Bearer $token").delete().build())

override suspend fun uploadGroupAvatar(token: String, chatId: String, base64Data: String): Result<String> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/avatar").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(UploadAvatarRequest.serializer(), UploadAvatarRequest(base64Data)))).build(), AvatarResponse.serializer()).map { it.avatarUrl }
}
