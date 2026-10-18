package com.maodouchat.network.api

import com.maodouchat.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

internal object MediaUploadApiClient : MediaUploadApi {
private val json get() = ApiService.json
private val JSON_MEDIA get() = ApiService.JSON_MEDIA
private val ATTACHMENT_CHUNK_BYTES get() = ApiService.ATTACHMENT_CHUNK_BYTES
private val ATTACHMENT_CHUNK_MAX_ATTEMPTS get() = ApiService.ATTACHMENT_CHUNK_MAX_ATTEMPTS
private val ATTACHMENT_ID_REGEX get() = ApiService.ATTACHMENT_ID_REGEX
private fun jsonBody(value: String) = value.toRequestBody(ApiService.JSON_MEDIA)
private suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = ApiService.send(request, serializer)
private suspend fun sendUnit(request: Request): Result<Unit> = ApiService.sendUnit(request)

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
            // 服务端进度可能滞后于文件写入：offset==total 时重拉状态自愈，上限 3 次
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
                    // 服务端可能按 messageId 幂等替换上传会话：重新锚定新会话 id 继续传
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
}
