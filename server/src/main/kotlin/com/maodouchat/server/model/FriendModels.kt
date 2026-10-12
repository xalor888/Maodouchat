package com.maodouchat.server.model

import kotlinx.serialization.Serializable

// 好友 DTO：好友申请与事件载荷。


@Serializable
data class SendFriendRequestBody(val toUserId: String, val message: String = "")


@Serializable
data class FriendRequestResponse(
    val id: String,
    val fromUser: UserResponse,
    val toUser: UserResponse,
    val message: String = "",
    val status: String,
    val createdAt: Long,
    val updatedAt: Long
)


@Serializable
data class FriendRequestEventPayload(
    val action: String,
    val request: FriendRequestResponse
)
