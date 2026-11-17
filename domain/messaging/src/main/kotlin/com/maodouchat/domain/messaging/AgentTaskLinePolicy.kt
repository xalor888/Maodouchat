package com.maodouchat.domain.messaging

// 待办任务列表行格式，自 app 侧 ai/agent 任务读簇收敛；
// 查库与屏蔽过滤仍在 app 侧，这里只定行文本。
object AgentTaskLinePolicy {

    fun taskLine(id: String, chatId: String, title: String, isCompleted: Boolean, dueText: String?): String =
        "$id\t$chatId\t$title\tcompleted=$isCompleted\tdue=${dueText.orEmpty()}"
}
