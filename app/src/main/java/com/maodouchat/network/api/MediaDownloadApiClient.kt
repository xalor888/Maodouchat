package com.maodouchat.network.api

import com.maodouchat.BuildConfig
import com.maodouchat.util.toHexString
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

internal object MediaDownloadApiClient : MediaDownloadApi {
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
}
