package com.maodouchat.server.repository

// 举报提交通道：reporter 侧创建举报（原 ReportWorkflowLifecycle 簇成员按域迁出，零行为改动）。
import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.Reports
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.CreateReportRequest
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import com.maodouchat.server.service.isUniqueViolation
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID


fun ReportWorkflow.createReport(reporterId: String, request: CreateReportRequest): CreateResult {
    val targetType = request.targetType.trim().uppercase()
    val targetId = request.targetId.trim()
    return try {
        transaction {
            if (targetType !in ALLOWED_TARGET_TYPES) return@transaction CreateResult.Failure("举报类型无效")
            if (targetId.isBlank() || targetId.length > 100) return@transaction CreateResult.Failure("举报对象无效")
            val reason = request.reason.trim().take(MAX_REASON_LENGTH)
            if (reason.isBlank()) return@transaction CreateResult.Failure("请选择举报原因")
            val description = request.description?.trim()?.take(MAX_DESCRIPTION_LENGTH)?.takeIf { it.isNotBlank() }

            val normalizedChatId: String?
            val normalizedMessageId: String?
            when (targetType) {
                "USER" -> {
                    if (targetId == reporterId) return@transaction CreateResult.Failure("不能举报自己")
                    val exists = Users.selectAll().where { Users.id eq targetId }.firstOrNull() != null
                    if (!exists) return@transaction CreateResult.Failure("用户不存在")
                    normalizedChatId = request.chatId?.trim()?.takeIf(String::isNotBlank)
                    normalizedMessageId = null
                }
                "MESSAGE" -> {
                    val message = MessagingV2Messages.selectAll().where {
                        (MessagingV2Messages.id eq targetId) and
                            (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
                    }.firstOrNull()
                        ?: return@transaction CreateResult.Failure("消息不存在")
                    val messageChatId = message[MessagingV2Messages.conversationId]
                    val canSee = ChatParticipants.selectAll()
                        .where { (ChatParticipants.chatId eq messageChatId) and (ChatParticipants.userId eq reporterId) }
                        .firstOrNull() != null
                    if (!canSee) return@transaction CreateResult.Failure("无权举报该消息")
                    normalizedChatId = messageChatId
                    normalizedMessageId = targetId
                }
                "POST" -> {
                    val exists = Posts.selectAll().where { Posts.id eq targetId }.firstOrNull() != null
                    if (!exists) return@transaction CreateResult.Failure("动态不存在")
                    // 8.38：不可见的动态不得举报（PRIVATE/被拉黑），防止对被拉黑用户刷审核负载
                    if (!com.maodouchat.server.repository.PostRepository().canView(targetId, reporterId)) {
                        return@transaction CreateResult.Failure("无权举报该动态")
                    }
                    normalizedChatId = null
                    normalizedMessageId = null
                }
                "COMMENT" -> {
                    val comment = PostComments.selectAll().where { PostComments.id eq targetId }.firstOrNull()
                        ?: return@transaction CreateResult.Failure("评论不存在")
                    val postId = comment[PostComments.postId]
                    if (!com.maodouchat.server.repository.PostRepository().canView(postId, reporterId)) {
                        return@transaction CreateResult.Failure("无权举报该评论")
                    }
                    normalizedChatId = null
                    normalizedMessageId = null
                }
                else -> return@transaction CreateResult.Failure("举报类型无效")
            }

            val now = System.currentTimeMillis()
            // 去重：同一举报人对同一目标近 24h 已存在 OPEN 举报时直接返回已有，防止刷量制造无限审核负载
            val dedupWindow = now - 24L * 60 * 60 * 1000
            val existingOpen = Reports.selectAll()
                .where {
                    (Reports.reporterId eq reporterId) and
                        (Reports.targetType eq targetType) and
                        (Reports.targetId eq targetId) and
                        (Reports.status eq "OPEN") and
                        (Reports.createdAt greaterEq dedupWindow)
                }
                .firstOrNull()
            if (existingOpen != null) {
                return@transaction CreateResult.Success(existingOpen.toResponse())
            }
            val id = "rep_${UUID.randomUUID()}"
            Reports.insert {
                it[Reports.id] = id
                it[Reports.reporterId] = reporterId
                it[Reports.targetType] = targetType
                it[Reports.targetId] = targetId
                it[Reports.chatId] = normalizedChatId
                it[Reports.messageId] = normalizedMessageId
                it[Reports.reason] = reason
                it[Reports.description] = description
                it[Reports.status] = "OPEN"
                it[Reports.reviewerId] = null
                it[Reports.resolutionNote] = null
                it[Reports.createdAt] = now
                it[Reports.resolvedAt] = null
            }
            CreateResult.Success(Reports.selectAll().where { Reports.id eq id }.first().toResponse())
        }
    } catch (conflict: org.jetbrains.exposed.exceptions.ExposedSQLException) {
        // 部分唯一索引 uidx_reports_open_dedup 兜底：并发提交同一目标的 OPEN 举报时，
        // 后提交者冲突。PG 上冲突已 abort 本事务、同事务回读必 500——必须在 Exposed
        // 回滚后的全新事务里回读已有行返回，保证幂等且不 500。
        if (!isUniqueViolation(conflict)) throw conflict
        transaction {
            val existing = Reports.selectAll().where {
                (Reports.reporterId eq reporterId) and
                    (Reports.targetType eq targetType) and
                    (Reports.targetId eq targetId) and
                    (Reports.status eq "OPEN")
            }.firstOrNull()
            if (existing != null) CreateResult.Success(existing.toResponse())
            else throw conflict
        }
    }
}
