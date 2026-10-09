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
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

internal object MediaImageApiClient : MediaImageApi {
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

override suspend fun uploadPostImage(token: String, base64Data: String): Result<String> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/posts/images").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(UploadPostImageRequest.serializer(), UploadPostImageRequest(base64Data)))).build(), UploadPostImageResponse.serializer()).map { it.imageUrl }

override suspend fun discardPostImage(token: String, imageUrl: String): Result<Unit> {
    val filename = imageUrl.substringAfterLast('/').takeIf { it.matches(POST_IMAGE_FILENAME_REGEX) }
        ?: return Result.failure(IllegalArgumentException("invalid_post_image_url"))
    return sendUnit(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/posts/images/$filename")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
    )
}

override suspend fun uploadAvatar(token: String, base64Data: String): Result<String> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/avatar").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(UploadAvatarRequest.serializer(), UploadAvatarRequest(base64Data)))).build(), AvatarResponse.serializer()).map { it.avatarUrl }

override suspend fun removeAvatar(token: String): Result<Unit> =
    sendUnit(Request.Builder().url("${ApiConfig.BASE_URL}/api/users/avatar").addHeader("Authorization", "Bearer $token").delete().build())

override suspend fun uploadGroupAvatar(token: String, chatId: String, base64Data: String): Result<String> =
    send(Request.Builder().url("${ApiConfig.BASE_URL}/api/chats/$chatId/avatar").addHeader("Authorization", "Bearer $token").post(jsonBody(json.encodeToString(UploadAvatarRequest.serializer(), UploadAvatarRequest(base64Data)))).build(), AvatarResponse.serializer()).map { it.avatarUrl }
}
