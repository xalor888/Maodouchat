package com.maodouchat.network.api

import com.maodouchat.network.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.KSerializer

// 消息操作（星标/置顶/会话删除）：从 ApiEndpointClients 按主题拆出，纯搬移。
internal object ApiMessagingEndpoints {
private suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = ApiService.send(request, serializer)
private suspend fun sendUnit(request: Request): Result<Unit> = ApiService.sendUnit(request)
private suspend fun executeForText(request: Request, errorPrefix: String): Result<String> = ApiService.executeForText(request, errorPrefix)

suspend fun getPublicStatus(): Result<String> = executeForText(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/public/status")
        .get()
        .build(),
    "public_status"
)

suspend fun deleteChat(token: String, chatId: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId").addHeader("Authorization", "Bearer $token").delete().build())

suspend fun toggleStarMessage(token: String, messageId: String): Result<StarMessageResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/messages/$messageId/star").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build(), StarMessageResponse.serializer())

suspend fun getPinnedMessages(token: String, chatId: String): Result<PinnedMessagesListResponse> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/chats/$chatId/pins")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        PinnedMessagesListResponse.serializer()
    )

suspend fun togglePinnedMessage(token: String, chatId: String, messageId: String): Result<TogglePinResponse> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/chats/$chatId/messages/$messageId/pin")
            .addHeader("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody(null))
            .build(),
        TogglePinResponse.serializer()
    )

suspend fun getStarredMessages(token: String, chatId: String?): Result<List<StarredMessageRefDto>> {
    val suffix = chatId?.takeIf { it.isNotBlank() }?.let { "?chatId=${java.net.URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
    return send(Request.Builder().url("${ApiConfig.BASE_URL}/api/messages/starred$suffix").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(StarredMessageRefDto.serializer()))
}
}
