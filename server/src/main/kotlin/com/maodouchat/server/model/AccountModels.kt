package com.maodouchat.server.model

import kotlinx.serialization.Serializable

// 账号 DTO：验证码注册/登录、改密、换头像、注销。


@Serializable
data class SendCodeRequest(val email: String, val purpose: String = "register")


@Serializable
data class VerifyCodeRequest(val email: String, val code: String)


@Serializable
data class RegisterWithCodeRequest(val name: String, val email: String, val password: String, val code: String)


@Serializable
data class ResetPasswordRequest(val email: String, val code: String, val newPassword: String)


@Serializable
data class ChangePasswordRequest(val oldPassword: String, val newPassword: String)


@Serializable
data class DeleteAccountRequest(val password: String)


@Serializable
data class DeleteAccountResponse(val status: String = "ok", val deletedAt: Long)


@Serializable
data class UploadAvatarRequest(val base64Data: String)


@Serializable
data class UpdateProfileRequest(val name: String? = null, val status: String? = null)
