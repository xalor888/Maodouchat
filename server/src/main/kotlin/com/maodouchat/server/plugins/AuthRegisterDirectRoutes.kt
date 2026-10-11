package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.RegisterRequest
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post


/** 直接注册（生产环境已下线，走验证码通道）。 */
internal fun Route.configureAuthRegisterDirectRoutes(
    userRepo: UserRepository,
    authTokenRepo: AuthTokenRepository,
    loginIpRateLimiter: BoundedRateLimiter,
)
{

    post("/api/auth/register") {
               if (ServerConfig.isProduction) {
                   call.respond(HttpStatusCode.Gone, ErrorResponse("请使用验证码注册"))
                   return@post
               }
               if (!RuntimeConfigService.isRegistrationAllowed()) {
                   call.respond(HttpStatusCode.Forbidden, ErrorResponse("注册已关闭"))
                   return@post
               }
               // 注册 IP 频率限制 — 防暴破（读取 AUTH_RATE_LIMIT_PER_MINUTE）
               if (!loginIpRateLimiter.acquire(call.remoteHost(), maxPerMinute = ServerConfig.authRateLimitPerMinute)) {
                   call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("注册过于频繁，请稍后再试"))
                   return@post
               }
               val req = call.receiveJson<RegisterRequest>()
               if (req == null || req.name.isBlank() || req.email.isBlank() || !isValidPassword(req.password)) {
                   call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                   return@post
               }
               if (userRepo.getByEmail(req.email) != null) {
                   call.respond(HttpStatusCode.Conflict, ErrorResponse("邮箱已注册"))
                   return@post
               }
               // 0.74：一次性/垃圾邮箱域名黑名单（反垃圾注册）
               if (isRegistrationEmailDomainBlocked(req.email)) {
                   call.respond(HttpStatusCode.Forbidden, ErrorResponse("该邮箱域名已被禁止注册", code = "EMAIL_DOMAIN_BLOCKED"))
                   return@post
               }
               val user = try {
                // 用 try-catch 捕获并发注册时的唯一约束冲突（第二个请求在 byEmail 检查后才插入）
                   userRepo.register(req.name, req.email, req.password)
               } catch (e: Exception) {
                   // 8.34 修复：仅唯一约束冲突映射 409；其余异常（DB 故障等）此前被伪装成
                   //「邮箱已注册」，掩盖真实失败、运维排障困难 → 交给 StatusPages 500 分支
                   if (userRepo.isUniqueViolation(e)) {
                       call.respond(HttpStatusCode.Conflict, ErrorResponse("邮箱已注册"))
                       return@post
                   }
                   throw e
               }
               if (user != null) {
                   call.respond(issueAuthResponse(user, authTokenRepo))
               } else {
                   call.respond(HttpStatusCode.InternalServerError, ErrorResponse("注册失败"))
               }
           }
}
