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
import kotlinx.serialization.json.*

/** Bot 核心·编辑：editMessage。 */
internal fun Route.configureBotCoreEditRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

        post("/api/bot/editMessage") {
            val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post

            if (!com.maodouchat.server.service.RuntimeConfigService.isMessageEditEnabled()) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("message_edit_disabled"))
            }
            val body = call.receiveBoundedTextOrEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val parsed = parseBotEditMessage(obj)
            val messageId = parsed.messageId
            val text = parsed.text
            if (messageId.isBlank() || text.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("messageId/text required"))
            }
            var contentOut = text
            val keyboardRows = parsed.keyboardRows
            val forceReplyFlag = parsed.forceReply
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
            val editedAt = System.currentTimeMillis()
            val edited = runCatching {
                serviceMessageRepo.editOwn(messageId, bot.id, contentOut, editedAt)
            }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("edit failed"))
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, messageId, "editMessage")
            val chatIdForEdit = edited.chatId
            if (!chatIdForEdit.isNullOrBlank()) {
                // 被移出聊天的 bot 不应再向其历史消息广播编辑给当前成员
                if (!conversationParticipantRepo.isParticipant(chatIdForEdit, bot.id)) {
                    call.respond(
            buildJsonObject {
    put("status", "ok")
    put("messageId", messageId)
            }
        )
                    return@post
                }
                fanoutBotEvent(
                    userRepo = userRepo,
                    participantRepository = conversationParticipantRepo,
                    json = json,
                    botId = bot.id,
                    chatId = chatIdForEdit,
                    event = com.maodouchat.server.messaging.v2.ServiceMessagingV2Event(
                        action = "EDIT",
                        targetMessageId = messageId,
                        content = contentOut,
                        editedAt = editedAt,
                    ),
                    messagingV2Repository = messagingV2Repository,
                )
            }
            call.respond(
            buildJsonObject {
    put("status", "ok")
    put("messageId", messageId)
            }
        )
        }
}
