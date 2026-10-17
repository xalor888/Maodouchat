package com.maodouchat.server.service

data class Accepted(val uploadedBytes: Long, val replayed: Boolean) : AppendResult
data class OffsetMismatch(val uploadedBytes: Long) : AppendResult
data object ContentMismatch : AppendResult
