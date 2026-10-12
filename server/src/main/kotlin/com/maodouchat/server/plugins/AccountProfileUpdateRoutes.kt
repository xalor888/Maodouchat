package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateProfileRequest
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.updateProfile
import com.maodouchat.server.service.CacheService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put

// 资料修改：昵称/状态更新（从 AccountProfileWriteRoutes 按域拆出）。
internal fun Route.configureAccountProfileUpdateRoutes(
    userRepo: UserRepository,
    cacheService: CacheService,
) {
    authenticate("auth-jwt") {

        put("/api/users/profile") {
            val userId = call.optionalUserId()
            if (userId == null) { call.respond(HttpStatusCode.Unauthorized, ErrorResponse("未认证")); return@put }
            // 8.33 修复：封禁用户不得修改资料（此前仅部分写路径有检查）
            if (call.rejectIfSuspended(userRepo, userId)) return@put
            val req = call.receiveJson<UpdateProfileRequest>()
            if (req == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效")); return@put }
            val before = userRepo.getById(userId)
            userRepo.updateProfile(userId, name = req.name, status = req.status)
            val updated = userRepo.getById(userId)
            // 0.93：资料变更失效公开主页缓存
            before?.username?.let { cacheService.invalidateUserProfile("user_profile:$it") }
            if (updated != null) call.respond(updated)
            else call.respond(HttpStatusCode.NotFound, ErrorResponse("用户不存在"))
        }
    }
}
