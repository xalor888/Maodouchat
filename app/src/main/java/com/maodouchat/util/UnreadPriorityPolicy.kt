package com.maodouchat.util

/**
 * 未读智能优先（纯本地排序提示）。
 * 不自动已读、不外发、不改服务端；仅影响列表顺序与轻提示。
 */
object UnreadPriorityPolicy {
    /** 手动标未读的加权（高于普通未读，低于置顶） */
    const val WEIGHT_MARKED_UNREAD = 1_000_000_000L

    /** 有未读数时的基础加权 */
    const val WEIGHT_HAS_UNREAD = 500_000_000L

    /** 未读条数上限参与排序，避免异常大值压过时间 */
    const val MAX_UNREAD_FOR_SCORE = 999

    /**
     * 排序键：越大越靠前。
     * 置顶仍由外层 `pinnedAt` 优先；此处只算未读加权 + 活动时间。
     */
    fun activityScore(
        lastMessageTime: Long,
        draftUpdatedAt: Long = 0L,
        unreadCount: Int = 0,
        markedUnread: Boolean = false,
        muted: Boolean = false
    ): Long {
        val activity = maxOf(lastMessageTime, draftUpdatedAt).coerceAtLeast(0L)
        if (muted) {
            // 免打扰未读不抢位，仅按时间
            return activity
        }
        var score = activity
        if (markedUnread) score += WEIGHT_MARKED_UNREAD
        val unread = unreadCount.coerceIn(0, MAX_UNREAD_FOR_SCORE)
        if (unread > 0) {
            score += WEIGHT_HAS_UNREAD + unread
        }
        return score
    }

    /** 是否展示「未读优先」轻提示条（有未读且未搜索时） */
    fun shouldShowHint(
        enabled: Boolean,
        totalUnreadChats: Int,
        isSearching: Boolean
    ): Boolean {
        if (!enabled) return false
        if (isSearching) return false
        return totalUnreadChats > 0
    }

    fun countUnreadChats(
        unreadCounts: List<Int>,
        markedUnreadFlags: List<Boolean>
    ): Int {
        val n = maxOf(unreadCounts.size, markedUnreadFlags.size)
        var count = 0
        for (i in 0 until n) {
            val u = unreadCounts.getOrElse(i) { 0 }
            val m = markedUnreadFlags.getOrElse(i) { false }
            if (u > 0 || m) count++
        }
        return count
    }
}
