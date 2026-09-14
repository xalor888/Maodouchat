package com.maodouchat.server.plugins

import com.maodouchat.server.db.AnnouncementAcks
import com.maodouchat.server.db.AuditExportRecords
import com.maodouchat.server.db.DeviceEventConsistencyLog
import com.maodouchat.server.db.DeviceEventSequences
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.RateLimitStatsSnapshots
import com.maodouchat.server.db.RiskEvents
import com.maodouchat.server.db.SystemAnnouncements
import com.maodouchat.server.db.UserTagAssignments
import com.maodouchat.server.db.UserTags
import com.maodouchat.server.model.ActiveAnnouncementsResponse
import com.maodouchat.server.model.AnnouncementAckResponse
import com.maodouchat.server.model.AnnouncementDto
import com.maodouchat.server.model.AnnouncementStatsResponse
import com.maodouchat.server.model.CreateAnnouncementRequest
import com.maodouchat.server.model.ErrorResponse
import com.maodouchat.server.model.UpdateAnnouncementRequest
import com.maodouchat.server.repository.AnnouncementRepository
import com.maodouchat.server.repository.RateLimitStatsRepository
import com.maodouchat.server.repository.UserTagRepository
import com.maodouchat.server.service.csvCell
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * B6 服务端运维增强路由。
 *
 * 安全约束（红线）：
 * - 所有 `/api/admin/` 端点双重门控：`authenticate("admin-jwt")` + `isAdminUser()`（MASTER_ADMINS）。
 * - 不导出 E2EE 明文：本模块只读写公告（平台明文广播）、用户标签、审计元数据、限流统计、设备一致性序列，
 *   绝不触碰 v2 envelopes / EncryptedAttachments 的密文列。
 * - 所有变更操作写 ModerationAuditLog 审计。
 *
 * 本文件只注册 AdminRouting.kt 中不存在的全新路径，不修改其已有路由。
 */
