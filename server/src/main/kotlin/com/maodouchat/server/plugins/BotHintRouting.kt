package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.MessageResponse
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ServiceMessageRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Durable bot-authored hint messages described by policy data instead of copied handlers. */
internal fun Route.configureBotHintRoutes(
    userRepository: UserRepository,
    participantRepository: ConversationParticipantRepository,
    serviceMessageRepository: ServiceMessageRepository,
    botRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    BOT_HINT_SPECS.forEach { spec ->
        post("/api/bot/${spec.path}") {
            val bot = call.requireRateLimitedBot(botRateLimiter) ?: return@post
            if (!spec.enabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse(spec.disabledError))
                return@post
            }
            val body = call.receiveBoundedTextOrEmpty()
            val request = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
                    return@post
                }
            val chatId = request["chatId"]?.jsonPrimitive?.content.orEmpty()
            if (chatId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
                return@post
            }
            if (!participantRepository.isParticipant(chatId, bot.id)) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
                return@post
            }
            val suppliedHint = request["hint"]?.jsonPrimitive?.content
            val hint = if (spec.sanitize) {
                sanitizeBotHint(suppliedHint).ifBlank { spec.defaultHint }
            } else {
                (suppliedHint ?: spec.defaultHint).take(120)
            }
            val content = spec.contentPrefix + hint
            val messageId = "bot_" + UUID.randomUUID().toString().replace("-", "").take(16)
            val now = System.currentTimeMillis()
            val inserted = runCatching {
                serviceMessageRepository.insert(messageId, chatId, bot.id, content, now, "SYSTEM")
            }.getOrDefault(false)
            if (!inserted) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
                return@post
            }
            BotRepository.logCommand(bot.id, chatId, null, spec.path)
            val message = MessageResponse(
                id = messageId,
                chatId = chatId,
                senderId = bot.id,
                content = content,
                type = "SYSTEM",
                timestamp = now,
                status = "SENT",
            )
            fanoutBotMessage(userRepository, participantRepository, json, bot.id, chatId, message)
            call.respond(buildJsonObject {
                put("ok", true)
                put("messageId", messageId)
                put("type", "SYSTEM")
            })
        }
    }
}

private data class BotHintSpec(
    val path: String,
    val enabled: () -> Boolean,
    val disabledError: String,
    val defaultHint: String,
    val contentPrefix: String,
    val sanitize: Boolean = false,
)

