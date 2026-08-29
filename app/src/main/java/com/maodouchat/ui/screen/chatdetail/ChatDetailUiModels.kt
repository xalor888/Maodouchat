package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User
import com.maodouchat.network.ChatDto
import com.maodouchat.network.PinnedMessageDto
import com.maodouchat.network.AiGroupTask

/**
 * 从 ChatDetailViewModel.kt 拆分的顶层常量、数据类、枚举和 UI 状态。
 * 这些声明不依赖 ViewModel 实例状态，可在同包内直接引用。
 */

// ── 常量 ──────────────────────────────

internal const val DEFAULT_TRANSLATION_LANGUAGE = "中文"
internal const val MAX_AI_SUMMARY_MESSAGES = 48
internal const val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1_000L
internal const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1_000L
internal const val SYNCED_UNREAD_SUMMARY_DISPLAY_MAX_AGE_MS = 24L * 60L * 60L * 1_000L

internal val RELIABLE_ATTACHMENT_TYPES = setOf(
    MessageType.FILE,
    MessageType.IMAGE,
    MessageType.GIF,
    MessageType.VIDEO,
    MessageType.VOICE
)
internal val AUTO_DOWNLOAD_MEDIA_TYPES = RELIABLE_ATTACHMENT_TYPES - MessageType.FILE

// ── 数据类 ────────────────────────────

/** 1.163：群聊消息已读人数（气泡下「已读 X/Y」）。 */
data class ReadCountUi(
    val read: Int,
    val total: Int
)

data class ReadReceiptUi(
    val userId: String,
    val name: String,
    val avatar: String? = null,
    val readAt: Long? = null,
    /** 1.65：成员是否在线（阅读详情中显示在线点）。 */
    val isOnline: Boolean = false
)

data class AiSummaryHistoryUi(
    val cacheKey: String,
    val summary: String,
    val scope: AiSummaryScope,
    val messageCount: Int,
    val createdAt: Long
)

data class AiOperationUi(
    val id: String,
    val type: String,
    val state: String,
    val attempts: Int,
    val lastErrorCode: String?,
    val nextRetryAtMs: Long? = null,
    /** 限流 / 配额时建议等待秒数（展示倒计时） */
    val retryAfterSeconds: Long? = null
)

// ── 枚举 ──────────────────────────────

enum class AiSummaryScope { RECENT, TODAY, SEVEN_DAYS, THIRTY_DAYS, SEARCH_RESULTS, UNREAD }

enum class AiImageAnalysisMode(val wireValue: String) {
    DESCRIBE("describe"), OCR("ocr"), SAFETY("safety")
}

enum class AiFileAnalysisMode(val wireValue: String) {
    SUMMARIZE("summarize"), QUESTION("question")
}

// ── UI 状态 ───────────────────────────

