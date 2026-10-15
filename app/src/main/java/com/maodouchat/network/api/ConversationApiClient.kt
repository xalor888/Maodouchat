package com.maodouchat.network.api

import com.maodouchat.BuildConfig
import com.maodouchat.util.toHexString
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
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

internal object ConversationApiClient : ConversationApi {
private val json get() = ApiService.json
private val JSON_MEDIA get() = ApiService.JSON_MEDIA
private fun jsonBody(value: String) = value.toRequestBody(ApiService.JSON_MEDIA)
private suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = ApiService.send(request, serializer)


override suspend fun getChats(token: String): Result<List<ChatDto>> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats").addHeader("Authorization", "Bearer $token").get().build(), ListSerializer(ChatDto.serializer()))

override suspend fun updateChatSettings(token: String, chatId: String, request: UpdateChatSettingsRequest): Result<ChatSettingsResponse> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/settings").addHeader("Authorization", "Bearer $token").put(jsonBody(json.encodeToString(UpdateChatSettingsRequest.serializer(), request))).build(), ChatSettingsResponse.serializer())

override suspend fun updateDisappearingMessages(
    token: String,
    chatId: String,
    seconds: Int
): Result<DisappearingMessagesResponse> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/chats/$chatId/disappearing-messages")
            .addHeader("Authorization", "Bearer $token")
            .put(jsonBody(json.encodeToString(UpdateDisappearingMessagesRequest.serializer(), UpdateDisappearingMessagesRequest(seconds))))
            .build(),
        DisappearingMessagesResponse.serializer()
    )

override suspend fun createChat(
    token: String,
    participantIds: List<String>,
    isGroup: Boolean,
    groupName: String?,
    chatType: String?): Result<ChatDto> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(CreateChatRequest.serializer(), CreateChatRequest(participantIds, isGroup, groupName, chatType)))).build(), ChatDto.serializer())
}
