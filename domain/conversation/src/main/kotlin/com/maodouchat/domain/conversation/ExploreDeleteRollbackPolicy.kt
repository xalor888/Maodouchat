package com.maodouchat.domain.conversation

// Explore 动态/评论乐观删除失败回滚：把条目插回删除前的下标，越界时钳制到列表两端
// （indexOfFirst 未找到返回 -1 时插到队首，与原行内逻辑一致）。
// 自两处逐字相同的行内恢复收敛：ExplorePostActionsCluster（动态）与
// ExploreCommentController（评论）。
object ExploreDeleteRollbackPolicy {

    fun <T> restore(items: List<T>, item: T, originalIndex: Int): List<T> =
        items.toMutableList().apply { add(originalIndex.coerceIn(0, size), item) }
}
