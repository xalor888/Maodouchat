package com.maodouchat.domain.calls


// 群通话成员连接状态文案种类；R.string 映射留在 app 侧。
enum class GroupParticipantStatusKind {
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
    FAILED,
    REJECTED,
    BUSY,
    NO_ANSWER,
}

fun groupParticipantStatusKind(state: GroupPeerConnectionState): GroupParticipantStatusKind =
    when (state) {
        GroupPeerConnectionState.CONNECTING -> GroupParticipantStatusKind.CONNECTING
        GroupPeerConnectionState.CONNECTED -> GroupParticipantStatusKind.CONNECTED
        GroupPeerConnectionState.RECONNECTING -> GroupParticipantStatusKind.RECONNECTING
        GroupPeerConnectionState.DISCONNECTED -> GroupParticipantStatusKind.DISCONNECTED
        GroupPeerConnectionState.FAILED -> GroupParticipantStatusKind.FAILED
        GroupPeerConnectionState.REJECTED -> GroupParticipantStatusKind.REJECTED
        GroupPeerConnectionState.BUSY -> GroupParticipantStatusKind.BUSY
        GroupPeerConnectionState.NO_ANSWER -> GroupParticipantStatusKind.NO_ANSWER
    }
