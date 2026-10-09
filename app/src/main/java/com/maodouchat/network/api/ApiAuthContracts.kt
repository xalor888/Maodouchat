package com.maodouchat.network.api

import com.maodouchat.network.*

interface AuthApi {
    suspend fun getTotpStatus(token: String): Result<String>

    suspend fun setupTotp(token: String): Result<String>

    suspend fun totpStatus(token: String): Result<Boolean>

    suspend fun regenerateTotpCodes(token: String, code: String): Result<List<String>>

    suspend fun confirmTotp(token: String, code: String): Result<List<String>>

    suspend fun disableTotp(token: String, code: String): Result<String>

    suspend fun login(email: String, password: String, totpCode: String = ""): Result<AuthResponse>

    suspend fun register(name: String, email: String, password: String): Result<AuthResponse>

    suspend fun logout(refreshToken: String, accessToken: String? = null, deviceId: String = ""): Result<Unit>

    suspend fun logoutAll(token: String): Result<Unit>

    suspend fun sendVerificationCode(email: String, purpose: String = "register"): Result<Unit>

    suspend fun registerWithCode(name: String, email: String, password: String, code: String): Result<AuthResponse>

    suspend fun resetPassword(email: String, code: String, newPassword: String): Result<Unit>

    suspend fun changePassword(token: String, oldPassword: String, newPassword: String): Result<Unit>

    suspend fun deleteAccount(token: String, password: String): Result<DeleteAccountResponse>
}
