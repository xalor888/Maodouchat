package com.maodouchat.server.model

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(val name: String, val email: String, val password: String)

@Serializable
data class LoginRequest(val email: String, val password: String, val totpCode: String = "")

@Serializable
data class AuthResponse(
    val token: String = "",
    val userId: String = "",
    val name: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val refreshExpiresAt: Long = 0,
    val requiresTotp: Boolean = false,
    val totpEnabled: Boolean = false
)

@Serializable
data class TotpSetupResponse(
    val secret: String,
    val otpauthUrl: String,
    val enabled: Boolean
)

@Serializable
data class TotpCodeRequest(val code: String)

@Serializable
data class TotpStatusResponse(val enabled: Boolean, val backupCodes: List<String> = emptyList())


@Serializable
data class RefreshTokenRequest(
    val refreshToken: String,
    /** 可选：单设备 logout 时清除该推送 deviceId，避免已退出设备仍收 FCM */
    val deviceId: String = ""
)

@Serializable
data class UserResponse(val id: String, val name: String, val email: String = "", val avatar: String? = null, val status: String = "", val isOnline: Boolean = false, val isModerator: Boolean = false, val lastSeen: Long = 0, val username: String? = null)

@Serializable
data class ChatResponse(
    val id: String, val participants: List<UserResponse>, val lastMessage: String = "",
    val lastMessageType: String = "TEXT", val lastMessageTime: Long = 0, val unreadCount: Int = 0,
    val isGroup: Boolean = false, val groupName: String? = null, val groupAnnouncement: String? = null, val groupAvatar: String? = null,
    val memberRevision: Long = 0, val pinnedAt: Long = 0, val notificationsMuted: Boolean = false,
    val archived: Boolean = false, val markedUnread: Boolean = false, val settingsUpdatedAt: Long = 0,
    val disappearingMessageSeconds: Int = 0,
    /** 会话类型：DIRECT / GROUP / CHANNEL（广播频道，单向一对多） */
    val chatType: String = "DIRECT"
)

object ChatType {
    const val DIRECT = "DIRECT"
    const val GROUP = "GROUP"
    const val CHANNEL = "CHANNEL"
}

@Serializable
data class UpdateChatSettingsRequest(
    val pinned: Boolean? = null,
    val notificationsMuted: Boolean? = null,
    val archived: Boolean? = null,
    val markedUnread: Boolean? = null
)

@Serializable
data class ChatSettingsResponse(
    val chatId: String, val pinnedAt: Long, val notificationsMuted: Boolean,
    val archived: Boolean, val markedUnread: Boolean, val updatedAt: Long
)

@Serializable
data class MessageReactionResponse(val userId: String, val emoji: String, val reactedAt: Long)

@Serializable
data class PinnedMessageResponse(
    val chatId: String,
    val messageId: String,
    val pinnedBy: String,
    val pinnedAt: Long
)

@Serializable
data class PinnedMessagesListResponse(
    val chatId: String,
    val pins: List<PinnedMessageResponse> = emptyList()
)

@Serializable
data class TogglePinResponse(
    val status: String = "ok",
    val pinned: Boolean = false,
    val pins: List<PinnedMessageResponse> = emptyList()
)

@Serializable
data class MessageResponse(
    val id: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val type: String = "TEXT",
    val timestamp: Long,
    val status: String = "SENT",
    val editedAt: Long? = null,
    val starred: Boolean = false,
    val reactions: List<MessageReactionResponse> = emptyList(),
    val expiresAt: Long? = null,
    val sealedSender: Boolean = false
)

@Serializable
data class UpdateDisappearingMessagesRequest(val seconds: Int = 0)

@Serializable
data class DisappearingMessagesResponse(
    val chatId: String,
    val seconds: Int,
    val updatedAt: Long
)

@Serializable
data class MessageExpiresPayload(
    val messageId: String,
    val chatId: String,
    val expiresAt: Long
)

