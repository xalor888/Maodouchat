package com.maodouchat.server.service

sealed interface AppendResult {
    data class Accepted(val uploadedBytes: Long, val replayed: Boolean) : AppendResult
    data class OffsetMismatch(val uploadedBytes: Long) : AppendResult
    data object ContentMismatch : AppendResult
}
