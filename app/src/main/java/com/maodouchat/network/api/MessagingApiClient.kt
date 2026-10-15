package com.maodouchat.network.api

import com.maodouchat.network.*
import kotlinx.serialization.builtins.serializer
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal object MessagingApiClient : MessagingApi {
private val json get() = ApiService.json
private val JSON_MEDIA get() = ApiService.JSON_MEDIA
private fun jsonBody(value: String) = value.toRequestBody(ApiService.JSON_MEDIA)
private suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = ApiService.send(request, serializer)


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
