package com.maodouchat.domain.messaging

// 草稿列表行格式，自 app 侧 ai/agent 任务读簇收敛；
// 查库与门闩仍在 app 侧，这里只定行文本与截断。
object AgentDraftLinePolicy {

    fun draftLine(chatId: String, text: String): String =
        "$chatId\t${AgentTaskReadLimitPolicy.draftText(text)}"
}
