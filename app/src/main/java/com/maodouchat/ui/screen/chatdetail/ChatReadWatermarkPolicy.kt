package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType

/** 已读回执判定：哪些消息算新未读、水印选哪条。纯函数，无协程/Room/网络。 */
internal object ChatReadWatermarkPolicy {

    /** 占位 owner：未登录或测试态下 `currentUserId` 会是它，此时绝不能标已读。 */
    const val PLACEHOLDER_OWNER: String = "me"

    /**
     * 决定本轮要不要标已读、标哪些、水印是哪条。
     *
     * 返回 null 表示「本轮整体跳过」——调用方不应改任何状态，也不该写 seen-set
     * （否则下次真的该报时反而被自己挡掉）。
     */
    fun plan(input: Input, seen: SeenSet): Plan? {
        // 没有会话 / 不在这个会话 / owner 不可用 / 会话门禁不通过 → 一律整体跳过。
        // 放在最前面且**不碰 seen**：这些是「暂时不该报」，不是「已经报过」。
        if (!input.hasChat) return null
        if (!input.isActiveChat) return null
        if (input.ownerUserId.isBlank() || input.ownerUserId == PLACEHOLDER_OWNER) return null
        if (!input.sessionMayContinue) return null

        val unread = input.messages.filter { message ->
            message.senderId != input.ownerUserId &&
                message.type != MessageType.SK_DIST &&
                message.status != MessageStatus.READ &&
                !seen.contains(message.id)
        }
        if (unread.isEmpty()) return null

        // 水印：timestamp 优先，id 兜底。同 timestamp 时必须按 id 稳定决胜，
        // 否则水印会随列表顺序抖动，「已读到哪」在两次运行之间不一致。
        val watermark = unread.maxWithOrNull(
            compareBy<Message> { it.timestamp }.thenBy { it.id },
        ) ?: return null

        val unreadIds = unread.mapTo(linkedSetOf()) { it.id }
        // 只有真正形成计划才记账：跳过的路径绝不污染 seen。
        seen.addAll(unreadIds)

        return Plan(
            unreadIds = unreadIds,
            watermarkMessageId = watermark.id,
            watermarkTimestamp = watermark.timestamp,
        )
    }

    /**
     * 回执入队失败时回滚，让下一轮重试。
     *
     * 必须做两件事：把这批 id 从 seen 里撤掉（否则下次状态不再变化就永远不会重试），
     * 并让调用方把 `lastMessagesSeen` 置空（否则 `onEach` 的去重短路会直接返回）。
     */
    fun rollbackAfterFailure(seen: SeenSet, plan: Plan) {
        seen.removeAll(plan.unreadIds)
    }

}
