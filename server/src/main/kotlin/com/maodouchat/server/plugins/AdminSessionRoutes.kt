package com.maodouchat.server.plugins

import com.maodouchat.server.auth.JwtConfig
import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.db.*
import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** 管理会话签发：口令 + TOTP 二次确认换 5 分钟 admin session。 */
internal fun Route.configureAdminSessionRoutes(
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
) {
    post("/api/admin/session") {
        val principal = call.principal<JWTPrincipal>()!!
        val userId = principal.payload.subject
        if (JwtConfig.isAdminSession(principal.payload)) {
            return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("管理员会话不能续签自身"))
        }
        if (!AdminAccess.isAdmin(userId)) {
            // 9.247：自部署高频踩坑——MASTER_ADMINS 填了邮箱而非 userId，
            // 报错附带配置指引便于自查（不泄露当前配置值）
            // 9.4xx：同时记录尝试账号（名+id），运维可据此排查「登的是哪个号」
            val who = userRepo.getById(userId)
            adminAuditLogger.warn(
                "Admin session denied for user {} ({} / {}); not in MASTER_ADMINS",
                userId, who?.name.orEmpty(), who?.email.orEmpty()
            )
            return@post call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse(
                    "需要主管理员权限：当前登录账号为 ${who?.name.orEmpty().ifBlank { "?" }}（${userId}），" +
                        "请确认服务端 MASTER_ADMINS 环境变量包含该 userId（非邮箱）；" +
                        "若登录的是其他账号，请改用主管理员账号重新登录"
                )
            )
        }
        if (!adminSessionAttemptLimiter.acquire(userId)) {
            call.response.headers.append(HttpHeaders.RetryAfter, ADMIN_SESSION_ATTEMPT_WINDOW_SECONDS.toString())
            return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("管理员二次验证尝试过多，请稍后再试"))
        }
        val request = call.receiveAdminJson<AdminSessionRequest>()
            ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("请求无效"))
        // 口令与第二因子在同一事务的行锁下判定（见 CredentialService.verifyAdminCredentials）。
        val check = userRepo.verifyAdminCredentials(userId, request.password, request.totpCode)
        if (!check.passwordOk) {
            return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("管理员密码错误"))
        }
        if (!check.secondFactorOk) {
            // 账号启用了 TOTP 却没给（或给错）动态码——与「口令错」分开报，
            // 前端据此决定是弹动态码输入框还是提示重输。不泄露该码是否正确之外的任何信息。
            val missing = request.totpCode.isNullOrBlank()
            adminAuditLogger.warn(
                "Admin session second factor rejected for user {} (codeMissing={}, totpEnabled={})",
                userId, missing, check.totpEnabled
            )
            return@post call.respond(
                HttpStatusCode.Unauthorized,
                ErrorResponse(
                    if (missing) {
                        "该主管理员账号已启用动态验证码，换发管理会话需一并提交 totpCode"
                    } else {
                        "动态验证码错误或已过期"
                    },
                    code = if (missing) "TOTP_REQUIRED" else "TOTP_INVALID",
                )
            )
        }
        adminSessionAttemptLimiter.reset(userId)
        val issuedAt = System.currentTimeMillis()
        val token = JwtConfig.generateAdminToken(
            userId = userId,
            tokenVersion = authTokenRepo.getAccessTokenVersion(userId),
            issuedAtMs = issuedAt
        )
        recordAdminAudit(userId, "ADMIN_SESSION_ISSUED", "expiresAt=${JwtConfig.adminTokenExpiresAt(issuedAt)}")
        call.respond(AdminSessionResponse(token, JwtConfig.adminTokenExpiresAt(issuedAt)))
    }
}
