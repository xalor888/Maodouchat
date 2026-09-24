package com.maodouchat.server.model

import kotlinx.serialization.Serializable


@Serializable
data class AdminDashboardResponse(
    val totalUsers: Long,
    val activeUsers24h: Long,
    val deactivatedUsers: Long,
    val totalPosts: Long,
    val totalReports: Long,
    val pendingReports: Long,
    val activeModerationRules: Long
)

/**
 * 管理后台二次确认请求。
 *
 * [totpCode] 是账号启用 TOTP 时的必需第二因子：此前这里只有口令，于是「口令 + 任意
 * 有效 access token」就能换发全权限管理会话，账号开着 2FA 也一样——2FA 在应用登录
 * 生效、却在提权入口被绕过。缺失或错误时服务端回 401 且 `code` 为
 * `TOTP_REQUIRED` / `TOTP_INVALID`，前端据此提示。
 */
@Serializable
data class AdminSessionRequest(
    val password: String,
    val totpCode: String? = null
)

@Serializable
data class AdminSessionResponse(
    val token: String,
    val expiresAt: Long
)

@Serializable
data class UserAdminResponse(
    val id: String,
    val name: String,
    val email: String,
    val isModerator: Boolean,
    val lastActiveAt: Long,
    val suspendedUntil: Long,
    val postRestrictedUntil: Long = 0L,
    val messageRestrictedUntil: Long = 0L,
    val deletedAt: Long? = null
)

@Serializable
data class AdminAccountDeactivatedResponse(
    val status: String,
    val deletedAt: Long
)

@Serializable
data class PostAdminResponse(
    val id: String,
    val authorId: String,
    val authorName: String,
    val content: String,
    val status: String,
    val createdAt: Long
)

@Serializable
data class CommentAdminResponse(
    val id: String,
    val postId: String,
    val authorId: String,
    val authorName: String,
    val content: String,
    val createdAt: Long
)

// ─── 管理后台扩展模型 ─────────────────

@Serializable
data class SystemStatsResponse(
    val totalMessages: Long,
    val totalChats: Long,
    val totalGroups: Long,
    val totalAttachments: Long,
    val attachmentStorageBytes: Long,
    val totalPushTokens: Long,
    val totalAiCalls: Long,
    val aiErrorCount: Long,
    val totalRiskEvents: Long,
    val pendingRiskEvents: Long,
    val totalComments: Long,
    val totalPostLikes: Long,
    val serverUptimeMs: Long,
    val jvmMaxMemoryBytes: Long,
    val jvmUsedMemoryBytes: Long,
    val activeThreads: Int,
    val onlineUsers: Long
)

@Serializable
data class ChatAdminResponse(
    val id: String,
    val isGroup: Boolean,
    val chatType: String = "DIRECT",
    val groupName: String?,
    val groupAnnouncement: String?,
    val memberCount: Int,
    val createdAt: Long,
    val lastActivity: Long
)

@Serializable
data class AiUsageAdminResponse(
    val id: String,
    val userId: String,
    val feature: String,
    val model: String?,
    val status: String,
    val inputChars: Int,
    val contextMessages: Int,
    val durationMs: Long?,
    val error: String?,
    val createdAt: Long,
    /** Soft estimate from inputChars for ops dashboards; not provider billed tokens. */
    val estimatedTokens: Int = 0
)

@Serializable
data class PushTokenAdminResponse(
    val userId: String,
    val deviceId: String,
    val platform: String,
    val timezoneOffsetMinutes: Int,
    val updatedAt: Long
)

@Serializable
data class RiskEventAdminResponse(
    val id: String,
    val userId: String,
    val source: String,
    val ruleId: String?,
    val action: String,
    val matched: String?,
    val referenceId: String?,
    val needsReview: Boolean,
    val createdAt: Long
)

@Serializable
data class TrendPointResponse(
    val timestamp: Long,
    val value: Long
)

@Serializable
data class AdminTrendsResponse(
    val newUsers: List<TrendPointResponse>,
    val newMessages: List<TrendPointResponse>,
    val newPosts: List<TrendPointResponse>
)

@Serializable
data class UserDetailAdminResponse(
    val id: String,
    val name: String,
    val email: String,
    val isModerator: Boolean,
    val lastActiveAt: Long,
    val suspendedUntil: Long,
    val postRestrictedUntil: Long = 0L,
    val messageRestrictedUntil: Long = 0L,
    val deletedAt: Long?,
    val messageCount: Long,
    val postCount: Long,
    val commentCount: Long,
    val chatCount: Long,
    val pushTokenCount: Long,
    val reportCount: Long,
    val avatar: String?
)

// ── 管理后台增强统计 DTO ──

@Serializable
data class OnlineUserAdminResponse(
    val id: String,
    val name: String,
    val email: String,
    val avatar: String? = null,
    val lastSeen: Long,
    val isModerator: Boolean = false,
    val platform: String? = null
)

@Serializable
data class RankingEntryResponse(
    val userId: String,
    val userName: String,
    val avatar: String? = null,
    val value: Long,
    val detail: String? = null
)

@Serializable
data class AdminRankingResponse(
    val topMessagers: List<RankingEntryResponse>,
    val topPosters: List<RankingEntryResponse>,
    val topStorageUsers: List<RankingEntryResponse>,
    val mostActiveGroups: List<RankingEntryResponse>
)

@Serializable
data class StorageBreakdownEntry(
    val category: String,
    val fileCount: Long,
    val totalBytes: Long
)

@Serializable
data class AdminStorageResponse(
    val totalBytes: Long,
    val totalFiles: Long,
    val byCategory: List<StorageBreakdownEntry>,
    val quotaPerUserBytes: Long,
    val usersNearQuota: Long
)

@Serializable
data class AdminRichTrendsResponse(
    val newUsers: List<TrendPointResponse>,
    val newMessages: List<TrendPointResponse>,
    val newPosts: List<TrendPointResponse>,
    val newReports: List<TrendPointResponse>,
    val newAiCalls: List<TrendPointResponse>,
    val newAttachments: List<TrendPointResponse>,
    val activeUsers: List<TrendPointResponse>
)

@Serializable
data class AdminAuditLogResponse(
    val id: String,
    val actorId: String? = null,
    val targetUserId: String? = null,
    val action: String,
    val detail: String? = null,
    val createdAt: Long
)