fun Application.configureAdminEnhanceRouting(
    announcementRepo: AnnouncementRepository,
    userTagRepo: UserTagRepository,
    rateLimitStatsRepo: RateLimitStatsRepository,
    fcmPushService: com.maodouchat.server.service.FcmPushService? = null,
    pushTokenRepo: com.maodouchat.server.repository.PushTokenRepository? = null
) {
    configureUserTagRoutes(userTagRepo)

    configureAnnouncementRoutes(announcementRepo, userTagRepo, fcmPushService, pushTokenRepo)

    routing {



        // ─── 管理端增强（双重门控）────────────────
        authenticate("admin-jwt") {
            route("/api/admin") {

                // ═══ 审计时间范围导出 ═══
                get("/audit/time-range-export") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.requireUserId()
                    val scope = call.request.queryParameters["scope"]?.trim()?.uppercase()?.take(30)
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少导出范围 scope"))
                    if (scope !in setOf("ADMIN_AUDIT", "RISK_EVENTS", "ANNOUNCEMENTS", "USER_TAGS", "RATE_LIMIT")) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("导出范围非法"))
                    }
                    val fromMs = call.request.queryParameters["fromMs"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少 fromMs"))
                    val toMs = call.request.queryParameters["toMs"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("缺少 toMs"))
                    if (fromMs >= toMs) return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("时间范围非法"))
                    if (toMs - fromMs > MAX_EXPORT_RANGE_MS) {
                        return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("导出时间范围不得超过 90 天"))
                    }
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 5_000).coerceIn(1, 10_000)
                    val (csv, exportedRows) = buildAuditExportCsv(scope, fromMs, toMs, limit)
                    val fileName = "maodouchat-${scope.lowercase()}-${fromMs}-${toMs}.csv"
                    transaction {
                        AuditExportRecords.insert {
                            it[AuditExportRecords.id] = UUID.randomUUID().toString()
                            it[AuditExportRecords.actorId] = actorId
                            it[AuditExportRecords.scope] = scope
                            it[AuditExportRecords.fromMs] = fromMs
                            it[AuditExportRecords.toMs] = toMs
                            // 9.140：此前恒记 0——审计追溯记录行数与实际导出内容不符
                            it[AuditExportRecords.rowCount] = exportedRows.toLong()
                            // fileRef 记下载文件名（CSV 流式返回不落盘，保留作为导出标识）
                            it[AuditExportRecords.fileRef] = fileName
                            it[AuditExportRecords.requestedAt] = System.currentTimeMillis()
                        }
                    }
                    recordAdminAudit(actorId, "ADMIN_AUDIT_TIME_EXPORT", "scope=$scope;from=$fromMs;to=$toMs;limit=$limit")
                    call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$fileName\"")
                    call.respondText(csv, contentType = io.ktor.http.ContentType.parse("text/csv; charset=utf-8"))
                }

                // ═══ 限流仪表盘 ═══
                get("/rate-limit/dashboard") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val range = call.request.queryParameters["range"]?.trim()?.lowercase() ?: "24h"
                    val hours = when (range) {
                        "1h" -> 1
                        "24h" -> 24
                        "7d" -> 24 * 7
                        else -> return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("range 非法：1h / 24h / 7d"))
                    }
                    val now = System.currentTimeMillis()
                    val fromMs = now - hours * 3_600_000L
                    val summary = rateLimitStatsRepo.summarize(fromMs, now)
                    call.respond(
                        RateLimitDashboardResponse(
                            range = range,
                            points = summary.points.map { p ->
                                RateLimitPoint(
                                    bucketStartMs = p.bucketStartMs,
                                    allowed = p.allowedDelta,
                                    rejected = p.rejectedDelta,
                                    totalBuckets = p.avgTotalBuckets,
                                    maxBuckets = p.maxTotalBuckets,
                                    maxPerMinute = p.maxPerMinute
                                )
                            },
                            totalAllowed = summary.totalAllowed,
                            totalRejected = summary.totalRejected,
                            peakRejectionsPerMinute = summary.peakRejectionsPerMinute,
                            live = summary.live.let {
                                RateLimitLiveStats(
                                    allowed = it.allowed, rejected = it.rejected,
                                    totalBuckets = it.totalBuckets, maxBuckets = it.maxBuckets,
                                    maxPerMinute = it.maxPerMinute
                                )
                            },
                            lastSnapshotAt = rateLimitStatsRepo.lastSnapshotAt() ?: 0L,
                            retentionDays = rateLimitStatsRepo.retentionDays
                        )
                    )
                }

                post("/rate-limit/sample") {
                    if (!call.isAdminUser()) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val actorId = call.requireUserId()
                    rateLimitStatsRepo.recordMinute()
                    recordAdminAudit(actorId, "RATE_LIMIT_MANUAL_SAMPLE", "")
                    call.respond(buildJsonObject { put("ok", true) })
                }

                // ═══ 设备事件一致性 ═══
                get("/device-consistency/summary") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val userId = call.request.queryParameters["userId"]?.trim()?.takeIf { it.isNotBlank() }
                    val sequences = transaction {
                        val q = DeviceEventSequences.selectAll()
                        (if (userId != null) q.andWhere { DeviceEventSequences.userId eq userId } else q)
                            .orderBy(DeviceEventSequences.userId to SortOrder.ASC)
                            .map { row ->
                                DeviceSeqResponse(
                                    userId = row[DeviceEventSequences.userId],
                                    deviceId = row[DeviceEventSequences.deviceId],
                                    eventType = row[DeviceEventSequences.eventType],
                                    lastAppliedSeq = row[DeviceEventSequences.lastAppliedSeq],
                                    lastEventAt = row[DeviceEventSequences.lastEventAt]
                                )
                            }
                    }
                    val anomalyCount = transaction {
                        val q = DeviceEventConsistencyLog.selectAll()
                        (if (userId != null) q.andWhere { DeviceEventConsistencyLog.userId eq userId } else q).count()
                    }
                    call.respond(DeviceConsistencySummaryResponse(sequences = sequences, anomalyCount = anomalyCount))
                }

                get("/device-consistency/events") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                    val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                    val status = call.request.queryParameters["status"]?.trim()?.uppercase()?.take(20)
                    val userId = call.request.queryParameters["userId"]?.trim()?.takeIf { it.isNotBlank() }
                    val events = transaction {
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
                            DeviceEventConsistencyLog.id to SortOrder.DESC
                        )
                            .limit(limit, offset)
                            .map { row ->
                                DeviceAnomalyResponse(
                                    id = row[DeviceEventConsistencyLog.id],
                                    userId = row[DeviceEventConsistencyLog.userId],
                                    deviceId = row[DeviceEventConsistencyLog.deviceId],
                                    eventType = row[DeviceEventConsistencyLog.eventType],
                                    seq = row[DeviceEventConsistencyLog.seq],
                                    status = row[DeviceEventConsistencyLog.status],
                                    referenceId = row[DeviceEventConsistencyLog.referenceId],
                                    firstSeenAt = row[DeviceEventConsistencyLog.firstSeenAt],
                                    lastSeenAt = row[DeviceEventConsistencyLog.lastSeenAt],
                                    detail = row[DeviceEventConsistencyLog.detail]
                                )
                            }
                    }
                    call.respond(events)
                }
            }
        }
    }
}

