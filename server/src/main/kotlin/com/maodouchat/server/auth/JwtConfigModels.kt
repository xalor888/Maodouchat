package com.maodouchat.server.auth

/** Same clock for JWT exp claim and AuthResponse.expiresAt (avoid dual-now skew). */
data class AccessToken(val token: String, val expiresAtMs: Long)
