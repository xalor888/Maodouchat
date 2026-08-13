package com.maodouchat.server.repository

import com.maodouchat.server.db.AiSummarySyncEnvelopes
import com.maodouchat.server.model.AiSummarySyncEnvelopeResponse
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.util.UUID

class AiSummarySyncRepository {

    fun store(
        userId: String,
        syncId: String,
        senderDeviceId: Int,
        targetDeviceIds: List<Int>,
        envelope: String
    ): Int = transaction {
        val now = System.currentTimeMillis()
        deleteExpiredRows(now)
        targetDeviceIds.distinct().forEach { targetDeviceId ->
            AiSummarySyncEnvelopes.upsert(
                AiSummarySyncEnvelopes.userId,
                AiSummarySyncEnvelopes.syncId,
                AiSummarySyncEnvelopes.targetDeviceId
            ) {
                it[AiSummarySyncEnvelopes.id] = "ase_${UUID.randomUUID()}"
                it[AiSummarySyncEnvelopes.userId] = userId
                it[AiSummarySyncEnvelopes.syncId] = syncId
                it[AiSummarySyncEnvelopes.senderDeviceId] = senderDeviceId
                it[AiSummarySyncEnvelopes.targetDeviceId] = targetDeviceId
                it[AiSummarySyncEnvelopes.envelope] = envelope
                it[AiSummarySyncEnvelopes.createdAt] = now
                it[AiSummarySyncEnvelopes.expiresAt] = now + RETENTION_MS
            }
            pruneDeviceQueue(userId, targetDeviceId)
        }
        targetDeviceIds.distinct().size
    }

    fun pending(userId: String, targetDeviceId: Int, limit: Int): List<AiSummarySyncEnvelopeResponse> = transaction {
        deleteExpiredRows(System.currentTimeMillis())
        AiSummarySyncEnvelopes.selectAll()
            .where {
                (AiSummarySyncEnvelopes.userId eq userId) and
                    (AiSummarySyncEnvelopes.targetDeviceId eq targetDeviceId)
            }
            .orderBy(AiSummarySyncEnvelopes.createdAt to SortOrder.ASC, AiSummarySyncEnvelopes.id to SortOrder.ASC)
            .limit(limit.coerceIn(1, MAX_FETCH))
            .map { row ->
                AiSummarySyncEnvelopeResponse(
                    id = row[AiSummarySyncEnvelopes.id],
                    syncId = row[AiSummarySyncEnvelopes.syncId],
                    senderDeviceId = row[AiSummarySyncEnvelopes.senderDeviceId],
                    envelope = row[AiSummarySyncEnvelopes.envelope],
                    createdAt = row[AiSummarySyncEnvelopes.createdAt]
                )
            }
    }

    fun acknowledge(userId: String, targetDeviceId: Int, envelopeIds: List<String>): Int = transaction {
        if (envelopeIds.isEmpty()) return@transaction 0
        AiSummarySyncEnvelopes.deleteWhere {
            (AiSummarySyncEnvelopes.userId eq userId) and
                (AiSummarySyncEnvelopes.targetDeviceId eq targetDeviceId) and
                (AiSummarySyncEnvelopes.id inList envelopeIds.distinct())
        }
    }

    fun deleteForDevice(userId: String, deviceId: Int): Int = transaction {
        AiSummarySyncEnvelopes.deleteWhere {
            (AiSummarySyncEnvelopes.userId eq userId) and
                ((AiSummarySyncEnvelopes.targetDeviceId eq deviceId) or
                    (AiSummarySyncEnvelopes.senderDeviceId eq deviceId))
        }
    }

    fun deleteExpired(): Int = transaction {
        deleteExpiredRows(System.currentTimeMillis())
    }

    private fun deleteExpiredRows(now: Long): Int {
        return AiSummarySyncEnvelopes.deleteWhere { AiSummarySyncEnvelopes.expiresAt lessEq now }
    }

    private fun pruneDeviceQueue(userId: String, targetDeviceId: Int) {
        val staleIds = AiSummarySyncEnvelopes.select(AiSummarySyncEnvelopes.id)
            .where {
                (AiSummarySyncEnvelopes.userId eq userId) and
                    (AiSummarySyncEnvelopes.targetDeviceId eq targetDeviceId)
            }
            .orderBy(AiSummarySyncEnvelopes.createdAt to SortOrder.DESC, AiSummarySyncEnvelopes.id to SortOrder.DESC)
            .map { it[AiSummarySyncEnvelopes.id] }
            .drop(MAX_PER_DEVICE)
        if (staleIds.isNotEmpty()) {
            AiSummarySyncEnvelopes.deleteWhere { AiSummarySyncEnvelopes.id inList staleIds }
        }
    }

    private companion object {
        const val MAX_FETCH = 120
        const val MAX_PER_DEVICE = 240
        const val RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L
    }
}
