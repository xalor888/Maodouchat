package com.maodouchat.webrtc

import com.maodouchat.domain.calls.CallIceServer
import com.maodouchat.domain.calls.FailureOperation
import com.maodouchat.domain.calls.SignalMessage
import com.maodouchat.domain.calls.buildSignalPayload
import com.maodouchat.domain.calls.mapFetchFailure
import com.maodouchat.domain.calls.mapIceServers
import com.maodouchat.domain.calls.mapPendingSignals
import com.maodouchat.domain.calls.mapSignalingResult
import com.maodouchat.network.ApiService
import com.maodouchat.network.WebSocketClient

/** WebRTC 信令服务：薄壳，只做网络收发，纯映射在 :domain:calls。 */
object WebRTCSignaling {

    fun sendViaWebSocket(
        toUserId: String,
        type: String,
        payload: String,
        callId: String = "",
        groupId: String = "",
        groupMemberIds: List<String> = emptyList(),
        groupInvite: Boolean = false,
        epoch: Long = 0,
        sequence: Long = 0,
        idempotencyKey: String = "",
    ): Boolean {
        return WebSocketClient.sendRaw(
            buildSignalPayload(
                toUserId, type, payload, callId, groupId, groupMemberIds, groupInvite,
                epoch, sequence, idempotencyKey,
            )
        )
    }

    suspend fun sendViaRest(
        token: String,
        toUserId: String,
        type: String,
        payload: String,
        callId: String = "",
        groupId: String = "",
        groupMemberIds: List<String> = emptyList(),
        groupInvite: Boolean = false,
        epoch: Long = 0,
        sequence: Long = 0,
        idempotencyKey: String = "",
    ): Result<Unit> {
        val result = ApiService.sendSignaling(
            token, toUserId, type, payload, callId, groupId, groupMemberIds, groupInvite,
            epoch, sequence, idempotencyKey,
        )
        return mapSignalingResult(result, FailureOperation.SEND)
    }

    suspend fun fetchPending(token: String, offersOnly: Boolean = false): Result<List<SignalMessage>> {
        return ApiService.getPendingSignaling(token, offersOnly).fold(
            onSuccess = { list -> Result.success(mapPendingSignals(list)) },
            onFailure = { err -> Result.failure(mapFetchFailure(err)) }
        )
    }

    suspend fun fetchIceServers(token: String): Result<List<CallIceServer>> {
        return ApiService.getIceConfig(token).fold(
            onSuccess = { payload -> mapIceServers(payload) },
            onFailure = { err -> Result.failure(mapFetchFailure(err)) }
        )
    }

    suspend fun hangUp(
        token: String,
        toUserId: String,
        callId: String = "",
        groupId: String = "",
        groupMemberIds: List<String> = emptyList(),
        epoch: Long = 0,
        sequence: Long = 0,
        idempotencyKey: String = "",
    ): Result<Unit> {
        val result = ApiService.hangUpCall(
            token, toUserId, callId, groupId, groupMemberIds,
            epoch, sequence, idempotencyKey,
        )
        return mapSignalingResult(result, FailureOperation.HANG_UP)
    }
}
