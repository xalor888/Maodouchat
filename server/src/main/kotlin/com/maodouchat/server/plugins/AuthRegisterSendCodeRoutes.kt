package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.SendCodeRequest
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.getByEmail
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.isRegistrationAllowed
import com.maodouchat.server.service.sendVerificationCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 验证码发送。 */
internal fun Route.configureAuthRegisterSendCodeRoutes(
    userRepo: UserRepository,
    sendCodeRateLimiter: BoundedRateLimiter,
    sendCodeIpRateLimiter: BoundedRateLimiter,
) {
                post("/api/auth/send-code") {
                    val req = call.receiveJsonOr400<SendCodeRequest>() ?: return@post
                    val purpose = req.purpose.trim().lowercase().ifBlank { "register" }
                    val isReset = purpose == com.maodouchat.server.service.EmailService.PURPOSE_RESET
                    if (!isReset && !RuntimeConfigService.isRegistrationAllowed()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("注册已关闭"))
                        return@post
                    }
                    val email = runCatching { req.email.normalizedEmail() }.getOrNull()
                    if (email == null || email.isBlank() || !email.contains("@")) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("邮件格式无效"))
                        return@post
                    }
                    // Reject blocked registration domains before consuming limiter capacity or
                    // sending mail. Password-reset requests remain intentionally unaffected.
                    if (!isReset && isRegistrationEmailDomainBlocked(email)) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse("该邮箱域名已被禁止注册", code = "EMAIL_DOMAIN_BLOCKED")
                        )
                        return@post
                    }
                    // 频率限制：先检查 IP 级限制（防邮件轰炸），再检查邮箱级限制
                    // 顺序很重要：如果先消费邮箱配额再被 IP 拒绝，共享 IP 下的用户会被误伤
                    if (!sendCodeIpRateLimiter.acquireSendCodeIp(call.remoteHost())) {
                        call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("该网络发送验证码次数过多，请稍后再试"))
                        return@post
                    }
                    // 每邮箱每分钟最多 3 次；拒绝请求不再继续扩充时间戳列表。
                    if (!sendCodeRateLimiter.acquire(email, maxPerMinute = 3)) {
                        call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("发送过于频繁，请稍后再试"))
                        return@post
                    }
                    // 重置密码：账号不存在时仍返回 ok，避免邮箱枚举；内部跳过发信
                    if (isReset && userRepo.getByEmail(email) == null) {
                        // 8.47 修复：等价延迟抗时间侧信道——已注册邮箱走 SMTP（数百 ms~数秒），
                        // 不存在邮箱即时返回可被攻击者测量时差枚举注册邮箱。统一延迟到可比量级。
                        withContext(Dispatchers.IO) { kotlinx.coroutines.delay(400L) }
                        call.respond(
                    buildJsonObject {
    put("status", "ok")
    put("message", "验证码已发送")
                    }
                )
                        return@post
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            com.maodouchat.server.service.EmailService.sendVerificationCode(
                                email,
                                purpose = if (isReset) com.maodouchat.server.service.EmailService.PURPOSE_RESET
                                else com.maodouchat.server.service.EmailService.PURPOSE_REGISTER
                            )
                        }
                    } catch (_: IllegalStateException) {
                        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("验证码邮件发送失败，请稍后重试"))
                        return@post
                    }
                    call.respond(
                    buildJsonObject {
    put("status", "ok")
    put("message", "验证码已发送")
                    }
                )
                }
}
