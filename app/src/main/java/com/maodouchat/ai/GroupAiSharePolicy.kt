package com.maodouchat.ai

/**
 * Group AI assistant share / identity rules (W4-03).
 * Answers stay private until the user explicitly confirms share; shared body is always
 * the current user's message with [aiAssisted] meta — never a synthetic system identity.
 */
object GroupAiSharePolicy {
    const val MAX_SHARE_CHARS = GroupAiShareDecision.MAX_SHARE_CHARS
    const val MAX_TASKS_PER_SAVE = GroupAiTaskDrafts.MAX_TASKS_PER_SAVE
    const val MAX_TASK_TITLE_CHARS = GroupAiTaskDrafts.MAX_TASK_TITLE_CHARS

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
    ): ShareDecision =
        GroupAiShareDecision.decideShare(isGroup, answer, alreadyShared)

    /** Shared message always belongs to the local user; meta marks AI assist only. */
    fun shareAsCurrentUserMeta(mode: String?): Map<String, Any?> =
        GroupAiShareMeta.shareAsCurrentUserMeta(mode)

    fun shareAiAssistedFlag(): Boolean = GroupAiShareMeta.shareAiAssistedFlag()

    fun shareAssistantMode(mode: String?): String? =
        GroupAiShareMeta.shareAssistantMode(mode)

    /** 任务仅在用户点「保存」后落库；空列表不可保存。 */
    fun canPersistTasks(tasks: List<TaskDraft>): Boolean =
        GroupAiTaskDrafts.canPersistTasks(tasks)

    /**
     * 闭环是否可演示：确认分享路径 + 非系统身份 meta + 本地任务可保存（勿扰在提醒层单独门控）。
     */
    fun isClosedLoopReady(
        isGroup: Boolean,
        answer: String?,
        tasks: List<TaskDraft> = emptyList(),
        alreadyShared: Boolean = false
    ): Boolean =
        GroupAiClosedLoopReadiness.isClosedLoopReady(isGroup, answer, tasks, alreadyShared)

    fun sanitizeTasks(tasks: List<TaskDraft>): List<TaskDraft> =
        GroupAiTaskDrafts.sanitizeTasks(tasks)
}
