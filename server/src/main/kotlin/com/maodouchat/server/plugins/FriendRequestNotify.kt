package com.maodouchat.server.plugins

import com.maodouchat.server.model.FriendRequestEventPayload
import com.maodouchat.server.model.FriendRequestResponse
import com.maodouchat.server.model.WsMessage
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.blockedEitherWayIdsInTx
import com.maodouchat.server.service.FcmPushService
import com.maodouchat.server.service.enqueueFriendRequest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 好友请求通知共享逻辑：发送簇与决策簇共用。
internal suspend fun blockedEitherWay(
    userRepository: UserRepository,
    userId: String,
    peerUserId: String,
): Set<String> = try {
    userRepository.blockedEitherWayIdsInTx(userId, listOf(peerUserId))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    emptySet()
}

internal suspend fun notifyFriendRequest(
    userRepository: UserRepository,
    pushService: FcmPushService,
    json: Json,
    request: FriendRequestResponse,
    action: String,
) {
    val payload = json.encodeToString(
        FriendRequestEventPayload.serializer(),
        FriendRequestEventPayload(action = action, request = request),
    )
    val envelope = json.encodeToString(WsMessage.serializer(), WsMessage("FRIEND_REQUEST", payload))
    val blockedFromSender = blockedEitherWay(userRepository, request.fromUser.id, request.toUser.id)
    val blockedFromRecipient = blockedEitherWay(userRepository, request.toUser.id, request.fromUser.id)

    if (request.toUser.id !in blockedFromSender) LocalRealtimeBus.publish(request.fromUser.id, envelope)
    if (request.fromUser.id !in blockedFromRecipient) LocalRealtimeBus.publish(request.toUser.id, envelope)
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
