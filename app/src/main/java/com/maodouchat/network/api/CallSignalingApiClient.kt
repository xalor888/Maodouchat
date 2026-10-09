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

internal object CallSignalingApiClient : CallSignalingApi {
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


override suspend fun sendSignaling(
    token: String,
    toUserId: String,
    type: String,
    payload: String,
    callId: String,
    groupId: String,
    groupMemberIds: List<String>,
    groupInvite: Boolean,
    epoch: Long,
    sequence: Long,
    idempotencyKey: String,
): Result<Unit> = sendUnit(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/signaling/send")
        .addHeader("Authorization", "Bearer $token")
        .post(
            jsonBody(
                json.encodeToString(
                    SignalingSendRequest.serializer(),
                    SignalingSendRequest(
                        toUserId = toUserId,
                        type = type,
                        payload = payload,
                        callId = callId,
                        groupId = groupId,
                        groupMemberIds = groupMemberIds,
                        groupInvite = groupInvite,
                        epoch = epoch,
                        sequence = sequence,
                        idempotencyKey = idempotencyKey,
                    )
                )
            )
        )
        .build()
)

override suspend fun hangUpCall(
    token: String,
    toUserId: String,
    callId: String,
    groupId: String,
    groupMemberIds: List<String>,
    epoch: Long,
    sequence: Long,
    idempotencyKey: String,
): Result<Unit> = sendUnit(
    Request.Builder()
        .url("${ApiConfig.BASE_URL}/api/signaling/hangup")
        .addHeader("Authorization", "Bearer $token")
        .post(
            jsonBody(
                json.encodeToString(
                    SignalingSendRequest.serializer(),
                    SignalingSendRequest(
                        toUserId = toUserId,
                        type = "hang-up",
                        payload = "",
                        callId = callId,
                        groupId = groupId,
                        groupMemberIds = groupMemberIds,
                        epoch = epoch,
                        sequence = sequence,
                        idempotencyKey = idempotencyKey,
                    )
                )
            )
        )
        .build()
)

/** 轮询待处理信令（含 offersOnly 冷启动）；走 executeWithRefresh */

override suspend fun getPendingSignaling(token: String, offersOnly: Boolean): Result<List<SignalMessageDto>> {
    val query = if (offersOnly) "?offersOnly=true" else ""
    return send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/signaling/pending$query")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        ListSerializer(SignalMessageDto.serializer())
    )
}

/** TURN/STUN 配置；走 executeWithRefresh，避免长通话 JWT 过期后 ICE 刷新失败 */

override suspend fun getIceConfig(token: String): Result<IceConfigDto> =
    send(
        Request.Builder()
            .url("${ApiConfig.BASE_URL}/api/calls/ice-config")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build(),
        IceConfigDto.serializer()
    )
}
