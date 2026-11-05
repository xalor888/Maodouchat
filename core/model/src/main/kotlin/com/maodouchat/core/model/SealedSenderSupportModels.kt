package com.maodouchat.core.model

data class Certificate(
    val certificate: String,
    val expiresAt: Long,
    val deviceId: Int,
    val userId: String
)

data class CacheKey(
    val userId: String,
    val deviceId: Int
)
