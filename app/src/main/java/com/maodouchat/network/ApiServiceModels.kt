package com.maodouchat.network

internal data class AuthenticatedRequestSession(
    val manager: TokenManager,
    val userId: String,
    val accessToken: String
)
