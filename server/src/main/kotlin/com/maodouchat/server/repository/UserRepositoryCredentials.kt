package com.maodouchat.server.repository

// 凭据（注册/登录/改密）委托簇：原 UserRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。
import com.maodouchat.server.model.UserResponse
import com.maodouchat.server.service.CredentialService

fun UserRepository.register(name: String, email: String, password: String): UserResponse? =
    credentialService.register(name, email, password)

fun UserRepository.login(email: String, password: String): UserResponse? =
    credentialService.login(email, password)

fun UserRepository.loginWithFactors(email: String, password: String, totpCode: String?): CredentialService.LoginResult =
    credentialService.loginWithFactors(email, password, totpCode)

fun UserRepository.changePassword(userId: String, oldPassword: String, newPassword: String): Boolean =
    credentialService.changePassword(userId, oldPassword, newPassword)

fun UserRepository.resetPasswordByEmail(email: String, newPassword: String): String? =
    credentialService.resetPasswordByEmail(email, newPassword)

fun UserRepository.verifyPassword(userId: String, password: String): Boolean =
    credentialService.verifyPassword(userId, password)

/** 管理后台二次确认（口令 + 账号启用 TOTP 时的第二因子）。见 [CredentialService.verifyAdminCredentials]。 */
fun UserRepository.verifyAdminCredentials(
    userId: String,
    password: String,
    totpCode: String?,
): CredentialService.AdminCredentialCheck =
    credentialService.verifyAdminCredentials(userId, password, totpCode)
