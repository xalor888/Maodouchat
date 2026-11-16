package com.maodouchat.domain.messaging

// AI 任务搜索：标题、负责人、截止文案、来源问题任一含查询词即命中。
// 自 AiTasksScreen 的 visibleTasks 收敛；AiTaskEntity 是 app 侧 Room 实体，
// 策略只收字符串字段，不依赖 app 类型。
object AiTaskSearchPolicy {

    fun matches(
        title: String,
        owner: String?,
        dueText: String?,
        sourceQuery: String,
        query: String
    ): Boolean =
        title.contains(query, ignoreCase = true) ||
            owner.orEmpty().contains(query, ignoreCase = true) ||
            dueText.orEmpty().contains(query, ignoreCase = true) ||
            sourceQuery.contains(query, ignoreCase = true)
}
