package com.maodouchat.ui.screen.chatdetail

/**
 * Read-receipt visibility: sender always; group OWNER/ADMIN may inspect any message.
 * Other participants must not open the member list.
 */
object ReadReceiptPolicy {

    fun isPrivilegedGroupRole(role: String?): Boolean {
        val normalized = role?.trim()?.uppercase().orEmpty()
        return normalized == "OWNER" || normalized == "ADMIN"
    }

    fun canViewReceipts(
        viewerId: String,
        senderId: String,
        isGroup: Boolean,
        viewerRole: String?,
    ): Boolean {
        if (viewerId.isBlank() || senderId.isBlank()) return false
        if (viewerId == senderId) return true
        return isGroup && isPrivilegedGroupRole(viewerRole)
    }

    fun shouldShowGroupReadCount(
        isGroup: Boolean,
        isOwnMessage: Boolean,
        viewerRole: String?,
    ): Boolean = isGroup && (isOwnMessage || isPrivilegedGroupRole(viewerRole))

    /**
     * 群已读 X/Y：Y 是除自己外的在群成员，X 是其中已留下回执的人数。
     * 服务端 GET read-receipts 只返回已读者，不能用 receipts.size 当分子去跟成员表混算。
     */
    fun computeGroupReadCount(
        viewerId: String,
        memberIds: List<String>,
        receiptUserIds: Collection<String>,
    ): Pair<Int, Int> {
        val viewer = viewerId.trim()
        val others = memberIds.map { it.trim() }.filter { it.isNotEmpty() && it != viewer }.distinct()
        val readSet = receiptUserIds.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
        if (others.isEmpty()) {
            val fallback = readSet.filter { it != viewer }
            return fallback.size to fallback.size
        }
        return others.count { it in readSet } to others.size
    }

    /**
     * 打开群聊时预拉「已读 X/Y」的消息 id。只取自己已送达的普通消息，最近的优先，条数封顶。
     * 不预拉则更早的自己消息永远没有计数，气泡下不会出现 X/Y。
     */
    fun outgoingMessageIdsForGroupReadPrefetch(
        viewerId: String,
        messagesNewestLast: List<PrefetchMessage>,
        max: Int = 8,
    ): List<String> {
        if (viewerId.isBlank() || max <= 0) return emptyList()
        val viewer = viewerId.trim()
        return messagesNewestLast.asReversed()
            .asSequence()
            .filter { it.senderId == viewer && it.id.isNotBlank() && it.eligibleForGroupReadCount }
            .map { it.id }
            .distinct()
            .take(max)
            .toList()
    }

    /**
     * 群聊的一人已读不等于全员已读；发送方按本地 v2 回执快照聚合展示。
     * 刷新尚未读满的自己消息，以及发送时还是 SENDING、从未预拉过的气泡。
     */
    fun incompleteGroupReadMessageIds(
        viewerId: String,
        messagesNewestLast: List<PrefetchMessage>,
        counts: Map<String, ReadCountUi>,
        max: Int = 4,
    ): List<String> {
        if (viewerId.isBlank() || max <= 0) return emptyList()
        return outgoingMessageIdsForGroupReadPrefetch(viewerId, messagesNewestLast, max = max)
            .filter { id ->
                val count = counts[id]
                count == null || (count.total > 0 && count.read < count.total)
            }
    }

    data class PrefetchMessage(
        val id: String,
        val senderId: String,
        val eligibleForGroupReadCount: Boolean,
    )
}
