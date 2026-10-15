package com.maodouchat.ai

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
