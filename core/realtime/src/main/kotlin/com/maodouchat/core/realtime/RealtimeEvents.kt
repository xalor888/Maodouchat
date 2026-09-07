package com.maodouchat.core.realtime

/**
 * A05: 领域类型化实时事件集合。
 * 将传输层的 WebSocket 帧与原始 JSON 解析解耦，分发为强类型领域事件。
 */
sealed class RealtimeDomainEvent {
    /** 消息到达唤醒事件（只唤醒 V2 inbox sync，可安全合并/去重）。 */
    data class Wake(val timestamp: Long = System.currentTimeMillis()) : RealtimeDomainEvent()

    /** 用户在线状态事件。 */
    data class Presence(
        val userId: String,
        val isOnline: Boolean,
        val lastSeen: Long = 0L,
        val onlineRevoked: Boolean = false,
        val statusRevoked: Boolean = false
    ) : RealtimeDomainEvent()

    /** 对方打字状态事件。 */
    data class Typing(
        val userId: String,
        val chatId: String,
        val isTyping: Boolean
    ) : RealtimeDomainEvent()

    /** 通话信令事件。 */
    data class CallSignaling(
        val fromUserId: String,
        val type: String,
        val payload: String,
        val callId: String = "",
        val groupId: String = "",
        val groupMemberIds: List<String> = emptyList(),
        val groupInvite: Boolean = false,
        val epoch: Long = 0,
        val sequence: Long = 0,
        val idempotencyKey: String = "",
    ) : RealtimeDomainEvent()

    /** 群版本变更事件。 */
    data class GroupRevision(
        val chatId: String,
        val memberRevision: Long,
        val reason: String,
        val actorId: String? = null,
        val targetUserId: String? = null
    ) : RealtimeDomainEvent()

    /** 群玩法动态事件。 */
    data class GroupPlay(
        val chatId: String,
        val event: String,
        val payloadJson: String
    ) : RealtimeDomainEvent()

    /** 群邀请变更事件。 */
    data class GroupInvite(
        val action: String,
        val inviteId: String,
        val chatId: String,
        val inviterId: String,
        val inviteeId: String,
        val inviterName: String = "",
        val chatName: String = "",
    ) : RealtimeDomainEvent()

    /** 管理员广播事件。 */
    data class AdminNotice(
        val title: String,
        val text: String,
        val timestamp: Long = 0L
    ) : RealtimeDomainEvent()

    /** 社交动态/好友变更事件。 */
    data class SocialUpdate(
        val kind: String,
        val targetId: String,
        val action: String? = null,
        val fromUserId: String = "",
        val fromUserName: String = "",
        val toUserId: String = "",
        val message: String = "",
    ) : RealtimeDomainEvent()

    /** 服务端通用或信令错误事件。 */
    data class RealtimeError(
        val code: String? = null,
        val message: String? = null,
        val retryAfterSeconds: Long? = null
    ) : RealtimeDomainEvent()

    /** 连接状态变更。 */
    data class ConnectionStateChanged(
        val state: RealtimeConnectionState,
        val message: String? = null
    ) : RealtimeDomainEvent()
}

enum class RealtimeConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}
