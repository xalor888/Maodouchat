package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/**
 * 路由鉴权/限制门卫。抽自 Routing.kt：封禁/维护/禁发消息/禁发动态的统一拦截入口。
 */

internal suspend fun ApplicationCall.rejectIfSuspended(userRepo: UserRepository, userId: String): Boolean {
    val until = userRepo.getSuspendedUntil(userId)
    if (until <= 0L) return false
    respond(HttpStatusCode.Forbidden, ErrorResponse(restrictionMessage(until, "账号已被临时封禁")))
    return true
}

internal suspend fun ApplicationCall.rejectIfMaintenance(messageId: String? = null): Boolean {
    if (!RuntimeConfigService.isMaintenanceMode()) return false
    respond(
        HttpStatusCode.ServiceUnavailable,
        ErrorResponse(
            error = RuntimeConfigService.get(RuntimeConfigService.KEY_MAINTENANCE_MESSAGE).ifBlank {
                "System under maintenance"
            },
            code = "MAINTENANCE",
            messageId = messageId,
        )
    )
    return true
}

internal suspend fun ApplicationCall.rejectIfMessageRestricted(userRepo: UserRepository, userId: String): Boolean {
    if (rejectIfSuspended(userRepo, userId)) return true
    val until = userRepo.getMessageRestrictionUntil(userId)
    if (until <= 0L) return false
    respond(HttpStatusCode.Forbidden, ErrorResponse(restrictionMessage(until, "你已被限制发消息")))
    return true
}

internal suspend fun ApplicationCall.rejectIfPostRestricted(userRepo: UserRepository, userId: String): Boolean {
    if (rejectIfSuspended(userRepo, userId)) return true
    val until = userRepo.getPostRestrictionUntil(userId)
    if (until <= 0L) return false
    respond(HttpStatusCode.Forbidden, ErrorResponse(restrictionMessage(until, "你已被限制发布动态")))
    return true
}