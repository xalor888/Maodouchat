package com.maodouchat.server.service

import com.maodouchat.server.db.AiAuditLogs
import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.Reports
import com.maodouchat.server.db.Users
import com.maodouchat.server.db.dayBucketExpression
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.AdminRichTrendsResponse
import com.maodouchat.server.model.AdminTrendsResponse
import com.maodouchat.server.model.TrendPointResponse
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun OperationsQueryService.trends(now: Long = System.currentTimeMillis(), points: Int = 7): AdminTrendsResponse = transaction {
    val startMs = now - (points - 1) * DAY_MS
    val dayStartMs = startMs - (startMs % DAY_MS)
    val userBucket = dayBucketExpression(Users.lastSeen)
    val userCounts = Users
        .slice(userBucket, Users.id.count())
        .selectAll()
        .where { Users.lastSeen greaterEq dayStartMs }
        .groupBy(userBucket)
        .toList()
        .associate { it[userBucket] to it[Users.id.count()].toLong() }
    val msgBucket = dayBucketExpression(MessagingV2Messages.serverTimestamp)
    val messageCounts = MessagingV2Messages
        .slice(msgBucket, MessagingV2Messages.id.count())
        .selectAll()
        .where {
            (MessagingV2Messages.serverTimestamp greaterEq dayStartMs) and
                (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
        }
        .groupBy(msgBucket)
        .toList()
        .associate { it[msgBucket] to it[MessagingV2Messages.id.count()].toLong() }
    val postBucket = dayBucketExpression(Posts.createdAt)
    val postCounts = Posts
        .slice(postBucket, Posts.id.count())
        .selectAll()
        .where { Posts.createdAt greaterEq dayStartMs }
        .groupBy(postBucket)
        .toList()
        .associate { it[postBucket] to it[Posts.id.count()].toLong() }
    val userPoints = mutableListOf<TrendPointResponse>()
    val messagePoints = mutableListOf<TrendPointResponse>()
    val postPoints = mutableListOf<TrendPointResponse>()
    for (i in points - 1 downTo 0) {
        val dayStart = now - i * DAY_MS
        val dayStartNorm = dayStart - (dayStart % DAY_MS)
        val bucket = dayStartNorm / DAY_MS
        userPoints += TrendPointResponse(dayStartNorm, userCounts[bucket] ?: 0)
        messagePoints += TrendPointResponse(dayStartNorm, messageCounts[bucket] ?: 0)
        postPoints += TrendPointResponse(dayStartNorm, postCounts[bucket] ?: 0)
    }
    AdminTrendsResponse(userPoints, messagePoints, postPoints)
}
fun OperationsQueryService.richTrends(now: Long = System.currentTimeMillis(), points: Int = 7): AdminRichTrendsResponse = transaction {
    val startMs = now - (points - 1) * DAY_MS
    val dayStartMs = startMs - (startMs % DAY_MS)
    fun dayCounts(col: Column<Long>): Map<Long, Long> {
        val bucket = dayBucketExpression(col)
        return col.table.slice(bucket, col.count()).selectAll()
            .where { col greaterEq dayStartMs }
            .groupBy(bucket)
            .toList()
            .associate { it[bucket] to it[col.count()].toLong() }
    }
    val userCounts = dayCounts(Users.lastSeen)
    val messageBucket = dayBucketExpression(MessagingV2Messages.serverTimestamp)
    val messageCounts = MessagingV2Messages
        .slice(messageBucket, MessagingV2Messages.id.count())
        .selectAll()
        .where {
            (MessagingV2Messages.serverTimestamp greaterEq dayStartMs) and
                (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
        }
        .groupBy(messageBucket)
        .associate { it[messageBucket] to it[MessagingV2Messages.id.count()].toLong() }
    val postCounts = dayCounts(Posts.createdAt)
    val reportCounts = dayCounts(Reports.createdAt)
    val aiCounts = dayCounts(AiAuditLogs.createdAt)
    val attachCounts = dayCounts(EncryptedAttachments.createdAt)
    val activeBucket = dayBucketExpression(Users.lastSeen)
    val activeDaily = Users
        .slice(activeBucket, Users.id.count())
        .selectAll()
        .where { Users.lastSeen greaterEq dayStartMs }
        .groupBy(activeBucket)
        .toList()
        .associate { it[activeBucket] to it[Users.id.count()].toLong() }
    var running = 0L
    val activeByDay = (0 until points).associate { i ->
        val dayStart = now - i * DAY_MS
        val dayStartNorm = dayStart - (dayStart % DAY_MS)
        running += activeDaily[dayStartNorm / DAY_MS] ?: 0
        dayStartNorm to running
    }
    val userPts = mutableListOf<TrendPointResponse>()
    val msgPts = mutableListOf<TrendPointResponse>()
    val postPts = mutableListOf<TrendPointResponse>()
    val reportPts = mutableListOf<TrendPointResponse>()
    val aiPts = mutableListOf<TrendPointResponse>()
    val attachPts = mutableListOf<TrendPointResponse>()
    val activePts = mutableListOf<TrendPointResponse>()
    for (i in points - 1 downTo 0) {
        val dayStart = now - i * DAY_MS
        val dayStartNorm = dayStart - (dayStart % DAY_MS)
        val bucket = dayStartNorm / DAY_MS
        userPts += TrendPointResponse(dayStartNorm, userCounts[bucket] ?: 0)
        msgPts += TrendPointResponse(dayStartNorm, messageCounts[bucket] ?: 0)
        postPts += TrendPointResponse(dayStartNorm, postCounts[bucket] ?: 0)
        reportPts += TrendPointResponse(dayStartNorm, reportCounts[bucket] ?: 0)
        aiPts += TrendPointResponse(dayStartNorm, aiCounts[bucket] ?: 0)
        attachPts += TrendPointResponse(dayStartNorm, attachCounts[bucket] ?: 0)
        activePts += TrendPointResponse(dayStartNorm, activeByDay[dayStartNorm] ?: 0)
    }
    AdminRichTrendsResponse(userPts, msgPts, postPts, reportPts, aiPts, attachPts, activePts)
}
