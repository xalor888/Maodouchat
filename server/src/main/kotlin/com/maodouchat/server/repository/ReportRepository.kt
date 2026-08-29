package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.PostComments
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.Reports
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.CreateReportRequest
import com.maodouchat.server.model.ReportResponse
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class ReportRepository {

    sealed class CreateResult {
        data class Success(val report: ReportResponse) : CreateResult()
        data class Failure(val message: String) : CreateResult()
    }

    sealed class UpdateResult {
        data class Success(val report: ReportResponse) : UpdateResult()
        data class Failure(val message: String) : UpdateResult()
    }

    /**
     * 处置标记结果：
     * - Applied：本请求首次写入 actionTaken，调用方应执行副作用
     * - AlreadyDone：已处置，调用方必须跳过副作用
     */
    sealed class ActionMarkResult {
        data class Applied(val report: ReportResponse) : ActionMarkResult()
        data class AlreadyDone(val report: ReportResponse) : ActionMarkResult()
        data class Failure(val message: String) : ActionMarkResult()
    }

    fun createReport(reporterId: String, request: CreateReportRequest): CreateResult {
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

    fun getMyReports(reporterId: String, limit: Int = 50): List<ReportResponse> = transaction {
        Reports.selectAll()
            .where { Reports.reporterId eq reporterId }
            .orderBy(Reports.createdAt to SortOrder.DESC, Reports.id to SortOrder.DESC)
            .limit(limit.coerceIn(1, 100))
            .map { it.toResponse() }
    }

    fun getReport(reportId: String): ReportResponse? = transaction {
        Reports.selectAll()
            .where { Reports.id eq reportId.trim() }
            .firstOrNull()
            ?.toResponse()
    }

    fun getReports(status: String? = null, limit: Int = 50, offset: Long = 0): List<ReportResponse> = transaction {
        val normalizedStatus = status?.trim()?.uppercase()?.takeIf { it.isNotBlank() && it != "ALL" }
        val query = Reports.selectAll()
        if (normalizedStatus != null) query.andWhere { Reports.status eq normalizedStatus }
        query.orderBy(Reports.createdAt to SortOrder.DESC, Reports.id to SortOrder.DESC)
            .limit(limit.coerceIn(1, 200), offset.coerceAtLeast(0))
            .map { it.toResponse() }
    }

    /**
     * 清理办结超过保留期的举报记录（默认 365 天，按 resolvedAt 计），防止无限增长。
     * 未办结（OPEN/IN_REVIEW）的举报永不清理。由 Routing.kt 的周期清理循环调用。
     */
    fun purgeResolvedOlderThan(retentionDays: Long = 365): Int {
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        return transaction {
            Reports.deleteWhere {
                (Reports.resolvedAt.isNotNull()) and (Reports.resolvedAt less cutoff)
            }
        }
    }

    fun updateReportStatus(reportId: String, reviewerId: String, status: String, resolutionNote: String?): UpdateResult = transaction {
        val normalizedId = reportId.trim()
        val normalizedStatus = status.trim().uppercase()
        if (normalizedId.isBlank()) return@transaction UpdateResult.Failure("举报不存在")
        if (normalizedStatus !in ALLOWED_STATUSES) return@transaction UpdateResult.Failure("举报状态无效")
        val existing = Reports.selectAll().where { Reports.id eq normalizedId }.firstOrNull()
            ?: return@transaction UpdateResult.Failure("举报不存在")
        // 8.34 修复：已执行处置动作的举报状态不可变——此前 RESOLVED（已封禁/已删内容）
        // → REJECTED 翻转畅通无阻：处罚副作用仍在，举报状态却读作「未违规」，审核视图
        // 按 REJECTED 过滤会漏掉已处罚目标。仅允许幂等同状态（重试）返回。
        val alreadyActioned = !existing[Reports.actionTaken].isNullOrBlank()
        if (alreadyActioned) {
            if (normalizedStatus != existing[Reports.status]) {
                return@transaction UpdateResult.Failure("已处置的举报不能变更状态")
            }
            return@transaction UpdateResult.Success(existing.toResponse())
        }
        val note = resolutionNote?.trim()?.take(MAX_RESOLUTION_NOTE_LENGTH)?.takeIf { it.isNotBlank() }
        val resolvedAt = if (normalizedStatus in FINAL_STATUSES) System.currentTimeMillis() else null
        Reports.update({ Reports.id eq existing[Reports.id] }) {
            it[Reports.status] = normalizedStatus
            it[Reports.reviewerId] = reviewerId
            it[Reports.resolutionNote] = note
            it[Reports.resolvedAt] = resolvedAt
        }
        ModerationAuditLog.insert {
            it[ModerationAuditLog.actorId] = reviewerId
            it[ModerationAuditLog.userId] = existing[Reports.targetId].takeIf { existing[Reports.targetType] == "USER" }
            it[ModerationAuditLog.action] = "REPORT_STATUS_UPDATE"
            it[ModerationAuditLog.detail] = "reportId=$normalizedId; status=$normalizedStatus"
            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
        }
        UpdateResult.Success(Reports.selectAll().where { Reports.id eq normalizedId }.first().toResponse())
    }

    /**
     * 原子标记处置完成（SELECT … FOR UPDATE）。
     * 仅首次写入 actionTaken 时返回 Applied，调用方据此决定是否执行封禁/删内容等副作用。
     */
    fun markActionTaken(reportId: String, reviewerId: String, action: String, resolutionNote: String?): ActionMarkResult = transaction {
        val normalizedId = reportId.trim()
        val normalizedAction = action.trim().uppercase()
        if (normalizedAction !in ALLOWED_ACTIONS) return@transaction ActionMarkResult.Failure("处置动作无效")
        val existing = Reports.selectAll().where { Reports.id eq normalizedId }.forUpdate().firstOrNull()
            ?: return@transaction ActionMarkResult.Failure("举报不存在")
        if (!existing[Reports.actionTaken].isNullOrBlank() || existing[Reports.status] in FINAL_STATUSES) {
            return@transaction ActionMarkResult.AlreadyDone(existing.toResponse())
        }
        val now = System.currentTimeMillis()
        val note = resolutionNote?.trim()?.take(MAX_RESOLUTION_NOTE_LENGTH)?.takeIf { it.isNotBlank() }
            ?: existing[Reports.resolutionNote]
        Reports.update({ Reports.id eq existing[Reports.id] }) {
            it[Reports.status] = "RESOLVED"
            it[Reports.reviewerId] = reviewerId
            it[Reports.resolutionNote] = note
            it[Reports.actionTaken] = normalizedAction
            it[Reports.actionAt] = now
            it[Reports.resolvedAt] = now
        }
        ModerationAuditLog.insert {
            it[ModerationAuditLog.actorId] = reviewerId
            it[ModerationAuditLog.userId] = existing[Reports.targetId].takeIf { existing[Reports.targetType] == "USER" }
            it[ModerationAuditLog.action] = "REPORT_ACTION_APPLIED"
            it[ModerationAuditLog.detail] = "reportId=$normalizedId; action=$normalizedAction"
            it[ModerationAuditLog.createdAt] = now
        }
        ActionMarkResult.Applied(Reports.selectAll().where { Reports.id eq normalizedId }.first().toResponse())
    }

    private fun ResultRow.toResponse(): ReportResponse {
        return ReportResponse(
            id = this[Reports.id],
            reporterId = this[Reports.reporterId],
            targetType = this[Reports.targetType],
            targetId = this[Reports.targetId],
            chatId = this[Reports.chatId],
            messageId = this[Reports.messageId],
            reason = this[Reports.reason],
            description = this[Reports.description],
            status = this[Reports.status],
            createdAt = this[Reports.createdAt],
            reviewerId = this[Reports.reviewerId],
            resolutionNote = this[Reports.resolutionNote],
            actionTaken = this[Reports.actionTaken],
            actionAt = this[Reports.actionAt],
            resolvedAt = this[Reports.resolvedAt]
        )
    }

    private fun isUniqueViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if (current is java.sql.SQLException && current.sqlState == "23505") return true
            if (message.contains("unique") || message.contains("duplicate key")) return true
            current = current.cause
        }
        return false
    }

    private companion object {
        val ALLOWED_TARGET_TYPES = setOf("USER", "MESSAGE", "POST", "COMMENT")
        val ALLOWED_STATUSES = setOf("OPEN", "IN_REVIEW", "RESOLVED", "REJECTED")
        val ALLOWED_ACTIONS = setOf("DELETE_CONTENT", "NO_ACTION", "RESTRICT_MESSAGES_24H", "RESTRICT_POSTS_7D", "SUSPEND_24H")
        val FINAL_STATUSES = setOf("RESOLVED", "REJECTED")
        const val MAX_REASON_LENGTH = 80
        const val MAX_DESCRIPTION_LENGTH = 800
        const val MAX_RESOLUTION_NOTE_LENGTH = 800
    }
}
