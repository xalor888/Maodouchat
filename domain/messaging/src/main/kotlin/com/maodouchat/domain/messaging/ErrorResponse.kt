package com.maodouchat.domain.messaging

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val error: String, val code: String? = null, val retryAfterSeconds: Long? = null)
