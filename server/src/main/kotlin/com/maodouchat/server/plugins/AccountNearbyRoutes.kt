package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.NearbyLocationStatusResponse
import com.maodouchat.server.model.UpdateNearbyLocationRequest
import com.maodouchat.server.repository.NearbyRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put

// 附近：位置状态读写与附近的人查询（从 AccountDiscoveryRoutes 按域拆出）。
internal fun Route.configureAccountNearbyRoutes(
    userRepo: UserRepository,
    nearbyRepo: NearbyRepository,
    nearbyUpdateRateLimiter: BoundedRateLimiter,
    nearbyQueryRateLimiter: BoundedRateLimiter,
) {
    authenticate("auth-jwt") {

        get("/api/users/nearby-location") {

            if (!RuntimeConfigService.isNearbyEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("nearby_disabled"))
                return@get
            }
            val userId = call.requireUserId()
            call.respond(nearbyRepo.getStatus(userId))
        }

        put("/api/users/nearby-location") {

            if (!RuntimeConfigService.isNearbyEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("nearby_disabled"))
                return@put
            }
            val userId = call.requireUserId()
            // 8.33 修复：封禁用户不得更新附近位置（位置 = 实时行踪，封禁期间必须消失）
            if (call.rejectIfSuspended(userRepo, userId)) return@put
            // 每用户限流：位置更新是 DB 写，防高频轮询刷写
            if (!nearbyUpdateRateLimiter.acquire(userId, maxPerMinute = 10)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("位置更新太频繁，请稍后再试"))
                return@put
            }
            val req = call.receiveJsonOr400<UpdateNearbyLocationRequest>(message = "位置参数无效") ?: return@put
            val status = nearbyRepo.updateLocation(userId, req.latitude, req.longitude)
            if (status == null) call.respond(HttpStatusCode.BadRequest, ErrorResponse("位置参数无效"))
            else call.respond(status)
        }

        delete("/api/users/nearby-location") {

            if (!RuntimeConfigService.isNearbyEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("nearby_disabled"))
                return@delete
            }
            val userId = call.requireUserId()
            nearbyRepo.stopSharing(userId)
            call.respond(NearbyLocationStatusResponse(false, 0))
        }

        get("/api/users/nearby") {

            if (!RuntimeConfigService.isNearbyEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("nearby_disabled"))
                return@get
            }
            val userId = call.requireUserId()
            // 8.33 修复：封禁用户不得查询附近的人（位置隐私双向一致）
            if (call.rejectIfSuspended(userRepo, userId)) return@get
            // 每用户限流：附近查询是范围扫描 + haversine 计算，防高频轮询打 CPU
            if (!nearbyQueryRateLimiter.acquire(userId, maxPerMinute = 30)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("查询太频繁，请稍后再试"))
                return@get
            }
            val radiusKm = parseAccountNearbyRadiusKm(call.request.queryParameters)
            val limit = parseAccountPageLimit(call.request.queryParameters, 50)
            call.respond(nearbyRepo.getNearby(userId, radiusKm, limit))
        }
    }
}
