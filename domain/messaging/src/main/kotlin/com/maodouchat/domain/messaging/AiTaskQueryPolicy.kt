package com.maodouchat.domain.messaging

// AI 任务搜索框输入上限：输入截断到 160 字符。
object AiTaskQueryPolicy {

    const val MAX_QUERY_LENGTH: Int = 160

    fun filterQuery(value: String): String = value.take(MAX_QUERY_LENGTH)
}
