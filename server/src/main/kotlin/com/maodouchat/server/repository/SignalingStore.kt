package com.maodouchat.server.repository

import com.maodouchat.server.db.SignalingMessages
import com.maodouchat.server.service.Cursor
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

fun SignalingRepository.store(
    fromUserId: String,
    toUserId: String,
    type: String,
    payload: String,
    callId: String,
    groupId: String = "",
    groupMemberIds: List<String> = emptyList(),
    groupInvite: Boolean = false,
    epoch: Long = 0,
    sequence: Long = 0,
    idempotencyKey: String = "",
): String {
    return transaction {
        storeInTx(fromUserId, toUserId, type, payload, callId, groupId, groupMemberIds, groupInvite, epoch, sequence, idempotencyKey)
    }
}

// 同 callId + 发送方已接受的最大 (epoch, sequence) 游标；无记录时 null。
// 遗留 0/0 行不参与游标，避免挡住已编号客户端。
fun SignalingRepository.latestCursor(callId: String, fromUserId: String): Cursor? {
    if (callId.isBlank() || fromUserId.isBlank()) return null
    return transaction {
        SignalingMessages.selectAll().where {
            (SignalingMessages.callId eq callId) and
                (SignalingMessages.fromUserId eq fromUserId) and
                (
                    (SignalingMessages.epoch greater 0L) or
                        (SignalingMessages.sequence greater 0L)
                )
        }.orderBy(
            SignalingMessages.epoch to SortOrder.DESC,
            SignalingMessages.sequence to SortOrder.DESC,
        ).limit(1)
            .firstOrNull()
            ?.let {
                Cursor(
                    epoch = it[SignalingMessages.epoch],
                    sequence = it[SignalingMessages.sequence],
                )
            }
    }
}

// 终端信令与清理同 callId 旧信令放在同一事务：避免 store 已提交、clear 未执行的窗口。
fun SignalingRepository.storeTerminalAndClearOthers(
    fromUserId: String,
    toUserId: String,
    type: String,
    payload: String,
    callId: String,
    groupId: String = "",
    groupMemberIds: List<String> = emptyList(),
    groupInvite: Boolean = false,
    epoch: Long = 0,
    sequence: Long = 0,
    idempotencyKey: String = "",
): String {
    val normalizedType = type.lowercase()
    require(normalizedType in TERMINAL_SIGNAL_TYPES) { "not_terminal_signal:$type" }
    return transaction {
        val id = storeInTx(
            fromUserId,
            toUserId,
            normalizedType,
            payload,
            callId,
            groupId,
            groupMemberIds,
            groupInvite,
            epoch,
            sequence,
            idempotencyKey,
        )
        if (callId.isNotBlank()) {
            clearForCallExcludingInTx(fromUserId, toUserId, callId, id)
        }
        id
    }
}

internal fun SignalingRepository.storeInTx(
    fromUserId: String,
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
): String {
    purgeStaleInTx()
    // 幂等：同 sender + callId 已存同一 idempotencyKey 时复用既有行，避免客户端重试产生重复信令。
    if (idempotencyKey.isNotBlank()) {
        SignalingMessages.selectAll().where {
            (SignalingMessages.idempotencyKey eq idempotencyKey) and
                (SignalingMessages.fromUserId eq fromUserId) and
                (SignalingMessages.callId eq callId)
        }.firstOrNull()?.let { return it[SignalingMessages.id] }
    }
    val id = "sig_${UUID.randomUUID()}"
    SignalingMessages.insert {
        it[SignalingMessages.id] = id
        it[SignalingMessages.fromUserId] = fromUserId
        it[SignalingMessages.toUserId] = toUserId
        it[SignalingMessages.callId] = callId
        it[SignalingMessages.groupId] = groupId
        it[SignalingMessages.groupMemberIds] = groupMemberIds.joinToString(",")
        it[SignalingMessages.groupInvite] = groupInvite
        it[SignalingMessages.type] = type
        it[SignalingMessages.payload] = payload
        it[SignalingMessages.epoch] = epoch
        it[SignalingMessages.sequence] = sequence
        it[SignalingMessages.idempotencyKey] = idempotencyKey
        it[SignalingMessages.timestamp] = System.currentTimeMillis()
    }
    return id
}
