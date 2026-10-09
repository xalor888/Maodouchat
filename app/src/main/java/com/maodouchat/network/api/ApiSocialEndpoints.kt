package com.maodouchat.network.api

import com.maodouchat.network.*
import com.maodouchat.BuildConfig
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.KSerializer

// 社交用户域（用户/隐私/通知/推送/拉黑）：从 ApiEndpointClients 按主题拆出，纯搬移。
internal object ApiSocialEndpoints {
private val json get() = ApiService.json
private fun jsonBody(value: String) = value.toRequestBody(ApiService.JSON_MEDIA)
private suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = ApiService.send(request, serializer)
private suspend fun sendUnit(request: Request): Result<Unit> = ApiService.sendUnit(request)

suspend fun getUsers(token: String, limit: Int, offset: Int): Result<List<UserDto>> =
    send(
        Request.Builder()
            .url(
                "${ApiConfig.BASE_URL}/api/users" +
                    "?limit=${limit.coerceIn(1, 100)}" +
                    "&offset=${offset.coerceAtLeast(0)}"
            )
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        ListSerializer(UserDto.serializer())
    )

/**
 * 翻页拉取全部可搜索用户（群加人候选等场景）。服务端每页最多 100 人，
 * 客户端按页循环直到返回空或达到 [maxUsers] 上限，避免群成员列表只显示前 30 人。
 */

suspend fun getAllSearchableUsers(
    token: String,
    pageSize: Int,
    maxUsers: Int): Result<List<UserDto>> {
    if (token.isBlank()) return Result.failure(IllegalArgumentException("token_missing"))
    val safePageSize = pageSize.coerceIn(1, 100)
    val safeMax = maxUsers.coerceAtLeast(1)
    val collected = mutableListOf<UserDto>()
    var offset = 0
    while (offset < safeMax) {
        val page = getUsers(token, limit = safePageSize, offset = offset)
            .getOrElse { return Result.failure(it) }
        if (page.isEmpty()) break
        collected += page
        if (collected.size >= safeMax) break
        offset += safePageSize
    }
    return Result.success(collected.take(safeMax))
}

suspend fun getUser(token: String, userId: String): Result<UserDto> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/users/${java.net.URLEncoder.encode(userId, Charsets.UTF_8.name())}")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        UserDto.serializer()
    )

suspend fun getNearbyLocationStatus(token: String): Result<NearbyLocationStatusResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/nearby-location").addHeader("Authorization", "Bearer $token").get().build(), NearbyLocationStatusResponse.serializer())

suspend fun updateNearbyLocation(token: String, latitude: Double, longitude: Double): Result<NearbyLocationStatusResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/nearby-location").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateNearbyLocationRequest.serializer(), UpdateNearbyLocationRequest(latitude, longitude)))).build(), NearbyLocationStatusResponse.serializer())

suspend fun stopNearbyLocationSharing(token: String): Result<NearbyLocationStatusResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/nearby-location").addHeader("Authorization", "Bearer $token").delete().build(), NearbyLocationStatusResponse.serializer())

suspend fun getNearbyUsers(token: String, radiusKm: Double, limit: Int): Result<List<NearbyUserResponse>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/nearby?radiusKm=${radiusKm.coerceIn(0.5, 20.0)}&limit=${limit.coerceIn(1, 100)}").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(NearbyUserResponse.serializer()))

suspend fun searchUsers(token: String, query: String, limit: Int): Result<List<UserDto>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=$limit").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(UserDto.serializer()))

suspend fun getCurrentUser(token: String): Result<UserDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/me").addHeader("Authorization", "Bearer $token").get().build(), UserDto.serializer())

/** 获取当前用户公开信息（含用户名） */

suspend fun getCurrentUserPublic(token: String): Result<CurrentUserPublicResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/me/public").addHeader("Authorization", "Bearer $token").get().build(), CurrentUserPublicResponse.serializer())

/** 获取公开个人主页信息（无需认证） */

