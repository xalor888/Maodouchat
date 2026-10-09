package com.maodouchat.network.api

import com.maodouchat.network.*
import okhttp3.Request
import kotlinx.serialization.KSerializer

// 客户端设置（会话文件夹/偏好/资料）：从 ApiEndpointClients 按主题拆出，纯搬移。
internal object ApiSettingsEndpoints {
private val json get() = ApiService.json
private fun jsonBody(value: String) = value.toRequestBody(ApiService.JSON_MEDIA)
private suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = ApiService.send(request, serializer)

// ─── 会话文件夹云同步 ─────────────────

suspend fun getChatFolders(token: String): Result<ChatFoldersSyncResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chat-folders").addHeader("Authorization", "Bearer $token").get().build(), ChatFoldersSyncResponse.serializer())

suspend fun putChatFolders(token: String, folders: List<ChatFolderDto>): Result<ChatFoldersSyncResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chat-folders").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(ChatFoldersSyncRequest.serializer(), ChatFoldersSyncRequest(folders)))).build(), ChatFoldersSyncResponse.serializer())

// ─── 客户端外观/列表偏好云同步 ────────────

// ─── 客户端外观/列表偏好云同步 ────────────

suspend fun getClientPrefs(token: String): Result<ClientPrefsDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/client-prefs").addHeader("Authorization", "Bearer $token").get().build(), ClientPrefsDto.serializer())

suspend fun putClientPrefs(token: String, request: ClientPrefsUpdateRequest): Result<ClientPrefsDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/client-prefs").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(ClientPrefsUpdateRequest.serializer(), request))).build(), ClientPrefsDto.serializer())

// ─── 头像上传 + 修改资料 ─────────────────

suspend fun updateProfile(token: String, name: String?, status: String?): Result<UserDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/profile").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateProfileRequest.serializer(), UpdateProfileRequest(name = name, status = status)))).build(), UserDto.serializer())
}
