package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.BlobStore
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/** Bot API application adapter. Compatibility-only probes and hint surfaces live separately. */
internal fun Route.configureBotCoreRoutes(
    userRepo: UserRepository,
    starMessageRepo: StarMessageRepository,
    pinnedMessageRepo: PinnedMessageRepository,
    serviceMessageRepo: ServiceMessageRepository,
    groupMembershipService: GroupMembershipService,
    groupProfileRepo: GroupProfileRepository,
    groupModerationRepo: GroupModerationRepository,
    groupInvitationService: GroupInvitationService,
    conversationLifecycleRepo: ConversationLifecycleRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {

    // Bot API uses its own token and must not be nested under user JWT authentication.

    configureBotInfoRoutes(botSendRateLimiter, conversationParticipantRepo)

    post("/api/bot/sendMessage") {
        val bot = call.requireBot() ?: return@post
        // 每 bot 限流：防单 bot 向 200 人群高频广播（WS fanout + FCM push 风暴）
        // 9.138：此前 60/min 与 30/min 两次 acquire 打在同一 limiter/bucket 上——
        // 每次调用烧 2 个 token，60 档完全被 30 档遮蔽且语义混乱；只保留 30/min 档
        if (!botSendRateLimiter.acquire(bot.id, maxPerMinute = 30)) {
            return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("发送太频繁，请稍后再试"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val text = obj["text"]?.jsonPrimitive?.content.orEmpty().take(4000)
        val parseMode = obj["parseMode"]?.jsonPrimitive?.content.orEmpty().uppercase()
        val replyToId = obj["replyToMessageId"]?.jsonPrimitive?.content?.take(80)
        val silentRequested = obj["silent"]?.jsonPrimitive?.booleanOrNull == true
        val silent = silentRequested && com.maodouchat.server.service.RuntimeConfigService.isSilentSendEnabled()
        val replyMarkup = obj["replyMarkup"]?.jsonObject ?: obj["reply_markup"]?.jsonObject
        val inlineKeyboardEl = replyMarkup?.get("inlineKeyboard")
            ?: replyMarkup?.get("inline_keyboard")
        val msgType = when {
            parseMode == "MARKDOWN" || parseMode == "MD" -> "MARKDOWN"
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
        var contentOut = if (!replyToId.isNullOrBlank()) {
            // Lightweight reply marker for bot plaintext channel (client may ignore).
            text + "\n[replyTo:" + replyToId + "]"
        } else text
        val keyboardRows = (inlineKeyboardEl as? kotlinx.serialization.json.JsonArray)?.mapNotNull { rowEl ->
            val row = rowEl as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
            row.mapNotNull { btnEl ->
                val b = btnEl as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val t = b["text"]?.jsonPrimitive?.content.orEmpty().take(64)
                val d = (b["callbackData"] ?: b["callback_data"])?.jsonPrimitive?.content.orEmpty().take(128)
                if (t.isBlank()) null else mapOf("text" to t, "callbackData" to d)
            }.takeIf { it.isNotEmpty() }
        }?.filter { !it.isNullOrEmpty() }?.take(8)
        val forceReplyFlag = run {
            val fr = replyMarkup?.get("forceReply") ?: replyMarkup?.get("force_reply")
            when (fr) {
                is kotlinx.serialization.json.JsonPrimitive -> fr.booleanOrNull == true || fr.content.equals("true", true)
                is kotlinx.serialization.json.JsonObject -> true
                else -> false
            }
        }
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

    configureBotCommandRoutes(botSendRateLimiter)

    configureBotChatActionRoutes(
        userRepo = userRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )

    configureBotWebhookRoutes(botSendRateLimiter)

    configureBotMemberRoutes(
        groupMembershipService = groupMembershipService,
        groupModerationRepo = groupModerationRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )

    configureBotCallbackRoutes(botSendRateLimiter)

    post("/api/bot/editMessage") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post

        if (!com.maodouchat.server.service.RuntimeConfigService.isMessageEditEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("message_edit_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
        val text = obj["text"]?.jsonPrimitive?.content.orEmpty().take(4000)
        val replyMarkup = obj["replyMarkup"]?.jsonObject ?: obj["reply_markup"]?.jsonObject
        val inlineKeyboardEl = replyMarkup?.get("inlineKeyboard")
            ?: replyMarkup?.get("inline_keyboard")
        if (messageId.isBlank() || text.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("messageId/text required"))
        }
        var contentOut = text
        val keyboardRows = (inlineKeyboardEl as? kotlinx.serialization.json.JsonArray)?.mapNotNull { rowEl ->
            val row = rowEl as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
            row.mapNotNull { btnEl ->
                val b = btnEl as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val t = b["text"]?.jsonPrimitive?.content.orEmpty().take(64)
                val d = (b["callbackData"] ?: b["callback_data"])?.jsonPrimitive?.content.orEmpty().take(128)
                if (t.isBlank()) null else mapOf("text" to t, "callbackData" to d)
            }.takeIf { it.isNotEmpty() }
        }?.filter { !it.isNullOrEmpty() }?.take(8)
        val forceReplyFlag = run {
            val fr = replyMarkup?.get("forceReply") ?: replyMarkup?.get("force_reply")
            when (fr) {
                is kotlinx.serialization.json.JsonPrimitive -> fr.booleanOrNull == true || fr.content.equals("true", true)
                is kotlinx.serialization.json.JsonObject -> true
                else -> false
            }
        }
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

    get("/api/bot/getChat") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chatId = call.request.queryParameters["chatId"].orEmpty()
        if (chatId.isBlank()) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        val chat = conversationQueryRepo.getById(chatId)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("chat not found"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val members = conversationParticipantRepo.participantIds(chatId)
        call.respond(
            buildJsonObject {
                put("id", chat.id)
                put("isGroup", chat.isGroup)
                put("title", (chat.groupName ?: ""))
                put("description", (chat.groupAnnouncement ?: ""))
                put("announcement", (chat.groupAnnouncement ?: ""))
                put("memberCount", members.size)
                put("botIsMember", (bot.id in members))
            }
        )
    }

    configureBotChatAdminRoutes(
        groupProfileRepo = groupProfileRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )

    configureBotMessageForwardingRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotChatModerationRoutes(
        conversationLifecycleRepo = conversationLifecycleRepo,
        serviceMessageRepo = serviceMessageRepo,
        userRepo = userRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )
    configureBotProfileRoutes(
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
    )

    configureBotMemberPromotionRoutes(
        groupMembershipService = groupMembershipService,
        groupInvitationService = groupInvitationService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
    )

    configureBotChatInviteRoutes(
        userRepo = userRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        serviceMessageRepo = serviceMessageRepo,
        groupProfileRepo = groupProfileRepo,
        groupInvitationService = groupInvitationService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotGeoRoutes(
        userRepo = userRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        serviceMessageRepo = serviceMessageRepo,
        groupMembershipService = groupMembershipService,
        groupModerationRepo = groupModerationRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotMediaRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        groupMembershipService = groupMembershipService,
        groupModerationRepo = groupModerationRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotMessagingVariantsRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        groupMembershipService = groupMembershipService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotPollEditRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotReactionRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotChatMiscRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        starMessageRepo = starMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotPollQuizRoutes(
        userRepo = userRepo,
        serviceMessageRepo = serviceMessageRepo,
        conversationParticipantRepo = conversationParticipantRepo,
        botSendRateLimiter = botSendRateLimiter,
        json = json,
        messagingV2Repository = messagingV2Repository,
    )

    configureBotPresentationRoutes(
        userRepository = userRepo,
        participantRepository = conversationParticipantRepo,
        serviceMessageRepository = serviceMessageRepo,
        botRateLimiter = botSendRateLimiter,
        json = json,
    )
}
