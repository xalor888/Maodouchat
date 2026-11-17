package com.maodouchat.domain.messaging

// Agent 任务类读操作的行数上限，自 app 侧 ai/agent 任务读簇收敛；
// 查库与门闩仍在 app 侧，这里只定边界。
object AgentTaskReadLimitPolicy {

    fun missedCallLimit(limit: Int): Int = limit.coerceIn(1, 30)

    fun taskQueryLimit(limit: Int): Int = limit.coerceIn(1, 80)

    fun taskResultLimit(limit: Int): Int = limit.coerceIn(1, 40)

    const val MAX_DRAFT_LIST_ITEMS: Int = 40

    const val MAX_DRAFT_TEXT_CHARS: Int = 160

    fun draftText(text: String): String = text.take(MAX_DRAFT_TEXT_CHARS)
}
