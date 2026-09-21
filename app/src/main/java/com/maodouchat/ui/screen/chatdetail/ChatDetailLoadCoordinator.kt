package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.model.User

/**
 * 进入聊天时「加载会话」的纯决策核心（G65，从 `ChatDetailViewModel.loadChat()` 178 行里抽出）。
 *
 * **为什么抽它**：`loadChat` 把网络、Room、资源字符串、UI 状态和「该不该失效 Sender Key」
 * 全搅在一行行 `viewModelScope.launch` 里。而这些判定恰恰是最容易被静默改坏的部分：
 * revision 上升时忘了失效 Sender Key → 群消息解不开且无提示；revision 不变时误失效 → 每次进
 * 聊天都重排密钥。它们此前**一个用例都没有**。
 *
 * **设计约束**：本文件是**纯函数**——不碰 Coroutine、Room、ApiService、Android 资源。
 * 所有需要资源的字符串都由调用方以 `formatXxx`  lambda 传入，测试因此完全确定且跑得快。
 * 返回值分成两部分：
 * - `nextState`：要写回的 UI 状态；
 * - `effects`：要触发的副作用清单（ViewModel 负责真正执行）。
 *
 * 这样「决策」可测，「执行」仍留在编排层，边界清晰。
 */
internal object ChatDetailLoadCoordinator {

    data class ChatLoadInput(
        val chat: Chat,
        /** 进入前的成员 revision；null 表示首次进入（无基线，不算「成员变更」）。 */
        val previousRevision: Long?,
        val currentUserId: String,
        /** true 表示这是 API 失败后的本地缓存回退。 */
        val fromCache: Boolean = false,
    )

    data class ChatLoadPlan(
        val nextState: ChatDetailUiState,
        val effects: List<ChatLoadEffect>,
        val shouldInvalidateSenderKey: Boolean,
        val revisionWarning: String?,
        val fromCache: Boolean,
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

    /**
     * 决定一次会话加载要写什么状态、触发哪些副作用。
     *
     * @param formatGroupName 群名缺省时的占位文案（资源字符串由调用方提供）
     * @param formatMemberCount 成员数文案，如 "5 人"
     * @param formatRevisionWarning 成员变更警示文案
     */
    fun planChatLoad(
        input: ChatLoadInput,
        currentState: ChatDetailUiState,
        formatGroupName: () -> String,
        formatMemberCount: (Int) -> String,
        formatRevisionWarning: () -> String,
    ): ChatLoadPlan {
        val chat = input.chat
        // 群成员 revision 上升 = 有新成员加入/退出 → 群 Sender Key 必须失效重排。
        // 仅在「有基线」且「严格上升」时成立：不变/下降/首见都不该触发。
        val shouldInvalidateSenderKey = chat.isGroup &&
            input.previousRevision != null &&
            chat.memberRevision > input.previousRevision
        val revisionWarning = if (shouldInvalidateSenderKey) formatRevisionWarning() else null

        val base = currentState.copy(isLoading = false, initialLoadError = null)

        return if (chat.isGroup) {
            val groupContact = User(
                id = chat.id,
                name = chat.groupName ?: formatGroupName(),
                avatar = chat.groupAvatar,
                status = formatMemberCount(chat.participants.size),
            )
            val nextState = base.copy(
                chat = chat,
                chatIsGroup = true,
                isSecretChat = chat.isSecret,
                contact = groupContact,
                // 群会话的阅后即焚设置沿用会话上的值（直聊同样如此）
                disappearingMessageSeconds = chat.disappearingMessageSeconds,
            )
            ChatLoadPlan(
                nextState = nextState,
                effects = listOf(
                    ChatLoadEffect.OccupySessionCipher(chat.id, peerUserId = null),
                    ChatLoadEffect.LoadGroupCandidates,
                    ChatLoadEffect.RefreshBotCommands(chat.id),
                ),
                shouldInvalidateSenderKey = shouldInvalidateSenderKey,
                revisionWarning = revisionWarning,
                fromCache = input.fromCache,
            )
        } else {
            val peer = chat.participants.firstOrNull { it.id != input.currentUserId }
            if (peer == null) {
                // 异常数据（参与者里只有自己）：不安排任何副作用，只收 loading。
                ChatLoadPlan(
                    nextState = base.copy(chat = chat, chatIsGroup = false),
                    effects = emptyList(),
                    shouldInvalidateSenderKey = false,
                    revisionWarning = null,
                    fromCache = input.fromCache,
                )
            } else {
                val nextState = base.copy(
                    chat = chat,
                    chatIsGroup = false,
                    isSecretChat = chat.isSecret,
                    contact = peer,
                    disappearingMessageSeconds = chat.disappearingMessageSeconds,
                )
                val effects = buildList {
                    add(ChatLoadEffect.OccupySessionCipher(chat.id, peer.id))
                    add(ChatLoadEffect.RefreshBlockState(peer.id))
                    add(ChatLoadEffect.RefreshScheduledMessages)
                    if (com.maodouchat.bot.BotCommandPolicy.isBotUserId(peer.id)) {
                        add(ChatLoadEffect.RefreshBotCommands(chat.id))
                    } else {
                        add(ChatLoadEffect.RefreshIdentitySafety(peer.id))
                    }
                }
                ChatLoadPlan(
                    nextState = nextState,
                    effects = effects,
                    shouldInvalidateSenderKey = false,
                    revisionWarning = null,
                    fromCache = input.fromCache,
                )
            }
        }
    }

    /** API 与本地缓存都拿不到会话时的失败态。 */
    fun planLoadFailure(
        currentState: ChatDetailUiState,
        formatError: () -> String,
    ): ChatDetailUiState = currentState.copy(isLoading = false, initialLoadError = formatError())

    /**
     * 「以下为未读消息」分隔线落在哪条消息上。
     *
     * 规则：未读数必须**大于 0 且小于**非控制消息条数，才取「倒数第 unreadCount 条」的首条；
     * 否则返回 null（没有未读、或整屏都是未读时分隔线没有意义）。
     * `SK_DIST` 等控制消息不参与分母——否则用户明明只缺 1 条，分隔线却标到很前面。
     */
    fun unreadSeparatorId(unreadCount: Int, messages: List<Message>): String? {
        if (unreadCount <= 0) return null
        val nonControl = messages.filter { it.type != MessageType.SK_DIST }
        if (unreadCount >= nonControl.size) return null
        return nonControl.takeLast(unreadCount).firstOrNull()?.id
    }
}
