package com.maodouchat.server.plugins

import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.EncryptedAttachmentStorage
import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*

/** Bot 媒体发送与附件/成员辅助（自 BotCoreRouting.kt 拆分，B12）。 */
internal fun Route.configureBotMediaRoutes(
    userRepo: UserRepository,
    serviceMessageRepo: ServiceMessageRepository,
    groupMembershipService: GroupMembershipService,
    groupModerationRepo: GroupModerationRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    botSendRateLimiter: BoundedRateLimiter,
    json: Json,
) {

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
        val commit = groupMembershipService.updateRole(
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
}