suspend fun getPublicProfile(username: String): Result<PublicProfileResponse> {
    val url = "${ApiConfig.BASE_URL}/api/public/profile/${java.net.URLEncoder.encode(username, "UTF-8")}"
    return send(Request.Builder().url(url).get().build(), PublicProfileResponse.serializer())
}

/** 设置用户名 */

suspend fun setUsername(token: String, username: String): Result<SetUsernameResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/me/username").addHeader("Authorization", "Bearer $token")
        .put(jsonBody(json.encodeToString(SetUsernameRequest.serializer(), SetUsernameRequest(username)))).build(),
        SetUsernameResponse.serializer())

/** 清除用户名 */

suspend fun clearUsername(token: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/me/username").addHeader("Authorization", "Bearer $token").delete().build())

/** 高级搜索 */

suspend fun getPrivacy(token: String): Result<UserPrivacyDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/privacy").addHeader("Authorization", "Bearer $token").get().build(), UserPrivacyDto.serializer())

suspend fun updatePrivacy(
    token: String,
    showOnline: Boolean?,
    showStatus: Boolean?,
    searchable: Boolean?,
    defaultPostVisibility: String?,
    onlineVisibility: String?): Result<UserPrivacyDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/privacy").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdatePrivacyRequest.serializer(), UpdatePrivacyRequest(showOnline, showStatus, searchable, defaultPostVisibility, onlineVisibility)))).build(), UserPrivacyDto.serializer())

suspend fun getPublicUpdates(baseUrl: String): Result<PublicUpdatesDto> =
    send(
        Request.Builder().url("${baseUrl.trimEnd('/')}/api/public/updates").get().build(),
        PublicUpdatesDto.serializer()
    ).recoverCatching { error ->
        if (baseUrl.trimEnd('/') != BuildConfig.API_BASE_URL.trimEnd('/')) {
            send(
                Request.Builder().url("${BuildConfig.API_BASE_URL.trimEnd('/')}/api/public/updates").get().build(),
                PublicUpdatesDto.serializer()
            ).getOrThrow()
        } else {
            throw error
        }
    }

suspend fun getNotificationSettings(token: String): Result<NotificationSettingsResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/notification-settings").addHeader("Authorization", "Bearer $token").get().build(), NotificationSettingsResponse.serializer())

suspend fun updateNotificationSettings(token: String, request: NotificationSettingsRequest): Result<NotificationSettingsResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/notification-settings").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(NotificationSettingsRequest.serializer(), request))).build(), NotificationSettingsResponse.serializer())

suspend fun registerPushToken(
    token: String,
    deviceId: String,
    pushToken: String,
    timezoneOffsetMinutes: Int
): Result<Unit> = sendUnit(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/users/push-tokens")
        .addHeader("Authorization", "Bearer $token")
        .post(jsonBody(json.encodeToString(
            RegisterPushTokenRequest.serializer(),
            RegisterPushTokenRequest(deviceId, pushToken, timezoneOffsetMinutes = timezoneOffsetMinutes)
        )))
        .build()
)

suspend fun removePushToken(token: String, deviceId: String): Result<Unit> = sendUnit(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/users/push-tokens")
        .addHeader("Authorization", "Bearer $token")
        .delete(jsonBody(json.encodeToString(RemovePushTokenRequest.serializer(), RemovePushTokenRequest(deviceId))))
        .build()
)

/** WebRTC 信令 REST：走 executeWithRefresh，长通话 JWT 过期后仍可挂断/补发 */

suspend fun blockUser(token: String, userId: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/block/$userId").addHeader("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build())

suspend fun unblockUser(token: String, userId: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/block/$userId").addHeader("Authorization", "Bearer $token").delete().build())

suspend fun getBlockedUsers(token: String): Result<List<String>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/blocks").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(String.serializer()))

suspend fun getBlockedUserDetails(token: String): Result<List<UserDto>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/blocks/details").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(UserDto.serializer()))
}
