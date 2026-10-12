package com.maodouchat.server.repository

import com.maodouchat.server.db.DeviceEventConsistencyLog
import com.maodouchat.server.db.DeviceEventSequences
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

data class DeviceSequenceRow(
    val userId: String,
    val deviceId: Int,
    val eventType: String,
    val lastAppliedSeq: Long,
    val lastEventAt: Long,
)

fun AdminExportRepository.deviceSequences(userId: String?): List<DeviceSequenceRow> = transaction {
    val q = DeviceEventSequences.selectAll()
    (if (userId != null) q.andWhere { DeviceEventSequences.userId eq userId } else q)
        .orderBy(DeviceEventSequences.userId to SortOrder.ASC)
        .map { row ->
            DeviceSequenceRow(
                userId = row[DeviceEventSequences.userId],
                deviceId = row[DeviceEventSequences.deviceId],
                eventType = row[DeviceEventSequences.eventType],
                lastAppliedSeq = row[DeviceEventSequences.lastAppliedSeq],
                lastEventAt = row[DeviceEventSequences.lastEventAt],
            )
        }
}

data class DeviceAnomalyRow(
    val id: String,
    val userId: String,
    val deviceId: Int,
    val eventType: String,
    val seq: Long,
    val status: String,
    val referenceId: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val detail: String?,
)

fun AdminExportRepository.deviceAnomalies(userId: String?, status: String?, limit: Int, offset: Long): List<DeviceAnomalyRow> = transaction {
    val q = DeviceEventConsistencyLog.selectAll()
    val filtered = when {
        userId != null && status != null -> q.andWhere {
            (DeviceEventConsistencyLog.userId eq userId) and (DeviceEventConsistencyLog.status eq status)
        }
        userId != null -> q.andWhere { DeviceEventConsistencyLog.userId eq userId }
        status != null -> q.andWhere { DeviceEventConsistencyLog.status eq status }
        else -> q
    }
    filtered.orderBy(
        DeviceEventConsistencyLog.lastSeenAt to SortOrder.DESC,
        DeviceEventConsistencyLog.id to SortOrder.DESC,
    )
        .limit(limit, offset)
        .map { row ->
            DeviceAnomalyRow(
                id = row[DeviceEventConsistencyLog.id],
                userId = row[DeviceEventConsistencyLog.userId],
                deviceId = row[DeviceEventConsistencyLog.deviceId],
                eventType = row[DeviceEventConsistencyLog.eventType],
                seq = row[DeviceEventConsistencyLog.seq],
                status = row[DeviceEventConsistencyLog.status],
                referenceId = row[DeviceEventConsistencyLog.referenceId],
                firstSeenAt = row[DeviceEventConsistencyLog.firstSeenAt],
                lastSeenAt = row[DeviceEventConsistencyLog.lastSeenAt],
                detail = row[DeviceEventConsistencyLog.detail],
            )
        }
}

fun AdminExportRepository.deviceAnomalyCount(userId: String?): Long = transaction {
    val q = DeviceEventConsistencyLog.selectAll()
    (if (userId != null) q.andWhere { DeviceEventConsistencyLog.userId eq userId } else q).count()
}
