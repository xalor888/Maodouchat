package com.maodouchat.server.plugins

import com.maodouchat.server.config.AdminAccess
import com.maodouchat.server.db.ModerationAuditLog
import com.maodouchat.server.db.Posts
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.PostAdminResponse
import com.maodouchat.server.model.UserAdminResponse
import com.maodouchat.server.service.DispositionService
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 管理后台共享支撑：DTO、鉴权/审计辅助、限流器、CSV 导出与 SQL 表达式等纯基础设施。
 * 与「路由」解耦，供 AdminManagementRouting 及各 admin 子域路由模块复用（单一真相源）。
 */

/** 独立管理后台只允许 MASTER_ADMINS；内容审核员继续使用受限审核 API。 */
internal suspend fun ApplicationCall.isAdminUser(): Boolean {
    val principal = principal<JWTPrincipal>() ?: return false
    val userId = principal.payload.subject
    return AdminAccess.isAdmin(userId)
}

internal suspend fun bestEffortAdminDisconnect(block: suspend () -> Unit) {
    try {
        block()
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (_: Exception) {
    }
}

internal fun recordAdminAudit(actorId: String, action: String, detail: String) {
    transaction {
        ModerationAuditLog.insert {
            it[ModerationAuditLog.actorId] = actorId
            it[ModerationAuditLog.action] = action.take(40)
            it[ModerationAuditLog.detail] = detail.take(500)
            it[ModerationAuditLog.createdAt] = System.currentTimeMillis()
        }
    }
}

@Serializable
data class WatermarkExtractResponse(
    val found: Boolean,
    val payloadHex: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val message: String = "",
    val notes: String = ""
)

@Serializable
data class WatermarkSelfTestResponse(
    val samplePngBase64: String,
    val found: Boolean,
    val payloadHex: String = "",
    val message: String = ""
)

@Serializable
data class UpdateUserStatusRequest(
    val bannedUntil: Long? = null,
    val note: String? = null,
    val reasonCode: String? = null
)

@Serializable
data class UpdatePostRestrictionRequest(
    val postRestrictedUntil: Long? = null,
    val note: String? = null,
    val reasonCode: String? = null
)

@Serializable
data class UpdateMessageRestrictionRequest(
    val messageRestrictedUntil: Long? = null,
    val note: String? = null,
    val reasonCode: String? = null
)

@Serializable
data class DispositionReasonDto(
    val code: String,
    val labelZh: String,
    val defaultDays: Int,
    val requiresCustomNote: Boolean = false
)

@Serializable
data class MuteReasonDto(
    val code: String,
    val labelZh: String,
    val durationHours: Int,
    val requiresCustomNote: Boolean = false
)

@Serializable
data class DispositionTemplatesResponse(
    val banReasons: List<DispositionReasonDto>,
    val muteReasons: List<MuteReasonDto> = emptyList(),
    val postRestrictReasons: List<DispositionReasonDto> = emptyList(),
    val messageRestrictReasons: List<DispositionReasonDto> = emptyList(),
    val unbanReasonCode: String,
    val unmuteReasonCode: String = "unmute",
    val unrestrictPostsReasonCode: String = "unrestrict_posts",
    val unrestrictMessagesReasonCode: String = "unrestrict_messages",
    val appealNoticeZh: String,
    val maxBanDays: Int,
    val maxPostRestrictDays: Int = DispositionService.MAX_POST_RESTRICT_DAYS,
    val maxMessageRestrictDays: Int = DispositionService.MAX_MESSAGE_RESTRICT_DAYS
)

internal const val MAX_ADMIN_SUSPEND_MS = 10L * 365L * 24L * 60L * 60L * 1_000L
internal const val MAX_ADMIN_JSON_BODY_CHARS = 80 * 1024
internal const val MAX_ADMIN_WATERMARK_BODY_CHARS = 4 * 1024 * 1024
internal const val ADMIN_SESSION_ATTEMPT_WINDOW_SECONDS = 5 * 60
internal const val MAX_ADMIN_SESSION_ATTEMPTS = 5
internal const val MAX_ADMIN_SESSION_ATTEMPT_BUCKETS = 10_000
internal val adminJson = Json { ignoreUnknownKeys = true }
internal val adminSessionAttemptLimiter = AdminSessionAttemptLimiter()
internal val adminAuditLogger = org.slf4j.LoggerFactory.getLogger("AdminAudit")

internal class AdminSessionAttemptLimiter {
    private val delegate = BoundedRateLimiter(
        maxBuckets = MAX_ADMIN_SESSION_ATTEMPT_BUCKETS,
        windowMs = ADMIN_SESSION_ATTEMPT_WINDOW_SECONDS * 1_000L,
    )

    fun acquire(userId: String, now: Long = System.currentTimeMillis()): Boolean =
        delegate.acquire(userId, maxPerMinute = MAX_ADMIN_SESSION_ATTEMPTS, now = now)

    fun reset(userId: String) {
        delegate.reset(userId)
    }
}

internal suspend inline fun <reified T> ApplicationCall.receiveAdminJson(): T? {
    val body = receiveBoundedText(MAX_ADMIN_JSON_BODY_CHARS) ?: return null
    return runCatching { adminJson.decodeFromString<T>(body) }.getOrNull()
}

/** Escape LIKE pattern special characters (%, _, \) so user input is treated literally. */
internal fun escapeLikePattern(input: String): String =
    input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** 解析批量操作的 id 列表（JSON 数组或逗号/空白分隔字符串），去重并截断到 100。 */
internal fun parseAdminIds(obj: JsonObject, key: String = "userIds"): List<String> {
    val raw = obj[key] ?: return emptyList()
    val values = when (raw) {
        is JsonArray -> raw.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
        else -> runCatching { raw.jsonPrimitive.content }
            .getOrNull()
            ?.split(',', ' ', '\n', '\t')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }
    return values.map { it.take(64) }.distinct().take(100)
}

internal fun csvCell(value: Any?): String {
    val raw = value?.toString() ?: ""
    // 公式注入防护须按「去除前导空白后的首字符」判定：Excel 会忽略前导空白/制表符
    // 求值单元格，此前仅查原始首字符，`" =CMD()"` 这类以空格开头的载荷仍会执行。
    val formulaSafe = if (raw.trimStart().firstOrNull() in setOf('=', '+', '-', '@')) "'$raw" else raw
    return "\"${formulaSafe.replace("\"", "\"\"")}\""
}

internal fun ResultRow.toUserAdminResponse(): UserAdminResponse = UserAdminResponse(
    id = this[Users.id],
    name = this[Users.name],
    email = this[Users.email],
    isModerator = this[Users.isModerator],
    lastActiveAt = this[Users.lastSeen],
    suspendedUntil = this[Users.suspendedUntil],
    postRestrictedUntil = this[Users.postRestrictedUntil],
    messageRestrictedUntil = this[Users.messageRestrictedUntil],
    deletedAt = this[Users.deletedAt]
)

internal fun ResultRow.toPostAdminResponse(authorName: String): PostAdminResponse = PostAdminResponse(
    id = this[Posts.id],
    authorId = this[Posts.authorId],
    authorName = authorName,
    content = this[Posts.content],
    status = this[Posts.status],
    createdAt = this[Posts.createdAt]
)

@kotlinx.serialization.Serializable
data class OpsSnapshotResponse(
    val users: Long,
    val messages: Long,
    val botsTotal: Long,
    val botsEnabled: Long,
    val botsWithWebhook: Long,
    val pollsTotal: Long,
    val pollsOpen: Long,
    val pollVotes: Long,
    val generatedAt: Long
)
