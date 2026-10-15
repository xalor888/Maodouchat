package com.maodouchat.server.repository

import com.maodouchat.server.model.FriendRequestResponse

sealed class FriendRequestResult {
    data class Success(val request: FriendRequestResponse) : FriendRequestResult()
    data class Failure(val message: String, val code: String = "FRIEND_ERROR") : FriendRequestResult()
}
