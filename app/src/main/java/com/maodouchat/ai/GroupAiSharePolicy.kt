package com.maodouchat.ai

/**
 * Group AI assistant share / identity rules (W4-03).
 * Answers stay private until the user explicitly confirms share; shared body is always
 * the current user's message with [aiAssisted] meta — never a synthetic system identity.
 */
object GroupAiSharePolicy {
    const val MAX_SHARE_CHARS = 4_000
    const val MAX_TASKS_PER_SAVE = 30
    const val MAX_TASK_TITLE_CHARS = 360

    data class ShareDecision(
        val allowed: Boolean,
        val body: String = "",
        val reason: BlockReason? = null
    )

    enum class BlockReason {
        NOT_GROUP,
        EMPTY_ANSWER,
        ALREADY_SHARED
    }

    data class TaskDraft(
        val title: String,
        val owner: String? = null,
        val dueText: String? = null,
        val dueAt: Long? = null
    )

    fun decideShare(
        isGroup: Boolean,
        answer: String?,
        alreadyShared: Boolean = false
    ): ShareDecision {
        if (!isGroup) return ShareDecision(false, reason = BlockReason.NOT_GROUP)
        if (alreadyShared) return ShareDecision(false, reason = BlockReason.ALREADY_SHARED)
        val body = answer?.trim().orEmpty()
        if (body.isEmpty()) return ShareDecision(false, reason = BlockReason.EMPTY_ANSWER)
        return ShareDecision(true, body = body.take(MAX_SHARE_CHARS))
    }

    /** Shared message always belongs to the local user; meta marks AI assist only. */
    fun shareAsCurrentUserMeta(mode: String?): Map<String, Any?> = mapOf(
        "aiAssisted" to true,
        "aiAssistantMode" to mode?.trim()?.take(40)?.takeIf { it.isNotEmpty() },
        "systemIdentity" to false
    )

    fun shareAiAssistedFlag(): Boolean = true

    fun shareAssistantMode(mode: String?): String? =
        mode?.trim()?.take(40)?.takeIf { it.isNotEmpty() }

    /** 任务仅在用户点「保存」后落库；空列表不可保存。 */
    fun canPersistTasks(tasks: List<TaskDraft>): Boolean = sanitizeTasks(tasks).isNotEmpty()

    /**
     * 闭环是否可演示：确认分享路径 + 非系统身份 meta + 本地任务可保存（勿扰在提醒层单独门控）。
     */
    fun isClosedLoopReady(
        isGroup: Boolean,
        answer: String?,
        tasks: List<TaskDraft> = emptyList(),
        alreadyShared: Boolean = false
    ): Boolean {
        val share = decideShare(isGroup, answer, alreadyShared)
        if (!share.allowed) return false
        val meta = shareAsCurrentUserMeta("answer")
        if (meta["systemIdentity"] != false) return false
        if (meta["aiAssisted"] != true) return false
        if (tasks.isNotEmpty() && !canPersistTasks(tasks)) return false
        return true
    }

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
