package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.SendFriendRequestBody
import com.maodouchat.server.repository.FriendRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.sendRequest
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.RuntimeConfigService
import com.maodouchat.server.service.isFriendRequestsEnabled
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json

// 好友请求发送（从 FriendRequestRoutes 按域拆出）。
internal fun Route.configureFriendRequestSendRoutes(
    userRepository: UserRepository,
    friendRepository: FriendRepository,
    pushService: FcmPushService,
    requestRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    authenticate("auth-jwt") {
        post("/api/friends/requests") {
            if (!RuntimeConfigService.isFriendRequestsEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("friend_requests_disabled"))
                return@post
            }
            val userId = call.requireUserId()
            if (call.rejectIfSuspended(userRepository, userId)) return@post
            if (!requestRateLimiter.acquire(userId, maxPerMinute = 10)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                return@post
            }
            val request = call.receiveJsonOr400<SendFriendRequestBody>() ?: return@post
            val recipientUserId = request.toUserId.trim()
            if (recipientUserId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("目标用户无效"))
                return@post
            }
            when (val result = friendRepository.sendRequest(userId, recipientUserId, request.message)) {
                is FriendRepository.Result.Success -> {
                    notifyFriendRequest(userRepository, pushService, json, result.request, "CREATED")
                    call.respond(HttpStatusCode.Created, result.request)
                }
                is FriendRepository.Result.Failure -> {
                    val status = when (result.code) {
                        "USER_NOT_FOUND" -> HttpStatusCode.NotFound
                        "BLOCKED", "SELF" -> HttpStatusCode.Forbidden
                        "ALREADY_FRIENDS", "ALREADY_PENDING", "INCOMING_PENDING" -> HttpStatusCode.Conflict
                        else -> HttpStatusCode.BadRequest
                    }
                    call.respond(status, ErrorResponse(result.message, code = result.code))
                }
            }
        }
    }
}
