package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.component.ChatMarkdown

/**
 * 发送/重试的**准入判定**（G66，从 `ChatDetailViewModel.sendMessage()` 90 行与
 * `retrySendMessage()` 68 行里抽出共用的守卫逻辑）。
 *
 * **为什么抽它**：这些守卫此前一个用例都没有，而每一条都对应一种真实的用户可见故障——
 * 空文本发出空气泡、未登录/被屏蔽时请求白跑一圈才报错、普通成员能 @所有人、
 * 重试能把**别人**的失败消息重发一遍。它们还散在两处，改一处忘另一处就会分叉。
 *
 * **设计约束**：纯对象，不碰 Coroutine / Room / ApiService / Android 资源。
 * 需要资源的文案由调用方按 `reason` 自己映射，测试因此完全确定。
 */
internal object ChatSendGuard {

    /** 输入框硬上限（与服务端限制对齐，超长在前端就截断，不让请求白跑）。 */
    const val MAX_TEXT_LENGTH: Int = 4_000

    /** 能走「文本重试」路径的消息类型。附件型另走附件重试。 */
    private val TEXT_RETRY_TYPES = setOf(
        MessageType.TEXT,
        MessageType.MARKDOWN,
        MessageType.STICKER,
        MessageType.LOCATION,
        MessageType.NUDGE,
    )

    enum class SendRejectReason {
        ALREADY_SENDING,
        BLANK,
        NO_SESSION,
        BLOCKED,
        MENTION_EVERYONE_FORBIDDEN,
    }

    enum class RetryRejectReason {
        NOT_FOUND,
        NOT_MY_MESSAGE,
        NOT_FAILED,
        NO_SESSION,
        UNSUPPORTED_TYPE,
    }

    sealed interface SendDecision {
        /** 放行，附带截断后的文本、组装好的 meta，以及调用方要做的 UI 动作。 */
        data class Allow(
            val text: String,
            val meta: MessageMeta,
            val messageType: MessageType,
            val clearDraft: Boolean,
            val consumeSilent: Boolean,
        ) : SendDecision

        data class Reject(val reason: SendRejectReason) : SendDecision
    }

    sealed interface RetryDecision {
        data class Allow(val message: Message) : RetryDecision
        /** 附件型失败消息：必须走附件重试，不能当文本重发。 */
        data class NeedsAttachmentRetry(val message: Message) : RetryDecision
        data class Reject(val reason: RetryRejectReason) : RetryDecision
    }

    /**
     * 发送准入。
     *
     * @param mentionsEnabled 功能开关：关着时完全不提取 mention，也不做 @所有人 权限判定
     * @param replyTarget 被回复的消息（仅取其 id）
     * @param forcedMeta 非空时原样采用（定时消息等路径自带 meta）
     */
    fun checkSend(
        state: ChatDetailUiState,
        ownerUserId: String,
        token: String,
        silent: Boolean?,
        forceText: String?,
        mentionsEnabled: Boolean,
        replyTarget: Message? = null,
        forcedMeta: MessageMeta? = null,
    ): SendDecision {
        // 重入保护必须最先：快速双击在 isSending 置位前的同帧窗口会各生成不同 msgId，
        // 服务端按 requestedId 去重拦不住，于是出现重复可见气泡。
        if (state.isSending) return SendDecision.Reject(SendRejectReason.ALREADY_SENDING)

        val rawText = (forceText ?: state.inputText).trim()
        val text = if (rawText.length > MAX_TEXT_LENGTH) rawText.take(MAX_TEXT_LENGTH) else rawText
        if (text.isBlank()) return SendDecision.Reject(SendRejectReason.BLANK)

        if (token.isBlank() || ownerUserId.isBlank()) {
            return SendDecision.Reject(SendRejectReason.NO_SESSION)
        }
        if (state.isContactBlocked) return SendDecision.Reject(SendRejectReason.BLOCKED)

        val isGroup = state.chat?.isGroup == true
        val wantSilent = silent ?: state.silentSend
        val participants = state.chat?.participants.orEmpty()

        val extractedMentions = if (isGroup && mentionsEnabled) {
            MentionPolicy.extractMentionIds(text, participants, ownerUserId)
        } else {
            emptyList()
        }
        val canMentionEveryone = !isGroup || run {
            // 1.45：角色未加载（null）时 fail-open，避免刚进群的管理员在成员刷新前被误拦
            val role = state.myMemberRole?.uppercase()
            role == null || role == "OWNER" || role == "ADMIN"
        }
        if (isGroup && extractedMentions.contains(MentionPolicy.EVERYONE_ID) && !canMentionEveryone) {
            return SendDecision.Reject(SendRejectReason.MENTION_EVERYONE_FORBIDDEN)
        }

        val looksMd = ChatMarkdown.looksLikeMarkdown(text)
        val meta = forcedMeta ?: MessageMeta(
            mentions = extractedMentions,
            replyToId = replyTarget?.id,
            markdown = looksMd,
            silent = wantSilent,
        )
        val messageType = if (looksMd) MessageType.MARKDOWN else MessageType.TEXT

        return SendDecision.Allow(
            text = text,
            meta = meta,
            messageType = messageType,
            // 1.168：forceText（定时消息立即发送）不读取输入框、不扰动用户草稿
            clearDraft = forceText == null,
            // 只有真的用上了粘性 silentSend 才需要一次性消费它
            consumeSilent = silent == null && state.silentSend,
        )
    }

    /** 重试准入：只放行「我自己的、失败的、类型可文本重发」的消息。 */
    fun checkRetry(
        state: ChatDetailUiState,
        messageId: String,
        ownerUserId: String,
        token: String,
    ): RetryDecision {
        val failed = state.messages.find { it.id == messageId }
            ?: return RetryDecision.Reject(RetryRejectReason.NOT_FOUND)
        if (failed.senderId != ownerUserId) {
            return RetryDecision.Reject(RetryRejectReason.NOT_MY_MESSAGE)
        }
        if (failed.status != MessageStatus.FAILED) {
            return RetryDecision.Reject(RetryRejectReason.NOT_FAILED)
        }
        if (token.isBlank() || ownerUserId.isBlank()) {
            return RetryDecision.Reject(RetryRejectReason.NO_SESSION)
        }
        if (failed.type in RELIABLE_ATTACHMENT_TYPES) {
            return RetryDecision.NeedsAttachmentRetry(failed)
        }
        if (failed.type !in TEXT_RETRY_TYPES) {
            return RetryDecision.Reject(RetryRejectReason.UNSUPPORTED_TYPE)
        }
        return RetryDecision.Allow(failed)
    }

}

