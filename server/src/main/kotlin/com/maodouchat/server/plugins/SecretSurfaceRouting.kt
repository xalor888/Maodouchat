package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

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
    userRepo: UserRepository,
    messagingV2Repository: com.maodouchat.server.messaging.v2.MessagingV2Repository = com.maodouchat.server.messaging.v2.MessagingV2Repository(),
) {
    val participantRepository = ConversationParticipantRepository()
    routing {
        configureSecretSurfaceHealthzRoutes()
        configureSecretSurfaceFlagsRoutes()
        configureSecretSurfaceHintRoutes(participantRepository, userRepo, messagingV2Repository)
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
