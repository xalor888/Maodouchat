package com.maodouchat.server.service

import com.maodouchat.server.repository.purgeAdminOperationalData
import com.maodouchat.server.repository.AiRepository
import com.maodouchat.server.repository.AuthTokenRepository
import com.maodouchat.server.repository.BotRepository
import com.maodouchat.server.repository.FriendRepository
import com.maodouchat.server.repository.GroupAuditRepository
import com.maodouchat.server.repository.GroupCheckinRepository
import com.maodouchat.server.repository.PostRepository
import com.maodouchat.server.repository.ReportWorkflow
import com.maodouchat.server.repository.SignalKeyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * 后台周期维护运行时（B01：循环本体彻底出 `Routing.kt`）。
 *
 * 两个循环：6h 清理组（11 个幂等清理任务）+ 15min 会话过期组。
 * 每个任务按名抢 `JobLease`（双实例互斥），执行经 `runTracked`
 * 接入 `BackgroundTaskHealth`（readiness 可见）。
 */
class MaintenanceRunner(
    private val orphanGcJob: OrphanGcJob,
    private val aiRepo: AiRepository,
    private val friendRepo: FriendRepository,
    private val groupAuditRepo: GroupAuditRepository,
    private val signalKeyRepo: SignalKeyRepository,
    private val postRepo: PostRepository,
    private val reportRepo: ReportWorkflow,
    private val authTokenRepo: AuthTokenRepository,
    private val jobLease: JobLease = JobLease(),
) {
    private val log = LoggerFactory.getLogger(MaintenanceRunner::class.java)

    fun start(scope: CoroutineScope): List<Job> = listOf(
        scope.launch {
            while (isActive) {
                // B14：周期任务经 BackgroundTaskHealth 追踪成功/连续失败，供 readiness 覆盖。
                runLeased("orphanGc", SIX_HOURS_PLUS, "Orphan blob/media GC failed") { orphanGcJob.run() }
                runLeased("aiAuditPurge", SIX_HOURS_PLUS, "AI audit log purge failed") { aiRepo.purgeOldAuditLogs() }
                runLeased("adminOpsPurge", SIX_HOURS_PLUS, "Admin operational data purge failed") { purgeAdminOperationalData() }
                runLeased("friendExpiry", SIX_HOURS_PLUS, "Stale friend request expiry failed") { friendRepo.expireStalePending() }
                runLeased("groupPlayPurge", SIX_HOURS_PLUS, "Group play data purge failed") { GroupCheckinRepository.purgeOldData() }
                runLeased("groupAuditPurge", SIX_HOURS_PLUS, "Group audit log purge failed") { groupAuditRepo.purgeOlderThan() }
                runLeased("botCommandLogPurge", SIX_HOURS_PLUS, "Bot command log purge failed") { BotRepository.purgeOldCommandLogs() }
                runLeased("botInboxPurge", SIX_HOURS_PLUS, "Bot inbox purge failed") { BotRepository.purgeOldInbox() }
                runLeased("prekeyPurge", SIX_HOURS_PLUS, "Consumed prekey purge failed") { signalKeyRepo.purgeConsumedPreKeys() }
                // 1.81：清理已删除评论的残留点赞
                runLeased("orphanCommentLikePurge", SIX_HOURS_PLUS, "Orphaned comment like purge failed") { postRepo.purgeOrphanedCommentLikes() }
                runLeased("reportPurge", SIX_HOURS_PLUS, "Resolved report purge failed") { reportRepo.purgeResolvedOlderThan() }
                delay(SIX_HOURS_MS)
            }
        },
        scope.launch {
            while (isActive) {
                runLeased("authSessionExpiry", 30L * 60L * 1_000L, "Expired authentication session cleanup failed") {
                    authTokenRepo.deleteExpired()
                }
                delay(15L * 60L * 1_000L)
            }
        },
    )

    // B01：双实例下同一任务同时只跑一份。抢不到租约直接跳过本轮
    //（幂等清理任务漏一轮无害）；跑完即释放，崩溃残留由 TTL 兜底。
    // TTL 略大于循环间隔：崩溃最多跳过约一轮。
    private suspend fun runLeased(name: String, ttlMs: Long, logMessage: String, block: suspend () -> Unit) {
        if (!jobLease.tryAcquire(name, ttlMs)) {
            log.debug("Skipping {}: lease held by another instance", name)
            return
        }
        try {
            runTracked(name, logMessage, block)
        } finally {
            jobLease.release(name)
        }
    }

    private companion object {
        const val SIX_HOURS_MS = 6L * 60L * 60L * 1_000L
        const val SIX_HOURS_PLUS = 7L * 60L * 60L * 1_000L
    }
}
