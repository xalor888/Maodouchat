package com.maodouchat.network.api

import com.maodouchat.network.*
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.serialization.KSerializer

// Bot 域：从 ApiEndpointClients 按主题拆出，纯搬移。
internal object ApiBotEndpoints {
private val json get() = ApiService.json
private fun jsonBody(value: String) = value.toRequestBody(ApiService.JSON_MEDIA)
private suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = ApiService.send(request, serializer)
private suspend fun executeForText(request: Request, errorPrefix: String): Result<String> = ApiService.executeForText(request, errorPrefix)

suspend fun listBots(token: String): Result<String> {
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/bots")
        .header("Authorization", "Bearer $token")
        .get()
        .build()
    return executeForText(req, "bots")
}

suspend fun createBot(token: String, name: String, username: String, description: String?): Result<String> {
    val o = org.json.JSONObject()
    o.put("name", name)
    o.put("username", username)
    if (description != null) o.put("description", description)
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/bots")
        .header("Authorization", "Bearer $token")
        .post(o.toString().toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "bot_create")
}

suspend fun setBotWebhook(token: String, botId: String, url: String?): Result<String> {
    val payload = if (url == null) """{"url":null}""" else org.json.JSONObject().put("url", url).toString()
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/bots/$botId/webhook")
        .header("Authorization", "Bearer $token")
        .put(payload.toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "bot_webhook")
}

suspend fun regenerateBotToken(token: String, botId: String): Result<String> {
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/bots/$botId/token")
        .header("Authorization", "Bearer $token")
        .post("{}".toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "bot_token")
}

suspend fun listChatBotCommands(token: String, chatId: String): Result<String> {
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/chats/$chatId/bot-commands")
        .header("Authorization", "Bearer $token")
        .get()
        .build()
    return executeForText(req, "bot_commands")
}

suspend fun postBotInbox(token: String, chatId: String, text: String, botId: String?): Result<String> {
    val payload = org.json.JSONObject().put("text", text).apply {
        if (!botId.isNullOrBlank()) put("botId", botId)
    }.toString()
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/chats/$chatId/bot-inbox")
        .header("Authorization", "Bearer $token")
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "bot_inbox")
}

suspend fun openBotDirectChat(token: String, botId: String): Result<ChatDto> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/bots/$botId/dm")
            .addHeader("Authorization", "Bearer $token")
            .post(jsonBody("{}"))
            .build(),
        ChatDto.serializer()
    )

suspend fun inviteBotToChat(token: String, chatId: String, botId: String): Result<String> {
    val payload = org.json.JSONObject().put("botId", botId).toString()
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/chats/$chatId/bots")
        .header("Authorization", "Bearer $token")
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "bot_invite")
}

suspend fun postBotCallback(
    token: String,
    chatId: String,
    messageId: String,
    botUserId: String,
    callbackData: String
): Result<Boolean> {
    val payload = org.json.JSONObject()
        .put("messageId", messageId)
        .put("botUserId", botUserId)
        .put("callbackData", callbackData)
        .toString()
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/chats/$chatId/bot-callback")
        .header("Authorization", "Bearer $token")
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "bot_callback").map { true }
}

private suspend fun <T> runIoCatching(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}

suspend fun deleteBot(token: String, botId: String): Result<String> {
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/bots/$botId")
        .header("Authorization", "Bearer $token")
        .delete()
        .build()
    return executeForText(req, "bot_delete")
}

suspend fun setBotEnabled(token: String, botId: String, enabled: Boolean): Result<String> {
    val payload = org.json.JSONObject().put("enabled", enabled).toString()
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/bots/$botId/enabled")
        .header("Authorization", "Bearer $token")
        .put(payload.toRequestBody("application/json".toMediaType()))
        .build()
    return executeForText(req, "bot_enabled")
}

/** 活跃公告（含本用户 acked 状态），返回 JSON 字符串由调用方解析。 */

/** 推送 HMAC 校验密钥（经认证通道下发；返回 JSON 字符串由调用方解析，key 为 null 表示未配置）。 */

suspend fun getPushVerifyKey(token: String): Result<String> {
    val req = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/push/verify-key")
        .header("Authorization", "Bearer $token")
        .get()
        .build()
    return executeForText(req, "push_verify_key")
}
}
