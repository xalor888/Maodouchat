package com.maodouchat.core.model

/** 附带 retry-after 的错误码解析；[persistedCode] 可写入 Room lastErrorCode。 */
data class AiClassifiedError(
    val code: String,
    val retryAfterSeconds: Long? = null,
    val persistedCode: String = code
)

/** 加载完成后要执行的副作用。用 sealed 让调用方的 `when` 必须穷尽。 */
sealed interface ChatLoadEffect {
    data class OccupySessionCipher(val chatId: String, val peerUserId: String?) : ChatLoadEffect
    data object LoadGroupCandidates : ChatLoadEffect
    data class RefreshBotCommands(val chatId: String) : ChatLoadEffect
    data class RefreshBlockState(val peerUserId: String) : ChatLoadEffect
    data object RefreshScheduledMessages : ChatLoadEffect
    data class RefreshIdentitySafety(val peerUserId: String) : ChatLoadEffect
}

/** 文案标签簇：原在 ChatDetailRoute 顶部内联读取，纯搬移。 */
data class ChatDetailMessageLabels(
    val sensitiveAuthTitle: String,
    val sensitiveAuthExport: String,
    val sensitiveAuthClearHistory: String,
    val sensitiveAuthFailed: String,
    val chatPermissionRecordMsg: String,
    val chatPermissionVoiceCallMsg: String,
    val chatPermissionVideoCallMsg: String,
    val chatPermissionLocationMsg: String,
    val chatCopiedMsg: String,
    val chatAiImageResultTitle: String,
    val chatAiFileResultTitle: String,
    val chatGroupAiTitle: String,
    val chatGroupAiCopiedMsg: String,
    val chatClipboardMessageLabel: String,
    val chatClipboardTranslationLabel: String,
    val chatTranslationCopiedMsg: String,
    val chatClipboardTranscriptLabel: String,
    val chatTranscriptCopiedMsg: String,
    val unknownSenderLabel: String,
    val groupMemberLabel: String,
)
