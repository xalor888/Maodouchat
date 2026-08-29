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

internal object MessagingApiClient : MessagingApi {
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


override suspend fun sendMessageV2(
    token: String,
    request: SendMessageRequestV2,
): Result<SendMessageResponseV2> = send(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/v2/messages")
        .addHeader("Authorization", "Bearer $token")
        .post(jsonBody(json.encodeToString(SendMessageRequestV2.serializer(), request)))
        .build(),
    SendMessageResponseV2.serializer(),
)

override suspend fun getConversationSnapshotV2(
    token: String,
    conversationId: String,
): Result<ConversationSnapshotV2Dto> = send(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/v2/conversations/${java.net.URLEncoder.encode(conversationId, Charsets.UTF_8.name())}/snapshot")
        .addHeader("Authorization", "Bearer $token")
        .get()
        .build(),
    ConversationSnapshotV2Dto.serializer(),
)

override suspend fun getPendingInboxV2(
    token: String,
    limit: Int,
): Result<PendingInboxResponseV2> = send(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/v2/inbox?limit=${limit.coerceIn(1, 500)}")
        .addHeader("Authorization", "Bearer $token")
        .get()
        .build(),
    PendingInboxResponseV2.serializer(),
)

override suspend fun acknowledgeInboxV2(
    token: String,
    envelopeIds: List<String>,
): Result<AcknowledgeEnvelopesResponseV2> = send(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/v2/inbox/ack")
        .addHeader("Authorization", "Bearer $token")
        .post(
            jsonBody(
                json.encodeToString(
                    AcknowledgeEnvelopesRequestV2.serializer(),
                    AcknowledgeEnvelopesRequestV2(envelopeIds),
                ),
            ),
        )
        .build(),
    AcknowledgeEnvelopesResponseV2.serializer(),
)
}
