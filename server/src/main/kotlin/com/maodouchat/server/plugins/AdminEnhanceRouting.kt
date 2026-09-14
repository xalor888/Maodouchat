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
import com.maodouchat.server.repository.AdminExportRepository
import com.maodouchat.server.service.AdminExportService
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
    pushTokenRepo: com.maodouchat.server.repository.PushTokenRepository? = null,
    exportRepository: AdminExportRepository = AdminExportRepository(),
    exportService: AdminExportService = AdminExportService(exportRepository),
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
                    val export = exportService.auditExportCsv(scope, fromMs, toMs, limit)
                    val fileName = "maodouchat-${scope.lowercase()}-${fromMs}-${toMs}.csv"
                    exportRepository.recordAuditExport(
                        actorId = actorId,
                        scope = scope,
                        fromMs = fromMs,
                        toMs = toMs,
                        // 9.140：此前恒记 0——审计追溯记录行数与实际导出内容不符
                        rowCount = export.rowCount.toLong(),
                        // fileRef 记下载文件名（CSV 流式返回不落盘，保留作为导出标识）
                        fileRef = fileName,
                    )
                    recordAdminAudit(actorId, "ADMIN_AUDIT_TIME_EXPORT", "scope=$scope;from=$fromMs;to=$toMs;limit=$limit")
                    call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$fileName\"")
                    call.respondText(export.body, contentType = io.ktor.http.ContentType.parse("text/csv; charset=utf-8"))
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
                    val sequences = exportRepository.deviceSequences(userId).map { row ->
                        DeviceSeqResponse(
                            userId = row.userId,
                            deviceId = row.deviceId,
                            eventType = row.eventType,
                            lastAppliedSeq = row.lastAppliedSeq,
                            lastEventAt = row.lastEventAt,
                        )
                    }
                    val anomalyCount = exportRepository.deviceAnomalyCount(userId)
                    call.respond(DeviceConsistencySummaryResponse(sequences = sequences, anomalyCount = anomalyCount))
                }

                get("/device-consistency/events") {
                    if (!call.isAdminUser()) return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("需要管理员权限"))
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                    val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                    val status = call.request.queryParameters["status"]?.trim()?.uppercase()?.take(20)
                    val userId = call.request.queryParameters["userId"]?.trim()?.takeIf { it.isNotBlank() }
                    val events = exportRepository.deviceAnomalies(userId, status, limit, offset).map { row ->
                        DeviceAnomalyResponse(
                            id = row.id,
                            userId = row.userId,
                            deviceId = row.deviceId,
                            eventType = row.eventType,
                            seq = row.seq,
                            status = row.status,
                            referenceId = row.referenceId,
                            firstSeenAt = row.firstSeenAt,
                            lastSeenAt = row.lastSeenAt,
                            detail = row.detail,
                        )
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


// ─────────────────────────────────────────────
// 内部辅助
// ─────────────────────────────────────────────
// isAdminUser / recordAdminAudit / csvCell 已统一到 AdminSupport.kt（内部共享版本）。




private object RateLimitSamplerThreadFactory : ThreadFactory {
    override fun newThread(r: Runnable): Thread =
        Thread(r, "rate-limit-stats-sampler").apply { isDaemon = true }
}

private val samplerLogger = LoggerFactory.getLogger("RateLimitStatsSampler")

private const val MAX_EXPORT_RANGE_MS = 90L * 24L * 60L * 60L * 1_000L
private const val SAMPLE_DELAY_MS = 60_000L

