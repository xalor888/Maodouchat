package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.BotRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** 开发者账号·会话：登录（IP+账号双限流）与当前用户。 */
internal fun Route.configureDeveloperAccountSessionRoutes(
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
    developerLoginRateLimiter: BoundedRateLimiter,
    developerLoginEmailRateLimiter: BoundedRateLimiter,
) {

        post("/login") {
            if (!developerLoginRateLimiter.acquire(
                    call.remoteHost(),
                    maxPerMinute = ServerConfig.authRateLimitPerMinute
                )
            ) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("登录过于频繁，请稍后再试"))
            }
            val body = call.receiveBoundedText().orEmpty()
            val obj = call.requireJsonObjectOr400(body) ?: return@post
            val (email, password, totpCode) = parseDeveloperLoginFields(obj)
            if (email.isBlank() || password.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("邮箱或密码不能为空"))
            }
            // 8.131：与主登录一致补按账号限流——此前仅按 IP 限流，攻击者轮换源 IP
            // 即可对同一开发者账号无限爆破（主登录早有 loginEmailRateLimiter 堵这个洞）
            val emailKey = runCatching { email.normalizedEmail() }.getOrDefault(email)
            if (!developerLoginEmailRateLimiter.acquire(emailKey, maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("该账号尝试过于频繁，请稍后再试"))
            }
            val loginResult = userRepo.loginWithFactors(email, password, totpCode)
            when {
                !loginResult.passwordOk -> {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("邮箱或密码错误", code = "AUTH_INVALID"))
                }
                loginResult.totpEnabled && !loginResult.totpOk -> {
                    // 200 so the console can surface the TOTP step without treating it as a transport error.
                    call.respond(
                        DevLoginResponse(
                            requiresTotp = true,
                            token = "",
                            userId = "",
                            email = email,
                            name = "",
                            bots = emptyList()
                        )
                    )
                }
                loginResult.user != null -> {
                    val user = checkNotNull(loginResult.user)
                    // 失败闭合：未配置开发者白名单时拒绝所有人，避免任意已登录账号绕过权限获取开发者会话（权限提升）。
                    // 必须通过 DEVELOPER_USER_IDS 显式授权才允许创建/管理机器人。
                    if (user.id !in ServerConfig.developerUserIds) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("开发者功能未启用或需要开发者权限"))
                        return@post
                    }
                    val tokenVersion = authTokenRepo.getAccessTokenVersion(user.id)
                    val devToken = mintDevSessionToken(user.id, tokenVersion)
                    val bots = BotRepository.listByOwner(user.id)
                    call.respond(
                        DevLoginResponse(
                            requiresTotp = false,
                            token = devToken,
                            userId = user.id,
                            email = user.email,
                            name = user.name,
                            bots = bots
                        )
                    )
                }
                else -> {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("邮箱或密码错误", code = "AUTH_INVALID"))
                }
            }
        }

        get("/me") {
            val userId = devSessionUserId(call)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("开发者会话无效或已过期"))
            val user = userRepo.getById(userId)
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("用户不存在"))
            val bots = BotRepository.listByOwner(userId)
            call.respond(DevMeResponse(userId = user.id, email = user.email, name = user.name, bots = bots))
        }
}
