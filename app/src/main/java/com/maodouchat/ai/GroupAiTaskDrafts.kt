package com.maodouchat.ai

import com.maodouchat.ai.GroupAiSharePolicy.TaskDraft

// 任务草稿消毒：标题必填、字段截断、最多保留 30 个；空列表不可保存。原逻辑逐字搬入。
internal object GroupAiTaskDrafts {
    const val MAX_TASKS_PER_SAVE = 30
    const val MAX_TASK_TITLE_CHARS = 360

    fun canPersistTasks(tasks: List<TaskDraft>): Boolean = sanitizeTasks(tasks).isNotEmpty()

    fun sanitizeTasks(tasks: List<TaskDraft>): List<TaskDraft> =
        tasks.asSequence()
            .mapNotNull { task ->
                val title = task.title.trim().take(MAX_TASK_TITLE_CHARS)
                if (title.isEmpty()) null
                else TaskDraft(
                    title = title,
                    owner = task.owner?.trim()?.take(100)?.takeIf { it.isNotEmpty() },
                    dueText = task.dueText?.trim()?.take(120)?.takeIf { it.isNotEmpty() },
                    dueAt = task.dueAt?.takeIf { it > 0L }
                )
            }
            .take(MAX_TASKS_PER_SAVE)
            .toList()
}