/**
 * 限流统计采样器：每 60s 把 GlobalRateLimiter 的累计计数器写入分钟桶，
 * 同时清理超过保留期的旧桶。守护线程，不阻塞 JVM 退出。
 * 单实例部署语义与 GlobalRateLimiter 一致。
 * 返回执行器供 ApplicationStopped 关闭（8.31 运维修复：退出瞬间不再执行 DB 写）。
 */
fun startRateLimitStatsSampler(rateLimitStatsRepo: RateLimitStatsRepository): ScheduledThreadPoolExecutor {
    val exec = ScheduledThreadPoolExecutor(1, RateLimitSamplerThreadFactory)
    exec.scheduleWithFixedDelay(
        {
            runCatching {
                rateLimitStatsRepo.recordMinute()
                val cutoff = System.currentTimeMillis() - rateLimitStatsRepo.retentionDays * 86_400_000L
                rateLimitStatsRepo.prune(cutoff)
            }.onFailure { e -> samplerLogger.warn("Rate-limit stats sampling failed", e) }
        },
        SAMPLE_DELAY_MS, SAMPLE_DELAY_MS, TimeUnit.MILLISECONDS
    )
    return exec
}

// ─────────────────────────────────────────────
// DTO
// ─────────────────────────────────────────────



@Serializable
data class RateLimitPoint(
    val bucketStartMs: Long,
    val allowed: Long,
    val rejected: Long,
    val totalBuckets: Long,
    val maxBuckets: Long,
    val maxPerMinute: Int
)

@Serializable
data class RateLimitLiveStats(
    val allowed: Long,
    val rejected: Long,
    val totalBuckets: Int,
    val maxBuckets: Int,
    val maxPerMinute: Int
)

@Serializable
data class RateLimitDashboardResponse(
    val range: String,
    val points: List<RateLimitPoint>,
    val totalAllowed: Long,
    val totalRejected: Long,
    val peakRejectionsPerMinute: Long,
    val live: RateLimitLiveStats,
    val lastSnapshotAt: Long,
    val retentionDays: Int
)

@Serializable
data class DeviceSeqResponse(
    val userId: String,
    val deviceId: Int,
    val eventType: String,
    val lastAppliedSeq: Long,
    val lastEventAt: Long
)

@Serializable
data class DeviceConsistencySummaryResponse(
    val sequences: List<DeviceSeqResponse>,
    val anomalyCount: Long
)

@Serializable
data class DeviceAnomalyResponse(
    val id: String,
    val userId: String,
    val deviceId: Int,
    val eventType: String,
    val seq: Long,
    val status: String,
    val referenceId: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val detail: String?
)

// ─────────────────────────────────────────────
// 设备事件一致性加固（幂等应用 + 异常记录）
// ─────────────────────────────────────────────

/**
 * 设备事件序列守卫：按 (userId, deviceId, eventType) 维护 lastAppliedSeq，
 * 拒绝 STALE（seq 落后）/ DUPLICATE（重复投递）事件；seq 跳号记为 OUT_OF_ORDER。
 * 单进程内按 key 加条纹锁串行化读-改-写；多实例部署需换 DB 行级锁（与 GlobalRateLimiter 同约束）。
 */
object DeviceEventConsistencyGuard {
    private val stripes = Array(256) { Any() }

