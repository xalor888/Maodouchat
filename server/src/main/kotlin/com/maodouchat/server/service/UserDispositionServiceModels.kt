package com.maodouchat.server.service

sealed interface UserDispositionResult {
    data class Applied(val reasonCode: String, val until: Long) : UserDispositionResult
    data class Invalid(val message: String) : UserDispositionResult
    object NotFound : UserDispositionResult
}
