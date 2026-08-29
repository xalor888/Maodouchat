package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.EncryptedAttachmentStorage
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
    groupMembershipRepo: GroupMembershipRepository,
    groupLifecycleService: GroupLifecycleService,
    groupProfileRepo: GroupProfileRepository,
    groupModerationRepo: GroupModerationRepository,
    groupInvitationRepo: GroupInvitationRepository,
    conversationLifecycleRepo: ConversationLifecycleRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {

    // Bot API uses its own token and must not be nested under user JWT authentication.


    get("/api/bot/webhookInfo") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val pending = com.maodouchat.server.repository.BotRepository.countPendingUpdates(bot.id)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "webhookInfo")
        call.respond(
        buildJsonObject {
put("url", (bot.webhookUrl ?: ""))
put("hasCustomCertificate", false)
put("pendingUpdateCount", pending)
put("signed", true)
put("signatureHeader", "X-Maodouchat-Signature")
put("maxConnections", 40)
        }
    )
    }

    get("/api/bot/me") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val commands = com.maodouchat.server.repository.BotRepository.getMyCommands(bot.id)
        call.respond(
            buildJsonObject {
                put("id", bot.id)
                put("name", bot.name)
                put("username", bot.username)
                put("description", (bot.description ?: ""))
                put("enabled", bot.enabled)
                put("webhookUrl", (bot.webhookUrl ?: ""))
                putJsonArray("commands") {
                    commands.forEach { c -> add(buildJsonObject { put("command", c.command); put("description", c.description) }) }
                }
                put("canJoinGroups", true)
                put("supportsInline", false)
                put("canSendDocuments", com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled())
                put("canSendVoice", (com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled() && com.maodouchat.server.service.RuntimeConfigService.isVoiceMessagesEnabled()))
                put("canReadGroupHistory", true)
                put("mediaUploadEnabled", com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled())
                put("groupPlayEnabled", com.maodouchat.server.service.RuntimeConfigService.isGroupPlayEnabled())
                put("callsEnabled", com.maodouchat.server.service.RuntimeConfigService.isCallsEnabled())
                put("scheduledMessagesEnabled", com.maodouchat.server.service.RuntimeConfigService.isScheduledMessagesEnabled())
                put("viewOnceEnabled", com.maodouchat.server.service.RuntimeConfigService.isViewOnceEnabled())
                put("liveLocationEnabled", com.maodouchat.server.service.RuntimeConfigService.isLiveLocationEnabled())
                put("markdownEnabled", com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled())
                put("typingIndicatorsEnabled", com.maodouchat.server.service.RuntimeConfigService.isTypingIndicatorsEnabled())
                put("readReceiptsEnabled", com.maodouchat.server.service.RuntimeConfigService.isReadReceiptsEnabled())
                put("presenceEnabled", com.maodouchat.server.service.RuntimeConfigService.isPresenceEnabled())
                put("messageStarringEnabled", com.maodouchat.server.service.RuntimeConfigService.isMessageStarringEnabled())
                put("chatExportEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatExportEnabled())
                put("messageForwardingEnabled", com.maodouchat.server.service.RuntimeConfigService.isMessageForwardingEnabled())
                put("globalSearchEnabled", com.maodouchat.server.service.RuntimeConfigService.isGlobalSearchEnabled())
                put("friendRequestsEnabled", com.maodouchat.server.service.RuntimeConfigService.isFriendRequestsEnabled())
                put("chatFoldersEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatFoldersEnabled())
                put("postsEnabled", com.maodouchat.server.service.RuntimeConfigService.isPostsEnabled())
                put("blockReportEnabled", com.maodouchat.server.service.RuntimeConfigService.isBlockReportEnabled())
                put("chatArchiveEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatArchiveEnabled())
                put("nearbyEnabled", com.maodouchat.server.service.RuntimeConfigService.isNearbyEnabled())
                put("chatPinEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatPinEnabled())
                put("markedUnreadEnabled", com.maodouchat.server.service.RuntimeConfigService.isMarkedUnreadEnabled())
                put("chatMuteEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatMuteEnabled())
                put("disappearingMessagesEnabled", com.maodouchat.server.service.RuntimeConfigService.isDisappearingMessagesEnabled())
                put("chatLockEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatLockEnabled())
                put("messageEditEnabled", com.maodouchat.server.service.RuntimeConfigService.isMessageEditEnabled())
                put("messagePinEnabled", com.maodouchat.server.service.RuntimeConfigService.isMessagePinEnabled())
                put("messageRevokeEnabled", com.maodouchat.server.service.RuntimeConfigService.isMessageRevokeEnabled())
                put("pollsEnabled", com.maodouchat.server.service.RuntimeConfigService.isPollsEnabled())
                put("appLockEnabled", com.maodouchat.server.service.RuntimeConfigService.isAppLockEnabled())
                put("chatDraftsEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatDraftsEnabled())
                put("groupInvitesEnabled", com.maodouchat.server.service.RuntimeConfigService.isGroupInvitesEnabled())
                put("mentionsEnabled", com.maodouchat.server.service.RuntimeConfigService.isMentionsEnabled())
                put("nudgeEnabled", com.maodouchat.server.service.RuntimeConfigService.isNudgeEnabled())
                put("safetyCodeEnabled", com.maodouchat.server.service.RuntimeConfigService.isSafetyCodeEnabled())
                put("qrCodeEnabled", com.maodouchat.server.service.RuntimeConfigService.isQrCodeEnabled())
                put("contactCardEnabled", com.maodouchat.server.service.RuntimeConfigService.isContactCardEnabled())
                put("spoilerMediaEnabled", com.maodouchat.server.service.RuntimeConfigService.isSpoilerMediaEnabled())
                put("autoDownloadEnabled", com.maodouchat.server.service.RuntimeConfigService.isAutoDownloadEnabled())
                put("staticLocationEnabled", com.maodouchat.server.service.RuntimeConfigService.isStaticLocationEnabled())
                put("fileShareEnabled", com.maodouchat.server.service.RuntimeConfigService.isFileShareEnabled())
                put("secretChatEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretChatEnabled())
                put("screenSecureRuntimeEnabled", com.maodouchat.server.service.RuntimeConfigService.isScreenSecureRuntimeEnabled())
                put("imageSendEnabled", com.maodouchat.server.service.RuntimeConfigService.isImageSendEnabled())
                put("videoSendEnabled", com.maodouchat.server.service.RuntimeConfigService.isVideoSendEnabled())
                put("gifSendEnabled", com.maodouchat.server.service.RuntimeConfigService.isGifSendEnabled())
                put("blindWatermarkEnabled", com.maodouchat.server.service.RuntimeConfigService.isBlindWatermarkEnabled())
                put("voiceCallEnabled", com.maodouchat.server.service.RuntimeConfigService.isVoiceCallEnabled())
                put("videoCallEnabled", com.maodouchat.server.service.RuntimeConfigService.isVideoCallEnabled())
                put("chatWallpaperEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatWallpaperEnabled())
                put("chatFontScaleEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatFontScaleEnabled())
                put("unreadPriorityEnabled", com.maodouchat.server.service.RuntimeConfigService.isUnreadPriorityEnabled())
                put("ringtoneEnabled", com.maodouchat.server.service.RuntimeConfigService.isRingtoneEnabled())
                put("notificationSoundEnabled", com.maodouchat.server.service.RuntimeConfigService.isNotificationSoundEnabled())
                put("notificationPreviewEnabled", com.maodouchat.server.service.RuntimeConfigService.isNotificationPreviewEnabled())
                put("pushNotificationsEnabled", com.maodouchat.server.service.RuntimeConfigService.isPushNotificationsEnabled())
                put("taskRemindersEnabled", com.maodouchat.server.service.RuntimeConfigService.isTaskRemindersEnabled())
                put("dndEnabled", com.maodouchat.server.service.RuntimeConfigService.isDndEnabled())
                put("inAppSoundsEnabled", com.maodouchat.server.service.RuntimeConfigService.isInAppSoundsEnabled())
                put("hapticsEnabled", com.maodouchat.server.service.RuntimeConfigService.isHapticsEnabled())
                put("chatAnimationsEnabled", com.maodouchat.server.service.RuntimeConfigService.isChatAnimationsEnabled())
                put("navTransitionsEnabled", com.maodouchat.server.service.RuntimeConfigService.isNavTransitionsEnabled())
                put("screenshotDetectEnabled", com.maodouchat.server.service.RuntimeConfigService.isScreenshotDetectEnabled())
                put("recentsExclusionEnabled", com.maodouchat.server.service.RuntimeConfigService.isRecentsExclusionEnabled())
                put("secretCopyBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretCopyBlockEnabled())
                put("secretMediaExportBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretMediaExportBlockEnabled())
                put("secretForwardBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretForwardBlockEnabled())
                put("secretChatExportBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretChatExportBlockEnabled())
                put("secretAutoDisappearEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretAutoDisappearEnabled())
                put("secretLinkPreviewBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretLinkPreviewBlockEnabled())
                put("secretExternalLinkBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretExternalLinkBlockEnabled())
                put("secretNotifPreviewBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretNotifPreviewBlockEnabled())
                put("secretListPreviewBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretListPreviewBlockEnabled())
                put("secretReactionBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretReactionBlockEnabled())
                put("secretStarBlockEnabled", com.maodouchat.server.service.RuntimeConfigService.isSecretStarBlockEnabled())
                put("reactionsEnabled", com.maodouchat.server.service.RuntimeConfigService.isReactionsEnabled())
                put("stickersEnabled", com.maodouchat.server.service.RuntimeConfigService.isStickersEnabled())
                put("silentSendEnabled", com.maodouchat.server.service.RuntimeConfigService.isSilentSendEnabled())
        }
    )
    }

    get("/api/bot/chats") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chats = org.jetbrains.exposed.sql.transactions.transaction {
            com.maodouchat.server.db.ChatParticipants.selectAll()
                .where { com.maodouchat.server.db.ChatParticipants.userId eq bot.id }
                .map { it[com.maodouchat.server.db.ChatParticipants.chatId] }
                .distinct()
        }
        call.respond(
        buildJsonObject {
put("chatIds", Json.parseToJsonElement(Json.encodeToString(chats)))
put("count", chats.size)
        }
    )
    }

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


    get("/api/bot/getMyCommands") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val commands = com.maodouchat.server.repository.BotRepository.getMyCommands(bot.id)
        call.respond(
        buildJsonObject {
put("commands", Json.parseToJsonElement(Json.encodeToString(commands)))
put("count", commands.size)
        }
    )
    }

    post("/api/bot/setMyCommands") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val arr = obj["commands"] as? kotlinx.serialization.json.JsonArray
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("commands array required"))
        val defs = arr.mapNotNull { item ->
            val o = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val command = o["command"]?.jsonPrimitive?.content.orEmpty()
            val description = o["description"]?.jsonPrimitive?.content.orEmpty()
            if (command.isBlank() || description.isBlank()) null
            else com.maodouchat.server.repository.BotRepository.BotCommandDef(
                command = command,
                description = description
            )
        }
        val normalized = com.maodouchat.server.repository.BotRepository.normalizeCommands(defs)
            ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid commands (max 100, unique a-z0-9_, description required)")
            )
        val saved = com.maodouchat.server.repository.BotRepository.setMyCommands(bot.id, normalized)
            ?: return@post call.respondBotUnavailable()
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "setMyCommands")
        call.respond(
        buildJsonObject {
put("ok", true)
put("commands", Json.parseToJsonElement(Json.encodeToString(saved)))
put("count", saved.size)
        }
    )
    }




    post("/api/bot/sendChatAction") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val action = obj["action"]?.jsonPrimitive?.content.orEmpty().lowercase().ifBlank { "typing" }
        if (chatId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val isTyping = action in setOf("typing", "upload_photo", "record_video", "upload_video", "record_voice", "upload_document", "find_location")
        val payload = json.encodeToString(
            WsMessage(
                "USER_TYPING",
                json.encodeToString(TypingPayload(bot.id, chatId, isTyping))
            )
        )
        try {
            // 9.124 补：typing 侧信道同样过滤拉黑 bot 的接收方（与用户 WS TYPING 双向拉黑过滤一致）
            val typingPids = conversationParticipantRepo.participantIds(chatId).filter { it != bot.id }
            val botBlockedIds = try { userRepo.blockedEitherWayIdsInTx(bot.id, typingPids) } catch (_: Exception) { emptySet() }
            typingPids.forEach { pid ->
                if (pid in botBlockedIds) return@forEach
                sendToUser(pid, payload)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendChatAction:$action")
        call.respond(
        buildJsonObject {
put("ok", true)
put("action", action)
        }
    )
    }


    post("/api/bot/pinChatMessage") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }

        if (!com.maodouchat.server.service.RuntimeConfigService.isMessagePinEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("message_pin_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank() || messageId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/messageId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val chat = conversationQueryRepo.getById(chatId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("chat not found"))
        // Bots act as managers for pin in groups where they are members; 1:1 always ok.
        // 修复：此前恒 true 使普通成员 bot 也可钉任意消息；与 unpinChatMessage 的
        // isOwnerOrAdmin 口径对齐（当前 bot 入群即 ADMIN，防御未来成员角色变化）。
        val actorIsManager = if (chat.isGroup) {
            conversationParticipantRepo.isOwnerOrAdmin(chatId, bot.id)
        } else {
            true
        }
        val outcome = pinnedMessageRepo.toggle(
            chatId = chatId,
            messageId = messageId,
            actorId = bot.id,
            actorIsManager = actorIsManager,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "pinChatMessage")
        when (outcome.result) {
            com.maodouchat.server.repository.PinnedMessageRepository.PinResult.PINNED,
            com.maodouchat.server.repository.PinnedMessageRepository.PinResult.UNPINNED -> {
                val pinned = outcome.result == com.maodouchat.server.repository.PinnedMessageRepository.PinResult.PINNED
                val payload = PinnedMessagesUpdatedPayload(chatId, bot.id, outcome.pins)
                val pinJson = json.encodeToString(
                    WsMessage.serializer(),
                    WsMessage(
                        "PINNED_MESSAGES_UPDATED",
                        json.encodeToString(PinnedMessagesUpdatedPayload.serializer(), payload)
                    )
                )
                val fanoutPids = conversationParticipantRepo.participantIds(chatId)
        val botBlockedIds = try { userRepo.blockedEitherWayIdsInTx(bot.id, fanoutPids) } catch (_: Exception) { emptySet() }
        fanoutPids.forEach { pid ->
            if (pid in botBlockedIds) return@forEach
            sendToUser(pid, pinJson)
        }
                call.respond(
        buildJsonObject {
put("ok", true)
put("pinned", pinned)
put("pins", Json.parseToJsonElement(Json.encodeToString(outcome.pins)))
put("count", outcome.pins.size)
        }
    )
            }
            com.maodouchat.server.repository.PinnedMessageRepository.PinResult.NOT_FOUND ->
                call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
            com.maodouchat.server.repository.PinnedMessageRepository.PinResult.FORBIDDEN ->
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            com.maodouchat.server.repository.PinnedMessageRepository.PinResult.LIMIT ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("pin limit reached"))
            com.maodouchat.server.repository.PinnedMessageRepository.PinResult.NOT_PINNABLE ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("message not pinnable"))
        }
    }

    get("/api/bot/getChatPins") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chatId = call.request.queryParameters["chatId"].orEmpty()
        if (chatId.isBlank()) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val pins = pinnedMessageRepo.list(chatId)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "getChatPins")
        call.respond(
        buildJsonObject {
put("chatId", chatId)
put("pins", Json.parseToJsonElement(Json.encodeToString(pins)))
put("count", pins.size)
        }
    )
    }


    post("/api/bot/setWebhook") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val url = obj["url"]?.jsonPrimitive?.content?.trim()?.take(500)
        if (!url.isNullOrBlank() && !com.maodouchat.server.repository.BotRepository.isAllowedWebhookUrl(url)) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid webhook url"))
        }
        val updated = com.maodouchat.server.repository.BotRepository.setWebhookByToken(bot.id, url)
            ?: return@post call.respondBotUnavailable()
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "setWebhook")
        call.respond(
        buildJsonObject {
put("ok", true)
put("url", (updated.webhookUrl ?: ""))
        }
    )
    }

    post("/api/bot/deleteWebhook") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (com.maodouchat.server.repository.BotRepository.setWebhookByToken(bot.id, null) == null) {
            return@post call.respondBotUnavailable()
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "deleteWebhook")
        call.respond(
        buildJsonObject {
put("ok", true)
put("url", "")
        }
    )
    }

    get("/api/bot/getChatMember") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chatId = call.request.queryParameters["chatId"].orEmpty()
        val userId = call.request.queryParameters["userId"].orEmpty()
        if (chatId.isBlank() || userId.isBlank()) {
            return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/userId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val members = conversationParticipantRepo.groupMembers(chatId)
        val m = members.firstOrNull { it.userId == userId }
        if (m == null) {
            // 1:1 or non-group: check participant list
            val isMember = conversationParticipantRepo.isParticipant(chatId, userId)
            if (!isMember) return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("member not found"))
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "getChatMember")
            call.respond(
        buildJsonObject {
put("userId", userId)
put("status", "member")
put("role", "MEMBER")
        }
    )
        } else {
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "getChatMember")
            call.respond(
        buildJsonObject {
put("userId", m.userId)
put("name", m.name)
put("role", m.role)
put("title", (m.title ?: ""))
put("mutedUntil", m.mutedUntil)
put("status", if (m.mutedUntil > System.currentTimeMillis()) "restricted" else "member")
        }
    )
        }
    }

    post("/api/bot/restrictChatMember") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val userId = obj["userId"]?.jsonPrimitive?.content.orEmpty()
        var until = obj["untilDate"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: obj["mutedUntil"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: 0L
        if (chatId.isBlank() || userId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/userId required"))
        }
        // 8.33 修复：与用户端 mute 路由一致，禁言时长上限 30 天（此前 bot 可无限期禁言）
        val nowMs = System.currentTimeMillis()
        if (until > 0L) {
            if (until <= nowMs) until = 0L
            else if (until > nowMs + MAX_MUTE_DURATION_MS) until = nowMs + MAX_MUTE_DURATION_MS
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        // Promote bot role temporarily for mute: treat bot as ADMIN if owner invited as member
        // Use owner path: if bot role is MEMBER, still try mute only when bot is elevated — promote on invite.
        val mutation = groupModerationRepo.updateMemberMute(
            chatId = chatId,
            actorId = bot.id,
            targetUserId = userId,
            mutedUntil = until,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "restrictChatMember")
        if (mutation != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("restrict failed: $mutation"))
        }
        notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "MEMBER_MUTED", bot.id, userId)
        call.respond(
        buildJsonObject {
put("ok", true)
put("userId", userId)
put("mutedUntil", until)
        }
    )
    }

    post("/api/bot/banChatMember") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val userId = obj["userId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank() || userId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/userId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val commit = groupLifecycleService.removeMember(
            chatId = chatId,
            actorId = bot.id,
            targetUserId = userId,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "banChatMember")
        if (commit.result != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("ban failed: ${commit.result}"))
        }
        notifyGroupRevisionChangedWithData(
            json = json,
            chatId = chatId,
            reason = "MEMBER_REMOVED",
            actorId = bot.id,
            targetUserId = userId,
            memberRevision = commit.memberRevisionAfter ?: 0L,
            recipientIds = commit.recipientsBefore,
        )
        call.respond(
        buildJsonObject {
put("ok", true)
put("userId", userId)
put("removed", true)
        }
    )
    }

    post("/api/bot/answerCallbackQuery") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val callbackQueryId = obj["callbackQueryId"]?.jsonPrimitive?.content
            ?: obj["id"]?.jsonPrimitive?.content
            ?: ""
        val text = obj["text"]?.jsonPrimitive?.content?.take(200)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "answerCallbackQuery")
        // Ack only — client shows ephemeral toast via future WS if needed.
        call.respond(
        buildJsonObject {
put("ok", true)
put("callbackQueryId", callbackQueryId)
put("text", (text ?: ""))
        }
    )
    }

    get("/api/bot/getUpdates") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val offset = call.request.queryParameters["offset"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
        val rows = com.maodouchat.server.repository.BotRepository.getUpdates(bot.id, offset, limit)
        // 8.46：混合类型 List<Map<String,Any>> 经 Json.encodeToString 运行时抛
        // SerializationException（Serializer for class 'Any' is not found）→ 端点 500。
        // 改用 buildJsonArray 直接构造 JSON 元素。
        val updatesArray = buildJsonArray {
            rows.forEach { (id, jsonStr) ->
                add(buildJsonObject {
                    put("update_id", id)
                    put(
                        "payload",
                        runCatching { Json.parseToJsonElement(jsonStr) }
                            .getOrElse { kotlinx.serialization.json.JsonPrimitive(jsonStr) }
                    )
                })
            }
        }
        val nextOffset = rows.lastOrNull()?.first?.plus(1) ?: offset
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getUpdates")
        call.respond(
        buildJsonObject {
put("ok", true)
put("updates", updatesArray)
put("nextOffset", nextOffset)
        }
    )
    }



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

    post("/api/bot/setChatTitle") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val title = (obj["title"] ?: obj["groupName"])?.jsonPrimitive?.content.orEmpty().trim()
        if (chatId.isBlank() || title.isBlank() || title.length > 50) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/title required (1-50)"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val mutation = groupProfileRepo.updateName(
            chatId = chatId,
            actorId = bot.id,
            name = title,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "setChatTitle")
        if (mutation != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("set title failed: $mutation"))
        }
        notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "GROUP_RENAMED", bot.id)
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("title", title)
        }
    )
    }

    post("/api/bot/setChatDescription") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val description = (obj["description"] ?: obj["announcement"])?.jsonPrimitive?.content.orEmpty().trim()
        if (chatId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        if (description.length > 1200) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("description too long"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val announcement = description.takeIf { it.isNotBlank() }
        val mutation = groupProfileRepo.updateAnnouncement(
            chatId = chatId,
            actorId = bot.id,
            announcement = announcement,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "setChatDescription")
        if (mutation != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("set description failed: $mutation"))
        }
        notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "ANNOUNCEMENT_UPDATED", bot.id)
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("description", (announcement ?: ""))
        }
    )
    }

    get("/api/bot/getChatAdministrators") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chatId = call.request.queryParameters["chatId"].orEmpty()
        if (chatId.isBlank()) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val admins = conversationParticipantRepo.groupMembers(chatId)
            .filter { it.role == "OWNER" || it.role == "ADMIN" }
        // 8.46：混合类型 List<Map<String,Any>> encodeToString 运行时抛 SerializationException → buildJsonArray
        val adminsArray = buildJsonArray {
            admins.forEach { m ->
                add(buildJsonObject {
                    put("userId", m.userId)
                    put("name", m.name)
                    put("role", m.role)
                    put("title", m.title ?: "")
                    put("isOnline", m.isOnline)
                })
            }
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "getChatAdministrators")
        call.respond(
        buildJsonObject {
put("chatId", chatId)
put("administrators", adminsArray)
put("count", admins.size)
        }
    )
    }

    post("/api/bot/forwardMessage") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMessageForwardingEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forwarding_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fromChatId = (obj["fromChatId"] ?: obj["from_chat_id"])?.jsonPrimitive?.content.orEmpty()
        val toChatId = (obj["chatId"] ?: obj["toChatId"] ?: obj["to_chat_id"])?.jsonPrimitive?.content.orEmpty()
        val messageId = (obj["messageId"] ?: obj["message_id"])?.jsonPrimitive?.content.orEmpty()
        if (fromChatId.isBlank() || toChatId.isBlank() || messageId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("fromChatId/chatId/messageId required"))
        }
        if (!conversationParticipantRepo.isParticipant(fromChatId, bot.id) || !conversationParticipantRepo.isParticipant(toChatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot must be in both chats"))
        }
        val src = serviceMessageRepo.getById(messageId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
        if (src.chatId != fromChatId) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("message not in fromChatId"))
        }
        // Bot plaintext channel only — refuse E2EE ciphertext / attachment blobs.
        val t = src.type.uppercase()
        if (t !in setOf("TEXT", "MARKDOWN", "SYSTEM")) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("only TEXT/MARKDOWN forward supported for bots"))
        }
        val content = src.content
        if (content.contains("ENC:") || content.startsWith("SK:") || content.length > 4000) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("cannot forward encrypted content"))
        }
        val newId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val forwardedBody = content + "\n[forwardedFrom:" + fromChatId + ":" + messageId + "]"
        val ok = runCatching {
            serviceMessageRepo.insert(newId, toChatId, bot.id, forwardedBody, now, if (t == "MARKDOWN") "MARKDOWN" else "TEXT")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("forward failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, toChatId, messageId, "forwardMessage")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = newId, chatId = toChatId, senderId = bot.id, content = forwardedBody,
            type = if (t == "MARKDOWN") "MARKDOWN" else "TEXT", timestamp = now, status = "SENT"
        )
        val forwardParticipants = conversationParticipantRepo.participantIds(toChatId)
        val sourceBlockedIds = try {
            userRepo.blockedEitherWayIdsInTx(src.senderId, forwardParticipants)
        } catch (_: Exception) { emptySet() }
        fanoutBotMessage(
            userRepo,
            conversationParticipantRepo,
            json,
            bot.id,
            toChatId,
            botMessage,
            excludedRecipientIds = sourceBlockedIds,
        )
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", newId)
put("fromChatId", fromChatId)
put("chatId", toChatId)
        }
    )
    }

    post("/api/bot/copyMessage") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMessageForwardingEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forwarding_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val fromChatId = (obj["fromChatId"] ?: obj["from_chat_id"])?.jsonPrimitive?.content.orEmpty()
        val toChatId = (obj["chatId"] ?: obj["toChatId"] ?: obj["to_chat_id"])?.jsonPrimitive?.content.orEmpty()
        val messageId = (obj["messageId"] ?: obj["message_id"])?.jsonPrimitive?.content.orEmpty()
        if (fromChatId.isBlank() || toChatId.isBlank() || messageId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("fromChatId/chatId/messageId required"))
        }
        if (!conversationParticipantRepo.isParticipant(fromChatId, bot.id) || !conversationParticipantRepo.isParticipant(toChatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot must be in both chats"))
        }
        val src = serviceMessageRepo.getById(messageId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
        if (src.chatId != fromChatId) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("message not in fromChatId"))
        }
        val t = src.type.uppercase()
        if (t !in setOf("TEXT", "MARKDOWN", "SYSTEM")) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("only TEXT/MARKDOWN copy supported for bots"))
        }
        val content = stripInlineMeta(src.content)
        if (content.contains("ENC:") || content.startsWith("SK:") || content.length > 4000) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("cannot copy encrypted content"))
        }
        val newId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val ok = runCatching {
            serviceMessageRepo.insert(newId, toChatId, bot.id, content, now, if (t == "MARKDOWN") "MARKDOWN" else "TEXT")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("copy failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, toChatId, messageId, "copyMessage")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = newId, chatId = toChatId, senderId = bot.id, content = content,
            type = if (t == "MARKDOWN") "MARKDOWN" else "TEXT", timestamp = now, status = "SENT"
        )
        val copyParticipants = conversationParticipantRepo.participantIds(toChatId)
        val sourceBlockedIds = try {
            userRepo.blockedEitherWayIdsInTx(src.senderId, copyParticipants)
        } catch (_: Exception) { emptySet() }
        fanoutBotMessage(
            userRepo,
            conversationParticipantRepo,
            json,
            bot.id,
            toChatId,
            botMessage,
            excludedRecipientIds = sourceBlockedIds,
        )
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", newId)
put("fromChatId", fromChatId)
put("chatId", toChatId)
        }
    )
    }

    post("/api/bot/leaveChat") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        val outcome = runCatching {
            conversationLifecycleRepo.leave(chatId = chatId, userId = bot.id, requireBotDeliverable = true)
        }.getOrNull()
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "leaveChat")
        if (outcome?.result == LeaveConversationResult.OWNER_TRANSFER_REQUIRED) {
            return@post call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse("群主需先转让群主身份再退出群聊", code = "GROUP_OWNER_TRANSFER_REQUIRED")
            )
        }
        if (outcome?.result == LeaveConversationResult.LEFT) {
            outcome.deletedAttachmentIds.forEach(EncryptedAttachmentStorage::delete)
            com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(
                outcome.deletedGroupAvatarUrl,
                chatId,
            )
        }
        val revisionAfter = outcome?.memberRevisionAfter
        if (outcome?.wasGroup == true && outcome.result == LeaveConversationResult.LEFT && revisionAfter != null) {
            notifyGroupRevisionChangedWithData(
                json = json,
                chatId = chatId,
                reason = "MEMBER_LEFT",
                actorId = bot.id,
                targetUserId = bot.id,
                memberRevision = revisionAfter,
                recipientIds = outcome.recipientsBefore,
            )
        }
        call.respond(
        buildJsonObject {
put("status", "ok")
put("chatId", chatId)
put("result", (outcome?.result?.name ?: "UNKNOWN"))
        }
    )
    }

    post("/api/bot/deleteMessage") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank() || messageId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/messageId required"))
        }
        val msg = serviceMessageRepo.getById(messageId)
        if (msg == null || msg.chatId != chatId) {
            return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
        }
        if (msg.senderId != bot.id) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("can only delete own bot messages"))
        }
        val deleted = runCatching {
            serviceMessageRepo.deleteOwn(messageId, bot.id)
        }.getOrNull()
        if (deleted != null) {
            fanoutBotEvent(
                userRepo = userRepo,
                participantRepository = conversationParticipantRepo,
                json = json,
                botId = bot.id,
                chatId = chatId,
                event = com.maodouchat.server.messaging.v2.ServiceMessagingV2Event(
                    action = "DELETE",
                    targetMessageId = messageId,
                ),
            )
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, messageId, "deleteMessage")
        call.respond(
        buildJsonObject {
put("status", if (deleted != null) "ok" else "failed")
put("messageId", messageId)
        }
    )
    }

