package com.maodouchat.server.service

import com.maodouchat.server.model.SendSignalRequest
import io.ktor.http.HttpStatusCode

data class Stored(val request: SendSignalRequest) : SendOutcome
data class Rejected(
    val status: HttpStatusCode,
    val message: String,
    val code: String? = null,
    val retryAfterSeconds: Long? = null,
) : SendOutcome
