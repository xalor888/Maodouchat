package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Chat

internal data class ChatLoadInput(
    val chat: Chat,
    /** 进入前的成员 revision；null 表示首次进入（无基线，不算「成员变更」）。 */
    val previousRevision: Long?,
    val currentUserId: String,
    /** true 表示这是 API 失败后的本地缓存回退。 */
    val fromCache: Boolean = false,
)

internal data class ChatLoadPlan(
    val nextState: ChatDetailUiState,
    val effects: List<ChatLoadEffect>,
    val shouldInvalidateSenderKey: Boolean,
    val revisionWarning: String?,
    val fromCache: Boolean,
)

internal /** 加载完成后要执行的副作用。用 sealed 让调用方的 `when` 必须穷尽。 */
sealed interface ChatLoadEffect {
    data class OccupySessionCipher(val chatId: String, val peerUserId: String?) : ChatLoadEffect
    data object LoadGroupCandidates : ChatLoadEffect
    data class RefreshBotCommands(val chatId: String) : ChatLoadEffect
    data class RefreshBlockState(val peerUserId: String) : ChatLoadEffect
    data object RefreshScheduledMessages : ChatLoadEffect
    data class RefreshIdentitySafety(val peerUserId: String) : ChatLoadEffect
}
