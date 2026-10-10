package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import java.util.UUID
import kotlinx.serialization.json.*

/** Bot 核心·发送：sendMessage。 */
internal fun Route.configureBotCoreSendRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {

        post("/api/bot/sendMessage") {
            val bot = call.requireBot() ?: return@post
            // 每 bot 限流：防单 bot 向 200 人群高频广播（WS fanout + FCM push 风暴）
            // 9.138：此前 60/min 与 30/min 两次 acquire 打在同一 limiter/bucket 上——
            // 每次调用烧 2 个 token，60 档完全被 30 档遮蔽且语义混乱；只保留 30/min 档
            if (!botSendRateLimiter.acquire(bot.id, maxPerMinute = 30)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("发送太频繁，请稍后再试"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val req = parseBotSendMessage(
                obj,
                silentSendEnabled = com.maodouchat.server.service.RuntimeConfigService.isSilentSendEnabled(),
            )
            val chatId = req.chatId
            val text = req.text
            val msgType = when {
                req.parseMode == "MARKDOWN" || req.parseMode == "MD" -> "MARKDOWN"
                else -> "TEXT"
            }
            if (msgType == "MARKDOWN" && !com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
            }
            if (chatId.isBlank() || text.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/text required"))
            }
            if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
            }
            // 8.33 修复：与用户发消息一致，广播频道仅创建者可发——bot 若被加入频道
            //（invite 时作为成员加入）不得绕过单向上行约束
            if (conversationParticipantRepo.chatType(chatId) == ChatType.CHANNEL && !conversationParticipantRepo.isChannelOwner(chatId, bot.id)) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("频道为单向广播，仅创建者可发送消息"))
            }
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendMessage")
            // Bots send as system-visible plaintext channel (not E2EE peer).
            val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val now = System.currentTimeMillis()
            var contentOut = if (!req.replyToId.isNullOrBlank()) {
                // Lightweight reply marker for bot plaintext channel (client may ignore).
                text + "\n[replyTo:" + req.replyToId + "]"
            } else text
            val keyboardRows = req.keyboardRows
            val forceReplyFlag = req.forceReply
            if (!keyboardRows.isNullOrEmpty() || forceReplyFlag) {
                val metaObj = kotlinx.serialization.json.buildJsonObject {
                    if (!keyboardRows.isNullOrEmpty()) {
                        put(
                            "inlineKeyboard",
                            kotlinx.serialization.json.JsonArray(
                                keyboardRows.map { row ->
                                    kotlinx.serialization.json.JsonArray(
                                        row.map { btn ->
                                            kotlinx.serialization.json.buildJsonObject {
                                                put("text", kotlinx.serialization.json.JsonPrimitive(btn["text"].orEmpty()))
                                                put("callbackData", kotlinx.serialization.json.JsonPrimitive(btn["callbackData"].orEmpty()))
                                            }
                                        }
                                    )
                                }
                            )
                        )
                    }
                    if (forceReplyFlag) put("forceReply", kotlinx.serialization.json.JsonPrimitive(true))
                }
                contentOut = contentOut + "<meta>" + metaObj.toString() + "</meta>"
            }
            val botMessage = runCatching {
                publishBotServiceMessage(
                    userRepository = userRepo,
                    participantRepository = conversationParticipantRepo,
                    serviceMessageRepository = serviceMessageRepo,
                    json = json,
                    botId = bot.id,
                    chatId = chatId,
                    messageId = msgId,
                    content = contentOut,
                    timestamp = now,
                    type = msgType,
                )
            }.getOrNull()
            if (botMessage == null) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
            }
            com.maodouchat.server.service.BotWebhookService.notifyChatEvent(
                chatId = chatId,
                event = "bot_message",
                messageId = msgId,
                senderId = bot.id,
                type = "TEXT",
                textPreview = text.take(200)
            )
            call.respond(
            buildJsonObject {
    put("ok", true)
    put("messageId", msgId)
            }
        )
        }
}