    enum class Status { APPLIED, STALE, DUPLICATE, OUT_OF_ORDER }

    data class ApplyOutcome(val status: Status, val lastAppliedSeq: Long)

    private fun stripe(userId: String, deviceId: Int, eventType: String): Any {
        val hash = (userId.hashCode() * 31 + deviceId) * 31 + eventType.hashCode()
        return stripes[Math.floorMod(hash, stripes.size)]
    }

    fun applyEvent(
        userId: String,
        deviceId: Int,
        eventType: String,
        seq: Long,
        referenceId: String? = null,
        now: Long = System.currentTimeMillis()
    ): ApplyOutcome {
        val lock = stripe(userId, deviceId, eventType)
        synchronized(lock) {
            return transaction {
                val existing = DeviceEventSequences.selectAll().where {
                    (DeviceEventSequences.userId eq userId) and
                        (DeviceEventSequences.deviceId eq deviceId) and
                        (DeviceEventSequences.eventType eq eventType)
                }.firstOrNull()

                if (existing == null) {
                    DeviceEventSequences.insert {
                        it[DeviceEventSequences.userId] = userId
                        it[DeviceEventSequences.deviceId] = deviceId
                        it[DeviceEventSequences.eventType] = eventType
                        it[DeviceEventSequences.lastAppliedSeq] = seq
                        it[DeviceEventSequences.lastEventAt] = now
                    }
                    return@transaction ApplyOutcome(Status.APPLIED, seq)
                }

                val lastApplied = existing[DeviceEventSequences.lastAppliedSeq]
                when {
                    seq < lastApplied -> {
                        recordAnomaly(userId, deviceId, eventType, seq, "STALE", referenceId, now, "last=$lastApplied")
                        ApplyOutcome(Status.STALE, lastApplied)
                    }
                    seq == lastApplied -> {
                        recordAnomaly(userId, deviceId, eventType, seq, "DUPLICATE", referenceId, now, "already=$lastApplied")
                        ApplyOutcome(Status.DUPLICATE, lastApplied)
                    }
                    seq > lastApplied + 1 -> {
                        recordAnomaly(userId, deviceId, eventType, seq, "OUT_OF_ORDER", referenceId, now, "expected=${lastApplied + 1}")
                        ApplyOutcome(Status.OUT_OF_ORDER, lastApplied)
                    }
                    else -> {
                        DeviceEventSequences.update({
                            (DeviceEventSequences.userId eq userId) and
                                (DeviceEventSequences.deviceId eq deviceId) and
                                (DeviceEventSequences.eventType eq eventType)
                        }) {
                            it[DeviceEventSequences.lastAppliedSeq] = seq
                            it[DeviceEventSequences.lastEventAt] = now
                        }
                        ApplyOutcome(Status.APPLIED, seq)
                    }
                }
            }
        }
    }

    /** 异常汇总：按事件类型 + 状态统计（供仪表盘）。 */
    fun anomalySummary(userId: String? = null): Map<String, Long> = transaction {
        val q = DeviceEventConsistencyLog.selectAll()
        val rows = if (userId != null) q.andWhere { DeviceEventConsistencyLog.userId eq userId } else q
        rows.groupBy { it[DeviceEventConsistencyLog.status] }.mapValues { (_, v) -> v.size.toLong() }
    }

    private fun recordAnomaly(
        userId: String,
        deviceId: Int,
        eventType: String,
        seq: Long,
        status: String,
        referenceId: String?,
        now: Long,
        detail: String
    ) {
        val id = "${userId.take(32)}|$deviceId|${eventType.take(16)}|$status"
        val existing = DeviceEventConsistencyLog.selectAll().where { DeviceEventConsistencyLog.id eq id }.firstOrNull()
        if (existing == null) {
            DeviceEventConsistencyLog.insert {
                it[DeviceEventConsistencyLog.id] = id
                it[DeviceEventConsistencyLog.userId] = userId
                it[DeviceEventConsistencyLog.deviceId] = deviceId
                it[DeviceEventConsistencyLog.eventType] = eventType
                it[DeviceEventConsistencyLog.seq] = seq
                it[DeviceEventConsistencyLog.status] = status
                it[DeviceEventConsistencyLog.referenceId] = referenceId
                it[DeviceEventConsistencyLog.firstSeenAt] = now
                it[DeviceEventConsistencyLog.lastSeenAt] = now
                it[DeviceEventConsistencyLog.detail] = detail.take(300)
            }
        } else {
            DeviceEventConsistencyLog.update({ DeviceEventConsistencyLog.id eq id }) {
                it[DeviceEventConsistencyLog.seq] = seq
                it[DeviceEventConsistencyLog.referenceId] = referenceId
                it[DeviceEventConsistencyLog.lastSeenAt] = now
                it[DeviceEventConsistencyLog.detail] = detail.take(300)
            }
        }
    }
}

