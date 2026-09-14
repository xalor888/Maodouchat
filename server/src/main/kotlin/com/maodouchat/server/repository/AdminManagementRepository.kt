package com.maodouchat.server.repository


import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.PushTokens
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.model.ChatType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInSubQuery
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** 会话总览里的 signal 设备条目。 */
data class AdminSignalDeviceRow(
    val deviceId: Int,
    val deviceName: String,
    val status: String,
    val lastSeenAt: Long,
    val createdAt: Long,
)

/** 会话总览里的 push token 条目。 */
data class AdminPushTokenRow(
    val deviceId: String,
    val platform: String,
    val updatedAt: Long,
)

/**
 * 管理搜索返回的消息**元数据**。
 *
 * 刻意没有「内容」字段：人类消息正文对服务端不透明，这里永远读不到、也不该读。
 */
data class AdminMessageMetadataRow(
    val id: String,
    val chatId: String,
    val senderId: String,
    val kind: String,
    val timestamp: Long,
)

data class AdminMessageSearchFilter(
    val q: String,
    val chatId: String,
    val userId: String,
    val limit: Int,
    val offset: Int,
)

/**
 * M2/G15：`AdminManagementRouting` 里 6 处直写事务的归属地。
 *
 * 拆成三块职责：会话总览的两处读查询、管理搜索的一处读查询、以及三处**同样形状**的审计写入。
 * 本类只负责「取数据 / 写审计」，响应 JSON 的形状仍由路由决定——路由管 HTTP，repository 管 SQL。
 */
class AdminManagementRepository {

    fun signalDevices(userId: String): List<AdminSignalDeviceRow> = transaction {
        SignalDevices.selectAll()
            .where { SignalDevices.userId eq userId }
            .orderBy(SignalDevices.lastSeenAt to SortOrder.DESC)
            .map {
                AdminSignalDeviceRow(
                    deviceId = it[SignalDevices.deviceId],
                    deviceName = it[SignalDevices.deviceName],
                    status = it[SignalDevices.status],
                    lastSeenAt = it[SignalDevices.lastSeenAt],
                    createdAt = it[SignalDevices.createdAt],
                )
            }
    }

    fun pushTokens(userId: String): List<AdminPushTokenRow> = transaction {
        PushTokens.selectAll()
            .where { PushTokens.userId eq userId }
            .orderBy(PushTokens.updatedAt to SortOrder.DESC)
            .map {
                AdminPushTokenRow(
                    deviceId = it[PushTokens.deviceId],
                    platform = it[PushTokens.platform],
                    updatedAt = it[PushTokens.updatedAt],
                )
            }
    }

    /** Metadata-only search. Human payloads remain opaque to the server. */
    fun searchMessageMetadata(filter: AdminMessageSearchFilter): List<AdminMessageMetadataRow> = transaction {
        var query = MessagingV2Messages.selectAll()
        if (filter.chatId.isNotBlank()) {
            query = query.andWhere { MessagingV2Messages.conversationId eq filter.chatId }
        }
        if (filter.userId.isNotBlank()) {
            query = query.andWhere { MessagingV2Messages.senderUserId eq filter.userId }
        }
        query = query.andWhere {
            (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE) and
                (MessagingV2Messages.conversationId notInSubQuery (
                    Chats.select(Chats.id).where { Chats.chatType eq ChatType.SECRET }
                ))
        }
        if (filter.q.isNotBlank()) {
            val like = "%" + escapeLikePattern(filter.q.take(80)) + "%"
            query = query.andWhere {
                (MessagingV2Messages.id like like) or
                    (MessagingV2Messages.conversationId like like) or
                    (MessagingV2Messages.senderUserId like like) or
                    (MessagingV2Messages.kind like like)
            }
        }
        query.orderBy(
            MessagingV2Messages.serverTimestamp to SortOrder.DESC,
            MessagingV2Messages.id to SortOrder.DESC,
        )
            .limit(filter.limit, filter.offset.toLong())
            .map {
                AdminMessageMetadataRow(
                    id = it[MessagingV2Messages.id],
                    chatId = it[MessagingV2Messages.conversationId],
                    senderId = it[MessagingV2Messages.senderUserId],
                    kind = it[MessagingV2Messages.kind],
                    timestamp = it[MessagingV2Messages.serverTimestamp],
                )
            }
    }

    /**
     * 三处路由都只是「写一行审计」，收敛到这一个 owner。
     * `userId` 为 null 表示这条审计不对应某个具体用户（例如广播）。
     */
    fun recordAudit(actorId: String?, userId: String?, action: String, detail: String?) {
        transaction {
            ModerationAuditLog.insert {
                it[ModerationAuditLog.actorId] = actorId
                it[ModerationAuditLog.userId] = userId
                it[ModerationAuditLog.action] = action
                it[ModerationAuditLog.detail] = detail
                it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
            }
        }
    }
}
