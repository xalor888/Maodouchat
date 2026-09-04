package com.maodouchat.server.service

import com.maodouchat.server.repository.UserRepository

/**
 * B13：用户治理写命令门面——单用户封禁/禁动态/禁消息的「校验截止时间 → 模板校验 → 审计明细
 * → 原子写+审计」编排，收敛 AdminUsersRouting 的重复逻辑；批量路由与单用户路由共用同一写语义。
 * 写字段 + 审计仍由 [UserRepository.applyUserSuspension] 等命令在同一事务完成。
 */
class UserDispositionService(private val users: UserRepository) {

    sealed interface Result {
        data class Applied(val reasonCode: String, val until: Long) : Result
        data class Invalid(val message: String) : Result
        object NotFound : Result
    }

    fun suspend(actorId: String, userId: String, until: Long, reasonCode: String?, note: String?): Result {
        val now = System.currentTimeMillis()
        if (until < 0 || until > now + DispositionService.MAX_SUSPEND_MS || (until in 1..now)) {
            return Result.Invalid("封禁截止时间无效")
        }
        val banDays = if (until <= 0L) 0 else
            (((until - now) + 86_399_999L) / 86_400_000L).toInt().coerceIn(1, DispositionService.MAX_BAN_DAYS)
        val ok = when (val d = DispositionService.validateDisposition(banDays, reasonCode, note)) {
            is DispositionService.DispositionValidation.Invalid -> return Result.Invalid(d.message)
            is DispositionService.DispositionValidation.Ok -> d
        }
        val detail = DispositionService.auditDetail(until, ok.reasonCode, ok.note)
        return if (users.applyUserSuspension(actorId, userId, until, detail)) {
            Result.Applied(ok.reasonCode, until)
        } else Result.NotFound
    }

    fun restrictPosts(actorId: String, userId: String, until: Long, reasonCode: String?, note: String?): Result {
        val now = System.currentTimeMillis()
        val maxMs = DispositionService.MAX_POST_RESTRICT_DAYS * 86_400_000L
        if (until < 0 || until > now + maxMs || (until in 1..now)) {
            return Result.Invalid("禁动态截止时间无效")
        }
        val days = if (until <= 0L) 0 else
            (((until - now) + 86_399_999L) / 86_400_000L).toInt().coerceIn(1, DispositionService.MAX_POST_RESTRICT_DAYS)
        val ok = when (val d = DispositionService.validatePostRestrict(days, reasonCode, note)) {
            is DispositionService.DispositionValidation.Invalid -> return Result.Invalid(d.message)
            is DispositionService.DispositionValidation.Ok -> d
        }
        val detail = DispositionService.auditPostRestrictDetail(until, ok.reasonCode, ok.note)
        return if (users.applyUserPostRestriction(actorId, userId, until, detail)) {
            Result.Applied(ok.reasonCode, until)
        } else Result.NotFound
    }

    fun restrictMessages(actorId: String, userId: String, until: Long, reasonCode: String?, note: String?): Result {
        val now = System.currentTimeMillis()
        val maxMs = DispositionService.MAX_MESSAGE_RESTRICT_DAYS * 86_400_000L
        if (until < 0 || until > now + maxMs || (until in 1..now)) {
            return Result.Invalid("禁消息截止时间无效")
        }
        val days = if (until <= 0L) 0 else
            (((until - now) + 86_399_999L) / 86_400_000L).toInt().coerceIn(1, DispositionService.MAX_MESSAGE_RESTRICT_DAYS)
        val ok = when (val d = DispositionService.validateMessageRestrict(days, reasonCode, note)) {
            is DispositionService.DispositionValidation.Invalid -> return Result.Invalid(d.message)
            is DispositionService.DispositionValidation.Ok -> d
        }
        val detail = DispositionService.auditMessageRestrictDetail(until, ok.reasonCode, ok.note)
        return if (users.applyUserMessageRestriction(actorId, userId, until, detail)) {
            Result.Applied(ok.reasonCode, until)
        } else Result.NotFound
    }

    // ─── B13：批量处置（收敛 AdminBulkRouting 的重复「skip+update+audit」）───

    fun bulkExtend(
        actorId: String,
        ids: List<String>,
        field: UserRepository.UserDispositionField,
        until: Long,
        action: String,
        detailFor: (Long) -> String,
    ): UserRepository.BulkDispositionResult =
        users.applyBulkDisposition(actorId, ids, field, UserRepository.UserDispositionMode.EXTEND, until, action, detailFor)

    fun bulkSet(
        actorId: String,
        ids: List<String>,
        field: UserRepository.UserDispositionField,
        until: Long,
        action: String,
        detailFor: (Long) -> String,
    ): UserRepository.BulkDispositionResult =
        users.applyBulkDisposition(actorId, ids, field, UserRepository.UserDispositionMode.SET, until, action, detailFor)

    fun bulkClear(
        actorId: String,
        ids: List<String>,
        field: UserRepository.UserDispositionField,
        action: String,
        detail: String,
    ): UserRepository.BulkDispositionResult =
        users.applyBulkDisposition(actorId, ids, field, UserRepository.UserDispositionMode.CLEAR, 0L, action, { detail })
}