data class ChatDetailUiState(
    val contact: User = User("", ""),
    val chat: Chat? = null,
    val chatIsGroup: Boolean = false,
    val messages: List<com.maodouchat.data.model.Message> = emptyList(),
    /** 1.03：进入聊天时未读起点消息 id（渲染「以下为未读消息」分隔线）。 */
    val unreadSeparatorId: String? = null,
    val isLoadingOlderMessages: Boolean = false,
    val hasMoreOlderMessages: Boolean = false,
    val inputText: String = "",
    /** 1.162：已从本地恢复草稿（用户编辑输入后清除）。 */
    val hasSavedDraft: Boolean = false,
    val currentUserId: String = "",
    val currentDeviceId: Int = 1,
    val currentIdentityFingerprint: String = "",
    val isLoading: Boolean = false,
    /**
     * First open-chat history merge has finished (REST success or local fallback).
     * Until then the timeline must stay pinned to the newest bubble: reverseLayout
     * keeps the first painted (older) item when a later, newer tail is prepended.
     */
    val initialTimelineReady: Boolean = false,
    /** 8.52 UX：初次加载失败且无本地缓存时的错误文案（与「还没有消息」空态区分）。 */
    val initialLoadError: String? = null,
    val isRecording: Boolean = false,
    /** 0..1 最新振幅，供录音波形 UI */
    val recordingAmplitude: Float = 0f,
    /** 录音已进行毫秒 */
    val recordingElapsedMs: Long = 0L,
    /** 环形波形快照（最旧->最新） */
    val recordingWaveform: List<Float> = emptyList(),
    /** 发送前试听：本地文件路径 */
    val voicePreviewPath: String? = null,
    val voicePreviewDurationMs: Long = 0L,
    val isSending: Boolean = false,
    val isReporting: Boolean = false,
    val identityWarning: String? = null,
    val safetyCode: String? = null,
    val contactIdentityFingerprint: String? = null,
    val trustState: SignalProtocol.IdentityTrustState = SignalProtocol.IdentityTrustState.UNKNOWN,
    val showSafetyCodeDialog: Boolean = false,
    val canVerifyIdentity: Boolean = false,
    val deviceSafetyStates: List<SignalProtocol.DeviceSafetyState> = emptyList(),
    val isLoadingDeviceSafety: Boolean = false,
    val deviceSafetyWarning: String? = null,
    val groupEncryptionWarning: String? = null,
    /** 0.65 新功能：群成员 userId → 角色（OWNER/ADMIN/MEMBER），消息发送者旁渲染群主/管理员徽章。 */
    val memberRoleByUser: Map<String, String> = emptyMap(),
    /** 0.69 修复：群成员 userId → 群内昵称（群聊消息发送者显示名优先使用群昵称）。 */
    val memberNicknameByUser: Map<String, String> = emptyMap(),
    val typingContact: String? = null,
    val forwardTargets: List<Chat> = emptyList(),
    val isForwarding: Boolean = false,
    val isContactBlocked: Boolean = false,
    val isBlockingContact: Boolean = false,
    val groupCandidates: List<User> = emptyList(),
    val isUpdatingGroup: Boolean = false,
    val readReceipts: List<ReadReceiptUi> = emptyList(),
    val isLoadingReadReceipts: Boolean = false,
    /** 1.163：群聊消息已读人数缓存（messageId → 已读/总数）。 */
    val groupReadCounts: Map<String, ReadCountUi> = emptyMap(),
    val showAiConsentDialog: Boolean = false,
    val isAiWorking: Boolean = false,
    val aiDraftOriginal: String? = null,
    val aiDraftPreview: String = "",
    val isAiDraftStreaming: Boolean = false,
    val aiDraftStreamErrorCode: String? = null,
    val aiSuggestions: List<String> = emptyList(),
    val isAiReplyStreaming: Boolean = false,
    val aiReplyStreamErrorCode: String? = null,
    val aiSummary: String? = null,
    val aiSummaryScope: AiSummaryScope? = null,
    val aiSummaryMessageCount: Int = 0,
    val showAiSummaryHistory: Boolean = false,
    val isAiSummaryHistoryLoading: Boolean = false,
    val aiSummaryHistory: List<AiSummaryHistoryUi> = emptyList(),
    val groupAiAnswer: String? = null,
    val groupAiQuestion: String = "",
    val groupAiMode: String = "answer",
    val groupAiTasks: List<AiGroupTask> = emptyList(),
    val isSavingGroupAiTasks: Boolean = false,
    val groupAiTasksSaved: Boolean = false,
    val groupAiTaskSaveError: String? = null,
    /** 当前答案是否已触发过「确认分享」，防止连点双发 */
    val groupAiAnswerShared: Boolean = false,
    val unreadAiSummary: String? = null,
    val unreadAiSummaryCount: Int = 0,
    val isUnreadSummaryLoading: Boolean = false,
    val transcribingVoiceMessageIds: Set<String> = emptySet(),
    val translatingMessageIds: Set<String> = emptySet(),
    val analyzingImageMessageIds: Set<String> = emptySet(),
    val aiImageAnalysisResult: String? = null,
    val aiImageAnalysisMode: AiImageAnalysisMode? = null,
    val analyzingFileMessageIds: Set<String> = emptySet(),
    val aiFileAnalysisResult: String? = null,
    val aiFileAnalysisMode: AiFileAnalysisMode? = null,
    val aiFileAnalysisName: String? = null,
    val fileTransferProgress: Map<String, Float> = emptyMap(),
    val fileTransferStates: Map<String, String> = emptyMap(),
    val fileTransferErrors: Map<String, String> = emptyMap(),
    val preparingAttachmentMessageIds: Set<String> = emptySet(),
    val downloadingFileMessageIds: Set<String> = emptySet(),
    val mediaDownloadErrorMessageIds: Set<String> = emptySet(),
    val fileReadyToOpenUri: String? = null,
    val semanticSearchResultIds: List<String> = emptyList(),
    val semanticSearchQuery: String = "",
    val isSemanticSearching: Boolean = false,
    val semanticSearchError: String? = null,
    val navigationTargetMessageId: String? = null,
    val aiEnabled: Boolean = false,
    val isUpdatingAiSetting: Boolean = false,
    val aiOperations: List<AiOperationUi> = emptyList(),
    /** 群聊中我的角色 OWNER/ADMIN/MEMBER；单聊为空 */
    val myMemberRole: String? = null,
    /** 8.48：我在本群的禁言截止时间（0=未禁言）；加载群成员时填充 */
    val myMutedUntil: Long = 0L,
    /** 会话置顶消息（新->旧） */
    val pinnedMessages: List<PinnedMessageDto> = emptyList(),
    val isTogglingPin: Boolean = false,
    /** 1:1 阅后即焚秒数；0=关；群聊始终 0 */
    val disappearingMessageSeconds: Int = 0,
    val isUpdatingDisappearing: Boolean = false,
    /** 本会话待发的本地定时文本 */
    val scheduledMessages: List<com.maodouchat.util.ScheduledMessage> = emptyList(),
    val scheduledInfoMessage: String? = null,
    /** 导出聊天结果提示（成功/空/失败） */
    val exportInfoMessage: String? = null,
    /** 本地会话 PIN 锁：是否已设置；null=尚未查询 */
    val isChatLocked: Boolean? = null,
    /** 本次进入会话是否已通过 PIN（进程内有效） */
    val isChatUnlocked: Boolean = false,
    val chatLockInfoMessage: String? = null,
    /** 当前打开的是密聊会话（chatType=SECRET）；null=尚未判定 */
    val isSecretChat: Boolean? = null,
    val secretChatInfoMessage: String? = null,
    /** 从普通单聊「发起密聊」成功后，导航到新的密聊会话。 */
    val openedSecretChatId: String? = null,
    /** 通用错误提示（功能禁用 / 操作失败），消费后清空 */
    val errorMessage: String? = null,
    /** 通用信息提示（操作成功），消费后清空 */
    val infoMessage: String? = null,
    /** Composer: next message is silent (no peer push). */
    val silentSend: Boolean = false,
    /** Active outbound live-location session (this device). */
    val activeLiveLocationMessageId: String? = null,
    val activeLiveLocationSessionId: String? = null,
    val activeLiveLocationUntil: Long? = null,
    /** Secret chat has a cached sealed-sender certificate for outbound. */
    val sealedSenderReady: Boolean = false,
    val sealedSenderExpiresInSec: Long = 0L,
    /** 本会话已入群/私聊的 bot 斜杠命令（明文 inbox，不碰成员密文）。 */
    val botCommands: List<com.maodouchat.bot.BotCommandPolicy.BotCommandItem> = emptyList()
)

// ── DTO 映射 ──────────────────────────

internal fun ChatDto.toDomainChat(): Chat = Chat(
    id = id,
    participants = participants.map { User(it.id, it.name, it.avatar, it.email, it.isOnline, it.status, lastSeen = it.lastSeen) },
    lastMessage = lastMessage,
    lastMessageType = MessageType.fromWire(lastMessageType),
    lastMessageTime = lastMessageTime,
    unreadCount = unreadCount,
    isGroup = isGroup,
    chatType = chatType,
    groupName = groupName,
    groupAnnouncement = groupAnnouncement,
    groupAvatar = groupAvatar,
    memberRevision = memberRevision,
    disappearingMessageSeconds = disappearingMessageSeconds
)
