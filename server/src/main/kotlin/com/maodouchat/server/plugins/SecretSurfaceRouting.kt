package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.MessageResponse
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ServiceMessageRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 8.46：hint 写端点按 bot 限流（防 bot 反复刷 SYSTEM 消息）。 */
private val hintRateLimiter = BoundedRateLimiter()

/** 9.136：hint 消息 WS fanout 的 JSON 实例（与 Routing.kt 的 routingJson 同配置）。 */
private val hintJson = Json { ignoreUnknownKeys = true }

/**
 * B2 密聊防泄漏扩展路由（Surface #71–#78）。
 *
 * 8 个新 surface 的 Bot flags / healthz（burnz ttlz fwlz simz 2faz ndz dvz sntz）
 * 与 hint 路由，独立于巨型 Routing.kt，在 Application.kt 末尾注册。
 *
 * 服务端只持有「开关位」：密聊内容全程 E2EE，服务端不接触密聊明文；
 * 这些路由仅帮助客户端/机器人展示/校验本地防护门控。
 *
 * hint 消息按既有 `bot_` 前缀 + "SYSTEM" 类型写入（前缀加密传输），
 * 内容为固定引导文案，不含任何用户密聊明文。
 */
fun Application.configureSecretSurfaceRouting(
    userRepo: UserRepository
) {
    val participantRepository = ConversationParticipantRepository()
    routing {
        configureSecretSurfaceRoutes(participantRepository, userRepo)
    }
}

/**
 * 向 /api/public/status 追加 8 个新 surface 的客户端能力门字段。
 * 由 Routing.kt 的公共状态端点调用（只追加 JSON 键，不触碰已有字段）。
 */
fun publicSecretSurfaceFlags(): Map<String, Boolean> = mapOf(
    "secretScreenshotBurnEnabled" to RuntimeConfigService.isSecretScreenshotBurnEnabled(),
    "secretAutoDestroyEnabled" to RuntimeConfigService.isSecretAutoDestroyEnabled(),
    "secretForwardWhitelistEnabled" to RuntimeConfigService.isSecretForwardWhitelistEnabled(),
    "secretSimChangeProtectionEnabled" to RuntimeConfigService.isSecretSimChangeProtectionEnabled(),
    "secret2faGateEnabled" to RuntimeConfigService.isSecret2faGateEnabled(),
    "secretNewDeviceRiskEnabled" to RuntimeConfigService.isSecretNewDeviceRiskEnabled(),
    "secretDeviceVerifyEnabled" to RuntimeConfigService.isSecretDeviceVerifyEnabled(),
    "secretSessionNoticeEnabled" to RuntimeConfigService.isSecretSessionNoticeEnabled()
)

/** 8 个新 surface 的 Bot 能力门字段（对应既有 getXxxFlags 返回体风格）。 */
private fun secretSurfaceBotFlags(): Map<String, Boolean> = mapOf(
    "secretScreenshotBurnEnabled" to RuntimeConfigService.isSecretScreenshotBurnEnabled(),
    "secretAutoDestroyEnabled" to RuntimeConfigService.isSecretAutoDestroyEnabled(),
    "secretForwardWhitelistEnabled" to RuntimeConfigService.isSecretForwardWhitelistEnabled(),
    "secretSimChangeProtectionEnabled" to RuntimeConfigService.isSecretSimChangeProtectionEnabled(),
    "secret2faGateEnabled" to RuntimeConfigService.isSecret2faGateEnabled(),
    "secretNewDeviceRiskEnabled" to RuntimeConfigService.isSecretNewDeviceRiskEnabled(),
    "secretDeviceVerifyEnabled" to RuntimeConfigService.isSecretDeviceVerifyEnabled(),
    "secretSessionNoticeEnabled" to RuntimeConfigService.isSecretSessionNoticeEnabled()
)

