package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType

/**
 * 「哪些消息算新未读、水印选哪条」的纯判定（G67，从 `ChatDetailViewModel.observeMessageStatus()` 83 行抽出）。
 *
 * **为什么抽它**：这些判定直接决定已读回执的正确性，而判错的后果都不是崩溃，是**静默的数据不一致**：
 * - 把自己的消息也算进未读 → 给自己发回执，服务端记一条无意义的已读；
 * - `SK_DIST` 计入未读 → 密钥分发被误标已读，后续群消息解不开；
 * - seen-set 不幂等 → 同一条消息每次状态变化都重发回执（刷屏 + 服务端压力）；
 * - 水印选错 → 「已读到哪」比实际少，对方永远显示未读。
 *
 * 它此前**一个用例都没有**。本对象是纯函数：无 Coroutine / Room / ApiService / Android 资源。
 */
internal object ChatReadWatermarkPolicy {

    /** 占位 owner：未登录或测试态下 `currentUserId` 会是它，此时绝不能标已读。 */
    const val PLACEHOLDER_OWNER: String = "me"

    data class Input(
        val messages: List<Message>,
        val hasChat: Boolean,
        val isActiveChat: Boolean,
        val ownerUserId: String,
        val sessionMayContinue: Boolean,
    )

    data class Plan(
        /** 本轮要标已读的消息 id（保序）。 */
        val unreadIds: Set<String>,
        /** 水印消息 id —— 「已读到这条为止」。 */
        val watermarkMessageId: String,
        /** 水印消息时间戳 —— 供 `markIncomingReadThrough` 用。 */
        val watermarkTimestamp: Long,
    )

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

    /**
     * 已处理过的消息 id 集合。
     *
     * 用 `LinkedHashSet` 保序，便于测试与日志里稳定断言；容量上界由
     * [PRUNE_THRESHOLD] 控制——长会话里它只增不减会缓慢泄漏。
     */
    class SeenSet {
        private val ids = LinkedHashSet<String>()

        fun contains(id: String): Boolean = id in ids

        fun add(id: String) {
            ids.add(id)
            if (ids.size > PRUNE_THRESHOLD) prune()
        }

        fun addAll(newIds: Collection<String>) {
            ids.addAll(newIds)
            if (ids.size > PRUNE_THRESHOLD) prune()
        }

        fun removeAll(target: Collection<String>) {
            ids.removeAll(target.toSet())
        }

        /** 清空（切号/退出登录时用；比新建实例省一次分配，也避免 val 重新赋值）。 */
        fun clearAll() {
            ids.clear()
        }

        fun isEmpty(): Boolean = ids.isEmpty()

        fun snapshot(): Set<String> = ids.toSet()

        /**
         * 只保留最近一半。已读标记是幂等的，丢掉旧 id 的最坏结果是
         * 「某条极老消息被重复报一次回执」——服务端按幂等键去重，无实质影响；
         * 而不加上界则是确定性内存增长。
         */
        private fun prune() {
            val keep = ids.toList().takeLast(PRUNE_KEEP)
            ids.clear()
            ids.addAll(keep)
        }
    }

    private const val PRUNE_THRESHOLD = 2_000
    private const val PRUNE_KEEP = 1_000
}
