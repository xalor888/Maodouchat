package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.MfaService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*


// 登录后会话管理（全设备登出、TOTP 状态/恢复码/启用/确认/停用）。
internal fun Route.configureAuthSessionRoutes(
    mfaService: MfaService,
    sessionService: com.maodouchat.server.service.SessionService,
    totpManageRateLimiter: BoundedRateLimiter,
) {
    authenticate("auth-jwt") {
            post("/api/auth/logout-all") {
                val userId = call.requireUserId()
                sessionService.revokeAllUserSessions(userId)
                // 全设备登出后必须清掉推送 token，否则已退出设备仍可能收到来电唤醒。
                disconnectUserSessions(userId, "已在其他设备退出全部会话")
                call.respond(
                buildJsonObject {
put("status", "ok")
                }
            )
            }

            get("/api/auth/totp/status") {
                val userId = call.requireUserId()
                call.respond(TotpStatusResponse(enabled = mfaService.isTotpEnabled(userId)))
            }

            // 0.77：重新生成恢复码（验证当前 TOTP；旧码全部作废）
            post("/api/auth/totp/recover-codes") {
                val userId = call.requireUserId()
                if (!totpManageRateLimiter.acquire(userId, maxPerMinute = 5)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                }
                val body = call.receiveBoundedTextOrEmpty()
                val code = parseAuthTotpCode(body)
                val codes = mfaService.regenerateBackupCodes(userId, code)
                if (codes == null) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid totp code", code = "TOTP_INVALID"))
                }
                call.respond(TotpStatusResponse(enabled = true, backupCodes = codes))
            }

            post("/api/auth/totp/setup") {
                val userId = call.requireUserId()
                val setup = mfaService.beginTotpSetup(userId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("user not found"))
                call.respond(
                    TotpSetupResponse(
                        secret = setup.first,
                        otpauthUrl = setup.second,
                        enabled = false
                    )
                )
            }

            post("/api/auth/totp/confirm") {
                val userId = call.requireUserId()
                // 8.40：2FA 管理端点限流 + 失败锁定——6 位码 ±1 窗口可爆破，此前无限流
                if (!totpManageRateLimiter.acquire(userId, maxPerMinute = 5)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                }
                val body = call.receiveBoundedTextOrEmpty()
                val code = parseAuthTotpCode(body)
                val backupCodes = mfaService.confirmTotpSetup(userId, code)
                if (backupCodes == null) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid totp code", code = "TOTP_INVALID"))
                }
                // 0.75：恢复码明文仅此一次返回（App 提示用户妥善保存）
                call.respond(TotpStatusResponse(enabled = true, backupCodes = backupCodes))
            }

            post("/api/auth/totp/disable") {
                val userId = call.requireUserId()
                // 8.40：与 confirm 一致限流；disable 需验码，爆破同样应被抑制
                if (!totpManageRateLimiter.acquire(userId, maxPerMinute = 5)) {
                    return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                }
                val body = call.receiveBoundedTextOrEmpty()
                val code = parseAuthTotpCode(body)
                if (!mfaService.disableTotp(userId, code)) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid totp code", code = "TOTP_INVALID"))
                }
                call.respond(TotpStatusResponse(enabled = false))
            }
    }
}
