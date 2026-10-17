package com.maodouchat.crypto

data class Certificate(
    val certificate: String,
    val expiresAt: Long,
    val deviceId: Int,
    val userId: String
)

internal data class CacheKey(
    val userId: String,
    val deviceId: Int
)
