package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ReadReceiptUi

// 已读回执搜索：名字、id 任一含查询词即命中；始终未读优先（readAt==null 排前）。
// 自 ChatDetailReadReceiptsSheet 的 filteredReadReceipts 收敛。
object ReadReceiptFilterPolicy {

    fun matches(receipt: ReadReceiptUi, query: String): Boolean =
        receipt.name.contains(query, ignoreCase = true) ||
            receipt.userId.contains(query, ignoreCase = true)

    fun filter(receipts: List<ReadReceiptUi>, query: String): List<ReadReceiptUi> {
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) receipts
        else receipts.filter { matches(it, trimmed) }
        return filtered.sortedBy { it.readAt != null }
    }
}
