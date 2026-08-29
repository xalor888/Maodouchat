package com.maodouchat.server.model

import kotlinx.serialization.Serializable



@Serializable
data class AdminChannelHealthResponse(
    val openaiConfigured: Boolean,
    val turnConfigured: Boolean,
    val smtpConfigured: Boolean,
    val jwtConfigured: Boolean,
    val openaiModel: String = "",
    val turnUrlCount: Int = 0,
    val smtpHostMasked: String = ""
)

@Serializable
data class ErrorResponse(
    val error: String,
    val code: String? = null,
    val retryAfterSeconds: Long? = null,
    val messageId: String? = null
)

@Serializable
data class HealthStatusResponse(
    val status: String,
    val service: String = "maodouchat-server",
    val checks: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class AiAuditLogResponse(
    val id: String,
    val chatId: String? = null,
    val feature: String,
    val model: String? = null,
    val status: String,
    val inputChars: Int = 0,
    val contextMessages: Int = 0,
    val durationMs: Long? = null,
    val error: String? = null,
    val createdAt: Long
)

@Serializable
data class NotificationSettingsRequest(
    val enableNotifications: Boolean? = null,
    val soundEnabled: Boolean? = null,
    val previewEnabled: Boolean? = null,
    val ringtoneEnabled: Boolean? = null,
    val dndStartHour: Int? = null,
    val dndEndHour: Int? = null,
    val dndEnabled: Boolean? = null,
    val dndStartMinute: Int? = null,
    val dndEndMinute: Int? = null
)

@Serializable
data class NotificationSettingsResponse(
    val enableNotifications: Boolean = true,
    val soundEnabled: Boolean = true,
    val previewEnabled: Boolean = true,
    val ringtoneEnabled: Boolean = true,
    val dndStartHour: Int = 22,
    val dndEndHour: Int = 7,
    val dndEnabled: Boolean = false,
    val dndStartMinute: Int = 22 * 60,
    val dndEndMinute: Int = 7 * 60,
    val updatedAt: Long = 0
)

@Serializable
data class RegisterPushTokenRequest(
    val deviceId: String,
    val token: String,
    val platform: String = "ANDROID",
    val timezoneOffsetMinutes: Int = 0
)

@Serializable
data class RemovePushTokenRequest(val deviceId: String)

@Serializable
data class DeleteAccountRequest(val password: String)

@Serializable
data class DeleteAccountResponse(val status: String = "ok", val deletedAt: Long)

@Serializable
data class CreateReportRequest(
    val targetType: String,
    val targetId: String,
    val chatId: String? = null,
    val messageId: String? = null,
    val reason: String,
    val description: String? = null
)

@Serializable
data class ReportResponse(
    val id: String,
    val reporterId: String,
    val targetType: String,
    val targetId: String,
    val chatId: String? = null,
    val messageId: String? = null,
    val reason: String,
    val description: String? = null,
    val status: String,
    val createdAt: Long,
    val reviewerId: String? = null,
    val resolutionNote: String? = null,
    val actionTaken: String? = null,
    val actionAt: Long? = null,
    val resolvedAt: Long? = null
)

@Serializable
data class UpdateReportStatusRequest(
    val status: String,
    val resolutionNote: String? = null
)

@Serializable
data class ApplyReportActionRequest(
    val action: String,
    val resolutionNote: String? = null
)

@Serializable
data class ModerationRuleResponse(
    val id: String,
    val name: String,
    val description: String? = null,
    val scope: String,
    val matchType: String,
    val pattern: String? = null,
    val action: String,
    val windowMs: Long = 0,
    val hitThreshold: Int = 0,
    val escalationAction: String? = null,
    val enabled: Boolean = true,
    val priority: Int = 100,
    val updatedAt: Long = 0
)

@Serializable
data class UpdateModerationRuleRequest(
    val name: String? = null,
    val scope: String? = null,
    val matchType: String? = null,
    val pattern: String? = null,
    val enabled: Boolean? = null,
    val action: String? = null,
    val hitThreshold: Int? = null,
    val windowMs: Long? = null,
    val escalationAction: String? = null,
    val priority: Int? = null
)

@Serializable
data class CreateModerationRuleRequest(
    val name: String,
    val scope: String = "ALL",
    val matchType: String = "KEYWORD",
    val pattern: String,
    val action: String = "WARN_MOD",
    val hitThreshold: Int = 0,
    val windowMs: Long = 0,
    val priority: Int = 100,
    val enabled: Boolean = true
)

@Serializable
data class RiskEventResponse(
    val id: String,
    val userId: String,
    val source: String,
    val ruleId: String? = null,
    val action: String,
    val matched: String? = null,
    val referenceId: String? = null,
    val needsReview: Boolean = false,
    val createdAt: Long
)

// 标记聊天已读
@Serializable
data class MarkReadResponse(val status: String, val updated: Int)

// 会话级标记已读请求；throughId 用于把"已读"边界钳到客户端实际加载到的最后一条消息，
// 防止 getUnreadWindow/getMessages 快照之后新到消息被越界标读。
@Serializable
data class MarkReadRequest(val throughId: String? = null)

// 邮箱验证码
@Serializable
data class SendCodeRequest(val email: String, val purpose: String = "register")

@Serializable
data class VerifyCodeRequest(val email: String, val code: String)

// 注册（带验证码）
@Serializable
data class RegisterWithCodeRequest(val name: String, val email: String, val password: String, val code: String)

// 忘记密码 / 重置
@Serializable
data class ResetPasswordRequest(val email: String, val code: String, val newPassword: String)

// 好友申请
@Serializable
data class SendFriendRequestBody(val toUserId: String, val message: String = "")

@Serializable
data class FriendRequestResponse(
    val id: String,
    val fromUser: UserResponse,
    val toUser: UserResponse,
    val message: String = "",
    val status: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class FriendRequestEventPayload(
    val action: String,
    val request: FriendRequestResponse
)

// 会话文件夹云同步
@Serializable
data class ChatFolderDto(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val chatIds: List<String> = emptyList(),
    val updatedAt: Long = 0
)

@Serializable
data class ChatFoldersSyncRequest(val folders: List<ChatFolderDto> = emptyList())

@Serializable
data class ChatFoldersSyncResponse(
    val folders: List<ChatFolderDto> = emptyList(),
    val updatedAt: Long = 0
)

/** 多端外观/语言/列表/AI 写作风格/应用锁超时/防截屏偏好（非密钥、非会话正文） */
@Serializable
data class ClientPrefsDto(
    val themeMode: String = "system",
    val themeStyle: String = "maodou",
    val accentColor: String = "none",
    val languageMode: String = "system",
    val chatWallpaper: String = "default",
    val chatFontScale: String = "normal",
    val linkPreviewEnabled: Boolean = true,
    val unreadPriorityEnabled: Boolean = true,
    val writingStyleEnabled: Boolean = false,
    val writingStylePreset: String = "none",
    val writingStyleCustom: String = "",
    val appLockTimeoutMinutes: Long = 5,
    val screenSecureEnabled: Boolean = false,
    val sensitiveGateEnabled: Boolean = true,
    val updatedAt: Long = 0
)

@Serializable
data class ClientPrefsUpdateRequest(
    val themeMode: String? = null,
    val themeStyle: String? = null,
    val accentColor: String? = null,
    val languageMode: String? = null,
    val chatWallpaper: String? = null,
    val chatFontScale: String? = null,
    val linkPreviewEnabled: Boolean? = null,
    val unreadPriorityEnabled: Boolean? = null,
    val writingStyleEnabled: Boolean? = null,
    val writingStylePreset: String? = null,
    val writingStyleCustom: String? = null,
    val appLockTimeoutMinutes: Long? = null,
    val screenSecureEnabled: Boolean? = null,
    val sensitiveGateEnabled: Boolean? = null
)

// 头像上传
@Serializable
data class UploadAvatarRequest(val base64Data: String)

// 修改资料
@Serializable
data class UpdateProfileRequest(val name: String? = null, val status: String? = null)

// 修改密码
@Serializable
data class ChangePasswordRequest(val oldPassword: String, val newPassword: String)

// 隐私设置
@Serializable
data class UserPrivacyResponse(
    val showOnline: Boolean = true,
    val showStatus: Boolean = true,
    val searchable: Boolean = true,
    val defaultPostVisibility: String = "PUBLIC",
    val onlineVisibility: String = "everyone"
)

@Serializable
data class UpdatePrivacyRequest(
    val showOnline: Boolean? = null,
    val showStatus: Boolean? = null,
    val searchable: Boolean? = null,
    val defaultPostVisibility: String? = null,
    val onlineVisibility: String? = null
)

@Serializable
data class UpdateNearbyLocationRequest(
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class NearbyLocationStatusResponse(
    val sharing: Boolean,
    val expiresAt: Long = 0
)

@Serializable
data class NearbyUserResponse(
    val user: UserResponse,
    val distanceMeters: Int,
    val locationUpdatedAt: Long
)

// 发现页 / 动态
@Serializable
data class CreatePostRequest(
    val content: String = "",
    val imageUrls: List<String> = emptyList(),
    val visibility: String? = null,
    val useDefaultVisibility: Boolean? = null
)

@Serializable
data class PostResponse(
    val id: String,
    val author: UserResponse,
    val content: String,
    val imageUrls: List<String> = emptyList(),
    val visibility: String = "PUBLIC",
    val createdAt: Long,
    val editedAt: Long? = null,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val likedByMe: Boolean = false,
    val isMine: Boolean = false
)

@Serializable
data class EditPostRequest(val content: String = "", val visibility: String? = null)

@Serializable
data class CreateCommentRequest(val content: String, /** 1.76：回复目标评论 id（可选）。 */ val replyToId: String? = null)

@Serializable
data class UpdateCommentRequest(val content: String)

@Serializable
data class PostCommentResponse(
    val id: String,
    val postId: String,
    val author: UserResponse,
    val content: String,
    val createdAt: Long,
    val isMine: Boolean = false,
    /** 1.76：被回复评论 id（null=顶级评论）。 */
    val parentId: String? = null,
    /** 1.52：评论点赞数与我是否已赞。 */
    val likeCount: Int = 0,
    val likedByMe: Boolean = false
)

/** 1.93：动态点赞者列表（最新在前，过滤双向拉黑）。 */
@Serializable
data class PostLikersResponse(
    val postId: String,
    val likers: List<UserResponse>
)

@Serializable
data class UploadPostImageRequest(val base64Data: String)

@Serializable
data class UploadPostImageResponse(val status: String, val imageUrl: String)

// 群成员信息（带角色/头衔/群昵称）
@Serializable
data class GroupMemberResponse(
    val userId: String,
    val name: String,
    val avatar: String? = null,
    val role: String = "MEMBER",
    val title: String? = null,
    val groupNickname: String? = null,
    val joinedAt: Long = 0,
    val isOnline: Boolean = false,
    val mutedUntil: Long = 0
)

@Serializable
data class UpdateMemberRoleRequest(val role: String)

@Serializable
data class UpdateGroupNicknameRequest(val groupNickname: String)

@Serializable
data class UpdateMemberTitleRequest(val title: String)

@Serializable
data class UpdateMemberMuteRequest(val mutedUntil: Long = 0)

// 已读回执
@Serializable
data class ReadReceiptResponse(val userId: String, val readAt: Long)

@Serializable
data class SenderKeyDistributionTargetResponse(
    val userId: String,
    val deviceId: Int,
    val status: String,
    val error: String? = null,
    val updatedAt: Long
)

@Serializable
data class SenderKeyDistributionStatusResponse(
    val chatId: String,
    val epoch: Long,
    val total: Int,
    val sent: Int,
    val failed: Int,
    val pending: Int,
    val targets: List<SenderKeyDistributionTargetResponse>
)

// ─── 管理后台响应模型 ─────────────────
