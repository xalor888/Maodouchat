package com.maodouchat.server.repository

import com.maodouchat.server.db.SignalingMessages
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and

internal fun SignalingRepository.purgeStaleInTx() {
    val now = System.currentTimeMillis()
    val last = lastPurgeAt.get()
    if (now - last < PURGE_MIN_INTERVAL_MS) return
    if (!lastPurgeAt.compareAndSet(last, now)) return
    val cutoff = now - SignalingRepository.SIGNALING_TTL_MS
    SignalingMessages.deleteWhere { SignalingMessages.timestamp less cutoff }
}

internal fun SignalingRepository.clearForCallExcludingInTx(userId1: String, userId2: String, callId: String, excludeId: String) {
    if (callId.isBlank()) return
    SignalingMessages.deleteWhere {
        val participantsMatch = (
            ((SignalingMessages.fromUserId eq userId1) and (SignalingMessages.toUserId eq userId2)) or
                ((SignalingMessages.fromUserId eq userId2) and (SignalingMessages.toUserId eq userId1))
            )
        participantsMatch and
            (SignalingMessages.callId eq callId) and
            (SignalingMessages.id neq excludeId)
    }
}
