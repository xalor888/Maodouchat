package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType

/**
 * 历史页装载后的收尾判定（G70，从 `ChatDetailViewModel.loadChat()` 的 IO 块里抽出）。
 *
 * **为什么抽它**：这段判定决定三件用户可见的事，而它此前**一个用例都没有**：
 * - 未读分隔线标在哪（标错 → 用户以为看全了，实际漏看）；
 * - 密聊**绝不**发已读回执（发了 → 对方知道你在看，密聊的隐私承诺破功）；
 * - 回执带不带 `groupRevision`（不带 → 服务端按错误 epoch 记账）。
 *
 * 本对象是纯函数：无 Coroutine / Room / ApiService / Android 资源。
 */
internal object ChatHistoryLoadPolicy {

    data class HistoryLoadPlan(
        /** 「以下为未读消息」分隔线落在哪条；null 表示不画。 */
        val unreadSeparatorId: String?,
        /** 是否武装阅后即焚（密聊）。 */
        val armSecretDisappearing: Boolean,
        /** 是否入队已读回执。 */
        val enqueueReadReceipt: Boolean,
        /** 回执的「已读到这条为止」。 */
        val readReceiptThroughMessageId: String?,
        /** 回执带的群 revision；直聊或缺失时为 null。 */
        val readReceiptGroupRevision: Long?,
    )

    /**
     * @param readBoundaryMessageId 最新一条**来电**消息 id（`getLatestIncomingMessage`）；
     *        没有来电消息时为 null，此时不得发回执
     * @param groupRevision 群成员 revision；直聊传 null
     */
    fun planHistoryLoad(
        messages: List<Message>,
        unreadCount: Int,
        isSecretChat: Boolean,
        readBoundaryMessageId: String?,
        groupRevision: Long?,
    ): HistoryLoadPlan {
        val separatorId = ChatDetailLoadCoordinator.unreadSeparatorId(unreadCount, messages)

        // 密聊：只武装阅后即焚，绝不发回执。这条优先于一切「有未读就发」的便利判断。
        if (isSecretChat) {
            return HistoryLoadPlan(
                unreadSeparatorId = separatorId,
                armSecretDisappearing = true,
                enqueueReadReceipt = false,
                readReceiptThroughMessageId = null,
                readReceiptGroupRevision = null,
            )
        }

        // 非密聊：有未读、且拿得到读边界，才发回执。
        val shouldReceipt = unreadCount > 0 && readBoundaryMessageId != null
        return HistoryLoadPlan(
            unreadSeparatorId = separatorId,
            armSecretDisappearing = false,
            enqueueReadReceipt = shouldReceipt,
            readReceiptThroughMessageId = readBoundaryMessageId.takeIf { shouldReceipt },
            readReceiptGroupRevision = groupRevision.takeIf { shouldReceipt },
        )
    }
}
