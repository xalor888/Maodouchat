package com.maodouchat.server.plugins

import com.maodouchat.server.service.MfaService
import io.ktor.server.routing.*


// 登录后会话管理（全设备登出、TOTP 状态/恢复码/启用/确认/停用）。
internal fun Route.configureAuthSessionRoutes(
    mfaService: MfaService,
    sessionService: com.maodouchat.server.service.SessionService,
    totpManageRateLimiter: BoundedRateLimiter,
) {
    configureAuthSessionLogoutRoutes(sessionService)
    configureAuthSessionTotpRoutes(mfaService, totpManageRateLimiter)
}
