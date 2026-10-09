package com.maodouchat.network

sealed class WebSocketEvent {
    data class PostDeleted(val postId: String) : WebSocketEvent()
    data class PinnedMessagesUpdated(
        val chatId: String,
        val actorId: String,
        val pins: List<PinnedMessageDto>
    ) : WebSocketEvent()
    data class DisappearingMessagesUpdated(
        val chatId: String,
        val seconds: Int,
        val updatedAt: Long = 0L
    ) : WebSocketEvent()
    data class UserOnline(
        val userId: String,
        val isOnline: Boolean,
        val lastSeen: Long = 0,
        val onlineRevoked: Boolean = false,
        val statusRevoked: Boolean = false
    ) : WebSocketEvent()
    data class AdminBroadcast(val title: String, val text: String, val ts: Long = 0L) : WebSocketEvent()
    data class SignalingReceived(
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
    ) : WebSocketEvent()
    data class ServerError(
        val code: String? = null,
        val retryAfterSeconds: Long? = null,
        val message: String = "",
    ) : WebSocketEvent()
    data class UserTyping(val userId: String, val chatId: String, val isTyping: Boolean) : WebSocketEvent()
    data class GroupRevisionChanged(
        val chatId: String,
        val memberRevision: Long,
        val reason: String,
        val actorId: String? = null,
        val targetUserId: String? = null
    ) : WebSocketEvent()
    data class FriendRequestUpdated(
        val action: String,
        val request: FriendRequestDto
    ) : WebSocketEvent()
    /** 群玩法实时事件（checkin_updated / chain_created / chain_updated / pk_created / pk_updated / pk_closed）。 */
    data class GroupPlayUpdated(
        val chatId: String,
        val event: String,
        val payloadJson: String
    ) : WebSocketEvent()
    /** 9.3xx：群邀请事件（CREATED / ACCEPTED / DECLINED / CANCELLED）。 */
    data class GroupInviteUpdated(
        val action: String,
        val invite: GroupInvitationDto
    ) : WebSocketEvent()
    data class Connected(val success: Boolean) : WebSocketEvent()
    data object InboxAvailableV2 : WebSocketEvent()
    data class Error(
        val kind: WebSocketErrorKind,
        val debugDetail: String? = null
    ) : WebSocketEvent()
    data object Disconnected : WebSocketEvent()
}

enum class WebSocketErrorKind {
    CONNECTION,
    ENVELOPE_PARSE,
    MESSAGE_PARSE,
    POST_DELETE_PARSE,
    PIN_PARSE,
    DISAPPEARING_PARSE,
    USER_STATUS_PARSE,
    TYPING_PARSE,
    GROUP_REVISION_PARSE,
    SIGNALING_PARSE,
    FRIEND_REQUEST_PARSE
}