private val BOT_HINT_SPECS = listOf(
    BotHintSpec("sendVoiceCallHint", RuntimeConfigService::isVoiceCallEnabled, "voice_call_disabled", "Voice call can be toggled separately from master calls", "CALL:VOICE "),
    BotHintSpec("sendVideoCallHint", RuntimeConfigService::isVideoCallEnabled, "video_call_disabled", "Video call can be toggled separately from master calls", "CALL:VIDEO "),
    BotHintSpec("sendWallpaperHint", RuntimeConfigService::isChatWallpaperEnabled, "chat_wallpaper_disabled", "Chat wallpaper can be toggled by admins", "THEME:WALL "),
    BotHintSpec("sendFontScaleHint", RuntimeConfigService::isChatFontScaleEnabled, "chat_font_scale_disabled", "Chat font scale can be toggled by admins", "THEME:FONT "),
    BotHintSpec("sendUnreadHint", RuntimeConfigService::isUnreadPriorityEnabled, "unread_priority_disabled", "Unread priority can be toggled by admins", "NOTIFY:UNREAD "),
    BotHintSpec("sendRingtoneHint", RuntimeConfigService::isRingtoneEnabled, "ringtone_disabled", "Ringtone can be toggled by admins", "NOTIFY:RING "),
    BotHintSpec("sendSoundHint", RuntimeConfigService::isNotificationSoundEnabled, "notification_sound_disabled", "Notification sound can be toggled by admins", "ALERT:SOUND "),
    BotHintSpec("sendPreviewHint", RuntimeConfigService::isNotificationPreviewEnabled, "notification_preview_disabled", "Notification preview can be toggled by admins", "ALERT:PREVIEW "),
    BotHintSpec("sendPushHint", RuntimeConfigService::isPushNotificationsEnabled, "push_notifications_disabled", "Push notifications can be toggled by admins", "PUSH:MASTER "),
    BotHintSpec("sendTaskReminderHint", RuntimeConfigService::isTaskRemindersEnabled, "task_reminders_disabled", "Task reminders can be toggled by admins", "PUSH:TASK "),
    BotHintSpec("sendDndHint", RuntimeConfigService::isDndEnabled, "dnd_disabled", "Do-not-disturb windows can be toggled by admins", "QUIET:DND "),
    BotHintSpec("sendSoundscapeHint", RuntimeConfigService::isInAppSoundsEnabled, "in_app_sounds_disabled", "In-app sounds can be toggled by admins", "FEEL:SOUND "),
    BotHintSpec("sendHapticsHint", RuntimeConfigService::isHapticsEnabled, "haptics_disabled", "Haptics can be toggled by admins", "FEEL:HAPTIC "),
    BotHintSpec("sendMotionHint", RuntimeConfigService::isChatAnimationsEnabled, "chat_animations_disabled", "Chat animations can be toggled by admins", "MOTION:CHAT "),
    BotHintSpec("sendNavHint", RuntimeConfigService::isNavTransitionsEnabled, "nav_transitions_disabled", "Navigation transitions can be toggled by admins", "MOTION:NAV "),
    BotHintSpec("sendCaptureDetectHint", RuntimeConfigService::isScreenshotDetectEnabled, "screenshot_detect_disabled", "Screenshot detection can be toggled by admins", "SHIELD:DETECT "),
    BotHintSpec("sendRecentsHint", RuntimeConfigService::isRecentsExclusionEnabled, "recents_exclusion_disabled", "Recents exclusion can be toggled by admins", "SHIELD:RECENTS "),
    BotHintSpec("sendSecretCopyHint", RuntimeConfigService::isSecretCopyBlockEnabled, "secret_copy_block_disabled", "Secret chat copy block can be toggled by admins", "LEAK:COPY "),
    BotHintSpec("sendSecretExportHint", RuntimeConfigService::isSecretMediaExportBlockEnabled, "secret_media_export_block_disabled", "Secret media export block can be toggled by admins", "LEAK:EXPORT "),
    BotHintSpec("sendSecretForwardHint", RuntimeConfigService::isSecretForwardBlockEnabled, "secret_forward_block_disabled", "Secret chat forward block can be toggled by admins", "VAULT:FORWARD "),
    BotHintSpec("sendSecretChatExportHint", RuntimeConfigService::isSecretChatExportBlockEnabled, "secret_chat_export_block_disabled", "Secret chat export block can be toggled by admins", "VAULT:EXPORT "),
    BotHintSpec("sendSealedSenderHint", RuntimeConfigService::isSealedSenderEnabled, "sealed_sender_disabled", "Sealed sender certs hide sender metadata on delivery hops", "SEAL:CERT "),
    BotHintSpec("sendPqxdhHint", RuntimeConfigService::isPqxdhPreviewEnabled, "pqxdh_preview_disabled", "PQXDH preview can be toggled by admins", "SEAL:PQXDH "),
    BotHintSpec("sendSecretAutoDisappearHint", RuntimeConfigService::isSecretAutoDisappearEnabled, "secret_auto_disappear_disabled", "Secret chats auto-enable 24h disappearing when timer is off", "MARK:AUTO24H "),
    BotHintSpec("sendSecretLinkPreviewHint", RuntimeConfigService::isSecretLinkPreviewBlockEnabled, "secret_link_preview_block_disabled", "Secret chats block link previews to avoid external fetches", "LINK:PREVIEW "),
    BotHintSpec("sendSecretExternalLinkHint", RuntimeConfigService::isSecretExternalLinkBlockEnabled, "secret_external_link_block_disabled", "Secret chats can block opening external links", "LINK:EXTERNAL "),
    BotHintSpec("sendSecretNotifPreviewHint", RuntimeConfigService::isSecretNotifPreviewBlockEnabled, "secret_notif_preview_block_disabled", "Secret chat notifications hide sender and body previews", "NOTIFY:SECRET "),
    BotHintSpec("sendSecretListPreviewHint", RuntimeConfigService::isSecretListPreviewBlockEnabled, "secret_list_preview_block_disabled", "Secret chats hide message previews in the chat list", "NOTIFY:LIST "),
    BotHintSpec("sendSecretReactionHint", RuntimeConfigService::isSecretReactionBlockEnabled, "secret_reaction_block_disabled", "Secret chats block reactions to reduce metadata leaks", "META:REACT ", sanitize = true),
    BotHintSpec("sendSecretStarHint", RuntimeConfigService::isSecretStarBlockEnabled, "secret_star_block_disabled", "Secret chats block starring to avoid cloud-side favorites metadata", "META:STAR ", sanitize = true),
    BotHintSpec("sendSecretTypingHint", RuntimeConfigService::isSecretTypingBlockEnabled, "secret_typing_block_disabled", "Secret chats block typing indicators to avoid presence side-channel", "META:TYPING ", sanitize = true),
    BotHintSpec("sendSecretReadReceiptHint", RuntimeConfigService::isSecretReadReceiptBlockEnabled, "secret_read_receipt_block_disabled", "Secret chats block read receipts to avoid observation side-channel", "META:READ ", sanitize = true),
    BotHintSpec("sendSecretPresenceHint", RuntimeConfigService::isSecretPresenceBlockEnabled, "secret_presence_block_disabled", "Secret chats block presence/online to avoid online side-channel", "META:PRESENCE ", sanitize = true),
    BotHintSpec("sendSecretLastSeenHint", RuntimeConfigService::isSecretLastSeenBlockEnabled, "secret_last_seen_block_disabled", "Secret chats block last seen to avoid observation side-channel", "META:LASTSEEN ", sanitize = true),
)
