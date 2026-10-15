package com.maodouchat.network.api

import com.maodouchat.network.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal object CallSignalingApiClient : CallSignalingApi {
private val json get() = ApiService.json
private val JSON_MEDIA get() = ApiService.JSON_MEDIA
private fun jsonBody(value: String) = value.toRequestBody(ApiService.JSON_MEDIA)
private suspend fun <T> send(request: Request, serializer: kotlinx.serialization.KSerializer<T>): Result<T> = ApiService.send(request, serializer)
private suspend fun sendUnit(request: Request): Result<Unit> = ApiService.sendUnit(request)


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