private fun Routing.configureSecretSurfaceRoutes(
    participantRepository: ConversationParticipantRepository,
    userRepo: UserRepository
) {
    // ── 8 个新 surface 的 healthz（burnz/ttlz/fwlz/simz/2faz/ndz/dvz/sntz）──
    get("/api/bot/burnz") { surfaceHealth(call, "burnz", 71) }
    get("/api/bot/ttlz") { surfaceHealth(call, "ttlz", 72) }
    get("/api/bot/fwlz") { surfaceHealth(call, "fwlz", 73) }
    get("/api/bot/simz") { surfaceHealth(call, "simz", 74) }
    get("/api/bot/2faz") { surfaceHealth(call, "2faz", 75) }
    get("/api/bot/ndz") { surfaceHealth(call, "ndz", 76) }
    get("/api/bot/dvz") { surfaceHealth(call, "dvz", 77) }
    get("/api/bot/sntz") { surfaceHealth(call, "sntz", 78) }

    // ── flags 集合（一次取回全部 8 个门）──
    get("/api/bot/getSecretSurfaceFlags") {
        val bot = authenticateBot(call) ?: return@get
        BotRepository.logCommand(bot.id, null, null, "getSecretSurfaceFlags")
        call.respond(
            buildJsonObject {
                put("ok", true)
                put("botId", bot.id)
                secretSurfaceBotFlags().forEach { (k, v) -> put(k, v) }
                put("surface", 71)
            }
        )
    }

    // ── 8 个新 surface 的 hint 路由（SYSTEM 消息，引导文案，无密聊明文）──
    post("/api/bot/sendSecretScreenshotBurnHint") { sendSecretSurfaceHint(call, participantRepository, userRepo, RuntimeConfigService.KEY_SECRET_SCREENSHOT_BURN_ENABLED, "BURN:SCREEN", "Secret chats burn local media cache when a screenshot attempt is detected") }
    post("/api/bot/sendSecretAutoDestroyHint") { sendSecretSurfaceHint(call, participantRepository, userRepo, RuntimeConfigService.KEY_SECRET_AUTO_DESTROY_ENABLED, "TTL:AUTODESTROY", "Secret chats auto-destroy after a session inactivity TTL") }
    post("/api/bot/sendSecretForwardWhitelistHint") { sendSecretSurfaceHint(call, participantRepository, userRepo, RuntimeConfigService.KEY_SECRET_FORWARD_WHITELIST_ENABLED, "FWL:WHITELIST", "Secret chat forwards are limited to the whitelist") }
    post("/api/bot/sendSecretSimChangeHint") { sendSecretSurfaceHint(call, participantRepository, userRepo, RuntimeConfigService.KEY_SECRET_SIM_CHANGE_PROTECTION_ENABLED, "SIM:LOCK", "Secret chats lock when the SIM changes or is removed") }
    post("/api/bot/sendSecret2faGateHint") { sendSecretSurfaceHint(call, participantRepository, userRepo, RuntimeConfigService.KEY_SECRET_2FA_GATE_ENABLED, "2FA:GATE", "Secret chats require a second factor before opening") }
    post("/api/bot/sendSecretNewDeviceRiskHint") { sendSecretSurfaceHint(call, participantRepository, userRepo, RuntimeConfigService.KEY_SECRET_NEW_DEVICE_RISK_ENABLED, "NDV:RISK", "Secret chats lock on untrusted new devices") }
    post("/api/bot/sendSecretDeviceVerifyHint") { sendSecretSurfaceHint(call, participantRepository, userRepo, RuntimeConfigService.KEY_SECRET_DEVICE_VERIFY_ENABLED, "DVZ:VERIFY", "Verify the peer device fingerprint before secret chats") }
    post("/api/bot/sendSecretSessionNoticeHint") { sendSecretSurfaceHint(call, participantRepository, userRepo, RuntimeConfigService.KEY_SECRET_SESSION_NOTICE_ENABLED, "SNT:NOTICE", "Secret chat notices show when both sides enable secret mode") }
}

// ── 私有辅助 ──────────────────────────────

/** 单个 surface 的 healthz：鉴权 + 记命令 + 返回 ok/surface/ping。鉴权失败时已响应 401 并返回 null。 */
private suspend fun surfaceHealth(
    call: io.ktor.server.application.ApplicationCall,
    name: String,
    surface: Int
) {
    val bot = authenticateBot(call) ?: return
    BotRepository.logCommand(bot.id, null, null, name)
    call.respond(
                buildJsonObject {
put("ok", true)
put("botId", bot.id)
put("surface", surface)
put("ping", name)
                }
            )
}

/** 从 X-Bot-Token / Authorization Bearer 提取并校验 Bot，失败时已响应 401 并返回 null。 */
private suspend fun authenticateBot(call: io.ktor.server.application.ApplicationCall): BotRepository.BotDto? {
    val headerToken = call.request.headers["X-Bot-Token"].orEmpty()
    val bearer = call.request.headers["Authorization"].bearerTokenOrNull().orEmpty()
    val token = headerToken.ifBlank { bearer }
    return BotRepository.authenticate(token)
        ?: run {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid bot token"))
            null
        }
}

/** 写入一条 bot SYSTEM 引导消息（前缀加密传输，内容为固定文案，不含密聊明文）。 */
private suspend fun sendSecretSurfaceHint(
    call: io.ktor.server.application.ApplicationCall,
    participantRepository: ConversationParticipantRepository,
    userRepo: UserRepository,
    gateKey: String,
    prefix: String,
    defaultHint: String
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
    val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json"))
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 9.136：hint 与 Routing.kt 家族一致走 sanitizeBotHint——控制字符/换行不得进入 SYSTEM 消息
    val hint = sanitizeBotHint(obj["hint"]?.jsonPrimitive?.content).ifBlank { defaultHint }
    if (chatId.isBlank()) return call.respond(HttpStatusCode.BadRequest, ErrorResponse("chatId required"))
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
    fanoutBotMessage(userRepo, participantRepository, hintJson, bot.id, chatId, botMessage)
    call.respond(
                buildJsonObject {
put("ok", true)
put("messageId", msgId)
put("type", "SYSTEM")
                }
            )
}
