package com.maodouchat.domain.messaging

// 按 id 合并去重：两段列表相加后按 id 去重、保留首次出现；
// 星标回灌、动态加载更多、评论加载更早三处同构的 distinctBy。
object DistinctMergePolicy {

    fun <T> mergeById(existing: List<T>, incoming: List<T>, idOf: (T) -> String): List<T> =
        (existing + incoming).distinctBy(idOf)
}