post("/api/bot/deleteMyCommands") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.clearMyCommands(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "deleteMyCommands")
        call.respond(
        buildJsonObject {
put("ok", true)
put("commands", buildJsonArray { })
put("count", 0)
        }
    )
    }


    post("/api/bot/setMyDescription") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val description = (obj["description"] ?: obj["about"])?.jsonPrimitive?.content
        val updated = com.maodouchat.server.repository.BotRepository.setMyDescription(bot.id, description)
            ?: return@post call.respondBotUnavailable()
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "setMyDescription")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", updated.id)
put("description", (updated.description ?: ""))
        }
    )
    }

    get("/api/bot/getChatMemberCount") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chatId = call.request.queryParameters["chatId"].orEmpty()
        if (chatId.isBlank()) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val count = conversationParticipantRepo.participantIds(chatId).size
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "getChatMemberCount")
        call.respond(
        buildJsonObject {
put("chatId", chatId)
put("count", count)
        }
    )
    }

    post("/api/bot/unbanChatMember") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val userId = obj["userId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank() || userId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/userId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val maxMembers = try {
            com.maodouchat.server.service.RuntimeConfigService.maxGroupSize()
        } catch (_: Exception) { 200 }
        val chatType = conversationParticipantRepo.chatType(chatId)
        val addedUserIds: List<String>
        val mutation: com.maodouchat.server.repository.GroupMemberMutationResult
        if (chatType == ChatType.CHANNEL) {
            val addResult = groupMembershipRepo.addMembers(
                chatId = chatId,
                actorId = bot.id,
                requestedUserIds = listOf(userId),
                maxMembers = maxMembers,
                requireBotDeliverable = true
            )
            mutation = addResult.result
            addedUserIds = addResult.addedUserIds
        } else {
            val inviteResult = groupInvitationRepo.inviteMembers(
                chatId = chatId,
                actorId = bot.id,
                requestedUserIds = listOf(userId),
                maxMembers = maxMembers
            )
            mutation = inviteResult.result
            addedUserIds = inviteResult.invitedUserIds
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "unbanChatMember")
        if (mutation != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("unban failed: $mutation"))
        }
        if (chatType == ChatType.CHANNEL) {
            notifyGroupRevisionChanged(
                queryRepository = conversationQueryRepo,
                participantRepository = conversationParticipantRepo,
                json = json,
                chatId = chatId,
                reason = "MEMBER_ADDED",
                actorId = bot.id,
                targetUserId = userId
            )
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("userId", userId)
put("readded", chatType == ChatType.CHANNEL)
put("invited", chatType != ChatType.CHANNEL)
put("added", Json.parseToJsonElement(Json.encodeToString(addedUserIds)))
        }
    )
    }

    post("/api/bot/promoteChatMember") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val userId = obj["userId"]?.jsonPrimitive?.content.orEmpty()
        val roleRaw = obj["role"]?.jsonPrimitive?.content.orEmpty().uppercase().ifBlank { "ADMIN" }
        val role = if (roleRaw == "MEMBER") "MEMBER" else "ADMIN"
        if (chatId.isBlank() || userId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/userId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        // Only OWNER can change roles; bots invited as ADMIN cannot promote — require owner bot or use updateGroupMemberRoleAsOwner
        val commit = groupLifecycleService.updateRole(
            chatId = chatId,
            ownerId = bot.id,
            targetUserId = userId,
            role = role,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "promoteChatMember")
        if (commit.result != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("role update failed: ${commit.result}"))
        }
        notifyGroupRevisionChangedWithData(
            json = json,
            chatId = chatId,
            reason = "ROLE_UPDATED",
            actorId = bot.id,
            targetUserId = userId,
            memberRevision = commit.memberRevisionAfter ?: 0L,
            recipientIds = commit.recipientsBefore,
        )
        call.respond(
        buildJsonObject {
put("ok", true)
put("userId", userId)
put("role", role)
        }
    )
    }

    post("/api/bot/unpinAllChatMessages") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val chat = conversationQueryRepo.getById(chatId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("chat not found"))
        val actorIsManager = if (chat.isGroup) conversationParticipantRepo.isOwnerOrAdmin(chatId, bot.id) else true
        val outcome = pinnedMessageRepo.clearAll(
            chatId = chatId,
            actorId = bot.id,
            actorIsManager = actorIsManager,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "unpinAllChatMessages")
        if (outcome.result == com.maodouchat.server.repository.PinnedMessageRepository.PinResult.FORBIDDEN) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        }
        if (outcome.result == com.maodouchat.server.repository.PinnedMessageRepository.PinResult.NOT_FOUND) {
            return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("chat not found"))
        }
        val payload = PinnedMessagesUpdatedPayload(chatId, bot.id, outcome.pins)
        val pinJson = json.encodeToString(
            WsMessage.serializer(),
            WsMessage(
                "PINNED_MESSAGES_UPDATED",
                json.encodeToString(PinnedMessagesUpdatedPayload.serializer(), payload)
            )
        )
        val fanoutPids = conversationParticipantRepo.participantIds(chatId)
        val botBlockedIds = try { userRepo.blockedEitherWayIdsInTx(bot.id, fanoutPids) } catch (_: Exception) { emptySet() }
        fanoutPids.forEach { pid ->
            if (pid in botBlockedIds) return@forEach
            sendToUser(pid, pinJson)
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("pins", Json.parseToJsonElement(Json.encodeToString(outcome.pins)))
put("count", 0)
        }
    )
    }

    post("/api/bot/sendPoll") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        if (!com.maodouchat.server.service.RuntimeConfigService.isPollsEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("polls_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val question = obj["question"]?.jsonPrimitive?.content.orEmpty()
        val optionsEl = obj["options"]
        val options = when (optionsEl) {
            is kotlinx.serialization.json.JsonArray -> optionsEl.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()?.trim()?.takeIf { s -> s.isNotBlank() }
            }
            else -> emptyList()
        }
        val multi = obj["multi"]?.jsonPrimitive?.booleanOrNull
            ?: obj["allowsMultipleAnswers"]?.jsonPrimitive?.booleanOrNull
            ?: false
        val anonymous = obj["anonymous"]?.jsonPrimitive?.booleanOrNull
            ?: obj["isAnonymous"]?.jsonPrimitive?.booleanOrNull
            ?: true
        val closesAt = obj["closesAt"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: obj["closeDate"]?.jsonPrimitive?.content?.toLongOrNull()
        if (chatId.isBlank() || question.isBlank() || options.size < 2) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/question/options(>=2) required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupPlayEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("group play disabled"))
        }
        val poll = com.maodouchat.server.repository.GroupPlayRepository.createPoll(
            chatId = chatId,
            creatorId = bot.id,
            question = question,
            options = options,
            multi = multi,
            anonymous = anonymous,
            closesAt = closesAt,
            requireBotDeliverable = true
        ) ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("poll create failed"))
        // Also drop a bot plaintext summary message so chat history shows the poll.
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val summary = buildString {
            append("📊 ")
            append(poll.question)
            poll.options.forEachIndexed { i, o ->
                append("\n")
                append(i + 1)
                append(". ")
                append(o)
            }
            append("\n[poll:")
            append(poll.id)
            append("]")
        }
        runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, summary, now, "TEXT")
        }
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = summary,
            type = "TEXT", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, poll.id, "sendPoll")
        call.respond(
        buildJsonObject {
put("ok", true)
put("poll", Json.parseToJsonElement(Json.encodeToString(poll)))
put("messageId", msgId)
        }
    )
    }


    post("/api/bot/sendDice") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        // emoji 语义映射（对齐 Telegram 骰子）：🎲🎯🎳 6 面、🏀⚽ 5 面、🎰 64 面；显式 sides 优先
        val diceEmoji = obj["emoji"]?.jsonPrimitive?.content.orEmpty().takeIf { it.isNotBlank() }
        val sides = (obj["sides"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: when (diceEmoji) {
                "🏀", "⚽" -> 5
                "🎰" -> 64
                else -> 6
            }).coerceIn(2, 100)
        if (chatId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupPlayEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("group play disabled"))
        }
        val value = (1..sides).random()
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = "${diceEmoji ?: "🎲"} ${value}/${sides}"
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "TEXT")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendDice")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "TEXT", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("value", value)
put("sides", sides)
        }
    )
    }

    post("/api/bot/votePoll") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val pollId = obj["pollId"]?.jsonPrimitive?.content.orEmpty()
        // 9.157：同用户投票端点——非法元素整体拒绝，不静默截成子集投票
        val indexes = buildList {
            val arr = obj["optionIndexes"] as? kotlinx.serialization.json.JsonArray
            if (arr != null) {
                for (element in arr) {
                    val v = (element as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                    if (v == null || v < 0) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid optionIndexes"))
                    }
                    add(v)
                }
            } else {
                val single = (obj["optionIndex"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                if (single == null || single < 0) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("pollId/optionIndexes required"))
                }
                add(single)
            }
        }
        if (pollId.isBlank() || indexes.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("pollId/optionIndexes required"))
        }
        val poll = com.maodouchat.server.repository.GroupPlayRepository.vote(
            pollId = pollId,
            userId = bot.id,
            optionIndexes = indexes,
            requireBotDeliverable = true
        )
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("vote failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, poll.chatId, pollId, "votePoll")
        call.respond(
        buildJsonObject {
put("ok", true)
put("poll", Json.parseToJsonElement(Json.encodeToString(poll)))
        }
    )
    }

    post("/api/bot/closePoll") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val pollId = obj["pollId"]?.jsonPrimitive?.content.orEmpty()
        if (pollId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("pollId required"))
        val poll = com.maodouchat.server.repository.GroupPlayRepository.closePoll(
            pollId = pollId,
            userId = bot.id,
            requireBotDeliverable = true
        )
            ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("close failed (creator only)"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, poll.chatId, pollId, "closePoll")
        call.respond(
        buildJsonObject {
put("ok", true)
put("poll", Json.parseToJsonElement(Json.encodeToString(poll)))
        }
    )
    }

    get("/api/bot/getPoll") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val pollId = call.request.queryParameters["pollId"].orEmpty()
        if (pollId.isBlank()) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("pollId required"))
        val poll = com.maodouchat.server.repository.GroupPlayRepository.getPoll(pollId, bot.id)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("poll not found"))
        if (!conversationParticipantRepo.isParticipant(poll.chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, poll.chatId, pollId, "getPoll")
        call.respond(
        buildJsonObject {
put("ok", true)
put("poll", Json.parseToJsonElement(Json.encodeToString(poll)))
        }
    )
    }

    post("/api/bot/exportChatInviteLink") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupInvitesEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_invites_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val rotate = obj["rotate"]?.jsonPrimitive?.booleanOrNull == true
        val expiresIn = (obj["expiresInSeconds"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: 7L * 24 * 3600).coerceIn(300L, 30L * 24 * 3600)
        val maxUses = (obj["maxUses"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100).coerceIn(1, 1000)
        if (chatId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        // 8.63：广播频道不开放邀请加入——与 App 侧 invite-token 路由一致拦截（此前 Bot 可给频道生成邀请）
        if (conversationParticipantRepo.chatType(chatId) == ChatType.CHANNEL) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("频道不支持邀请加入"))
        }
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000L
        val mutation = groupInvitationRepo.configureToken(
            chatId = chatId,
            actorId = bot.id,
            rotate = rotate,
            expiresAt = expiresAt,
            maxUses = maxUses,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "exportChatInviteLink")
        if (mutation.result != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("invite failed: ${mutation.result}"))
        }
        val inv = mutation.invite
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invite missing"))
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("inviteLink", "maodouchat:chat-invite:v1:${inv.token}")
put("token", inv.token)
put("expiresAt", inv.expiresAt)
put("maxUses", inv.maxUses)
put("usedCount", inv.usedCount)
        }
    )
    }

    post("/api/bot/setChatPhoto") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val base64 = (obj["photoBase64"] ?: obj["base64Data"] ?: obj["photo"])?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank() || base64.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/photoBase64 required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val avatarUrl = try {
            com.maodouchat.server.service.FileStorageService.saveGroupAvatar(base64, chatId)
        } catch (error: IllegalArgumentException) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "invalid photo"))
        }
        var committed = false
        try {
            val mutation = groupProfileRepo.updateAvatar(
                chatId = chatId,
                actorId = bot.id,
                avatarUrl = avatarUrl,
                requireBotDeliverable = true
            )
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "setChatPhoto")
            if (mutation.result != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
                return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("set photo failed: ${mutation.result}"))
            }
            committed = true
            com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(mutation.previousAvatarUrl, chatId)
            notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "AVATAR_UPDATED", bot.id)
            call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("avatarUrl", avatarUrl)
        }
    )
        } finally {
            if (!committed) {
                com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(avatarUrl, chatId)
            }
        }
    }


    post("/api/bot/revokeChatInviteLink") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupInvitesEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_invites_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val mutation = groupInvitationRepo.revokeToken(
            chatId = chatId,
            actorId = bot.id,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "revokeChatInviteLink")
        if (mutation != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("revoke failed: $mutation"))
        }
        notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "INVITE_REVOKED", bot.id)
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("revoked", true)
        }
    )
    }

    post("/api/bot/deleteChatPhoto") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val mutation = groupProfileRepo.clearAvatar(
            chatId = chatId,
            actorId = bot.id,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "deleteChatPhoto")
        if (mutation.result != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("delete photo failed: ${mutation.result}"))
        }
        com.maodouchat.server.service.FileStorageService.deleteGroupAvatarUrl(mutation.previousAvatarUrl, chatId)
        notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "AVATAR_CLEARED", bot.id)
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("cleared", true)
        }
    )
    }

    post("/api/bot/sendLocation") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isStaticLocationEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("static_location_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val lat = obj["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: obj["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val lon = obj["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: obj["lng"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: obj["lon"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val title = obj["title"]?.jsonPrimitive?.content?.take(80).orEmpty()
        if (chatId.isBlank() || lat == null || lon == null) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/latitude/longitude required"))
        }
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid coordinates"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        // Bot plaintext location marker (clients may render map if they parse LOCATION body).
        val content = buildString {
            append("📍 ")
            if (title.isNotBlank()) {
                append(title)
                append(" ")
            }
            append(String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon))
            append("\n[location:")
            append(lat)
            append(",")
            append(lon)
            append("]")
        }
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "LOCATION")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendLocation")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "LOCATION", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("latitude", lat)
put("longitude", lon)
        }
    )
    }

    get("/api/bot/listChatPolls") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chatId = call.request.queryParameters["chatId"].orEmpty()
        if (chatId.isBlank()) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val polls = com.maodouchat.server.repository.GroupPlayRepository.listChatPolls(chatId, bot.id)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "listChatPolls")
        call.respond(
        buildJsonObject {
put("chatId", chatId)
put("polls", Json.parseToJsonElement(Json.encodeToString(polls)))
put("count", polls.size)
        }
    )
    }


    post("/api/bot/setMyName") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val name = (obj["name"] ?: obj["displayName"])?.jsonPrimitive?.content.orEmpty().trim().take(120)
        if (name.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid name"))
        }
        val updated = com.maodouchat.server.repository.BotRepository.setMyName(bot.id, name)
            ?: return@post call.respondBotUnavailable()
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "setMyName")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", updated.id)
put("name", updated.name)
        }
    )
    }

    get("/api/bot/getChatHistory") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chatId = call.request.queryParameters["chatId"].orEmpty()
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
        if (chatId.isBlank()) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        // Bot history exposes only server-authored service messages; peer E2EE bodies stay device-local.
        val history = serviceMessageRepo.list(chatId, limit, bot.id)
        // 8.46：混合类型 List<Map<String,Any>> encodeToString 运行时抛 SerializationException → buildJsonArray
        val historyArray = buildJsonArray {
            history.forEach { m ->
                add(buildJsonObject {
                    put("messageId", m.id)
                    put("chatId", m.chatId)
                    put("senderId", m.senderId)
                    put("type", m.type)
                    put("timestamp", m.timestamp)
                    put("status", m.status)
                    put("content", m.content.take(4000))
                    put("sealedSender", m.sealedSender)
                })
            }
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "getChatHistory")
        call.respond(
        buildJsonObject {
put("chatId", chatId)
put("messages", historyArray)
put("count", history.size)
        }
    )
    }

    post("/api/bot/sendContact") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isContactCardEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("contact_card_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val contactName = (obj["name"] ?: obj["firstName"])?.jsonPrimitive?.content.orEmpty().trim().take(80)
        val phone = (obj["phone"] ?: obj["phoneNumber"])?.jsonPrimitive?.content.orEmpty().trim().take(40)
        val userId = obj["userId"]?.jsonPrimitive?.content?.take(64).orEmpty()
        if (chatId.isBlank() || (contactName.isBlank() && userId.isBlank() && phone.isBlank())) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId and contact fields required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = buildString {
            append("👤 ")
            if (contactName.isNotBlank()) append(contactName)
            if (phone.isNotBlank()) {
                if (isNotEmpty() && !endsWith(" ")) append(" ")
                append(phone)
            }
            if (userId.isNotBlank()) {
                append("\n[contactUser:")
                append(userId)
                append("]")
            }
            if (phone.isNotBlank()) {
                append("\n[contactPhone:")
                append(phone)
                append("]")
            }
        }
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "TEXT")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendContact")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "TEXT", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
        }
    )
    }

    post("/api/bot/sendVenue") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val lat = obj["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: obj["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val lon = obj["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: obj["lng"]?.jsonPrimitive?.content?.toDoubleOrNull()
            ?: obj["lon"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val title = obj["title"]?.jsonPrimitive?.content.orEmpty().trim().take(80)
        val address = obj["address"]?.jsonPrimitive?.content.orEmpty().trim().take(160)
        if (chatId.isBlank() || lat == null || lon == null || title.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/latitude/longitude/title required"))
        }
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid coordinates"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = buildString {
            append("📌 ")
            append(title)
            if (address.isNotBlank()) {
                append("\n")
                append(address)
            }
            append("\n")
            append(String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon))
            append("\n[venue:")
            append(lat)
            append(",")
            append(lon)
            append("|")
            append(title.replace("|", "/"))
            append("]")
        }
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "LOCATION")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendVenue")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "LOCATION", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("latitude", lat)
put("longitude", lon)
put("title", title)
        }
    )
    }



    post("/api/bot/unpinChatMessage") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank() || messageId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/messageId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val chat = conversationQueryRepo.getById(chatId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("chat not found"))
        val actorIsManager = if (chat.isGroup) conversationParticipantRepo.isOwnerOrAdmin(chatId, bot.id) else true
        // toggle: if currently pinned -> unpins; if not pinned, pin then toggle again would pin — force unpin via toggle only when pinned
        val before = pinnedMessageRepo.list(chatId).any { it.messageId == messageId }
        if (!before) {
            com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "unpinChatMessage")
            return@post call.respond(
        buildJsonObject {
put("ok", true)
put("pinned", false)
put("messageId", messageId)
put("alreadyUnpinned", true)
        }
    )
        }
        val outcome = pinnedMessageRepo.toggle(
            chatId = chatId,
            messageId = messageId,
            actorId = bot.id,
            actorIsManager = actorIsManager,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "unpinChatMessage")
        when (outcome.result) {
            com.maodouchat.server.repository.PinnedMessageRepository.PinResult.UNPINNED,
            com.maodouchat.server.repository.PinnedMessageRepository.PinResult.PINNED -> {
                val payload = PinnedMessagesUpdatedPayload(chatId, bot.id, outcome.pins)
                val pinJson = json.encodeToString(
                    WsMessage.serializer(),
                    WsMessage(
                        "PINNED_MESSAGES_UPDATED",
                        json.encodeToString(PinnedMessagesUpdatedPayload.serializer(), payload)
                    )
                )
                val fanoutPids = conversationParticipantRepo.participantIds(chatId)
        val botBlockedIds = try { userRepo.blockedEitherWayIdsInTx(bot.id, fanoutPids) } catch (_: Exception) { emptySet() }
        fanoutPids.forEach { pid ->
            if (pid in botBlockedIds) return@forEach
            sendToUser(pid, pinJson)
        }
                call.respond(
        buildJsonObject {
put("ok", true)
put("pinned", false)
put("pins", Json.parseToJsonElement(Json.encodeToString(outcome.pins)))
put("count", outcome.pins.size)
        }
    )
            }
            com.maodouchat.server.repository.PinnedMessageRepository.PinResult.NOT_FOUND ->
                call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
            com.maodouchat.server.repository.PinnedMessageRepository.PinResult.FORBIDDEN ->
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
            else -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("unpin failed: ${outcome.result}"))
        }
    }

    post("/api/bot/sendSticker") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isStickersEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("stickers_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val emoji = (obj["emoji"] ?: obj["sticker"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().trim().take(16)
        val pack = obj["pack"]?.jsonPrimitive?.content.orEmpty().trim().take(40)
        if (chatId.isBlank() || emoji.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/emoji required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = buildString {
            append(emoji)
            if (pack.isNotBlank()) {
                append("\n[stickerPack:")
                append(pack)
                append("]")
            }
        }
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "STICKER")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendSticker")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "STICKER", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("emoji", emoji)
put("type", "STICKER")
        }
    )
    }

    post("/api/bot/sendVoice") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isVoiceMessagesEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("voice_messages_disabled"))
        }
        if (!com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("media_upload_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val duration = (obj["duration"] ?: obj["durationSec"])?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(200)
        val b64 = (obj["fileBase64"] ?: obj["voice"] ?: obj["data"])?.jsonPrimitive?.content.orEmpty()
        // 9.138：与 sendPhoto/sendDocument 一致拒绝空媒体——此前可广播无内容的 voice 消息
        if (chatId.isBlank() || b64.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/voice required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val size = if (b64.isNotBlank()) {
            runCatching {
                java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace("\\s".toRegex(), "")).size
            }.getOrDefault(0)
        } else 0
        if (size > 4 * 1024 * 1024) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("voice too large (max 4MB)"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = buildString {
            append("🎤 voice")
            if (duration > 0) {
                append(" ")
                append(duration)
                append("s")
            }
            if (size > 0) {
                append(" (")
                append(size)
                append("B)")
            }
            if (caption.isNotBlank()) {
                append("\n")
                append(caption)
            }
            append("\n[botVoiceSize:")
            append(size)
            append("]")
        }.take(4000)
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "VOICE")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendVoice")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "VOICE", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("duration", duration)
put("size", size)
put("type", "VOICE")
        }
    )
    }

    get("/api/bot/getInviteLink") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupInvitesEnabled()) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_invites_disabled"))
        }
        val chatId = call.request.queryParameters["chatId"].orEmpty()
        if (chatId.isBlank()) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        // 9.242：邀请 token 是管理者级信息（持 token 可拉人入群）——与 pin/unpin 的
        // isOwnerOrAdmin 口径对齐（当前 bot 入群即 ADMIN，防御未来成员角色变化）
        if (!conversationParticipantRepo.isOwnerOrAdmin(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot is not a manager of this chat"))
        }
        // Read-only invite snapshot; rotation uses exportChatInviteLink
        val chat = conversationQueryRepo.getById(chatId)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("chat not found"))
        val inviteRow = org.jetbrains.exposed.sql.transactions.transaction {
            com.maodouchat.server.db.Chats.selectAll()
                .where { com.maodouchat.server.db.Chats.id eq chatId }
                .firstOrNull()
        }
        val invite = inviteRow?.get(com.maodouchat.server.db.Chats.groupInviteToken).orEmpty()
        val expiresAt = inviteRow?.get(com.maodouchat.server.db.Chats.groupInviteExpiresAt) ?: 0L
        val maxUses = inviteRow?.get(com.maodouchat.server.db.Chats.groupInviteMaxUses) ?: 0
        val used = inviteRow?.get(com.maodouchat.server.db.Chats.groupInviteUseCount) ?: 0
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "getInviteLink")
        call.respond(
        buildJsonObject {
put("chatId", chatId)
put("title", (chat.groupName ?: ""))
put("inviteToken", invite)
put("inviteLink", if (invite.isNotBlank()) "maodouchat:chat-invite:v1:$invite" else "")
put("expiresAt", expiresAt)
put("maxUses", maxUses)
put("usedCount", used)
put("hasInvite", invite.isNotBlank())
        }
    )
    }

    post("/api/bot/demoteChatMember") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val userId = obj["userId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank() || userId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/userId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val commit = groupLifecycleService.updateRole(
            chatId = chatId,
            ownerId = bot.id,
            targetUserId = userId,
            role = "MEMBER",
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "demoteChatMember")
        if (commit.result != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("demote failed: ${commit.result}"))
        }
        notifyGroupRevisionChangedWithData(
            json = json,
            chatId = chatId,
            reason = "MEMBER_ROLE_CHANGED",
            actorId = bot.id,
            targetUserId = userId,
            memberRevision = commit.memberRevisionAfter ?: 0L,
            recipientIds = commit.recipientsBefore,
        )
        call.respond(
        buildJsonObject {
put("ok", true)
put("userId", userId)
put("role", "MEMBER")
        }
    )
    }

    post("/api/bot/sendDocument") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isFileShareEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("file_share_disabled"))
        }
        if (!com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("media_upload_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val fileName = (obj["fileName"] ?: obj["filename"])?.jsonPrimitive?.content.orEmpty().trim().take(120).ifBlank { "document.bin" }
        val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)
        val b64 = (obj["fileBase64"] ?: obj["document"] ?: obj["data"])?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank() || b64.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/fileBase64 required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val bytes = runCatching {
            java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace("\\s".toRegex(), ""))
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid base64"))
        }
        if (bytes.size > 8 * 1024 * 1024) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("file too large (max 8MB)"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = buildString {
            append("📎 ")
            append(fileName)
            append(" (")
            append(bytes.size)
            append(" bytes)")
            if (caption.isNotBlank()) {
                append("\n")
                append(caption)
            }
            // Bot plaintext channel only — not E2EE peer attachment pipeline
            append("\n[botFileName:")
            append(fileName)
            append("]")
            append("\n[botFileSize:")
            append(bytes.size)
            append("]")
        }.take(4000)
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "FILE")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendDocument")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "FILE", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("fileName", fileName)
put("size", bytes.size)
        }
    )
    }

    post("/api/bot/sendPhoto") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("media_upload_disabled"))
        }
        if (!com.maodouchat.server.service.RuntimeConfigService.isImageSendEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("image_send_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)
        val b64 = (obj["photoBase64"] ?: obj["photo"] ?: obj["fileBase64"] ?: obj["data"])?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank() || b64.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/photoBase64 required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val bytes = runCatching {
            java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace("\\s".toRegex(), ""))
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid base64"))
        }
        if (bytes.size > 5 * 1024 * 1024) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("photo too large (max 5MB)"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = buildString {
            append("🖼 photo ")
            append(bytes.size)
            append("B")
            if (caption.isNotBlank()) {
                append("\n")
                append(caption)
            }
            append("\n[botPhotoSize:")
            append(bytes.size)
            append("]")
        }.take(4000)
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "IMAGE")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendPhoto")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "IMAGE", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("size", bytes.size)
put("type", "IMAGE")
        }
    )
    }

    get("/api/bot/getFile") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val messageId = call.request.queryParameters["messageId"].orEmpty()
        val fileId = call.request.queryParameters["fileId"].orEmpty()
        val id = messageId.ifBlank { fileId }
        if (id.isBlank()) {
            return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("messageId or fileId required"))
        }
        val msg = serviceMessageRepo.getById(id)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
        if (!conversationParticipantRepo.isParticipant(msg.chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val content = msg.content
        val isBotMedia = content.contains("[botFileName:") || content.contains("[botPhotoSize:") ||
            content.startsWith("📎 ") || content.startsWith("🖼 ")
        if (!isBotMedia && msg.senderId != bot.id) {
            return@get call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("not a bot media envelope (E2EE peer content is not downloadable)")
            )
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, msg.chatId, null, "getFile")
        call.respond(
        buildJsonObject {
put("messageId", msg.id)
put("chatId", msg.chatId)
put("type", msg.type)
put("content", content.take(4000))
put("timestamp", msg.timestamp)
put("note", "E2EE peer attachments are not exposed; bot plaintext media metadata only")
        }
    )
    }

    get("/api/bot/getMyDescription") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getMyDescription")
        call.respond(
        buildJsonObject {
put("botId", bot.id)
put("description", (bot.description ?: ""))
put("name", bot.name)
put("username", bot.username)
        }
    )
    }

    post("/api/bot/deleteUpdates") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val upTo = obj?.get("upToId")?.jsonPrimitive?.content?.toLongOrNull()
            ?: obj?.get("offset")?.jsonPrimitive?.content?.toLongOrNull()
            ?: call.request.queryParameters["upToId"]?.toLongOrNull()
            ?: 0L
        if (upTo <= 0L) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("upToId required"))
        }
        val n = com.maodouchat.server.repository.BotRepository.deleteUpdates(bot.id, upTo)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "deleteUpdates")
        call.respond(
        buildJsonObject {
put("ok", true)
put("deleted", n)
put("upToId", upTo)
        }
    )
    }

    post("/api/bot/sendVideo") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("media_upload_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)
        val duration = (obj["duration"] ?: obj["durationSec"])?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val b64 = (obj["videoBase64"] ?: obj["fileBase64"] ?: obj["data"])?.jsonPrimitive?.content.orEmpty()
        // 9.138：与 sendPhoto/sendDocument 一致拒绝空媒体
        if (chatId.isBlank() || b64.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/videoBase64 required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val size = if (b64.isNotBlank()) {
            runCatching {
                java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace("\\s".toRegex(), "")).size
            }.getOrDefault(0)
        } else 0
        if (size > 12 * 1024 * 1024) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("video too large (max 12MB)"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = buildString {
            append("🎬 video")
            if (duration > 0) { append(" "); append(duration); append("s") }
            if (size > 0) { append(" ("); append(size); append("B)") }
            if (caption.isNotBlank()) { append("\n"); append(caption) }
            append("\n[botVideoSize:"); append(size); append("]")
        }.take(4000)
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "VIDEO")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendVideo")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "VIDEO", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("size", size)
put("duration", duration)
put("type", "VIDEO")
        }
    )
    }

    post("/api/bot/sendAnimation") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("media_upload_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)
        val b64 = (obj["animationBase64"] ?: obj["gifBase64"] ?: obj["fileBase64"] ?: obj["data"])?.jsonPrimitive?.content.orEmpty()
        // 9.138：与 sendPhoto/sendDocument 一致拒绝空媒体
        if (chatId.isBlank() || b64.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/animationBase64 required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val size = if (b64.isNotBlank()) {
            runCatching {
                java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace("\\s".toRegex(), "")).size
            }.getOrDefault(0)
        } else 0
        if (size > 8 * 1024 * 1024) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("animation too large (max 8MB)"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = buildString {
            append("✨ gif/animation")
            if (size > 0) { append(" ("); append(size); append("B)") }
            if (caption.isNotBlank()) { append("\n"); append(caption) }
            append("\n[botAnimSize:"); append(size); append("]")
        }.take(4000)
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "GIF")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendAnimation")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "GIF", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("size", size)
put("type", "GIF")
        }
    )
    }

    get("/api/bot/getMyName") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getMyName")
        call.respond(
        buildJsonObject {
put("botId", bot.id)
put("name", bot.name)
put("username", bot.username)
        }
    )
    }

    post("/api/bot/setChatPermissions") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val canSend = obj["canSendMessages"]?.jsonPrimitive?.booleanOrNull
            ?: obj["can_send_messages"]?.jsonPrimitive?.booleanOrNull
        val until = obj["until"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: obj["untilDate"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: 0L
        if (chatId.isBlank() || canSend == null) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/canSendMessages required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        // Group default: mute-all via member mute of non-admins is not stored as a single flag.
        // Approximate Telegram setChatPermissions by muting all non-admin members when canSend=false.
        val members = conversationParticipantRepo.participantIds(chatId)
        val muteUntil = if (canSend) 0L else {
            if (until > System.currentTimeMillis()) until
            else System.currentTimeMillis() + 24L * 3600_000L
        }
        // 8.48 修复 M8：一次事务批量静音非管理员成员（此前逐成员 isOwnerOrAdmin +
        // 独立事务静音 ≈5 次查询/人，500 人群 ≈2500 次）
        val nonBotMembers = members.filter { it != bot.id }
        val bulkMutation = if (nonBotMembers.isEmpty()) {
            GroupBulkMuteResult(GroupMemberMutationResult.UPDATED)
        } else {
            groupModerationRepo.updateMembersMute(
            chatId = chatId,
            actorId = bot.id,
            targetUserIds = nonBotMembers,
            mutedUntil = muteUntil,
            requireBotDeliverable = true
        )
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "setChatPermissions")
        if (bulkMutation.result != GroupMemberMutationResult.UPDATED) {
            return@post call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("set permissions failed: ${bulkMutation.result}")
            )
        }
        val changed = bulkMutation.updatedCount
        if (changed > 0) {
            notifyGroupRevisionChanged(conversationQueryRepo, conversationParticipantRepo, json, chatId, "CHAT_PERMISSIONS", bot.id)
        }
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatId", chatId)
put("canSendMessages", canSend)
put("muteUntil", muteUntil)
put("membersUpdated", changed)
        }
    )
    }

    post("/api/bot/logEvent") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        fun stringField(name: String): String? =
            (obj[name] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
        if (listOf("event", "name", "chatId", "userId").any { name ->
                obj[name] != null && stringField(name) == null
            }
        ) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("event, name, chatId and userId must be strings"))
        }
        val event = (stringField("event") ?: stringField("name")).orEmpty().take(40).ifBlank { "custom" }
        val chatId = stringField("chatId")?.trim()?.takeIf { it.isNotEmpty() }
        val userId = stringField("userId")?.trim()?.takeIf { it.isNotEmpty() }
        if (chatId != null && !conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        if (userId != null && (chatId == null || !conversationParticipantRepo.isParticipant(chatId, userId))) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("user is not in chat"))
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "logEvent:$event")
        call.respond(
        buildJsonObject {
put("ok", true)
put("event", event)
        }
    )
    }

    post("/api/bot/sendAudio") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("media_upload_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val title = (obj["title"] ?: obj["fileName"])?.jsonPrimitive?.content.orEmpty().trim().take(80)
        val duration = (obj["duration"] ?: obj["durationSec"])?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)
        val b64 = (obj["audioBase64"] ?: obj["fileBase64"] ?: obj["data"])?.jsonPrimitive?.content.orEmpty()
        // 9.138：与 sendPhoto/sendDocument 一致拒绝空媒体
        if (chatId.isBlank() || b64.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/audioBase64 required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val size = if (b64.isNotBlank()) {
            runCatching {
                java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace("\\s".toRegex(), "")).size
            }.getOrDefault(0)
        } else 0
        if (size > 10 * 1024 * 1024) {
            return@post call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("audio too large (max 10MB)"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = buildString {
            append("🎵 audio")
            if (title.isNotBlank()) { append(" "); append(title) }
            if (duration > 0) { append(" "); append(duration); append("s") }
            if (size > 0) { append(" ("); append(size); append("B)") }
            if (caption.isNotBlank()) { append("\n"); append(caption) }
            append("\n[botAudioSize:"); append(size); append("]")
        }.take(4000)
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "FILE")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendAudio")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "FILE", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("size", size)
put("duration", duration)
put("type", "FILE")
        }
    )
    }

    post("/api/bot/kickChatMember") {
        // Alias of banChatMember for Telegram-compat naming
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val userId = obj["userId"]?.jsonPrimitive?.content.orEmpty()
        if (chatId.isBlank() || userId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/userId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val commit = groupLifecycleService.removeMember(
            chatId = chatId,
            actorId = bot.id,
            targetUserId = userId,
            requireBotDeliverable = true
        )
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, userId, "kickChatMember")
        if (commit.result != com.maodouchat.server.repository.GroupMemberMutationResult.UPDATED) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("kick failed: ${commit.result}"))
        }
        notifyGroupRevisionChangedWithData(
            json = json,
            chatId = chatId,
            reason = "MEMBER_REMOVED",
            actorId = bot.id,
            targetUserId = userId,
            memberRevision = commit.memberRevisionAfter ?: 0L,
            recipientIds = commit.recipientsBefore,
        )
        call.respond(
        buildJsonObject {
put("ok", true)
put("userId", userId)
put("kicked", true)
        }
    )
    }

    get("/api/bot/getChatInviteLink") {
        // Alias of getInviteLink
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupInvitesEnabled()) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_invites_disabled"))
        }
        val chatId = call.request.queryParameters["chatId"].orEmpty()
        if (chatId.isBlank()) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        // 9.242：同 getInviteLink——邀请 token 仅管理者可读
        if (!conversationParticipantRepo.isOwnerOrAdmin(chatId, bot.id)) {
            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot is not a manager of this chat"))
        }
        val chat = conversationQueryRepo.getById(chatId)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("chat not found"))
        val inviteRow = org.jetbrains.exposed.sql.transactions.transaction {
            com.maodouchat.server.db.Chats.selectAll()
                .where { com.maodouchat.server.db.Chats.id eq chatId }
                .firstOrNull()
        }
        val invite = inviteRow?.get(com.maodouchat.server.db.Chats.groupInviteToken).orEmpty()
        val expiresAt = inviteRow?.get(com.maodouchat.server.db.Chats.groupInviteExpiresAt) ?: 0L
        val maxUses = inviteRow?.get(com.maodouchat.server.db.Chats.groupInviteMaxUses) ?: 0
        val used = inviteRow?.get(com.maodouchat.server.db.Chats.groupInviteUseCount) ?: 0
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "getChatInviteLink")
        call.respond(
        buildJsonObject {
put("chatId", chatId)
put("title", (chat.groupName ?: ""))
put("inviteToken", invite)
put("inviteLink", if (invite.isNotBlank()) "maodouchat:chat-invite:v1:$invite" else "")
put("expiresAt", expiresAt)
put("maxUses", maxUses)
put("usedCount", used)
put("hasInvite", invite.isNotBlank())
        }
    )
    }

    post("/api/bot/sendMessageSilent") {
        // Convenience wrapper: force silent when runtime allows
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isSilentSendEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("silent_send_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val text = obj["text"]?.jsonPrimitive?.content.orEmpty().take(4000)
        val parseMode = obj["parseMode"]?.jsonPrimitive?.content.orEmpty().uppercase()
        val msgType = when {
            parseMode == "MARKDOWN" || parseMode == "MD" -> "MARKDOWN"
            else -> "TEXT"
        }
        if (chatId.isBlank() || text.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/text required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val contentOut = text
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, contentOut, now, msgType)
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendMessageSilent")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = contentOut,
            type = msgType, timestamp = now, status = "SENT"
        )
        // silent: still deliver WS, but clients should suppress push (server push path checks silent if present)
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("silent", true)
put("type", msgType)
        }
    )
    }

    post("/api/bot/sendMarkdown") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val text = (obj["text"] ?: obj["markdown"])?.jsonPrimitive?.content.orEmpty().take(4000)
        val silentRequested = obj["silent"]?.jsonPrimitive?.booleanOrNull == true
        val silent = silentRequested && com.maodouchat.server.service.RuntimeConfigService.isSilentSendEnabled()
        if (chatId.isBlank() || text.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/text required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, text, now, "MARKDOWN")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendMarkdown")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = text,
            type = "MARKDOWN", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
