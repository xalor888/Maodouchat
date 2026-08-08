package com.maodouchat.webrtc

import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.network.ApiService
import com.maodouchat.network.WebSocketClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * WebRTC 信令服务
 *
 * 通过 WebSocket 或 REST API 交换 SDP Offer/Answer 和 ICE Candidate。
 * REST 路径统一走 ApiService（含 token 刷新）。
 */
object WebRTCSignaling {

    enum class FailureOperation { SEND, FETCH, HANG_UP }

    class SignalingException(
        val operation: FailureOperation,
        val statusCode: Int? = null,
        val code: String? = null,
        val retryAfterSeconds: Long? = null
    ) :
        Exception("WebRTC signaling ${operation.name.lowercase()} failed")

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class SendSignalRequest(
        val toUserId: String,
        val type: String,
        val payload: String,
        val callId: String = "",
        val groupId: String = "",
        val groupMemberIds: List<String> = emptyList(),
        val groupInvite: Boolean = false
    )

    @Serializable
    data class SignalMessage(
        val id: String,
        val fromUserId: String,
        val type: String,
        val payload: String,
        val timestamp: Long,
        val callId: String = "",
        val groupId: String = "",
        val groupMemberIds: List<String> = emptyList(),
        val groupInvite: Boolean = false
    )

    /**
     * 通过 WebSocket 发送信令消息（优先，实时）
     */
    fun sendViaWebSocket(
        toUserId: String,
        type: String,
        payload: String,
        callId: String = "",
        groupId: String = "",
        groupMemberIds: List<String> = emptyList(),
        groupInvite: Boolean = false
    ): Boolean {
        val request = SendSignalRequest(toUserId, type, payload, callId, groupId, groupMemberIds, groupInvite)
        val signalPayload = json.encodeToString(SendSignalRequest.serializer(), request)
        val wsMsg = json.encodeToString(
            com.maodouchat.network.WsMessage.serializer(),
            com.maodouchat.network.WsMessage("SIGNALING", signalPayload)
        )
        return WebSocketClient.sendRaw(wsMsg)
    }

    /**
     * 通过 REST API 发送信令消息（备选，WebSocket 断开时使用）
     * 走 ApiService.executeWithRefresh，避免长通话 JWT 过期后信令失败。
     */
    suspend fun sendViaRest(
        token: String,
        toUserId: String,
        type: String,
        payload: String,
        callId: String = "",
        groupId: String = "",
        groupMemberIds: List<String> = emptyList(),
        groupInvite: Boolean = false
    ): Result<Unit> {
        val result = ApiService.sendSignaling(
            token, toUserId, type, payload, callId, groupId, groupMemberIds, groupInvite
        )
        return mapSignalingResult(result, FailureOperation.SEND)
    }

    /**
     * 获取待处理的信令消息（轮询模式）。
     * 走 ApiService.executeWithRefresh：冷启动 / 长会话 JWT 过期时先 refresh 再拉 pending，
     * 避免 IncomingCallWake 因 401 丢 offer/hang-up。
     */
    suspend fun fetchPending(token: String, offersOnly: Boolean = false): Result<List<SignalMessage>> {
        return ApiService.getPendingSignaling(token, offersOnly).fold(
            onSuccess = { list ->
                Result.success(
                    list.map {
                        SignalMessage(
                            id = it.id,
                            fromUserId = it.fromUserId,
                            type = it.type,
                            payload = it.payload,
                            timestamp = it.timestamp,
                            callId = it.callId,
                            groupId = it.groupId,
                            groupMemberIds = it.groupMemberIds,
                            groupInvite = it.groupInvite
                        )
                    }
                )
            },
            onFailure = { err -> Result.failure(mapFetchFailure(err)) }
        )
    }

    /** Fetches short-lived TURN credentials. Callers may fall back to STUN if this request fails. */
    suspend fun fetchIceServers(token: String): Result<List<CallIceServer>> {
        return ApiService.getIceConfig(token).fold(
            onSuccess = { payload ->
                val servers = payload.iceServers.mapNotNull { server ->
                    val urls = server.urls.filter {
                        it.startsWith("stun:") || it.startsWith("turn:") || it.startsWith("turns:")
                    }
                    if (urls.isEmpty()) null else CallIceServer(urls, server.username, server.credential)
                }
                if (servers.isEmpty()) Result.failure(SignalingException(FailureOperation.FETCH))
                else Result.success(servers)
            },
            onFailure = { err -> Result.failure(mapFetchFailure(err)) }
        )
    }

    private fun mapFetchFailure(err: Throwable): Throwable {
        return if (err is ApiException) {
            SignalingException(FailureOperation.FETCH, statusCode = err.statusCode, code = err.serverCode)
        } else {
            err
        }
    }

    /**
     * 挂断通话（清理信令消息）。走 ApiService 刷新 token，避免长通话后 401。
     */
    suspend fun hangUp(
        token: String,
        toUserId: String,
        callId: String = "",
        groupId: String = "",
        groupMemberIds: List<String> = emptyList()
    ): Result<Unit> {
        val result = ApiService.hangUpCall(token, toUserId, callId, groupId, groupMemberIds)
        return mapSignalingResult(result, FailureOperation.HANG_UP)
    }

    private fun mapSignalingResult(result: Result<Unit>, operation: FailureOperation): Result<Unit> {
        val err = result.exceptionOrNull() ?: return result
        if (err is SignalingException) return result
        if (err is ApiException && err.kind == ApiFailureKind.HTTP) {
            return Result.failure(
                SignalingException(
                    operation,
                    statusCode = err.statusCode,
                    code = err.serverCode
                )
            )
        }
        return Result.failure(err)
    }
}
