package com.maodouchat.domain.messaging

// 已读回执搜索框输入上限：输入截断到 100 字符。
object ReadReceiptQueryPolicy {

    const val MAX_QUERY_LENGTH: Int = 100

    fun filterQuery(value: String): String = value.take(MAX_QUERY_LENGTH)
}
