package com.maodouchat.domain.messaging

/**
 * 全局搜索索引过期判定（8.31 F18 快速路径）：索引非空且文档数与可搜索消息数偏差在阈值内
 * 时跳过全量重建。hasMessageTypeMismatch 只在漂移小于阈值时才查，保持原调用顺序。
 */
object SearchIndexStalePolicy {
    /** 文档数与消息数偏差超过此比例或最小绝对值即触发全量重建。 */
    const val STALE_DRIFT_RATIO = 0.1
    const val MIN_STALE_DRIFT = 50

    // 类型失配查询是 suspend 的 DAO 调用，判定函数本身也必须是 suspend，
    // 调用方（MessageSearchRepository.refreshIndexIfStale）本就在协程内。
    suspend fun shouldRefreshIndex(
        docCount: Int,
        msgCount: Int,
        hasMessageTypeMismatch: suspend () -> Boolean,
    ): Boolean {
        if (msgCount == 0 && docCount == 0) return false
        if (docCount > 0) {
            val drift = kotlin.math.abs(msgCount - docCount)
            if (drift < (msgCount.toDouble() * STALE_DRIFT_RATIO).toInt().coerceAtLeast(MIN_STALE_DRIFT)) {
                if (!hasMessageTypeMismatch()) return false
            }
        }
        return true
    }
}
