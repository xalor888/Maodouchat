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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 8.46：hint 写端点按 bot 限流（防 bot 反复刷 SYSTEM 消息）。 */
private val hintRateLimiter = BoundedRateLimiter()

/** 9.136：hint 消息 WS fanout 的 JSON 实例（与 Routing.kt 的 routingJson 同配置）。 */
private val hintJson = Json { ignoreUnknownKeys = true }

internal fun Route.configureSecretSurfaceHintRoutes(
    participantRepository: ConversationParticipantRepository,
    userRepo: UserRepository,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
    // ── 8 个新 surface 的 hint 路由（SYSTEM 消息，引导文案，无密聊明文）──
    post("/api/bot/sendSecretScreenshotBurnHint") { sendSecretSurfaceHint(call, participantRepository, userRepo, RuntimeConfigService.KEY_SECRET_SCREENSHOT_BURN_ENABLED, "BURN:SCREEN", "Secret chats burn local media cache when a screenshot attempt is detected", messagingV2Repository) }
    post("/api/bot/sendSecretAutoDestroyHint") { sendSecretSurfaceHint(call, participantRepository, userRepo, RuntimeConfigService.KEY_SECRET_AUTO_DESTROY_ENABLED, "TTL:AUTODESTROY", "Secret chats auto-destroy after a session inactivity TTL", messagingV2Repository) }
    post("/api/bot/sendSecretForwardWhitelistHint") { sendSecretSurfaceHint(call, participantRepository, userRepo, RuntimeConfigService.KEY_SECRET_FORWARD_WHITELIST_ENABLED, "FWL:WHITELIST", "Secret chat forwards are limited to the whitelist", messagingV2Repository) }
    post("/api/bot/sendSecretSimChangeHint") { sendSecretSurfaceHint(call, participantRepository, userRepo, RuntimeConfigService.KEY_SECRET_SIM_CHANGE_PROTECTION_ENABLED, "SIM:LOCK", "Secret chats lock when the SIM changes or is removed", messagingV2Repository) }
    post("/api/bot/sendSecret2faGateHint") { sendSecretSurfaceHint(call, participantRepository, userRepo, RuntimeConfigService.KEY_SECRET_2FA_GATE_ENABLED, "2FA:GATE", "Secret chats require a second factor before opening", messagingV2Repository) }
    post("/api/bot/sendSecretNewDeviceRiskHint") { sendSecretSurfaceHint(call, participantRepository, userRepo, RuntimeConfigService.KEY_SECRET_NEW_DEVICE_RISK_ENABLED, "NDV:RISK", "Secret chats lock on untrusted new devices", messagingV2Repository) }
    post("/api/bot/sendSecretDeviceVerifyHint") { sendSecretSurfaceHint(call, participantRepository, userRepo, RuntimeConfigService.KEY_SECRET_DEVICE_VERIFY_ENABLED, "DVZ:VERIFY", "Verify the peer device fingerprint before secret chats", messagingV2Repository) }
    post("/api/bot/sendSecretSessionNoticeHint") { sendSecretSurfaceHint(call, participantRepository, userRepo, RuntimeConfigService.KEY_SECRET_SESSION_NOTICE_ENABLED, "SNT:NOTICE", "Secret chat notices show when both sides enable secret mode", messagingV2Repository) }
}

/** 写入一条 bot SYSTEM 引导消息（前缀加密传输，内容为固定文案，不含密聊明文）。 */
private suspend fun sendSecretSurfaceHint(
    call: io.ktor.server.application.ApplicationCall,
    participantRepository: ConversationParticipantRepository,
    userRepo: UserRepository,
    gateKey: String,
    prefix: String,
    defaultHint: String,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
) {
    val bot = authenticateBot(call) ?: return
    // 8.46 修复：8 个 hint 写端点此前无 per-bot 限流——bot 认证通过即可反复往任意
    // 所在群写 SYSTEM 消息刷屏；与 PollRouting 各写端点一致按 botId 限流。
    if (!hintRateLimiter.acquire(bot.id, maxPerMinute = 30)) {
        return call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作太频繁，请稍后再试"))
    }
    if (!RuntimeConfigService.getBoolean(gateKey, false)) {
        return call.respond(HttpStatusCode.Forbidden, ErrorResponse("surface_gate_disabled"))
    }
    val body = call.receiveBoundedTextOrEmpty()
    val obj = call.requireJsonObjectOr400(body) ?: return
    // 9.136：hint 与 Routing.kt 家族一致走 sanitizeBotHint——控制字符/换行不得进入 SYSTEM 消息
    val (rawChatId, hint) = parseSecretSurfaceHint(obj, defaultHint)
    val chatId = call.requireNonBlankValueOr400(rawChatId, "chatId required") ?: return
    if (!participantRepository.isParticipant(chatId, bot.id)) return call.respond(HttpStatusCode.Forbidden, ErrorResponse("bot not in chat"))
    val content = "$prefix " + hint
    val msgId = "bot_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    val now = System.currentTimeMillis()
    val ok = runCatching {
        ServiceMessageRepository().insert(msgId, chatId, bot.id, content, now, "SYSTEM")
    }.getOrDefault(false)
    if (!ok) return call.respond(HttpStatusCode.BadRequest, ErrorResponse("send failed"))
    BotRepository.logCommand(bot.id, chatId, null, "sendSecretSurfaceHint:$prefix")
    // 9.136：与 Routing.kt 经典 bot 端点一致补实时 WS fanout（9.131 遗漏本文件 8 个端点——
    // 此前仅落库，在线成员需重新拉历史才可见）
    val botMessage = MessageResponse(
        id = msgId, chatId = chatId, senderId = bot.id, content = content,
        type = "SYSTEM", timestamp = now, status = "SENT"
    )
    fanoutBotMessage(userRepo, participantRepository, hintJson, bot.id, chatId, botMessage, messagingV2Repository = messagingV2Repository)
    call.respond(
                buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
                }
            )
}