/** 跨设备已读同步：某设备标记会话已读后广播给同账号其他活跃连接。 */
@Serializable
data class ChatMarkedReadPayload(
    val chatId: String
)

/** 多设备 DELETE/REVOKE/EDIT 变更回放条目 */
@Serializable
data class MessageMutationResponse(
    val id: String,
    val chatId: String,
    val messageId: String,
    val action: String,
    val actorId: String,
    val content: String? = null,
    val editedAt: Long? = null,
    val createdAt: Long
)

@Serializable
data class UnreadWindowResponse(
    val messageIds: List<String>,
    val totalCount: Int,
    val truncated: Boolean
)

@Serializable
data class SendMessageRequest(
    val chatId: String,
    val content: String,
    val type: String = "TEXT",
    val id: String? = null,
    /** Client requests sealed-sender style delivery (requires valid cert header or matching field). */
    val sealedSender: Boolean = false,
    val sealedSenderCertificate: String? = null,
    val silent: Boolean = false
)

@Serializable
data class AttachmentUploadResponse(
    val id: String,
    val cipherSha256: String,
    val cipherSize: Long,
    val expiresAt: Long
)

@Serializable
data class AttachmentCommitRequest(val messageId: String)

@Serializable
data class AttachmentUploadSessionRequest(
    val chatId: String,
    val messageId: String,
    val cipherSha256: String,
    val cipherSize: Long
)

@Serializable
data class AttachmentUploadStatusResponse(
    val id: String,
    val cipherSha256: String,
    val cipherSize: Long,
    val uploadedBytes: Long,
    val status: String,
    val expiresAt: Long,
    val complete: Boolean
)

@Serializable
data class UpdateMessageReactionRequest(val emoji: String)

@Serializable
data class CreateChatRequest(
    val participantIds: List<String>,
    val isGroup: Boolean = false,
    val groupName: String? = null,
    /** DIRECT / GROUP / CHANNEL；null 时由 isGroup 推导。 */
    val chatType: String? = null
)

@Serializable
data class UpdateGroupAnnouncementRequest(val announcement: String)

@Serializable
data class GroupInviteResponse(
    val token: String,
    val payload: String,
    val chat: ChatResponse? = null,
    val expiresAt: Long = 0,
    val maxUses: Int = 0,
    val usedCount: Int = 0,
    val remainingUses: Int = 0
)

@Serializable
data class CreateGroupInviteRequest(
    val rotate: Boolean = false,
    val expiresInSeconds: Long = 7L * 24L * 60L * 60L,
    val maxUses: Int = 100
)

@Serializable
data class GroupAuditLogResponse(
    val id: String,
    val actorId: String,
    val actorName: String,
    val action: String,
    val targetUserId: String? = null,
    val targetUserName: String? = null,
    val createdAt: Long
)

@Serializable
data class JoinGroupInviteRequest(val token: String)

@Serializable
data class UpdateStatusRequest(val messageId: String, val status: String)

@Serializable
data class WsMessage(val type: String, val payload: String)

@Serializable
data class GroupRevisionChangedPayload(
    val chatId: String,
    val memberRevision: Long,
    val reason: String,
    val actorId: String? = null,
    val targetUserId: String? = null
)

@Serializable
data class ErrorResponse(
    val error: String,
    val code: String? = null,
    val retryAfterSeconds: Long? = null
)

