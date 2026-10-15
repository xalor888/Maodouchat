package com.maodouchat.server.service

data class IssuedCertificate(
    val certificate: String,
    val userId: String,
    val deviceId: Int,
    val expiresAt: Long
)

data class VerifiedCertificate(
    val userId: String,
    val deviceId: Int,
    val expiresAt: Long
)