// ─────────────────────────────────────────────
// 内部辅助
// ─────────────────────────────────────────────
// isAdminUser / recordAdminAudit / csvCell 已统一到 AdminSupport.kt（内部共享版本）。

/** 时间范围导出：仅导出元数据/平台明文公告，绝不导出 E2EE 消息密文。返回 CSV 与实际行数。 */
private fun buildAuditExportCsv(scope: String, fromMs: Long, toMs: Long, limit: Int): Pair<String, Int> {
    val rows = when (scope) {
        "ADMIN_AUDIT" -> transaction {
            ModerationAuditLog.selectAll().where {
                (ModerationAuditLog.createdAt greaterEq fromMs) and (ModerationAuditLog.createdAt less toMs)
            }.orderBy(ModerationAuditLog.createdAt to SortOrder.ASC)
                .limit(limit)
                .map { row ->
                    listOf(
                        row[ModerationAuditLog.id], row[ModerationAuditLog.actorId], row[ModerationAuditLog.userId],
                        row[ModerationAuditLog.action], row[ModerationAuditLog.detail], row[ModerationAuditLog.createdAt]
                    )
                }
        }
        "RISK_EVENTS" -> transaction {
            RiskEvents.selectAll().where {
                (RiskEvents.createdAt greaterEq fromMs) and (RiskEvents.createdAt less toMs)
            }.orderBy(RiskEvents.createdAt to SortOrder.ASC)
                .limit(limit)
                .map { row ->
                    listOf(
                        row[RiskEvents.id], row[RiskEvents.userId], row[RiskEvents.sourceValue],
                        row[RiskEvents.ruleId], row[RiskEvents.action], row[RiskEvents.matched],
                        row[RiskEvents.referenceId], row[RiskEvents.needsReview], row[RiskEvents.createdAt]
                    )
                }
        }
        "ANNOUNCEMENTS" -> transaction {
            SystemAnnouncements.selectAll().where {
                (SystemAnnouncements.createdAt greaterEq fromMs) and (SystemAnnouncements.createdAt less toMs)
            }.orderBy(SystemAnnouncements.createdAt to SortOrder.ASC)
                .limit(limit)
                .map { row ->
                    listOf(
                        row[SystemAnnouncements.id], row[SystemAnnouncements.title], row[SystemAnnouncements.content],
                        row[SystemAnnouncements.level], row[SystemAnnouncements.targetAudience],
                        row[SystemAnnouncements.targetTagId], row[SystemAnnouncements.startsAt],
                        row[SystemAnnouncements.expiresAt], row[SystemAnnouncements.status],
                        row[SystemAnnouncements.createdBy], row[SystemAnnouncements.createdAt],
                        row[SystemAnnouncements.publishedAt], row[SystemAnnouncements.cancelledAt]
                    )
                }
        }
        "USER_TAGS" -> transaction {
            val rows = UserTagAssignments.selectAll().where {
                (UserTagAssignments.createdAt greaterEq fromMs) and (UserTagAssignments.createdAt less toMs)
            }.orderBy(UserTagAssignments.createdAt to SortOrder.ASC)
                .limit(limit)
                .toList()
            // 8.48 修复 M11：批量回查标签名（此前逐赋值查询 → N+1）
            val tagIds = rows.map { it[UserTagAssignments.tagId] }.distinct()
            val tagNameById = if (tagIds.isEmpty()) emptyMap() else
                UserTags.selectAll().where { UserTags.id inList tagIds }
                    .associate { it[UserTags.id] to it[UserTags.name] }
            rows.map { row ->
                    val tagName = tagNameById[row[UserTagAssignments.tagId]] ?: ""
                    listOf(
                        row[UserTagAssignments.tagId], tagName, row[UserTagAssignments.userId],
                        row[UserTagAssignments.assignmentSource], row[UserTagAssignments.assignedBy],
                        row[UserTagAssignments.createdAt]
                    )
                }
        }
        "RATE_LIMIT" -> transaction {
            RateLimitStatsSnapshots.selectAll().where {
                (RateLimitStatsSnapshots.bucketStartMs greaterEq fromMs) and
                    (RateLimitStatsSnapshots.bucketStartMs less toMs)
            }.orderBy(RateLimitStatsSnapshots.bucketStartMs to SortOrder.ASC)
                .limit(limit)
                .map { row ->
                    listOf(
                        row[RateLimitStatsSnapshots.bucketStartMs], row[RateLimitStatsSnapshots.allowed],
                        row[RateLimitStatsSnapshots.rejected], row[RateLimitStatsSnapshots.totalBuckets],
                        row[RateLimitStatsSnapshots.maxBuckets], row[RateLimitStatsSnapshots.maxPerMinute],
                        row[RateLimitStatsSnapshots.sampledAt]
                    )
                }
        }
        else -> emptyList()
    }

    val header = when (scope) {
        "ADMIN_AUDIT" -> "id,actorId,targetUserId,action,detail,createdAt"
        "RISK_EVENTS" -> "id,userId,source,ruleId,action,matched,referenceId,needsReview,createdAt"
        "ANNOUNCEMENTS" -> "id,title,content,level,audience,tagId,startsAt,expiresAt,status,createdBy,createdAt,publishedAt,cancelledAt"
        "USER_TAGS" -> "tagId,tagName,userId,source,assignedBy,createdAt"
        "RATE_LIMIT" -> "bucketStartMs,allowed,rejected,totalBuckets,maxBuckets,maxPerMinute,sampledAt"
        else -> ""
    }
    val body = rows.joinToString("\r\n") { row -> row.joinToString(",") { cell -> csvCell(cell) } }
    // 9.140：连同实际行数返回，供审计导出记录写入真实 rowCount
    return "\uFEFF$header\r\n$body\r\n" to rows.size
}



