package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.OutgoingMessageCommand
import com.maodouchat.core.model.OutgoingMessageResult
import com.maodouchat.core.model.SendDecision
import com.maodouchat.core.model.SendRejectReason
import com.maodouchat.core.model.mergeMessageVersions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 发送流水线（文本/富文本主发送 + 静默发送开关）：准入判定、乐观上屏、V2 出站编排。
// Application/CurrentSession/Log/R.string 走端口；ViewModel 只做接线。
class ChatComposerSendController(
    private val scope: CoroutineScope,
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val activeChatId: () -> String,
    private val chatId: () -> String,
    private val currentState: () -> ChatDetailUiState,
    private val updateState: ((ChatDetailUiState) -> ChatDetailUiState) -> Unit,
    private val textProvider: (Int, Array<out Any>) -> String,
    private val requireSilentSend: () -> Boolean,
    private val composeContentWithMeta: (String, MessageMeta) -> String,
    private val mergeMessages: (List<Message>, List<Message>) -> List<Message>,
    private val clearDraft: () -> Unit,
    private val maybeForwardBotInbox: suspend (liveToken: String, chatId: String, plaintext: String, isGroup: Boolean, peerId: String) -> Unit,
    private val outgoingFacade: ChatOutgoingFacade,
    private val mentionsEnabled: () -> Boolean,
    private val logWarning: (String, Throwable?) -> Unit,
    private val sendInFlightRes: Int,
    private val sendEmptyRes: Int,
    private val sessionExpiredRes: Int,
    private val blockedUserStatusRes: Int,
    private val mentionEveryoneRestrictedRes: Int,
    private val sendFailedRes: Int,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private fun text(id: Int, vararg args: Any): String = textProvider(id, args)

    fun toggleSilentSend() {
        val next = !currentState().silentSend
        if (next && !requireSilentSend()) return
        updateState { it.copy(silentSend = next) }
    }

    fun sendMessage(
        replyTarget: Message? = null,
        silent: Boolean? = null,
        forceText: String? = null,
        forcedMeta: MessageMeta? = null,
        onDurableCommit: (() -> Unit)? = null,
        onDurableFailure: (() -> Unit)? = null,
    ): Boolean {
        // G66：准入判定与 meta 组装全部下沉到纯函数 ChatSendGuard（可单测），这里只做副作用。
        val sendOwnerUserId = ownerUserId()
        val decision = ChatSendGuard.checkSend(
            state = currentState(),
            ownerUserId = sendOwnerUserId,
            token = token(),
            silent = silent,
            forceText = forceText,
            mentionsEnabled = mentionsEnabled(),
            replyTarget = replyTarget,
            forcedMeta = forcedMeta,
        )
        if (decision is SendDecision.Reject) {
            updateState { it.copy(groupEncryptionWarning = sendRejectMessage(decision.reason)) }
            return false
        }
        val allowed = decision as SendDecision.Allow
        // G68：待发意图统一走工厂（chatId 回落、id 生成、SENDING 初态都在那里）
        val optimistic = ChatSendIntentFactory.build(
            chatId = ChatSendIntentFactory.effectiveChatId(activeChatId(), chatId()),
            senderId = sendOwnerUserId,
            messageId = ChatSendIntentFactory.newMessageId(),
            timestamp = System.currentTimeMillis(),
            content = composeContentWithMeta(allowed.text, allowed.meta),
            type = allowed.messageType,
            meta = allowed.meta,
        )
        if (allowed.clearDraft) clearDraft()
        updateState {
            it.copy(
                messages = mergeMessageVersions(it.messages, listOf(optimistic)),
                inputText = if (allowed.clearDraft) "" else it.inputText,
                groupEncryptionWarning = null,
                isSending = true,
                silentSend = if (allowed.consumeSilent) false else it.silentSend
            )
        }
        scope.launch {
            enqueueTextViaMessagingV2(
                optimistic = optimistic,
                contentWithMeta = optimistic.content,
                plaintext = allowed.text,
                onDurableCommit = onDurableCommit,
                onDurableFailure = onDurableFailure,
            )
        }
        return true
    }

    private suspend fun enqueueTextViaMessagingV2(
        optimistic: Message,
        contentWithMeta: String,
        plaintext: String,
        onDurableCommit: (() -> Unit)?,
        onDurableFailure: (() -> Unit)?,
    ) {
        try {
            val result = withContext(ioDispatcher) {
                outgoingFacade.enqueue(
                    command = OutgoingMessageCommand(
                        ownerUserId = optimistic.senderId,
                        optimisticMessage = optimistic,
                        body = contentWithMeta,
                        type = optimistic.type,
                    ),
                    onDurableCommit = { onDurableCommit?.invoke() },
                    onDurableFailure = { onDurableFailure?.invoke() },
                    afterDurableCommit = { conversation, _ ->
                        try {
                            // 函数类型值不支持命名参数，按声明顺序传位置参数。
                            maybeForwardBotInbox(
                                token(),
                                conversation.conversationId,
                                plaintext,
                                conversation.isGroup,
                                conversation.peerUserId.orEmpty(),
                            )
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            logWarning("bot inbox follow-up failed", error)
                        }
                    },
                )
            }
            when (result) {
                is OutgoingMessageResult.Staged -> updateState { state ->
                    state.copy(
                        messages = state.messages.map {
                            if (it.id == result.message.id) result.message else it
                        },
                        isSending = false,
                    )
                }
                is OutgoingMessageResult.Failed -> {
                    updateState { state ->
                        state.copy(
                            messages = state.messages.map {
                                if (it.id == result.message.id) result.message else it
                            },
                            isSending = false,
                            groupEncryptionWarning = result.error.message?.take(120)
                                ?: text(sendFailedRes),
                        )
                    }
                    logWarning(
                        "v2 text enqueue failed: ${result.error.message}",
                        result.error,
                    )
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            updateState { it.copy(isSending = false) }
            throw error
        }
    }

    /** G66：把守卫拒绝原因映射成用户可见文案（资源字符串只应出现在编排层）。 */
    private fun sendRejectMessage(reason: SendRejectReason): String = when (reason) {
        SendRejectReason.ALREADY_SENDING -> text(sendInFlightRes)
        SendRejectReason.BLANK -> text(sendEmptyRes)
        SendRejectReason.NO_SESSION -> text(sessionExpiredRes)
        SendRejectReason.BLOCKED ->
            text(blockedUserStatusRes, currentState().contact.displayName)
        SendRejectReason.MENTION_EVERYONE_FORBIDDEN ->
            text(mentionEveryoneRestrictedRes)
    }
}
