package com.maodouchat.server.plugins

import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal

/**
 * 路由层调用方身份提取（B02）。
 *
 * 收敛 200+ 处 `call.principal<JWTPrincipal>()!!.payload.subject` 样板：
 * 缺失 principal 时行为与原来一致（Kotlin `!!` 抛错，由上层 StatusPages 处理）。
 */
fun ApplicationCall.requireUserId(): String = principal<JWTPrincipal>()!!.payload.subject

/**
 * 可选身份（公开端点的 viewer 语义）：无 principal 时返回 null，
 * 与 `call.principal<JWTPrincipal>()?.payload?.subject` 等价。
 */
fun ApplicationCall.optionalUserId(): String? = principal<JWTPrincipal>()?.payload?.subject

/**
 * 设备会话绑定（B02 DeviceSession：auth session → user + 已确认 Signal device）。
 *
 * 收敛 V2 路由三处 `authSessionId → resolveAuthenticatedDevice` 设备门：
 * principal 缺失沿用 `!!` 语义；session/device 未绑定返回 null，
 * 调用方按原样回 409 DEVICE_NOT_READY。
 */
data class DeviceSessionBinding(
    val userId: String,
    val authSessionId: String,
    val deviceId: Int,
)

fun ApplicationCall.deviceSessionBinding(
    repository: com.maodouchat.server.messaging.v2.MessagingV2Repository,
): DeviceSessionBinding? {
    val principal = principal<JWTPrincipal>()!!
    val userId = principal.payload.subject
    val authSessionId = com.maodouchat.server.auth.JwtConfig.authSessionId(principal.payload) ?: return null
    val deviceId = repository.resolveAuthenticatedDevice(userId, authSessionId) ?: return null
    return DeviceSessionBinding(userId, authSessionId, deviceId)
}
