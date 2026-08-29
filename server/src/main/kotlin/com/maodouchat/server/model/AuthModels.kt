package com.maodouchat.server.model

import kotlinx.serialization.Serializable


@Serializable
data class RegisterRequest(val name: String, val email: String, val password: String)

@Serializable
data class LoginRequest(val email: String, val password: String, val totpCode: String = "")

@Serializable
data class AuthResponse(
    val token: String = "",
    val userId: String = "",
    val name: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val refreshExpiresAt: Long = 0,
    val requiresTotp: Boolean = false,
    val totpEnabled: Boolean = false
)

@Serializable
data class TotpSetupResponse(
    val secret: String,
    val otpauthUrl: String,
    val enabled: Boolean
)

@Serializable
data class TotpCodeRequest(val code: String)

@Serializable
data class TotpStatusResponse(val enabled: Boolean, val backupCodes: List<String> = emptyList())


@Serializable
data class RefreshTokenRequest(
    val refreshToken: String,
    /** 可选：单设备 logout 时清除该推送 deviceId，避免已退出设备仍收 FCM */
    val deviceId: String = ""
)

@Serializable
data class UserResponse(val id: String, val name: String, val email: String = "", val avatar: String? = null, val status: String = "", val isOnline: Boolean = false, val isModerator: Boolean = false, val lastSeen: Long = 0, val username: String? = null)