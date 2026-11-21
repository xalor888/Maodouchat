package com.maodouchat.domain.calls

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.ApiFailureKind
import com.maodouchat.core.model.IceConfigDto
import com.maodouchat.core.model.SendSignalRequest
import com.maodouchat.core.model.SignalMessageDto
import com.maodouchat.core.model.WsMessage
import kotlinx.serialization.json.Json

private val signalingJson = Json { ignoreUnknownKeys = true }

/** 把信令请求包进 SIGNALING 信令信封，返回待发送的原始文本。 */
fun buildSignalPayload(
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
): String {
    val request = SendSignalRequest(
        toUserId, type, payload, callId, groupId, groupMemberIds, groupInvite,
        epoch, sequence, idempotencyKey,
    )
    val signalPayload = signalingJson.encodeToString(SendSignalRequest.serializer(), request)
    return signalingJson.encodeToString(
        WsMessage.serializer(),
        WsMessage("SIGNALING", signalPayload)
    )
}

/** 待取信令 DTO 逐字段转 SignalMessage。 */
fun mapPendingSignals(dtos: List<SignalMessageDto>): List<SignalMessage> =
    dtos.map {
        SignalMessage(
            id = it.id,
            fromUserId = it.fromUserId,
            type = it.type,
            payload = it.payload,
            timestamp = it.timestamp,
            callId = it.callId,
            groupId = it.groupId,
            groupMemberIds = it.groupMemberIds,
            groupInvite = it.groupInvite,
            epoch = it.epoch,
            sequence = it.sequence,
            idempotencyKey = it.idempotencyKey,
        )
    }

/** 只保留 stun:/turn:/turns: 的 URL；过滤后无可用服务器则报 FETCH 失败。 */
fun mapIceServers(config: IceConfigDto): Result<List<CallIceServer>> {
    val servers = config.iceServers.mapNotNull { server ->
        val urls = server.urls.filter {
            it.startsWith("stun:") || it.startsWith("turn:") || it.startsWith("turns:")
        }
        if (urls.isEmpty()) null else CallIceServer(urls, server.username, server.credential)
    }
    return if (servers.isEmpty()) Result.failure(SignalingException(FailureOperation.FETCH))
    else Result.success(servers)
}

fun mapFetchFailure(err: Throwable): Throwable {
    return if (err is ApiException) {
        SignalingException(FailureOperation.FETCH, statusCode = err.statusCode, code = err.serverCode)
    } else {
        err
    }
}

fun mapSignalingResult(result: Result<Unit>, operation: FailureOperation): Result<Unit> {
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
