package com.maodouchat.core.model

enum class FailureOperation { SEND, FETCH, HANG_UP }

class SignalingException(
    val operation: FailureOperation,
    val statusCode: Int? = null,
    val code: String? = null,
    val retryAfterSeconds: Long? = null
) :
    Exception("WebRTC signaling ${operation.name.lowercase()} failed")
