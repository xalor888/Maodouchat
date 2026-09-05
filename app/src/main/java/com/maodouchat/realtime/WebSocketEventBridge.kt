package com.maodouchat.realtime

import com.maodouchat.core.realtime.DefaultRealtimeEventDispatcher
import com.maodouchat.core.realtime.RealtimeConnectionState
import com.maodouchat.core.realtime.RealtimeDomainEvent
import com.maodouchat.core.realtime.RealtimeEventDispatcher
import com.maodouchat.network.WebSocketEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * A05: 将传输层 WebSocketEvent 解码并桥接为领域强类型 RealtimeDomainEvent。
 * 集中管理事件转发，避免各 ViewModel、Screen 直接收集原始 WebSocketClient。
 */
class WebSocketEventBridge(
    val dispatcher: RealtimeEventDispatcher = DefaultRealtimeEventDispatcher(),
) {
    fun start(events: SharedFlow<WebSocketEvent>, scope: CoroutineScope) {
        scope.launch {
            events.collect { wsEvent ->
                when (wsEvent) {
                    is WebSocketEvent.InboxAvailableV2 -> {
                        dispatcher.dispatch(RealtimeDomainEvent.Wake())
                    }
                    is WebSocketEvent.UserOnline -> {
                        dispatcher.dispatch(
                            RealtimeDomainEvent.Presence(
                                userId = wsEvent.userId,
                                isOnline = wsEvent.isOnline,
                                lastSeen = wsEvent.lastSeen,
                                onlineRevoked = wsEvent.onlineRevoked,
                                statusRevoked = wsEvent.statusRevoked,
                            )
                        )
                    }
                    is WebSocketEvent.UserTyping -> {
                        dispatcher.dispatch(
                            RealtimeDomainEvent.Typing(
                                userId = wsEvent.userId,
                                chatId = wsEvent.chatId,
                                isTyping = wsEvent.isTyping,
                            )
                        )
                    }
                    is WebSocketEvent.SignalingReceived -> {
                        dispatcher.dispatch(
                            RealtimeDomainEvent.CallSignaling(
                                fromUserId = wsEvent.fromUserId,
                                type = wsEvent.type,
                                payload = wsEvent.payload,
                                callId = wsEvent.callId,
                                groupId = wsEvent.groupId,
                                groupMemberIds = wsEvent.groupMemberIds,
                                groupInvite = wsEvent.groupInvite,
                            )
                        )
                    }
                    is WebSocketEvent.GroupRevisionChanged -> {
                        dispatcher.dispatch(
                            RealtimeDomainEvent.GroupRevision(
                                chatId = wsEvent.chatId,
                                memberRevision = wsEvent.memberRevision,
                                reason = wsEvent.reason,
                                actorId = wsEvent.actorId,
                                targetUserId = wsEvent.targetUserId,
                            )
                        )
                    }
                    is WebSocketEvent.GroupPlayUpdated -> {
                        dispatcher.dispatch(
                            RealtimeDomainEvent.GroupPlay(
                                chatId = wsEvent.chatId,
                                event = wsEvent.event,
                                payloadJson = wsEvent.payloadJson,
                            )
                        )
                    }
                    is WebSocketEvent.GroupInviteUpdated -> {
                        dispatcher.dispatch(
                            RealtimeDomainEvent.GroupInvite(
                                action = wsEvent.action,
                                inviteId = wsEvent.invite.id,
                                chatId = wsEvent.invite.chatId,
                                inviterId = wsEvent.invite.inviterId,
                                inviteeId = wsEvent.invite.userId,
                                inviterName = wsEvent.invite.inviterName,
                                chatName = wsEvent.invite.chatName,
                            )
                        )
                    }
                    is WebSocketEvent.AdminBroadcast -> {
                        dispatcher.dispatch(
                            RealtimeDomainEvent.AdminNotice(
                                title = wsEvent.title,
                                text = wsEvent.text,
                                timestamp = wsEvent.ts,
                            )
                        )
                    }
                    is WebSocketEvent.FriendRequestUpdated -> {
                        dispatcher.dispatch(
                            RealtimeDomainEvent.SocialUpdate(
                                kind = "friend_request",
                                targetId = wsEvent.request.id,
                                action = wsEvent.action,
                                fromUserId = wsEvent.request.fromUser.id,
                                fromUserName = wsEvent.request.fromUser.name,
                                toUserId = wsEvent.request.toUser.id,
                                message = wsEvent.request.message,
                            )
                        )
                    }
                    is WebSocketEvent.PostDeleted -> {
                        dispatcher.dispatch(
                            RealtimeDomainEvent.SocialUpdate(
                                kind = "post_deleted",
                                targetId = wsEvent.postId,
                            )
                        )
                    }
                    is WebSocketEvent.Connected -> {
                        if (wsEvent.success) {
                            dispatcher.updateConnectionState(RealtimeConnectionState.CONNECTED)
                        } else {
                            dispatcher.updateConnectionState(RealtimeConnectionState.FAILED)
                        }
                    }
                    is WebSocketEvent.Disconnected -> {
                        dispatcher.updateConnectionState(RealtimeConnectionState.DISCONNECTED)
                    }
                    is WebSocketEvent.Error -> {
                        dispatcher.updateConnectionState(
                            RealtimeConnectionState.FAILED,
                            wsEvent.debugDetail ?: wsEvent.kind.name
                        )
                    }
                    is WebSocketEvent.ServerError -> {
                        dispatcher.dispatch(
                            RealtimeDomainEvent.RealtimeError(
                                code = wsEvent.code,
                                message = wsEvent.message,
                                retryAfterSeconds = wsEvent.retryAfterSeconds,
                            )
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}