put("silent", silent)
        }
    )
    }

    get("/api/bot/getMyCommandsCount") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val commands = com.maodouchat.server.repository.BotRepository.getMyCommands(bot.id)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getMyCommandsCount")
        call.respond(
        buildJsonObject {
put("botId", bot.id)
put("count", commands.size)
put("commands", Json.parseToJsonElement(Json.encodeToString(commands)))
        }
    )
    }

    post("/api/bot/sendNudge") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isNudgeEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("nudge_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val note = obj["text"]?.jsonPrimitive?.content.orEmpty().take(80)
        if (chatId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val content = if (note.isNotBlank()) "👋 $note" else "👋 nudge"
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "NUDGE")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendNudge")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "NUDGE", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "NUDGE")
        }
    )
    }

    get("/api/bot/health") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val pending = com.maodouchat.server.repository.BotRepository.countPendingUpdates(bot.id)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "health")
        call.respond(
        buildJsonObject {
put("ok", bot.enabled)
put("botId", bot.id)
put("enabled", bot.enabled)
put("pendingUpdateCount", pending)
put("webhookConfigured", !bot.webhookUrl.isNullOrBlank())
put("serverTime", System.currentTimeMillis())
        }
    )
    }

    get("/api/bot/getMe") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val commands = com.maodouchat.server.repository.BotRepository.getMyCommands(bot.id)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getMe")
        call.respond(
        buildJsonObject {
put("ok", true)
put("id", bot.id)
put("name", bot.name)
put("username", bot.username)
put("description", (bot.description ?: ""))
put("enabled", bot.enabled)
put("webhookUrl", (bot.webhookUrl ?: ""))
put("commands", Json.parseToJsonElement(Json.encodeToString(commands)))
put("markdownEnabled", com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled())
put("typingIndicatorsEnabled", com.maodouchat.server.service.RuntimeConfigService.isTypingIndicatorsEnabled())
put("mediaUploadEnabled", com.maodouchat.server.service.RuntimeConfigService.isMediaUploadEnabled())
        }
    )
    }

    post("/api/bot/stopPoll") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.repository.BotRepository.isBotDeliverable(bot.id)) {
            return@post call.respondBotUnavailable()
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val pollId = obj["pollId"]?.jsonPrimitive?.content.orEmpty()
        if (pollId.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("pollId required"))
        val poll = com.maodouchat.server.repository.GroupPlayRepository.closePoll(
            pollId = pollId,
            userId = bot.id,
            requireBotDeliverable = true
        )
            ?: return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("stop failed (creator only)"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, poll.chatId, pollId, "stopPoll")
        call.respond(
        buildJsonObject {
put("ok", true)
put("poll", Json.parseToJsonElement(Json.encodeToString(poll)))
put("alias", "closePoll")
        }
    )
    }

    post("/api/bot/editMessageCaption") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
        val caption = (obj["caption"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(1000)
        if (messageId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("messageId required"))
        }
        val existing = serviceMessageRepo.getById(messageId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
        if (existing.senderId != bot.id) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("not your message"))
        }
        if (!conversationParticipantRepo.isParticipant(existing.chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        // Bot plaintext cards only — refuse peer E2EE envelopes (ciphertext bodies).
        val body0 = existing.content.orEmpty()
        if (body0.startsWith("E2EE:") || (body0.startsWith("{") && body0.contains("\"ciphertext\""))) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("cannot edit peer E2EE message"))
        }
        val newBody = if (caption.isBlank()) body0 else {
            // Prefer rewriting trailing caption after first line for media cards.
            val lines = body0.lines()
            if (lines.size <= 1) caption else (lines.first() + "\n" + caption)
        }
        val editedAt = System.currentTimeMillis()
        // Bot plaintext cards may use media types; bypass peer edit window / attachment lock.
        val edited = runCatching {
            serviceMessageRepo.editOwn(messageId, bot.id, newBody, editedAt)
        }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("edit failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, existing.chatId, messageId, "editMessageCaption")
        fanoutBotEvent(
            userRepo = userRepo,
            participantRepository = conversationParticipantRepo,
            json = json,
            botId = bot.id,
            chatId = existing.chatId,
            event = com.maodouchat.server.messaging.v2.ServiceMessagingV2Event(
                action = "EDIT",
                targetMessageId = messageId,
                content = newBody,
                editedAt = editedAt,
            ),
        )
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", messageId)
put("caption", caption.take(200))
        }
    )
    }

    get("/api/bot/getCommandStats") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val logs = com.maodouchat.server.repository.BotRepository.listCommandLogs(bot.id, limit)
        val counts = linkedMapOf<String, Int>()
        logs.forEach { row ->
            val c = row.command.ifBlank { "?" }
            counts[c] = (counts[c] ?: 0) + 1
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getCommandStats")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("totalSampled", logs.size)
put("byCommand", Json.parseToJsonElement(Json.encodeToString(counts)))
put("recent", buildJsonArray {
    logs.take(20).forEach {
add(buildJsonObject {
    put("id", it.id)
    put("command", it.command)
    put("chatId", (it.chatId ?: ""))
    put("createdAt", it.createdAt)
})
    }
})
        }
    )
    }

    post("/api/bot/sendCode") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val code = (obj["code"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(3500)
        val lang = obj["language"]?.jsonPrimitive?.content.orEmpty().take(24)
        if (chatId.isBlank() || code.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/code required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val fenced = if (lang.isNotBlank()) "```$lang\n$code\n```" else "```\n$code\n```"
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, fenced, now, "MARKDOWN")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendCode")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = fenced,
            type = "MARKDOWN", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
        }
    )
    }










    post("/api/bot/setMessageReaction") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isReactionsEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("reactions_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
        val emoji = obj["emoji"]?.jsonPrimitive?.content.orEmpty().trim()
        if (messageId.isBlank() || emoji.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("messageId/emoji required"))
        }
        if (emoji !in ALLOWED_REACTION_EMOJIS) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("unsupported emoji"))
        }
        val msg = serviceMessageRepo.metadata(messageId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
        if (!conversationParticipantRepo.isParticipant(msg.chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val botReactions = serviceMessageRepo.setReaction(messageId, bot.id, emoji)
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("cannot react"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, msg.chatId, messageId, "setMessageReaction")
        fanoutBotEvent(
            userRepo = userRepo,
            participantRepository = conversationParticipantRepo,
            json = json,
            botId = bot.id,
            chatId = msg.chatId,
            event = com.maodouchat.server.messaging.v2.ServiceMessagingV2Event(
                action = "REACTION_SET",
                targetMessageId = messageId,
                reactionEmoji = emoji,
            ),
        )
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", messageId)
put("emoji", emoji)
put("reactions", Json.parseToJsonElement(Json.encodeToString(botReactions)))
        }
    )
    }

    post("/api/bot/sendQuote") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val quote = (obj["quote"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(1500)
        val note = obj["note"]?.jsonPrimitive?.content.orEmpty().take(500)
        if (chatId.isBlank() || quote.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/quote required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val quoted = quote.lines().joinToString("\n") { "> " + it }
        val content = if (note.isNotBlank()) "$quoted\n\n$note" else quoted
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "MARKDOWN")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendQuote")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "MARKDOWN", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
        }
    )
    }

    get("/api/bot/getChatIds") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val chats = org.jetbrains.exposed.sql.transactions.transaction {
            com.maodouchat.server.db.ChatParticipants.selectAll()
                .where { com.maodouchat.server.db.ChatParticipants.userId eq bot.id }
                .map { it[com.maodouchat.server.db.ChatParticipants.chatId] }
                .distinct()
        }
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getChatIds")
        call.respond(
        buildJsonObject {
put("ok", true)
put("chatIds", Json.parseToJsonElement(Json.encodeToString(chats)))
put("count", chats.size)
        }
    )
    }

    post("/api/bot/clearCommands") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        val cleared = com.maodouchat.server.repository.BotRepository.setMyCommands(bot.id, emptyList())
            ?: return@post call.respondBotUnavailable()
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "clearCommands")
        call.respond(
            buildJsonObject {
                put("ok", true)
                put("commands", Json.parseToJsonElement(Json.encodeToString(cleared)))
                put("count", cleared.size)
            }
        )
    }

    post("/api/bot/starMessage") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMessageStarringEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("starring_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
        if (messageId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("messageId required"))
        }
        val msg = serviceMessageRepo.metadata(messageId)
            ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found"))
        if (!conversationParticipantRepo.isParticipant(msg.chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val starred = starMessageRepo.toggleStar(
            userId = bot.id,
            messageId = messageId,
            requireBotDeliverable = true
        )
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("cannot star"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, msg.chatId, messageId, "starMessage")
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", messageId)
put("starred", starred)
        }
    )
    }

    post("/api/bot/sendChecklist") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isMarkdownEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("markdown disabled by admin"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val title = obj["title"]?.jsonPrimitive?.content.orEmpty().take(80)
        val itemsEl = obj["items"] as? kotlinx.serialization.json.JsonArray
        val items = itemsEl?.mapNotNull {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.take(80)
        }?.filter { it.isNotBlank() }?.take(20).orEmpty()
        if (chatId.isBlank() || items.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/items required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val head = if (title.isNotBlank()) "**$title**\n" else ""
        val bodyMd = items.joinToString("\n") { "- [ ] $it" }
        val content = head + bodyMd
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "MARKDOWN")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendChecklist")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "MARKDOWN", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "MARKDOWN")
put("items", items.size)
        }
    )
    }

    get("/api/bot/getWebhookInfo") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@get
        val pending = com.maodouchat.server.repository.BotRepository.countPendingUpdates(bot.id)
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, null, null, "getWebhookInfo")
        call.respond(
        buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("url", (bot.webhookUrl ?: ""))
put("hasCustomCertificate", false)
put("pendingUpdateCount", pending)
put("maxConnections", 40)
put("enabled", bot.enabled)
        }
    )
    }



    post("/api/bot/sendPollQuiz") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupPlayEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_play_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val question = (obj["question"] ?: obj["text"])?.jsonPrimitive?.content.orEmpty().take(200)
        val optionsEl = obj["options"] as? kotlinx.serialization.json.JsonArray
        val options = optionsEl?.mapNotNull {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.take(80)
        }?.filter { it.isNotBlank() }?.take(10).orEmpty()
        val correct = obj["correctOptionIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        if (chatId.isBlank() || question.isBlank() || options.size < 2) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId/question/options required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val safeIdx = correct.coerceIn(0, options.lastIndex)
        val content = buildString {
            append("QUIZ:").append(question)
            options.forEachIndexed { i, o -> append("|").append(if (i == safeIdx) "*" else "").append(o) }
        }.take(2000)
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "TEXT")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendPollQuiz")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "TEXT", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("correctOptionIndex", safeIdx)
        }
    )
    }

    post("/api/bot/sendDiceCustom") {
        val bot = call.requireRateLimitedBot(botSendRateLimiter) ?: return@post
        if (!com.maodouchat.server.service.RuntimeConfigService.isGroupPlayEnabled()) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("group_play_disabled"))
        }
        val body = call.receiveBoundedTextOrEmpty()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
        val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
        val sides = (obj["sides"]?.jsonPrimitive?.content?.toIntOrNull() ?: 6).coerceIn(2, 100)
        if (chatId.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
        }
        if (!conversationParticipantRepo.isParticipant(chatId, bot.id)) {
            return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
        }
        val value = (1..sides).random()
        val content = "DICE:$sides|$value|bot dice roll"
        val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val now = System.currentTimeMillis()
        val ok = runCatching {
            serviceMessageRepo.insert(msgId, chatId, bot.id, content, now, "TEXT")
        }.getOrDefault(false)
        if (!ok) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
        com.maodouchat.server.repository.BotRepository.logCommand(bot.id, chatId, null, "sendDiceCustom")
        val botMessage = com.maodouchat.server.model.MessageResponse(
            id = msgId, chatId = chatId, senderId = bot.id, content = content,
            type = "TEXT", timestamp = now, status = "SENT"
        )
        fanoutBotMessage(userRepo, conversationParticipantRepo, json, bot.id, chatId, botMessage)
        call.respond(
        buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("sides", sides)
put("value", value)
        }
    )
    }

    configureBotPresentationRoutes(
        userRepository = userRepo,
        participantRepository = conversationParticipantRepo,
        serviceMessageRepository = serviceMessageRepo,
        botRateLimiter = botSendRateLimiter,
        json = json,
    )
}
