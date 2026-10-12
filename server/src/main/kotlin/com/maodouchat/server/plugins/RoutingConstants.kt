package com.maodouchat.server.plugins

import com.maodouchat.server.config.ServerConfig
import io.ktor.http.HttpStatusCode

/**
 * 共享常量簇（从 RoutingHelpers.kt 按域拆出，零行为改动）。
 */

// ── 常量 ──────────────────────────────

internal const val MAX_JSON_BODY_CHARS = 2_000_000
internal const val MAX_UPLOAD_JSON_BODY_CHARS = 3_000_000
internal const val MAX_GLOBAL_BODY_BYTES = 5L * 1024L * 1024L
internal const val MAX_MUTE_DURATION_MS = 30L * 24 * 60 * 60 * 1000
internal const val MAX_GROUP_MEMBERS_HARD_CAP = 500
/** 广播频道订阅者上限（含创建者）。 */
internal const val MAX_CHANNEL_SUBSCRIBERS = 5_000
/** Effective group size limit (runtime setting, hard-capped). */
internal fun maxGroupMembers(): Int =
    com.maodouchat.server.service.RuntimeConfigService
        .getInt(com.maodouchat.server.service.RuntimeConfigService.KEY_MAX_GROUP_SIZE, 200)
        .coerceIn(2, MAX_GROUP_MEMBERS_HARD_CAP)

internal const val MAX_ATTACHMENT_CIPHER_BYTES = 100L * 1024L * 1024L + 64L
internal const val MAX_ATTACHMENT_CHUNK_BYTES = 4L * 1024L * 1024L
/** Per-user encrypted-attachment quota; env-tunable via USER_STORAGE_QUOTA_BYTES (20 GB default). */
internal val maxAttachmentUserBytes: Long get() = ServerConfig.userStorageQuotaBytes
internal const val ATTACHMENT_UPLOAD_TTL_MS = 24L * 60L * 60L * 1_000L
internal const val MEDIA_ORPHAN_GRACE_MS = 7L * 24L * 60L * 60L * 1_000L
internal const val ATTACHMENT_HASH_HEADER = "X-Content-SHA256"
internal const val ATTACHMENT_CHUNK_HASH_HEADER = "X-Chunk-SHA256"
internal val ATTACHMENT_TOO_LARGE_STATUS = HttpStatusCode(413, "Payload Too Large")
internal val ATTACHMENT_QUOTA_STATUS = HttpStatusCode(507, "Insufficient Storage")
internal val ATTACHMENT_RANGE_NOT_SATISFIABLE = HttpStatusCode(416, "Range Not Satisfiable")

internal val ALLOWED_REACTION_EMOJIS = setOf(
    "\uD83D\uDC4D", "\uD83D\uDC4E", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE0D",
    "\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE21", "\uD83D\uDD25", "\uD83C\uDF89",
    "\uD83D\uDC4F", "\uD83D\uDE4F", "\uD83D\uDC40", "\uD83E\uDD14", "\uD83D\uDCAF",
    "\u2705", "\uD83D\uDE80", "\u2B50", "\uD83C\uDF1F", "\uD83E\uDD73", "\uD83E\uDD70",
    "\uD83D\uDCAA", "\uD83E\uDD1D", "\uD83D\uDE0A", "\uD83D\uDE4C", "\uD83E\uDD29",
    "\uD83E\uDD72", "\uD83E\uDD23", "\uD83D\uDC4C", "\uD83E\uDEF6",
    "\uD83D\uDCA1", "\uD83C\uDFAF", "\uD83D\uDCCC", "\uD83E\uDDE9"
)
