package com.maodouchat.core.model

/**
 * 历史页装载后的收尾判定：未读分隔线、密聊不发回执、回执带不带 groupRevision。
 * 纯函数，不碰 Coroutine / Room / ApiService / Android 资源。
 */
object ChatHistoryLoadPolicy {

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
        val separatorId = unreadSeparatorId(unreadCount, messages)

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
