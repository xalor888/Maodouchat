package com.maodouchat.ai

import com.maodouchat.ai.GroupAiSharePolicy.TaskDraft

// 闭环可演示判定：确认分享路径 + 非系统身份 meta + 本地任务可保存（勿扰在提醒层单独门控）。原逻辑逐字搬入。
internal object GroupAiClosedLoopReadiness {
    fun isClosedLoopReady(
        isGroup: Boolean,
        answer: String?,
        tasks: List<TaskDraft> = emptyList(),
        alreadyShared: Boolean = false
    ): Boolean {
        val share = GroupAiShareDecision.decideShare(isGroup, answer, alreadyShared)
        if (!share.allowed) return false
        val meta = GroupAiShareMeta.shareAsCurrentUserMeta("answer")
        if (meta["systemIdentity"] != false) return false
        if (meta["aiAssisted"] != true) return false
        if (tasks.isNotEmpty() && !GroupAiTaskDrafts.canPersistTasks(tasks)) return false
        return true
    }
}
