package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import android.util.Log
import com.maodouchat.R
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.messaging.v2.OutgoingMessageCommand
import com.maodouchat.messaging.v2.OutgoingMessageResult
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 发送流水线（文本/富文本主发送 + 静默发送开关）：准入判定、乐观上屏、V2 出站编排
// 从 ChatDetailViewModel 纯搬移，日志 tag 保持 ChatDetailViewModel 不变。
internal class ChatComposerSendController(
    private val scope: CoroutineScope,
    private val getApplication: () -> Application,
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
) {
    private fun text(id: Int, vararg args: Any): String = textProvider(id, args)

    fun toggleSilentSend() {
        val next = !currentState().silentSend
        if (next && !requireSilentSend()) return
        updateState { it.copy(silentSend = next) }
    }

    /**
     * Primary composer send for 1:1 and groups (text / markdown).
     * [replyTarget] embeds replyToId into E2EE MessageMeta.
     * [silent] overrides composer silentSend when non-null.
     */
    internal fun sendMessage(
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
            mentionsEnabled = RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.MENTIONS),
            replyTarget = replyTarget,
            forcedMeta = forcedMeta,
        )
        if (decision is ChatSendGuard.SendDecision.Reject) {
            updateState { it.copy(groupEncryptionWarning = sendRejectMessage(decision.reason)) }
            return false
        }
        val allowed = decision as ChatSendGuard.SendDecision.Allow
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
            val result = withContext(Dispatchers.IO) {
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
                                com.maodouchat.session.CurrentSession.snapshot().token.orEmpty(),
                                conversation.conversationId,
                                plaintext,
                                conversation.isGroup,
                                conversation.peerUserId.orEmpty(),
                            )
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.w("ChatDetailViewModel", "bot inbox follow-up failed", error)
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
                                ?: text(R.string.chat_send_failed),
                        )
                    }
                    Log.w(
                        "ChatDetailViewModel",
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
    private fun sendRejectMessage(reason: ChatSendGuard.SendRejectReason): String = when (reason) {
        ChatSendGuard.SendRejectReason.ALREADY_SENDING -> text(R.string.chat_send_in_flight)
        ChatSendGuard.SendRejectReason.BLANK -> text(R.string.chat_send_empty)
        ChatSendGuard.SendRejectReason.NO_SESSION -> text(R.string.error_session_expired)
        ChatSendGuard.SendRejectReason.BLOCKED ->
            text(R.string.chat_blocked_user_status, currentState().contact.displayName)
        ChatSendGuard.SendRejectReason.MENTION_EVERYONE_FORBIDDEN ->
            text(R.string.chat_mention_everyone_restricted)
    }
}
