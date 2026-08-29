package com.maodouchat.server.plugins

import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.FriendRequestEventPayload
import com.maodouchat.server.model.FriendRequestResponse
import com.maodouchat.server.model.SendFriendRequestBody
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.repository.FriendRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.RuntimeConfigService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Authenticated friend graph routes with durable offline push and realtime wake-up. */
internal fun Route.configureFriendRoutes(
    userRepository: UserRepository,
    friendRepository: FriendRepository,
    pushService: FcmPushService,
    requestRateLimiter: BoundedRateLimiter,
    json: Json,
) {
    suspend fun blockedEitherWay(userId: String, peerUserId: String): Set<String> = try {
        userRepository.blockedEitherWayIdsInTx(userId, listOf(peerUserId))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptySet()
    }

    suspend fun notifyFriendRequest(request: FriendRequestResponse, action: String) {
        val payload = json.encodeToString(
            FriendRequestEventPayload.serializer(),
            FriendRequestEventPayload(action = action, request = request),
        )
        val envelope = json.encodeToString(WsMessage.serializer(), WsMessage("FRIEND_REQUEST", payload))
        val blockedFromSender = blockedEitherWay(request.fromUser.id, request.toUser.id)
        val blockedFromRecipient = blockedEitherWay(request.toUser.id, request.fromUser.id)

        if (request.toUser.id !in blockedFromSender) sendToUser(request.fromUser.id, envelope)
        if (request.fromUser.id !in blockedFromRecipient) sendToUser(request.toUser.id, envelope)
        when (action) {
            "CREATED" -> if (request.fromUser.id !in blockedFromRecipient) {
                pushService.enqueueFriendRequest(
                    recipientId = request.toUser.id,
                    fromUserId = request.fromUser.id,
                    requestId = request.id,
                    action = action,
                )
            }
            "ACCEPTED" -> if (request.toUser.id !in blockedFromSender) {
                pushService.enqueueFriendRequest(
                    recipientId = request.fromUser.id,
                    fromUserId = request.toUser.id,
                    requestId = request.id,
                    action = action,
                )
            }
        }
    }

    authenticate("auth-jwt") {
        post("/api/friends/requests") {
            if (!RuntimeConfigService.isFriendRequestsEnabled()) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("friend_requests_disabled"))
                return@post
            }
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            if (call.rejectIfSuspended(userRepository, userId)) return@post
            if (!requestRateLimiter.acquire(userId, maxPerMinute = 10)) {
                call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("操作过于频繁，请稍后再试"))
                return@post
            }
            val request = call.receiveBoundedText()?.let { parseJson<SendFriendRequestBody>(it) } ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@post
            }
            val recipientUserId = request.toUserId.trim()
            if (recipientUserId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("目标用户无效"))
                return@post
            }
            when (val result = friendRepository.sendRequest(userId, recipientUserId, request.message)) {
                is FriendRepository.Result.Success -> {
                    notifyFriendRequest(result.request, "CREATED")
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

        get("/api/friends/requests/incoming") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val status = call.request.queryParameters["status"] ?: "PENDING"
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
            call.respond(friendRepository.listIncoming(userId, status, limit))
        }

        get("/api/friends/requests/outgoing") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val status = call.request.queryParameters["status"] ?: "PENDING"
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
            call.respond(friendRepository.listOutgoing(userId, status, limit))
        }

        post("/api/friends/requests/{id}/accept") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            if (call.rejectIfSuspended(userRepository, userId)) return@post
            call.respondToMutation(friendRepository.acceptRequest(userId, call.parameters["id"].orEmpty())) {
                notifyFriendRequest(it, "ACCEPTED")
            }
        }

        post("/api/friends/requests/{id}/reject") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            if (call.rejectIfSuspended(userRepository, userId)) return@post
            call.respondToMutation(friendRepository.rejectRequest(userId, call.parameters["id"].orEmpty())) {
                notifyFriendRequest(it, "REJECTED")
            }
        }

        post("/api/friends/requests/{id}/cancel") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            if (call.rejectIfSuspended(userRepository, userId)) return@post
            call.respondToMutation(friendRepository.cancelRequest(userId, call.parameters["id"].orEmpty())) {
                notifyFriendRequest(it, "CANCELLED")
            }
        }

        get("/api/friends") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            call.respond(friendRepository.listFriends(userId))
        }

        delete("/api/friends/{friendId}") {
            val userId = call.principal<JWTPrincipal>()!!.payload.subject
            val friendId = call.parameters["friendId"].orEmpty()
            if (friendId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("参数无效"))
                return@delete
            }
            if (friendRepository.removeFriend(userId, friendId)) {
                call.respond(buildJsonObject { put("status", "ok") })
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("好友关系不存在", code = "NOT_FRIENDS"))
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondToMutation(
    result: FriendRepository.Result,
    onSuccess: suspend (FriendRequestResponse) -> Unit,
) {
    when (result) {
        is FriendRepository.Result.Success -> {
            onSuccess(result.request)
            respond(result.request)
        }
        is FriendRepository.Result.Failure -> {
            val status = when (result.code) {
                "NOT_FOUND" -> HttpStatusCode.NotFound
                "FORBIDDEN" -> HttpStatusCode.Forbidden
                else -> HttpStatusCode.BadRequest
            }
            respond(status, ErrorResponse(result.message, code = result.code))
        }
    }
}
