package com.maodouchat.domain.conversation

sealed interface PushTransportState {
    data class Connected(val ownerUserId: String) : PushTransportState
    data class Connecting(val ownerUserId: String) : PushTransportState
    data object NoSession : PushTransportState
    data object Stopped : PushTransportState
}
