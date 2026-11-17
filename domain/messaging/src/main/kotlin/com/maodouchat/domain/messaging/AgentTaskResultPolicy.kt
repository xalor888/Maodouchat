package com.maodouchat.domain.messaging

// Agent 任务写操作的结果文案，自 app 侧 AgentTaskWriteTools 收敛；
// 数据库调用仍留在 app 侧。
object AgentTaskResultPolicy {

    fun created(id: String): String =
        "Created task $id"

    fun completed(taskId: String, completed: Boolean): String =
        "Task $taskId completed=$completed"

    fun deleted(taskId: String): String =
        "Deleted task $taskId"
}
