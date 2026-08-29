package com.maodouchat.network.api

import com.maodouchat.BuildConfig
import com.maodouchat.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

internal object AuthApiClient : AuthApi {
private val json get() = ApiService.json
private val JSON_MEDIA get() = ApiService.JSON_MEDIA
private val ATTACHMENT_CHUNK_BYTES get() = ApiService.ATTACHMENT_CHUNK_BYTES
private val ATTACHMENT_CHUNK_MAX_ATTEMPTS get() = ApiService.ATTACHMENT_CHUNK_MAX_ATTEMPTS
private val ATTACHMENT_ID_REGEX get() = ApiService.ATTACHMENT_ID_REGEX
private val POST_IMAGE_FILENAME_REGEX get() = ApiService.POST_IMAGE_FILENAME_REGEX
private fun jsonBody(value: String) = value.toRequestBody(ApiService.JSON_MEDIA)
private suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = ApiService.send(request, serializer)
private suspend fun sendUnit(request: Request): Result<Unit> = ApiService.sendUnit(request)
private suspend fun executeForText(request: Request, errorPrefix: String): Result<String> = ApiService.executeForText(request, errorPrefix)
private suspend fun executeStreamingWithRefresh(request: Request): Response = ApiService.executeStreamingWithRefresh(request)
private fun parseError(body: String): String? = ApiService.parseError(body)
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }


override suspend fun getTotpStatus(token: String): Result<String> = executeForText(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/auth/totp/status")
        .header("Authorization", "Bearer $token")
        .get()
        .build(),
    "totp_status"
)

override suspend fun setupTotp(token: String): Result<String> = executeForText(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/auth/totp/setup")
        .header("Authorization", "Bearer $token")
        .post("{}".toRequestBody("application/json".toMediaType()))
        .build(),
    "totp_setup"
)

/** 0.77：查询 2FA 是否已启用。 */

override suspend fun totpStatus(token: String): Result<Boolean> = try {
    val body = executeForText(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/auth/totp/status")
            .header("Authorization", "Bearer $token")
            .get()
            .build(),
        "totp_status"
    ).getOrThrow()
    Result.success(runCatching { org.json.JSONObject(body).optBoolean("enabled", false) }.getOrDefault(false))
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}

/** 0.77：验证当前 TOTP 后重新生成恢复码（返回新恢复码）。 */

override suspend fun regenerateTotpCodes(token: String, code: String): Result<List<String>> = try {
    val body = executeForText(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/auth/totp/recover-codes")
            .header("Authorization", "Bearer $token")
            .post(org.json.JSONObject().put("code", code).toString().toRequestBody("application/json".toMediaType()))
            .build(),
        "totp_recover"
    ).getOrThrow()
    val obj = runCatching { org.json.JSONObject(body) }.getOrNull()
    val codes = obj?.optJSONArray("backupCodes")
    val result = if (codes != null) {
        (0 until codes.length()).map { codes.optString(it) }.filter { it.isNotBlank() }
    } else {
        emptyList()
    }
    Result.success(result)
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}

override suspend fun confirmTotp(token: String, code: String): Result<List<String>> = try {
    val body = executeForText(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/auth/totp/confirm")
            .header("Authorization", "Bearer $token")
            .post(org.json.JSONObject().put("code", code).toString().toRequestBody("application/json".toMediaType()))
            .build(),
        "totp_confirm"
    ).getOrThrow()
    // 0.75：解析恢复码（启用成功后一次性返回）
    val obj = runCatching { org.json.JSONObject(body) }.getOrNull()
    val codes = obj?.optJSONArray("backupCodes")
    val result = if (codes != null) {
        (0 until codes.length()).map { codes.optString(it) }.filter { it.isNotBlank() }
    } else {
        emptyList()
    }
    Result.success(result)
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}

override suspend fun disableTotp(token: String, code: String): Result<String> = executeForText(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/auth/totp/disable")
        .header("Authorization", "Bearer $token")
        .post(org.json.JSONObject().put("code", code).toString().toRequestBody("application/json".toMediaType()))
        .build(),
    "totp_disable"
)

override suspend fun login(email: String, password: String, totpCode: String): Result<AuthResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/auth/login").post(jsonBody(json.encodeToString(LoginRequest.serializer(), LoginRequest(email, password, totpCode)))).build(), AuthResponse.serializer())

override suspend fun register(name: String, email: String, password: String): Result<AuthResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/auth/register").post(jsonBody(json.encodeToString(RegisterRequest.serializer(), RegisterRequest(name, email, password)))).build(), AuthResponse.serializer())

override suspend fun logout(refreshToken: String, accessToken: String?, deviceId: String): Result<Unit> {
    val builder = Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/auth/logout")
        .post(
            jsonBody(
                json.encodeToString(
                    RefreshTokenRequest.serializer(),
                    RefreshTokenRequest(refreshToken, deviceId = deviceId)
                )
            )
        )
    if (!accessToken.isNullOrBlank()) {
        builder.addHeader("Authorization", "Bearer $accessToken")
    }
    return sendUnit(builder.build())
}

override suspend fun logoutAll(token: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/auth/logout-all").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build())

override suspend fun sendVerificationCode(email: String, purpose: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/auth/send-code").post(jsonBody(json.encodeToString(SendCodeRequest.serializer(), SendCodeRequest(email, purpose)))).build())

override suspend fun registerWithCode(name: String, email: String, password: String, code: String): Result<AuthResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/auth/register-with-code").post(jsonBody(json.encodeToString(RegisterWithCodeRequest.serializer(), RegisterWithCodeRequest(name, email, password, code)))).build(), AuthResponse.serializer())

override suspend fun resetPassword(email: String, code: String, newPassword: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/auth/reset-password").post(jsonBody(json.encodeToString(ResetPasswordRequest.serializer(), ResetPasswordRequest(email, code, newPassword)))).build())

// ─── 好友申请 ──────────────────────────

override suspend fun changePassword(token: String, oldPassword: String, newPassword: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/change-password").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(ChangePasswordRequest.serializer(), ChangePasswordRequest(oldPassword, newPassword)))).build())

override suspend fun deleteAccount(token: String, password: String): Result<DeleteAccountResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/me").addHeader("Authorization", "Bearer $token").delete(jsonBody(json.encodeToString(DeleteAccountRequest.serializer(), DeleteAccountRequest(password)))).build(), DeleteAccountResponse.serializer())
}
