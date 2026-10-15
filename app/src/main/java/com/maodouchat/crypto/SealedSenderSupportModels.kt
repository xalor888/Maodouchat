package com.maodouchat.crypto

data class Certificate(
    val certificate: String,
    val expiresAt: Long,
    val deviceId: Int,
    val userId: String
)
