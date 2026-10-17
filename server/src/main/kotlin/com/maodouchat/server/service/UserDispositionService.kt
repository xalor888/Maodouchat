package com.maodouchat.server.service

import com.maodouchat.server.repository.BulkDispositionResult
import com.maodouchat.server.repository.UserDispositionField
import com.maodouchat.server.repository.UserDispositionMode
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.repository.UserSettingsField
import com.maodouchat.server.repository.applyBulkDisableTotp
import com.maodouchat.server.repository.applyBulkDisposition
import com.maodouchat.server.repository.applyBulkUserSettings
import com.maodouchat.server.repository.applyUserMessageRestriction
import com.maodouchat.server.repository.applyUserPostRestriction
import com.maodouchat.server.repository.applyUserSuspension

/**
 * B13：用户治理写命令门面——单用户封禁/禁动态/禁消息的「校验截止时间 → 模板校验 → 审计明细
 * → 原子写+审计」编排，收敛 AdminUsersRouting 的重复逻辑；批量路由与单用户路由共用同一写语义。
 * 写字段 + 审计仍由 [UserRepository.applyUserSuspension] 等命令在同一事务完成。
 */
class UserDispositionService(private val users: UserRepository) {

    fun suspend(actorId: String, userId: String, until: Long, reasonCode: String?, note: String?): UserDispositionResult {
        val now = System.currentTimeMillis()
        if (until < 0 || until > now + DispositionService.MAX_SUSPEND_MS || (until in 1..now)) {
            return Invalid("封禁截止时间无效")
        }
        val banDays = if (until <= 0L) 0 else
            (((until - now) + 86_399_999L) / 86_400_000L).toInt().coerceIn(1, DispositionService.MAX_BAN_DAYS)
        val ok = when (val d = DispositionService.validateDisposition(banDays, reasonCode, note)) {
            is DispositionValidation.Invalid -> return Invalid(d.message)
            is DispositionValidation.Ok -> d
        }
        val detail = DispositionService.auditDetail(until, ok.reasonCode, ok.note)
        return if (users.applyUserSuspension(actorId, userId, until, detail)) {
            Applied(ok.reasonCode, until)
        } else NotFound
    }

    fun restrictPosts(actorId: String, userId: String, until: Long, reasonCode: String?, note: String?): UserDispositionResult {
        val now = System.currentTimeMillis()
        val maxMs = DispositionService.MAX_POST_RESTRICT_DAYS * 86_400_000L
        if (until < 0 || until > now + maxMs || (until in 1..now)) {
            return Invalid("禁动态截止时间无效")
        }
        val days = if (until <= 0L) 0 else
            (((until - now) + 86_399_999L) / 86_400_000L).toInt().coerceIn(1, DispositionService.MAX_POST_RESTRICT_DAYS)
        val ok = when (val d = DispositionService.validatePostRestrict(days, reasonCode, note)) {
            is DispositionValidation.Invalid -> return Invalid(d.message)
            is DispositionValidation.Ok -> d
        }
        val detail = DispositionService.auditPostRestrictDetail(until, ok.reasonCode, ok.note)
        return if (users.applyUserPostRestriction(actorId, userId, until, detail)) {
            Applied(ok.reasonCode, until)
        } else NotFound
    }

    fun restrictMessages(actorId: String, userId: String, until: Long, reasonCode: String?, note: String?): UserDispositionResult {
        val now = System.currentTimeMillis()
        val maxMs = DispositionService.MAX_MESSAGE_RESTRICT_DAYS * 86_400_000L
        if (until < 0 || until > now + maxMs || (until in 1..now)) {
            return Invalid("禁消息截止时间无效")
        }
        val days = if (until <= 0L) 0 else
            (((until - now) + 86_399_999L) / 86_400_000L).toInt().coerceIn(1, DispositionService.MAX_MESSAGE_RESTRICT_DAYS)
        val ok = when (val d = DispositionService.validateMessageRestrict(days, reasonCode, note)) {
            is DispositionValidation.Invalid -> return Invalid(d.message)
            is DispositionValidation.Ok -> d
        }
        val detail = DispositionService.auditMessageRestrictDetail(until, ok.reasonCode, ok.note)
        return if (users.applyUserMessageRestriction(actorId, userId, until, detail)) {
            Applied(ok.reasonCode, until)
        } else NotFound
    }

    // ─── B13：批量处置（收敛 AdminBulkRouting 的重复「skip+update+audit」）───

    fun bulkExtend(
        actorId: String,
        ids: List<String>,
        field: UserDispositionField,
        until: Long,
        action: String,
        detailFor: (Long) -> String,
    ): BulkDispositionResult =
        users.applyBulkDisposition(actorId, ids, field, UserDispositionMode.EXTEND, until, action, detailFor)

    fun bulkSet(
        actorId: String,
        ids: List<String>,
        field: UserDispositionField,
        until: Long,
        action: String,
        detailFor: (Long) -> String,
    ): BulkDispositionResult =
        users.applyBulkDisposition(actorId, ids, field, UserDispositionMode.SET, until, action, detailFor)

    fun bulkClear(
        actorId: String,
        ids: List<String>,
        field: UserDispositionField,
        action: String,
        detail: String,
    ): BulkDispositionResult =
        users.applyBulkDisposition(actorId, ids, field, UserDispositionMode.CLEAR, 0L, action, { detail })

    fun bulkSetUserSettings(
        actorId: String,
        ids: List<String>,
        field: UserSettingsField,
        value: Boolean,
    ): BulkDispositionResult =
        users.applyBulkUserSettings(actorId, ids, field, value)

    fun bulkDisableTotp(actorId: String, ids: List<String>): BulkDispositionResult =
        users.applyBulkDisableTotp(actorId, ids)
}
