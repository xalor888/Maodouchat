package com.maodouchat.ui.screen.chatdetail

/** 已处理过的消息 id 集合（保序，上界 PRUNE_THRESHOLD，防长会话缓慢增长）。 */
internal class SeenSet {
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

    /** 只保留最近一半：已读幂等，丢旧 id 最坏只是多报一次（服务端去重）。 */
    private fun prune() {
        val keep = ids.toList().takeLast(PRUNE_KEEP)
        ids.clear()
        ids.addAll(keep)
    }
}

private const val PRUNE_THRESHOLD = 2_000
private const val PRUNE_KEEP = 1_000
