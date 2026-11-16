package com.maodouchat.domain.messaging

// 媒体中心搜索框输入上限：输入截断到 200 字符。
object MediaCenterQueryPolicy {

    const val MAX_QUERY_LENGTH: Int = 200

    fun filterQuery(value: String): String = value.take(MAX_QUERY_LENGTH)
}