private object RateLimitSamplerThreadFactory : ThreadFactory {
    override fun newThread(r: Runnable): Thread =
        Thread(r, "rate-limit-stats-sampler").apply { isDaemon = true }
}

private val samplerLogger = LoggerFactory.getLogger("RateLimitStatsSampler")

private const val MAX_EXPORT_RANGE_MS = 90L * 24L * 60L * 60L * 1_000L
private const val SAMPLE_DELAY_MS = 60_000L

/**
 * 清理 B6 运维数据中的过期记录（由 Routing.kt 的 6 小时周期循环调用），
 * 防止以下记录表无限增长：
 * - AnnouncementAcks 公告已读确认：ackedAt 超过 90 天删除
 * - DeviceEventConsistencyLog 设备一致性异常日志：lastSeenAt 超过 30 天删除
 * - AuditExportRecords 审计导出登记：requestedAt 超过 180 天删除
 * - ModerationAuditLog 管理操作审计：createdAt 超过 365 天删除
 *
 * 返回每个表本次删除的行数（仅供日志观测）。
 */
fun purgeAdminOperationalData(): Map<String, Int> {
    val now = System.currentTimeMillis()
    val day = 86_400_000L
    return transaction {
        mapOf(
            "announcementAcks" to AnnouncementAcks.deleteWhere {
                AnnouncementAcks.ackedAt less now - 90 * day
            },
            "deviceEventLogs" to DeviceEventConsistencyLog.deleteWhere {
                DeviceEventConsistencyLog.lastSeenAt less now - 30 * day
            },
            "auditExportRecords" to AuditExportRecords.deleteWhere {
                AuditExportRecords.requestedAt less now - 180 * day
            },
            "moderationAuditLogs" to ModerationAuditLog.deleteWhere {
                ModerationAuditLog.createdAt less now - 365 * day
            }
        )
    }
}