@Serializable
data class HealthStatusResponse(
    val status: String,
    val service: String = "maodouchat-server",
    val checks: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

// AI Gateway
@Serializable
data class AiRewriteRequest(
    val text: String,
    val mode: String = "polish",
    val targetLanguage: String? = null,
    val chatId: String? = null,
    /** Optional client writing-style preference; untrusted preference text only. */
    val styleHint: String? = null
)

@Serializable
data class AiRewriteResponse(val text: String, val model: String)

@Serializable
data class AiTranslateRequest(val text: String, val targetLanguage: String = "中文", val chatId: String? = null)

@Serializable
data class AiTranslateResponse(val text: String, val model: String, val targetLanguage: String)

@Serializable
data class AiContextMessage(val sender: String = "", val text: String)

@Serializable
data class AiSuggestRepliesRequest(
    val messages: List<AiContextMessage>,
    val tone: String = "natural",
    val count: Int = 3,
    val chatId: String? = null
)

@Serializable
data class AiSuggestRepliesResponse(val replies: List<String>, val model: String)

@Serializable
data class AiStreamEvent(
    val type: String,
    val text: String? = null,
    val model: String? = null,
    val code: String? = null
)

@Serializable
data class AiSummarizeRequest(
    val messages: List<AiContextMessage>,
    val style: String = "brief",
    val chatId: String? = null
)

@Serializable
data class AiSummarizeResponse(val summary: String, val model: String)

@Serializable
data class AiSummarySyncUploadRequest(
    val syncId: String,
    val senderDeviceId: Int,
    val targetDeviceIds: List<Int>,
    val envelope: String
)

@Serializable
data class AiSummarySyncEnvelopeResponse(
    val id: String,
    val syncId: String,
    val senderDeviceId: Int,
    val envelope: String,
    val createdAt: Long
)

@Serializable
data class AiSummarySyncAckRequest(
    val deviceId: Int,
    val envelopeIds: List<String>
)

@Serializable
data class AiGroupAssistantRequest(
    val query: String,
    val messages: List<AiContextMessage>,
    val mode: String = "answer",
    val chatId: String
)

@Serializable
data class AiGroupTask(
    val title: String,
    val owner: String? = null,
    val dueText: String? = null,
    val dueAt: Long? = null
)

@Serializable
data class AiGroupAssistantResult(
    val answer: String,
    val tasks: List<AiGroupTask> = emptyList()
)

@Serializable
data class AiGroupAssistantResponse(
    val answer: String,
    val mode: String,
    val model: String,
    val tasks: List<AiGroupTask> = emptyList()
)

@Serializable
data class AiSemanticSearchCandidate(
    val messageId: String,
    val sender: String = "",
    val text: String,
    val timestamp: Long
)

@Serializable
data class AiSemanticSearchRequest(
    val query: String,
    val candidates: List<AiSemanticSearchCandidate>,
    val limit: Int = 10,
    val chatId: String
)

@Serializable
data class AiSemanticSearchMatch(val messageId: String, val score: Double)

@Serializable
data class AiSemanticSearchResponse(
    val matches: List<AiSemanticSearchMatch>,
    val model: String
)

@Serializable
data class AiGlobalSemanticSearchCandidate(
    val chatId: String,
    val messageId: String,
    val sender: String = "",
    val text: String,
    val timestamp: Long
)

@Serializable
data class AiGlobalSemanticSearchRequest(
    val query: String,
    val candidates: List<AiGlobalSemanticSearchCandidate>,
    val limit: Int = 20
)

@Serializable
data class AiGlobalSemanticSearchMatch(
    val chatId: String,
    val messageId: String,
    val score: Double
)

@Serializable
data class AiGlobalSemanticSearchResponse(
    val matches: List<AiGlobalSemanticSearchMatch>,
    val model: String
)

@Serializable
data class AiTranscribeRequest(
    val audioBase64: String,
    val mimeType: String = "audio/mp4",
    val language: String? = null,
    val chatId: String? = null
)

@Serializable
data class AiTranscribeResponse(val text: String, val model: String)

@Serializable
data class AiImageAnalyzeRequest(
    val imageBase64: String,
    val mimeType: String = "image/jpeg",
    val mode: String = "describe",
    val chatId: String
)

@Serializable
data class AiImageAnalyzeResponse(
    val text: String,
    val mode: String,
    val model: String
)

@Serializable
data class AiFileAnalyzeRequest(
    val fileBase64: String,
    val fileName: String,
    val mimeType: String,
    val mode: String = "summarize",
    val question: String? = null,
    val chatId: String
)

@Serializable
data class AiFileAnalyzeResponse(
    val text: String,
    val mode: String,
    val fileName: String,
    val model: String
)

@Serializable
data class AiSettingsRequest(val chatId: String? = null, val enabled: Boolean)

@Serializable
data class AiSettingsResponse(
    val userEnabled: Boolean = true,
    val chatId: String? = null,
    val chatEnabled: Boolean? = null,
    val effectiveEnabled: Boolean = true
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

// Signal 密钥交换
@Serializable
data class UploadKeysRequest(
    val registrationId: Int,
    val deviceId: Int = 1,
    val deviceName: String? = null,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKey: String,
    val signedPreKeySignature: String,
    val preKeys: List<PreKeyData>
)

@Serializable
data class PreKeyData(val keyId: Int, val publicKey: String)

@Serializable
data class PreKeyBundleResponse(
    val registrationId: Int,
    val deviceId: Int,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKey: String,
    val signedPreKeySignature: String,
    val preKeyId: Int? = null,
    val preKey: String? = null
)

@Serializable
data class DeviceInfoResponse(
    val userId: String,
    val deviceId: Int,
    val deviceName: String = "我的设备",
    val identityKey: String,
    val lastSeenAt: Long? = null,
    val isCurrent: Boolean = false,
    val status: String = "CONFIRMED",
    val confirmedAt: Long? = null,
    val confirmedByDeviceId: Int? = null
)

@Serializable
data class UpdateDeviceNameRequest(val deviceName: String)

@Serializable
data class ConfirmDeviceRequest(
    val approverDeviceId: Int,
    val signature: String = ""
)

@Serializable
data class DevicePreKeyBundleResponse(
    val userId: String,
    val deviceId: Int,
    val registrationId: Int,
    val identityKey: String,
    val signedPreKeyId: Int,
    val signedPreKey: String,
    val signedPreKeySignature: String,
    val preKeyId: Int? = null,
    val preKey: String? = null
)

// WebRTC 信令
@Serializable
data class SendSignalRequest(
    val toUserId: String,
    val type: String,
    val payload: String,
    val callId: String = "",
    val groupId: String = "",
    val groupMemberIds: List<String> = emptyList(),
    val groupInvite: Boolean = false
)

@Serializable
data class SignalMessageResponse(
    val id: String,
    val fromUserId: String,
    val type: String,
    val payload: String,
    val timestamp: Long,
    val callId: String = "",
    val groupId: String = "",
    val groupMemberIds: List<String> = emptyList(),
    val groupInvite: Boolean = false
)

@Serializable
data class IceServerResponse(
    val urls: List<String>,
    val username: String = "",
    val credential: String = ""
)

@Serializable
data class IceConfigResponse(
    val iceServers: List<IceServerResponse>,
    val expiresAt: Long,
    val turnEnabled: Boolean
)

// 标记聊天已读
@Serializable
data class MarkReadResponse(val status: String, val updated: Int)

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
    val defaultPostVisibility: String = "PUBLIC"
)

@Serializable
data class UpdatePrivacyRequest(
    val showOnline: Boolean? = null,
    val showStatus: Boolean? = null,
    val searchable: Boolean? = null,
    val defaultPostVisibility: String? = null
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
data class BatchReadRequest(val chatIds: List<String> = emptyList())

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
data class SenderKeyDistributionTargetRequest(
    val userId: String,
    val deviceId: Int,
    val status: String = "SENT",
    val error: String? = null
)

@Serializable
data class SenderKeyDistributionReportRequest(
    val epoch: Long,
    val messageId: String? = null,
    val targets: List<SenderKeyDistributionTargetRequest>
)

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

@Serializable
data class AdminSessionRequest(val password: String)

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
